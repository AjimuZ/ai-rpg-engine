package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.rpgengine.rules.CombatProfile;

/**
 * The fixed initiative-round scheduler of the {@code skirmish-initiative-rounds/1} kernel (P11.2, docs/11 sections
 * 2, 6, 7 and 7.1). Pure: given an encounter state, a legal tactical intention and a source of recorded draws it
 * returns the complete {@link TacticalPlan}; the same inputs always give the same plan.
 *
 * <p><b>Initiative</b> is rolled once, at {@code ENTERING}: each participant in roster order draws two d10 (draw
 * ordinals {@code 2i} and {@code 2i+1}) and adds its frozen initiative modifier. Order is total descending, then
 * finesse descending, then participant ID ascending, and the totals and ranks are stored; nothing re-rolls them.
 *
 * <p><b>Rounds and activations</b>: opening a round increments it and refreshes each active participant's reaction
 * budget once. Activations run in rank order over active participants only (defeated, escaped and surrendered
 * participants are skipped). An activation start grants the profile's action points and resets the speech
 * allowance; its end expires unused points. After the last activation the round ends and adds
 * {@code roundSeconds} of world time. The scheduler then opens the next round, unless the round cap is reached or
 * the player no longer stands (the stalemate and terminal policies of P11.5 decide those encounters).
 *
 * <p><b>Decision boundaries</b>: the scheduler advances until the player's own activation waits for an action, so
 * an operation never stops at another participant's turn and the player never decides out of turn. Because the player
 * acts once per round, one operation crosses at most one round boundary.
 *
 * <p><b>Player actions</b> keep the activation open: a move, declared action, strike, Guard or item use passes through
 * {@code RESOLVING} and returns to {@code AWAITING_ACTION}, even at zero points, because only the explicit end of the
 * activation passes control (a speech-only utterance still costs nothing). A speech-only utterance changes only the
 * speech count.
 *
 * <p><b>Attacks</b> (P11.3, docs/11 sections 8-9): a strike draws, in this order, the two attack dice, then on a hit
 * each damage die, then on a strong hit the strong-hit die, so a miss reserves no damage draw. Costs and ammunition are
 * paid by the attempt, hit or miss. {@link CombatMath} does the arithmetic. Each strike writes a public
 * {@code STRIKE_RESOLVED} event (dice, score, outcome, damage taken) and an internal {@code STRIKE_CALCULATED} event
 * with the complete calculation and draw ordinals, whose defense, armor and Health values are hidden from the player
 * when they belong to another participant. Zero Health defeats a participant, which is never a death; the schedule
 * then skips it. A defeated participant's activation never starts and its stance ends.
 *
 * <p><b>Non-player tactics</b> are deliberately small until constrained choices and the full fallback exist
 * (P12.3/P12.4): a participant without an attack passes; an armed one first moves one edge toward the nearest opponent
 * when none is in reach, makes at most one strike at the first opponent in reach (nearest, then roster order), and
 * takes the Guard action with what is left. One strike per activation bounds the draws of an operation: nine strikes at
 * seven draws each plus eighteen initiative dice stay well inside {@code DeterministicDraw.MAX_DRAWS}.
 *
 * <p><b>The opening strike</b> resolves once in the initiative operation: under {@code first} right after round one
 * opens, paid by granting fewer points at the player's round-one activation, and under {@code initiative} when that
 * activation starts. Its legality is judged at the slot, because the target may have moved or fallen by then. A strike
 * that cannot be made at its slot lapses with its public reason, costing nothing; so does one whose attacker fell
 * before its slot, when round one closes. Either way {@code opening_status} ends {@code RESOLVED}.
 */
public final class CombatScheduler {
    /** Sides of each initiative die (kernel). */
    static final int INITIATIVE_SIDES = CombatProfile.INITIATIVE_SIDES;

    /** Static entry point only. */
    private CombatScheduler() {
    }

    /** Source of recorded draws for the operation. */
    @FunctionalInterface
    public interface Dice {
        /**
         * Reserve or replay one draw.
         *
         * @param ordinal 0-based ordinal within the operation
         * @param sides faces of the die
         * @return a value in {@code 1..sides}; the same ordinal of the same operation always gives the same value
         */
        int value(int ordinal, int sides);
    }

