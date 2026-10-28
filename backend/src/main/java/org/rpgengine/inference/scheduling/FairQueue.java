package org.rpgengine.inference.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.SessionScope;

/**
 * The scheduler's pure selection policy (docs/28 section 9): which waiting task gets the next
 * runtime turn permit, and which running background task yields to interactive work. It holds no
 * clock, thread, database or port; {@link InferenceScheduler} calls it from its serialized drain
 * loop, and tests drive it directly, including with more than one permit.
 *
 * <p>Rules, in the order {@link #next} applies them:
 * <ol>
 *   <li><b>Permits.</b> At most {@code maxActive} permits are in use, counting running tasks and
 *       permits {@linkplain #hold() held} for reconciliation of an unconfirmed outcome.</li>
 *   <li><b>Isolated lines.</b> A logical line (campaign, role, audience: one runtime conversation
 *       family) runs at most one task at a time, and only its oldest waiting task is eligible, so
 *       each line's tasks reach its conversation in admission order and never concurrently.</li>
 *   <li><b>Interactive first.</b> An eligible interactive task always wins over background work;
 *       background work never ages into the interactive class.</li>
 *   <li><b>Interactive reservation.</b> With one permit, background work starts only while no
 *       interactive task waits. With more permits, background work holds at most
 *       {@code maxActive - 1} of them, so one is always left for interactive work.</li>
 *   <li><b>Aging.</b> Among background tasks the effective rank improves by one class per
 *       {@code agingStep} waited, never above {@link WorkClass#CONTINUITY}; the lowest effective
 *       rank is served first.</li>
 *   <li><b>Fair rotation.</b> Among the candidates of the chosen rank, the campaign served least
 *       recently in that tier (interactive or background) goes next; a campaign never served goes
 *       before any served one, and ties go to the oldest waiting task. Each campaign therefore gets
 *       at most one dispatch before every other waiting campaign got one.</li>
 * </ol>
 *
 * <p>Preemption ({@link #preemptionVictim}): when an eligible interactive task waits and no permit
 * is free, the lowest-priority running background task (the most recently started among equals)
 * is the victim, unless a preemption is already in flight. The caller interrupts it by exact
 * identity and the permit stays in use until its terminal result arrives.
 *
 * <p>Not thread-safe: the scheduler's drain loop is the only caller.
 *
 * @param <E> scheduled item type
 */
final class FairQueue<E extends FairQueue.Item> {
    /** What the policy needs to know about a scheduled item. */
    interface Item {
        /** @return unique task identity */
        UUID taskId();

        /** @return logical line the task runs in */
        Line line();

        /** @return priority class */
        WorkClass workClass();

        /** @return admission order, unique and increasing */
        long sequence();

        /** @return admission time, the base of background aging */
        Instant enqueuedAt();
    }

    /**
     * One logical role/audience line of a campaign: the conversation family whose tasks must run
     * one at a time and in order. Generation and hashes are deliberately absent: tasks of one line
     * under a newer generation or policy still must not overlap the older line's running task.
     *
     * @param campaignId campaign
     * @param role logical role
     * @param audience disclosure audience
     */
    record Line(UUID campaignId, AiRole role, Audience audience) {
        /** Require every component. */
        Line {
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(audience, "audience");
        }

        /** @return the line of a session scope */
        static Line of(SessionScope scope) {
            return new Line(scope.campaignId(), scope.role(), scope.audience());
        }
    }

    private enum Tier { INTERACTIVE, BACKGROUND }

    private record Served(Tier tier, UUID campaignId) {
    }

    private final int maxActive;
    private final Duration agingStep;
    /** Waiting items by identity, in admission order. */
    private final LinkedHashMap<UUID, E> waiting = new LinkedHashMap<>();
    /** Running items by identity, in start order. */
    private final LinkedHashMap<UUID, E> active = new LinkedHashMap<>();
    private final Map<Line, Integer> busyLines = new HashMap<>();
    private final Set<UUID> preempting = new HashSet<>();
    private final Map<Served, Long> lastServed = new HashMap<>();
    private long dispatches;
    private int held;

    /**
     * @param maxActive permits, 1..{@value SchedulerSettings#MAX_ACTIVE_BOUND}
     * @param agingStep waiting time per background class promotion, positive
     */
    FairQueue(int maxActive, Duration agingStep) {
        if (maxActive < 1 || maxActive > SchedulerSettings.MAX_ACTIVE_BOUND) {
            throw new IllegalArgumentException("maxActive out of range");
        }
        if (agingStep == null || agingStep.isNegative() || agingStep.isZero()) {
            throw new IllegalArgumentException("agingStep must be positive");
        }
        this.maxActive = maxActive;
        this.agingStep = agingStep;
    }

    /** Add a waiting item; its identity must be new. */
    void add(E item) {
        if (waiting.containsKey(item.taskId()) || active.containsKey(item.taskId())) {
            throw new IllegalStateException("task already scheduled");
        }
        waiting.put(item.taskId(), item);
    }

    /** @return true when a waiting item was removed (cancelled, expired or refused before dispatch) */
    boolean remove(E item) {
        return waiting.remove(item.taskId(), item);
    }

    /** @return waiting items in admission order */
    List<E> waiting() {
        return List.copyOf(waiting.values());
    }

    /** @return running items in start order */
    List<E> active() {
        return List.copyOf(active.values());
    }

    /** @return permits in use: running items plus held permits */
    int permitsInUse() {
        return active.size() + held;
    }

