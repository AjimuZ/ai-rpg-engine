package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Pure legality of the player's tactical intentions at a decision boundary (P11.2, docs/11 sections 5-7.1). Every
 * rule reads only the {@link EncounterState} the caller loaded under the campaign lock, so admission, the prepare
 * step and the canonical commit reach the same verdict on the same rows. An illegal intention spends nothing: the
 * caller refuses it before any effect, draw or time exists.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Turn</b> — {@code ROLL_INITIATIVE} only at {@code ENTERING}; every other intention only at the
 *       player's own {@code AWAITING_ACTION} while the player stands ({@link EncounterState#playerDecision}).</li>
 *   <li><b>Movement</b> — one unblocked directed edge from the player's zone to another declared zone (scene
 *       changes included), priced by the frozen profile ({@code moveCost}, times the terrain multiplier on a
 *       difficult edge), into a zone below its capacity, and affordable from the remaining action points.</li>
 *   <li><b>Addressing</b> ({@link #canAddress}) — a different participant who is active or has surrendered,
 *       within {@code maxRangeEdges} range bands of the speaker (range ignores blocking: a closed gate does not
 *       stop a voice), spoken by a standing participant.</li>
 *   <li><b>Speech</b> — at most {@code speechPerActivation} speech-only utterances per player activation, at no
 *       action-point cost, to an addressable participant.</li>
 *   <li><b>Declared actions</b> — only actions the frozen encounter declares. A social action needs an addressable
 *       recipient its recipient policy admits; a scene action takes no recipient, is used from its zone when one is
 *       declared, and must change its passage (a passage already in the requested state is refused, not charged).
 *       Either costs its declared action points, which must be available.</li>
 * </ul>
 */
public final class TacticalRules {
    /** Static rules only. */
    private TacticalRules() {
    }

    /** Why a tactical intention is refused; the commands layer maps each to a public refusal category. */
    public enum Refusal {
        /** Not the player's decision boundary for this intention (out of turn, wrong phase, no standing player). */
        TURN,
        /** The intention costs more action points than remain in the activation. */
        ACTION_POINTS,
        /** The destination is unknown, the current zone, not one unblocked edge away or full. */
        ZONE,
        /** The recipient or target is missing, the player, of the wrong side, not standing or out of range. */
        TARGET,
        /** The speech-only utterances of this activation are used up. */
        SPEECH_LIMIT,
        /** The declared action does not exist, cannot be used from here or would change nothing. */
        ACTION
    }

    /**
     * Decide whether an intention is legal now.
     *
     * @param state the encounter as read under the campaign lock
     * @param intent the player's intention
     * @return null when legal, otherwise the first violated rule in the order turn, target or place, cost
     */
    public static Refusal refusal(EncounterState state, TacticalIntent intent) {
        if (intent instanceof TacticalIntent.RollInitiative) {
            return state.phase() == EncounterPhase.ENTERING ? null : Refusal.TURN;
        }
        if (!state.playerDecision()) {
            return Refusal.TURN;
        }
        var player = state.player();
        return switch (intent) {
            case TacticalIntent.RollInitiative roll -> throw new IllegalStateException("handled above");
            case TacticalIntent.EndActivation end -> null;
            case TacticalIntent.MoveZone move -> {
                int cost = moveCost(state, move.zone());
                yield cost < 0 ? Refusal.ZONE : cost > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            }
            case TacticalIntent.Speech speech -> {
                if (state.speechUsed() >= state.profile().value("speechPerActivation")) {
                    yield Refusal.SPEECH_LIMIT;
                }
                var recipient = state.participant(speech.recipient());
                yield recipient == null || !canAddress(state.space(), state.profile(), player, recipient)
                        ? Refusal.TARGET : null;
            }
            case TacticalIntent.Interact interact -> {
                var action = state.action(interact.action());
                if (action == null) {
                    yield Refusal.ACTION;
                }
                if (action.kind() == EncounterAction.Kind.SOCIAL) {
                    var target = interact.target() == null ? null : state.participant(interact.target());
                    if (target == null || !action.recipients().admits(target.side())
                            || !canAddress(state.space(), state.profile(), player, target)) {
                        yield Refusal.TARGET;
                    }
                } else {
                    if (interact.target() != null) {
                        yield Refusal.TARGET;
                    }
                    var passage = action.passage();
                    if (action.zone() != null && !action.zone().equals(player.zone())
                            || state.space().passageIs(passage.from(), passage.to(), passage.blocked())) {
                        yield Refusal.ACTION;
                    }
                }
                yield action.cost() > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            }
        };
    }

    /**
     * Whether one participant can address another by voice.
     *
     * @param space the current zone graph
     * @param profile frozen profile supplying {@code maxRangeEdges}
     * @param speaker the participant speaking
     * @param recipient the participant addressed
     * @return true for a different, active or surrendered recipient within range of a standing speaker
     */
    public static boolean canAddress(ZoneGraph space, CombatProfile profile, EncounterState.Combatant speaker,
            EncounterState.Combatant recipient) {
        return !speaker.id().equals(recipient.id()) && speaker.status() == EncounterState.Status.ACTIVE
                && (recipient.status() == EncounterState.Status.ACTIVE
                        || recipient.status() == EncounterState.Status.SURRENDERED)
                && space.distance(speaker.zone(), recipient.zone()) <= profile.value("maxRangeEdges");
    }

    /**
     * Action-point cost of the player's move to a zone, ignoring the points available.
     *
     * @param state encounter state at the player's decision
     * @param zone destination zone ID
     * @return the cost of the single edge, or -1 when the move is illegal (unknown zone, current zone, no
     *         unblocked edge, or a destination at its capacity)
     */
    public static int moveCost(EncounterState state, String zone) {
        var space = state.space();
        String from = state.player().zone();
        if (!space.zones().containsKey(zone) || zone.equals(from)) {
            return -1;
        }
        int cost = space.stepCost(from, zone, state.profile());
        int capacity = space.zones().get(zone).capacity();
        long occupants = state.participants().stream().filter(p -> p.present() && p.zone().equals(zone)).count();
        return cost < 0 || capacity > 0 && occupants >= capacity ? -1 : cost;
    }

    /**
     * Legal candidates of the player's current decision (docs/11 section 12), for the receipt: affordable moves
     * with their cost, the speech allowance and addressable recipients, and each declared action that is legal now
     * with its cost and admissible recipients. Everything listed is public: every participant is observable.
     *
     * @param state encounter state at the player's decision
     * @return ordered canonical values, or an empty map when the player has no decision now
     */
    public static Map<String, Object> options(EncounterState state) {
        if (!state.playerDecision()) {
            return Map.of();
        }
        var space = state.space();
        var player = state.player();
        var moves = new ArrayList<Object>();
        for (String zone : space.zones().keySet()) {
            int cost = moveCost(state, zone);
            if (cost >= 0 && cost <= state.actionPoints()) {
                var move = new LinkedHashMap<String, Object>();
                move.put("zone", zone);
                move.put("cost", cost);
                moves.add(move);
            }
        }
        var recipients = new ArrayList<Object>();
        for (var participant : state.participants()) {
            if (canAddress(space, state.profile(), player, participant)) {
                recipients.add(participant.id());
            }
        }
        var speech = new LinkedHashMap<String, Object>();
        speech.put("remaining", Math.max(0, state.profile().value("speechPerActivation") - state.speechUsed()));
        speech.put("recipients", recipients);
        var actions = new ArrayList<Object>();
        for (EncounterAction action : state.actions()) {
            var targets = new ArrayList<Object>();
            if (action.kind() == EncounterAction.Kind.SOCIAL) {
                for (var participant : state.participants()) {
                    if (refusal(state, new TacticalIntent.Interact(action.id(), participant.id())) == null) {
                        targets.add(participant.id());
                    }
                }
                if (targets.isEmpty()) {
                    continue;
                }
            } else if (refusal(state, new TacticalIntent.Interact(action.id(), null)) != null) {
                continue;
            }
            var line = new LinkedHashMap<String, Object>();
            line.put("actionId", action.id());
            line.put("kind", action.kind().wire());
            line.put("cost", action.cost());
            line.put("targets", targets);
            actions.add(line);
        }
        var options = new LinkedHashMap<String, Object>();
        options.put("moves", moves);
        options.put("speech", speech);
        options.put("actions", actions);
        options.put("endActivation", true);
        return options;
    }
}