    /**
     * Resolve one tactical intention.
     *
     * @param state the encounter as read under the campaign lock
     * @param intent an intention {@link TacticalRules#refusal} accepts for {@code state}
     * @param dice recorded draws, used by the initiative roll and by attacks
     * @return the complete plan
     * @throws IllegalStateException when the intention is refused (the rule gate should have stopped it) or a draw
     *         lies outside its die
     */
    public static TacticalPlan apply(EncounterState state, TacticalIntent intent, Dice dice) {
        var refusal = TacticalRules.refusal(state, intent);
        if (refusal != null) {
            throw new IllegalStateException("validated tactical intention is refused: " + refusal);
        }
        var run = new Run(state);
        switch (intent) {
            case TacticalIntent.RollInitiative roll -> {
                run.initiative(dice);
                run.advance();
            }
            case TacticalIntent.EndActivation end -> {
                run.draws(dice);
                run.endActivation(true);
                run.advance();
            }
            case TacticalIntent.MoveZone move -> run.move(move.zone());
            case TacticalIntent.Speech speech -> run.speak(speech);
            case TacticalIntent.Interact interact -> run.interact(interact);
            case TacticalIntent.Strike strike -> run.playerStrike(strike, dice);
            case TacticalIntent.Guard guard -> run.playerGuard();
            case TacticalIntent.UseItem use -> throw new IllegalStateException("an item use needs its resolved outcome");
        }
        return run.plan();
    }

    /**
     * What a pinned item's use contract did, as the commands layer resolved it with the catalog. The scheduler only
     * charges the action and takes over the resulting Health values; it reads no pack data.
     *
     * @param definition canonical item definition used
     * @param playerHealth the player's Health after the effects, above the pool's minimum
     * @param targetHealth the targeted ally's encounter-local Health after the effects, or null without such a target
     * @param healed Health the effects restored to the targeted ally
     */
    public record ItemOutcome(String definition, int playerHealth, Integer targetHealth, long healed) {
        /** Require the item. */
        public ItemOutcome {
            java.util.Objects.requireNonNull(definition, "definition");
        }
    }

    /**
     * Resolve the action of using an item whose effects the commands layer already calculated.
     *
     * @param state the encounter as read under the campaign lock
     * @param intent an intention {@link TacticalRules#refusal} accepts for {@code state}
     * @param outcome the item's resolved effects
     * @param dice recorded draws, used only when the item leaves the player at zero and the schedule continues
     * @param firstDraw the operation's first draw ordinal the item's own effects did not use
     * @return the complete plan; a player the item itself left at zero Health is defeated and the schedule continues
     * @throws IllegalStateException when the intention is refused
     */
    public static TacticalPlan useItem(EncounterState state, TacticalIntent.UseItem intent, ItemOutcome outcome,
            Dice dice, int firstDraw) {
        var refusal = TacticalRules.refusal(state, intent);
        if (refusal != null) {
            throw new IllegalStateException("validated tactical intention is refused: " + refusal);
        }
        var run = new Run(state);
        run.draws(dice);
        run.nextDraw = firstDraw;
        run.playerItem(intent, outcome);
        return run.plan();
    }

    /** Mutable working copy of one encounter during a single resolution. */
    private static final class Run {
        private final EncounterState base;
        private final CombatProfile profile;
        /** The operation's recorded draws; set by the intentions that can draw. */
        private Dice dice = (ordinal, sides) -> {
            throw new IllegalStateException("this intention draws no dice");
        };
        private final Map<String, Working> participants = new LinkedHashMap<>();
        private final List<TacticalPlan.Step> steps = new ArrayList<>();
        private final List<TacticalPlan.Note> notes = new ArrayList<>();
        private EncounterPhase phase;
        private int round;
        private Integer cursor;
        private Integer points;
        private int speech;
        private final List<EncounterAction.Passage> passages;
        private long elapsed;
        private String openingStatus;
        /** Points the {@code first} opening strike took from the player's round-one activation, until it starts. */
        private int openingCharge;
        /** Next durable draw ordinal of this operation. */
        private int nextDraw;
        private final List<EncounterState.Ammunition> ammunition;
        private final Map<java.util.UUID, TacticalPlan.AmmunitionUse> spent = new LinkedHashMap<>();
        private final List<Object> strikes = new ArrayList<>();

        /** Give the run the operation's draws. */
        void draws(Dice source) {
            dice = source;
        }

