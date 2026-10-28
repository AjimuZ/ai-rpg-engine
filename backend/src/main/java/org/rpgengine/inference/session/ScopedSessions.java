package org.rpgengine.inference.session;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.rpgengine.inference.CompletedTaskHandle;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The only way engine code submits inference tasks: it maps each {@link SessionScope} to its own
 * logical session identifier and submits through the installation's {@link NarrationTaskPort}
 * (P08.3, ADR 0007, docs/06 section 7). Callers name a scope, never a session identifier, so a
 * privileged planner conversation can never be handed to the public narrator, and two campaigns,
 * roles, audiences or policies never share a runtime conversation. {@code SessionBoundaryTest}
 * fails the build if other main code builds a {@link TaskRequest} or calls the port's
 * {@code submit} or {@code retireSession}. Since P08.6 {@link #submit} is called only by the fair
 * {@code org.rpgengine.inference.scheduling.InferenceScheduler} at dispatch time
 * ({@code SchedulerBoundaryTest}), so the session is resolved when the task actually starts and a
 * generation fence or supersession that happened while it waited still applies.
 *
 * <p>Rules, in the order {@link #submit} applies them:
 * <ol>
 *   <li><b>Consistency.</b> A scope whose campaign already has active sessions under another
 *       adventure is {@code REJECTED} with {@value #SCOPE_CONFLICT}; a campaign belongs to one
 *       adventure, so this is a caller defect, never a reason to share or replace sessions.</li>
 *   <li><b>Generation fence.</b> A scope older than an active session of its campaign (a lower
 *       recovery generation, for example a callback from before a restore) is {@code REJECTED}
 *       with {@value #STALE_GENERATION}. It neither runs nor disturbs the current sessions.</li>
 *   <li><b>Renewal</b> (P09.4, docs/08 section 7). An equal scope's session that reached the
 *       {@link RenewalPolicy} turn ceiling, or whose accumulated conversation plus this task would
 *       cross the context-pressure threshold, is retired ({@link Reason#TURN_CEILING},
 *       {@link Reason#CONTEXT_PRESSURE}) and replaced before this task runs. Dispatch time is the
 *       earliest safe boundary of the line, because the scheduler never runs two tasks of one
 *       line at once. When the {@link Rehydrator} reports that a commit may be in progress, the
 *       renewal is deferred and the task runs in the current session ({@code deferred}).</li>
 *   <li><b>Reuse.</b> Otherwise an equal scope reuses its session, so a role keeps continuity
 *       within its own audience until renewal.</li>
 *   <li><b>Supersession.</b> A new scope first retires every active session of the same campaign
 *       with a lower generation (all roles and audiences), and the session of the same campaign,
 *       role and audience under other hashes: a content or policy change renews the line rather
 *       than leaving context assembled under the old rules in use (docs/08 section 7).</li>
 *   <li><b>Fresh identity.</b> The new session gets a fresh random identifier, never derived
 *       from the scope, so a retired session's identifier is not reissued to the same line.</li>
 *   <li><b>Rehydration.</b> When the line (campaign, role, audience) had an earlier session, in
 *       this process or, per the {@link SessionJournal}, in an earlier one, the first task of the
 *       new session carries the rehydrator's continuity block before its own instructions. A block
 *       that would push the instructions past {@link TaskRequest#MAX_INSTRUCTION_BYTES} is
 *       omitted and recorded as such.</li>
 *   <li><b>Bound.</b> At most {@code capacity} sessions are active; the least recently used one
 *       is retired. Losing a session costs continuity only: the next task of that scope starts a
 *       fresh runtime conversation, which the port reports as {@code freshSession}.</li>
 * </ol>
 *
 * <p><b>Counting</b> ({@link #settled}, called by the scheduler for every task it forwarded): a
 * result that may have run a runtime turn (it has provenance, or it is unconfirmed) adds one turn
 * and its estimated size (input plus output, or the slot's output allowance when there is no
 * output) to the session; a completed one also counts toward the turn ceiling. A turn that may
 * have run but did not complete (failed, timed out, cancelled or unconfirmed) leaves the
 * conversation in an unknown state, so the session is retired at once as
 * {@link Reason#UNRELIABLE}. Results that never reached a turn change nothing.
 *
 * <p><b>Owner renewal.</b> {@link #renewCampaign} retires every session of one campaign as
 * {@link Reason#OWNER}; the caller ({@code org.rpgengine.memory.SessionRenewalService}) first
 * proves that the campaign is idle. No retirement touches campaign state, events, the public
 * transcript or any pending decision: renewal is not a new campaign, a rest or a turn.
 *
 * <p>Every retirement is forwarded to {@link NarrationTaskPort#retireSession}, so the adapter also
 * forgets the runtime conversation; a task already running on it finishes on its conversation and
 * is still counted. The registry is in memory, like the adapter's session-to-conversation
 * bindings: a backend restart starts every scope afresh (the journal's {@link #recover} marks the
 * previous process's records {@code RESTART}), which also covers a runtime switch at restart
 * (ADR 0020 decision 4).
 *
 * <p>Thread-safety: registry state is guarded by this object's monitor; the port, the journal and
 * the rehydrator are always called outside it, and {@link NarrationTaskPort#submit} never blocks.
 * Because the monitor is released while a block is prepared, {@link #submit} re-checks the scope
 * afterwards and only replaces the exact session it decided to renew.
 */
@Component
public final class ScopedSessions {
    /** Default and largest number of active sessions; the default matches the adapter's binding bound. */
    public static final int DEFAULT_CAPACITY = 256;
    /** Upper bound of the configurable capacity. */
    public static final int MAX_CAPACITY = 4096;
    /** Error code of a scope older than its campaign's active sessions. */
    public static final String STALE_GENERATION = "stale-campaign-generation";
    /** Error code of a campaign scoped under two adventures. */
    public static final String SCOPE_CONFLICT = "session-scope-conflict";
    /** Separates the continuity block from the task's own instructions. */
    static final String SEPARATOR = "\n\n";
    /** Bytes per estimated unit, as the scheduler's usage estimate. */
    static final int BYTES_PER_UNIT = 4;
    /** Most tasks tracked between submission and settlement; older entries are dropped uncounted. */
    static final int MAX_IN_FLIGHT = 4096;
    /** Most lines whose last retirement reason is remembered for rehydration decisions. */
    static final int MAX_LINE_HISTORY = 4 * MAX_CAPACITY;
    private static final System.Logger LOG = System.getLogger(ScopedSessions.class.getName());

    /** Why a session was retired; counted for diagnostics, never tied to campaign data. */
    public enum Reason {
        /** {@link #retire}: a caller renewed one scope. */
        EXPLICIT,
        /** A newer generation, content hash or policy hash replaced the session's line. */
        SUPERSEDED,
        /** {@link #retireCampaign}: the campaign was restored, deleted or archived. */
        CAMPAIGN,
        /** {@link #retireAll}: runtime change or owner-requested renewal of everything. */
        INSTALLATION,
        /** The least recently used session made room under the capacity bound. */
        CAPACITY,
        /** The session completed {@link RenewalPolicy#turnCeiling()} tasks. */
        TURN_CEILING,
        /** The accumulated conversation would cross the context-pressure threshold. */
        CONTEXT_PRESSURE,
        /** A turn that may have run did not complete, so the conversation's content is uncertain. */
        UNRELIABLE,
        /** {@link #renewCampaign}: the owner renewed an idle campaign's sessions. */
        OWNER;

        /** @return lowercase hyphenated name used in continuity blocks */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * Non-sensitive registry state: counts only, never scopes or identifiers.
     *
     * @param active sessions currently mapped
     * @param retired retirements since start, per reason (every reason present)
     * @param deferred renewals postponed because a commit may have been in progress
     * @param warned sessions that crossed the context-pressure warning threshold
     * @param rehydrated new sessions whose first task carried a continuity block
     */
    public record Status(int active, Map<Reason, Long> retired, long deferred, long warned, long rehydrated) {
        /** Copy the counts. */
        public Status {
            retired = Map.copyOf(retired);
        }

        /** Status without renewal activity. */
        public Status(int active, Map<Reason, Long> retired) {
            this(active, retired, 0, 0, 0);
        }
    }

    /** One live logical session and its counters; mutated only under the registry monitor. */
    private static final class Session {
        final UUID id;
        final SessionScope scope;
        int turns;
        int completed;
        long units;
        boolean warned;

        Session(UUID id, SessionScope scope) {
            this.id = id;
            this.scope = scope;
        }

        SessionJournal.Usage usage() {
            return new SessionJournal.Usage(turns, completed, units, warned);
        }
    }

    /** Continuity line of sessions: the same campaign, role and audience under any generation or hashes. */
    private record Line(UUID campaignId, AiRole role, Audience audience) {
        static Line of(SessionScope scope) {
            return new Line(scope.campaignId(), scope.role(), scope.audience());
        }
    }

    /** A forwarded task awaiting {@link #settled}. */
    private record InFlight(Session session, long inputUnits, long outputAllowance) {
    }

    /** A retirement to forward to the port and the journal outside the monitor. */
    private record Retirement(Session session, Reason reason) {
    }

    private final NarrationTaskPort port;
    private final int capacity;
    private final Supplier<UUID> ids;
    private final RenewalPolicy policy;
    private final SessionJournal journal;
    private final Rehydrator rehydrator;
    private final Clock clock;
    /** Scope to session, in access order so the eldest entry is the least recently used. */
    private final LinkedHashMap<SessionScope, Session> active = new LinkedHashMap<>(16, 0.75f, true);
    private final EnumMap<Reason, Long> retired = new EnumMap<>(Reason.class);
    private final LinkedHashMap<UUID, InFlight> inFlight = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, InFlight> eldest) {
            return size() > MAX_IN_FLIGHT;
        }
    };
    /** Last retirement reason of each recently retired line, so its next session is rehydrated. */
    private final LinkedHashMap<Line, Reason> lineHistory = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Line, Reason> eldest) {
            return size() > MAX_LINE_HISTORY;
        }
    };
    private long deferred;
    private long warned;
    private long rehydrated;

    /**
     * Spring constructor with random session identifiers.
     *
     * @param port the installation's task port
     * @param capacity most active sessions, 1..{@value #MAX_CAPACITY}
     *        ({@code engine.inference.sessions.capacity})
     * @param environment source of the {@link RenewalPolicy} ({@code engine.inference.sessions.*})
     * @param journal durable session records; {@link SessionJournal#NONE} when no bean exists
     *        (contexts without storage)
     * @param rehydrator continuity blocks of renewed sessions; {@link Rehydrator#NONE} when absent
     * @throws IllegalArgumentException for an out-of-range capacity or renewal setting, failing startup
     */
    @Autowired
    public ScopedSessions(NarrationTaskPort port,
            @Value("${engine.inference.sessions.capacity:" + DEFAULT_CAPACITY + "}") int capacity,
            Environment environment, ObjectProvider<SessionJournal> journal, ObjectProvider<Rehydrator> rehydrator) {
        this(port, capacity, RenewalPolicy.from(environment), journal.getIfAvailable(() -> SessionJournal.NONE),
                rehydrator.getIfAvailable(() -> Rehydrator.NONE), UUID::randomUUID, Clock.systemUTC());
    }

    /**
     * Registry with the default renewal policy and no journal or rehydration.
     *
     * @param port the installation's task port
     * @param capacity most active sessions, 1..{@value #MAX_CAPACITY}
     */
    public ScopedSessions(NarrationTaskPort port, int capacity) {
        this(port, capacity, UUID::randomUUID);
    }

    /**
     * @param port the installation's task port
     * @param capacity most active sessions, 1..{@value #MAX_CAPACITY}
     * @param ids source of fresh session identifiers; tests supply a deterministic one
     */
    ScopedSessions(NarrationTaskPort port, int capacity, Supplier<UUID> ids) {
        this(port, capacity, RenewalPolicy.defaults(), SessionJournal.NONE, Rehydrator.NONE, ids, Clock.systemUTC());
    }

    /**
     * Fully configured registry.
     *
     * @param port the installation's task port
     * @param capacity most active sessions, 1..{@value #MAX_CAPACITY}
     * @param policy renewal thresholds
     * @param journal durable session records
     * @param rehydrator continuity blocks of renewed sessions
     * @param ids source of fresh session identifiers
     * @param clock time source of journal records
     * @throws IllegalArgumentException for an out-of-range capacity
     */
    public ScopedSessions(NarrationTaskPort port, int capacity, RenewalPolicy policy, SessionJournal journal,
            Rehydrator rehydrator, Supplier<UUID> ids, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.rehydrator = Objects.requireNonNull(rehydrator, "rehydrator");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("engine.inference.sessions.capacity must be 1.." + MAX_CAPACITY);
        }
        this.capacity = capacity;
        for (Reason reason : Reason.values()) {
            retired.put(reason, 0L);
        }
    }

    /**
     * Startup, before any submission: retire the journal records left active by an earlier
     * process. Never throws for storage failures (journal contract).
     *
     * @return number of records retired
     */
    public int recover() {
        return journal.recover(clock.instant());
    }

    /** @return the renewal thresholds in force */
    public RenewalPolicy policy() {
        return policy;
    }

    /**
     * Submit one task in the session of {@code scope}, renewing, opening or superseding sessions
     * as the class rules describe.
     *
     * @param scope isolation key the task's context was assembled for
     * @param taskId caller task identity, unique per live task
     * @param slot logical inference slot
     * @param instructions task text, already filtered for the scope's audience
     * @param outputSchema JSON Schema object text
     * @param timeout total budget from submission
     * @return the port's handle, or a completed {@code REJECTED} handle with
     *         {@value #SCOPE_CONFLICT} or {@value #STALE_GENERATION}; nothing is sent then
     * @throws NullPointerException for a missing argument
     * @throws IllegalArgumentException when {@link TaskRequest} rejects the request; the scope's
     *         session may already have been opened, which is harmless
     * @throws IllegalStateException when the identity source repeats an active identifier
     */
    public TaskHandle submit(SessionScope scope, UUID taskId, InferenceSlot slot, String instructions,
            String outputSchema, Duration timeout) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(outputSchema, "outputSchema");
        long allowance = outputAllowance(slot);
        Line line = Line.of(scope);
        Session reuse = null;
        Session renewing = null;
        Reason due = null;
        String cause;
        synchronized (this) {
            String refusal = refusal(scope);
            if (refusal != null) {
                return rejected(taskId, refusal);
            }
            Session current = active.get(scope);
            if (current != null) {
                due = due(current, units(instructions, outputSchema) + allowance);
                if (due == null) {
                    reuse = current;
                    track(taskId, current, instructions, outputSchema, allowance);
                } else {
                    renewing = current;
                }
            }
            cause = due != null ? due.name() : openingCause(scope, line);
        }
        if (reuse != null) {
            return port.submit(new TaskRequest(taskId, reuse.id, slot, instructions, outputSchema, timeout));
        }
        // Outside the monitor: storage and context assembly may take a while.
        boolean history = cause != null || journal.hasHistory(scope.campaignId(), scope.role(), scope.audience());
        String openedCause = cause != null ? cause : history ? "RESTART" : "FIRST";
        Rehydrator.Result prepared = history ? prepare(scope, openedCause, renewing != null) : null;

        List<Retirement> retirements = new ArrayList<>();
        Session session;
        boolean opened;
        synchronized (this) {
            String refusal = refusal(scope);
            if (refusal != null) {
                return rejected(taskId, refusal);
            }
            Session current = active.get(scope);
            if (renewing != null && prepared instanceof Rehydrator.Deferred && current == renewing) {
                // Never renew inside an unresolved commit race; the line keeps its session for now.
                deferred++;
                session = current;
                opened = false;
            } else if (current != null && current != renewing) {
                // Another caller opened the scope meanwhile; the scheduler never does this, but the
                // registry stays correct: use it, without a block assembled for another session.
                session = current;
                opened = false;
            } else {
                if (current != null) {
                    active.remove(scope);
                    retirements.add(new Retirement(current, due));
                    count(due, 1);
                    lineHistory.put(line, due);
                }
                session = open(scope, retirements);
                opened = true;
            }
        }
        Instant now = clock.instant();
        for (Retirement retirement : retirements) {
            port.retireSession(retirement.session().id);
            journal.retired(retirement.session().id, retirement.reason(), now);
        }
        String sent = instructions;
        if (opened) {
            String basis = "NONE";
            String code = null;
            Rehydrator.Ready ready = null;
            if (prepared instanceof Rehydrator.Ready block) {
                ready = block;
                String combined = block.text() + SEPARATOR + instructions;
                if (combined.getBytes(StandardCharsets.UTF_8).length <= TaskRequest.MAX_INSTRUCTION_BYTES) {
                    sent = combined;
                    basis = block.basis();
                    synchronized (this) {
                        rehydrated++;
                    }
                } else {
                    basis = "OMITTED";
                }
            } else if (prepared instanceof Rehydrator.Unavailable unavailable) {
                basis = "UNAVAILABLE";
                code = unavailable.code();
            } else if (prepared instanceof Rehydrator.Deferred postponed) {
                // The renewed session was retired by someone else while the block was prepared.
                basis = "UNAVAILABLE";
                code = postponed.code();
            }
            journal.opened(new SessionJournal.Opened(session.id, scope, openedCause, ready, basis, code, now));
        }
        synchronized (this) {
            track(taskId, session, sent, outputSchema, allowance);
        }
        return port.submit(new TaskRequest(taskId, session.id, slot, sent, outputSchema, timeout));
    }

    /**
     * Account a forwarded task's terminal result to its session (see the class comment). Called by
     * the scheduler on settlement; unknown or already settled identities are ignored. Never throws
     * for journal failures.
     *
     * @param taskId identity the task was submitted with
     * @param result its terminal result
     */
    public void settled(UUID taskId, TaskResult result) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(result, "result");
        Session session;
        SessionJournal.Usage usage;
        boolean unreliable = false;
        boolean warnedNow = false;
        synchronized (this) {
            InFlight flight = inFlight.remove(taskId);
            boolean ran = result.provenance() != null || result.status() == TaskResult.Status.UNRESOLVED;
            if (flight == null || !ran) {
                return;
            }
            session = flight.session();
            boolean completed = result.status() == TaskResult.Status.COMPLETED;
            session.turns++;
            if (completed) {
                session.completed++;
            }
            long output = completed ? units(result.output(), "") : flight.outputAllowance();
            session.units += flight.inputUnits() + output;
            if (!session.warned && session.units >= policy.warnUnits()) {
                session.warned = true;
                warned++;
                warnedNow = true;
            }
            if (!completed && active.get(session.scope) == session) {
                active.remove(session.scope);
                count(Reason.UNRELIABLE, 1);
                lineHistory.put(Line.of(session.scope), Reason.UNRELIABLE);
                unreliable = true;
            }
            usage = session.usage();
        }
        Instant now = clock.instant();
        journal.used(session.id, usage, now);
        if (unreliable) {
            port.retireSession(session.id);
            journal.retired(session.id, Reason.UNRELIABLE, now);
        }
        if (warnedNow) {
            LOG.log(System.Logger.Level.INFO, "AI session crossed the context-pressure warning threshold ("
                    + policy.warnPercent() + "% of " + policy.contextWindow() + " estimated units)");
        }
    }

    /**
     * Retire the session of one scope, so its next task starts a fresh runtime conversation:
     * renewal, or recovery after a failure made the context unreliable (docs/08 section 7).
     * Idempotent.
     *
     * @param scope scope to renew
     * @return true when an active session was retired
     */
    public boolean retire(SessionScope scope) {
        Objects.requireNonNull(scope, "scope");
        return retireWhere(scope::equals, Reason.EXPLICIT) > 0;
    }

    /**
     * Retire every session of one campaign, of every role and audience: restore, deletion or
     * archival. Idempotent.
     *
     * @param campaignId campaign whose sessions end
     * @return number of sessions retired
     */
    public int retireCampaign(UUID campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return retireWhere(scope -> scope.campaignId().equals(campaignId), Reason.CAMPAIGN);
    }

    /**
     * Owner-requested renewal of one idle campaign: retire every session of the campaign, so each
     * line's next task starts a fresh conversation carrying a continuity block. The caller proves
     * idleness first; a task that nevertheless still runs finishes on its old conversation.
     * Campaign state and the transcript are untouched. Idempotent.
     *
     * @param campaignId campaign whose sessions are renewed
     * @return number of sessions retired
     */
    public int renewCampaign(UUID campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return retireWhere(scope -> scope.campaignId().equals(campaignId), Reason.OWNER);
    }

    /**
     * Retire every session of the installation: a runtime change without restart or an owner
     * request to renew everything. Idempotent.
     *
     * @return number of sessions retired
     */
    public int retireAll() {
        return retireWhere(scope -> true, Reason.INSTALLATION);
    }

    /**
     * Read the current session of a scope without counting as a use (the least-recently-used
     * order is unchanged). For diagnostics and tests; submission never needs it.
     *
     * @param scope scope to look up
     * @return the active session identifier, if any
     */
    public synchronized Optional<UUID> activeSession(SessionScope scope) {
        Objects.requireNonNull(scope, "scope");
        // Iteration is not an access in an access-ordered map, unlike get().
        for (Map.Entry<SessionScope, Session> entry : active.entrySet()) {
            if (entry.getKey().equals(scope)) {
                return Optional.of(entry.getValue().id);
            }
        }
        return Optional.empty();
    }

    /**
     * Counters of the active session of a scope, for diagnostics and tests.
     *
     * @param scope scope to look up
     * @return its usage, if a session is active
     */
    public synchronized Optional<SessionJournal.Usage> usage(SessionScope scope) {
        Objects.requireNonNull(scope, "scope");
        for (Map.Entry<SessionScope, Session> entry : active.entrySet()) {
            if (entry.getKey().equals(scope)) {
                return Optional.of(entry.getValue().usage());
            }
        }
        return Optional.empty();
    }

    /** @return counts of active, retired, deferred, warned and rehydrated sessions */
    public synchronized Status status() {
        return new Status(active.size(), retired, deferred, warned, rehydrated);
    }

    /**
     * @param session an active session
     * @param nextUnits estimated input plus output allowance of the task about to run
     * @return the renewal reason that applies before that task, or null
     */
    private Reason due(Session session, long nextUnits) {
        if (session.completed >= policy.turnCeiling()) {
            return Reason.TURN_CEILING;
        }
        // A session without turns is never renewed for pressure: a fresh one would be no smaller.
        if (session.turns > 0 && session.units + nextUnits > policy.renewUnits()) {
            return Reason.CONTEXT_PRESSURE;
        }
        return null;
    }

    /**
     * Why a scope without a session of its own gets one; caller holds the monitor.
     *
     * @return SUPERSEDED when a session of its line (under another generation or hashes) is
     *         active, the line's last retirement reason when this process remembers one, or null
     *         when only the journal can tell (RESTART or FIRST)
     */
    private String openingCause(SessionScope scope, Line line) {
        for (SessionScope existing : active.keySet()) {
            if (existing.sameLine(scope)) {
                return Reason.SUPERSEDED.name();
            }
        }
        Reason last = lineHistory.get(line);
        return last == null ? null : last.name();
    }

    /** Ask the rehydrator; an unexpected failure is reported as unavailable, never thrown. */
    private Rehydrator.Result prepare(SessionScope scope, String cause, boolean deferrable) {
        try {
            String wire = cause.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            return Objects.requireNonNull(rehydrator.prepare(scope, wire, deferrable), "rehydration");
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "session rehydration failed: " + failure.getClass().getName());
            return new Rehydrator.Unavailable("rehydration-failed");
        }
    }

    /** Remember a forwarded task for {@link #settled}; caller holds the monitor. */
    private void track(UUID taskId, Session session, String instructions, String outputSchema, long allowance) {
        inFlight.put(taskId, new InFlight(session, units(instructions, outputSchema), allowance));
    }

    /** @return the refusal code for {@code scope}, or null when it may run; caller holds the monitor */
    private String refusal(SessionScope scope) {
        String code = null;
        for (SessionScope existing : active.keySet()) {
            if (!existing.campaignId().equals(scope.campaignId())) {
                continue;
            }
            if (!existing.adventureId().equals(scope.adventureId())) {
                return SCOPE_CONFLICT;
            }
            if (existing.campaignGeneration() > scope.campaignGeneration()) {
                code = STALE_GENERATION;
            }
        }
        return code;
    }

    /**
     * Open the session of a scope that has none, retiring what it supersedes and what exceeds the
     * capacity; caller holds the monitor.
     *
     * @param scope new scope
     * @param retirements collects sessions the caller must retire at the port and the journal
     * @return the new session
     */
    private Session open(SessionScope scope, List<Retirement> retirements) {
        for (Iterator<Map.Entry<SessionScope, Session>> entries = active.entrySet().iterator(); entries.hasNext();) {
            Map.Entry<SessionScope, Session> entry = entries.next();
            SessionScope existing = entry.getKey();
            boolean olderGeneration = existing.campaignId().equals(scope.campaignId())
                    && existing.campaignGeneration() < scope.campaignGeneration();
            // An equal scope would have been reused, so a same-line entry differs in generation or hashes.
            if (olderGeneration || existing.sameLine(scope)) {
                entries.remove();
                retirements.add(new Retirement(entry.getValue(), Reason.SUPERSEDED));
                lineHistory.put(Line.of(existing), Reason.SUPERSEDED);
                count(Reason.SUPERSEDED, 1);
            }
        }
        UUID id = ids.get();
        if (id == null || active.values().stream().anyMatch(session -> session.id.equals(id))) {
            throw new IllegalStateException("session identity source repeated an active identifier");
        }
        Session session = new Session(id, scope);
        active.put(scope, session);
        while (active.size() > capacity) {
            Iterator<Map.Entry<SessionScope, Session>> eldest = active.entrySet().iterator();
            Map.Entry<SessionScope, Session> entry = eldest.next();
            retirements.add(new Retirement(entry.getValue(), Reason.CAPACITY));
            lineHistory.put(Line.of(entry.getKey()), Reason.CAPACITY);
            eldest.remove();
            count(Reason.CAPACITY, 1);
        }
        return session;
    }

    /** Remove every matching session and retire it at the port and the journal outside the monitor. */
    private int retireWhere(Predicate<SessionScope> match, Reason reason) {
        List<Session> sessions = new ArrayList<>();
        synchronized (this) {
            for (Iterator<Map.Entry<SessionScope, Session>> entries = active.entrySet().iterator(); entries.hasNext();) {
                Map.Entry<SessionScope, Session> entry = entries.next();
                if (match.test(entry.getKey())) {
                    sessions.add(entry.getValue());
                    lineHistory.put(Line.of(entry.getKey()), reason);
                    entries.remove();
                }
            }
            count(reason, sessions.size());
        }
        Instant now = clock.instant();
        for (Session session : sessions) {
            port.retireSession(session.id);
            journal.retired(session.id, reason, now);
        }
        return sessions.size();
    }

    /** Add to a retirement counter; caller holds the monitor. */
    private void count(Reason reason, int amount) {
        retired.merge(reason, (long) amount, Long::sum);
    }

    /** @return a completed refusal; nothing is sent */
    private static TaskHandle rejected(UUID taskId, String code) {
        return new CompletedTaskHandle(TaskResult.withoutTurn(taskId, TaskResult.Status.REJECTED, code));
    }

    /** @return estimated units of the given texts, rounded up */
    static long units(String first, String second) {
        long bytes = (long) first.getBytes(StandardCharsets.UTF_8).length + second.getBytes(StandardCharsets.UTF_8).length;
        return (bytes + BYTES_PER_UNIT - 1) / BYTES_PER_UNIT;
    }

    /**
     * @param slot logical slot
     * @return estimated output units of one turn of that weight class, as the scheduler reserves
     */
    static long outputAllowance(InferenceSlot slot) {
        return switch (slot) {
            case LIGHT -> 1_024;
            case STANDARD -> 2_048;
            case DELIBERATE -> 4_096;
        };
    }
}
