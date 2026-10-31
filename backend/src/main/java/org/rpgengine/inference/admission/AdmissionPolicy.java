package org.rpgengine.inference.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.inference.scheduling.WorkClass;

/**
 * The pure admission policy of the GPU and RAM coordinator (docs/34 section 13): which waiting
 * request makes its model resident next, which requests share a resident model, and which leases
 * give way. It holds no clock, thread, database or port; {@link AdmissionCoordinator} calls it with
 * explicit times, and tests drive it directly.
 *
 * <p>Rules, in the order {@link #admitNext} applies them:
 * <ol>
 *   <li><b>Priority.</b> Interactive requests go before background ones. A background request gains
 *       one class per {@code agingStep} waited, never above {@link WorkClass#CONTINUITY}, so
 *       preparation work cannot starve speculative work, and background work never overtakes
 *       interactive work.</li>
 *   <li><b>Fair rotation.</b> Among requests of the same effective rank, the modality served least
 *       recently in that tier goes first, then the owner served least recently in that tier, then
 *       the oldest request. A flood of text requests therefore cannot hold back image or speech work,
 *       nor one campaign another's.</li>
 *   <li><b>Batching.</b> A request whose modality and model match a resident lease with room joins it
 *       at no extra capacity. When a request creates a lease, the other waiting requests for the same
 *       model join it at once, up to the modality's batch limit.</li>
 *   <li><b>Capacity.</b> A request that needs a new lease is admitted only when its demand fits what
 *       is left of the budget after every lease and every {@linkplain #hold hold}.</li>
 *   <li><b>No overtaking.</b> When the first request in order cannot be admitted, nothing behind it is
 *       admitted, not even by joining a lease, so the leases ahead of it drain and a large request is
 *       never starved by a stream of small ones.</li>
 * </ol>
 *
 * <p>Leases end by {@link #finish} (the last member leaves), {@link #expire} (not renewed in time) or
 * {@link #revoke}. {@link #preemptionVictims} names background-only leases to revoke so a blocked
 * interactive request can run; the caller revokes them by identity. Joining a lease does not extend
 * it: every member shares its expiry, and the holder renews it while it works.
 *
 * <p>Not thread-safe: the coordinator serializes every call.
 */
final class AdmissionPolicy {
    /** A request with its admission order. */
    private record Waiting(AdmissionRequest request, long sequence) {
    }

    /** What a lease is closing for a departing member. */
    record Departure(Lease lease, boolean closed) {
    }

    private enum Tier { INTERACTIVE, BACKGROUND }

    private record ServedModality(Tier tier, Modality modality) {
    }

    private record ServedOwner(Tier tier, UUID owner) {
    }

    /** Mutable lease state; only snapshots leave the policy. */
    private static final class Held {
        final UUID id = UUID.randomUUID();
        final Modality modality;
        final String model;
        final Demand demand;
        final Instant grantedAt;
        final LinkedHashMap<UUID, Waiting> members = new LinkedHashMap<>();
        Instant expiresAt;

        Held(Waiting first, Instant now, Duration ttl) {
            modality = first.request.modality();
            model = first.request.model();
            demand = first.request.demand();
            grantedAt = now;
            expiresAt = now.plus(ttl);
        }

        boolean accepts(AdmissionRequest request) {
            return modality == request.modality() && model.equals(request.model());
        }

        boolean backgroundOnly() {
            return members.values().stream().allMatch(member -> member.request.workClass().background());
        }

        Lease snapshot() {
            return new Lease(id, modality, model, demand, List.copyOf(members.keySet()), grantedAt, expiresAt);
        }
    }

    private final AdmissionSettings settings;
    private final LinkedHashMap<UUID, Waiting> waiting = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Held> leases = new LinkedHashMap<>();
    private final Map<UUID, UUID> leaseOfRequest = new HashMap<>();
    private final Map<UUID, Demand> holds = new LinkedHashMap<>();
    private final Set<UUID> preempting = new HashSet<>();
    private final Map<ServedModality, Long> lastModality = new HashMap<>();
    private final Map<ServedOwner, Long> lastOwner = new HashMap<>();
    private long served;
    private long sequence;

    /** @param settings budget, lease lifetime, aging step and batch limits */
    AdmissionPolicy(AdmissionSettings settings) {
        this.settings = settings;
    }

    /** @return the outcome; only {@link Submission#ACCEPTED} adds the request to the waiting set */
    Submission submit(AdmissionRequest request) {
        if (waiting.containsKey(request.id()) || leaseOfRequest.containsKey(request.id())) {
            return Submission.DUPLICATE;
        }
        if (!request.demand().fitsIn(settings.capacity())) {
            return Submission.EXCEEDS_CAPACITY;
        }
        waiting.put(request.id(), new Waiting(request, ++sequence));
        return Submission.ACCEPTED;
    }