        /** Copy the state. */
        Run(EncounterState state) {
            base = state;
            profile = state.profile();
            phase = state.phase();
            round = state.round();
            cursor = state.cursor();
            points = state.actionPoints();
            speech = state.speechUsed();
            passages = new ArrayList<>(state.passages());
            openingStatus = state.openingStatus();
            ammunition = new ArrayList<>(state.ammunition());
            for (var participant : state.participants()) {
                participants.put(participant.id(), new Working(participant));
            }
        }

        /** Roll and store initiative, announce the order, and open round one. */
        void initiative(Dice dice) {
            this.dice = dice;
            var rolled = new ArrayList<Working>();
            for (Working participant : participants.values()) {
                int first = draw(INITIATIVE_SIDES);
                int second = draw(INITIATIVE_SIDES);
                participant.dice = List.of(first, second);
                participant.initiative = first + second + participant.source.initiativeModifier();
                rolled.add(participant);
            }
            // Total descending, then finesse descending, then stable participant ID ascending (docs/11 section 6).
            rolled.sort(Comparator.comparingInt((Working w) -> -w.initiative)
                    .thenComparingInt(w -> -w.source.finesse())
                    .thenComparing(w -> w.source.id()));
            var order = new ArrayList<Object>();
            for (int rank = 0; rank < rolled.size(); rank++) {
                Working participant = rolled.get(rank);
                participant.rank = rank;
                var line = new TreeMap<String, Object>();
                line.put("participantId", participant.source.id());
                line.put("dice", participant.dice);
                line.put("modifier", participant.source.initiativeModifier());
                line.put("total", participant.initiative);
                order.add(line);
            }
            note("INITIATIVE_ROLLED", Map.of("encounterId", base.id().toString(), "order", order), false);
            openRound();
            if (pendingOpening() && base.opening() == OpeningPolicy.FIRST) {
                // Before the first activation: the blow moves ahead, its price stays in the player's own activation.
                if (openingStrike(false)) {
                    openingCharge = profile.value("strikeCost");
                }
            }
        }

        /** @return whether a declared opening strike still waits for its slot */
        private boolean pendingOpening() {
            return "PENDING".equals(openingStatus);
        }

        /**
         * Make the declared opening strike at its slot, or let it lapse with its public reason, paying nothing.
         *
         * @param charge whether to spend the strike's points from the activation in progress
         * @return whether the strike was made
         */
        private boolean openingStrike(boolean charge) {
            Working player = participants.get(EncounterTemplate.PLAYER);
            Working target = participants.get(base.openingTarget());
            var current = state();
            var attacker = current.player();
            var victim = current.participant(target.source.id());
            CombatAttack chosen = null;
            String reason = attacker.sheet().attacks().isEmpty() ? "NO_ATTACK" : null;
            for (CombatAttack attack : attacker.sheet().attacks()) {
                var block = TacticalRules.attackBlock(current.space(), attacker, attack, victim);
                boolean armed = attack.ammunition() == null || current.ammunitionOf(attack.ammunition()) > 0;
                if (block == null && armed) {
                    chosen = attack;
                    break;
                }
                reason = reason != null ? reason : block != null ? block.name() : "NO_AMMUNITION";
            }
            openingStatus = "RESOLVED";
            if (chosen == null) {
                lapse(reason);
                if (charge) {
                    step(phase);
                }
                return false;
            }
            int cost = profile.value("strikeCost");
            if (charge) {
                points -= cost;
                step(EncounterPhase.RESOLVING);
            }
            strike(player, chosen, target, cost, true, true);
            if (charge) {
                step(EncounterPhase.AWAITING_ACTION);
            }
            return true;
        }

        /** Announce that the opening strike cannot be made; it spends and draws nothing. */
        private void lapse(String reason) {
            note("OPENING_STRIKE_LAPSED", activation(participants.get(EncounterTemplate.PLAYER))
                    .put("targetId", base.openingTarget()).put("reason", reason).build(), true);
        }

        /** Continue the schedule until the player's decision, the round cap or a round without a standing player. */
        void advance() {
            for (int guard = 0; guard < TacticalPlan.MAX_STEPS; guard++) {
                switch (phase) {
                    case ROUND_OPEN -> next(-1);
                    case AWAITING_ACTION -> {
                        if (acting().source.player()) {
                            return;
                        }
                        tactic(acting());
                        endActivation(false);
                    }
                    case ACTOR_END -> next(cursor);
                    case ROUND_END -> {
                        if (round >= profile.roundCap()
                                || participants.get(EncounterTemplate.PLAYER).status != EncounterState.Status.ACTIVE) {
                            return;
                        }
                        openRound();
                    }
                    default -> throw new IllegalStateException("the scheduler cannot continue from " + phase);
                }
            }
            throw new IllegalStateException("the schedule did not reach a decision within its step bound");
        }

