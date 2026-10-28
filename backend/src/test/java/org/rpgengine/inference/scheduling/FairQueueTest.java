package org.rpgengine.inference.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;

/**
 * The pure selection policy of the fair queue (docs/28 section 9): interactive priority, isolated
 * and ordered role/audience lines, the interactive reservation with one and with several permits,
 * background aging that never reaches the interactive class, fair rotation among campaigns, held
 * permits and the choice of a preemption victim. Several permits are exercised here only: the
 * production setting accepts one.
 */
class FairQueueTest {
    static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");
    static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    static final UUID C = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    long sequence;

    record Item(UUID taskId, FairQueue.Line line, WorkClass workClass, long sequence, Instant enqueuedAt,
            String name) implements FairQueue.Item {
        @Override
        public String toString() {
            return name;
        }
    }

    Item item(String name, UUID campaign, AiRole role, WorkClass workClass, Instant at) {
        Audience audience = role.permits(Audience.PUBLIC) ? Audience.PUBLIC
                : role.permits(Audience.PRIVILEGED) ? Audience.PRIVILEGED : Audience.character("npc-1");
        return new Item(UUID.randomUUID(), new FairQueue.Line(campaign, role, audience), workClass, ++sequence, at,
                name);
    }

    Item interactive(String name, UUID campaign, AiRole role) {
        return item(name, campaign, role, WorkClass.INTERACTIVE, T0);
    }

    Item background(String name, UUID campaign, AiRole role, WorkClass workClass) {
        return item(name, campaign, role, workClass, T0);
    }

    /** Dispatch and immediately finish items one at a time, returning the dispatch order. */
    static List<String> drainSequentially(FairQueue<Item> queue, Instant now) {
        List<String> order = new ArrayList<>();
        for (var next = queue.next(now, true); next.isPresent(); next = queue.next(now, true)) {
            queue.started(next.get());
            order.add(next.get().name());
            queue.finished(next.get());
        }
        return order;
    }

    @Test
    void interactiveWorkAlwaysGoesBeforeOlderBackgroundWork() {
        var queue = new FairQueue<Item>(1, Duration.ofSeconds(60));
        queue.add(item("old-plan", A, AiRole.QUEST_PLANNER, WorkClass.CONTINUITY, T0.minus(Duration.ofHours(1))));
        queue.add(interactive("narration", B, AiRole.NARRATOR));
        assertThat(drainSequentially(queue, T0)).containsExactly("narration", "old-plan");
    }

    @Test
    void campaignsRotateFairlyWithinAClassAndNeverServedCampaignsGoFirst() {
        var queue = new FairQueue<Item>(1, Duration.ofSeconds(60));
        // Campaign A floods the queue on two lines before B and C arrive.
        queue.add(interactive("a-intent-1", A, AiRole.INTERPRETER));
        queue.add(interactive("a-narration-1", A, AiRole.NARRATOR));
        queue.add(interactive("a-intent-2", A, AiRole.INTERPRETER));
        queue.add(interactive("b-narration", B, AiRole.NARRATOR));
        queue.add(interactive("c-narration", C, AiRole.NARRATOR));
        assertThat(drainSequentially(queue, T0))
                .containsExactly("a-intent-1", "b-narration", "c-narration", "a-narration-1", "a-intent-2");
    }

    @Test
    void aLineRunsOneTaskAtATimeInAdmissionOrderEvenAgainstPriority() {
        var queue = new FairQueue<Item>(3, Duration.ofSeconds(60));
        Item speculative = background("plan-speculative", A, AiRole.QUEST_PLANNER, WorkClass.SPECULATIVE);
        Item preparation = background("plan-preparation", A, AiRole.QUEST_PLANNER, WorkClass.PREPARATION);
        queue.add(speculative);
        queue.add(preparation);
        // The higher-priority preparation waits behind the older head of its own line.
        assertThat(queue.next(T0, true)).contains(speculative);
        queue.started(speculative);
        assertThat(queue.next(T0, true)).isEmpty();
        queue.finished(speculative);
        assertThat(queue.next(T0, true)).contains(preparation);
    }

    @Test
    void onePermitStartsBackgroundOnlyWhileNoInteractiveTaskWaits() {
        var queue = new FairQueue<Item>(1, Duration.ofSeconds(60));
        Item narration = interactive("narration", A, AiRole.NARRATOR);
        Item plan = background("plan", B, AiRole.QUEST_PLANNER, WorkClass.PREPARATION);
        queue.add(plan);
        queue.add(narration);
        assertThat(queue.next(T0, true)).contains(narration);
        queue.started(narration);
        assertThat(queue.next(T0, true)).isEmpty();
        queue.finished(narration);
        assertThat(queue.next(T0, true)).contains(plan);
    }

