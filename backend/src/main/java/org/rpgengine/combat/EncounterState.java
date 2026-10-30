package org.rpgengine.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Detached tactical state of one active encounter as read under the campaign lock (P11.2): the lifecycle cursor
 * (phase, round, acting initiative rank, remaining action points, speech used in the current activation), the
 * passage states changed by scene actions, the frozen profile, space, escape zones and declared actions read back
 * from the stored canonical definition, and every participant's mutable tactical facts. It is a value: the pure
 * {@link TacticalRules} and {@link CombatScheduler} read it and the canonical commit writes their plan guarded by
 * the row versions recorded here.
 *
 * <p>Invariants (also enforced by V22/V23): {@code cursor} and {@code actionPoints} are set exactly while an
 * activation is in progress (phases {@code ACTOR_START} through {@code ACTOR_END}); initiative ranks are a
 * permutation of {@code 0..n-1} once initiative is rolled and absent before.
 *
 * @param id encounter identity
 * @param rowVersion storage version of the encounter row
 * @param phase lifecycle phase
 * @param round current round, 0 before initiative
 * @param cursor initiative rank of the acting participant, or null between activations
 * @param actionPoints remaining action points of the acting participant, or null between activations
 * @param speechUsed speech-only utterances already made in the current activation
 * @param passages passage states set by scene actions, one per zone pair, in the stored order
 * @param profile frozen combat profile
 * @param frozenSpace frozen zone graph, before passage changes
 * @param escapeZones frozen escape zones
 * @param actions frozen declared social and scene actions
 * @param opening frozen opening-action policy
 * @param openingTarget declared opening strike target, or null
 * @param openingStatus {@code NONE}, {@code PENDING} or {@code RESOLVED}
 * @param participants every participant in roster order (the player first)
 * @param ammunition the player's unworn stacks of the items its frozen attacks consume, in identity order (P11.3)
 */
