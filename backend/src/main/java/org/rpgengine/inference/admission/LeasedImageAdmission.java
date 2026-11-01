package org.rpgengine.inference.admission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;

/**
 * The {@link ImageAdmission} behind the switch: each job becomes one image {@link AdmissionRequest}, and a created
 * lease runs its batch through {@link LeaseExecutor#runBatch} as an {@link ImageSteppedJob}.
 *
 * <p>Grants arrive only through {@link #accept}, from the {@link TextResidencyDriver}'s worker, because that driver is
 * the one caller of {@link LeaseExecutor#pass} and a grant is handed out once. {@link #accept} and {@link #sweep}
 * never wait for a batch: each batch runs on its own thread, because the worker must keep passing and renewing text
 * while images run, and a second lease must not wait behind the first until it lapses. The number of batch threads is
 * bounded by the leases that fit the budget. A caller's cancel withdraws its request on one further thread, as each
 * withdrawal waits for the executor's monitor and only one could hold it at a time anyway.
 *
 * <p>Every status ends the jobs it covers with a fixed code, except that a job put back to ask again
 * ({@link LeaseExecutor.Status#DEFERRED}, {@code DELAYED}, {@code SWAP_FAILED}) stays waiting within its
 * {@link ImageAdmission.Ticket#maxWait}, because the executor asks again itself and a resubmission would duplicate it.
 * When the wait ends the request is withdrawn, so it is never granted later. {@link #sweep} ends a wait that no grant
 * ever answered, such as a request queued behind a long text lease.
 *
 * <p>Failures: a broken grant ends its jobs {@code UNAVAILABLE} and gives its members back. Grants and batches log by
 * class name only, as a message could quote a model name.
 */
final class LeasedImageAdmission implements ImageAdmission, AutoCloseable {
    /** The lease model of a request that names no checkpoint and takes the backend's current one. */
    static final String CURRENT_CHECKPOINT = "image-current";
    private static final String SHUTDOWN = "image-shutdown";
    private static final System.Logger LOG = System.getLogger(LeasedImageAdmission.class.getName());

