package org.rpgengine.inference.scheduling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link TaskLedger} for tests without a database (contract runner and pure scheduler
 * suites). It mirrors the {@link JdbcTaskLedger} lifecycle, ownership, charge and permit rules,
 * with a configurable number of permits so the multi-permit policy can be exercised through the
 * scheduler; campaign scope checks are scripted through {@link #generation}. The real-PostgreSQL
 * behaviour is proven separately by {@code InferenceSchedulerTest}.
 */
public final class MemoryTaskLedger implements TaskLedger {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "FAILED", "TIMED_OUT",
            "UNRESOLVED", "UNAVAILABLE", "REJECTED", "ABANDONED");

    /** One ledger row. */
    public static final class Row {
        final UUID campaignId;
        final long generation;
        final WorkClass workClass;
        final long estimate;
        String status = "QUEUED";
        String code;
        UUID instance;
        long charged;
        Instant dispatchedAt;
        String reference;
        boolean preempted;

        Row(ScheduledTask task, UUID instance, long estimate) {
            this.campaignId = task.scope().campaignId();
            this.generation = task.scope().campaignGeneration();
            this.workClass = task.workClass();
            this.estimate = estimate;
            this.instance = instance;
        }

        /** @return lifecycle status */
        public String status() {
            return status;
        }

        /** @return outcome code */
        public String code() {
            return code;
        }

        /** @return charged usage */
        public long charged() {
            return charged;
        }

        /** @return runtime reference */
        public String reference() {
            return reference;
        }

        /** @return preemption mark */
        public boolean preempted() {
            return preempted;
        }
    }

    private final int permits;
    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    /** Campaign generations; a campaign absent here is accepted at any generation. */
    private final Map<UUID, Long> generation = new HashMap<>();
    private Suspension suspension = Suspension.NONE;

    /** Ledger with the single qualified permit. */
    public MemoryTaskLedger() {
        this(1);
    }

    /** @param permits rows that may hold a permit at once */
    public MemoryTaskLedger(int permits) {
        this.permits = permits;
    }

    /** Script a campaign's current recovery generation. */
    public synchronized void generation(UUID campaignId, long value) {
        generation.put(campaignId, value);
    }

    /** @return a row, or null */
    public synchronized Row row(UUID taskId) {
        return rows.get(taskId);
    }

    @Override
    public synchronized Admission admit(Entry entry) {
        ScheduledTask task = entry.task();
        if (rows.containsKey(task.taskId())) {
            return Admission.DUPLICATE;
        }
        Long current = generation.get(task.scope().campaignId());
        if (current != null && current != task.scope().campaignGeneration()) {
            return Admission.STALE_GENERATION;
        }
        rows.put(task.taskId(), new Row(task, entry.instance(), entry.estimatedUsage()));
        return Admission.ADMITTED;
    }

    @Override
    public synchronized Dispatch dispatch(UUID taskId, UUID instance, Instant now) {
        Row row = rows.get(taskId);
        if (row == null || !row.status.equals("QUEUED") || !row.instance.equals(instance)) {
            return Dispatch.FENCED;
        }
        long holding = rows.values().stream().filter(r -> r.status.equals("DISPATCHED")
                || r.status.equals("RECONCILING")).count();
        if (holding >= permits) {
            return Dispatch.PERMIT_BUSY;
        }
        Long current = generation.get(row.campaignId);
        if (current != null && current != row.generation) {
            return Dispatch.STALE_GENERATION;
        }
        row.status = "DISPATCHED";
        row.dispatchedAt = now;
        row.charged = row.estimate;
        return Dispatch.DISPATCHED;
    }

    @Override
    public synchronized boolean recordReference(UUID taskId, String reference) {
        Row row = rows.get(taskId);
        if (row == null || !row.status.equals("DISPATCHED") || row.reference != null) {
            return false;
        }
        row.reference = reference;
        return true;
    }

    @Override
    public synchronized boolean markPreempted(UUID taskId, UUID instance) {
        Row row = rows.get(taskId);
        if (row == null || !row.status.equals("DISPATCHED") || !row.instance.equals(instance) || row.preempted) {
            return false;
        }
        row.preempted = true;
        return true;
    }

    @Override
    public synchronized boolean settle(UUID taskId, UUID instance, String status, String code, boolean keepCharge,
            Instant now) {
        Row row = rows.get(taskId);
        if (row == null || !row.instance.equals(instance)) {
            return false;
        }
        if (status.equals("RECONCILING")) {
            if (!row.status.equals("DISPATCHED")) {
                return false;
            }
            row.status = status;
            row.code = code;
            return true;
        }
        if (!row.status.equals("QUEUED") && !row.status.equals("DISPATCHED")) {
            return false;
        }
        if (!(row.status.equals("DISPATCHED") && keepCharge)) {
            row.charged = 0;
        }
        row.status = status;
        row.code = code;
        return true;
    }

    @Override
    public synchronized int release(List<UUID> taskIds, UUID instance, Instant now) {
        int count = 0;
        for (UUID taskId : taskIds) {
            Row row = rows.get(taskId);
            if (row != null && row.status.equals("RECONCILING") && row.instance.equals(instance)) {
                row.status = "UNRESOLVED";
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized Recovery recover(UUID instance, Instant now) {
        int abandoned = 0;
        List<Uncertain> uncertain = new ArrayList<>();
        for (Map.Entry<UUID, Row> entry : rows.entrySet()) {
            Row row = entry.getValue();
            if (row.instance.equals(instance) || TERMINAL.contains(row.status)) {
                continue;
            }
            if (row.status.equals("QUEUED")) {
                row.status = "ABANDONED";
                row.code = "restart";
                abandoned++;
            } else {
                row.status = "RECONCILING";
                row.code = row.code == null ? "restart" : row.code;
                row.instance = instance;
                uncertain.add(new Uncertain(entry.getKey(), row.reference));
            }
        }
        return new Recovery(abandoned, uncertain);
    }

    @Override
    public synchronized long usageSince(Instant since, boolean backgroundOnly) {
        return rows.values().stream().filter(row -> row.charged > 0 && row.dispatchedAt.isAfter(since))
                .filter(row -> !backgroundOnly || row.workClass.background()).mapToLong(row -> row.charged).sum();
    }

    @Override
    public synchronized Suspension suspension() {
        return suspension;
    }

    @Override
    public synchronized void saveSuspension(Suspension suspension, Instant now) {
        this.suspension = suspension;
    }

    @Override
    public synchronized Optional<String> status(UUID taskId) {
        return Optional.ofNullable(rows.get(taskId)).map(row -> row.status);
    }
}
