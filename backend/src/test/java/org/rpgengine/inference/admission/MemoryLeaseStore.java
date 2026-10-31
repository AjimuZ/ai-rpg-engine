package org.rpgengine.inference.admission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link LeaseStore} that follows the same ownership and lifecycle rules as the PostgreSQL
 * store, with switches to make writes fail, so coordinator tests run without a database.
 */
final class MemoryLeaseStore implements LeaseStore {
    final Map<UUID, LeaseRecord> rows = new LinkedHashMap<>();
    /** Grants that may still succeed before every later grant fails; negative means unlimited. */
    int grantsBeforeFailure = -1;
    boolean failSettle;

    @Override
    public void grant(UUID instance, Lease lease) {
        if (grantsBeforeFailure == 0) {
            throw new IllegalStateException("store unavailable");
        }
        if (grantsBeforeFailure > 0) {
            grantsBeforeFailure--;
        }
        rows.put(lease.id(), new LeaseRecord(lease.id(), instance, lease.modality(), lease.model(), lease.demand(),
                lease.members().size(), LeaseStatus.ACTIVE, lease.grantedAt(), lease.expiresAt(), null));
    }

    @Override
    public boolean resize(UUID leaseId, UUID instance, int size) {
        LeaseRecord row = rows.get(leaseId);
        if (row == null || row.status() != LeaseStatus.ACTIVE || !row.instance().equals(instance) || size < 1 || size > 64) {
            return false;
        }
        rows.put(leaseId, new LeaseRecord(row.id(), row.instance(), row.modality(), row.model(), row.demand(), size,
                row.status(), row.grantedAt(), row.expiresAt(), row.finishedAt()));
        return true;
    }

    @Override
    public boolean renew(UUID leaseId, UUID instance, Instant expiresAt, Instant now) {
        LeaseRecord row = rows.get(leaseId);
        if (row == null || row.status() != LeaseStatus.ACTIVE || !row.instance().equals(instance)
                || !row.expiresAt().isAfter(now)) {
            return false;
        }
        Instant later = expiresAt.isAfter(row.expiresAt()) ? expiresAt : row.expiresAt();
        rows.put(leaseId, new LeaseRecord(row.id(), row.instance(), row.modality(), row.model(), row.demand(),
                row.batchSize(), row.status(), row.grantedAt(), later, row.finishedAt()));
        return true;
    }

    @Override
    public boolean settle(UUID leaseId, UUID instance, LeaseStatus terminal, Instant now) {
        if (failSettle) {
            throw new IllegalStateException("store unavailable");
        }
        LeaseRecord row = rows.get(leaseId);
        if (row == null || row.status() != LeaseStatus.ACTIVE || !row.instance().equals(instance)) {
            return false;
        }
        rows.put(leaseId, new LeaseRecord(row.id(), row.instance(), row.modality(), row.model(), row.demand(),
                row.batchSize(), terminal, row.grantedAt(), row.expiresAt(), now));
        return true;
    }

    @Override
    public List<LeaseRecord> recover(UUID instance, Instant now) {
        List<LeaseRecord> taken = new ArrayList<>();
        for (LeaseRecord row : List.copyOf(rows.values())) {
            if (!row.status().terminal() && !row.instance().equals(instance)) {
                LeaseRecord moved = new LeaseRecord(row.id(), instance, row.modality(), row.model(), row.demand(),
                        row.batchSize(), LeaseStatus.RECONCILING, row.grantedAt(), row.expiresAt(), null);
                rows.put(row.id(), moved);
                taken.add(moved);
            }
        }
        taken.sort(Comparator.comparing(LeaseRecord::grantedAt));
        return taken;
    }

    @Override
    public boolean reconcile(UUID leaseId, UUID instance, Instant now) {
        if (failSettle) {
            throw new IllegalStateException("store unavailable");
        }
        LeaseRecord row = rows.get(leaseId);
        if (row == null || row.status() != LeaseStatus.ACTIVE || !row.instance().equals(instance)) {
            return false;
        }
        rows.put(leaseId, new LeaseRecord(row.id(), row.instance(), row.modality(), row.model(), row.demand(),
                row.batchSize(), LeaseStatus.RECONCILING, row.grantedAt(), row.expiresAt(), null));
        return true;
    }

    @Override
    public boolean confirmUnloaded(UUID leaseId, UUID instance, Instant now) {
        LeaseRecord row = rows.get(leaseId);
        if (row == null || row.status() != LeaseStatus.RECONCILING || !row.instance().equals(instance)) {
            return false;
        }
        rows.put(leaseId, new LeaseRecord(row.id(), row.instance(), row.modality(), row.model(), row.demand(),
                row.batchSize(), LeaseStatus.RELEASED, row.grantedAt(), row.expiresAt(), now));
        return true;
    }

    @Override
    public List<LeaseRecord> open() {
        return rows.values().stream().filter(row -> !row.status().terminal())
                .sorted(Comparator.comparing(LeaseRecord::grantedAt)).toList();
    }

    @Override
    public Optional<LeaseStatus> status(UUID leaseId) {
        return Optional.ofNullable(rows.get(leaseId)).map(LeaseRecord::status);
    }
}