public record EncounterState(UUID id, long rowVersion, EncounterPhase phase, int round, Integer cursor,
        Integer actionPoints, int speechUsed, List<EncounterAction.Passage> passages, CombatProfile profile,
        ZoneGraph frozenSpace, Set<String> escapeZones, List<EncounterAction> actions, OpeningPolicy opening,
        String openingTarget, String openingStatus, List<Combatant> participants, List<Ammunition> ammunition) {
    /** Tactical state without stacks of ammunition, for fights whose attacks consume nothing. */
    public EncounterState(UUID id, long rowVersion, EncounterPhase phase, int round, Integer cursor,
            Integer actionPoints, int speechUsed, List<EncounterAction.Passage> passages, CombatProfile profile,
            ZoneGraph frozenSpace, Set<String> escapeZones, List<EncounterAction> actions, OpeningPolicy opening,
            String openingTarget, String openingStatus, List<Combatant> participants) {
        this(id, rowVersion, phase, round, cursor, actionPoints, speechUsed, passages, profile, frozenSpace, escapeZones,
                actions, opening, openingTarget, openingStatus, participants, List.of());
    }

    /**
     * One stack of ammunition the player can spend.
     *
     * @param instance item instance
     * @param definition canonical item definition
     * @param quantity units left
     */
    public record Ammunition(java.util.UUID instance, String definition, int quantity) {
        /** Require identities. */
        public Ammunition {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(definition, "definition");
        }
    }

    /**
     * Frozen fighting values of one participant (P11.3), read back from its frozen profile.
     *
     * @param guard Guard before any stance or cover
     * @param resistance Resistance
     * @param armor flat armor
     * @param roles role attribute values
     * @param attacks frozen attacks in stored order; empty when the participant cannot attack
     */
    public record Sheet(int guard, int resistance, int armor, Map<String, Integer> roles, List<CombatAttack> attacks) {
        /** A participant with no fighting values, for scheduling that reads none of them. */
        public static final Sheet NONE = new Sheet(0, 0, 0, Map.of(), List.of());

        /** Freeze the collections. */
        public Sheet {
            roles = Map.copyOf(roles);
            attacks = List.copyOf(attacks);
        }

        /** @return the value of a role, zero when the sheet has none */
        public int role(String role) {
            return roles.getOrDefault(role, 0);
        }

        /** @return the frozen attack with this ID, or null */
        public CombatAttack attack(String id) {
            return attacks.stream().filter(attack -> attack.id().equals(id)).findFirst().orElse(null);
        }

        /**
         * Read the fighting values from a stored frozen participant form. Encounters frozen before P11.3 have no
         * {@code attacks} member: their participants simply cannot attack.
         *
         * @param frozen parsed canonical participant
         * @return the sheet
         * @throws ClassCastException or {@link IllegalArgumentException} for a malformed form
         */
        public static Sheet fromCanonical(Map<?, ?> frozen) {
            var roles = new java.util.HashMap<String, Integer>();
            ((Map<?, ?>) frozen.get("roles")).forEach((role, value) ->
                    roles.put((String) role, ((Number) value).intValue()));
            var attacks = new java.util.ArrayList<CombatAttack>();
            if (frozen.get("attacks") instanceof List<?> stored) {
                stored.forEach(attack -> attacks.add(CombatAttack.fromCanonical((Map<?, ?>) attack)));
            }
            return new Sheet(((Number) frozen.get("guard")).intValue(), ((Number) frozen.get("resistance")).intValue(),
                    ((Number) frozen.get("armor")).intValue(), roles, attacks);
        }
    }

    /** Tactical standing of a participant; only {@link #ACTIVE} participants take activations. */
    public enum Status {
        ACTIVE, DEFEATED, ESCAPED, SURRENDERED
    }

    /**
     * Mutable tactical facts of one participant plus the frozen values the scheduler reads.
     *
     * @param id roster ID ({@link EncounterTemplate#PLAYER} for the protagonist)
     * @param ordinal roster position, 0 for the player
     * @param actorId campaign actor behind the participant, or null
     * @param side fighting side
     * @param zone current zone
     * @param status tactical standing
     * @param finesse frozen finesse role value, the first initiative tie-break
     * @param initiativeModifier frozen initiative modifier
     * @param initiative rolled initiative total, or null before initiative
     * @param rank initiative rank (0 acts first), or null before initiative
     * @param reactions reactions left this round
     * @param rowVersion storage version of the participant row
     * @param health current Health above the pool's minimum (P11.3)
     * @param healthMax Health maximum above the pool's minimum
     * @param guarding whether its Guard action holds until the start of its next activation
     * @param sheet frozen fighting values
     */
    public record Combatant(String id, int ordinal, UUID actorId, EncounterTemplate.Side side, String zone,
            Status status, int finesse, int initiativeModifier, Integer initiative, Integer rank, int reactions,
            long rowVersion, int health, int healthMax, boolean guarding, Sheet sheet) {
        /** Participant for scheduling alone: unhurt at one Health, not guarding, no fighting values. */
        public Combatant(String id, int ordinal, UUID actorId, EncounterTemplate.Side side, String zone,
                Status status, int finesse, int initiativeModifier, Integer initiative, Integer rank, int reactions,
                long rowVersion) {
            this(id, ordinal, actorId, side, zone, status, finesse, initiativeModifier, initiative, rank, reactions,
                    rowVersion, 1, 1, false, Sheet.NONE);
        }

        /** Require identities and Health inside its bounds. */
        public Combatant {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(sheet, "sheet");
            if ((initiative == null) != (rank == null)) {
                throw new IllegalArgumentException("initiative and rank are set together");
            }
            if (health < 0 || health > healthMax) {
                throw new IllegalArgumentException("Health outside its bounds");
            }
        }

        /** @return whether the two participants fight on opposing sides */
        public boolean opposes(Combatant other) {
            return (side == EncounterTemplate.Side.HOSTILE) != (other.side == EncounterTemplate.Side.HOSTILE);
        }

        /** @return whether this is the protagonist */
        public boolean player() {
            return side == EncounterTemplate.Side.PLAYER;
        }

        /** @return whether the participant still occupies a zone (an escaped participant has left the space) */
        public boolean present() {
            return status != Status.ESCAPED;
        }
    }

    /** Copy the collections and check the cursor invariant. */
    public EncounterState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(frozenSpace, "frozenSpace");
        Objects.requireNonNull(opening, "opening");
        passages = List.copyOf(passages);
        escapeZones = java.util.Collections.unmodifiableSet(new TreeSet<>(escapeZones));
        actions = List.copyOf(actions);
        participants = List.copyOf(participants);
        ammunition = List.copyOf(ammunition);
        if ((cursor == null) != (actionPoints == null)) {
            throw new IllegalArgumentException("cursor and action points are set together");
        }
        if (participants.isEmpty() || !participants.getFirst().player()) {
            throw new IllegalArgumentException("the player is the first participant");
        }
    }

    /** @return the current space: the frozen graph with every passage change applied in order */
    public ZoneGraph space() {
        ZoneGraph space = frozenSpace;
        for (EncounterAction.Passage passage : passages) {
            space = space.withPassage(passage.from(), passage.to(), passage.blocked());
        }
        return space;
    }

    /** @return the protagonist's participant */
    public Combatant player() {
        return participants.getFirst();
    }

    /**
     * @param participantId a roster ID
     * @return the participant, or null when the roster has none with this ID
     */
    public Combatant participant(String participantId) {
        return participants.stream().filter(p -> p.id().equals(participantId)).findFirst().orElse(null);
    }

    /** @return the acting participant, or null between activations */
    public Combatant acting() {
        if (cursor == null) {
            return null;
        }
        return participants.stream().filter(p -> cursor.equals(p.rank())).findFirst().orElse(null);
    }

    /** @return participants in initiative order; empty before initiative is rolled */
    public List<Combatant> order() {
        return participants.stream().filter(p -> p.rank() != null).sorted(Comparator.comparing(Combatant::rank)).toList();
    }

    /** @return the declared action with this ID, or null */
    public EncounterAction action(String actionId) {
        return actions.stream().filter(action -> action.id().equals(actionId)).findFirst().orElse(null);
    }

    /**
     * @param definition canonical item definition
     * @return unworn units of it the player can spend
     */
    public int ammunitionOf(String definition) {
        return ammunition.stream().filter(stack -> stack.definition().equals(definition))
                .mapToInt(Ammunition::quantity).sum();
    }

    /**
     * Whether the player may decide now: the encounter waits for an action of the acting player, who still stands.
     * Tactical intentions other than the initiative roll are accepted only at this boundary (docs/11 section 7.1:
     * no out-of-turn submission).
     *
     * @return true at the player's own {@code AWAITING_ACTION}
     */
    public boolean playerDecision() {
        Combatant acting = acting();
        return phase == EncounterPhase.AWAITING_ACTION && acting != null && acting.player()
                && acting.status() == Status.ACTIVE;
    }
}
