package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.HashSet;
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
 *   <li><b>Attacks</b> (P11.3, {@link #attackBlock}) — one frozen attack of the attacker against a standing participant
 *       of the opposing side. Melee needs the same zone. A ranged attack reaches its {@code range} in zone distance;
 *       across zones it also needs line of sight (an obscured zone hides its occupants from outside) and fails against
 *       full cover. An attack that consumes ammunition needs a unit available. It costs {@code strikeCost}.</li>
 *   <li><b>Guard</b> (P11.3) — once per activation for {@code guardCost}; the stance holds until the start of the
 *       participant's next activation, so a stance still held means this activation already took it.</li>
 *   <li><b>Item use</b> (P11.3) — {@code useItemCost}, on the player or on a standing ally in the player's zone. The
 *       pinned use contract is checked by the commands layer, which has the catalog.</li>
 *   <li><b>Abilities</b> (P11.4) — a frozen ability that its limit and cooldown allow, paid in action points and Focus,
 *       on targets of the side it names within its range.</li>
 *   <li><b>Prevention</b> (P11.4) — an active effect can prevent actions (everything that costs points), movement or
 *       spending Focus. Speech and the end of the activation stay available, so a prevented player is never stuck.</li>
 *   <li><b>Reaction policy</b> (P11.4) — a reaction of the player's frozen profile, set before the first round or at the
 *       player's own decision, at no cost. It never answers a roll already seen.</li>
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
        ACTION,
        /** The attacker has no such frozen attack, or no ammunition for it. */
        ATTACK,
        /** The Guard action was already taken in this activation. */
        GUARD,
        /** The player has no such frozen ability, or it waits for its limit or cooldown. */
        ABILITY,
        /** The ability costs more Focus than the player holds. */
        RESOURCE,
        /** The reaction is unknown to the player's frozen profile. */
        REACTION,
        /** An active effect prevents this intention. */
        PREVENTED
    }

    /** Why one attack cannot reach one target right now; public, because every participant is observable. */
    public enum AttackBlock {
        /** The target is on the attacker's own side, or is the attacker. */
        NOT_OPPOSED,
        /** The target is defeated, has escaped or has surrendered. */
        NOT_STANDING,
        /** The target is beyond the attack's range. */
        OUT_OF_RANGE,
        /** The target stands in an obscured zone the attacker is outside of. */
        NO_LINE_OF_SIGHT,
        /** The target stands in a zone with full cover the attacker is outside of. */
        FULL_COVER
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
        if (intent instanceof TacticalIntent.SetReactionPolicy policy) {
            // Before any roll, or at the player's own decision: never during another participant's action.
            if (state.phase() != EncounterPhase.ENTERING && !state.playerDecision()) {
                return Refusal.TURN;
            }
            return state.player().sheet().reaction(policy.reaction()) == null ? Refusal.REACTION : null;
        }
        if (!state.playerDecision()) {
            return Refusal.TURN;
        }
        var player = state.player();
        if (costsPoints(intent) && state.prevented(player, CombatEffect.Prevents.ACTIONS)
                || intent instanceof TacticalIntent.MoveZone && state.prevented(player, CombatEffect.Prevents.MOVEMENT)) {
            return Refusal.PREVENTED;
        }
        return switch (intent) {
            case TacticalIntent.RollInitiative roll -> throw new IllegalStateException("handled above");
            case TacticalIntent.SetReactionPolicy policy -> throw new IllegalStateException("handled above");
            case TacticalIntent.UseAbility use -> ability(state, player, use);
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
            case TacticalIntent.Strike strike -> {
                var attack = player.sheet().attack(strike.attack());
                if (attack == null || !armed(state, attack)) {
                    yield Refusal.ATTACK;
                }
                var target = state.participant(strike.target());
                if (target == null || attackBlock(state.space(), player, attack, target) != null) {
                    yield Refusal.TARGET;
                }
                yield state.profile().value("strikeCost") > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            }
            case TacticalIntent.Guard guard -> player.guarding() ? Refusal.GUARD
                    : state.profile().value("guardCost") > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            case TacticalIntent.UseItem use -> {
                if (use.target() != null) {
                    var target = state.participant(use.target());
                    if (target == null || target.opposes(player) || target.status() != EncounterState.Status.ACTIVE
                            || !target.zone().equals(player.zone())) {
                        yield Refusal.TARGET;
                    }
                }
                yield state.profile().value("useItemCost") > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            }
        };
    }

    /** @return whether the intention is an action paid in points, which a prevented participant cannot take */
    private static boolean costsPoints(TacticalIntent intent) {
        return !(intent instanceof TacticalIntent.EndActivation || intent instanceof TacticalIntent.Speech);
    }

    /**
     * Legality of an ability: a frozen ability, not waiting for its limit or cooldown, its Focus spendable and held, its
     * targets admissible and its points available, in that order.
     */
    private static Refusal ability(EncounterState state, EncounterState.Combatant player, TacticalIntent.UseAbility use) {
        var ability = player.sheet().ability(use.ability());
        if (ability == null || ability.waiting(player.uses().get(ability.id()), state.round()) != null) {
            return Refusal.ABILITY;
        }
        if (ability.focus() > 0 && state.prevented(player, CombatEffect.Prevents.RESOURCES)) {
            return Refusal.PREVENTED;
        }
        if (ability.focus() > player.focus()) {
            return Refusal.RESOURCE;
        }
        boolean self = ability.targets() == CombatAbility.Targets.SELF;
        if (self != use.targets().isEmpty() || use.targets().size() > ability.maxTargets()
                || new HashSet<>(use.targets()).size() != use.targets().size()) {
            return Refusal.TARGET;
        }
        for (String id : use.targets()) {
            var target = state.participant(id);
            if (target == null || !canTarget(state.space(), player, ability, target)) {
                return Refusal.TARGET;
            }
        }
        return ability.cost() > state.actionPoints() ? Refusal.ACTION_POINTS : null;
    }

    /**
     * Whether an ability can take one target now: the side it names, standing, within its range, and not hidden or behind
     * full cover from another zone, the same space rules as an attack.
     *
     * @param space the current zone graph
     * @param user the participant using it
     * @param ability the ability
     * @param target the participant considered
     * @return whether it is an admissible target
     */
    public static boolean canTarget(ZoneGraph space, EncounterState.Combatant user, CombatAbility ability,
            EncounterState.Combatant target) {
        boolean side = ability.targets() == CombatAbility.Targets.ALLY ? !user.opposes(target)
                : ability.targets() == CombatAbility.Targets.HOSTILE && user.opposes(target);
        return side && target.status() == EncounterState.Status.ACTIVE
                && reachBlock(space, user, target, ability.range()) == null;
    }

    /**
     * Whether one attack can reach one target now, ignoring its cost.
     *
     * @param space the current zone graph
     * @param attacker the participant attacking
     * @param attack one of its frozen attacks
     * @param target the participant attacked
     * @return null when the target can be attacked, otherwise the first rule that stops it: side, standing, range,
     *         line of sight, cover. Cover and visibility only matter across zones: inside one zone nothing stands
     *         between the two. A blocked edge does not stop a shot (range ignores blocking, see {@link ZoneGraph}).
     */
    public static AttackBlock attackBlock(ZoneGraph space, EncounterState.Combatant attacker, CombatAttack attack,
            EncounterState.Combatant target) {
        if (!attacker.opposes(target)) {
            return AttackBlock.NOT_OPPOSED;
        }
        if (target.status() != EncounterState.Status.ACTIVE) {
            return AttackBlock.NOT_STANDING;
        }
        return reachBlock(space, attacker, target, attack.range());
    }

    /** Range, then line of sight, then cover; the latter two only across zones, because nothing stands inside one. */
    private static AttackBlock reachBlock(ZoneGraph space, EncounterState.Combatant attacker,
            EncounterState.Combatant target, int range) {
        if (space.distance(attacker.zone(), target.zone()) > range) {
            return AttackBlock.OUT_OF_RANGE;
        }
        if (!attacker.zone().equals(target.zone())) {
            var zone = space.zones().get(target.zone());
            if (zone.obscured()) {
                return AttackBlock.NO_LINE_OF_SIGHT;
            }
            if (zone.cover() == ZoneGraph.Cover.FULL) {
                return AttackBlock.FULL_COVER;
            }
        }
        return null;
    }

    /**
     * Effective defense of a target against one attack.
     *
     * @param state the encounter
     * @param attacker the participant attacking
     * @param attack the attack, already legal against the target
     * @param target the participant attacked
     * @return Resistance with its active modifiers for a resistance attack, otherwise Guard with the stance, any partial
     *         cover of the target's zone against an attack from outside it and active Guard modifiers, under the situational cap
     */
    public static int defense(EncounterState state, EncounterState.Combatant attacker, CombatAttack attack,
            EncounterState.Combatant target) {
        return defense(state, attacker, attack, target, 0);
    }

    /**
     * Effective defense of a target against one attack with a Guard bonus from a reaction.
     *
     * @param state the encounter
     * @param attacker the participant attacking
     * @param attack the attack, already legal against the target
     * @param target the participant attacked
     * @param reactionBonus the highest Guard bonus a reaction gave against this attack, zero without one
     * @return the defense; the Guard action and the reaction share one stacking key, so the higher applies and never the
     *         sum, and a resistance attack reads neither
     */
    public static int defense(EncounterState state, EncounterState.Combatant attacker, CombatAttack attack,
            EncounterState.Combatant target, int reactionBonus) {
        var profile = state.profile();
        int cap = profile.value("situationalGuardCap");
        if (attack.defense() == CombatAttack.Defense.RESISTANCE) {
            return CombatMath.resistance(target.sheet().resistance(),
                    CombatEffects.resistance(target.effects(), state.effects()), cap);
        }
        int stance = Math.max(target.guarding() ? profile.value("guardBonus") : 0, reactionBonus);
        return CombatMath.guard(target.sheet().guard(), stance, coverBonus(state, attacker, target),
                CombatEffects.guard(target.effects(), state.effects()), cap);
    }

    /** Partial cover counts only against an attack from another zone; full cover already refused the attack. */
    private static int coverBonus(EncounterState state, EncounterState.Combatant attacker,
            EncounterState.Combatant target) {
        boolean covered = !attacker.zone().equals(target.zone())
                && state.space().zones().get(target.zone()).cover() == ZoneGraph.Cover.PARTIAL;
        return covered ? state.profile().value("partialCoverBonus") : 0;
    }

    /** @return whether the attack needs no ammunition or the player holds a unit of it */
    private static boolean armed(EncounterState state, CombatAttack attack) {
        return attack.ammunition() == null || state.ammunitionOf(attack.ammunition()) > 0;
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
        return moveCost(state, state.player().zone(), zone);
    }

    /**
     * Action-point cost of any participant's move between two zones, ignoring the points available.
     *
     * @param state encounter state
     * @param from the mover's zone
     * @param zone destination zone ID
     * @return the cost of the single edge, or -1 when the move is illegal
     */
    public static int moveCost(EncounterState state, String from, String zone) {
        var space = state.space();
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
     * with their cost, the speech allowance and addressable recipients, each declared action that is legal now
     * with its cost and admissible recipients and, since P11.3, each frozen attack with its cost, its reachable targets
     * and the reason it cannot be used now, the Guard action and the price of using an item. Everything listed is
     * public: every participant is observable.
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
        var strikes = new ArrayList<Object>();
        int strikeCost = state.profile().value("strikeCost");
        for (CombatAttack attack : player.sheet().attacks()) {
            var targets = new ArrayList<Object>();
            for (var participant : state.participants()) {
                if (attackBlock(space, player, attack, participant) == null) {
                    targets.add(participant.id());
                }
            }
            var line = new LinkedHashMap<String, Object>();
            line.put("attackId", attack.id());
            line.put("cost", strikeCost);
            line.put("targets", targets);
            // Stable public reason of a disabled candidate; a usable one has none.
            String disabled = !armed(state, attack) ? "NO_AMMUNITION" : targets.isEmpty() ? "NO_TARGET_IN_REACH"
                    : strikeCost > state.actionPoints() ? "ACTION_POINTS" : null;
            line.put("disabled", disabled);
            strikes.add(line);
        }
        var guard = new LinkedHashMap<String, Object>();
        guard.put("cost", state.profile().value("guardCost"));
        guard.put("available", refusal(state, new TacticalIntent.Guard()) == null);
        var items = new LinkedHashMap<String, Object>();
        items.put("cost", state.profile().value("useItemCost"));
        items.put("available", refusal(state, new TacticalIntent.UseItem(null)) == null);
        var abilities = new ArrayList<Object>();
        for (CombatAbility ability : player.sheet().abilities()) {
            var targets = new ArrayList<Object>();
            for (var participant : state.participants()) {
                if (canTarget(space, player, ability, participant)) {
                    targets.add(participant.id());
                }
            }
            var line = new LinkedHashMap<String, Object>();
            line.put("abilityId", ability.id());
            line.put("cost", ability.cost());
            line.put("focus", ability.focus());
            line.put("targets", targets);
            line.put("maxTargets", ability.maxTargets());
            line.put("disabled", abilityDisabled(state, player, ability, targets.isEmpty()));
            abilities.add(line);
        }
        var reactions = new ArrayList<Object>();
        for (CombatReaction reaction : player.sheet().allReactions()) {
            var line = new LinkedHashMap<String, Object>();
            line.put("reactionId", reaction.id());
            line.put("policy", player.policy(reaction.id()).canonicalForm());
            reactions.add(line);
        }
        var options = new LinkedHashMap<String, Object>();
        options.put("moves", moves);
        options.put("speech", speech);
        options.put("actions", actions);
        options.put("strikes", strikes);
        options.put("guard", guard);
        options.put("useItem", items);
        options.put("abilities", abilities);
        options.put("reactions", reactions);
        options.put("reactionsLeft", player.reactions());
        options.put("endActivation", true);
        return options;
    }

    /** The first stable public reason an ability cannot be used now, or null when it can. */
    private static String abilityDisabled(EncounterState state, EncounterState.Combatant player, CombatAbility ability,
            boolean noTargets) {
        String waiting = ability.waiting(player.uses().get(ability.id()), state.round());
        if (waiting != null) {
            return waiting;
        }
        if (state.prevented(player, CombatEffect.Prevents.ACTIONS)
                || ability.focus() > 0 && state.prevented(player, CombatEffect.Prevents.RESOURCES)) {
            return "PREVENTED";
        }
        if (ability.focus() > player.focus()) {
            return "FOCUS";
        }
        if (ability.targets() != CombatAbility.Targets.SELF && noTargets) {
            return "NO_TARGET_IN_REACH";
        }
        return ability.cost() > state.actionPoints() ? "ACTION_POINTS" : null;
    }
}
