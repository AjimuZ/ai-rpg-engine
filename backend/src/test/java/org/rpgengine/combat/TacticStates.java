package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * A fixed fight for the non-player decision tests (P12.3), seeded with values the deciding participant must never learn.
 *
 * <p>Space: {@code ring} joins {@code mid} and the obscured {@code fog}; {@code mid} joins {@code far}. Roster, in order: the
 * player in {@code mid} (guarding, campaign actor {@link #PLAYER_ACTOR}, attack {@link #PLAYER_ATTACK}, a stack of
 * {@link #PLAYER_AMMUNITION}); the hostile {@code bandit} in {@code ring} (campaign actor {@link #BANDIT_ACTOR}, a melee
 * {@code cudgel} and a range-1 {@code sling}); the allied {@link #HIDDEN} in {@code fog}, whom nobody outside {@code fog} can
 * see; and the allied companion {@code scout} in {@code ring} with a melee {@code knife}. Initiative ranks follow roster order
 * except that the bandit acts first, and the default profile grants three points.
 */
public final class TacticStates {
    /** Campaign actor of the player; never part of another participant's knowledge. */
    public static final UUID PLAYER_ACTOR = UUID.fromString("00000000-0000-4000-8000-00000000a5e1");
    /** Campaign actor of the bandit, the subject of its character audience. */
    public static final UUID BANDIT_ACTOR = UUID.fromString("00000000-0000-4000-8000-00000000b4d1");
    /** Roster ID of the participant hidden in the obscured zone. */
    public static final String HIDDEN = "warden-sentinel";
    /** The player's own attack, private to the player. */
    public static final String PLAYER_ATTACK = "sentinel-blade";
    /** The player's ammunition definition, private to the player. */
    public static final String PLAYER_AMMUNITION = "item.sentinel-bolts";
    /** Encounter identity. */
    public static final UUID ENCOUNTER = UUID.fromString("00000000-0000-4000-8000-00000000e1c0");

    private TacticStates() {
    }

    /** @return the fight at the bandit's decision of round one with three points */
    public static EncounterState banditDecides() {
        return deciding(0, 3, List.of());
    }

    /** @return the fight at the companion scout's decision of round one with three points */
    public static EncounterState scoutDecides() {
        return deciding(3, 3, List.of());
    }

    /**
     * The fight at one participant's decision.
     *
     * @param rank initiative rank of the deciding participant: 0 the bandit, 1 the player, 2 the hidden ally, 3 the scout
     * @param points action points left
     * @param banditEffects active effects of the bandit
     * @return the state
     */
    public static EncounterState deciding(int rank, int points, List<CombatEffects.Active> banditEffects) {
        return state(rank, points, banditEffects, Map.of(), false);
    }

    /**
     * The fight at one participant's decision with chosen effect definitions and the bandit's stance.
     *
     * @param rank initiative rank of the deciding participant
     * @param points action points left
     * @param banditEffects active effects of the bandit
     * @param definitions frozen effect definitions by ID
     * @param banditGuarding whether the bandit's Guard action holds
     * @return the state
     */
    public static EncounterState state(int rank, int points, List<CombatEffects.Active> banditEffects,
            Map<String, CombatEffect> definitions, boolean banditGuarding) {
        var roles = Map.of("force", 1, "finesse", 1, "insight", 1, "resolve", 1);
        var player = new EncounterState.Combatant("player", 0, PLAYER_ACTOR, EncounterTemplate.Side.PLAYER, "mid",
                EncounterState.Status.ACTIVE, 2, 4, 20, 1, 1, 0, 24, 24, true,
                new EncounterState.Sheet(17, 13, 2, roles, List.of(attack(PLAYER_ATTACK, 0))));
        var bandit = new EncounterState.Combatant("bandit", 1, BANDIT_ACTOR, EncounterTemplate.Side.HOSTILE, "ring",
                EncounterState.Status.ACTIVE, 1, 2, 22, 0, 1, 0, 9, 12, banditGuarding,
                new EncounterState.Sheet(11, 10, 0, roles, List.of(attack("cudgel", 0), attack("sling", 1))), 0, 0,
                banditEffects, Map.of(), Map.of());
        var hidden = new EncounterState.Combatant(HIDDEN, 2, null, EncounterTemplate.Side.ALLY, "fog",
                EncounterState.Status.ACTIVE, 1, 2, 15, 2, 1, 0, 7, 7, false,
                new EncounterState.Sheet(10, 10, 0, roles, List.of(attack("sentinel-hook", 0))));
        var scout = new EncounterState.Combatant("scout", 3, null, EncounterTemplate.Side.ALLY, "ring",
                EncounterState.Status.ACTIVE, 1, 2, 10, 3, 1, 0, 8, 8, false,
                new EncounterState.Sheet(10, 10, 0, roles, List.of(attack("knife", 0))));
        var profile = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp",
                null, Map.of());
        return new EncounterState(ENCOUNTER, 4, EncounterPhase.AWAITING_ACTION, 1, rank, points, 0, List.of(), profile,
                space(), Set.of(), List.of(), OpeningPolicy.INITIATIVE, null, "NONE", List.of(player, bandit, hidden, scout),
                List.of(new EncounterState.Ammunition(UUID.fromString("00000000-0000-4000-8000-00000000a111"),
                        PLAYER_AMMUNITION, 5)), definitions, 0);
    }

    /** A plain attack of one d6 against Guard. */
    private static CombatAttack attack(String id, int range) {
        return new CombatAttack(id, "force", 0, 1, 6, "force", 0, 0, range, CombatAttack.Defense.GUARD, false, null);
    }

    /** {@code ring}-{@code mid}, {@code ring}-{@code fog} (obscured) and {@code mid}-{@code far}, both ways. */
    private static ZoneGraph space() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        for (String id : List.of("ring", "mid", "far")) {
            zones.put(id, new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, false, 0));
        }
        zones.put("fog", new ZoneGraph.Zone("fog", Set.of(), ZoneGraph.Cover.NONE, true, 0));
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (String[] pair : List.of(new String[] {"ring", "mid"}, new String[] {"ring", "fog"}, new String[] {"mid", "far"})) {
            edges.add(new ZoneGraph.Edge(pair[0], pair[1], false, false));
            edges.add(new ZoneGraph.Edge(pair[1], pair[0], false, false));
        }
        return new ZoneGraph(zones, edges);
    }
}