        /** Start the next active participant after a rank, or end the round when none is left. */
        private void next(int after) {
            Working next = participants.values().stream()
                    .filter(w -> w.rank != null && w.rank > after && w.status == EncounterState.Status.ACTIVE)
                    .min(Comparator.comparingInt(w -> w.rank)).orElse(null);
            if (next == null) {
                endRound();
                return;
            }
            cursor = next.rank;
            points = profile.actionPoints();
            if (next.source.player()) {
                points -= openingCharge;
                openingCharge = 0;
            }
            speech = 0;
            // Effects tied to the participant's next activation expire first (docs/11 section 6).
            next.guarding = false;
            step(EncounterPhase.ACTOR_START);
            note("ACTIVATION_STARTED", activation(next).put("actionPoints", points).build(), next.source.player());
            step(EncounterPhase.AWAITING_ACTION);
            if (next.source.player() && pendingOpening()) {
                openingStrike(true);
            }
        }

        /** End the acting participant's activation: unused points expire. */
        void endActivation(boolean byPlayer) {
            Working acting = acting();
            int unused = points;
            points = 0;
            step(EncounterPhase.ACTOR_END);
            note("ACTIVATION_ENDED", activation(acting).put("unusedActionPoints", unused).build(), byPlayer);
        }

        /** Close the round and add its world time. */
        private void endRound() {
            if (pendingOpening()) {
                // The player fell before its slot: the strike can never be made.
                openingStatus = "RESOLVED";
                lapse("ATTACKER_DOWN");
            }
            cursor = null;
            points = null;
            speech = 0;
            elapsed += profile.roundSeconds();
            step(EncounterPhase.ROUND_END);
            note("ROUND_ENDED", Map.of("encounterId", base.id().toString(), "round", round,
                    "seconds", profile.roundSeconds()), false);
        }

        /** Open the next round: its number grows by one and reaction budgets refresh once. */
        private void openRound() {
            round++;
            for (Working participant : participants.values()) {
                if (participant.status == EncounterState.Status.ACTIVE) {
                    participant.reactions = profile.reactionsPerRound();
                }
            }
            step(EncounterPhase.ROUND_OPEN);
            note("ROUND_STARTED", Map.of("encounterId", base.id().toString(), "round", round), false);
        }

        /** Move the player across one edge. */
        void move(String zone) {
            Working player = participants.get(EncounterTemplate.PLAYER);
            int cost = TacticalRules.moveCost(base, zone);
            String from = player.zone;
            points -= cost;
            player.zone = zone;
            step(EncounterPhase.RESOLVING);
            note("PARTICIPANT_MOVED", activation(player).put("from", from).put("to", zone).put("actionPoints", cost)
                    .put("remainingActionPoints", points).build(), true);
            step(EncounterPhase.AWAITING_ACTION);
        }

        /** Record one speech-only utterance; nothing else changes. */
        void speak(TacticalIntent.Speech words) {
            speech++;
            step(phase);
            note("COMBAT_SPEECH", activation(participants.get(EncounterTemplate.PLAYER))
                    .put("recipientId", words.recipient()).put("text", words.text()).build(), true);
        }

        /** Attempt a declared action: pay its cost and, for a scene action, set its passage. */
        void interact(TacticalIntent.Interact interact) {
            var action = base.action(interact.action());
            points -= action.cost();
            var announced = activation(participants.get(EncounterTemplate.PLAYER)).put("actionId", action.id())
                    .put("kind", action.kind().wire()).put("actionPoints", action.cost())
                    .put("remainingActionPoints", points);
            if (action.kind() == EncounterAction.Kind.SOCIAL) {
                announced.put("intent", action.intent().wire()).put("targetId", interact.target());
            } else {
                var passage = action.passage();
                passages.removeIf(set -> set.covers(passage.from(), passage.to()));
                passages.add(passage);
                announced.put("passage", Map.of("from", passage.from(), "to", passage.to(), "blocked", passage.blocked()));
            }
            step(EncounterPhase.RESOLVING);
            note("INTERACTION_ATTEMPTED", announced.build(), true);
            step(EncounterPhase.AWAITING_ACTION);
        }

