package org.rpgengine.combat;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.rules.ZoneGraph;

/**
 * Prose-free projection of one pack {@code encounter} definition (P11.1, docs/09 section 4): where the encounter
 * can start, its zone graph, the starting zones, the participants with their explicit validated statistics,
 * the objectives and the opening-action policy. Names and descriptions stay in the pack's localized text; this
 * value carries only what the rules read.
 *
 * <p>The pack compiler has already reported every authoring error with a located diagnostic; the constructor
 * repeats the structural invariants so a value built in code (tests, a future proposal pipeline) cannot be
 * inconsistent either.
 *
 * @param id canonical template ID (local ID for the root pack, {@code pack#id} for a dependency)
 * @param pack declaring pack ID; its combat profile applies
 * @param location canonical location ID where the player can engage
 * @param zones the encounter space
 * @param playerZone zone the player starts in
 * @param escapeZones zones whose reach completes an escape, sorted; may be empty
 * @param participants non-player participants in authored order
 * @param objectives objectives in authored order, at least one
 * @param opening opening-action policy
 * @param actions declared social and scene actions in authored order (P11.2), possibly none
 */
public record EncounterTemplate(String id, String pack, String location, ZoneGraph zones, String playerZone,
        Set<String> escapeZones, List<Participant> participants, List<EncounterObjective> objectives,
        OpeningPolicy opening, List<EncounterAction> actions) {
    /** Participant ID reserved for the player in a frozen roster. */
    public static final String PLAYER = "player";
    /** Most non-player participants of one template. */
    public static final int MAX_PARTICIPANTS = org.rpgengine.rules.CombatProfile.PARTICIPANTS_MAX;
    /** Most objectives of one template. */
    public static final int MAX_OBJECTIVES = 8;
    /** Hostile bodies allowed beyond the party size (docs/12 section 4, default roster limit). */
    public static final int EXTRA_HOSTILES = 2;

    /** Which side a participant fights on. */
    public enum Side {
        /** The protagonist. */
        PLAYER,
        /** A companion fighting with the player. */
        ALLY,
        /** An opponent. */
        HOSTILE;

        /** @return the lowercase wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Catalog threat tier; its cost and level scaling are applied by encounter planning (P13.1). */
    public enum Threat {
        MINOR, STANDARD, ELITE, ANCHOR;

        /** @return the lowercase wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Explicit validated statistics of a template participant (docs/11 section 4: creature and archetype
     * templates provide their own Health, defenses and attack attributes instead of the player formulas). They
     * are recorded in the frozen roster and cannot be improvised during the fight.
     *
     * @param health Health maximum, 1..999; the participant starts unhurt
     * @param focus Focus maximum, 0..999; 0 means the participant has no Focus mechanic
     * @param guard Guard defense, 0..40
     * @param resistance Resistance defense, 0..40
     * @param armor flat armor, 0..the profile's {@code armorMax}
     * @param roles role attribute values (force, finesse, insight, resolve), each 0..6
     */
    public record Stats(int health, int focus, int guard, int resistance, int armor, Map<String, Integer> roles) {
        /** Bound every value and require all four roles. */
        public Stats {
            within(health, 1, 999, "health");
            within(focus, 0, 999, "focus");
            within(guard, 0, 40, "guard");
            within(resistance, 0, 40, "resistance");
            within(armor, 0, 10, "armor");
            if (!roles.keySet().equals(new HashSet<>(org.rpgengine.rules.CombatProfile.ROLES))) {
                throw new IllegalArgumentException("participant statistics give every role");
            }
            roles.forEach((role, value) -> within(value, org.rpgengine.rules.CombatProfile.ROLE_MIN,
                    org.rpgengine.rules.CombatProfile.ROLE_MAX, role));
            roles = Collections.unmodifiableMap(new java.util.TreeMap<>(roles));
        }
    }

    /**
     * One non-player participant.
     *
     * @param id participant ID, unique within the template and never {@link #PLAYER}
     * @param side {@link Side#ALLY} or {@link Side#HOSTILE}
     * @param zone starting zone
     * @param npc canonical NPC definition this participant portrays, or null for a template-only creature
     * @param threat catalog threat tier
     * @param stats explicit statistics
     * @param attacks attacks the participant can make (P11.3); without any it passes its activations
     * @param reactions reactions it declares beside Brace, each with its own policy (P11.4)
     * @param policies predeclared reaction policies by reaction ID; Brace first by default (P11.4)
     * @param conditions canonical conditions it enters the fight with (P11.4)
     */
    public record Participant(String id, Side side, String zone, String npc, Threat threat, Stats stats,
            List<CombatAttack> attacks, List<CombatReaction> reactions, Map<String, CombatReaction.Policy> policies,
            List<String> conditions) {
        /** Participant that cannot attack, the shape used before P11.3. */
        public Participant(String id, Side side, String zone, String npc, Threat threat, Stats stats) {
            this(id, side, zone, npc, threat, stats, List.of());
        }

        /** Participant without reactions or conditions, the default Brace first; the shape used before P11.4. */
        public Participant(String id, Side side, String zone, String npc, Threat threat, Stats stats,
                List<CombatAttack> attacks) {
            this(id, side, zone, npc, threat, stats, attacks, List.of(),
                    Map.of(CombatReaction.BRACE_ID, CombatReaction.Policy.FIRST), List.of());
        }

        /** Require identities and a non-player side. */
        public Participant {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(threat, "threat");
            Objects.requireNonNull(stats, "stats");
            attacks = List.copyOf(attacks);
            reactions = List.copyOf(reactions);
            policies = Map.copyOf(policies);
            conditions = List.copyOf(conditions);
            if (side == null || side == Side.PLAYER || PLAYER.equals(id)) {
                throw new IllegalArgumentException("a template participant is an ally or a hostile and is not the player");
            }
        }
    }

    /** Template without declared actions, the shape used before P11.2. */
    public EncounterTemplate(String id, String pack, String location, ZoneGraph zones, String playerZone,
            Set<String> escapeZones, List<Participant> participants, List<EncounterObjective> objectives,
            OpeningPolicy opening) {
        this(id, pack, location, zones, playerZone, escapeZones, participants, objectives, opening, List.of());
    }

    /** Check the structural invariants and freeze the collections. */
    public EncounterTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(zones, "zones");
        Objects.requireNonNull(opening, "opening");
        zone(zones, playerZone, "player zone");
        escapeZones = Collections.unmodifiableSet(new TreeSet<>(escapeZones));
        for (String zone : escapeZones) {
            zone(zones, zone, "escape zone");
            if (zone.equals(playerZone)) {
                throw new IllegalArgumentException("the player cannot start in an escape zone");
            }
            if (!zones.reachable(playerZone).contains(zone)) {
                throw new IllegalArgumentException("escape zone " + zone + " cannot be reached from the player zone");
            }
        }
        participants = List.copyOf(participants);
        objectives = List.copyOf(objectives);
        if (participants.isEmpty() || participants.size() > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("an encounter has 1.." + MAX_PARTICIPANTS + " participants");
        }
        if (objectives.isEmpty() || objectives.size() > MAX_OBJECTIVES) {
            throw new IllegalArgumentException("an encounter has 1.." + MAX_OBJECTIVES + " objectives");
        }
        var ids = new HashSet<String>();
        var occupancy = new LinkedHashMap<String, Integer>(Map.of(playerZone, 1));
        int hostiles = 0;
        int allies = 0;
        for (Participant participant : participants) {
            if (!ids.add(participant.id())) {
                throw new IllegalArgumentException("duplicate participant " + participant.id());
            }
            zone(zones, participant.zone(), "participant zone");
            occupancy.merge(participant.zone(), 1, Integer::sum);
            if (participant.side() == Side.HOSTILE) {
                hostiles++;
            } else {
                allies++;
            }
        }
        occupancy.forEach((zone, count) -> {
            int capacity = zones.zones().get(zone).capacity();
            if (capacity > 0 && count > capacity) {
                throw new IllegalArgumentException("zone " + zone + " starts with " + count + " occupants; capacity "
                        + capacity);
            }
        });
        if (hostiles == 0) {
            throw new IllegalArgumentException("an encounter needs at least one hostile participant");
        }
        if (hostiles > 1 + allies + EXTRA_HOSTILES) {
            throw new IllegalArgumentException(hostiles + " hostile participants exceed the party size " + (1 + allies)
                    + " plus " + EXTRA_HOSTILES);
        }
        var objectiveIds = new HashSet<String>();
        for (EncounterObjective objective : objectives) {
            if (!objectiveIds.add(objective.id())) {
                throw new IllegalArgumentException("duplicate objective " + objective.id());
            }
            switch (objective.kind()) {
                case ESCAPE -> {
                    if (escapeZones.isEmpty()) {
                        throw new IllegalArgumentException("an escape objective needs an escape zone");
                    }
                }
                case REACH_ZONE -> {
                    zone(zones, objective.zone(), "objective zone");
                    if (objective.zone().equals(playerZone) || !zones.reachable(playerZone).contains(objective.zone())) {
                        throw new IllegalArgumentException("objective zone " + objective.zone()
                                + " must differ from the player zone and be reachable from it");
                    }
                }
                case PROTECT -> {
                    if (participants.stream().noneMatch(p -> p.id().equals(objective.participant()) && p.side() == Side.ALLY)) {
                        throw new IllegalArgumentException("a protect objective names an allied participant");
                    }
                }
                default -> { }
            }
        }
        actions = List.copyOf(actions);
        if (actions.size() > EncounterAction.MAX_ACTIONS) {
            throw new IllegalArgumentException("an encounter declares at most " + EncounterAction.MAX_ACTIONS + " actions");
        }
        var actionIds = new HashSet<String>();
        for (EncounterAction action : actions) {
            if (!actionIds.add(action.id())) {
                throw new IllegalArgumentException("duplicate action " + action.id());
            }
            if (action.kind() == EncounterAction.Kind.SCENE) {
                if (action.zone() != null) {
                    zone(zones, action.zone(), "action zone");
                }
                var passage = action.passage();
                zone(zones, passage.from(), "passage zone");
                zone(zones, passage.to(), "passage zone");
                if (zones.edges().stream().noneMatch(edge -> passage.covers(edge.from(), edge.to()))) {
                    throw new IllegalArgumentException("action " + action.id() + " names no declared passage");
                }
            }
        }
    }

    /** @return the participant with this ID, or null */
    public Participant participant(String participantId) {
        return participants.stream().filter(p -> p.id().equals(participantId)).findFirst().orElse(null);
    }

    /** Fail unless a zone is declared. */
    private static void zone(ZoneGraph zones, String zone, String role) {
        if (zone == null || !zones.zones().containsKey(zone)) {
            throw new IllegalArgumentException(role + " " + zone + " is not a declared zone");
        }
    }

    /** Fail unless a value is within an inclusive bound. */
    private static void within(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be within " + min + ".." + max);
        }
    }
}
