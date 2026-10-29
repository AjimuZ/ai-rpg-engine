package org.rpgengine.quests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The pure, deterministic quest transition engine (P10.3). A resolver runs it after the effects of a
 * command are known, over the facts those effects produce, and gets back the ordered {@link Step}s
 * that follow from them. It performs no I/O, reads no clock, rolls no dice and never reads prose, so
 * the same rules, stored state, facts and request always give byte-identical steps.
 *
 * <p><b>Transitions.</b> A player request moves one quest: accept ({@code available -> active}), suspend
 * ({@code active -> suspended}) or resume ({@code suspended -> active}). Everything else is derived:
 * <ul>
 *   <li>an {@code undiscovered} quest becomes {@code available} in the first evaluation where its
 *       {@code availableWhen} predicate holds (always, when it has none); a <em>secret</em> quest then
 *       becomes {@code active} immediately, because no command can address it;</li>
 *   <li>an {@code active} quest marks each of its objectives satisfied, in authored order, when the
 *       objective's predicate holds. Satisfaction is sticky;</li>
 *   <li>an {@code active} quest ends with the first outcome, in authored order, whose predicate holds:
 *       {@code success} and {@code alternative} complete it, {@code failure} fails it. The outcome's
 *       item rewards are granted through {@link World#grant} exactly then.</li>
 * </ul>
 * Suspended quests track nothing, and completed or failed quests never change again, so a quest settles
 * its reward source at most once.
 *
 * <p><b>Cascades.</b> Passes repeat in ascending quest-identity order until nothing changes, so a quest
 * that completes can open or finish another one, and granted rewards count for later predicates, all in
 * one commit. Every transition only moves a quest forward, which bounds the passes; {@link #MAX_ROUNDS}
 * and the budgets below are defensive limits.
 *
 * <p><b>Budgets.</b> A commit carries at most the caller's event budget, and grants at most
 * {@link QuestRules#MAX_REWARD_UNITS} reward units. A transition that would exceed a budget is
 * <em>deferred</em>, not dropped: its predicates still hold, so the next evaluating command settles it.
 */
public final class QuestPlan {
    /** Defensive bound on evaluation passes. */
    public static final int MAX_ROUNDS = 64;

    /** Static engine only. */
    private QuestPlan() { }

    /**
     * Stored state of one quest.
     *
     * @param status current status
     * @param outcomeId the outcome that ended the quest, null while it is not terminal
     * @param rowVersion storage version of the row, or {@link #ABSENT} when the quest has no row
     * @param satisfied objectives satisfied so far
     */
    public record State(QuestStatus status, String outcomeId, long rowVersion, Set<String> satisfied) {
        /** The row version of a quest that has never been stored. */
        public static final long ABSENT = -1;

        /** Freeze the set and check the terminal invariant. */
        public State {
            Objects.requireNonNull(status, "status");
            satisfied = Set.copyOf(satisfied);
            if (status.terminal() != (outcomeId != null)) {
                throw new IllegalArgumentException("exactly a terminal quest records its outcome");
            }
            if (rowVersion < ABSENT) {
                throw new IllegalArgumentException("invalid row version");
            }
        }

        /** @return the state of a quest that has no stored row */
        public static State initial(QuestRules.Quest quest) {
            return new State(quest.initial(), null, ABSENT, Set.of());
        }
    }

    /** What a player may ask of one quest. */
    public enum Action {
        /** {@code available -> active}. */
        ACCEPT,
        /** {@code active -> suspended}. */
        SUSPEND,
        /** {@code suspended -> active}. */
        RESUME
    }

    /**
     * One player request.
     *
     * @param action the transition
     * @param questId canonical quest ID
     */
    public record Request(Action action, String questId) {
        /** Require both parts. */
        public Request {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(questId, "questId");
        }
    }

    /** One derived transition, in the order it happened. */
    public sealed interface Step permits Discovered, Accepted, Suspended, Resumed, ObjectiveSatisfied, Ended {
        /** @return the canonical quest this step belongs to */
        String questId();

        /** @return whether the quest is secret, so the step stays out of public events and receipts */
        boolean secret();
    }

    /**
     * {@code undiscovered -> available}.
     *
     * @param questId canonical quest ID
     * @param secret whether the quest is hidden background content
     */
    public record Discovered(String questId, boolean secret) implements Step { }

    /**
     * {@code available -> active}.
     *
     * @param questId canonical quest ID
     * @param secret whether the quest is hidden background content
     * @param automatic true when the engine accepted a secret quest, false for the player's request
     */
    public record Accepted(String questId, boolean secret, boolean automatic) implements Step { }

    /**
     * {@code active -> suspended}.
     *
     * @param questId canonical quest ID
     * @param secret always false: secret quests cannot be addressed
     */
    public record Suspended(String questId, boolean secret) implements Step { }

    /**
     * {@code suspended -> active}.
     *
     * @param questId canonical quest ID
     * @param secret always false: secret quests cannot be addressed
     */
    public record Resumed(String questId, boolean secret) implements Step { }

    /**
     * An objective became satisfied.
     *
     * @param questId canonical quest ID
     * @param secret whether the quest is hidden background content
     * @param objectiveId the objective
     */
    public record ObjectiveSatisfied(String questId, boolean secret, String objectiveId) implements Step { }

    /**
     * The quest ended and its rewards were granted. A secret quest's ending is public: it is the
     * moment the player learns of it.
     *
     * @param questId canonical quest ID
     * @param secret whether the quest was hidden background content
     * @param outcome the selected outcome, including the rewards it settles
     */
    public record Ended(String questId, boolean secret, QuestRules.Outcome outcome) implements Step { }

    /**
     * The facts a plan reads and the only place it writes: granting rewards. The implementation must
     * make granted units visible to later {@link #possessed} calls of the same evaluation.
     */
    public interface World {
        /** @return whether the player has been at the canonical location */
        boolean visited(String location);

        /** @return units of the item the player holds, including units granted earlier in this evaluation */
        long possessed(String item);

        /**
         * Hand reward units to the player.
         *
         * @param item canonical item definition ID
         * @param quantity units, at least one
         */
        void grant(String item, int quantity);
    }

    /**
     * Outcome of one evaluation.
     *
     * @param steps the transitions, in order
     * @param after the state of every quest the evaluation touched (changed quests only)
     * @param deferred true when a budget stopped a transition that is still due
     */
    public record Result(List<Step> steps, Map<String, State> after, boolean deferred) {
        /** Freeze the parts. */
        public Result {
            steps = List.copyOf(steps);
            after = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(after));
        }
    }

    /** Mutable working state of one quest. */
    private static final class Work {
        QuestStatus status;
        String outcomeId;
        final Set<String> satisfied;
        final QuestRules.Quest quest;
        boolean changed;

        /** Copy a stored (or initial) state. */
        Work(QuestRules.Quest quest, State state) {
            this.quest = quest;
            status = state.status();
            outcomeId = state.outcomeId();
            satisfied = new LinkedHashSet<>(state.satisfied());
        }
    }

    /**
     * Evaluate the quests after a command's effects.
     *
     * @param rules pinned quest rules
     * @param stored stored state by canonical quest ID; a quest without an entry has its initial status
     * @param world committed facts after the command's effects, and the reward sink
     * @param request the player's request, or null when the command is not a quest command
     * @param eventBudget how many events the steps may add (each step costs one, an ending adds one per
     *        rewarded definition)
     * @return the ordered steps and the touched quests' final state
     * @throws IllegalStateException if the request contradicts the stored state, which the rule gate
     *         must have refused before resolution
     */
    public static Result evaluate(QuestRules rules, Map<String, State> stored, World world, Request request,
            int eventBudget) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(world, "world");
        var work = new LinkedHashMap<String, Work>();
        rules.quests().forEach((id, quest) -> work.put(id, new Work(quest, stored.getOrDefault(id,
                State.initial(quest)))));
        var run = new Run(work, world, eventBudget);
        if (request != null) {
            run.request(request);
        }
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (!run.pass()) {
                break;
            }
        }
        var after = new LinkedHashMap<String, State>();
        work.forEach((id, quest) -> {
            if (quest.changed) {
                long version = stored.containsKey(id) ? stored.get(id).rowVersion() : State.ABSENT;
                after.put(id, new State(quest.status, quest.outcomeId, version, quest.satisfied));
            }
        });
        return new Result(run.steps, after, run.deferred);
    }

    /** Single-use evaluation state; also the {@link QuestPredicate.Facts} the predicates read. */
    private static final class Run implements QuestPredicate.Facts {
        final Map<String, Work> work;
        final World world;
        final List<Step> steps = new ArrayList<>();
        int budget;
        int rewardUnits;
        boolean deferred;

        /** Start an evaluation with a fresh budget. */
        Run(Map<String, Work> work, World world, int eventBudget) {
            this.work = work;
            this.world = world;
            this.budget = eventBudget;
        }

        @Override public boolean visited(String location) { return world.visited(location); }

        @Override public long possessed(String item) { return world.possessed(item); }

        @Override
        public QuestStatus status(String quest) {
            Work found = work.get(quest);
            return found == null ? QuestStatus.UNDISCOVERED : found.status;
        }

        @Override
        public boolean satisfied(String quest, String objective) {
            Work found = work.get(quest);
            return found != null && found.satisfied.contains(objective);
        }

        /** Apply the player's request to its quest; the rule gate proved it legal. */
        void request(Request request) {
            Work target = work.get(request.questId());
            if (target == null || target.quest.secret()) {
                throw new IllegalStateException("validated quest request names no addressable quest");
            }
            var needed = switch (request.action()) {
                case ACCEPT -> QuestStatus.AVAILABLE;
                case SUSPEND -> QuestStatus.ACTIVE;
                case RESUME -> QuestStatus.SUSPENDED;
            };
            if (target.status != needed || budget < 1) {
                throw new IllegalStateException("validated quest request contradicts the stored state");
            }
            budget--;
            target.changed = true;
            switch (request.action()) {
                case ACCEPT -> {
                    target.status = QuestStatus.ACTIVE;
                    steps.add(new Accepted(target.quest.id(), false, false));
                }
                case SUSPEND -> {
                    target.status = QuestStatus.SUSPENDED;
                    steps.add(new Suspended(target.quest.id(), false));
                }
                case RESUME -> {
                    target.status = QuestStatus.ACTIVE;
                    steps.add(new Resumed(target.quest.id(), false));
                }
            }
        }

        /** One pass over every quest in identity order; true when anything changed. */
        boolean pass() {
            boolean progressed = false;
            for (Work quest : work.values()) {
                progressed |= advance(quest);
            }
            return progressed;
        }

        /** Advance one quest as far as its predicates allow in this pass. */
        private boolean advance(Work quest) {
            boolean progressed = false;
            if (quest.status == QuestStatus.UNDISCOVERED) {
                boolean due = quest.quest.availableWhen() == null || quest.quest.availableWhen().test(this);
                if (!due) {
                    return false;
                }
                // A secret quest is discovered and accepted together, so it needs two events.
                if (budget < (quest.quest.secret() ? 2 : 1)) {
                    deferred = true;
                    return false;
                }
                budget--;
                quest.status = QuestStatus.AVAILABLE;
                quest.changed = true;
                steps.add(new Discovered(quest.quest.id(), quest.quest.secret()));
                progressed = true;
                if (quest.quest.secret()) {
                    budget--;
                    quest.status = QuestStatus.ACTIVE;
                    steps.add(new Accepted(quest.quest.id(), true, true));
                }
            } else if (quest.status == QuestStatus.AVAILABLE && quest.quest.secret()) {
                // A secret quest that is somehow available (stored by an older engine) starts at once.
                if (budget < 1) {
                    deferred = true;
                    return false;
                }
                budget--;
                quest.status = QuestStatus.ACTIVE;
                quest.changed = true;
                steps.add(new Accepted(quest.quest.id(), true, true));
                progressed = true;
            }
            if (quest.status != QuestStatus.ACTIVE) {
                return progressed;
            }
            for (QuestRules.Objective objective : quest.quest.objectives()) {
                if (quest.satisfied.contains(objective.id()) || !objective.when().test(this)) {
                    continue;
                }
                if (budget < 1) {
                    deferred = true;
                    return progressed;
                }
                budget--;
                quest.satisfied.add(objective.id());
                quest.changed = true;
                steps.add(new ObjectiveSatisfied(quest.quest.id(), quest.quest.secret(), objective.id()));
                progressed = true;
            }
            for (QuestRules.Outcome outcome : quest.quest.outcomes()) {
                if (!outcome.when().test(this)) {
                    continue;
                }
                int cost = 1 + outcome.rewards().size();
                if (budget < cost || rewardUnits + outcome.units() > QuestRules.MAX_REWARD_UNITS) {
                    deferred = true;
                    return progressed;
                }
                budget -= cost;
                rewardUnits += outcome.units();
                quest.status = outcome.kind().ends();
                quest.outcomeId = outcome.id();
                quest.changed = true;
                steps.add(new Ended(quest.quest.id(), quest.quest.secret(), outcome));
                for (QuestRules.Reward reward : outcome.rewards()) {
                    world.grant(reward.item(), reward.quantity());
                }
                return true;
            }
            return progressed;
        }
    }
}