        /** The player's strike: pay, resolve inside {@code RESOLVING}, and return to the decision. */
        void playerStrike(TacticalIntent.Strike intent, Dice dice) {
            this.dice = dice;
            Working player = participants.get(EncounterTemplate.PLAYER);
            int cost = profile.value("strikeCost");
            points -= cost;
            step(EncounterPhase.RESOLVING);
            strike(player, player.source.sheet().attack(intent.attack()), participants.get(intent.target()), cost, true,
                    false);
            step(EncounterPhase.AWAITING_ACTION);
        }

        /** The player's Guard action. */
        void playerGuard() {
            guard(participants.get(EncounterTemplate.PLAYER), true);
        }

        /**
         * Pay for an item whose effects are already resolved and take over the Health values they left. A player left
         * at zero by its own item falls: the activation ends and the schedule continues without it.
         */
        void playerItem(TacticalIntent.UseItem intent, ItemOutcome outcome) {
            Working player = participants.get(EncounterTemplate.PLAYER);
            int cost = profile.value("useItemCost");
            points -= cost;
            step(EncounterPhase.RESOLVING);
            var announced = activation(player).put("definitionId", outcome.definition()).put("actionPoints", cost)
                    .put("remainingActionPoints", points);
            player.health = outcome.playerHealth();
            if (intent.target() != null) {
                announced.put("targetId", intent.target()).put("healed", outcome.healed());
                Working target = participants.get(intent.target());
                if (outcome.targetHealth() != null) {
                    target.health = outcome.targetHealth();
                    defeatIfDown(target);
                }
            }
            note("COMBAT_ITEM_USED", announced.build(), true);
            defeatIfDown(player);
            if (player.status != EncounterState.Status.ACTIVE) {
                endActivation(true);
                advance();
                return;
            }
            step(EncounterPhase.AWAITING_ACTION);
        }

        /**
         * The small deterministic tactic of a non-player participant (see the class comment). Every choice goes through
         * the same legality as the player's, so a tactic can never do what the player could not.
         */
        private void tactic(Working actor) {
            if (actor.source.sheet().attacks().isEmpty()) {
                return;
            }
            int strikeCost = profile.value("strikeCost");
            if (reach(actor) == null) {
                approach(actor);
            }
            var reach = reach(actor);
            if (reach != null && points >= strikeCost) {
                points -= strikeCost;
                step(EncounterPhase.RESOLVING);
                strike(actor, reach.attack(), reach.target(), strikeCost, false, false);
                step(EncounterPhase.AWAITING_ACTION);
            }
            if (points >= profile.value("guardCost") && !actor.guarding) {
                guard(actor, false);
            }
        }

        /** An attack and the target it reaches. */
        private record Reach(CombatAttack attack, Working target) {
        }

        /** @return the first declared attack that reaches an opponent, at the nearest such opponent, then roster order */
        private Reach reach(Working actor) {
            var current = state();
            var attacker = current.participant(actor.source.id());
            var space = current.space();
            for (CombatAttack attack : attacker.sheet().attacks()) {
                var target = current.participants().stream()
                        .filter(other -> TacticalRules.attackBlock(space, attacker, attack, other) == null)
                        .min(Comparator.comparingInt((EncounterState.Combatant other) ->
                                space.distance(attacker.zone(), other.zone())).thenComparingInt(EncounterState.Combatant::ordinal));
                if (target.isPresent()) {
                    return new Reach(attack, participants.get(target.get().id()));
                }
            }
            return null;
        }

        /** Move one affordable edge that gets strictly closer to the nearest standing opponent, if there is one. */
        private void approach(Working actor) {
            var current = state();
            var space = current.space();
            var mover = current.participant(actor.source.id());
            var opponents = current.participants().stream()
                    .filter(other -> mover.opposes(other) && other.status() == EncounterState.Status.ACTIVE).toList();
            int best = nearest(space, mover.zone(), opponents);
            String destination = null;
            for (String zone : space.zones().keySet()) {
                int cost = TacticalRules.moveCost(current, mover.zone(), zone);
                if (cost >= 0 && cost <= points && nearest(space, zone, opponents) < best) {
                    best = nearest(space, zone, opponents);
                    destination = zone;
                }
            }
            if (destination != null) {
                int cost = TacticalRules.moveCost(current, mover.zone(), destination);
                String from = actor.zone;
                points -= cost;
                actor.zone = destination;
                step(EncounterPhase.RESOLVING);
                note("PARTICIPANT_MOVED", activation(actor).put("from", from).put("to", destination)
                        .put("actionPoints", cost).put("remainingActionPoints", points).build(), false);
                step(EncounterPhase.AWAITING_ACTION);
            }
        }

