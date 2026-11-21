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
 *   <li><b>Drafts</b> (P15.4-B, {@link #draftRefusal}) — the candidate ability the commands layer previewed from the
 *       player's draft, under exactly the ability rules above. Every draft's use is recorded under the one reserved candidate
 *       ID, so a draft waits its own cooldown counted from the player's last draft, whichever draft that was. Without a
 *       candidate a draft is refused as an unknown ability, so no caller can resolve one the commands layer did not
 *       preview.</li>
 *   <li><b>Prevention</b> (P11.4) — an active effect can prevent actions (everything that costs points), movement or
 *       spending Focus. Speech and the end of the activation stay available, so a prevented player is never stuck.</li>
 *   <li><b>Reaction policy</b> (P11.4) — a reaction of the player's frozen profile, set before the first round or at the
 *       player's own decision, at no cost. It never answers a roll already seen.</li>
 *   <li><b>Negotiation</b> (P11.5) — an action that proposes terms needs a recipient still standing, because a surrendered
 *       one has nothing left to agree. An answer names the one offer on the table by its identity, so a stale or unknown one
 *       is refused. Declining is free, and accepting costs what the terms declare, which a prevented player cannot pay.</li>
 *   <li><b>Ready/equip</b> (P11.5, ADR 0034) — {@code readyCost}. Which item, its slot, hands and tags are pinned item data
 *       the commands layer checks with the same equipment rules as exploration.</li>
 *   <li><b>Assess</b> (P11.5, {@link #canAssess}) — a different standing participant within {@code maxRangeEdges} range
 *       bands whom the player can see: from another zone, an obscured zone hides its occupants. Cover does not stop a look.
 *       It costs {@code assessCost}.</li>
 *   <li><b>Withdraw</b> (P11.5, {@link #withdrawCost}) — a legal move whose destination's cheapest walk to an escape zone is
 *       strictly shorter than from the player's zone, for {@code withdrawCost} or the edge's Move price when that is higher.
 *       An effect that prevents movement or actions refuses it, as it refuses a move.</li>
 *   <li><b>Settlement</b> (P11.5, ADR 0036) — only at {@code OUTCOME_PENDING}, naming that encounter; it costs nothing and no
 *       effect prevents it, because the fight is over. Every other intention is refused there as out of turn.</li>
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
        /**
         * The destination is unknown, the current zone, not one unblocked edge away or full, or, for a withdrawal, no closer
         * to an escape zone.
         */
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
        /** The player has no such frozen ability, or it waits for its limit or cooldown; also a draft without its candidate. */
        ABILITY,
        /** The ability costs more Focus than the player holds. */
        RESOURCE,
        /** The reaction is unknown to the player's frozen profile. */
        REACTION,
        /** An active effect prevents this intention. */
        PREVENTED,
        /** No offer with this identity is on the table. */
        OFFER
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
     * @return null when legal, otherwise the first violated rule in the order turn, target or place, cost; a
     *         {@link TacticalIntent.UseDraft} that passes the turn and prevention is always {@link Refusal#ABILITY} here,
     *         because only {@link #draftRefusal} knows its candidate
     */
    public static Refusal refusal(EncounterState state, TacticalIntent intent) {
        return refusal(state, intent, null);
    }

    /**
     * Decide whether the player can use a previewed draft now (P15.4-B): the turn and prevention rules of every action, then
     * the ability rules on the candidate, with its cooldown read under the reserved candidate ID.
     *
     * @param state the encounter as read under the campaign lock
     * @param intent the player's draft intention
     * @param candidate the frozen ability the commands layer previewed from the draft at this same state
     * @return null when legal, otherwise the first violated rule, as for {@link #refusal}
     */
    public static Refusal draftRefusal(EncounterState state, TacticalIntent.UseDraft intent, CombatAbility candidate) {
        java.util.Objects.requireNonNull(candidate, "candidate");
        return refusal(state, intent, candidate);
    }

    /** The shared rule chain; {@code candidate} is the previewed ability of a draft and null for every other intention. */
    private static Refusal refusal(EncounterState state, TacticalIntent intent, CombatAbility candidate) {
        if (intent instanceof TacticalIntent.RollInitiative) {
            return state.phase() == EncounterPhase.ENTERING ? null : Refusal.TURN;
        }
        if (intent instanceof TacticalIntent.Settle settle) {
            // The one intention of a decided fight: no turn, cost or prevention applies, and it names the fight reviewed.
            return state.phase() == EncounterPhase.OUTCOME_PENDING && state.id().toString().equals(settle.encounter())
                    ? null : Refusal.TURN;
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
        boolean moving = intent instanceof TacticalIntent.MoveZone || intent instanceof TacticalIntent.Withdraw;
        if (costsPoints(intent) && state.prevented(player, CombatEffect.Prevents.ACTIONS)
                || moving && state.prevented(player, CombatEffect.Prevents.MOVEMENT)) {
            return Refusal.PREVENTED;
        }
        return switch (intent) {
            case TacticalIntent.RollInitiative roll -> throw new IllegalStateException("handled above");
            case TacticalIntent.SetReactionPolicy policy -> throw new IllegalStateException("handled above");
            case TacticalIntent.Settle settle -> throw new IllegalStateException("handled above");
            case TacticalIntent.UseAbility use -> {
                var ability = player.sheet().ability(use.ability());
                yield ability == null ? Refusal.ABILITY : ability(state, player, ability, use.targets());
            }
            case TacticalIntent.UseDraft draft -> candidate == null ? Refusal.ABILITY
                    : ability(state, player, candidate, draft.targets());
            case TacticalIntent.EndActivation end -> null;
            case TacticalIntent.MoveZone move -> {
                int cost = moveCost(state, move.zone());
                yield cost < 0 ? Refusal.ZONE : cost > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            }
            case TacticalIntent.Withdraw withdraw -> {
                int cost = withdrawCost(state, withdraw.zone());
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
                            || !canAddress(state.space(), state.profile(), player, target)
                            || action.terms() != null && target.status() != EncounterState.Status.ACTIVE) {
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
            case TacticalIntent.AnswerOffer answer -> {
                var offer = state.openOffer();
                if (offer == null || !offer.id().equals(answer.offer())) {
                    yield Refusal.OFFER;
                }
                int cost = answer.accept() ? offer.terms().cost() : 0;
                yield cost > 0 && state.prevented(player, CombatEffect.Prevents.ACTIONS) ? Refusal.PREVENTED
                        : cost > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            }
            case TacticalIntent.Ready ready ->
                    state.profile().value("readyCost") > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            case TacticalIntent.Assess assess -> {
                var target = state.participant(assess.target());
                if (target == null || !canAssess(state.space(), state.profile(), player, target)) {
                    yield Refusal.TARGET;
                }
                yield state.profile().value("assessCost") > state.actionPoints() ? Refusal.ACTION_POINTS : null;
            }
        };
    }

    /**
     * Whether one participant can assess another now (P11.5).
     *
     * @param space the current zone graph
     * @param profile frozen profile supplying {@code maxRangeEdges}
     * @param observer the participant looking
     * @param target the participant looked at
     * @return true for a different, standing target within range bands of a standing observer and in its sight: from another
     *         zone an obscured zone hides its occupants, as it does from an attack; range ignores blocked edges, as a voice does
     */
    public static boolean canAssess(ZoneGraph space, CombatProfile profile, EncounterState.Combatant observer,
            EncounterState.Combatant target) {
        return !observer.id().equals(target.id()) && observer.status() == EncounterState.Status.ACTIVE
                && target.status() == EncounterState.Status.ACTIVE
                && space.distance(observer.zone(), target.zone()) <= profile.value("maxRangeEdges")
                && (observer.zone().equals(target.zone()) || !space.zones().get(target.zone()).obscured());
    }

    /**
     * @return whether the intention is an action paid in points, which a prevented participant cannot take. An answer is a
     *         decision first: only what its terms declare can make it an action, which its own rule checks
     */
    private static boolean costsPoints(TacticalIntent intent) {
        return !(intent instanceof TacticalIntent.EndActivation || intent instanceof TacticalIntent.Speech
                || intent instanceof TacticalIntent.AnswerOffer);
    }

    /**
     * Legality of an ability, frozen or a draft's candidate: not waiting for its limit or cooldown, its Focus spendable and
     * held, its targets admissible and its points available, in that order.
     */
    private static Refusal ability(EncounterState state, EncounterState.Combatant player, CombatAbility ability,
            java.util.List<String> targets) {
        if (ability.waiting(player.uses().get(ability.id()), state.round()) != null) {
            return Refusal.ABILITY;
        }
        if (ability.focus() > 0 && state.prevented(player, CombatEffect.Prevents.RESOURCES)) {
            return Refusal.PREVENTED;
        }
        if (ability.focus() > player.focus()) {
            return Refusal.RESOURCE;
        }
        boolean self = ability.targets() == CombatAbility.Targets.SELF;
        if (self != targets.isEmpty() || targets.size() > ability.maxTargets()
                || new HashSet<>(targets).size() != targets.size()) {
            return Refusal.TARGET;
        }
        for (String id : targets) {
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
     * Action-point cost of the player's withdrawal to a zone (P11.5, ADR 0035), ignoring the points available.
     *
     * @param state encounter state at the player's decision
     * @param zone destination zone ID
     * @return {@code withdrawCost}, raised to the edge's Move price when that is higher, so withdrawing never makes a crossing
     *         cheaper than walking it; or -1 when the encounter has no escape zone or the destination is not a legal move
     *         ({@link #moveCost}) whose cheapest walk to an escape zone, along the current unblocked edges, is strictly shorter
     *         than from the player's zone
     */
    public static int withdrawCost(EncounterState state, String zone) {
        int step = moveCost(state, zone);
        if (step < 0) {
            return -1;
        }
        int here = escapeDistance(state, state.player().zone());
        int there = escapeDistance(state, zone);
        // A legal step into a zone that can reach an escape zone means the player's zone can reach one too.
        if (there < 0 || there >= here) {
            return -1;
        }
        return Math.max(step, state.profile().value("withdrawCost"));
    }

    /** @return the fewest action points of walking from a zone to the nearest escape zone, or -1 when none can be reached */
    private static int escapeDistance(EncounterState state, String zone) {
        var space = state.space();
        int best = -1;
        for (String escape : state.escapeZones()) {
            int cost = space.travelCost(zone, escape, state.profile());
            if (cost >= 0 && (best < 0 || cost < best)) {
                best = cost;
            }
        }
        return best;
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
     * and the reason it cannot be used now, the Guard action and the price of using an item. Since P11.5 a declared
     * action also lists its check and proposed terms, and the offer on the table is listed with what accepting costs. A
     * check's difficulty and a counteroffer not yet made are absent. Also since P11.5 the price of readying an item and
     * whether it can be paid, the price of assessing with the participants that can be assessed, and each zone the player
     * can withdraw to with its price. Everything listed is public: every participant is observable.
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
            // Present only when declared, so the options of an action without them read as they did before P11.5.
            if (action.check() != null) {
                line.put("check", action.check().publicForm());
            }
            if (action.terms() != null) {
                line.put("terms", action.terms().canonicalForm());
            }
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
        // Since P11.5: which item can be readied is pinned item data the commands layer checks, so only the price is listed.
        var ready = new LinkedHashMap<String, Object>();
        ready.put("cost", state.profile().value("readyCost"));
        ready.put("available", refusal(state, new TacticalIntent.Ready(true)) == null);
        options.put("ready", ready);
        var observed = new ArrayList<Object>();
        for (var participant : state.participants()) {
            if (refusal(state, new TacticalIntent.Assess(participant.id())) == null) {
                observed.add(participant.id());
            }
        }
        var assess = new LinkedHashMap<String, Object>();
        assess.put("cost", state.profile().value("assessCost"));
        assess.put("targets", observed);
        options.put("assess", assess);
        // Since P11.5: each zone the player can withdraw to now, with its price.
        var withdrawals = new ArrayList<Object>();
        for (String zone : space.zones().keySet()) {
            if (refusal(state, new TacticalIntent.Withdraw(zone)) == null) {
                var line = new LinkedHashMap<String, Object>();
                line.put("zone", zone);
                line.put("cost", withdrawCost(state, zone));
                withdrawals.add(line);
            }
        }
        options.put("withdraw", withdrawals);
        options.put("endActivation", true);
        var offer = state.openOffer();
        if (offer != null) {
            var line = new LinkedHashMap<String, Object>(offer.canonicalForm());
            line.put("canAccept", refusal(state, new TacticalIntent.AnswerOffer(offer.id(), true)) == null);
            options.put("offer", line);
        }
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
