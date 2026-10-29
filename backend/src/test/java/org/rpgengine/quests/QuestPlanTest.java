package org.rpgengine.quests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pure acceptance of the quest transition engine (P10.3): derived discovery, explicit accept, suspend and
 * resume, sticky objectives, ordered outcomes, one-time endings, cascades, rewards that count for later
 * predicates, budgets that defer rather than drop, and determinism.
 */
class QuestPlanTest {
    private static final QuestPredicate YES = new QuestPredicate.Flag(true);
    private static final QuestPredicate NO = new QuestPredicate.Flag(false);

    /** Construct the stateless fixture. */
    QuestPlanTest() { }

    /** Mutable world: visited places, held items, and a record of grants. */
    private static final class Fake implements QuestPlan.World {
        final Set<String> visited = new HashSet<>();
        final Map<String, Long> held = new HashMap<>();
        final List<String> grants = new ArrayList<>();
        long time;

        @Override public long worldTime() { return time; }

        @Override public boolean visited(String location) { return visited.contains(location); }

        @Override public long possessed(String item) { return held.getOrDefault(item, 0L); }

        @Override
        public void grant(String item, int quantity) {
            held.merge(item, (long) quantity, Long::sum);
            grants.add(item + "x" + quantity);
        }
    }

    private static QuestRules.Quest quest(String id, QuestStatus initial, QuestPredicate available,
            List<QuestRules.Objective> objectives, List<QuestRules.Outcome> outcomes) {
        return new QuestRules.Quest(id, false, initial, available, objectives, outcomes);
    }

    private static QuestRules.Outcome success(String id, QuestPredicate when, QuestRules.Reward... rewards) {
        return new QuestRules.Outcome(id, QuestRules.Kind.SUCCESS, when, List.of(rewards));
    }

    private static QuestRules rules(QuestRules.Quest... quests) {
        var map = new LinkedHashMap<String, QuestRules.Quest>();
        for (QuestRules.Quest quest : quests) {
            map.put(quest.id(), quest);
        }
        return new QuestRules(map);
    }

    private static QuestPlan.State stored(QuestStatus status, String outcome, long version, String... satisfied) {
        return new QuestPlan.State(status, outcome, version, Set.of(satisfied));
    }

    private static QuestPlan.Result run(QuestRules rules, Map<String, QuestPlan.State> stored, Fake world,
            QuestPlan.Request request) {
        return QuestPlan.evaluate(rules, stored, world, request, 100);
    }

    /** A quest whose discovery predicate holds becomes available once; one that does not stays hidden. */
    @Test
    void discoveryFollowsItsPredicate() {
        var gated = quest("gated", QuestStatus.UNDISCOVERED, new QuestPredicate.Visited("grove"),
                List.of(new QuestRules.Objective("o", false, NO)), List.of(success("s", NO)));
        var open = quest("open", QuestStatus.UNDISCOVERED, null,
                List.of(new QuestRules.Objective("o", false, NO)), List.of(success("s", NO)));
        var world = new Fake();
        var first = run(rules(gated, open), Map.of(), world, null);
        assertEquals(List.of(new QuestPlan.Discovered("open", false)), first.steps());
        assertEquals(QuestStatus.AVAILABLE, first.after().get("open").status());
        assertEquals(QuestPlan.State.ABSENT, first.after().get("open").rowVersion());
        world.visited.add("grove");
        var second = run(rules(gated, open), Map.of("open", stored(QuestStatus.AVAILABLE, null, 0)), world, null);
        assertEquals(List.of(new QuestPlan.Discovered("gated", false)), second.steps());
        // Once available a quest is never discovered again, and an available quest tracks nothing.
        var third = run(rules(gated, open), Map.of("open", stored(QuestStatus.AVAILABLE, null, 0),
                "gated", stored(QuestStatus.AVAILABLE, null, 0)), world, null);
        assertEquals(List.of(), third.steps());
        assertTrue(third.after().isEmpty());
    }