    private final LeaseExecutor leases;
    private final AdmissionCoordinator coordinator;
    private final Demand demand;
    private final Clock clock;
    private final Duration stepWait;
    private final Duration stopWait;
    private final Duration closeWait;
    private final ExecutorService batches = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "inference-image");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService withdrawals = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "inference-image-withdrawal");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, AdmittedImage> images = new ConcurrentHashMap<>();
    private final Map<UUID, ImageSteppedJob> running = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param leases the executor
     * @param coordinator receives the requests
     * @param demand what an image model is taken to need, within the coordinator's capacity
     * @param clock the coordinator's time source
     * @param stepWait longest wait of one job step, positive
     * @param stopWait longest wait for a cancelled job before its model is unloaded
     * @param closeWait longest wait in {@link #close} for running batches to end, which should cover one step, the
     *        stop wait and an unload
     */
    LeasedImageAdmission(LeaseExecutor leases, AdmissionCoordinator coordinator, Demand demand, Clock clock,
            Duration stepWait, Duration stopWait, Duration closeWait) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.demand = Objects.requireNonNull(demand, "demand");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stepWait = Objects.requireNonNull(stepWait, "stepWait");
        this.stopWait = Objects.requireNonNull(stopWait, "stopWait");
        this.closeWait = Objects.requireNonNull(closeWait, "closeWait");
    }

    /** {@inheritDoc} After {@link #close} the job ends {@code image-shutdown}. */
    @Override
    public ImageHandle submit(ImageGenerationPort adapter, ImageRequest request, Ticket ticket) {
        Instant now = clock.instant();
        AdmittedImage image = new AdmittedImage(UUID.randomUUID(), Objects.requireNonNull(adapter, "adapter"),
                Objects.requireNonNull(request, "request"), now.plus(ticket.maxWait()), this::withdrawLater);
        if (closed) {
            image.finish(ImageResult.failed(SHUTDOWN));
            return image;
        }
        // Known before the coordinator can grant it, because a grant nobody recognises is given back.
        images.put(image.id(), image);
        image.result().whenComplete((result, failure) -> images.remove(image.id()));
        try {
            Submission submission = coordinator.submit(new AdmissionRequest(image.id(), Modality.IMAGE, ticket.workClass(),
                    ticket.owner(), leaseModel(request), demand, now));
            if (submission != Submission.ACCEPTED) {
                image.finish(ImageResult.failed(UNAVAILABLE));
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "image request failed: " + failure.getClass().getName());
            image.finish(ImageResult.failed(UNAVAILABLE));
        }
        return image;
    }

    /**
     * A checkpoint name may hold characters a lease model may not, so such a name is replaced by a digest: two
     * checkpoints must never share a lease and its single demand.
     *
     * @return the model identifier of the request's lease
     */
    static String leaseModel(ImageRequest request) {
        String model = request.model();
        if (model == null) {
            return CURRENT_CHECKPOINT;
        }
        if (AdmissionRequest.validModel(model)) {
            return model;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(model.getBytes(StandardCharsets.UTF_8));
            return "image-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }

    /**
     * Take an image grant from a pass. Runs on the driver's worker and never waits for a batch.
     *
     * @param grant a grant of an image lease
     */
    void accept(Grant grant) {
        try {
            if (grant.created()) {
                start(grant);
            } else {
                join(grant);
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "image grant failed: " + failure.getClass().getName());
            end(grant, UNAVAILABLE);
        }
    }

    private void start(Grant grant) {
        List<AdmittedImage> wanted = grant.admitted().stream().map(request -> images.get(request.id()))
                .filter(Objects::nonNull).toList();
        if (wanted.isEmpty()) {
            release(grant);
            return;
        }
        ImageSteppedJob job = new ImageSteppedJob(wanted, stepWait, stopWait);
        running.put(grant.lease().id(), job);
        if (closed) {
            // A worker that outlasted the driver's stop: close may already have gone through the running batches.
            job.stop();
        }
        try {
            batches.execute(() -> run(grant, job));
        } catch (RejectedExecutionException stopped) {
            running.remove(grant.lease().id());
            end(grant, SHUTDOWN);
        }
    }

    /**
     * No running batch means its outcome already named these members: the single worker takes this grant before any
     * later pass could end the lease, so the outcome saw them as members of the live lease.
     */
    private void join(Grant grant) {
        ImageSteppedJob job = running.get(grant.lease().id());
        if (job != null) {
            grant.admitted().stream().map(request -> images.get(request.id())).filter(Objects::nonNull).forEach(job::add);
        }
    }

    /** Nothing was loaded for this grant, so its members may simply end the lease. */
    private void release(Grant grant) {
        grant.admitted().forEach(request -> leases.finishText(request.id()));
    }

    private void end(Grant grant, String code) {
        release(grant);
        for (AdmissionRequest request : grant.admitted()) {
            AdmittedImage image = images.get(request.id());
            if (image != null) {
                image.finish(ImageResult.failed(code));
            }
        }
    }

    private void run(Grant grant, ImageSteppedJob job) {
        UUID lease = grant.lease().id();
        try {
            settle(leases.runBatch(grant, job), job);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "image batch failed: " + failure.getClass().getName());
            job.images().forEach(image -> {
                leases.finishText(image.id());
                image.finish(ImageResult.failed(UNAVAILABLE));
            });
        } finally {
            // Only after settling, so that a sweep meanwhile does not end these jobs with a code that says less.
            running.remove(lease);
        }
    }

    /**
     * End what the batch owes. A job that already has its answer ignores this. Once closed, every job ends
     * {@code image-shutdown} and none stays waiting, since nothing grants any more.
     */
    void settle(LeaseExecutor.Outcome outcome, ImageSteppedJob job) {
        Set<AdmittedImage> owed = new LinkedHashSet<>(job.images());
        for (UUID member : outcome.members()) {
            AdmittedImage image = images.get(member);
            if (image != null) {
                owed.add(image);
            }
        }
        String code = closed ? SHUTDOWN : code(outcome.status());
        Instant now = clock.instant();
        for (AdmittedImage image : owed) {
            if (asksAgain(outcome.status())) {
                if (!closed && now.isBefore(image.giveUpAt())) {
                    image.waitingFor(code);
                    continue;
                }
                leases.cancel(image.id());
            }
            image.finish(ImageResult.failed(code));
        }
    }

    private static boolean asksAgain(LeaseExecutor.Status status) {
        return status == LeaseExecutor.Status.DEFERRED || status == LeaseExecutor.Status.DELAYED
                || status == LeaseExecutor.Status.SWAP_FAILED;
    }

    /**
     * @return the fixed code for a job a batch of this status did not finish. {@code COMPLETED} covers a request that
     *         joined after the last step, and {@code READY} never reaches a batch; both lost their admission unserved.
     */
    static String code(LeaseExecutor.Status status) {
        return switch (status) {
            case DEFERRED -> DEFERRED;
            case DELAYED -> DELAYED;
            case SWAP_FAILED -> SWAP_FAILED;
            case FELL_BACK -> CONTENTION;
            case CONTENTION_YIELDED -> YIELDED;
            case INTERRUPTED -> INTERRUPTED;
            case TIME_CAPPED -> TIME_CAPPED;
            case JOB_FAILED -> JOB_FAILED;
            case LOAD_FAILED -> LOAD_FAILED;
            case LOST, COMPLETED, READY -> LOST;
        };
    }

    /**
     * End the waits that are over and that no batch holds now. Runs on the driver's worker after each pass, so a
     * request granted by that pass is already in a batch.
     */
    void sweep() {
        Set<AdmittedImage> inBatches = new HashSet<>();
        running.values().forEach(job -> inBatches.addAll(job.images()));
        Instant now = clock.instant();
        for (AdmittedImage image : List.copyOf(images.values())) {
            if (!inBatches.contains(image) && !now.isBefore(image.giveUpAt())) {
                leases.cancel(image.id());
                image.finish(ImageResult.failed(image.waitingFor()));
            }
        }
    }

    /** A cancel must not wait for the executor, whose monitor is held across server calls. */
    private void withdrawLater(AdmittedImage image) {
        try {
            withdrawals.execute(() -> leases.cancel(image.id()));
        } catch (RejectedExecutionException stopped) {
            // Closed: nothing grants any more, and the next process recovers no waiting request.
        }
    }

    /**
     * Stop, then end every open job with {@code image-shutdown}. The driver has stopped by then, as lifecycle beans
     * stop before any bean is destroyed.
     *
     * <p>Batches are told to stop rather than interrupted, because an interrupt can reach a server call and leave an
     * unload unconfirmed. Each batch stops at its next step, cancels its backend, waits for it and unloads its model,
     * which takes up to the step wait, the stop wait and the unload's status checks. This waits for that for up to the
     * close wait, and a batch that takes longer goes on as a daemon thread until it ends or the process exits. A batch
     * still loading its model stops only after the load. If the process exits first, the lease stays open and the next
     * process holds it until its model is gone. Requests still queued are withdrawn only when every batch ended,
     * because a batch that is still running may hold the executor's monitor across a server call; nothing grants them
     * after this.
     */
    @Override
    public void close() {
        closed = true;
        running.values().forEach(ImageSteppedJob::stop);
        batches.shutdown();
        withdrawals.shutdown();
        boolean ended = false;
        try {
            ended = batches.awaitTermination(closeWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (AdmittedImage image : List.copyOf(images.values())) {
            if (ended) {
                leases.cancel(image.id());
            }
            image.finish(ImageResult.failed(SHUTDOWN));
        }
    }
}