    @Test
    void severalPermitsAlwaysKeepOneForInteractiveWork() {
        var queue = new FairQueue<Item>(3, Duration.ofSeconds(60));
        List<Item> plans = List.of(background("p1", A, AiRole.QUEST_PLANNER, WorkClass.PREPARATION),
                background("p2", B, AiRole.QUEST_PLANNER, WorkClass.PREPARATION),
                background("p3", C, AiRole.QUEST_PLANNER, WorkClass.PREPARATION));
        plans.forEach(queue::add);
        queue.started(queue.next(T0, true).orElseThrow());
        queue.started(queue.next(T0, true).orElseThrow());
        // Two of three permits run background work; the third is reserved.
        assertThat(queue.next(T0, true)).isEmpty();
        Item narration = interactive("narration", A, AiRole.NARRATOR);
        queue.add(narration);
        assertThat(queue.next(T0, true)).contains(narration);
        queue.started(narration);
        assertThat(queue.permitsInUse()).isEqualTo(3);
        assertThat(queue.next(T0, true)).isEmpty();
    }

    @Test
    void backgroundAgingPromotesWaitingWorkButNeverIntoTheInteractiveClass() {
        var queue = new FairQueue<Item>(1, Duration.ofSeconds(60));
        Item oldSpeculative = item("old-speculative", A, AiRole.WORLD_DIRECTOR, WorkClass.SPECULATIVE,
                T0.minus(Duration.ofSeconds(150)));
        Item newPreparation = background("new-preparation", B, AiRole.QUEST_PLANNER, WorkClass.PREPARATION);
        queue.add(newPreparation);
        queue.add(oldSpeculative);
        // Two aging steps take SPECULATIVE (3) to CONTINUITY (1), ahead of PREPARATION (2).
        assertThat(queue.effectiveRank(oldSpeculative, T0)).isEqualTo(WorkClass.CONTINUITY.rank());
        assertThat(queue.effectiveRank(oldSpeculative, T0.plus(Duration.ofDays(1)))).isEqualTo(1);
        assertThat(queue.next(T0, true)).contains(oldSpeculative);
        Item narration = interactive("narration", C, AiRole.NARRATOR);
        queue.add(narration);
        assertThat(queue.next(T0.plus(Duration.ofDays(1)), true)).contains(narration);
        assertThat(queue.effectiveRank(narration, T0.plus(Duration.ofDays(1)))).isZero();
    }

    @Test
    void suspendedBackgroundWorkWaitsWhileInteractiveWorkContinues() {
        var queue = new FairQueue<Item>(1, Duration.ofSeconds(60));
        Item plan = background("plan", A, AiRole.QUEST_PLANNER, WorkClass.PREPARATION);
        queue.add(plan);
        assertThat(queue.next(T0, false)).isEmpty();
        Item narration = interactive("narration", A, AiRole.NARRATOR);
        queue.add(narration);
        assertThat(queue.next(T0, false)).contains(narration);
    }

    @Test
    void aHeldPermitBlocksDispatchUntilReleased() {
        var queue = new FairQueue<Item>(1, Duration.ofSeconds(60));
        Item narration = interactive("narration", A, AiRole.NARRATOR);
        queue.add(narration);
        queue.hold();
        assertThat(queue.next(T0, true)).isEmpty();
        assertThat(queue.preemptionVictim(T0)).isEmpty();
        assertThatThrownBy(queue::hold).isInstanceOf(IllegalStateException.class);
        queue.release();
        assertThat(queue.next(T0, true)).contains(narration);
    }

    @Test
    void preemptionPicksTheLowestPriorityMostRecentBackgroundTaskOnce() {
        var single = new FairQueue<Item>(1, Duration.ofSeconds(60));
        Item plan = background("plan", A, AiRole.QUEST_PLANNER, WorkClass.PREPARATION);
        single.add(plan);
        single.started(single.next(T0, true).orElseThrow());
        assertThat(single.preemptionVictim(T0)).isEmpty();
        single.add(interactive("narration", B, AiRole.NARRATOR));
        assertThat(single.preemptionVictim(T0)).contains(plan);
        single.markPreempting(plan);
        // One preemption in flight at a time; the permit stays in use until the terminal result.
        assertThat(single.preemptionVictim(T0)).isEmpty();
        assertThat(single.next(T0, true)).isEmpty();
        single.finished(plan);
        assertThat(single.next(T0, true)).map(Item::name).contains("narration");

        var several = new FairQueue<Item>(3, Duration.ofSeconds(60));
        Item continuity = background("summary", A, AiRole.SUMMARIZER, WorkClass.CONTINUITY);
        Item speculative = background("director", B, AiRole.WORLD_DIRECTOR, WorkClass.SPECULATIVE);
        Item narration = interactive("narration-a", C, AiRole.NARRATOR);
        several.add(continuity);
        several.add(speculative);
        several.add(narration);
        several.started(several.next(T0, true).orElseThrow());
        several.started(several.next(T0, true).orElseThrow());
        several.started(several.next(T0, true).orElseThrow());
        several.add(interactive("narration-b", B, AiRole.NARRATOR));
        assertThat(several.preemptionVictim(T0)).contains(speculative);
    }

    @Test
    void rejectsInconsistentUse() {
        var queue = new FairQueue<Item>(1, Duration.ofSeconds(60));
        Item narration = interactive("narration", A, AiRole.NARRATOR);
        queue.add(narration);
        assertThatThrownBy(() -> queue.add(narration)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> queue.finished(narration)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(queue::release).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FairQueue<Item>(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FairQueue<Item>(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
