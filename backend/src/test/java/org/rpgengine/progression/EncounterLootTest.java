package org.rpgengine.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.EncounterOutcome;
import org.rpgengine.combat.EncounterOutcome.Kind;
import org.rpgengine.combat.EncounterState.Status;
import org.rpgengine.combat.EncounterTemplate.Side;
import org.rpgengine.progression.EncounterLoot.Claimed;
import org.rpgengine.progression.EncounterLoot.Holding;
import org.rpgengine.progression.EncounterLoot.Refusal;
import org.rpgengine.progression.EncounterLoot.Refused;
import org.rpgengine.progression.EncounterLoot.Standing;
import org.rpgengine.progression.EncounterLoot.State;
import org.rpgengine.progression.EncounterLoot.Transfer;

/**
 * Exact loot ownership (P13.4-A, docs/12 section 8): only a defeated hostile's lots become collectible, nobody owns them
 * until a claim, each lot moves once and whole, and no sequence of claims creates or loses a unit.
 */
class EncounterLootTest {
    private static final UUID FIGHT = UUID.fromString("00000000-0000-0000-0000-00000000000f");
    private static final String PLAYER = "00000000-0000-0000-0000-000000000001";
    private static final String COMPANION = "00000000-0000-0000-0000-000000000002";
    private static final List<Standing> STANDINGS = List.of(new Standing("raider", Side.HOSTILE, Status.DEFEATED),
            new Standing("captain", Side.HOSTILE, Status.SURRENDERED), new Standing("scout", Side.HOSTILE, Status.ESCAPED),
            new Standing("archer", Side.HOSTILE, Status.ACTIVE), new Standing("guide", Side.ALLY, Status.DEFEATED));
    private static final List<Holding> HOLDINGS = List.of(new Holding("raider-blade", "raider", "blade", 1, true),
            new Holding("raider-coin", "raider", "coin", 12, false), new Holding("captain-blade", "captain", "blade", 1, true),
            new Holding("scout-coin", "scout", "coin", 3, false), new Holding("archer-bow", "archer", "bow", 1, true),
            new Holding("guide-rope", "guide", "rope", 1, false));

    /** Construct a stateless fixture. */
    EncounterLootTest() {
    }

    /** Only a defeated hostile's lots open, its equipped blade included; every other holder keeps its own. */
    @Test
    void onlyAFallenHostilesLotsBecomeAvailable() {
        var loot = EncounterLoot.open(FIGHT, outcome(Kind.HOSTILE_SURRENDER), STANDINGS, HOLDINGS);
        assertEquals(List.of(State.AVAILABLE, State.AVAILABLE, State.HELD, State.HELD, State.HELD, State.HELD),
                loot.lots().stream().map(EncounterLoot.Lot::state).toList());
        assertEquals(List.of("raider-blade", "raider-coin"),
                loot.available().stream().map(lot -> lot.holding().lotId()).toList());
        loot.lots().forEach(lot -> assertNull(lot.owner()));
        // The bow equipped on the archer still fighting is never claimable, so it cannot reach the player's pack.
        assertEquals(new Refused(Refusal.NOT_AVAILABLE), loot.claim("archer-bow", PLAYER));
        assertEquals(new Refused(Refusal.NOT_AVAILABLE), loot.claim("captain-blade", PLAYER));
        assertEquals(new Refused(Refusal.NOT_AVAILABLE), loot.claim("guide-rope", PLAYER));
        assertEquals(new Refused(Refusal.UNKNOWN_LOT), loot.claim("chest", PLAYER));
    }

    /** A claim moves a whole lot once; a second claim by anybody is refused and no unit is created or lost. */
    @Test
    void eachLotIsClaimedOnceAndUnitsAreConserved() {
        var loot = EncounterLoot.open(FIGHT, outcome(Kind.VICTORY), STANDINGS, HOLDINGS);
        var units = loot.units();
        assertEquals(Map.of("blade", 2, "bow", 1, "coin", 15, "rope", 1), units);

        var first = assertInstanceOf(Claimed.class, loot.claim("raider-blade", PLAYER));
        assertEquals(new Transfer("loot:" + FIGHT + ":raider-blade", "raider", PLAYER, "blade", 1, true), first.transfer());
        var after = first.loot();
        assertEquals(PLAYER, after.lots().getFirst().owner());
        assertEquals(new Refused(Refusal.ALREADY_CLAIMED), after.claim("raider-blade", PLAYER));
        assertEquals(new Refused(Refusal.ALREADY_CLAIMED), after.claim("raider-blade", COMPANION));
        // The original value is unchanged: a stale read cannot see the move, which its commit then refuses.
        assertEquals(State.AVAILABLE, loot.lots().getFirst().state());

        var second = assertInstanceOf(Claimed.class, after.claim("raider-coin", COMPANION));
        assertEquals(new Transfer("loot:" + FIGHT + ":raider-coin", "raider", COMPANION, "coin", 12, false),
                second.transfer());
        assertEquals(List.of(), second.loot().available());
        assertEquals(units, second.loot().units());
    }

    /** After a defeat or the player's surrender nothing opens, while an escape, agreement or objective leaves the fallen's lots. */
    @Test
    void aLostFieldOpensNothing() {
        for (Kind kind : List.of(Kind.DEFEAT, Kind.PLAYER_SURRENDER)) {
            assertEquals(List.of(), EncounterLoot.open(FIGHT, outcome(kind), STANDINGS, HOLDINGS).available());
        }
        for (Kind kind : List.of(Kind.ESCAPE, Kind.AGREEMENT, Kind.OBJECTIVE)) {
            assertEquals(2, EncounterLoot.open(FIGHT, outcome(kind), STANDINGS, HOLDINGS).available().size());
        }
    }

    /** Inconsistent inputs are refused: the player's own items, unknown holders, duplicates, bad quantities. */
    @Test
    void inconsistentInputsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Standing("player", Side.HOSTILE, Status.DEFEATED));
        assertThrows(IllegalArgumentException.class, () -> new Standing("raider", Side.PLAYER, Status.DEFEATED));
        assertThrows(IllegalArgumentException.class, () -> new Holding("lot", "raider", "coin", 0, false));
        assertThrows(IllegalArgumentException.class, () -> EncounterLoot.open(FIGHT, outcome(Kind.VICTORY), STANDINGS,
                List.of(new Holding("lot", "stranger", "coin", 1, false))));
        assertThrows(IllegalArgumentException.class, () -> EncounterLoot.open(FIGHT, outcome(Kind.VICTORY), STANDINGS,
                List.of(new Holding("lot", "raider", "coin", 1, false), new Holding("lot", "archer", "coin", 1, false))));
        assertThrows(IllegalArgumentException.class, () -> EncounterLoot.open(FIGHT, outcome(Kind.VICTORY),
                List.of(STANDINGS.getFirst(), STANDINGS.getFirst()), List.of()));
        assertThrows(NullPointerException.class, () -> EncounterLoot.open(FIGHT, null, STANDINGS, HOLDINGS));
    }

    /** A verdict of this kind. */
    private static EncounterOutcome outcome(Kind kind) {
        String objective = kind == Kind.OBJECTIVE ? "hold" : null;
        String terms = kind == Kind.AGREEMENT ? "truce" : null;
        return new EncounterOutcome(kind, objective, terms, Map.of());
    }
}