        /** @return the fewest action points of walking from a zone to an opponent; a large value when none can be reached */
        private int nearest(org.rpgengine.rules.ZoneGraph space, String zone, List<EncounterState.Combatant> opponents) {
            int best = Integer.MAX_VALUE;
            for (var opponent : opponents) {
                int cost = space.travelCost(zone, opponent.zone(), profile);
                if (cost >= 0) {
                    best = Math.min(best, cost);
                }
            }
            return best;
        }

        /** Take the Guard action; it holds until the start of the participant's next activation. */
        private void guard(Working actor, boolean byPlayer) {
            int cost = profile.value("guardCost");
            points -= cost;
            actor.guarding = true;
            step(EncounterPhase.RESOLVING);
            note("GUARD_RAISED", activation(actor).put("bonus", profile.value("guardBonus")).put("actionPoints", cost)
                    .put("remainingActionPoints", points).build(), byPlayer);
            step(EncounterPhase.AWAITING_ACTION);
        }

        /**
         * Resolve one legal attack: spend its ammunition, draw the dice, calculate, apply the damage and record both
         * events. The caller paid the action points and owns the surrounding phase steps.
         *
         * @param opening whether this is the opening strike, which also resolves at a slot where no points are in play
         */
        private void strike(Working attacker, CombatAttack attack, Working target, int cost, boolean byPlayer,
                boolean opening) {
            var current = state();
            var attackerNow = current.participant(attacker.source.id());
            int defense = TacticalRules.defense(current, attackerNow, attack, current.participant(target.source.id()));
            if (attack.ammunition() != null) {
                spend(attack.ammunition());
            }
            var ordinals = new ArrayList<Object>();
            var sheet = attacker.source.sheet();
            int first = recorded(INITIATIVE_SIDES, ordinals);
            int second = recorded(INITIATIVE_SIDES, ordinals);
            // Conditions and declared effects supply situational modifiers with P11.4; none exists yet.
            int situational = 0;
            var roll = CombatMath.attack(first, second, sheet.role(attack.attribute()), attack.skill(), situational,
                    profile.value("situationalModifierCap"), defense, profile.value("strongHitMargin"), attack.strongHit());
            var damageDice = new ArrayList<Object>();
            int strongDie = 0;
            int sum = 0;
            CombatMath.Damage damage = null;
            int before = target.health;
            if (roll.hit()) {
                for (int die = 0; die < attack.diceCount(); die++) {
                    int value = recorded(attack.diceSides(), ordinals);
                    damageDice.add(value);
                    sum += value;
                }
                if (roll.strong()) {
                    strongDie = recorded(profile.value("strongHitDieSides"), ordinals);
                }
                int contribution = attack.damageAttribute() == null ? 0 : sheet.role(attack.damageAttribute());
                // Temporary absorption arrives with declared effects (P11.4); the formula already consumes it first.
                damage = CombatMath.damage(sum, contribution, attack.flat(), strongDie, target.source.sheet().armor(),
                        attack.penetration(), 0, target.health);
                target.health = damage.healthAfter();
            }
            int taken = damage == null ? 0 : damage.healthLoss();
            var shown = activation(attacker).put("targetId", target.source.id()).put("attackId", attack.id())
                    .put("actionPoints", cost).put("attackDice", List.of(first, second)).put("attackTotal", roll.total())
                    .put("hit", roll.hit()).put("strongHit", roll.strong()).put("damageDice", damageDice)
                    .put("damage", taken).put("drawOrdinals", ordinals);
            if (points != null) {
                shown.put("remainingActionPoints", points);
            }
            if (strongDie > 0) {
                shown.put("strongDie", strongDie);
            }
            if (opening) {
                shown.put("opening", true);
            }
            if (target.source.player()) {
                // The player may see its own defense and Health; another participant's stay in the internal record.
                shown.put("defense", defense).put("targetHealthAfter", target.health);
            }
            if (attack.ammunition() != null) {
                shown.put("ammunition", attack.ammunition());
            }
            note("STRIKE_RESOLVED", shown.build(), byPlayer);
            var calculation = activation(attacker).put("targetId", target.source.id()).put("attackId", attack.id())
                    .put("drawOrdinals", ordinals).put("attackDice", List.of(first, second))
                    .put("attributeRole", attack.attribute()).put("attributeValue", sheet.role(attack.attribute()))
                    .put("skill", attack.skill()).put("situational", situational).put("attackTotal", roll.total())
                    .put("defenseKind", attack.defense().wire()).put("defense", defense).put("margin", roll.margin())
                    .put("hit", roll.hit()).put("strongHit", roll.strong()).put("healthBefore", before)
                    .put("healthAfter", target.health);
            if (damage != null) {
                calculation.put("damageDice", damageDice).put("strongDie", strongDie).put("flat", attack.flat())
                        .put("rawDamage", damage.raw()).put("armor", target.source.sheet().armor())
                        .put("penetration", attack.penetration()).put("effectiveArmor", damage.effectiveArmor())
                        .put("absorbed", damage.absorbed()).put("healthLoss", damage.healthLoss());
            }
            notes.add(new TacticalPlan.Note("STRIKE_CALCULATED", calculation.build(), byPlayer, true));
            var line = new TreeMap<String, Object>();
            line.put("attackerId", attacker.source.id());
            line.put("targetId", target.source.id());
            line.put("attackId", attack.id());
            line.put("attackTotal", roll.total());
            line.put("hit", roll.hit());
            line.put("strongHit", roll.strong());
            line.put("damage", taken);
            strikes.add(line);
            defeatIfDown(target);
        }

