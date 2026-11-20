package org.rpgengine.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.EncounterOutcome;
import org.rpgengine.combat.EncounterOutcome.Kind;
import org.rpgengine.progression.ChallengeRewards.Claim;
import org.rpgengine.progression.ChallengeRewards.Fight;
import org.rpgengine.progression.ChallengeRewards.Item;
import org.rpgengine.progression.ChallengeRewards.Magnitude;
import org.rpgengine.progression.ChallengeRewards.Quest;
import org.rpgengine.progression.ChallengeRewards.Reason;
import org.rpgengine.progression.ChallengeRewards.Skip;
import org.rpgengine.progression.ChallengeRewards.Source;

/**
 * Challenge-source reward deduplication (P13.4-A, docs/12 section 8): a challenge pays once under
 * {@code challenge:<challengeSourceId>} whether a fight or a quest reaches it, a quest milestone is a distinct source,
 * and replays, aliases, respawns and stale ledgers never pay twice.
 */
class ChallengeRewardsTest {
    private static final String PLAYER = "00000000-0000-0000-0000-000000000001";
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Source BANDITS = new Source("bandit-camp", Magnitude.STANDARD, ChallengeRewards.DEFAULT_POLICY,
            List.of(new Item("bandit-map", 1)));

    /** Construct a stateless fixture. */
    ChallengeRewardsTest() {
    }

    /** A won fight settles its challenge once, with its XP, reward-table items and a canonical ledger form. */
    @Test
    void aWonFightSettlesItsChallengeOnce() {
        var plan = ChallengeRewards.plan(PLAYER, Set.of(), List.of(fight(FIRST, BANDITS, Kind.VICTORY)));
        assertEquals(1, plan.settlements().size());
        var settlement = plan.settlements().getFirst();
        assertEquals("challenge:bandit-camp", settlement.sourceKey());
        assertEquals(PLAYER, settlement.recipientKey());
        assertEquals(20, settlement.xp());
        assertEquals(List.of(new Item("bandit-map", 1)), settlement.items());
        assertEquals(Map.of("challengeSourceId", "bandit-camp", "xp", 20,
                "items", List.of(Map.of("definitionId", "bandit-map", "quantity", 1)),
                "origin", Map.of("kind", "encounter", "encounterId", FIRST.toString(), "outcome", "VICTORY")),
                settlement.form());
        assertEquals(List.of(), plan.skips());
        assertEquals(20, plan.xp());
    }

    /** The fight and the quest that share a challenge pay its XP once in either order; the milestone pays besides. */
    @Test
    void aQuestAndItsFightPayTheChallengeOnce() {
        var quest = new Quest("clear-the-road", "cleared", true, BANDITS, Magnitude.MINOR, List.of(new Item("coin", 5)));
        var fightFirst = ChallengeRewards.plan(PLAYER, Set.of(), List.of(fight(FIRST, BANDITS, Kind.VICTORY), quest));
        assertEquals(List.of("challenge:bandit-camp", "quest:clear-the-road:outcome"), keys(fightFirst));
        assertEquals(List.of(new Skip(1, "challenge:bandit-camp", Reason.ALREADY_SETTLED)), fightFirst.skips());
        assertEquals(20 + 10, fightFirst.xp());

        // The quest ended first, for example after the camp was talked down outside a fight: the later fight pays nothing.
        var questFirst = ChallengeRewards.plan(PLAYER, Set.of(), List.of(quest, fight(FIRST, BANDITS, Kind.VICTORY)));
        assertEquals(List.of("quest:clear-the-road:outcome", "challenge:bandit-camp"), keys(questFirst));
        assertEquals(List.of(new Skip(1, "challenge:bandit-camp", Reason.ALREADY_SETTLED)), questFirst.skips());
        assertEquals(30, questFirst.xp());
        assertEquals(Map.of("kind", "quest", "questId", "clear-the-road", "outcomeId", "cleared"),
                questFirst.settlements().get(1).form().get("origin"));

        // Across commits the ledger carries the same identity: the second ending reads it and pays nothing.
        var later = ChallengeRewards.plan(PLAYER, Set.of("challenge:bandit-camp"), List.of(quest));
        assertEquals(List.of("quest:clear-the-road:outcome"), keys(later));
        assertEquals(10, later.xp());
        var replay = ChallengeRewards.plan(PLAYER, Set.of("challenge:bandit-camp", "quest:clear-the-road:outcome"),
                List.of(quest, fight(FIRST, BANDITS, Kind.VICTORY)));
        assertEquals(List.of(), replay.settlements());
        assertEquals(3, replay.skips().size());
        assertTrue(replay.skips().stream().allMatch(skip -> skip.reason() == Reason.ALREADY_SETTLED));
    }

