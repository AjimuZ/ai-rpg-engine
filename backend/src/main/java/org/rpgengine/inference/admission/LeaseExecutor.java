package org.rpgengine.inference.admission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.rpgengine.inference.ModelControlException;
import org.rpgengine.inference.ModelServerControl;
import org.rpgengine.inference.ModelServerControl.Residency;
import org.rpgengine.inference.admission.AdmissionMetrics.Counter;

/**
 * Turns the {@link AdmissionCoordinator}'s leases into real residency (docs/34 section 10): it saves the
 * text slot's cache, unloads the text model, confirms the memory came free, runs a batch, unloads the
 * batch's model, reloads the text model and restores the cache.
 *
 * <p>Invariant: every model in memory is either covered by a live lease or a hold, or is an unleased
 * resident text model ("warm"), which is evicted before a requested model that would not fit beside it
 * loads. A reload nobody requested never evicts: it waits, and two text models that do not fit together
 * do not swap on every pass. A warm text model counts for nothing in the coordinator's accounting, so the
 * turn after a finished text lease needs no swap; the executor counts it itself before every load.
 *
 * <p>Failure rules:
 * <ul>
 *   <li>A failed save costs the cache only: the swap goes on and the text model comes back cold.</li>
 *   <li>A failed or unconfirmed text unload means no other model loads; the text model stays usable and
 *       the batch's requests ask again later.</li>
 *   <li>A failed or unconfirmed unload of a leased model keeps that lease RECONCILING with its capacity
 *       held, and {@link #pass} retries until it is gone. An expired lease takes the same path, so
 *       expired capacity is reused only after an unload.</li>
 *   <li>A failed reload does not fail the batch that already ran; {@link #pass} retries it, and a text
 *       request that needs the model tries again itself.</li>
 *   <li>A failed restore leaves the text model loaded and cold, which costs time only, because the
 *       adapter resends its conversation.</li>
 * </ul>
 *
 * <p>Unowned contention: before a model loads, the free GPU reading must cover its demand plus the
 * headroom, counting what an eviction would free. A low reading delays the requests and, after
 * {@link LeaseExecutorSettings#patience()}, ends them as {@link Status#FELL_BACK}, so the caller uses
 * catalog art or text-only play. An unknown reading refuses a batch but not a text load, because text
 * is what keeps the game playable when the reading source breaks. Nothing here can stop another process.
 *
 * <p>This class never queues text: text requests reach it through the coordinator while their task
 * holds the scheduler's permit. All decisions run under this object's monitor, including the calls to the
 * server, because a reload must not race an admission; job steps run outside it, on the caller's
 * thread. {@link #pass} is the only caller of {@link AdmissionCoordinator#poll}, so the grants a pass
 * returns are the only way to get a lease.
 */
public final class LeaseExecutor {
    /** How a request for residency ended. */
    public enum Status {
        /** A text model is resident for the lease, which stays live for its holder. */
        READY,
        /** Every step ran and the lease is released. */
        COMPLETED,
        /** The job yielded between steps to interactive demand and the lease is revoked; submit again to retry. */
        INTERRUPTED,
        /** The job used up its time cap and the lease is revoked; submit again to retry. */
        TIME_CAPPED,
        /** The job yielded because free GPU memory fell under the floor and the lease is revoked. */
        CONTENTION_YIELDED,
        /** The lease expired or could not be renewed, so its members lost their admission. */
        LOST,
        /** The step threw; the lease is released. */
        JOB_FAILED,
        /** Interactive demand is queued or expected, so nothing started; the executor asks again itself. */
        DEFERRED,
        /** Too little free GPU memory, so the model did not load; the executor asks again itself. */
        DELAYED,
        /** Contention outlasted the patience; the requests are given up and the caller falls back. */
        FELL_BACK,
        /** The text model could not be removed, so nothing else loaded; the executor asks again itself. */
        SWAP_FAILED,
        /** The model would not load, or nothing can load it; the requests are given up. */
        LOAD_FAILED
    }

    /** Where the text model stands after a batch. */
    public enum TextState {
        /** No text model was out of memory. */
        UNTOUCHED,
        /** Reloaded with its prompt cache. */
        RESTORED,
        /** Reloaded without its prompt cache. */
        COLD,
        /** Not in memory yet: a reload failed, or something else holds or needs the room. {@link #pass} retries it. */
        NOT_RESIDENT
    }

