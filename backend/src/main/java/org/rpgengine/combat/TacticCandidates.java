package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.rules.ZoneGraph;

/**
 * Knowledge-scoped legal candidates of one non-player participant's decision (P12.3, docs/11 section 12, docs/21
 * section 7): what the acting participant may choose and what it observes, read from the encounter as it stands at that
 * participant's {@code AWAITING_ACTION}. Pure: the same state always gives the same {@link Menu}.
 *
 * <p><b>Knowledge.</b> The acting participant observes itself and every other present participant it can see: one in its own
 * zone, or one in a zone that is not obscured (the line-of-sight rule of {@link TacticalRules#attackBlock}). For another
 * participant it learns only the public standing: roster ID, whether it is an ally or an opponent, zone, status and whether its
 * Guard action holds. It never learns anyone else's Health, Focus, Guard, Resistance, armor, attacks, abilities, effects,
 * reaction policies or campaign actor, the player's ammunition or items, the declared actions, objectives, offers or the
 * initiative totals. A participant hidden in an obscured zone appears nowhere: not as an observation and not as a target.
 *
 * <p><b>Candidates.</b> Only choices the scheduler can carry out for a non-player participant are listed, each in a fixed
 * order so a test or a fallback never depends on iteration order:
 * <ol>
 *   <li>{@code move}: every zone one legal edge away ({@link TacticalRules#moveCost}) that the remaining points pay for;</li>
 *   <li>{@code strike:<attackId>}: each frozen attack, in stored order, with the observed opponents it reaches now, when
 *       {@code strikeCost} is affordable;</li>
 *   <li>{@code guard}: the Guard action, once per activation, when {@code guardCost} is affordable;</li>
 *   <li>{@code end-activation}: always, so a participant is never without a legal choice.</li>
 * </ol>
 * An effect that prevents actions leaves only {@code end-activation}; one that prevents movement removes {@code move}. A
 * candidate with nothing to choose (no destination, no reachable target) is not listed: these are legal choices, not the
 * player's disabled controls. Abilities, items, speech, social and scene actions, readying, assessing and withdrawing are not
 * candidates yet, because the scheduler does not perform them for non-player participants.
 *
 * <p>A chosen candidate is still only a proposal: {@link Menu#admits} decides it against a menu read again from the
 * committed state, so a choice made over an older state is refused rather than forced (docs/21 section 7).
 */
public final class TacticCandidates {
    /** Most candidates of one decision; frozen attacks beyond this bound are not offered, in stored order. */
    public static final int MAX_CANDIDATES = 32;
    /** Most observed participants of one decision; a roster is far smaller. */
    public static final int MAX_OBSERVED = 32;
    /** Candidate identity of the move. */
    public static final String MOVE = "move";
    /** Candidate identity of the Guard action. */
    public static final String GUARD = "guard";
    /** Candidate identity of the explicit end of the activation. */
    public static final String END_ACTIVATION = "end-activation";
    /** Prefix of a strike candidate's identity; the frozen attack ID follows. */
    public static final String STRIKE_PREFIX = "strike:";

    /** Static entry point only. */
    private TacticCandidates() {
    }

    /** What a candidate does. */
    public enum Kind {
        /** One edge to a listed destination. */
        MOVE,
        /** One frozen attack at a listed target. */
        STRIKE,
        /** The Guard action. */
        GUARD,
        /** End the activation; unused points expire. */
        END_ACTIVATION;

        /** @return the lowercase wire name, for example {@code end-activation} */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * One reachable destination of the move.
     *
     * @param zone zone ID
     * @param cost action points the edge costs
     */
    public record Destination(String zone, int cost) {
        /** Require the zone and a non-negative cost. */
        public Destination {
            Objects.requireNonNull(zone, "zone");
            if (cost < 0) {
                throw new IllegalArgumentException("a move cost is never negative");
            }
        }
    }