    /** Accept, suspend and resume move exactly one quest and are refused from any other status. */
    @Test
    void requestsMoveOneQuestAndRefuseWrongStatus() {
        var q = quest("q", QuestStatus.AVAILABLE, null, List.of(new QuestRules.Objective("o", false, NO)),
                List.of(success("s", NO)));
        var rules = rules(q);
        var world = new Fake();
        var accepted = run(rules, Map.of(), world, new QuestPlan.Request(QuestPlan.Action.ACCEPT, "q"));
        assertEquals(List.of(new QuestPlan.Accepted("q", false, false)), accepted.steps());
        assertEquals(QuestStatus.ACTIVE, accepted.after().get("q").status());
        var suspended = run(rules, Map.of("q", stored(QuestStatus.ACTIVE, null, 0)), world,
                new QuestPlan.Request(QuestPlan.Action.SUSPEND, "q"));
        assertEquals(List.of(new QuestPlan.Suspended("q", false)), suspended.steps());
        var resumed = run(rules, Map.of("q", stored(QuestStatus.SUSPENDED, null, 1)), world,
                new QuestPlan.Request(QuestPlan.Action.RESUME, "q"));
        assertEquals(List.of(new QuestPlan.Resumed("q", false)), resumed.steps());
        assertEquals(1, resumed.after().get("q").rowVersion(), "the version read, not a successor");
        for (var wrong : List.of(
                new QuestPlan.Request(QuestPlan.Action.SUSPEND, "q"), new QuestPlan.Request(QuestPlan.Action.RESUME, "q"),
                new QuestPlan.Request(QuestPlan.Action.ACCEPT, "missing"))) {
            assertThrows(IllegalStateException.class, () -> run(rules, Map.of(), world, wrong));
        }
        assertThrows(IllegalStateException.class, () -> run(rules, Map.of("q", stored(QuestStatus.ACTIVE, null, 0)),
                world, new QuestPlan.Request(QuestPlan.Action.ACCEPT, "q")));
        assertThrows(IllegalStateException.class, () -> QuestPlan.evaluate(rules, Map.of(), world,
                new QuestPlan.Request(QuestPlan.Action.ACCEPT, "q"), 0));
    }

    /** Objectives are marked in authored order, stay satisfied after the proof is gone, and are never repeated. */
    @Test
    void objectivesAreOrderedAndSticky() {
        var q = quest("q", QuestStatus.AVAILABLE, null, List.of(
                new QuestRules.Objective("reach", false, new QuestPredicate.Visited("grove")),
                new QuestRules.Objective("carry", true, new QuestPredicate.Possesses("relic", 1))),
                List.of(success("s", NO)));
        var world = new Fake();
        world.visited.add("grove");
        world.held.put("relic", 1L);
        var first = run(rules(q), Map.of("q", stored(QuestStatus.ACTIVE, null, 4)), world, null);
        assertEquals(List.of(new QuestPlan.ObjectiveSatisfied("q", false, "reach"),
                new QuestPlan.ObjectiveSatisfied("q", false, "carry")), first.steps());
        assertEquals(Set.of("reach", "carry"), first.after().get("q").satisfied());
        // The relic is given away: the satisfied objective is history and is not announced again.
        world.held.clear();
        var second = run(rules(q), Map.of("q", stored(QuestStatus.ACTIVE, null, 5, "reach", "carry")), world, null);
        assertEquals(List.of(), second.steps());
        assertTrue(QuestPlan.evaluate(rules(q), Map.of("q", stored(QuestStatus.SUSPENDED, null, 5)), world, null, 10)
                .steps().isEmpty(), "a suspended quest tracks nothing");
    }

    /** The first outcome in authored order wins, grants its rewards and ends the quest; the ending is final. */
    @Test
    void firstMatchingOutcomeEndsTheQuestOnce() {
        var objective = new QuestRules.Objective("o", false, new QuestPredicate.Visited("grove"));
        var q = quest("q", QuestStatus.AVAILABLE, null, List.of(objective), List.of(
                new QuestRules.Outcome("lost", QuestRules.Kind.FAILURE, new QuestPredicate.Visited("swamp"), List.of()),
                success("won", new QuestPredicate.Objective("q", "o"), new QuestRules.Reward("coin", 5)),
                success("also", YES, new QuestRules.Reward("gem", 1))));
        var world = new Fake();
        world.visited.add("grove");
        var done = run(rules(q), Map.of("q", stored(QuestStatus.ACTIVE, null, 2)), world, null);
        assertEquals(2, done.steps().size());
        var ended = assertInstanceOf(QuestPlan.Ended.class, done.steps().get(1));
        assertEquals("won", ended.outcome().id());
        assertEquals(List.of("coinx5"), world.grants, "only the selected outcome pays");
        assertEquals(QuestStatus.COMPLETED, done.after().get("q").status());
        assertEquals("won", done.after().get("q").outcomeId());
        // A completed quest is final, even though its predicates still hold and a better outcome now matches.
        world.visited.add("swamp");
        var again = run(rules(q), Map.of("q", stored(QuestStatus.COMPLETED, "won", 3, "o")), world, null);
        assertEquals(List.of(), again.steps());
        assertEquals(List.of("coinx5"), world.grants, "no second payment");
        // A failure outcome ends the quest as failed.
        var failing = quest("f", QuestStatus.AVAILABLE, null, List.of(objective), List.of(
                new QuestRules.Outcome("lost", QuestRules.Kind.FAILURE, new QuestPredicate.Visited("swamp"), List.of())));
        var failed = run(rules(failing), Map.of("f", stored(QuestStatus.ACTIVE, null, 0)), world, null);
        assertEquals(QuestStatus.FAILED, failed.after().get("f").status());
        var alternative = quest("alt", QuestStatus.AVAILABLE, null, List.of(objective), List.of(
                new QuestRules.Outcome("other", QuestRules.Kind.ALTERNATIVE, YES, List.of()),
                success("never", NO)));
        assertEquals(QuestStatus.COMPLETED, run(rules(alternative), Map.of("alt", stored(QuestStatus.ACTIVE, null, 0)),
                world, null).after().get("alt").status());
    }

