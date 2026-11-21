package org.rpgengine.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.knowledge.KnowledgeLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Advances the faction clocks of the pinned content inside one canonical commit (P15.2-B, ADR 0052). Like
 * {@link Relationships} it is a static transaction participant: the caller holds the campaign row lock, has written
 * this commit's world time, events and quest rows and flushed them, so everything read here is the committing state
 * and every write rolls back with the operation.
 *
 * <p><b>Due causes.</b> The causes due at the committed world time are derived from durable facts alone, never from
 * the wall clock or a read: every time boundary of a running clock at or before the committed world time, every
 * terminal quest whose recorded outcome a clock's cause names, and every completed clock another clock's cause names.
 * A cause that already has a recorded change is not due again, and a complete clock takes no cause.
 *
 * <p><b>Order and catch-up.</b> Due causes are applied in one total order: due world time, then time ticks before quest
 * endings before clock completions, then clock identity, then the cause's own identity. Each applied cause adds its
 * segments up to the clock's target and is recorded as one immutable {@code faction_clock_change} row naming the cause.
 * A change that fills a clock completes it and makes the causes naming that clock due at the same world time, so a
 * cascade runs in the same commit. At most {@value #MAX_CHANGES} changes are recorded per commit; the rest stay due,
 * because nothing marks them consumed, and the next commit applies them first because they are the oldest. Nothing is
 * skipped and nothing counts twice, and the same durable facts always give the same rows.
 *
 * <p>Quest predicates read the recorded values ({@code clock} atom), so a change recorded by one command is seen by the
 * quest evaluation of the next one. A campaign whose pinned content declares no clock returns before any query.
 */
public final class FactionClocks {
    /** Most clock changes one commit records; later due causes wait for the next commit (bounded catch-up). */
    public static final int MAX_CHANGES = 32;
    /** Query scope bumped by every recorded change. */
    public static final String SCOPE = "clocks";
    private static final Pattern SCOPE_SAFE = Pattern.compile("[a-z][a-z0-9_.:-]{0,120}");

    /** Static transaction participant and pure planner only. */
    private FactionClocks() { }

    /** Kind of fact that caused a change, with its stored spelling; the declaration order is the tie-break order. */
    public enum CauseKind {
        /** A time boundary of the clock's tick passed. */
        WORLD_TIME,
        /** A quest named by a cause ended with a matching outcome. */
        QUEST_ENDED,
        /** A clock named by a cause completed. */
        CLOCK_COMPLETED
    }

    /**
     * What the recorded changes of one clock say, read in commit order.
     *
     * @param value current value, 0 when nothing was recorded
     * @param firstDue due world time of the first recorded change, or null when none
     * @param lastTick due world time of the latest recorded time tick, or null when none
     * @param completedDue due world time of the change that completed the clock, or null while it is not complete
     * @param quests quests whose ending has been counted
     * @param sources clocks whose completion has been counted
     */
    public record History(int value, Long firstDue, Long lastTick, Long completedDue, Set<String> quests,
            Set<FactionClockRules.Key> sources) {
        /** A clock with no recorded change. */
        public static final History EMPTY = new History(0, null, null, null, Set.of(), Set.of());

        /** Freeze the sets. */
        public History {
            quests = Set.copyOf(quests);
            sources = Set.copyOf(sources);
        }

        /** @return whether the clock has reached its target */
        public boolean completed() {
            return completedDue != null;
        }
    }

    /**
     * One ended quest.
     *
     * @param quest canonical quest ID
     * @param outcome recorded outcome ID
     * @param sequence sequence of the event that ended it
     * @param worldTime world time of that event
     */
    public record Ending(String quest, String outcome, long sequence, long worldTime) {
        /** Require the identities. */
        public Ending {
            Objects.requireNonNull(quest, "quest");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * One change, as recorded.
     *
     * @param clock the clock that moved
     * @param cause kind of cause
     * @param due world time at which the cause became due: the tick boundary, the quest's ending or the other clock's
     *        completion
     * @param quest the ended quest for {@link CauseKind#QUEST_ENDED}, else null
     * @param sequence the ending event's sequence for {@link CauseKind#QUEST_ENDED}, else null
     * @param source the completed clock for {@link CauseKind#CLOCK_COMPLETED}, else null
     * @param before value before the change
     * @param after value after the change, above {@code before}
     * @param target the clock's target
     */
    public record Change(FactionClockRules.Key clock, CauseKind cause, long due, String quest, Long sequence,
            FactionClockRules.Key source, int before, int after, int target) {
        /** @return whether this change completed the clock */
        public boolean completed() {
            return after == target;
        }
    }

    /** One due cause before it is applied. */
    private record Pending(FactionClockRules.Key clock, CauseKind cause, long due, String quest, Long sequence,
            FactionClockRules.Key source, int segments) {
        private static final Comparator<Pending> ORDER = Comparator.comparingLong(Pending::due)
                .thenComparing(Pending::cause).thenComparing(Pending::clock)
                .thenComparing(pending -> pending.quest() == null ? "" : pending.quest())
                .thenComparing(pending -> pending.source() == null ? "" : pending.source().toString());
    }

    /** Mutable working state of one clock during planning. */
    private static final class Work {
        private final FactionClockRules.Clock rules;
        private int value;
        private Long firstDue;
        private Long lastTick;
        private Long completedDue;
        private final Set<String> quests;
        private final Set<FactionClockRules.Key> sources;

        private Work(FactionClockRules.Clock rules, History history) {
            this.rules = rules;
            value = history.value();
            firstDue = history.firstDue();
            lastTick = history.lastTick();
            completedDue = history.completedDue();
            quests = new HashSet<>(history.quests());
            sources = new HashSet<>(history.sources());
        }

        private boolean completed() {
            return completedDue != null;
        }

        /** Queue the clock's next time boundary when its tick runs and the boundary is not later than now. */
        private void queueTick(PriorityQueue<Pending> queue, long now) {
            FactionClockRules.Tick tick = rules.tick();
            if (tick == null || completed()) {
                return;
            }
            Long origin = tick.from() == FactionClockRules.Origin.START ? Long.valueOf(0) : firstDue;
            if (origin == null) {
                return;
            }
            long due = (lastTick == null ? origin : lastTick) + tick.seconds();
            if (due <= now) {
                queue.add(new Pending(rules.key(), CauseKind.WORLD_TIME, due, null, null, null, 1));
            }
        }

        /** Queue every cause of this clock that the other clock's completion satisfies. */
        private void queueCompletion(PriorityQueue<Pending> queue, FactionClockRules.Key completed, long due) {
            if (completed() || sources.contains(completed)) {
                return;
            }
            for (FactionClockRules.Cause cause : rules.causes()) {
                if (cause instanceof FactionClockRules.ClockCompleted other && other.clock().equals(completed)) {
                    queue.add(new Pending(rules.key(), CauseKind.CLOCK_COMPLETED, due, null, null, completed,
                            other.segments()));
                }
            }
        }
    }

    /**
     * Plan the changes due at a world time. Pure: no I/O, no clock, no randomness.
     *
     * @param rules pinned clock rules
     * @param recorded history of every clock that has recorded changes; a missing clock has {@link History#EMPTY}
     * @param endings every ended quest a cause may name (others are ignored)
     * @param now committed world time; no cause due later is applied
     * @param limit most changes to return, at least zero
     * @return the changes in the order they must be recorded; due causes beyond {@code limit} are left out and are
     *         planned again, first, from the next history
     */
    public static List<Change> plan(FactionClockRules rules, Map<FactionClockRules.Key, History> recorded,
            Collection<Ending> endings, long now, int limit) {
        Objects.requireNonNull(rules, "rules");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        var work = new TreeMap<FactionClockRules.Key, Work>();
        rules.clocks().forEach((key, clock) -> work.put(key, new Work(clock, recorded.getOrDefault(key,
                History.EMPTY))));
        var ended = new HashMap<String, Ending>();
        endings.forEach(ending -> ended.put(ending.quest(), ending));
        var queue = new PriorityQueue<Pending>(Pending.ORDER);
        for (Work clock : work.values()) {
            if (clock.completed()) {
                continue;
            }
            clock.queueTick(queue, now);
            for (FactionClockRules.Cause cause : clock.rules.causes()) {
                switch (cause) {
                    case FactionClockRules.QuestEnded quest -> {
                        Ending ending = ended.get(quest.quest());
                        if (ending != null && ending.worldTime() <= now && quest.matches(ending.outcome())
                                && !clock.quests.contains(quest.quest())) {
                            queue.add(new Pending(clock.rules.key(), CauseKind.QUEST_ENDED, ending.worldTime(),
                                    quest.quest(), ending.sequence(), null, quest.segments()));
                        }
                    }
                    case FactionClockRules.ClockCompleted other -> {
                        Work source = work.get(other.clock());
                        if (source != null && source.completed()) {
                            clock.queueCompletion(queue, other.clock(), source.completedDue);
                        }
                    }
                }
            }
        }
        var changes = new ArrayList<Change>();
        while (!queue.isEmpty() && changes.size() < limit) {
            Pending next = queue.poll();
            Work clock = work.get(next.clock());
            // A cause already counted (or one that reached a clock completed earlier in this plan) is not a change.
            boolean counted = switch (next.cause()) {
                case WORLD_TIME -> clock.lastTick != null && next.due() <= clock.lastTick;
                case QUEST_ENDED -> clock.quests.contains(next.quest());
                case CLOCK_COMPLETED -> clock.sources.contains(next.source());
            };
            if (clock.completed() || counted) {
                continue;
            }
            int before = clock.value;
            int after = Math.min(clock.rules.segments(), before + next.segments());
            boolean first = clock.firstDue == null;
            clock.value = after;
            if (first) {
                clock.firstDue = next.due();
            }
            switch (next.cause()) {
                case WORLD_TIME -> clock.lastTick = next.due();
                case QUEST_ENDED -> clock.quests.add(next.quest());
                case CLOCK_COMPLETED -> clock.sources.add(next.source());
            }
            var change = new Change(clock.rules.key(), next.cause(), next.due(), next.quest(), next.sequence(),
                    next.source(), before, after, clock.rules.segments());
            changes.add(change);
            if (change.completed()) {
                clock.completedDue = next.due();
                for (Work other : work.values()) {
                    other.queueCompletion(queue, clock.rules.key(), next.due());
                }
            } else if (next.cause() == CauseKind.WORLD_TIME || first && clock.rules.tick() != null
                    && clock.rules.tick().from() == FactionClockRules.Origin.ADVANCE) {
                // The next boundary follows this tick, or the first advance started a tick counted from it. A tick
                // counted from the start was already queued when planning began, so it is never queued twice.
                clock.queueTick(queue, now);
            }
        }
        return List.copyOf(changes);
    }

    /**
     * Record the clock changes due at this commit's world time.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked and updated to this commit's revision and world time
     * @param rules clock rules of the campaign's pinned catalog
     * @param at provenance of the committing operation (its revision and world time)
     * @return the query scopes written: {@value #SCOPE}, and {@code clock:<faction>:<clock>} for each moved clock of a
     *         root faction; empty when nothing moved
     * @throws IllegalStateException without the caller's transaction
     * @throws org.springframework.dao.DataAccessException when the database refuses a row (for example a forged cause)
     */
    public static Set<String> advanceWithin(JdbcTemplate jdbc, UUID campaign, FactionClockRules rules,
            KnowledgeLedger.Provenance at) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(at, "at");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("faction clocks require the caller's transaction");
        }
        if (rules.clocks().isEmpty()) {
            return Set.of();
        }
        var changes = plan(rules, histories(jdbc, campaign), endings(jdbc, campaign, rules), at.worldTime(),
                MAX_CHANGES);
        var scopes = new LinkedHashSet<String>();
        int ordinal = 0;
        for (Change change : changes) {
            jdbc.update("insert into faction_clock_change (campaign_id, changed_revision, ordinal, faction_id, clock_id, "
                    + "cause, due_world_time, cause_quest_id, cause_event_sequence, cause_faction_id, cause_clock_id, "
                    + "value_before, value_after, target, completed, changed_world_time) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", campaign, at.revision(), ordinal,
                    change.clock().faction(), change.clock().clock(), change.cause().name(), change.due(),
                    change.quest(), change.sequence(), change.source() == null ? null : change.source().faction(),
                    change.source() == null ? null : change.source().clock(), change.before(), change.after(),
                    change.target(), change.completed(), at.worldTime());
            ordinal++;
            scopes.add(SCOPE);
            String scope = "clock:" + change.clock().faction() + ":" + change.clock().clock();
            if (SCOPE_SAFE.matcher(scope).matches()) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    /** Read every recorded change of the campaign in commit order and fold it into one history per clock. */
    private static Map<FactionClockRules.Key, History> histories(JdbcTemplate jdbc, UUID campaign) {
        var folded = new LinkedHashMap<FactionClockRules.Key, History>();
        jdbc.query("select faction_id, clock_id, cause, due_world_time, cause_quest_id, cause_faction_id, cause_clock_id, "
                + "value_after, completed from faction_clock_change where campaign_id = ? "
                + "order by changed_revision, ordinal", row -> {
                    var key = new FactionClockRules.Key(row.getString(1), row.getString(2));
                    History before = folded.getOrDefault(key, History.EMPTY);
                    long due = row.getLong(4);
                    var quests = new HashSet<>(before.quests());
                    var sources = new HashSet<>(before.sources());
                    Long lastTick = before.lastTick();
                    switch (CauseKind.valueOf(row.getString(3))) {
                        case WORLD_TIME -> lastTick = due;
                        case QUEST_ENDED -> quests.add(row.getString(5));
                        case CLOCK_COMPLETED -> sources.add(new FactionClockRules.Key(row.getString(6),
                                row.getString(7)));
                    }
                    folded.put(key, new History(row.getInt(8), before.firstDue() == null ? due : before.firstDue(),
                            lastTick, row.getBoolean(9) ? Long.valueOf(due) : before.completedDue(), quests, sources));
                }, campaign);
        return folded;
    }

    /** Read the ended quests that some clock cause names, with their ending event's world time. */
    private static List<Ending> endings(JdbcTemplate jdbc, UUID campaign, FactionClockRules rules) {
        var named = new HashSet<String>();
        rules.clocks().values().forEach(clock -> clock.causes().forEach(cause -> {
            if (cause instanceof FactionClockRules.QuestEnded quest) {
                named.add(quest.quest());
            }
        }));
        if (named.isEmpty()) {
            return List.of();
        }
        var endings = new ArrayList<Ending>();
        jdbc.query("select q.quest_id, q.outcome_id, q.event_sequence, e.world_time_seconds from quest_state q "
                + "join campaign_event e on e.campaign_id = q.campaign_id and e.sequence = q.event_sequence "
                + "where q.campaign_id = ? and q.status in ('completed', 'failed') order by q.quest_id", row -> {
                    if (named.contains(row.getString(1))) {
                        endings.add(new Ending(row.getString(1), row.getString(2), row.getLong(3), row.getLong(4)));
                    }
                }, campaign);
        return endings;
    }
}