    /** @return permits held for reconciliation without a running item */
    int held() {
        return held;
    }

    /** Keep one permit in use without a running item, until {@link #release()}. */
    void hold() {
        if (permitsInUse() >= maxActive) {
            throw new IllegalStateException("no permit to hold");
        }
        held++;
    }

    /** Return one held permit. */
    void release() {
        if (held == 0) {
            throw new IllegalStateException("no held permit");
        }
        held--;
    }

    /**
     * Choose the item to start now.
     *
     * @param now current time, for background aging
     * @param backgroundAllowed false while background work is suspended (account backoff)
     * @return the item to dispatch, or empty when no permit is free or nothing is eligible
     */
    Optional<E> next(Instant now, boolean backgroundAllowed) {
        if (permitsInUse() >= maxActive) {
            return Optional.empty();
        }
        List<E> interactive = new ArrayList<>();
        List<E> background = new ArrayList<>();
        Set<Line> heads = new HashSet<>();
        for (E item : waiting.values()) {
            // Admission order: the first item met for a line is its head; later ones wait behind it.
            if (!heads.add(item.line()) || busyLines.containsKey(item.line())) {
                continue;
            }
            (item.workClass().background() ? background : interactive).add(item);
        }
        if (!interactive.isEmpty()) {
            return Optional.of(rotate(interactive, Tier.INTERACTIVE));
        }
        if (!backgroundAllowed || background.isEmpty() || !backgroundPermitFree()) {
            return Optional.empty();
        }
        int best = background.stream().mapToInt(item -> effectiveRank(item, now)).min().orElseThrow();
        return Optional.of(rotate(background.stream().filter(item -> effectiveRank(item, now) == best).toList(),
                Tier.BACKGROUND));
    }

    /** Move a waiting item to running and record the service for rotation. */
    void started(E item) {
        if (!waiting.remove(item.taskId(), item)) {
            throw new IllegalStateException("task is not waiting");
        }
        if (permitsInUse() >= maxActive) {
            throw new IllegalStateException("no free permit");
        }
        active.put(item.taskId(), item);
        busyLines.merge(item.line(), 1, Integer::sum);
        lastServed.put(new Served(tier(item), item.line().campaignId()), ++dispatches);
    }

    /** Remove a running item whose terminal result has been settled; its permit becomes free. */
    void finished(E item) {
        if (!active.remove(item.taskId(), item)) {
            throw new IllegalStateException("task is not running");
        }
        busyLines.computeIfPresent(item.line(), (line, count) -> count == 1 ? null : count - 1);
        preempting.remove(item.taskId());
    }

    /**
     * @param now current time
     * @return the running background item to interrupt for a waiting interactive one, or empty
     */
    Optional<E> preemptionVictim(Instant now) {
        if (permitsInUse() < maxActive || !preempting.isEmpty()) {
            return Optional.empty();
        }
        Set<Line> heads = new HashSet<>();
        boolean interactiveReady = false;
        for (E item : waiting.values()) {
            if (heads.add(item.line()) && !busyLines.containsKey(item.line()) && !item.workClass().background()) {
                interactiveReady = true;
                break;
            }
        }
        if (!interactiveReady) {
            return Optional.empty();
        }
        List<E> running = new ArrayList<>(active.values());
        E victim = null;
        for (int index = running.size() - 1; index >= 0; index--) {
            // Later starts are met first, so among equal ranks the most recent start wins.
            E item = running.get(index);
            if (item.workClass().background() && (victim == null
                    || effectiveRank(item, now) > effectiveRank(victim, now))) {
                victim = item;
            }
        }
        return Optional.ofNullable(victim);
    }

    /** Record that {@code item} is being interrupted for interactive work. */
    void markPreempting(E item) {
        if (!active.containsKey(item.taskId())) {
            throw new IllegalStateException("task is not running");
        }
        preempting.add(item.taskId());
    }

    /**
     * @param item scheduled item
     * @param now current time
     * @return the item's rank after aging; interactive items always rank 0
     */
    int effectiveRank(E item, Instant now) {
        WorkClass workClass = item.workClass();
        if (!workClass.background()) {
            return workClass.rank();
        }
        long waited = Math.max(0, Duration.between(item.enqueuedAt(), now).toMillis());
        long steps = waited / agingStep.toMillis();
        return (int) Math.max(WorkClass.CONTINUITY.rank(), workClass.rank() - Math.min(steps, WorkClass.values().length));
    }

    /**
     * With several permits background work keeps one free for interactive work. With one permit no
     * extra rule is needed: {@link #next} reaches background candidates only when no interactive
     * task is eligible, and an interactive task can be ineligible only because its line runs a
     * task, which then holds the single permit.
     */
    private boolean backgroundPermitFree() {
        long runningBackground = active.values().stream().filter(item -> item.workClass().background()).count();
        return maxActive == 1 || runningBackground < maxActive - 1;
    }

    /** Least recently served campaign first; never-served campaigns first; then oldest head. */
    private E rotate(List<E> candidates, Tier tier) {
        Comparator<E> order = Comparator
                .<E>comparingLong(item -> lastServed.getOrDefault(new Served(tier, item.line().campaignId()), 0L))
                .thenComparingLong(Item::sequence);
        return candidates.stream().min(order).orElseThrow();
    }

    private static Tier tier(Item item) {
        return item.workClass().background() ? Tier.BACKGROUND : Tier.INTERACTIVE;
    }
}