    /** A quest ending can open another quest and its granted rewards satisfy a later predicate, in one evaluation. */
    @Test
    void cascadesAndRewardsCountWithinOneEvaluation() {
        var first = quest("a-first", QuestStatus.AVAILABLE, null, List.of(new QuestRules.Objective("o", false, YES)),
                List.of(success("done", new QuestPredicate.Objective("a-first", "o"), new QuestRules.Reward("key", 1))));
        var second = quest("b-second", QuestStatus.UNDISCOVERED, new QuestPredicate.Status("a-first",
                QuestStatus.COMPLETED), List.of(new QuestRules.Objective("o", false, new QuestPredicate.Possesses("key", 1))),
                List.of(success("done", new QuestPredicate.Objective("b-second", "o"))));
        var world = new Fake();
        var result = run(rules(second, first), Map.of("a-first", stored(QuestStatus.ACTIVE, null, 0)), world, null);
        assertEquals(List.of("a-first", "a-first", "b-second"), result.steps().stream().map(QuestPlan.Step::questId)
                .limit(3).toList(), "identity order, with the dependent quest discovered after its prerequisite ended");
        // b-second is discovered but not accepted, so it tracks nothing yet.
        assertEquals(QuestStatus.AVAILABLE, result.after().get("b-second").status());
        // Accepted later, the key granted earlier counts at once.
        var later = run(rules(second, first), Map.of("a-first", stored(QuestStatus.COMPLETED, "done", 1, "o"),
                "b-second", stored(QuestStatus.AVAILABLE, null, 0)), world, new QuestPlan.Request(QuestPlan.Action.ACCEPT, "b-second"));
        assertEquals(QuestStatus.COMPLETED, later.after().get("b-second").status());
        assertEquals(List.of("keyx1"), world.grants);
    }

    /** A cascade inside one pass: the later quest sees the earlier one's rewards without another command. */
    @Test
    void rewardsFeedActiveQuestsInTheSamePass() {
        var giver = quest("a-giver", QuestStatus.AVAILABLE, null, List.of(new QuestRules.Objective("o", false, YES)),
                List.of(success("done", YES, new QuestRules.Reward("coin", 3))));
        var taker = quest("b-taker", QuestStatus.AVAILABLE, null, List.of(new QuestRules.Objective("rich", false,
                new QuestPredicate.Possesses("coin", 3))), List.of(success("done", new QuestPredicate.Objective("b-taker", "rich"))));
        var result = run(rules(giver, taker), Map.of("a-giver", stored(QuestStatus.ACTIVE, null, 0),
                "b-taker", stored(QuestStatus.ACTIVE, null, 0)), new Fake(), null);
        assertEquals(QuestStatus.COMPLETED, result.after().get("b-taker").status());
    }

    /** Hidden background quests start by themselves, stay internal and still end publicly. */
    @Test
    void secretQuestsActivateThemselves() {
        var hidden = new QuestRules.Quest("hidden", true, QuestStatus.UNDISCOVERED, null,
                List.of(new QuestRules.Objective("o", false, YES)), List.of(success("done", new QuestPredicate.Objective(
                        "hidden", "o"))));
        var result = run(rules(hidden), Map.of(), new Fake(), null);
        assertEquals(List.of(new QuestPlan.Discovered("hidden", true), new QuestPlan.Accepted("hidden", true, true)),
                result.steps().subList(0, 2));
        assertTrue(result.steps().get(2).secret());
        assertInstanceOf(QuestPlan.Ended.class, result.steps().getLast());
        // Secret quests cannot be addressed by a request.
        assertThrows(IllegalStateException.class, () -> run(rules(hidden), Map.of("hidden", stored(QuestStatus.AVAILABLE,
                null, 0)), new Fake(), new QuestPlan.Request(QuestPlan.Action.ACCEPT, "hidden")));
        // A stored available secret quest (older engine) starts at the next evaluation.
        var legacy = run(rules(hidden), Map.of("hidden", stored(QuestStatus.AVAILABLE, null, 0)), new Fake(), null);
        assertEquals(new QuestPlan.Accepted("hidden", true, true), legacy.steps().getFirst());
    }

