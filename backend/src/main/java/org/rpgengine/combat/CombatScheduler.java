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
 *
 * <p><b>Reactions</b> (P11.4, docs/11 section 10) are predeclared and fire inside the action that provokes them: the
 * reactor's stored policy decides, never a choice after seeing a roll. Simultaneous reactions run by declared priority,
 * then initiative order, then participant ID. Depth is one: while a reaction resolves no other reaction is considered, so a
 * counter strike can never be braced or countered. A reaction that lacks its Focus, is prevented, cannot reach or would
 * not improve the defense spends nothing. Reactions per operation are bounded by {@link #MAX_REACTIONS}, and a counter
 * also needs room in the draw budget ({@link #COUNTER_DRAW_CEILING}), so every mandatory strike of the operation still fits.
 *
 * <p><b>Effects</b> (P11.4, docs/11 section 11) tick at their declared phase, simultaneous ones ordered by
 * {@link CombatEffects#order}. An effect applied while a phase is being processed first fires at a later trigger, and an
 * effect cannot apply another one, so automatic processing cannot loop; {@link #MAX_AUTOMATIC_EFFECTS} still bounds it per
 * operation, skipping the ticks beyond it with a public mark rather than failing the fight. An attack runs: declared
 * action ticks, {@code attacked} reactions, pre-roll ticks, the dice, hit reactions, damage, post-hit effects, defeat,
 * post-damage effects and {@code damaged} reactions. A tick that fells the actor at an activation start leaves it no points.
 */
public final class CombatScheduler {
    /** Sides of each initiative die (kernel). */
    static final int INITIATIVE_SIDES = CombatProfile.INITIATIVE_SIDES;

    /** Most automatic effect ticks of one operation; a fight of many long-lasting effects skips the rest visibly. */
    static final int MAX_AUTOMATIC_EFFECTS = 64;
    /** Most reactions of one operation, which bounds its events. */
    static final int MAX_REACTIONS = 12;
    /**
     * A counter strike is considered only while the operation has used at most this many draws: 128 durable draws, less
     * seven for this strike, less seven each for the eight other participants' strikes and the opening strike that may
     * still follow.
     */
    static final int COUNTER_DRAW_CEILING = 58;

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
        // Any action can draw since P11.4: a move or an item can provoke a counter strike.
        run.draws(dice);
        switch (intent) {
            case TacticalIntent.RollInitiative roll -> {
                run.initiative();
                run.advance();
            }
            case TacticalIntent.EndActivation end -> {
                run.endActivation(true);
                run.advance();
            }
            case TacticalIntent.MoveZone move -> run.move(move.zone());
            case TacticalIntent.Speech speech -> run.speak(speech);
            case TacticalIntent.Interact interact -> run.interact(interact);
            case TacticalIntent.Strike strike -> run.playerStrike(strike);
            case TacticalIntent.Guard guard -> run.playerGuard();
            case TacticalIntent.UseAbility use -> run.playerAbility(use);
            case TacticalIntent.SetReactionPolicy policy -> run.setPolicy(policy);
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
        private final Map<String, CombatEffect> library;
        private int effectsApplied;
        /** Automatic ticks of this operation. */
        private int automatic;
        /** Whether the automatic bound was reached and announced. */
        private boolean bounded;
        /** Reactions of this operation. */
        private int reactionsFired;
        /** True while a reaction resolves: the kernel's depth of one, so nothing it does can provoke another. */
        private boolean reacting;
        /** Counts the attacks and actions of this operation, for trigger keys that one round can repeat. */
        private int actionIndex;
        /** Public lines waiting for the next {@code EFFECTS_RESOLVED}. */
        private final List<Object> effectLines = new ArrayList<>();
        /** Lines naming hidden values, waiting for the next internal {@code EFFECTS_CALCULATED}. */
        private final List<Object> hiddenLines = new ArrayList<>();

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
            library = state.effects();
            effectsApplied = state.effectsApplied();
            for (var participant : state.participants()) {
                participants.put(participant.id(), new Working(participant));
            }
        }

        /** Roll and store initiative, announce the order, seed the effects the participants came with, open round one. */
        void initiative() {
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
            for (Working participant : participants.values()) {
                for (String condition : participant.source.sheet().conditions()) {
                    // A condition without a combat block changes nothing here: it keeps its exploration meaning.
                    if (library.containsKey(condition)) {
                        applyEffect(participant, condition, null);
                    }
                }
            }
            flush("entry");
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
            String reason = player.status != EncounterState.Status.ACTIVE ? "ATTACKER_DOWN"
                    : current.prevented(attacker, CombatEffect.Prevents.ACTIONS) ? "PREVENTED"
                    : attacker.sheet().attacks().isEmpty() ? "NO_ATTACK" : null;
            boolean able = player.status == EncounterState.Status.ACTIVE
                    && !current.prevented(attacker, CombatEffect.Prevents.ACTIONS);
            for (CombatAttack attack : able ? attacker.sheet().attacks() : List.<CombatAttack>of()) {
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
                        // A player who fell at the start of its activation has no decision left.
                        Working acting = acting();
                        if (acting.source.player() && acting.status == EncounterState.Status.ACTIVE) {
                            return;
                        }
                        tactic(acting);
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
            speech = 0;
            // Effects tied to the participant's next activation expire first (docs/11 section 6), then its start
            // triggers run, and only a participant that can still act receives usable points.
            next.guarding = false;
            expireUntil(next);
            fire(CombatEffect.Phase.ACTIVATION_START, List.of(next), "");
            flush(CombatEffect.Phase.ACTIVATION_START.wire());
            boolean able = next.status == EncounterState.Status.ACTIVE
                    && !CombatEffects.prevents(next.effects, library, CombatEffect.Prevents.ACTIONS);
            points = able ? profile.actionPoints() : 0;
            if (next.source.player()) {
                points = Math.max(0, points - openingCharge);
                openingCharge = 0;
            }
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
            fire(CombatEffect.Phase.ACTIVATION_END, List.of(acting), "");
            var counted = CombatEffects.afterActivation(acting.effects, library);
            acting.effects = new ArrayList<>(counted.kept());
            counted.expired().forEach(active -> ended(acting, active, "EXPIRED"));
            flush(CombatEffect.Phase.ACTIVATION_END.wire());
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
            fire(CombatEffect.Phase.ROUND_END, standing(), "");
            for (Working participant : participants.values()) {
                var after = CombatEffects.afterRound(participant.effects, library, profile.roundSeconds());
                participant.effects = new ArrayList<>(after.kept());
                after.expired().forEach(active -> ended(participant, active, "EXPIRED"));
            }
            flush(CombatEffect.Phase.ROUND_END.wire());
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
            fire(CombatEffect.Phase.ROUND_OPEN, standing(), "");
            flush(CombatEffect.Phase.ROUND_OPEN.wire());
        }

        /** @return the participants still standing, in roster order */
        private List<Working> standing() {
            return participants.values().stream().filter(w -> w.status == EncounterState.Status.ACTIVE).toList();
        }

        /** Move the player across one edge; an opponent's declared reaction can fell it before it leaves. */
        void move(String zone) {
            Working player = participants.get(EncounterTemplate.PLAYER);
            int cost = TacticalRules.moveCost(base, zone);
            points -= cost;
            step(EncounterPhase.RESOLVING);
            traverse(player, zone, cost, true);
            settle(player);
        }

        /**
         * Cross one edge inside a resolving step: the mover declares, an opponent's declared reaction answers it leaving,
         * and a mover still standing arrives. The blow lands while the mover is still in the zone it is leaving.
         */
        private void traverse(Working mover, String zone, int cost, boolean byPlayer) {
            String from = mover.zone;
            if (!declare(mover)) {
                return;
            }
            var provocation = new Provocation(mover, null, null);
            react(CombatReaction.Trigger.LEAVES_ZONE, provocation, null);
            flush("movement");
            if (mover.status == EncounterState.Status.ACTIVE) {
                mover.zone = zone;
                note("PARTICIPANT_MOVED", activation(mover).put("from", from).put("to", zone).put("actionPoints", cost)
                        .put("remainingActionPoints", points).build(), byPlayer);
            }
        }

        /**
         * Return to the decision after the player's action, or, when the action left the player down, end the activation
         * and continue the schedule without it.
         */
        private void settle(Working player) {
            flush("action");
            if (player.status != EncounterState.Status.ACTIVE) {
                endActivation(true);
                advance();
                return;
            }
            step(EncounterPhase.AWAITING_ACTION);
        }

        /**
         * The part of a trigger key that separates triggers one round can repeat: one operation's row version never recurs,
         * and the index separates the actions inside it.
         */
        private String repeatable() {
            actionIndex++;
            return ":v" + base.rowVersion() + "a" + actionIndex;
        }

        /** Fire the actor's declared-action ticks. @return whether the actor still stands to carry the action out */
        private boolean declare(Working actor) {
            fire(CombatEffect.Phase.ACTION_DECLARED, List.of(actor), repeatable());
            flush(CombatEffect.Phase.ACTION_DECLARED.wire());
            return actor.status == EncounterState.Status.ACTIVE;
        }

        /** Store one predeclared policy; nothing else changes, and it costs nothing. */
        void setPolicy(TacticalIntent.SetReactionPolicy policy) {
            Working player = participants.get(EncounterTemplate.PLAYER);
            player.policies.put(policy.reaction(), policy.policy());
            step(phase);
            note("REACTION_POLICY_SET", activation(player).put("reactionId", policy.reaction())
                    .put("policy", policy.policy().canonicalForm()).build(), true);
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
            Working player = participants.get(EncounterTemplate.PLAYER);
            var action = base.action(interact.action());
            points -= action.cost();
            step(EncounterPhase.RESOLVING);
            if (declare(player)) {
                var announced = activation(player).put("actionId", action.id())
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
                note("INTERACTION_ATTEMPTED", announced.build(), true);
            }
            settle(player);
        }

        /** The player's strike: pay, resolve inside {@code RESOLVING}, and return to the decision. */
        void playerStrike(TacticalIntent.Strike intent) {
            Working player = participants.get(EncounterTemplate.PLAYER);
            int cost = profile.value("strikeCost");
            points -= cost;
            step(EncounterPhase.RESOLVING);
            if (declare(player)) {
                strike(player, player.source.sheet().attack(intent.attack()), participants.get(intent.target()), cost, true,
                        false);
            }
            settle(player);
        }

        /** The player's Guard action. */
        void playerGuard() {
            Working player = participants.get(EncounterTemplate.PLAYER);
            guard(player, true);
            settle(player);
        }

        /**
         * The player's ability: pay its points and Focus and record the use, then run its operations in order. One operation's
         * dice are drawn once, for all its targets; a target that has fallen by then is skipped. Damage here is no attack,
         * so no reaction answers it.
         */
        void playerAbility(TacticalIntent.UseAbility intent) {
            Working player = participants.get(EncounterTemplate.PLAYER);
            var ability = player.source.sheet().ability(intent.ability());
            points -= ability.cost();
            player.focus -= ability.focus();
            var use = player.uses.get(ability.id());
            player.uses.put(ability.id(), new CombatAbility.Use(round, use == null ? 1 : use.uses() + 1));
            step(EncounterPhase.RESOLVING);
            if (declare(player)) {
                var targets = ability.targets() == CombatAbility.Targets.SELF ? List.of(player)
                        : intent.targets().stream().map(participants::get).toList();
                var announced = activation(player).put("abilityId", ability.id()).put("actionPoints", ability.cost())
                        .put("remainingActionPoints", points).put("focus", ability.focus())
                        .put("targets", targets.stream().map(target -> (Object) target.source.id()).toList());
                note("COMBAT_ABILITY_USED", announced.build(), true);
                for (var operation : ability.operations()) {
                    operate(player, ability, operation, operation.toSelf() ? List.of(player) : targets);
                }
            }
            settle(player);
        }

        /** Run one ability operation for each standing affected participant. */
        private void operate(Working user, CombatAbility ability, CombatAbility.Operation operation, List<Working> affected) {
            switch (operation.kind()) {
                case DAMAGE, HEAL -> {
                    var ordinals = new ArrayList<Object>();
                    var faces = new ArrayList<Object>();
                    int sum = 0;
                    for (int die = 0; die < operation.diceCount(); die++) {
                        int value = recorded(operation.diceSides(), ordinals);
                        faces.add(value);
                        sum += value;
                    }
                    int attribute = operation.attribute() == null ? 0 : user.source.sheet().role(operation.attribute());
                    for (Working target : affected) {
                        if (target.status == EncounterState.Status.ACTIVE) {
                            amountLine(user, ability, operation, target, sum, attribute, faces, ordinals);
                        }
                    }
                }
                case APPLY -> affected.stream().filter(target -> target.status == EncounterState.Status.ACTIVE)
                        .forEach(target -> applyEffect(target, operation.effectId(), user));
                case REMOVE -> affected.forEach(target -> {
                    var removed = target.effects.stream().filter(active -> active.effectId().equals(operation.effectId()))
                            .toList();
                    target.effects.removeAll(removed);
                    removed.forEach(active -> ended(target, active, "REMOVED"));
                });
            }
        }

        /** Apply a damage or healing operation's amount to one target and record it. */
        private void amountLine(Working user, CombatAbility ability, CombatAbility.Operation operation, Working target,
                int sum, int attribute, List<Object> faces, List<Object> ordinals) {
            int before = target.health;
            var line = new TreeMap<String, Object>();
            line.put("kind", operation.kind().name());
            line.put("abilityId", ability.id());
            line.put("sourceId", user.source.id());
            line.put("holderId", target.source.id());
            line.put("dice", faces);
            line.put("drawOrdinals", ordinals);
            var hidden = new TreeMap<>(line);
            if (operation.kind() == CombatAbility.Kind.DAMAGE) {
                var damage = CombatMath.damage(sum, attribute, operation.flat(), 0, target.source.sheet().armor(),
                        operation.penetration(), CombatEffects.absorption(target.effects), target.health);
                target.health = damage.healthAfter();
                absorb(target, damage.absorbed());
                line.put("damage", damage.healthLoss());
                hidden.put("rawDamage", damage.raw());
                hidden.put("armor", target.source.sheet().armor());
                hidden.put("absorbed", damage.absorbed());
            } else {
                long healed = CombatMath.healed(target.health, target.source.healthMax(), sum + attribute + operation.flat());
                target.health += Math.toIntExact(healed);
                line.put("healed", healed);
            }
            hidden.put("healthBefore", before);
            hidden.put("healthAfter", target.health);
            if (target.source.player()) {
                line.put("healthAfter", target.health);
            }
            effectLines.add(line);
            hiddenLines.add(hidden);
            defeatIfDown(target);
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
            // The contract already ran in the commands layer, which has the catalog, so the tick follows its effects.
            if (player.status == EncounterState.Status.ACTIVE) {
                declare(player);
            }
            settle(player);
        }

        /**
         * The small deterministic tactic of a non-player participant (see the class comment). Every choice goes through
         * the same legality as the player's, so a tactic can never do what the player could not.
         */
        private void tactic(Working actor) {
            if (actor.source.sheet().attacks().isEmpty()) {
                return;
            }
            if (actor.status != EncounterState.Status.ACTIVE
                    || CombatEffects.prevents(actor.effects, library, CombatEffect.Prevents.ACTIONS)) {
                return;
            }
            int strikeCost = profile.value("strikeCost");
            if (reach(actor) == null && !CombatEffects.prevents(actor.effects, library, CombatEffect.Prevents.MOVEMENT)) {
                approach(actor);
            }
            var reach = actor.status == EncounterState.Status.ACTIVE ? reach(actor) : null;
            if (reach != null && points >= strikeCost) {
                points -= strikeCost;
                step(EncounterPhase.RESOLVING);
                if (declare(actor)) {
                    strike(actor, reach.attack(), reach.target(), strikeCost, false, false);
                }
                flush("action");
                step(EncounterPhase.AWAITING_ACTION);
            }
            if (actor.status == EncounterState.Status.ACTIVE && points >= profile.value("guardCost") && !actor.guarding) {
                guard(actor, false);
                flush("action");
                step(EncounterPhase.AWAITING_ACTION);
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
                points -= cost;
                step(EncounterPhase.RESOLVING);
                traverse(actor, destination, cost, false);
                flush("action");
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

        /**
         * Take the Guard action inside a resolving step; it holds until the start of the participant's next activation.
         * The caller returns from the step, because a declared-action tick can fell the actor.
         */
        private void guard(Working actor, boolean byPlayer) {
            int cost = profile.value("guardCost");
            points -= cost;
            step(EncounterPhase.RESOLVING);
            if (declare(actor)) {
                actor.guarding = true;
                note("GUARD_RAISED", activation(actor).put("bonus", profile.value("guardBonus")).put("actionPoints", cost)
                        .put("remainingActionPoints", points).build(), byPlayer);
            }
        }

        /** What one provoking action offers the reactions it triggers. */
        private static final class Provocation {
            private final Working attacker;
            private final Working target;
            private final CombatAttack attack;
            /** The highest Guard bonus a reaction gave against this attack. */
            private int guardBonus;
            /** Absorption reactions gave against this attack only. */
            private int absorb;

            /** Bind the action; a move has neither target nor attack. */
            Provocation(Working attacker, Working target, CombatAttack attack) {
                this.attacker = attacker;
                this.target = target;
                this.attack = attack;
            }
        }

        /**
         * Consider every reaction of one trigger (docs/11 section 10). Does nothing during another reaction: reactions
         * never chain. Simultaneous reactions run by declared priority, then initiative order, then participant ID.
         *
         * @param only when not null, consider only the reactions it accepts
         */
        private void react(CombatReaction.Trigger trigger, Provocation provocation, java.util.function.Predicate<CombatReaction> only) {
            if (reacting) {
                return;
            }
            record Row(Working reactor, CombatReaction reaction) {
            }
            var rows = new ArrayList<Row>();
            for (Working reactor : standing()) {
                for (CombatReaction reaction : reactor.source.sheet().allReactions()) {
                    if (reaction.trigger() == trigger && (only == null || only.test(reaction))
                            && answers(reactor, reaction, provocation)) {
                        rows.add(new Row(reactor, reaction));
                    }
                }
            }
            rows.sort(Comparator.comparingInt((Row row) -> -row.reaction().priority())
                    .thenComparingInt(row -> row.reactor().rank == null ? Integer.MAX_VALUE : row.reactor().rank)
                    .thenComparing(row -> row.reactor().source.id()).thenComparing(row -> row.reaction().id()));
            reacting = true;
            try {
                rows.forEach(row -> resolveReaction(row.reactor(), row.reaction(), provocation, trigger));
            } finally {
                reacting = false;
            }
        }

        /** Whether this reaction's own constraints cover this provocation, before its policy or budget is read. */
        private boolean answers(Working reactor, CombatReaction reaction, Provocation provocation) {
            if (reaction.trigger() == CombatReaction.Trigger.LEAVES_ZONE) {
                return reactor != provocation.attacker && reactor.zone.equals(provocation.attacker.zone)
                        && reactor.source.opposes(provocation.attacker.source);
            }
            boolean covered = reactor == provocation.target || reaction.protectsAllies()
                    && reactor.zone.equals(provocation.target.zone) && !reactor.source.opposes(provocation.target.source);
            // An attack from an obscured zone the reactor is outside of is not a visible attack.
            boolean visible = reactor.zone.equals(provocation.attacker.zone)
                    || !state().space().zones().get(provocation.attacker.zone).obscured();
            return covered && visible && reaction.attacks().admits(provocation.attack);
        }

        /** Fire one eligible reaction unless its policy, budget, Focus or usefulness says otherwise. */
        private void resolveReaction(Working reactor, CombatReaction reaction, Provocation provocation,
                CombatReaction.Trigger trigger) {
            if (reactor.status != EncounterState.Status.ACTIVE || reactor.reactions < 1 || reactionsFired >= MAX_REACTIONS
                    || !reactor.policy(reaction.id()).fires(reactor.health, reactor.source.healthMax(), reactor.focus)) {
                return;
            }
            if (CombatEffects.prevents(reactor.effects, library, CombatEffect.Prevents.REACTIONS)) {
                skipped(reactor, reaction, "PREVENTED");
                return;
            }
            if (reaction.focus() > 0 && (reaction.focus() > reactor.focus
                    || CombatEffects.prevents(reactor.effects, library, CombatEffect.Prevents.RESOURCES))) {
                skipped(reactor, reaction, "INSUFFICIENT_RESOURCES");
                return;
            }
            CombatAttack counter = null;
            int bonus = 0;
            switch (reaction.kind()) {
                case GUARD -> {
                    bonus = reaction.amount() > 0 ? reaction.amount() : profile.value("guardBonus");
                    var current = state();
                    var attacker = current.participant(provocation.attacker.source.id());
                    var target = current.participant(provocation.target.source.id());
                    // One stacking key: a Brace that would not raise the effective defense spends nothing.
                    if (TacticalRules.defense(current, attacker, provocation.attack, target, Math.max(bonus, provocation.guardBonus))
                            <= TacticalRules.defense(current, attacker, provocation.attack, target, provocation.guardBonus)) {
                        skipped(reactor, reaction, "REDUNDANT");
                        return;
                    }
                }
                case COUNTER -> {
                    if (nextDraw > COUNTER_DRAW_CEILING || provocation.attacker.status != EncounterState.Status.ACTIVE) {
                        skipped(reactor, reaction, "UNAVAILABLE");
                        return;
                    }
                    counter = counterAttack(reactor, reaction, provocation.attacker);
                    if (counter == null) {
                        skipped(reactor, reaction, "NO_ATTACK_IN_REACH");
                        return;
                    }
                }
                case ABSORB, APPLY -> { }
            }
            reactionsFired++;
            reactor.reactions--;
            reactor.focus -= reaction.focus();
            var announced = activation(reactor).put("reactionId", reaction.id()).put("trigger", trigger.wire())
                    .put("kind", reaction.kind().wire()).put("provokedBy", provocation.attacker.source.id());
            if (provocation.target != null) {
                announced.put("targetId", provocation.target.source.id());
            }
            note("REACTION_RESOLVED", announced.build(), reactor.source.player());
            switch (reaction.kind()) {
                case GUARD -> provocation.guardBonus = Math.max(provocation.guardBonus, bonus);
                case ABSORB -> provocation.absorb += reaction.amount();
                case APPLY -> {
                    Working holder = reaction.onAttacker() ? provocation.attacker : reactor;
                    if (holder.status == EncounterState.Status.ACTIVE) {
                        applyEffect(holder, reaction.effectId(), reactor);
                    }
                }
                case COUNTER -> strike(reactor, counter, provocation.attacker, 0, reactor.source.player(), false);
            }
        }

        /** The attack a counter makes: the named one or the first that reaches, armed, at the moment of the counter. */
        private CombatAttack counterAttack(Working reactor, CombatReaction reaction, Working target) {
            var current = state();
            var attacker = current.participant(reactor.source.id());
            var victim = current.participant(target.source.id());
            for (CombatAttack attack : attacker.sheet().attacks()) {
                boolean named = reaction.attackId() == null || reaction.attackId().equals(attack.id());
                boolean armed = attack.ammunition() == null
                        || reactor.source.player() && current.ammunitionOf(attack.ammunition()) > 0;
                if (named && armed && TacticalRules.attackBlock(current.space(), attacker, attack, victim) == null) {
                    return attack;
                }
            }
            return null;
        }

        /**
         * Record why the player's own reaction did not fire. An opponent's stays unstated, because its Focus and budget
         * are hidden; a reaction never invents a cost to go ahead.
         */
        private void skipped(Working reactor, CombatReaction reaction, String reason) {
            if (reactor.source.player()) {
                var line = new TreeMap<String, Object>();
                line.put("kind", "REACTION_SKIPPED");
                line.put("holderId", reactor.source.id());
                line.put("reactionId", reaction.id());
                line.put("reason", reason);
                effectLines.add(line);
            }
        }

        /**
         * Apply one frozen effect and announce the outcome. A stored definition is always there: the freeze step copied
         * every effect the encounter can reach.
         */
        private void applyEffect(Working holder, String effectId, Working source) {
            CombatEffect effect = library.get(effectId);
            if (effect == null) {
                throw new IllegalStateException("effect " + effectId + " has no frozen definition");
            }
            boolean own = cursor != null && holder.rank != null && cursor.equals(holder.rank);
            var applied = CombatEffects.apply(holder.effects, effect, library, source == null ? null : source.source.id(),
                    holder.source.id(), effectsApplied + 1, own);
            holder.effects = new ArrayList<>(applied.held());
            if (applied.instance() != null && applied.instance().sequence() == effectsApplied + 1) {
                effectsApplied++;
            }
            applied.removed().forEach(active -> ended(holder, active, "REPLACED"));
            var line = new TreeMap<String, Object>();
            line.put("kind", "APPLIED");
            line.put("holderId", holder.source.id());
            line.put("effectId", effectId);
            line.put("outcome", applied.outcome().name());
            if (source != null) {
                line.put("sourceId", source.source.id());
            }
            if (applied.instance() != null) {
                line.put("instanceId", applied.instance().instanceId());
                line.put("stacks", applied.instance().stacks());
            }
            effectLines.add(line);
        }

        /** Announce that an instance ended. The caller already removed it. */
        private void ended(Working holder, CombatEffects.Active active, String reason) {
            var line = new TreeMap<String, Object>();
            line.put("kind", "ENDED");
            line.put("holderId", holder.source.id());
            line.put("effectId", active.effectId());
            line.put("instanceId", active.instanceId());
            line.put("reason", reason);
            effectLines.add(line);
        }

        /** The participant's activation starts: effects that last until then end, on every holder. */
        private void expireUntil(Working actor) {
            for (Working holder : participants.values()) {
                var gone = holder.effects.stream().filter(active -> actor.source.id().equals(active.untilId())).toList();
                holder.effects.removeAll(gone);
                gone.forEach(active -> ended(holder, active, "EXPIRED"));
            }
        }

        /** Consume absorption from a holder's instances, announcing any shield this spends. */
        private void absorb(Working holder, int amount) {
            var before = holder.effects;
            var after = CombatEffects.consumeAbsorption(before, library, amount);
            holder.effects = new ArrayList<>(after);
            for (var active : before) {
                if (after.stream().noneMatch(kept -> kept.instanceId().equals(active.instanceId()))) {
                    ended(holder, active, "DEPLETED");
                }
            }
        }

        /**
         * Run one phase's ticks for these holders, simultaneous ones in effect order across all of them. The instances are
         * read once: one applied meanwhile first fires at a later trigger, and one that ended meanwhile does not fire, so
         * each resolves once for its trigger key. Beyond {@link #MAX_AUTOMATIC_EFFECTS} the rest is skipped visibly.
         *
         * @param scope what separates repeated triggers of this phase within one round; empty for one per round
         */
        private void fire(CombatEffect.Phase phase, List<Working> holders, String scope) {
            record Due(Working holder, CombatEffects.Active active, CombatEffect.Tick tick) {
            }
            var due = new ArrayList<Due>();
            for (Working holder : holders) {
                if (holder.status == EncounterState.Status.ACTIVE) {
                    for (var active : holder.effects) {
                        library.get(active.effectId()).ticks().stream().filter(tick -> tick.phase() == phase)
                                .forEach(tick -> due.add(new Due(holder, active, tick)));
                    }
                }
            }
            var order = CombatEffects.order(library);
            due.sort((left, right) -> order.compare(left.active(), right.active()));
            for (Due row : due) {
                if (row.holder().status != EncounterState.Status.ACTIVE || row.holder().effects.stream()
                        .noneMatch(active -> active.instanceId().equals(row.active().instanceId()))) {
                    continue;
                }
                if (automatic >= MAX_AUTOMATIC_EFFECTS) {
                    if (!bounded) {
                        bounded = true;
                        var line = new TreeMap<String, Object>();
                        line.put("kind", "BOUNDED");
                        line.put("phase", phase.wire());
                        effectLines.add(line);
                    }
                    return;
                }
                automatic++;
                tick(row.holder(), row.active(), row.tick(), phase, scope);
            }
        }

        /** One tick: a fixed amount per stack, damage through absorption and past armor, healing that never revives. */
        private void tick(Working holder, CombatEffects.Active active, CombatEffect.Tick tick, CombatEffect.Phase phase,
                String scope) {
            int amount = tick.amount() * active.stacks();
            int before = holder.health;
            var line = new TreeMap<String, Object>();
            line.put("kind", "TICK");
            line.put("holderId", holder.source.id());
            line.put("effectId", active.effectId());
            line.put("instanceId", active.instanceId());
            line.put("phase", phase.wire());
            line.put("triggerKey", active.instanceId() + ":" + round + ":" + phase.wire() + scope);
            var hidden = new TreeMap<>(line);
            if (tick.heal()) {
                long healed = CombatMath.healed(holder.health, holder.source.healthMax(), amount);
                holder.health += Math.toIntExact(healed);
                line.put("healed", healed);
            } else {
                var damage = CombatMath.damage(0, 0, amount, 0, 0, 0, CombatEffects.absorption(holder.effects), holder.health);
                holder.health = damage.healthAfter();
                absorb(holder, damage.absorbed());
                line.put("damage", damage.healthLoss());
                hidden.put("absorbed", damage.absorbed());
            }
            hidden.put("healthBefore", before);
            hidden.put("healthAfter", holder.health);
            if (holder.source.player()) {
                line.put("healthAfter", holder.health);
            }
            effectLines.add(line);
            hiddenLines.add(hidden);
            defeatIfDown(holder);
        }

        /** Publish the lines gathered since the last flush: one public event and, with hidden values, one internal. */
        private void flush(String phaseName) {
            if (!effectLines.isEmpty()) {
                note("EFFECTS_RESOLVED", Map.of("encounterId", base.id().toString(), "round", round, "phase", phaseName,
                        "lines", List.copyOf(effectLines)), false);
                effectLines.clear();
            }
            if (!hiddenLines.isEmpty()) {
                notes.add(new TacticalPlan.Note("EFFECTS_CALCULATED", Map.of("encounterId", base.id().toString(),
                        "round", round, "phase", phaseName, "lines", List.copyOf(hiddenLines)), false, true));
                hiddenLines.clear();
            }
        }

        /**
         * Resolve one legal attack: spend its ammunition, draw the dice, calculate, apply the damage and record both
         * events. The caller paid the action points and owns the surrounding phase steps.
         *
         * @param opening whether this is the opening strike, which also resolves at a slot where no points are in play
         */
        private void strike(Working attacker, CombatAttack attack, Working target, int cost, boolean byPlayer,
                boolean opening) {
            if (attack.ammunition() != null) {
                spend(attack.ammunition());
            }
            var provocation = new Provocation(attacker, target, attack);
            react(CombatReaction.Trigger.ATTACKED, provocation, null);
            String scope = repeatable();
            fire(CombatEffect.Phase.PRE_ROLL, List.of(target), scope);
            if (target.status != EncounterState.Status.ACTIVE) {
                // A pre-roll tick felled the target: the attempt is paid, and nothing is left to roll against.
                note("STRIKE_LAPSED", activation(attacker).put("targetId", target.source.id()).put("attackId", attack.id())
                        .put("reason", "TARGET_DOWN").build(), byPlayer);
                flush(CombatEffect.Phase.PRE_ROLL.wire());
                return;
            }
            var current = state();
            var attackerNow = current.participant(attacker.source.id());
            int defense = TacticalRules.defense(current, attackerNow, attack, current.participant(target.source.id()),
                    provocation.guardBonus);
            var ordinals = new ArrayList<Object>();
            var sheet = attacker.source.sheet();
            int first = recorded(INITIATIVE_SIDES, ordinals);
            int second = recorded(INITIATIVE_SIDES, ordinals);
            int situational = CombatEffects.attack(attacker.effects, library);
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
                // After the dice, before the damage: what a hit reaction adds can still meet this blow. Its counters wait
                // until this attack's record is complete.
                react(CombatReaction.Trigger.HIT, provocation, reaction -> reaction.kind() != CombatReaction.Kind.COUNTER);
                int contribution = attack.damageAttribute() == null ? 0 : sheet.role(attack.damageAttribute());
                int held = CombatEffects.absorption(target.effects);
                damage = CombatMath.damage(sum, contribution, attack.flat(), strongDie, target.source.sheet().armor(),
                        attack.penetration(), provocation.absorb + held, target.health);
                target.health = damage.healthAfter();
                // The reaction's absorption meets this blow first; only the rest wears down lasting shields.
                absorb(target, Math.max(0, damage.absorbed() - provocation.absorb));
            }
            int taken = damage == null ? 0 : damage.healthLoss();
            var shown = activation(attacker).put("targetId", target.source.id()).put("attackId", attack.id())
                    .put("actionPoints", cost).put("attackDice", List.of(first, second)).put("attackTotal", roll.total())
                    .put("hit", roll.hit()).put("strongHit", roll.strong()).put("damageDice", damageDice)
                    .put("damage", taken).put("drawOrdinals", ordinals);
            // A counter is paid by a reaction: the points in play belong to the participant it answers.
            if (points != null && !reacting) {
                shown.put("remainingActionPoints", points);
            }
            if (reacting) {
                shown.put("reaction", true);
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
            if (roll.hit()) {
                onHit(attacker, attack, target, CombatEffect.Phase.POST_HIT);
                fire(CombatEffect.Phase.POST_HIT, List.of(target), scope);
            }
            defeatIfDown(target);
            if (taken > 0) {
                onHit(attacker, attack, target, CombatEffect.Phase.POST_DAMAGE);
                fire(CombatEffect.Phase.POST_DAMAGE, List.of(target), scope);
            }
            flush(CombatEffect.Phase.POST_DAMAGE.wire());
            if (roll.hit()) {
                react(CombatReaction.Trigger.HIT, provocation, reaction -> reaction.kind() == CombatReaction.Kind.COUNTER);
            }
            if (taken > 0) {
                react(CombatReaction.Trigger.DAMAGED, provocation, null);
            }
            flush("reaction");
        }

        /** Apply the attack's declared effects of one phase; a holder that has fallen receives nothing. */
        private void onHit(Working attacker, CombatAttack attack, Working target, CombatEffect.Phase phase) {
            for (CombatAttack.OnHit effect : attack.onHit()) {
                Working holder = effect.toSelf() ? attacker : target;
                if (effect.phase() == phase && holder.status == EncounterState.Status.ACTIVE && holder.health > 0) {
                    applyEffect(holder, effect.effectId(), attacker);
                }
            }
        }

        /** A participant at zero Health is defeated, not dead (docs/11 section 13); its stance ends with it. */
        private void defeatIfDown(Working participant) {
            if (participant.health == 0 && participant.status == EncounterState.Status.ACTIVE) {
                participant.status = EncounterState.Status.DEFEATED;
                participant.guarding = false;
                note("PARTICIPANT_DEFEATED", activation(participant).build(), false);
                // Its own effects end with it; another holder's follow their definition, and one waiting for this
                // participant's next activation has nothing left to wait for.
                participant.effects.forEach(active -> ended(participant, active, "HOLDER_DEFEATED"));
                participant.effects = new ArrayList<>();
                String id = participant.source.id();
                for (Working holder : participants.values()) {
                    for (var active : List.copyOf(holder.effects)) {
                        boolean source = id.equals(active.sourceId()) && library.get(active.effectId()).endsWithSource();
                        if (source || id.equals(active.untilId())) {
                            holder.effects.remove(active);
                            ended(holder, active, source ? "SOURCE_DEFEATED" : "ACTOR_GONE");
                        }
                    }
                }
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
                            participant.source.player() ? null : (Integer) participant.health, participant.guarding,
                            participant.source.player() ? null : (Integer) participant.focus, participant.effects,
                            participant.policies, participant.uses));
                }
            }
            flush("operation");
            Working player = participants.get(EncounterTemplate.PLAYER);
            return new TacticalPlan(base.id(), base.rowVersion(), steps, updates, notes, elapsed, summary(),
                    player.health - player.source.health(), List.copyOf(spent.values()),
                    player.focus - player.source.focus());
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
                var line = new TreeMap<String, Object>();
                line.put("participantId", w.source.id());
                line.put("side", w.source.side().wire());
                line.put("zone", w.zone);
                line.put("status", w.status.name());
                // Present only with active effects: a fight without any reads as it did before P11.4.
                if (!w.effects.isEmpty()) {
                    line.put("effects", w.effects.stream().map(active -> (Object) Map.of("effectId", active.effectId(),
                            "stacks", active.stacks())).toList());
                }
                standing.add(line);
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
                        s.healthMax(), w.guarding, s.sheet(), w.focus, s.focusMax(), w.effects, w.policies, w.uses));
            }
            return new EncounterState(base.id(), base.rowVersion(), phase, round, cursor, points, speech, passages,
                    profile, base.frozenSpace(), base.escapeZones(), base.actions(), base.opening(),
                    base.openingTarget(), openingStatus, list, ammunition, library, effectsApplied);
        }

        /** Record the current encounter row state after checking its arrow. */
        private void step(EncounterPhase next) {
            EncounterPhase previous = steps.isEmpty() ? base.phase() : steps.getLast().phase();
            if (next != previous) {
                previous.require(next);
            }
            phase = next;
            steps.add(new TacticalPlan.Step(phase, round, cursor, points, speech, passages, openingStatus, effectsApplied));
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
        private int focus;
        private List<CombatEffects.Active> effects;
        private final Map<String, CombatReaction.Policy> policies;
        private final Map<String, CombatAbility.Use> uses;

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
            focus = source.focus();
            effects = new ArrayList<>(source.effects());
            policies = new TreeMap<>(source.policies());
            uses = new TreeMap<>(source.uses());
        }

        /** @return the participant's policy for a reaction; a reaction without one never fires */
        CombatReaction.Policy policy(String reaction) {
            return policies.getOrDefault(reaction, CombatReaction.Policy.OFF);
        }

        /** @return whether any stored fact differs */
        boolean changed() {
            return !zone.equals(source.zone()) || !java.util.Objects.equals(initiative, source.initiative())
                    || !java.util.Objects.equals(rank, source.rank()) || reactions != source.reactions()
                    || status != source.status() || health != source.health() || guarding != source.guarding()
                    || focus != source.focus() || !effects.equals(source.effects()) || !policies.equals(source.policies())
                    || !uses.equals(source.uses());
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
