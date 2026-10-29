package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.campaign.EventVisibility;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure proof of the P07.4 local calculation: expression semantics, draw ordinal order,
 * timing phases, conditional branches, exact cost payment, clamping, vocabulary-bound
 * targets, event visibility, receipt details and byte-identical determinism. No database
 * is involved: draws come from a recording function, so every reserved ordinal is visible.
 */
class LocalActionResolverTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALLY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID VIGOR = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID FOCUS = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ALLY_VIGOR = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final UUID STRANGER_VIGOR = UUID.fromString("00000000-0000-0000-0000-0000000000f4");

    /** Construct the stateless fixture. */
    LocalActionResolverTest() { }

    /** Numeric operators follow the compiler's integer semantics and dice consume ordinals in order. */
    @Test
    void evaluatesExpressionsAndReservesDrawsInEvaluationOrder() {
        // add(2d6, might, rank, div(-7, 2), clamp(15, 0, 10), min(4, 9), max(-1, 0), mul(2, 3), sub(1, 5))
        var amount = op("add", new EffectProgram.Dice(2, 6), new EffectProgram.Attribute("might"),
                new EffectProgram.Skill("lore"), op("div", constant(-7), constant(2)),
                op("clamp", constant(15), constant(0), constant(10)), op("min", constant(4), constant(9)),
                op("max", constant(-1), constant(0)), op("sub", op("mul", constant(2), constant(3)), constant(5)));
        var requested = new ArrayList<int[]>();
        var result = resolve(ability("vital", modify("self", "vigor", amount)), List.of(),
                (ordinal, sides) -> {
                    requested.add(new int[] {ordinal, sides});
                    return ordinal + 2;
                });
        // Dice 2 + 3, might 2, lore 1, -3 (truncation toward zero), 10, 4, 0, 1.
        long expected = 5 + 2 + 1 - 3 + 10 + 4 + 0 + 1;
        assertEquals(List.of(List.of(0, 6), List.of(1, 6)), requested.stream().map(pair -> List.of(pair[0], pair[1])).toList());
        var details = details(result);
        var calculation = details.path("calculations").get(0);
        assertEquals("effects/0/amount", calculation.path("path").asString());
        assertEquals(expected, calculation.path("value").asLong());
        assertEquals("[0,1]", calculation.path("drawOrdinals").toString());
        // Vigor 3 of 8 rises by 20 and is clamped at the maximum.
        var effect = payload(result, 1);
        assertEquals("CLAMPED", effect.path("outcomes").get(0).path("outcome").asString());
        assertEquals(5, effect.path("outcomes").get(0).path("applied").asLong());
        assertEquals(List.of(new ResolvedCommit.ResourceChange(VIGOR, 7, 5)), result.resources());
    }

    /** Pre-timing nodes run before post-timing nodes; a conditional runs only its chosen branch. */
    @Test
    void ordersTimingPhasesAndBranchesDeterministically() {
        var post = modify("self", "vigor", constant(-1));
        var guarded = new EffectProgram.Conditional("pre",
                op("gt", new EffectProgram.Dice(1, 20), constant(10)),
                List.of(new EffectProgram.ModifyResource("pre", "self", "focus", constant(2))),
                List.of(new EffectProgram.ModifyResource("pre", "self", "focus", constant(-2))));
        // Short-circuit: "any" stops at the true literal, so its die reserves no ordinal.
        var shortcut = new EffectProgram.Conditional("post",
                op("any", new EffectProgram.Flag(true), op("gt", new EffectProgram.Dice(1, 4), constant(0))),
                List.of(modify("self", "vigor", constant(1))), List.of());
        var result = resolve(ability("ordered", post, guarded, shortcut), List.of(), (ordinal, sides) -> 15);
        var paths = new ArrayList<String>();
        for (int index = 1; index < result.events().size(); index++) {
            paths.add(payload(result, index).path("path").asString());
        }
        assertEquals(List.of("effects/1/then/0", "effects/0", "effects/2/then/0"), paths);
        var calculations = details(result).path("calculations");
        assertEquals("effects/1/when", calculations.get(0).path("path").asString());
        assertTrue(calculations.get(0).path("value").asBoolean());
        assertEquals("[0]", calculations.get(0).path("drawOrdinals").toString());
        assertEquals("[]", calculations.get(3).path("drawOrdinals").toString());
        // The low roll takes the else branch instead.
        var low = resolve(ability("ordered", guarded), List.of(), (ordinal, sides) -> 3);
        assertEquals("effects/0/else/0", payload(low, 1).path("path").asString());
    }

    /** Costs are paid exactly before effects, and net deltas merge per pool with the read storage version. */
    @Test
    void paysCostsAndMergesNetChanges() {
        var spend = new CommandRuleCatalog.Ability("pack:a", List.of(), Set.of(), Map.of("vigor", 2, "focus", 0),
                "self", 1, List.of(modify("self", "vigor", constant(1))));
        var result = resolve(spend, List.of(), (ordinal, sides) -> 1);
        assertEquals(List.of("ABILITY_USED", "RESOURCE_SPENT", "EFFECT_RESOLVED"),
                result.events().stream().map(event -> event.body().eventType()).toList());
        var spent = payload(result, 1);
        assertEquals("vigor", spent.path("resourceKey").asString());
        assertEquals(3, spent.path("before").asLong());
        assertEquals(1, spent.path("after").asLong());
        assertEquals(List.of(new ResolvedCommit.ResourceChange(VIGOR, 7, -1)), result.resources());
        assertEquals("[{\"actorId\":\"" + PLAYER + "\",\"resourceKey\":\"vigor\",\"before\":3,\"after\":2}]",
                details(result).path("changes").toString());
        assertEquals(List.of("actor:" + PLAYER), result.queryScopes());
    }

    /**
     * Effects reach only explicit targets holding the resource in the ability's vocabulary; others are
     * explicitly unaffected. Values of other actors' pools make the event engine-internal.
     */
    @Test
    void appliesTargetsByVocabularyAndHidesOtherActorsValues() {
        var heal = ability("mend", modify("targets", "vigor", constant(-4)));
        var result = resolve(heal, List.of(ALLY, STRANGER), (ordinal, sides) -> 1);
        var outcomes = payload(result, 1).path("outcomes");
        assertEquals("CLAMPED", outcomes.get(0).path("outcome").asString());
        assertEquals(1, outcomes.get(0).path("before").asLong());
        assertEquals(0, outcomes.get(0).path("after").asLong());
        assertEquals("NO_RESOURCE", outcomes.get(1).path("outcome").asString());
        assertFalse(outcomes.get(1).has("before"));
        assertEquals(EventVisibility.INTERNAL, result.events().get(1).visibility());
        assertEquals(List.of(new ResolvedCommit.ResourceChange(ALLY_VIGOR, 2, -1)), result.resources());
        assertEquals(List.of("actor:" + ALLY), result.queryScopes());
        // A self-policy ability's "target" effect lands on the acting player and stays public.
        var self = resolve(ability("steady", modify("target", "vigor", constant(1))), List.of(),
                (ordinal, sides) -> 1);
        assertEquals(EventVisibility.PUBLIC, self.events().get(1).visibility());
        assertEquals(PLAYER.toString(), payload(self, 1).path("outcomes").get(0).path("actorId").asString());
    }

    /** Movement adds the authored duration of its link, stays within the clock and needs the pinned origin. */
    @Test
    void movementTakesTheDurationOfItsLink() {
        var move = LocalActionResolver.resolve(new TypedCommand.Move("grove"), snapshot(), places(45), (o, s) -> 1);
        assertEquals(2_700, move.world().elapsedSeconds());
        assertEquals(2_700, JSON.readTree(move.events().getFirst().body().payload()).path("seconds").asLong());
        assertEquals(2_700, details(move).path("elapsedSeconds").asLong());
        assertEquals(2_700, details(move).path("location").path("seconds").asLong());
        // The clock is bounded again where time is resolved: a defect cannot carry the world past its end.
        var late = snapshotAt(org.rpgengine.rules.WorldClock.END_SECONDS - 10, Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> LocalActionResolver.resolve(new TypedCommand.Move("grove"), late, places(45), (o, s) -> 1));
        assertThrows(NullPointerException.class,
                () -> LocalActionResolver.resolve(new TypedCommand.Move("grove"), snapshot(), null, (o, s) -> 1));
        assertThrows(IllegalStateException.class, () -> LocalActionResolver.resolve(new TypedCommand.Move("grove"),
                snapshot(), new CommandRuleCatalog(UUID.randomUUID(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()),
                (o, s) -> 1));
    }

    /**
     * A rest follows its recorded interruption draw: a draw at or under the chance halves the time and the
     * restoration and ends no condition, any other draw runs to the end. A policy that cannot be interrupted
     * reserves no draw.
     */
    @Test
    void restFollowsItsRecordedDraw() {
        var requested = new ArrayList<int[]>();
        var cut = LocalActionResolver.resolve(new TypedCommand.Rest("camp"), snapshotAt(1_000, Set.of("dazed", "rattled")),
                restCatalog(), (ordinal, sides) -> {
                    requested.add(new int[] {ordinal, sides});
                    return 40;
                });
        assertEquals(List.of(List.of(0, 100)), requested.stream().map(pair -> List.of(pair[0], pair[1])).toList());
        assertEquals(14_400, cut.world().elapsedSeconds());
        assertEquals(List.of("REST_INTERRUPTED", "RESOURCE_RECOVERED"),
                cut.events().stream().map(event -> event.body().eventType()).toList());
        // Vigor 3 of 8 gains half of 6, and no condition ends.
        assertEquals(List.of(new ResolvedCommit.ResourceChange(VIGOR, 7, 3)), cut.resources());
        assertTrue(cut.conditions().isEmpty());
        var rest = details(cut).path("rest");
        assertTrue(rest.path("interrupted").asBoolean());
        assertEquals(0, rest.path("drawOrdinal").asInt());
        assertEquals(List.of("actor:" + PLAYER), cut.queryScopes());

        var full = LocalActionResolver.resolve(new TypedCommand.Rest("camp"), snapshotAt(1_000, Set.of("dazed", "rattled")),
                restCatalog(), (ordinal, sides) -> 41);
        assertEquals(28_800, full.world().elapsedSeconds());
        assertEquals(List.of("REST_COMPLETED", "RESOURCE_RECOVERED", "CONDITION_REMOVED"),
                full.events().stream().map(event -> event.body().eventType()).toList());
        // The five missing units fit below the maximum of 8: the six declared units are clamped.
        assertEquals(List.of(new ResolvedCommit.ResourceChange(VIGOR, 7, 5)), full.resources());
        assertEquals(List.of(new ResolvedCommit.ConditionRemoval(PLAYER, "dazed")), full.conditions());
        assertFalse(details(full).path("rest").path("interrupted").asBoolean());
        assertEquals("[\"dazed\"]", details(full).path("rest").path("conditionsEnded").toString());

        // A policy that cannot be interrupted never touches the draw source.
        var calm = LocalActionResolver.resolve(new TypedCommand.Rest("calm"), snapshotAt(0, Set.of()), restCatalog(),
                (ordinal, sides) -> {
                    throw new AssertionError("no draw expected");
                });
        assertEquals(600, calm.world().elapsedSeconds());
        assertFalse(details(calm).path("rest").has("drawOrdinal"));
        // Restoring a pool that is not the player's vocabulary key changes nothing and is not an error.
        assertTrue(calm.conditions().isEmpty());
        assertEquals(new ResolvedCommit.WorldChange(true, 600, org.rpgengine.campaign.CampaignMode.EXPLORATION, null),
                calm.world());
    }

    /** Missing attributes, foreign draws and stale premises fail closed instead of reading zero. */
    @Test
    void failsClosedOnUnknownValuesAndBadDraws() {
        assertThrows(IllegalStateException.class, () -> resolve(ability("x", modify("self", "vigor",
                new EffectProgram.Attribute("grace"))), List.of(), (ordinal, sides) -> 1));
        assertThrows(IllegalStateException.class, () -> resolve(ability("x", modify("self", "vigor",
                new EffectProgram.Resource("luck"))), List.of(), (ordinal, sides) -> 1));
        assertThrows(IllegalStateException.class, () -> resolve(ability("x", modify("self", "vigor",
                new EffectProgram.Dice(1, 6))), List.of(), (ordinal, sides) -> 7));
        var unpayable = new CommandRuleCatalog.Ability("pack:a", List.of(), Set.of(), Map.of("vigor", 4), "self", 1,
                List.of(modify("self", "vigor", constant(1))));
        assertThrows(IllegalStateException.class, () -> resolve(unpayable, List.of(), (ordinal, sides) -> 1));
        var unsupported = new CommandRuleCatalog.Ability("pack:a", List.of(), Set.of(), Map.of(), "self", 1,
                List.of(new EffectProgram.Unsupported("post", "damage")));
        assertThrows(IllegalStateException.class, () -> resolve(unsupported, List.of(), (ordinal, sides) -> 1));
    }

    /** Exploration intentions produce exact placements, durations, events and the cleared decision. */
    @Test
    void resolvesMovementWaitingAndSpeech() {
        // An instantaneous link: the pack declared no travel time.
        var move = LocalActionResolver.resolve(new TypedCommand.Move("grove"), snapshot(), places(0), (o, s) -> 1);
        assertEquals(List.of(new ResolvedCommit.Placement(PLAYER, 4, "grove")), move.placements());
        assertEquals(new ResolvedCommit.WorldChange(true, 0, org.rpgengine.campaign.CampaignMode.EXPLORATION, null),
                move.world());
        assertEquals("ACTOR_MOVED", move.events().getFirst().body().eventType());
        assertEquals("{\"actorId\":\"" + PLAYER + "\",\"from\":\"trailhead\",\"to\":\"grove\",\"seconds\":0}",
                move.events().getFirst().body().payload());
        assertEquals(List.of("actor:" + PLAYER), move.queryScopes());
        var wait = LocalActionResolver.resolve(new TypedCommand.Wait(90), snapshot(), null, (o, s) -> 1);
        assertEquals(90, wait.world().elapsedSeconds());
        assertTrue(wait.resources().isEmpty() && wait.placements().isEmpty() && wait.queryScopes().isEmpty());
        var speech = LocalActionResolver.resolve(new TypedCommand.Speak(ALLY, "Hold the lineé"), snapshot(), null,
                (o, s) -> 1);
        var spoken = JSON.readTree(speech.events().getFirst().body().payload());
        assertEquals("Hold the lineé", spoken.path("text").asString());
        assertEquals(ALLY.toString(), spoken.path("recipientActorId").asString());
        var details = JSON.readTree(move.receiptDetails());
        assertEquals(LocalActionResolver.RESOLVER, details.path("resolver").asString());
        assertTrue(details.path("pendingDecision").isNull());
        assertTrue(details.path("turnAdvanced").asBoolean());
    }

    /** The same inputs and draws give byte-identical effects, events and receipt details. */
    @Test
    void isDeterministic() {
        var program = ability("volley", modify("targets", "vigor", op("sub", constant(0),
                new EffectProgram.Dice(3, 4))), modify("self", "focus", new EffectProgram.Dice(1, 8)));
        var first = resolve(program, List.of(ALLY, STRANGER), (ordinal, sides) -> ordinal % sides + 1);
        var second = resolve(program, List.of(ALLY, STRANGER), (ordinal, sides) -> ordinal % sides + 1);
        assertEquals(first, second);
        assertEquals(first.receiptDetails(), second.receiptDetails());
    }

    /** Resolve one ability of pack {@code pack:a} for the fixed player with explicit targets. */
    private static LocalActionResolver.Resolution resolve(CommandRuleCatalog.Ability ability, List<UUID> targets,
            LocalActionResolver.Draws draws) {
        var catalog = new CommandRuleCatalog(UUID.randomUUID(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("subject", ability));
        return LocalActionResolver.resolve(new TypedCommand.UseAbility("subject", targets), snapshot(), catalog, draws);
    }

    /** Catalog with one link from the trailhead to the grove that takes the given minutes. */
    private static CommandRuleCatalog places(int minutes) {
        return new CommandRuleCatalog(UUID.randomUUID(), Map.of("trailhead",
                new CommandRuleCatalog.Location(true, Set.of("grove"), Map.of("grove", minutes), Set.of())),
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Catalog with two rest policies of pack:a: an interruptible camp and a calm short rest. */
    private static CommandRuleCatalog restCatalog() {
        var camp = new org.rpgengine.rules.RestRules.Policy("pack:a", 480, null, 40, Map.of("vigor", 6, "ghost", 2),
                List.of("dazed"));
        var calm = new org.rpgengine.rules.RestRules.Policy("pack:a", 10, null, 0, Map.of("ghost", 1), List.of());
        return new CommandRuleCatalog(UUID.randomUUID(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                new org.rpgengine.characters.CharacterRuleCatalog(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()),
                org.rpgengine.quests.QuestRules.NONE, Map.of("camp", camp, "calm", calm));
    }

    /** The standard snapshot with a given world clock and the player's active conditions. */
    private static LocalActionResolver.Snapshot snapshotAt(long clock, Set<String> conditions) {
        var base = snapshot();
        return new LocalActionResolver.Snapshot(base.player(), base.location(), base.playerRowVersion(),
                base.attributes(), base.skills(), base.pools(), base.vocabularies(), base.operationId(), base.stacks(),
                base.stored(), clock, conditions);
    }

    /** Player at trailhead with vigor 3/8 and focus 5/10; the ally shares pack:a, the stranger uses pack:b. */
    private static LocalActionResolver.Snapshot snapshot() {
        return new LocalActionResolver.Snapshot(PLAYER, "trailhead", 4, Map.of("might", 2L), Map.of("lore", 1L),
                Map.of(PLAYER, Map.of("vigor", new LocalActionResolver.Pool(VIGOR, 7, 0, 3, 8),
                                "focus", new LocalActionResolver.Pool(FOCUS, 1, 0, 5, 10)),
                        ALLY, Map.of("vigor", new LocalActionResolver.Pool(ALLY_VIGOR, 2, 0, 1, 6)),
                        STRANGER, Map.of("vigor", new LocalActionResolver.Pool(STRANGER_VIGOR, 0, 0, 4, 4))),
                Map.of(PLAYER, "pack:a", ALLY, "pack:a", STRANGER, "pack:b"));
    }

    /** Cost-free multiple-target ability of pack:a with the given effects; the ID only labels the case. */
    private static CommandRuleCatalog.Ability ability(String id, EffectProgram.Effect... effects) {
        return new CommandRuleCatalog.Ability("pack:a", List.of(), Set.of(), Map.of(), "multiple", 8, List.of(effects));
    }

    /** Post-timing resource modification. */
    private static EffectProgram.ModifyResource modify(String target, String resource, EffectProgram.Formula amount) {
        return new EffectProgram.ModifyResource("post", target, resource, amount);
    }

    /** Operator node. */
    private static EffectProgram.Operation op(String operator, EffectProgram.Formula... operands) {
        return new EffectProgram.Operation(operator, List.of(operands));
    }

    /** Integer literal. */
    private static EffectProgram.Constant constant(long value) {
        return new EffectProgram.Constant(value);
    }

    /** Parsed receipt details. */
    private static JsonNode details(LocalActionResolver.Resolution resolution) {
        return JSON.readTree(resolution.receiptDetails());
    }

    /** Parsed payload of the event at index. */
    private static JsonNode payload(LocalActionResolver.Resolution resolution, int index) {
        return JSON.readTree(resolution.events().get(index).body().payload());
    }
}