        /** A participant at zero Health is defeated, not dead (docs/11 section 13); its stance ends with it. */
        private void defeatIfDown(Working participant) {
            if (participant.health == 0 && participant.status == EncounterState.Status.ACTIVE) {
                participant.status = EncounterState.Status.DEFEATED;
                participant.guarding = false;
                note("PARTICIPANT_DEFEATED", activation(participant).build(), false);
            }
        }

        /** Spend one unit from the player's first stack of this ammunition, in identity order. */
        private void spend(String definition) {
            for (int index = 0; index < ammunition.size(); index++) {
                var stack = ammunition.get(index);
                if (stack.definition().equals(definition) && stack.quantity() > 0) {
                    ammunition.set(index, new EncounterState.Ammunition(stack.instance(), definition, stack.quantity() - 1));
                    var used = spent.get(stack.instance());
                    spent.put(stack.instance(), new TacticalPlan.AmmunitionUse(stack.instance(), definition,
                            used == null ? 1 : used.quantity() + 1));
                    return;
                }
            }
            throw new IllegalStateException("validated attack has no ammunition");
        }

        /** Assemble the plan from the working copy. */
        TacticalPlan plan() {
            var updates = new ArrayList<TacticalPlan.ParticipantUpdate>();
            for (Working participant : participants.values()) {
                if (participant.changed()) {
                    // The player's pool lives in actor_resource: its row keeps a null local Health.
                    updates.add(new TacticalPlan.ParticipantUpdate(participant.source.id(),
                            participant.source.rowVersion(), participant.zone, participant.initiative, participant.rank,
                            participant.reactions, participant.status,
                            participant.source.player() ? null : (Integer) participant.health, participant.guarding));
                }
            }
            Working player = participants.get(EncounterTemplate.PLAYER);
            return new TacticalPlan(base.id(), base.rowVersion(), steps, updates, notes, elapsed, summary(),
                    player.health - player.source.health(), List.copyOf(spent.values()));
        }