    /**
     * One legal choice.
     *
     * @param id stable candidate identity: {@link #MOVE}, {@code strike:<attackId>}, {@link #GUARD} or {@link #END_ACTIVATION}
     * @param kind what it does
     * @param cost action points it costs; for a move, the cheapest destination's cost
     * @param attackId frozen attack of a strike, otherwise null
     * @param targets roster IDs a strike may hit, nearest then roster order; empty for other kinds
     * @param destinations zones a move may reach, in zone order; empty for other kinds
     */
    public record Candidate(String id, Kind kind, int cost, String attackId, List<String> targets,
            List<Destination> destinations) {
        /** Require identities; copy the lists. */
        public Candidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            targets = List.copyOf(targets);
            destinations = List.copyOf(destinations);
            if ((kind == Kind.STRIKE) != (attackId != null) || (kind == Kind.STRIKE) == targets.isEmpty()
                    || (kind == Kind.MOVE) == destinations.isEmpty()) {
                throw new IllegalArgumentException("a strike names its attack and targets, a move its destinations");
            }
        }
    }

    /**
     * Public standing of one participant the actor can see.
     *
     * @param participantId roster ID
     * @param opponent whether it fights on the side opposing the actor
     * @param zone its zone
     * @param status its tactical standing
     * @param guarding whether its Guard action holds
     */
    public record Observed(String participantId, boolean opponent, String zone, EncounterState.Status status,
            boolean guarding) {
        /** Require identities. */
        public Observed {
            Objects.requireNonNull(participantId, "participantId");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Everything one non-player decision may use: the actor's own values, what it observes and its legal candidates.
     *
     * @param encounterId the encounter
     * @param rowVersion encounter row version the menu was read from
     * @param round current round
     * @param actorId roster ID of the acting participant
     * @param campaignActor campaign actor behind the acting participant, or null for a template-only creature
     * @param zone the actor's zone
     * @param actionPoints points left in the activation
     * @param health the actor's own Health
     * @param healthMax the actor's own Health maximum
     * @param focus the actor's own Focus
     * @param focusMax the actor's own Focus maximum
     * @param guarding whether the actor's own Guard action holds
     * @param observed other participants it can see, in roster order
     * @param candidates legal choices in the class comment's order, ending with {@link #END_ACTIVATION}
     */
    public record Menu(UUID encounterId, long rowVersion, int round, String actorId, UUID campaignActor, String zone,
            int actionPoints, int health, int healthMax, int focus, int focusMax, boolean guarding, List<Observed> observed,
            List<Candidate> candidates) {
        /** Require identities; copy and bound the lists. */
        public Menu {
            Objects.requireNonNull(encounterId, "encounterId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(zone, "zone");
            observed = List.copyOf(observed);
            candidates = List.copyOf(candidates);
            if (observed.size() > MAX_OBSERVED || candidates.isEmpty() || candidates.size() > MAX_CANDIDATES
                    || !candidates.getLast().id().equals(END_ACTIVATION)) {
                throw new IllegalArgumentException("a menu holds bounded observations and ends with the end of the activation");
            }
        }

        /** @return the candidate with this identity, or null */
        public Candidate candidate(String id) {
            return candidates.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
        }

        /**
         * Whether a choice is one of this menu's candidates with a permitted parameter. This is the revalidation of a
         * proposal: read the menu again from the committed state and ask it.
         *
         * @param intent {@link TacticalIntent.MoveZone}, {@link TacticalIntent.Strike}, {@link TacticalIntent.Guard} or
         *        {@link TacticalIntent.EndActivation}; anything else is never admitted
         * @return true only when a listed candidate allows exactly this choice
         */
        public boolean admits(TacticalIntent intent) {
            return switch (intent) {
                case TacticalIntent.MoveZone move -> {
                    var candidate = candidate(MOVE);
                    yield candidate != null && candidate.destinations().stream().anyMatch(d -> d.zone().equals(move.zone()));
                }
                case TacticalIntent.Strike strike -> {
                    var candidate = candidate(STRIKE_PREFIX + strike.attack());
                    yield candidate != null && candidate.targets().contains(strike.target());
                }
                case TacticalIntent.Guard guard -> candidate(GUARD) != null;
                case TacticalIntent.EndActivation end -> true;
                default -> false;
            };
        }
    }

    /**
     * Read the menu of the acting non-player participant.
     *
     * @param state the encounter, at the acting participant's {@code AWAITING_ACTION}
     * @return the menu, or null when no standing non-player participant is deciding now (another phase, the player's own
     *         decision, or a participant out of the fight)
     */
    public static Menu menu(EncounterState state) {
        var actor = state.acting();
        if (state.phase() != EncounterPhase.AWAITING_ACTION || actor == null || actor.player()
                || actor.status() != EncounterState.Status.ACTIVE) {
            return null;
        }
        var space = state.space();
        var profile = state.profile();
        int points = state.actionPoints();
        var observed = new ArrayList<Observed>();
        for (var other : state.participants()) {
            if (!other.id().equals(actor.id()) && perceives(space, actor, other)) {
                observed.add(new Observed(other.id(), actor.opposes(other), other.zone(), other.status(), other.guarding()));
            }
        }
        var candidates = new ArrayList<Candidate>();
        boolean acts = !state.prevented(actor, CombatEffect.Prevents.ACTIONS);
        if (acts && !state.prevented(actor, CombatEffect.Prevents.MOVEMENT)) {
            var destinations = new ArrayList<Destination>();
            for (String zone : space.zones().keySet()) {
                int cost = TacticalRules.moveCost(state, actor.zone(), zone);
                if (cost >= 0 && cost <= points) {
                    destinations.add(new Destination(zone, cost));
                }
            }
            if (!destinations.isEmpty()) {
                int cheapest = destinations.stream().mapToInt(Destination::cost).min().orElseThrow();
                candidates.add(new Candidate(MOVE, Kind.MOVE, cheapest, null, List.of(), destinations));
            }
        }
        int strikeCost = profile.value("strikeCost");
        if (acts && strikeCost <= points) {
            for (CombatAttack attack : actor.sheet().attacks()) {
                if (candidates.size() >= MAX_CANDIDATES - 2) {
                    break;
                }
                var targets = state.participants().stream()
                        .filter(other -> perceives(space, actor, other)
                                && TacticalRules.attackBlock(space, actor, attack, other) == null)
                        .sorted(java.util.Comparator.comparingInt((EncounterState.Combatant other) ->
                                space.distance(actor.zone(), other.zone())).thenComparingInt(EncounterState.Combatant::ordinal))
                        .map(EncounterState.Combatant::id).toList();
                if (!targets.isEmpty()) {
                    candidates.add(new Candidate(STRIKE_PREFIX + attack.id(), Kind.STRIKE, strikeCost, attack.id(), targets,
                            List.of()));
                }
            }
        }
        int guardCost = profile.value("guardCost");
        if (acts && !actor.guarding() && guardCost <= points) {
            candidates.add(new Candidate(GUARD, Kind.GUARD, guardCost, null, List.of(), List.of()));
        }
        candidates.add(new Candidate(END_ACTIVATION, Kind.END_ACTIVATION, 0, null, List.of(), List.of()));
        return new Menu(state.id(), state.rowVersion(), state.round(), actor.id(), actor.actorId(), actor.zone(), points,
                actor.health(), actor.healthMax(), actor.focus(), actor.focusMax(), actor.guarding(), observed, candidates);
    }

    /**
     * Whether one participant can see another now.
     *
     * @param space the current zone graph
     * @param observer the participant looking
     * @param other the participant looked at
     * @return true for a different participant still in the space, in the observer's zone or in a zone that is not obscured;
     *         range does not limit sight, the same rule an attack's line of sight uses
     */
    public static boolean perceives(ZoneGraph space, EncounterState.Combatant observer, EncounterState.Combatant other) {
        return !observer.id().equals(other.id()) && other.present()
                && (observer.zone().equals(other.zone()) || !space.zones().get(other.zone()).obscured());
    }
}
