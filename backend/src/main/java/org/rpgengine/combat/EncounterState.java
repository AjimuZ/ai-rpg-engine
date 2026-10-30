package org.rpgengine.combat;

import java.util.Comparator;
import java.util.List;
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
 */
public record EncounterState(UUID id, long rowVersion, EncounterPhase phase, int round, Integer cursor,
        Integer actionPoints, int speechUsed, List<EncounterAction.Passage> passages, CombatProfile profile,
        ZoneGraph frozenSpace, Set<String> escapeZones, List<EncounterAction> actions, OpeningPolicy opening,
        String openingTarget, String openingStatus, List<Combatant> participants) {
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
     */
    public record Combatant(String id, int ordinal, UUID actorId, EncounterTemplate.Side side, String zone,
            Status status, int finesse, int initiativeModifier, Integer initiative, Integer rank, int reactions,
            long rowVersion) {
        /** Require identities. */
        public Combatant {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(status, "status");
            if ((initiative == null) != (rank == null)) {
                throw new IllegalArgumentException("initiative and rank are set together");
            }
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