    /** @return true when a waiting request was removed; a request that holds a lease is not waiting */
    boolean cancel(UUID requestId) {
        return waiting.remove(requestId) != null;
    }

    /** @return waiting requests in submission order */
    List<AdmissionRequest> waiting() {
        return waiting.values().stream().map(Waiting::request).toList();
    }

    /** @return snapshots of every live lease, oldest grant first */
    List<Lease> leases() {
        return leases.values().stream().map(Held::snapshot).toList();
    }

    /** @return the capacity not leased out and not held, never negative */
    Demand free() {
        Demand used = Demand.NONE;
        for (Held lease : leases.values()) {
            used = used.plus(lease.demand);
        }
        for (Demand hold : holds.values()) {
            used = used.plus(hold);
        }
        Demand capacity = settings.capacity();
        return new Demand(Math.max(0, capacity.gpuBytes() - used.gpuBytes()),
                Math.max(0, capacity.ramBytes() - used.ramBytes()));
    }

    /**
     * Reserve capacity for an ended lease whose model may still be resident: one of an earlier process,
     * or one of this process whose unload is not confirmed. The hold may push the total over the budget
     * when the budget was lowered since; nothing is admitted until it drains.
     *
     * @param leaseId identity of the ended lease
     * @param demand capacity it reserved
     */
    void hold(UUID leaseId, Demand demand) {
        holds.put(leaseId, demand);
    }

    /** @return true when a hold existed and its capacity is free again */
    boolean unhold(UUID leaseId) {
        return holds.remove(leaseId) != null;
    }

    /** @return true when capacity is held for {@code leaseId} */
    boolean holding(UUID leaseId) {
        return holds.containsKey(leaseId);
    }

    /** @return capacity currently held for leases whose model is not confirmed gone */
    Demand held() {
        Demand total = Demand.NONE;
        for (Demand hold : holds.values()) {
            total = total.plus(hold);
        }
        return total;
    }

    /**
     * Choose and place the next request.
     *
     * @param now current time, for background aging and lease expiry; call {@link #expire} first
     * @return the grant, or empty when nothing waits or the first request in order cannot be admitted
     */
    Optional<Grant> admitNext(Instant now) {
        List<Waiting> order = ordered(now);
        if (order.isEmpty()) {
            return Optional.empty();
        }
        Waiting head = order.getFirst();
        AdmissionRequest request = head.request;
        for (Held lease : leases.values()) {
            if (lease.accepts(request) && lease.members.size() < settings.maxBatch(lease.modality)
                    && lease.expiresAt.isAfter(now)) {
                place(lease, head);
                return Optional.of(new Grant(lease.snapshot(), List.of(request), false));
            }
        }
        if (!request.demand().fitsIn(free())) {
            return Optional.empty();
        }
        Held lease = new Held(head, now, settings.leaseTtl());
        leases.put(lease.id, lease);
        place(lease, head);
        List<AdmissionRequest> admitted = new ArrayList<>(List.of(request));
        for (Waiting other : order.subList(1, order.size())) {
            if (lease.members.size() >= settings.maxBatch(lease.modality)) {
                break;
            }
            if (lease.accepts(other.request)) {
                place(lease, other);
                admitted.add(other.request);
            }
        }
        return Optional.of(new Grant(lease.snapshot(), admitted, true));
    }

    /**
     * Take back an admission that could not be recorded: its requests wait again in their original
     * order, and a lease created by it disappears.
     */
    void undo(Grant grant) {
        Held lease = leases.get(grant.lease().id());
        if (lease == null) {
            return;
        }
        for (AdmissionRequest request : grant.admitted()) {
            Waiting back = lease.members.remove(request.id());
            leaseOfRequest.remove(request.id());
            if (back != null) {
                waiting.put(request.id(), back);
            }
        }
        if (lease.members.isEmpty()) {
            leases.remove(lease.id);
            preempting.remove(lease.id);
        }
    }

    /**
     * Remove every lease whose expiry has passed; their capacity is free again.
     *
     * @return snapshots of the expired leases with the members they had, oldest grant first
     */
    List<Lease> expire(Instant now) {
        List<Lease> expired = new ArrayList<>();
        for (Held lease : new ArrayList<>(leases.values())) {
            if (!lease.expiresAt.isAfter(now)) {
                expired.add(lease.snapshot());
                drop(lease);
            }
        }
        return expired;
    }

    /**
     * Extend a live lease to {@code now + leaseTtl}.
     *
     * @return the lease after renewal, or empty when it is unknown or already expired
     */
    Optional<Lease> renew(UUID leaseId, Instant now) {
        Held lease = leases.get(leaseId);
        if (lease == null || !lease.expiresAt.isAfter(now)) {
            return Optional.empty();
        }
        Instant renewed = now.plus(settings.leaseTtl());
        if (renewed.isAfter(lease.expiresAt)) {
            lease.expiresAt = renewed;
        }
        return Optional.of(lease.snapshot());
    }