    /** A respawned fight, an alias template and a repeated quest view reuse the source key and pay nothing more. */
    @Test
    void respawnsAliasesAndRepeatsPayNothing() {
        var alias = new Source("bandit-camp", Magnitude.MAJOR);
        var quest = new Quest("clear-the-road", "cleared", true, null, Magnitude.MINOR, List.of());
        var plan = ChallengeRewards.plan(PLAYER, Set.of(), List.<Claim>of(fight(FIRST, BANDITS, Kind.VICTORY),
                fight(SECOND, BANDITS, Kind.VICTORY), fight(SECOND, alias, Kind.HOSTILE_SURRENDER), quest, quest));
        assertEquals(List.of("challenge:bandit-camp", "quest:clear-the-road:outcome"), keys(plan));
        assertEquals(List.of(new Skip(1, "challenge:bandit-camp", Reason.ALREADY_SETTLED),
                new Skip(2, "challenge:bandit-camp", Reason.ALREADY_SETTLED),
                new Skip(4, "quest:clear-the-road:outcome", Reason.ALREADY_SETTLED)), plan.skips());
        assertEquals(30, plan.xp());
        // Another recipient's ledger is its own: the same challenge pays a companion once too.
        var companion = ChallengeRewards.plan("00000000-0000-0000-0000-000000000002", Set.of(),
                List.of(fight(FIRST, BANDITS, Kind.VICTORY)));
        assertEquals(List.of("challenge:bandit-camp"), keys(companion));
    }

    /** Outcome policy: a loss earns nothing and leaves the challenge open; surrender or a declared escape earns it. */
    @Test
    void theOutcomePolicyDecidesWhatEarns() {
        var lost = ChallengeRewards.plan(PLAYER, Set.of(), List.<Claim>of(fight(FIRST, BANDITS, Kind.DEFEAT),
                fight(FIRST, BANDITS, Kind.PLAYER_SURRENDER), fight(FIRST, BANDITS, Kind.ESCAPE),
                new Quest("clear-the-road", "abandoned", false, BANDITS, null, List.of())));
        assertEquals(List.of(), lost.settlements());
        assertEquals(4, lost.skips().size());
        assertTrue(lost.skips().stream().allMatch(skip -> skip.reason() == Reason.NOT_EARNED));
        // The challenge was not consumed: a later victory earns it.
        var won = ChallengeRewards.plan(PLAYER, Set.of(), List.of(fight(SECOND, BANDITS, Kind.DEFEAT),
                fight(SECOND, BANDITS, Kind.HOSTILE_SURRENDER)));
        assertEquals(List.of("challenge:bandit-camp"), keys(won));
        assertEquals(1, won.settlements().getFirst().claim());

        var slipped = new Source("toll-bridge", Magnitude.MINOR, EnumSet.of(Kind.ESCAPE, Kind.AGREEMENT), List.of());
        assertEquals(10, ChallengeRewards.plan(PLAYER, Set.of(), List.of(fight(FIRST, slipped, Kind.ESCAPE))).xp());
        assertEquals(0, ChallengeRewards.plan(PLAYER, Set.of(), List.of(fight(FIRST, slipped, Kind.VICTORY))).xp());
        // A quest with neither milestone XP nor items settles no milestone row.
        assertEquals(List.of(), ChallengeRewards.plan(PLAYER, Set.of(),
                List.of(new Quest("errand", "done", true, null, null, List.of()))).settlements());
    }

    /** Malformed declarations are refused before any plan exists. */
    @Test
    void malformedDeclarationsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Source("Bandit Camp", Magnitude.MINOR));
        assertThrows(IllegalArgumentException.class, () -> new Source("bandit:camp", Magnitude.MINOR));
        assertThrows(IllegalArgumentException.class,
                () -> new Source("camp", Magnitude.MINOR, EnumSet.of(Kind.VICTORY, Kind.DEFEAT), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Source("camp", Magnitude.MINOR, EnumSet.noneOf(Kind.class), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Source("camp", Magnitude.MINOR,
                ChallengeRewards.DEFAULT_POLICY, List.of(new Item("coin", 1), new Item("coin", 2))));
        assertThrows(IllegalArgumentException.class, () -> new Item("coin", 0));
        assertThrows(IllegalArgumentException.class, () -> new Item("coin", 10_000));
        assertThrows(NullPointerException.class, () -> new Fight(FIRST, BANDITS, null));
        assertThrows(IllegalArgumentException.class, () -> ChallengeRewards.plan(" ", Set.of(), List.of()));
        // The dependency-qualified form is a canonical ID.
        assertEquals("challenge:base#bandit-camp", new Source("base#bandit-camp", Magnitude.MINOR).key());
    }

    /** A fight claim with a verdict of this kind. */
    private static Fight fight(UUID encounter, Source source, Kind kind) {
        String objective = kind == Kind.OBJECTIVE ? "hold" : null;
        String terms = kind == Kind.AGREEMENT ? "truce" : null;
        return new Fight(encounter, source, new EncounterOutcome(kind, objective, terms, Map.of()));
    }

    /** The source keys of a plan's settlements in order. */
    private static List<String> keys(ChallengeRewards.Plan plan) {
        return plan.settlements().stream().map(ChallengeRewards.Settlement::sourceKey).toList();
    }
}