        /** Public summary of the resulting state for the receipt, with the player's legal options. */
        private Map<String, Object> summary() {
            var after = state();
            var summary = new TreeMap<String, Object>();
            summary.put("encounterId", base.id().toString());
            summary.put("phase", phase.name());
            summary.put("round", round);
            summary.put("zone", participants.get(EncounterTemplate.PLAYER).zone);
            Working acting = cursor == null ? null : acting();
            if (acting != null) {
                summary.put("acting", acting.source.id());
                summary.put("actionPoints", points);
            }
            var order = new ArrayList<Object>();
            participants.values().stream().filter(w -> w.rank != null).sorted(Comparator.comparingInt(w -> w.rank))
                    .forEach(w -> order.add(Map.of("participantId", w.source.id(), "initiative", w.initiative)));
            summary.put("initiative", order);
            var standing = new ArrayList<Object>();
            for (Working w : participants.values()) {
                standing.add(Map.of("participantId", w.source.id(), "side", w.source.side().wire(), "zone", w.zone,
                        "status", w.status.name()));
            }
            summary.put("participants", standing);
            if (!strikes.isEmpty()) {
                summary.put("strikes", strikes);
            }
            var options = TacticalRules.options(after);
            if (!options.isEmpty()) {
                summary.put("options", options);
            }
            return summary;
        }

        /** The working copy as an encounter state, so the rules can list the options that follow. */
        private EncounterState state() {
            var list = new ArrayList<EncounterState.Combatant>();
            for (Working w : participants.values()) {
                var s = w.source;
                list.add(new EncounterState.Combatant(s.id(), s.ordinal(), s.actorId(), s.side(), w.zone, w.status,
                        s.finesse(), s.initiativeModifier(), w.initiative, w.rank, w.reactions, s.rowVersion(), w.health,
                        s.healthMax(), w.guarding, s.sheet()));
            }
            return new EncounterState(base.id(), base.rowVersion(), phase, round, cursor, points, speech, passages,
                    profile, base.frozenSpace(), base.escapeZones(), base.actions(), base.opening(),
                    base.openingTarget(), openingStatus, list, ammunition);
        }

        /** Record the current encounter row state after checking its arrow. */
        private void step(EncounterPhase next) {
            EncounterPhase previous = steps.isEmpty() ? base.phase() : steps.getLast().phase();
            if (next != previous) {
                previous.require(next);
            }
            phase = next;
            steps.add(new TacticalPlan.Step(phase, round, cursor, points, speech, passages, openingStatus));
        }

        /** Append one event note. */
        private void note(String type, Map<String, Object> payload, boolean byPlayer) {
            notes.add(new TacticalPlan.Note(type, payload, byPlayer));
        }

        /** The acting participant's working copy. */
        private Working acting() {
            return participants.values().stream().filter(w -> cursor.equals(w.rank)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("the cursor names no participant"));
        }

        /** Common payload of an activation-scoped event; the player's events also name the player's actor. */
        private Payload activation(Working participant) {
            var payload = new Payload().put("encounterId", base.id().toString()).put("round", round)
                    .put("participantId", participant.source.id());
            if (participant.source.actorId() != null) {
                payload.put("actorId", participant.source.actorId().toString());
            }
            return payload;
        }

        /** Draw one die at the next ordinal of this operation and check its range. */
        private int draw(int sides) {
            int value = dice.value(nextDraw++, sides);
            if (value < 1 || value > sides) {
                throw new IllegalStateException("draw outside its die");
            }
            return value;
        }

        /** Draw one die and record its ordinal in the calculation that consumed it. */
        private int recorded(int sides, List<Object> ordinals) {
            ordinals.add(nextDraw);
            return draw(sides);
        }
    }

    /** Working tactical facts of one participant. */
    private static final class Working {
        private final EncounterState.Combatant source;
        private String zone;
        private Integer initiative;
        private Integer rank;
        private int reactions;
        private List<Integer> dice;
        private EncounterState.Status status;
        private int health;
        private boolean guarding;

        /** Start from the stored facts. */
        Working(EncounterState.Combatant source) {
            this.source = source;
            zone = source.zone();
            initiative = source.initiative();
            rank = source.rank();
            reactions = source.reactions();
            status = source.status();
            health = source.health();
            guarding = source.guarding();
        }

        /** @return whether any stored fact differs */
        boolean changed() {
            return !zone.equals(source.zone()) || !java.util.Objects.equals(initiative, source.initiative())
                    || !java.util.Objects.equals(rank, source.rank()) || reactions != source.reactions()
                    || status != source.status() || health != source.health() || guarding != source.guarding();
        }
    }

    /** Small ordered builder of non-null payload values. */
    private static final class Payload {
        private final Map<String, Object> values = new TreeMap<>();

        /** Add one value. */
        Payload put(String key, Object value) {
            values.put(key, java.util.Objects.requireNonNull(value, key));
            return this;
        }

        /** @return the payload */
        Map<String, Object> build() {
            return values;
        }
    }
}