    /** Budgets defer a due transition instead of dropping it, and a later evaluation settles it. */
    @Test
    void budgetsDeferDueTransitions() {
        var big = quest("a-big", QuestStatus.AVAILABLE, null, List.of(new QuestRules.Objective("o", false, YES)),
                List.of(success("done", YES, new QuestRules.Reward("coin", 20))));
        var other = quest("b-other", QuestStatus.AVAILABLE, null, List.of(new QuestRules.Objective("o", false, YES)),
                List.of(success("done", YES, new QuestRules.Reward("gem", 20))));
        var world = new Fake();
        var active = Map.of("a-big", stored(QuestStatus.ACTIVE, null, 0), "b-other", stored(QuestStatus.ACTIVE, null, 0));
        var first = run(rules(big, other), active, world, null);
        // 20 + 20 units exceed the 32-unit commit bound: the second ending waits, its objective is recorded.
        assertTrue(first.deferred());
        assertEquals(List.of("coinx20"), world.grants);
        assertEquals(QuestStatus.COMPLETED, first.after().get("a-big").status());
        assertEquals(QuestStatus.ACTIVE, first.after().get("b-other").status());
        var next = new HashMap<>(active);
        next.put("a-big", stored(QuestStatus.COMPLETED, "done", 1, "o"));
        next.put("b-other", stored(QuestStatus.ACTIVE, null, 1, "o"));
        var second = run(rules(big, other), next, world, null);
        assertFalse(second.deferred());
        assertEquals(List.of("coinx20", "gemx20"), world.grants);
        // An event budget too small for an ending defers it without partial effects.
        var tight = QuestPlan.evaluate(rules(big), Map.of("a-big", stored(QuestStatus.ACTIVE, null, 0, "o")), new Fake(),
                null, 1);
        assertTrue(tight.deferred());
        assertTrue(tight.steps().isEmpty());
    }

    /** The same inputs always give the same steps, in the same order. */
    @Test
    void evaluationIsDeterministic() {
        var q = quest("q", QuestStatus.UNDISCOVERED, null, List.of(new QuestRules.Objective("o", false, YES)),
                List.of(success("done", new QuestPredicate.Objective("q", "o"), new QuestRules.Reward("coin", 2))));
        var one = run(rules(q), Map.of(), new Fake(), null);
        var two = run(rules(q), Map.of(), new Fake(), null);
        assertEquals(one, two);
    }

    /** Writes derived from an evaluation cite the announcing step and reject misaligned indexes. */
    @Test
    void changesCiteTheirAnnouncingSteps() {
        var q = quest("q", QuestStatus.AVAILABLE, null, List.of(new QuestRules.Objective("o", false, YES)),
                List.of(success("done", new QuestPredicate.Objective("q", "o"))));
        var result = run(rules(q), Map.of("q", stored(QuestStatus.ACTIVE, null, 6)), new Fake(), null);
        assertEquals(2, result.steps().size());
        var changes = QuestChanges.of(result, List.of(4, 5), List.of());
        assertEquals(List.of(new QuestChanges.StateWrite("q", 6, QuestStatus.COMPLETED, "done", 5)), changes.states());
        assertEquals(List.of(new QuestChanges.ObjectiveWrite("q", "o", 4)), changes.objectives());
        assertThrows(IllegalArgumentException.class, () -> QuestChanges.of(result, List.of(4), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QuestChanges.StateWrite("q", 0, QuestStatus.COMPLETED, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new QuestChanges.StateWrite("q", 0, QuestStatus.ACTIVE, "x", 0));
        assertThrows(IllegalArgumentException.class, () -> new QuestChanges.StateWrite("q", 0, QuestStatus.UNDISCOVERED, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new QuestChanges(List.of(changes.states().getFirst(),
                changes.states().getFirst()), List.of(), List.of()));
        assertEquals(java.util.Set.of("quests", "quest:q"), QuestWriter.scopes(changes));
        assertTrue(QuestWriter.scopes(QuestChanges.NONE).isEmpty());
        assertEquals(java.util.Set.of("quests"), QuestWriter.scopes(new QuestChanges(List.of(new QuestChanges.StateWrite(
                "pack#q", 0, QuestStatus.ACTIVE, null, 0)), List.of(), List.of())));
    }
}
