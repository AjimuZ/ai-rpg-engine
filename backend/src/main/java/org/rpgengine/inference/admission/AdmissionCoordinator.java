package org.rpgengine.inference.admission;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the {@link AdmissionPolicy} over a {@link LeaseStore} and a clock: the one place that decides
 * which local model may be resident. Every call is serialized, so the policy needs no locking.
 *
 * <p>A grant is written before it is returned: if the store refuses it, the requests keep waiting
 * and nothing was granted. Ending a lease is the opposite: the policy changes first, because a lease
 * that really ended must free its capacity even when the store is down. A failed write then leaves a
 * stale ACTIVE row, which the next process turns into a RECONCILING hold, so capacity is overstated
 * rather than lost.
 *
 * <p>The coordinator reserves capacity; it does not load or unload anything. Callers that load a
 * model for a created grant must renew its lease while they use it, and must unload the model
 * before they call {@link #revoke} or {@link #finish} for the last member. A lease that expires frees
 * its capacity on paper at once, whether or not the model is still resident.
 */
public final class AdmissionCoordinator {
    /**
     * Result of one {@link #poll}.
     *
     * @param expired leases that lapsed, with the members they had; those members lost their admission
     * @param grants new admissions, in service order; each is already recorded
     * @param unrecorded expired leases whose end could not be recorded in the store
     */
    public record Poll(List<Lease> expired, List<Grant> grants, int unrecorded) {
        /** Copy the lists. */
        public Poll {
            expired = List.copyOf(expired);
            grants = List.copyOf(grants);
        }
    }

    private final AdmissionPolicy policy;
    private final LeaseStore store;
    private final Clock clock;
    private final UUID instance = UUID.randomUUID();

    /**
     * @param settings budget, lease lifetime, aging step and batch limits
     * @param store durable lease record
     * @param clock time source; tests inject a fixed or stepped clock
     */
    public AdmissionCoordinator(AdmissionSettings settings, LeaseStore store, Clock clock) {
        policy = new AdmissionPolicy(Objects.requireNonNull(settings, "settings"));
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return this process's identity, the owner of every row it writes */
    public UUID instance() {
        return instance;
    }

    /** @return the outcome; an accepted request waits until {@link #poll} grants it */
    public synchronized Submission submit(AdmissionRequest request) {
        return policy.submit(Objects.requireNonNull(request, "request"));
    }

    /** @return true when a waiting request was withdrawn; a request that holds a lease is ended by {@link #finish} */
    public synchronized boolean cancel(UUID requestId) {
        return policy.cancel(requestId);
    }

    /**
     * Expire lapsed leases, then admit every request that can be admitted.
     *
     * <p>If the store refuses a grant, that grant is taken back and its requests keep waiting; the
     * grants recorded before it and the leases that expired in this poll are still returned, and the
     * failure is raised only when there is nothing else to report, so a caller never loses a recorded
     * grant or an expiry. The next poll retries.
     *
     * @throws RuntimeException the store's failure, when it was the only result of this poll
     */
    public synchronized Poll poll() {
        Instant now = clock.instant();
        List<Lease> expired = policy.expire(now);
        int unrecorded = 0;
        for (Lease lease : expired) {
            if (!settle(lease.id(), LeaseStatus.EXPIRED, now)) {
                unrecorded++;
            }
        }
        List<Grant> grants = new ArrayList<>();
        for (Optional<Grant> next = policy.admitNext(now); next.isPresent(); next = policy.admitNext(now)) {
            Grant grant = next.get();
            try {
                record(grant);
            } catch (RuntimeException failure) {
                policy.undo(grant);
                if (grants.isEmpty() && expired.isEmpty()) {
                    throw failure;
                }
                break;
            }
            grants.add(grant);
        }
        return new Poll(expired, grants, unrecorded);
    }

    /**
     * A member finished with its lease; the last member ends the lease and frees its capacity.
     *
     * @return true when the request held a lease
     */
    public synchronized boolean finish(UUID requestId) {
        Optional<AdmissionPolicy.Departure> departure = policy.finish(requestId);
        if (departure.isEmpty()) {
            return false;
        }
        Lease lease = departure.get().lease();
        if (departure.get().closed()) {
            store.settle(lease.id(), instance, LeaseStatus.RELEASED, clock.instant());
        } else {
            store.resize(lease.id(), instance, lease.members().size());
        }
        return true;
    }

    /**
     * Extend a lease by one lifetime from now.
     *
     * @return true when the lease was live and the store recorded the new expiry; false means the
     *         holder must treat its admission as lost
     */
    public synchronized boolean renew(UUID leaseId) {
        Instant now = clock.instant();
        Optional<Lease> renewed = policy.renew(leaseId, now);
        return renewed.isPresent() && store.renew(leaseId, instance, renewed.get().expiresAt(), now);
    }

    /**
     * Name the leases to take back so the next interactive request can run, and count their capacity
     * as coming free. The caller interrupts and unloads each one, then calls {@link #revoke}.
     *
     * @return lease identities, possibly empty; already-named leases are not named again
     */
    public synchronized List<UUID> preemptionVictims() {
        List<UUID> victims = policy.preemptionVictims(clock.instant());
        victims.forEach(policy::markPreempting);
        return victims;
    }

    /**
     * End a lease for good and free its capacity. Call it after the model has been unloaded.
     *
     * @return the lease with the members that lost their admission, or empty when it was not live
     */
    public synchronized Optional<Lease> revoke(UUID leaseId) {
        Optional<Lease> revoked = policy.revoke(leaseId);
        revoked.ifPresent(lease -> store.settle(lease.id(), instance, LeaseStatus.REVOKED, clock.instant()));
        return revoked;
    }

    /**
     * Take over the leases an earlier process left open and keep their capacity reserved. Call it once
     * at start, before the first {@link #poll}; a model such a lease names may still be resident.
     *
     * @return the taken-over leases, which the caller should try to unload and then confirm
     */
    public synchronized List<LeaseRecord> recover() {
        List<LeaseRecord> taken = store.recover(instance, clock.instant());
        taken.forEach(record -> policy.hold(record.id(), record.demand()));
        return taken;
    }

    /**
     * Record that the model of a recovered lease is gone and free its capacity.
     *
     * @return true when the lease was held by this process and the store recorded its release
     */
    public synchronized boolean confirmUnloaded(UUID leaseId) {
        if (!store.confirmUnloaded(leaseId, instance, clock.instant())) {
            return false;
        }
        policy.unhold(leaseId);
        return true;
    }

    /** @return capacity neither leased nor held */
    public synchronized Demand free() {
        return policy.free();
    }

    /** @return capacity held for unconfirmed leases of earlier processes */
    public synchronized Demand held() {
        return policy.held();
    }

    /** @return waiting requests in submission order */
    public synchronized List<AdmissionRequest> waiting() {
        return policy.waiting();
    }

    /** @return live leases, oldest grant first */
    public synchronized List<Lease> leases() {
        return policy.leases();
    }

    private void record(Grant grant) {
        if (grant.created()) {
            store.grant(instance, grant.lease());
        } else if (!store.resize(grant.lease().id(), instance, grant.lease().members().size())) {
            throw new IllegalStateException("lease is not active in the store");
        }
    }

    /** @return false when the store could not record the end */
    private boolean settle(UUID leaseId, LeaseStatus status, Instant now) {
        try {
            return store.settle(leaseId, instance, status, now);
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
