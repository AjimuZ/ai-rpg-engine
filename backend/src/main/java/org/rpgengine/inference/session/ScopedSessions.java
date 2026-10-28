package org.rpgengine.inference.session;

import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li><b>Reuse.</b> An equal scope reuses its session, so a role keeps continuity within its
 *       own audience until renewal.</li>
 *   <li><b>Supersession.</b> A new scope first retires every active session of the same campaign
 *       with a lower generation (all roles and audiences), and the session of the same campaign,
 *       role and audience under other hashes: a content or policy change renews the line rather
 *       than leaving context assembled under the old rules in use (docs/08 section 7).</li>
 *   <li><b>Fresh identity.</b> The new session gets a fresh random identifier, never derived
 *       from the scope, so a retired session's identifier is not reissued to the same line.</li>
 *   <li><b>Bound.</b> At most {@code capacity} sessions are active; the least recently used one
 *       is retired. Losing a session costs continuity only: the next task of that scope starts a
 *       fresh runtime conversation, which the port reports as {@code freshSession}.</li>
 * </ol>
 * Every retirement is forwarded to {@link NarrationTaskPort#retireSession}, so the adapter also
 * forgets the runtime conversation; a task already running on it finishes on its conversation.
 *
 * <p>The registry is in memory, like the adapter's session-to-conversation bindings: a backend
 * restart starts every scope afresh, which also covers a runtime switch at restart (ADR 0020
 * decision 4). Durable AI-session records with counters and checkpoint hashes, and the renewal
 * triggers that call {@link #retire}, are P09.4; the operational checkpoints they will reference
 * exist since P09.3 ({@code org.rpgengine.memory.OperationalCheckpoints}).
 *
 * <p>Thread-safety: registry state is guarded by this object's monitor; the port is always
 * called outside it, and {@link NarrationTaskPort#submit} never blocks.
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

    /** Why a session was retired; counted for diagnostics, never tied to campaign data. */
    public enum Reason {
        /** {@link #retire}: renewal or a context made unreliable by a failure. */
        EXPLICIT,
        /** A newer generation, content hash or policy hash replaced the session's line. */
        SUPERSEDED,
        /** {@link #retireCampaign}: the campaign was restored, deleted or archived. */
        CAMPAIGN,
        /** {@link #retireAll}: runtime change or owner-requested renewal of everything. */
        INSTALLATION,
        /** The least recently used session made room under the capacity bound. */
        CAPACITY
    }

    /**
     * Non-sensitive registry state: counts only, never scopes or identifiers.
     *
     * @param active sessions currently mapped
     * @param retired retirements since start, per reason (every reason present)
     */
    public record Status(int active, Map<Reason, Long> retired) {
        /** Copy the counts. */
        public Status {
            retired = Map.copyOf(retired);
        }
    }

    private final NarrationTaskPort port;
    private final int capacity;
    private final Supplier<UUID> ids;
    /** Scope to session identifier, in access order so the eldest entry is the least recently used. */
    private final LinkedHashMap<SessionScope, UUID> active = new LinkedHashMap<>(16, 0.75f, true);
    private final EnumMap<Reason, Long> retired = new EnumMap<>(Reason.class);

    /**
     * Spring constructor with random session identifiers.
     *
     * @param port the installation's task port
     * @param capacity most active sessions, 1..{@value #MAX_CAPACITY}
     *        ({@code engine.inference.sessions.capacity})
     * @throws IllegalArgumentException for an out-of-range capacity, failing startup
     */
    @Autowired
    public ScopedSessions(NarrationTaskPort port,
            @Value("${engine.inference.sessions.capacity:" + DEFAULT_CAPACITY + "}") int capacity) {
        this(port, capacity, UUID::randomUUID);
    }

    /**
     * @param port the installation's task port
     * @param capacity most active sessions, 1..{@value #MAX_CAPACITY}
     * @param ids source of fresh session identifiers; tests supply a deterministic one
     */
    ScopedSessions(NarrationTaskPort port, int capacity, Supplier<UUID> ids) {
        this.port = Objects.requireNonNull(port, "port");
        this.ids = Objects.requireNonNull(ids, "ids");
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("engine.inference.sessions.capacity must be 1.." + MAX_CAPACITY);
        }
        this.capacity = capacity;
        for (Reason reason : Reason.values()) {
            retired.put(reason, 0L);
        }
    }

    /**
     * Submit one task in the session of {@code scope}, opening or superseding sessions as the
     * class rules describe.
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
        List<UUID> superseded = new ArrayList<>();
        UUID session;
        synchronized (this) {
            String refusal = refusal(scope);
            if (refusal != null) {
                return new CompletedTaskHandle(TaskResult.withoutTurn(taskId, TaskResult.Status.REJECTED, refusal));
            }
            session = active.get(scope);
            if (session == null) {
                session = open(scope, superseded);
            }
        }
        superseded.forEach(port::retireSession);
        return port.submit(new TaskRequest(taskId, session, slot, instructions, outputSchema, timeout));
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
        UUID session;
        synchronized (this) {
            session = active.remove(scope);
            if (session != null) {
                count(Reason.EXPLICIT, 1);
            }
        }
        if (session != null) {
            port.retireSession(session);
        }
        return session != null;
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
        for (Map.Entry<SessionScope, UUID> entry : active.entrySet()) {
            if (entry.getKey().equals(scope)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** @return counts of active and retired sessions */
    public synchronized Status status() {
        return new Status(active.size(), retired);
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
     * @param retiredIds collects identifiers the caller must retire at the port
     * @return the new session identifier
     */
    private UUID open(SessionScope scope, List<UUID> retiredIds) {
        for (Iterator<Map.Entry<SessionScope, UUID>> entries = active.entrySet().iterator(); entries.hasNext();) {
            Map.Entry<SessionScope, UUID> entry = entries.next();
            SessionScope existing = entry.getKey();
            boolean olderGeneration = existing.campaignId().equals(scope.campaignId())
                    && existing.campaignGeneration() < scope.campaignGeneration();
            // An equal scope would have been reused, so a same-line entry differs in generation or hashes.
            if (olderGeneration || existing.sameLine(scope)) {
                entries.remove();
                retiredIds.add(entry.getValue());
                count(Reason.SUPERSEDED, 1);
            }
        }
        UUID session = ids.get();
        if (session == null || active.containsValue(session)) {
            throw new IllegalStateException("session identity source repeated an active identifier");
        }
        active.put(scope, session);
        while (active.size() > capacity) {
            Iterator<Map.Entry<SessionScope, UUID>> eldest = active.entrySet().iterator();
            retiredIds.add(eldest.next().getValue());
            eldest.remove();
            count(Reason.CAPACITY, 1);
        }
        return session;
    }

    /** Remove every matching session and retire it at the port outside the monitor. */
    private int retireWhere(Predicate<SessionScope> match, Reason reason) {
        List<UUID> sessions = new ArrayList<>();
        synchronized (this) {
            for (Iterator<Map.Entry<SessionScope, UUID>> entries = active.entrySet().iterator(); entries.hasNext();) {
                Map.Entry<SessionScope, UUID> entry = entries.next();
                if (match.test(entry.getKey())) {
                    sessions.add(entry.getValue());
                    entries.remove();
                }
            }
            count(reason, sessions.size());
        }
        sessions.forEach(port::retireSession);
        return sessions.size();
    }

    /** Add to a retirement counter; caller holds the monitor. */
    private void count(Reason reason, int amount) {
        retired.merge(reason, (long) amount, Long::sum);
    }
}