    /**
     * Result of one residency request.
     *
     * @param status how it ended
     * @param steps job steps that ran
     * @param text where the text model stands
     * @param reconciling true when the lease's model is not confirmed gone and its capacity stays held
     * @param members requests the lease held when it ended
     */
    public record Outcome(Status status, int steps, TextState text, boolean reconciling, List<UUID> members) {
        /** Copy the member list. */
        public Outcome {
            members = List.copyOf(members);
        }
    }

    /**
     * Result of one {@link #pass}.
     *
     * @param expired leases that lapsed; their members lost their admission
     * @param grants new admissions, which the caller serves with {@link #runBatch} or {@link #prepareText}
     */
    public record Pass(List<Lease> expired, List<Grant> grants) {
        /** Copy the lists. */
        public Pass {
            expired = List.copyOf(expired);
            grants = List.copyOf(grants);
        }
    }

    /** Waits between two status checks; tests inject one that moves their clock. */
    @FunctionalInterface
    public interface Pause {
        /** @throws InterruptedException when the waiting thread is interrupted */
        void pause(Duration duration) throws InterruptedException;
    }

    /** @param sized false for a model found on the server at start, whose demand is taken as the whole budget */
    private record Warm(String model, Demand demand, boolean sized) {
    }

    private record Evicted(String model, Demand demand, boolean sized, boolean cached) {
    }

    private record Parked(Modality modality, String model) {
    }

    /** A request put back. Time spent deferred to interactive demand does not count against the patience. */
    private static final class Delay {
        final AdmissionRequest request;
        Instant contendedSince;
        Instant retryAt;

        Delay(AdmissionRequest request) {
            this.request = request;
        }
    }

    private static final class Run {
        final Lease lease;
        final Instant deadline;
        Instant renewAt;
        boolean lost;

        Run(Lease lease, Instant deadline, Instant renewAt) {
            this.lease = lease;
            this.deadline = deadline;
            this.renewAt = renewAt;
        }
    }

    private final AdmissionCoordinator coordinator;
    private final Map<Modality, ModelServerControl> controls;
    private final GpuMemoryReading reading;
    private final BooleanSupplier expectedDemand;
    private final LeaseExecutorSettings settings;
    private final Clock clock;
    private final Pause pause;
    private final AdmissionMetrics metrics = new AdmissionMetrics();
    private final Map<String, Warm> warm = new LinkedHashMap<>();
    private final Map<String, Evicted> evicted = new LinkedHashMap<>();
    private final Map<UUID, Parked> parked = new LinkedHashMap<>();
    private final Map<UUID, Run> running = new HashMap<>();
    private final Map<UUID, Delay> delays = new LinkedHashMap<>();
    private final Map<UUID, AdmissionRequest> known = new HashMap<>();
    private final Set<UUID> yielding = new HashSet<>();
    private Instant settleAt = Instant.MIN;
    private Instant reloadAt = Instant.MIN;
    private boolean synced;

