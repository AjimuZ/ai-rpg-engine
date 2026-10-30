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
 * an operation never stops at another participant's turn and the player never decides out of turn. Other
 * participants' activations pass without an action in P11.2: tactics for non-player participants (the deterministic
 * fallback and constrained choices) arrive with attacks (P11.3) and P12.3/P12.4, and their unused points expire like
 * the player's. Because the player acts once per round, one operation crosses at most one round boundary.
 *
 * <p><b>Player actions</b> keep the activation open: a move or declared action passes through {@code RESOLVING}
 * and returns to {@code AWAITING_ACTION}, even at zero points, because only the explicit end of the activation
 * passes control (a speech-only utterance still costs nothing). A speech-only utterance changes only the speech
 * count. The opening strike stays {@code PENDING}: resolving it at its slot is part of attacks (P11.3).
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
     * @param dice recorded draws; only the initiative roll uses them
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
                run.endActivation(true);
                run.advance();
            }
            case TacticalIntent.MoveZone move -> run.move(move.zone());
            case TacticalIntent.Speech speech -> run.speak(speech);
            case TacticalIntent.Interact interact -> run.interact(interact);
        }
        return run.plan();
    }

    /** Mutable working copy of one encounter during a single resolution. */
    private static final class Run {
        private final EncounterState base;
        private final CombatProfile profile;
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
            for (var participant : state.participants()) {
                participants.put(participant.id(), new Working(participant));
            }
        }

        /** Roll and store initiative, announce the order, and open round one. */
        void initiative(Dice dice) {
            int ordinal = 0;
            var rolled = new ArrayList<Working>();
            for (Working participant : participants.values()) {
                int first = roll(dice, ordinal++);
                int second = roll(dice, ordinal++);
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
                        endActivation(false);
                    }
                    case ACTOR_END -> next(cursor);
                    case ROUND_END -> {
                        if (round >= profile.roundCap()
                                || participants.values().iterator().next().source.status() != EncounterState.Status.ACTIVE) {
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
                    .filter(w -> w.rank != null && w.rank > after && w.source.status() == EncounterState.Status.ACTIVE)
                    .min(Comparator.comparingInt(w -> w.rank)).orElse(null);
            if (next == null) {
                endRound();
                return;
            }
            cursor = next.rank;
            points = profile.actionPoints();
            speech = 0;
            step(EncounterPhase.ACTOR_START);
            note("ACTIVATION_STARTED", activation(next).put("actionPoints", points).build(), next.source.player());
            step(EncounterPhase.AWAITING_ACTION);
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
                if (participant.source.status() == EncounterState.Status.ACTIVE) {
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

        /** Assemble the plan from the working copy. */
        TacticalPlan plan() {
            var updates = new ArrayList<TacticalPlan.ParticipantUpdate>();
            for (Working participant : participants.values()) {
                if (participant.changed()) {
                    updates.add(new TacticalPlan.ParticipantUpdate(participant.source.id(),
                            participant.source.rowVersion(), participant.zone, participant.initiative, participant.rank,
                            participant.reactions));
                }
            }
            return new TacticalPlan(base.id(), base.rowVersion(), steps, updates, notes, elapsed, summary());
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
                list.add(new EncounterState.Combatant(s.id(), s.ordinal(), s.actorId(), s.side(), w.zone, s.status(),
                        s.finesse(), s.initiativeModifier(), w.initiative, w.rank, w.reactions, s.rowVersion()));
            }
            return new EncounterState(base.id(), base.rowVersion(), phase, round, cursor, points, speech, passages,
                    profile, base.frozenSpace(), base.escapeZones(), base.actions(), base.opening(),
                    base.openingTarget(), base.openingStatus(), list);
        }

        /** Record the current encounter row state after checking its arrow. */
        private void step(EncounterPhase next) {
            EncounterPhase previous = steps.isEmpty() ? base.phase() : steps.getLast().phase();
            if (next != previous) {
                previous.require(next);
            }
            phase = next;
            steps.add(new TacticalPlan.Step(phase, round, cursor, points, speech, passages));
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

        /** Draw one initiative die and check its range. */
        private static int roll(Dice dice, int ordinal) {
            int value = dice.value(ordinal, INITIATIVE_SIDES);
            if (value < 1 || value > INITIATIVE_SIDES) {
                throw new IllegalStateException("draw outside its die");
            }
            return value;
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

        /** Start from the stored facts. */
        Working(EncounterState.Combatant source) {
            this.source = source;
            zone = source.zone();
            initiative = source.initiative();
            rank = source.rank();
            reactions = source.reactions();
        }

        /** @return whether any stored fact differs */
        boolean changed() {
            return !zone.equals(source.zone()) || !java.util.Objects.equals(initiative, source.initiative())
                    || !java.util.Objects.equals(rank, source.rank()) || reactions != source.reactions();
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