    /** @return the lease after the member left (closed when it was the last), or empty for a non-member */
    Optional<Departure> finish(UUID requestId) {
        UUID leaseId = leaseOfRequest.remove(requestId);
        Held lease = leaseId == null ? null : leases.get(leaseId);
        if (lease == null) {
            return Optional.empty();
        }
        lease.members.remove(requestId);
        boolean closed = lease.members.isEmpty();
        Lease after = lease.snapshot();
        if (closed) {
            drop(lease);
        }
        return Optional.of(new Departure(after, closed));
    }

    /** @return the lease as it was, with its members, or empty when unknown; its capacity is free again */
    Optional<Lease> revoke(UUID leaseId) {
        Held lease = leases.get(leaseId);
        if (lease == null) {
            return Optional.empty();
        }
        Lease snapshot = lease.snapshot();
        drop(lease);
        return Optional.of(snapshot);
    }

    /** Record that the caller is taking {@code leaseId} back, so its capacity counts as coming free. */
    void markPreempting(UUID leaseId) {
        if (!leases.containsKey(leaseId)) {
            throw new IllegalStateException("lease is not live");
        }
        preempting.add(leaseId);
    }

    /**
     * Leases to revoke so the first request in order can run.
     *
     * <p>Only when that request is interactive and cannot be admitted, and only leases made entirely
     * of background members are named, the most recently granted first. Capacity of leases already
     * being taken back counts as free. Nothing is named when revoking every candidate would still not
     * make room, so work is never interrupted for nothing.
     *
     * @return lease identities to revoke, possibly empty
     */
    List<UUID> preemptionVictims(Instant now) {
        List<Waiting> order = ordered(now);
        if (order.isEmpty() || order.getFirst().request.workClass().background()) {
            return List.of();
        }
        AdmissionRequest head = order.getFirst().request;
        for (Held lease : leases.values()) {
            if (lease.accepts(head) && lease.members.size() < settings.maxBatch(lease.modality)
                    && lease.expiresAt.isAfter(now)) {
                return List.of();
            }
        }
        Demand available = free();
        for (UUID leaseId : preempting) {
            available = available.plus(leases.get(leaseId).demand);
        }
        if (head.demand().fitsIn(available)) {
            return List.of();
        }
        List<Held> candidates = new ArrayList<>(leases.values());
        List<UUID> victims = new ArrayList<>();
        for (int index = candidates.size() - 1; index >= 0; index--) {
            Held lease = candidates.get(index);
            if (lease.backgroundOnly() && !preempting.contains(lease.id)) {
                available = available.plus(lease.demand);
                victims.add(lease.id);
                if (head.demand().fitsIn(available)) {
                    return List.copyOf(victims);
                }
            }
        }
        return List.of();
    }

    /** Requests are ordered by effective rank, then rotation among modalities and owners, then age. */
    private List<Waiting> ordered(Instant now) {
        Comparator<Waiting> order = Comparator
                .<Waiting>comparingInt(item -> effectiveRank(item.request, now))
                .thenComparingLong(item -> lastModality.getOrDefault(
                        new ServedModality(tier(item.request), item.request.modality()), 0L))
                .thenComparingLong(item -> lastOwner.getOrDefault(
                        new ServedOwner(tier(item.request), item.request.owner()), 0L))
                .thenComparingLong(Waiting::sequence);
        return waiting.values().stream().sorted(order).toList();
    }

    /** Move a waiting request onto a lease and record the service for rotation. */
    private void place(Held lease, Waiting item) {
        waiting.remove(item.request.id());
        lease.members.put(item.request.id(), item);
        leaseOfRequest.put(item.request.id(), lease.id);
        Tier tier = tier(item.request);
        served++;
        lastModality.put(new ServedModality(tier, item.request.modality()), served);
        lastOwner.put(new ServedOwner(tier, item.request.owner()), served);
    }

    private void drop(Held lease) {
        leases.remove(lease.id);
        preempting.remove(lease.id);
        for (UUID member : lease.members.keySet()) {
            leaseOfRequest.remove(member);
        }
    }

    /** Interactive requests rank 0; a background request improves by one class per aging step. */
    private int effectiveRank(AdmissionRequest request, Instant now) {
        WorkClass workClass = request.workClass();
        if (!workClass.background()) {
            return workClass.rank();
        }
        long waited = Math.max(0, Duration.between(request.enqueuedAt(), now).toMillis());
        long steps = waited / settings.agingStep().toMillis();
        return (int) Math.max(WorkClass.CONTINUITY.rank(), workClass.rank() - Math.min(steps, WorkClass.values().length));
    }

    private static Tier tier(AdmissionRequest request) {
        return request.workClass().background() ? Tier.BACKGROUND : Tier.INTERACTIVE;
    }
}