    /**
     * @param coordinator the leases; it must hold expired capacity until an unload is confirmed
     * @param controls what holds the models of each modality; a modality with no entry cannot load
     * @param reading free GPU memory
     * @param expectedDemand true while a text request is expected soon, such as within the reading window
     * @param settings behaviour around the server
     * @param clock the coordinator's time source
     * @param pause waits between status checks
     * @throws IllegalArgumentException when the coordinator frees expired capacity at once
     */
    public LeaseExecutor(AdmissionCoordinator coordinator, Map<Modality, ModelServerControl> controls,
            GpuMemoryReading reading, BooleanSupplier expectedDemand, LeaseExecutorSettings settings, Clock clock,
            Pause pause) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        if (coordinator.expiry() != AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED) {
            throw new IllegalArgumentException("real residency needs expired capacity held until unloaded");
        }
        this.controls = Map.copyOf(controls);
        this.reading = Objects.requireNonNull(reading, "reading");
        this.expectedDemand = Objects.requireNonNull(expectedDemand, "expectedDemand");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * Take over what an earlier process left: its open leases stay held until their models are gone, and
     * a resident text model nobody leased counts as warm, with the whole budget as its unknown demand.
     * Once evicted, such a model comes back only for a text request, which brings its real demand.
     * Call it once, before the first {@link #pass}.
     */
    public synchronized void start() {
        for (LeaseRecord record : coordinator.recover()) {
            parked.put(record.id(), new Parked(record.modality(), record.model()));
        }
        sync();
        settle();
    }

    /**
     * One round of housekeeping and admission: retry unloads, ask again for requests that were put back,
     * name leases to take back, admit, and bring the text model back when nothing else needs the room.
     *
     * @return what expired and what was granted
     * @throws RuntimeException the store's failure, when it was the only result of the round
     */
    public synchronized Pass pass() {
        Instant now = clock.instant();
        sync();
        settle();
        for (Delay delay : delays.values()) {
            if (delay.retryAt != null && !now.isBefore(delay.retryAt)) {
                delay.retryAt = null;
                coordinator.submit(delay.request);
            }
        }
        yielding.addAll(coordinator.preemptionVictims());
        AdmissionCoordinator.Poll poll = coordinator.poll();
        for (Lease lease : poll.expired()) {
            metrics.count(Counter.LEASES_EXPIRED);
            Run run = running.get(lease.id());
            if (run != null) {
                run.lost = true;
            } else {
                parked.put(lease.id(), new Parked(lease.modality(), lease.model()));
                settleAt = now;
                forget(lease.members());
            }
        }
        yielding.retainAll(coordinator.leases().stream().map(Lease::id).toList());
        for (Grant grant : poll.grants()) {
            metrics.count(Counter.GRANTS);
            for (AdmissionRequest request : grant.admitted()) {
                known.put(request.id(), request);
                metrics.waited(Duration.between(request.enqueuedAt(), now).toMillis());
            }
        }
        settle();
        restoreText();
        return new Pass(poll.expired(), poll.grants());
    }

    /**
     * Make the text model of a grant resident, evicting only what does not fit beside it. On
     * {@link Status#READY} the lease stays live: the holder renews it through the coordinator and ends it
     * with {@link #finishText} or {@link #yieldText}. On any other status the lease is over.
     *
     * @param grant a grant of a text lease, created or joined
     * @return the outcome
     * @throws IllegalArgumentException for a lease of another modality
     */
    public synchronized Outcome prepareText(Grant grant) {
        Lease lease = grant.lease();
        if (lease.modality() != Modality.TEXT) {
            throw new IllegalArgumentException("not a text lease");
        }
        ModelServerControl control = controls.get(Modality.TEXT);
        if (control == null) {
            return giveUp(lease);
        }
        if (!holdsLease(lease.id())) {
            return lost(lease);
        }
        try {
            if (control.status(lease.model()) == Residency.LOADED) {
                warm.put(lease.model(), new Warm(lease.model(), lease.demand(), true));
                evicted.remove(lease.model());
                return ready(lease, TextState.UNTOUCHED);
            }
        } catch (ModelControlException failure) {
            metrics.count(Counter.LOAD_FAILURES);
            return endFailedLoad(lease, control);
        }
        sync();
        List<Warm> evictions = evictionsFor(lease);
        if (!memoryFits(lease.demand(), reclaimable(evictions), false)) {
            return giveBack(lease, Status.DELAYED, Counter.CONTENTION_DELAYS, true);
        }
        if (!evict(evictions)) {
            return giveBack(lease, Status.SWAP_FAILED, Counter.SWAP_FAILURES, true);
        }
        try {
            return ready(lease, bringBack(control, lease.model(), lease.demand()));
        } catch (ModelControlException failure) {
            metrics.count(Counter.LOAD_FAILURES);
            return endFailedLoad(lease, control);
        }
    }

    /**
     * A text request is done. The model stays warm for the next turn.
     *
     * @return true when the request held a lease
     */
    public synchronized boolean finishText(UUID requestId) {
        known.remove(requestId);
        try {
            return coordinator.finish(requestId);
        } catch (RuntimeException failure) {
            metrics.count(Counter.STORE_FAILURES);
            return true;
        }
    }

    /**
     * @param leaseId a live lease
     * @return true when the coordinator named this lease for more urgent work and its holder must stop
     */
    public synchronized boolean shouldYield(UUID leaseId) {
        yielding.addAll(coordinator.preemptionVictims());
        return yielding.contains(leaseId);
    }

    /**
     * A text holder stopped for more urgent work. Its model stays warm, so it is safe to free the capacity
     * without an unload: the invariant evicts it before anything that would not fit loads.
     *
     * @return the members that lost their admission, empty when the lease was not live
     */
    public synchronized List<UUID> yieldText(UUID leaseId) {
        yielding.remove(leaseId);
        Optional<Lease> revoked = coordinator.leases().stream().filter(lease -> lease.id().equals(leaseId)).findFirst();
        revoked.ifPresent(lease -> {
            metrics.count(Counter.INTERRUPTIONS);
            forget(lease.members());
            revoke(lease.id());
        });
        return revoked.map(Lease::members).orElse(List.of());
    }

    /**
     * Run a batch for a created lease of a non-text model, on the caller's thread. Requests that join the
     * lease during the run are the job's to absorb, through the grants of a later {@link #pass}.
     *
     * @param grant the grant that created the lease
     * @param job the work, called one step at a time
     * @return the outcome; the lease is over in every case
     * @throws IllegalArgumentException for a text lease or a grant that joined an existing lease
     */
    public Outcome runBatch(Grant grant, SteppedJob job) {
        Lease lease = grant.lease();
        if (lease.modality() == Modality.TEXT || !grant.created()) {
            throw new IllegalArgumentException("a batch needs a created lease of a non-text model");
        }
        Run run;
        synchronized (this) {
            Outcome refused = startBatch(lease);
            if (refused != null) {
                return refused;
            }
            Instant now = clock.instant();
            run = new Run(lease, now.plus(settings.timeCap()), renewalAfter(lease, now));
            running.put(lease.id(), run);
            lease.members().forEach(delays::remove);
        }
        int steps = 0;
        Status status = null;
        while (status == null) {
            status = checkpoint(run);
            if (status != null) {
                break;
            }
            try {
                boolean more = job.step();
                steps++;
                metrics.count(Counter.JOB_STEPS);
                if (!more) {
                    status = Status.COMPLETED;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                status = Status.JOB_FAILED;
            } catch (Exception failure) {
                status = Status.JOB_FAILED;
            }
        }
        return endBatch(run, status, steps);
    }

    /**
     * Withdraw a request that waits, in the coordinator's queue or put back here to ask again, so it is
     * never granted again and stops counting as delayed. Use this rather than
     * {@link AdmissionCoordinator#cancel}, which cannot see a request put back here and leaves its delay
     * behind.
     *
     * <p>A request that holds a lease keeps it until its holder ends it, because its model may be in
     * use. It is forgotten here all the same, so it is not put back if that lease gives way.
     *
     * @return true when the request was withdrawn; false when it holds a lease or is unknown
     */
    public synchronized boolean cancel(UUID requestId) {
        Delay delay = delays.remove(requestId);
        known.remove(requestId);
        boolean queued = coordinator.cancel(requestId);
        return queued || delay != null && delay.retryAt != null;
    }

    /**
     * Counters and gauges under fixed names, numbers only: no key, endpoint, model name, prompt or output
     * can appear here. This in-process snapshot is the only exposure; no route or actuator endpoint reads it.
     * It waits while a swap holds the monitor.
     *
     * @return every metric by name, sorted
     */
    public synchronized Map<String, Long> metrics() {
        Map<String, Long> values = new TreeMap<>(metrics.snapshot());
        Demand free = coordinator.free();
        values.put("capacity_free_gpu_bytes", free.gpuBytes());
        values.put("capacity_free_ram_bytes", free.ramBytes());
        values.put("capacity_held_gpu_bytes", coordinator.held().gpuBytes());
        values.put("jobs_running", (long) running.size());
        values.put("leases_active", (long) coordinator.leases().size());
        values.put("leases_reconciling", (long) parked.size());
        values.put("requests_delayed", (long) delays.size());
        values.put("requests_waiting", (long) coordinator.waiting().size());
        values.put("text_models_resident", (long) warm.size());
        values.put("text_models_swapped_out", (long) evicted.size());
        return values;
    }

    /** Stage one of a batch. @return the outcome when the batch must not run, or null with its model resident */
    private Outcome startBatch(Lease lease) {
        ModelServerControl control = controls.get(lease.modality());
        if (control == null) {
            return giveUp(lease);
        }
        if (!holdsLease(lease.id())) {
            return lost(lease);
        }
        if (yielding.contains(lease.id()) || coordinator.interactiveTextWaiting() || expectedDemand.getAsBoolean()) {
            return giveBack(lease, Status.DEFERRED, Counter.DEFERRALS, false);
        }
        sync();
        if (!synced) {
            return giveBack(lease, Status.SWAP_FAILED, Counter.SWAP_FAILURES, true);
        }
        List<Warm> evictions = evictionsFor(lease);
        if (!memoryFits(lease.demand(), reclaimable(evictions), true)) {
            return giveBack(lease, Status.DELAYED, Counter.CONTENTION_DELAYS, true);
        }
        if (!evict(evictions)) {
            return giveBack(lease, Status.SWAP_FAILED, Counter.SWAP_FAILURES, true);
        }
        if (!evictions.isEmpty() && !memoryConfirmed(lease.demand())) {
            return giveBack(lease, Status.DELAYED, Counter.CONTENTION_DELAYS, true);
        }
        try {
            awaitLoaded(control, lease.model());
            return null;
        } catch (ModelControlException failure) {
            metrics.count(Counter.LOAD_FAILURES);
            return endFailedLoad(lease, control);
        }
    }

    /** The checks between two steps. @return why the job must stop, or null to go on */
    private synchronized Status checkpoint(Run run) {
        if (run.lost) {
            return Status.LOST;
        }
        Instant now = clock.instant();
        if (!now.isBefore(run.deadline)) {
            return Status.TIME_CAPPED;
        }
        yielding.addAll(coordinator.preemptionVictims());
        if (yielding.contains(run.lease.id())) {
            metrics.count(Counter.INTERRUPTIONS);
            return Status.INTERRUPTED;
        }
        if (!now.isBefore(run.renewAt)) {
            if (!holdsLease(run.lease.id())) {
                return Status.LOST;
            }
            run.renewAt = renewalAfter(run.lease, now);
        }
        if (settings.runningFloorBytes() > 0 && run.lease.demand().gpuBytes() > 0) {
            OptionalLong free = freeGpu();
            if (free.isPresent() && free.getAsLong() < settings.runningFloorBytes()) {
                metrics.count(Counter.CONTENTION_YIELDS);
                return Status.CONTENTION_YIELDED;
            }
        }
        return null;
    }

    /** Stage three of a batch: the model leaves memory before its capacity can be reused. */
    private synchronized Outcome endBatch(Run run, Status status, int steps) {
        Lease lease = run.lease;
        running.remove(lease.id());
        yielding.remove(lease.id());
        List<UUID> members = members(lease);
        boolean gone = unloaded(controls.get(lease.modality()), lease.model());
        boolean reconciling = false;
        if (run.lost || !gone) {
            reconciling = !gone || !confirm(lease.id());
            if (reconciling) {
                coordinator.reconcile(lease.id());
                parked.put(lease.id(), new Parked(lease.modality(), lease.model()));
                settleAt = clock.instant().plus(settings.recheck());
            }
        } else if (status == Status.COMPLETED || status == Status.JOB_FAILED) {
            members.forEach(this::finish);
        } else {
            revoke(lease.id());
        }
        forget(members);
        return new Outcome(status, steps, restoreText(), reconciling, members);
    }

    /** A text lease is served: its members are no longer delayed, though the executor still knows them. */
    private Outcome ready(Lease lease, TextState state) {
        List<UUID> members = members(lease);
        members.forEach(delays::remove);
        return new Outcome(Status.READY, 0, state, false, members);
    }

    /** The members' requests are over, so nothing may ask again for them or count them as delayed. */
    private void forget(List<UUID> members) {
        members.forEach(member -> {
            delays.remove(member);
            known.remove(member);
        });
    }

    /** End a lease whose model would not load; it may be half in memory, so it must be confirmed gone first. */
    private Outcome endFailedLoad(Lease lease, ModelServerControl control) {
        List<UUID> members = members(lease);
        boolean gone = unloaded(control, lease.model());
        if (gone) {
            revoke(lease.id());
            warm.remove(lease.model());
        } else {
            coordinator.reconcile(lease.id());
            parked.put(lease.id(), new Parked(lease.modality(), lease.model()));
            settleAt = clock.instant().plus(settings.recheck());
        }
        forget(members);
        return new Outcome(Status.LOAD_FAILED, 0, restoreText(), !gone, members);
    }

    /** End a lease that nothing can serve; no model was loaded. */
    private Outcome giveUp(Lease lease) {
        List<UUID> members = members(lease);
        revoke(lease.id());
        forget(members);
        metrics.count(Counter.LOAD_FAILURES);
        return new Outcome(Status.LOAD_FAILED, 0, restoreText(), false, members);
    }

    /** End a lease that is no longer held; no model was loaded for it here. */
    private Outcome lost(Lease lease) {
        List<UUID> members = members(lease);
        revoke(lease.id());
        forget(members);
        return new Outcome(Status.LOST, 0, restoreText(), false, members);
    }

    /**
     * End a lease whose model never loaded and put its requests back to ask again after the recheck pause,
     * keeping their age. A request delayed past the patience ends the whole lease as a fallback.
     *
     * @param bounded true when the patience applies
     */
    private Outcome giveBack(Lease lease, Status status, Counter counter, boolean bounded) {
        Instant now = clock.instant();
        List<UUID> members = members(lease);
        revoke(lease.id());
        yielding.remove(lease.id());
        boolean exhausted = bounded && members.stream().map(delays::get).anyMatch(delay -> delay != null
                && delay.contendedSince != null && !delay.contendedSince.plus(settings.patience()).isAfter(now));
        if (exhausted) {
            metrics.count(Counter.CONTENTION_FALLBACKS);
            forget(members);
            return new Outcome(Status.FELL_BACK, 0, restoreText(), false, members);
        }
        metrics.count(counter);
        for (UUID member : members) {
            AdmissionRequest request = known.get(member);
            if (request != null) {
                Delay delay = delays.computeIfAbsent(member, id -> new Delay(request));
                if (bounded && delay.contendedSince == null) {
                    delay.contendedSince = now;
                }
                delay.retryAt = now.plus(settings.recheck());
            }
        }
        return new Outcome(status, 0, restoreText(), false, members);
    }

    /** @return the members of a lease now, or those of its snapshot once it ended */
    private List<UUID> members(Lease lease) {
        return coordinator.leases().stream().filter(live -> live.id().equals(lease.id())).findFirst().map(Lease::members)
                .orElse(lease.members());
    }

    private boolean holdsLease(UUID leaseId) {
        try {
            return coordinator.renew(leaseId);
        } catch (RuntimeException failure) {
            // The accounting renewed it; only the record is behind, and a restart holds such a row anyway.
            metrics.count(Counter.STORE_FAILURES);
            return true;
        }
    }

    private Instant renewalAfter(Lease lease, Instant now) {
        return now.plus(Duration.between(lease.grantedAt(), lease.expiresAt()).dividedBy(2));
    }

    private void revoke(UUID leaseId) {
        try {
            coordinator.revoke(leaseId);
        } catch (RuntimeException failure) {
            metrics.count(Counter.STORE_FAILURES);
        }
    }

    private void finish(UUID requestId) {
        try {
            coordinator.finish(requestId);
        } catch (RuntimeException failure) {
            metrics.count(Counter.STORE_FAILURES);
        }
    }

    /** @return true when the store recorded the release */
    private boolean confirm(UUID leaseId) {
        try {
            return coordinator.confirmUnloaded(leaseId);
        } catch (RuntimeException failure) {
            metrics.count(Counter.STORE_FAILURES);
            return false;
        }
    }

    /** Learn once what the text server already holds; until it answers, no batch starts. */
    private void sync() {
        ModelServerControl control = controls.get(Modality.TEXT);
        if (control == null) {
            synced = true;
        }
        if (synced) {
            return;
        }
        try {
            for (String model : control.resident()) {
                warm.putIfAbsent(model, new Warm(model, coordinator.capacity(), false));
            }
            synced = true;
        } catch (ModelControlException failure) {
            metrics.count(Counter.SWAP_FAILURES);
        }
    }

    /** Retry the unloads of parked leases, except a model a live lease is using again. */
    private void settle() {
        Instant now = clock.instant();
        if (parked.isEmpty() || now.isBefore(settleAt)) {
            return;
        }
        boolean failed = false;
        for (Map.Entry<UUID, Parked> entry : new ArrayList<>(parked.entrySet())) {
            Parked lease = entry.getValue();
            ModelServerControl control = controls.get(lease.modality());
            if (control == null || leased(lease.modality(), lease.model())) {
                continue;
            }
            if (unloaded(control, lease.model()) && confirm(entry.getKey())) {
                parked.remove(entry.getKey());
                if (lease.modality() == Modality.TEXT) {
                    warm.remove(lease.model());
                }
            } else {
                failed = true;
            }
        }
        if (failed) {
            settleAt = now.plus(settings.recheck());
        }
    }

    private boolean leased(Modality modality, String model) {
        return coordinator.leases().stream().anyMatch(lease -> lease.modality() == modality && lease.model().equals(model));
    }

    /** @return the warm text models that do not fit beside {@code lease}, which is already on paper */
    private List<Warm> evictionsFor(Lease lease) {
        Demand room = coordinator.free();
        List<Warm> evictions = new ArrayList<>();
        for (Warm resident : warm.values()) {
            if (leased(Modality.TEXT, resident.model())) {
                continue;
            }
            if (resident.demand().fitsIn(room)) {
                room = room.minus(resident.demand());
            } else {
                evictions.add(resident);
            }
        }
        return evictions;
    }

    private static long reclaimable(List<Warm> evictions) {
        return evictions.stream().mapToLong(resident -> resident.demand().gpuBytes()).sum();
    }

    /** @return true when every model left memory; the rest stay warm and usable */
    private boolean evict(List<Warm> evictions) {
        ModelServerControl control = controls.get(Modality.TEXT);
        for (Warm resident : evictions) {
            boolean cached = saveCache(control, resident.model());
            if (!unloaded(control, resident.model())) {
                return false;
            }
            warm.remove(resident.model());
            evicted.put(resident.model(), new Evicted(resident.model(), resident.demand(), resident.sized(), cached));
            metrics.count(Counter.SWAPS_OUT);
        }
        return true;
    }

    private boolean saveCache(ModelServerControl control, String model) {
        try {
            control.saveSlot(model, settings.slot(), cacheFile(model));
            metrics.count(Counter.SLOT_SAVES);
            return true;
        } catch (ModelControlException failure) {
            metrics.count(Counter.SLOT_SAVE_FAILURES);
            return false;
        }
    }

    /** @return true when {@code demand} fits on paper beside every lease, every hold and every warm model */
    private boolean fitsBesideWarm(Demand demand) {
        Demand room = coordinator.free();
        for (Warm resident : warm.values()) {
            if (leased(Modality.TEXT, resident.model())) {
                continue;
            }
            if (!resident.demand().fitsIn(room)) {
                return false;
            }
            room = room.minus(resident.demand());
        }
        return demand.fitsIn(room);
    }

    /**
     * Bring swapped-out text of known size back when nothing else waits for the room, it fits on paper
     * beside everything resident and free memory allows it. The paper check decides alone when the reading
     * is unknown or the card is larger than the budget. This never evicts, because no request is behind it;
     * a model left out waits for room or for its own text request. The model evicted last comes first,
     * being the one last in use. A failed attempt is retried after the recheck pause.
     *
     * @return the worst state of any text model that was out
     */
    private TextState restoreText() {
        ModelServerControl control = controls.get(Modality.TEXT);
        if (evicted.isEmpty()) {
            return TextState.UNTOUCHED;
        }
        TextState worst = TextState.UNTOUCHED;
        Instant now = clock.instant();
        for (Evicted out : new ArrayList<>(evicted.values()).reversed()) {
            TextState state = TextState.NOT_RESIDENT;
            boolean wanted = coordinator.waiting().stream().allMatch(request -> request.modality() == Modality.TEXT
                    && request.model().equals(out.model()));
            if (control != null && out.sized() && wanted && !now.isBefore(reloadAt)
                    && fitsBesideWarm(out.demand()) && memoryFits(out.demand(), 0, false)) {
                try {
                    state = bringBack(control, out.model(), out.demand());
                } catch (ModelControlException failure) {
                    metrics.count(Counter.LOAD_FAILURES);
                    reloadAt = clock.instant().plus(settings.recheck());
                }
            }
            worst = state.compareTo(worst) > 0 ? state : worst;
        }
        return worst;
    }

    /** Load a text model and restore its cache when it was saved; a failed restore leaves it cold. */
    private TextState bringBack(ModelServerControl control, String model, Demand demand) throws ModelControlException {
        awaitLoaded(control, model);
        Evicted out = evicted.remove(model);
        warm.put(model, new Warm(model, demand, true));
        if (out == null) {
            return TextState.COLD;
        }
        metrics.count(Counter.SWAPS_BACK);
        if (!out.cached()) {
            return TextState.COLD;
        }
        try {
            control.restoreSlot(model, settings.slot(), cacheFile(model));
            metrics.count(Counter.SLOT_RESTORES);
            return TextState.RESTORED;
        } catch (ModelControlException failure) {
            metrics.count(Counter.SLOT_RESTORE_FAILURES);
            return TextState.COLD;
        }
    }

    private void awaitLoaded(ModelServerControl control, String model) throws ModelControlException {
        Residency now = control.status(model);
        if (now == Residency.LOADED) {
            return;
        }
        if (now != Residency.LOADING) {
            control.load(model);
        }
        for (int attempt = 0; attempt < settings.statusAttempts(); attempt++) {
            now = control.status(model);
            if (now == Residency.LOADED) {
                return;
            }
            if (now == Residency.FAILED) {
                throw new ModelControlException("load-failed");
            }
            pauseBetweenChecks();
        }
        throw new ModelControlException("load-unconfirmed");
    }

    /** @return true when the server confirms the model left memory; asking again for a gone model is harmless */
    private boolean unloaded(ModelServerControl control, String model) {
        try {
            if (gone(control.status(model))) {
                return true;
            }
            control.unload(model);
            for (int attempt = 0; attempt < settings.statusAttempts(); attempt++) {
                if (gone(control.status(model))) {
                    return true;
                }
                pauseBetweenChecks();
            }
        } catch (ModelControlException failure) {
            // Counted below: an unreachable server is an unconfirmed unload.
        }
        metrics.count(Counter.UNLOAD_FAILURES);
        return false;
    }

    private static boolean gone(Residency residency) {
        return residency == Residency.GONE || residency == Residency.FAILED;
    }

    /** @return true when the demand fits the reading now, counting {@code reclaim} bytes an eviction would free */
    private boolean memoryFits(Demand demand, long reclaim, boolean strict) {
        if (demand.gpuBytes() == 0) {
            return true;
        }
        OptionalLong free = freeGpu();
        if (free.isEmpty()) {
            return !strict;
        }
        return free.getAsLong() + reclaim >= demand.gpuBytes() + settings.headroomBytes();
    }

    /** Confirm freed memory after an unload, which the server may release a little later. */
    private boolean memoryConfirmed(Demand demand) {
        for (int attempt = 0; attempt < settings.statusAttempts(); attempt++) {
            if (memoryFits(demand, 0, true)) {
                return true;
            }
            try {
                pauseBetweenChecks();
            } catch (ModelControlException interrupted) {
                return false;
            }
        }
        return false;
    }

    private OptionalLong freeGpu() {
        OptionalLong free;
        try {
            free = reading.freeBytes();
        } catch (RuntimeException failure) {
            free = OptionalLong.empty();
        }
        if (free.isEmpty()) {
            metrics.count(Counter.READINGS_UNAVAILABLE);
        }
        return free;
    }

    private void pauseBetweenChecks() throws ModelControlException {
        try {
            pause.pause(settings.statusDelay());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelControlException("control-interrupted");
        }
    }

    /** @return a file name that is safe for the server's slot directory and does not repeat the model name */
    private static String cacheFile(String model) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(model.getBytes(StandardCharsets.UTF_8));
            return "engine-slot-" + HexFormat.of().formatHex(digest, 0, 16) + ".bin";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }
}
