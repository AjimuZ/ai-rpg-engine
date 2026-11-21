package org.rpgengine.assets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.admission.ImageAdmission;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.presentation.PresentationEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bounded runtime image jobs for scenes, portraits and items (P17.6-A, docs/23 section 4): a request for one missing
 * identifying image of a subject becomes at most one generation, whose validated result is published through
 * {@link ManagedAssets} and judged there against the campaign as it is when the result arrives.
 *
 * <p>Always holds:
 * <ul>
 *   <li>nothing is generated unless the owner enabled it ({@value Settings#ENABLED}) and an image port exists; a refusal
 *       is an answer, never an exception, and play goes on with catalog or built-in art;</li>
 *   <li>a subject that already shows art for the requested appearance, accepted or authored by the pinned pack,
 *       is {@link Admission#REUSED} and costs nothing; an equal request while one is queued or running is
 *       {@link Admission#COALESCED} into the same job; a queued request for an older appearance of the same subject is
 *       {@link Outcome#SUPERSEDED} by a newer one and never reaches the port;</li>
 *   <li>at most one job per campaign and at most {@link Settings#maxActive()} jobs in total are with the port; each
 *       campaign queues at most {@link Settings#queuePerCampaign()} more and is charged at most
 *       {@link Settings#requestsPerWindow()} generations per rolling {@link Settings#window()}. A charge is taken when
 *       a job is queued, kept once the port received it (whatever the outcome, because a backend may have spent the
 *       work), and given back only when the job ends before it was sent;</li>
 *   <li>a result is stored only through {@link ManagedAssets#publish(UUID, ManagedAssets.Candidate, long)} with the
 *       state revision, pinned pack revision and recovery generation read when the job was requested, so a late,
 *       superseded or pre-restore result never replaces what is shown; a result that arrives after the job's
 *       {@link Settings#runningTimeout()} or after a cancel is dropped;</li>
 *   <li>the port receives only a prompt made of a fixed category framing and the caller's public appearance text, the
 *       picture settings and a seed derived from the job's stable identity: no campaign, actor, world row or hidden
 *       fact. The caller promises that the appearance text is public (docs/23 section 3); this class cannot tell.</li>
 * </ul>
 *
 * <p>Jobs live in memory only. A restart forgets queued and running jobs and their charges, which is safe because
 * nothing canonical depends on them and accepted images are durable in the registry; it also resets the budget window.
 */
@Service
public class RuntimeImageJobs implements AutoCloseable {
    /** Categories this pipeline generates; every other category answers {@link Admission#DISABLED}. */
    public static final Set<AssetCategory> CATEGORIES = Set.copyOf(EnumSet.of(AssetCategory.SCENE,
            AssetCategory.PORTRAIT, AssetCategory.ITEM));
    /** Longest accepted public appearance text, in characters. */
    public static final int MAX_APPEARANCE = 1000;
    /** Credit line of every generated image. */
    public static final String CREDIT = "Generated image for this campaign";
    /** Failure code of a job that held the port longer than its running timeout. */
    public static final String TIMED_OUT = "image-job-timeout";
    /** Failure code of a result the registry refused (bytes or request invalid). */
    public static final String REJECTED = "image-job-rejected";
    /** Failure code of a storage failure while checking or publishing. */
    public static final String STORAGE = "image-job-storage";
    /** Failure code of a submission that threw instead of answering. */
    public static final String SUBMIT_FAILED = "image-job-submit-failed";
    /** Failure code of the service closing while the job waited or ran. */
    public static final String SHUTDOWN = "image-job-shutdown";
    static final String NEGATIVE_PROMPT = "text, watermark, signature, logo, frame, nsfw, gore";
    static final int STEPS = 28;
    static final double GUIDANCE = 6.0;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final System.Logger LOG = System.getLogger(RuntimeImageJobs.class.getName());

    private final JdbcTemplate jdbc;
    private final ManagedAssets assets;
    private final ImageGenerationPort port;
    private final ImageAdmission admission;
    private final Settings settings;
    private final Clock clock;
    /** Queued and running jobs by identity key; guarded by {@code this}. */
    private final Map<String, Job> active = new HashMap<>();
    private final Map<UUID, Job> byId = new HashMap<>();
    /** Jobs not yet sent, oldest first, across campaigns; guarded by {@code this}. */
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    /** Campaigns with a job at the port; guarded by {@code this}. */
    private final Set<UUID> busy = new HashSet<>();
    /** Charge instants per campaign inside the current window; guarded by {@code this}. */
    private final Map<UUID, ArrayDeque<Instant>> charges = new HashMap<>();
    private boolean closed;

    /**
     * Spring wiring. Without an image port bean (no worker address or admission switch) every request is
     * {@link Admission#UNAVAILABLE}; without an admission bean the port is called directly.
     *
     * @throws IllegalStateException when a setting is out of range; the message names the setting only
     */
    @Autowired
    public RuntimeImageJobs(JdbcTemplate jdbc, ManagedAssets assets, ObjectProvider<ImageGenerationPort> ports,
            ObjectProvider<ImageAdmission> admissions, Environment environment) {
        this(jdbc, assets, ports.getIfUnique(), admissions.getIfAvailable(ImageAdmission::direct),
                Settings.from(environment), Clock.systemUTC());
    }

    /**
     * @param port the image port, or null when no image capability is configured
     * @param clock source of the budget window's time
     */
    RuntimeImageJobs(JdbcTemplate jdbc, ManagedAssets assets, ImageGenerationPort port, ImageAdmission admission,
            Settings settings, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.port = port;
        this.admission = Objects.requireNonNull(admission, "admission");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Owner limits of the pipeline, read from {@code engine.presentation.images.*}. Defaults are placeholders and have
     * not been measured.
     *
     * @param enabled whether generation may run at all (default false)
     * @param queuePerCampaign jobs one campaign may have waiting behind its running one, 1 to 32 (4)
     * @param requestsPerWindow generations one campaign may be charged per window, 1 to 1000 (12)
     * @param window length of the rolling budget window, 1 minute to 24 hours (60 minutes)
     * @param maxActive jobs with the port at once over all campaigns, 1 to 4 (1)
     * @param runningTimeout longest wait for one job's result once sent; a later result is dropped (600 seconds)
     * @param maxWait how long one job may wait for an admission lease (60 seconds)
     */
    public record Settings(boolean enabled, int queuePerCampaign, int requestsPerWindow, Duration window, int maxActive,
            Duration runningTimeout, Duration maxWait) {
        static final String ENABLED = "engine.presentation.images.enabled";
        static final String PREFIX = "engine.presentation.images.";

        /** Check the bounds; a value outside them names its component and never its value. */
        public Settings {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(runningTimeout, "runningTimeout");
            Objects.requireNonNull(maxWait, "maxWait");
            require(queuePerCampaign >= 1 && queuePerCampaign <= 32, "queue-per-campaign");
            require(requestsPerWindow >= 1 && requestsPerWindow <= 1000, "requests-per-window");
            require(!window.isNegative() && !window.isZero() && window.compareTo(Duration.ofHours(24)) <= 0,
                    "window-minutes");
            require(maxActive >= 1 && maxActive <= 4, "max-active");
            require(!runningTimeout.isNegative() && !runningTimeout.isZero(), "running-timeout-seconds");
            require(!maxWait.isNegative(), "max-wait-seconds");
        }

        /**
         * @return the settings of the environment; only the exact text {@code true} enables generation
         * @throws IllegalStateException for a value that is not a number or out of range
         */
        static Settings from(Environment environment) {
            String enabled = environment.getProperty(ENABLED, "false");
            int runningSeconds = number(environment, "running-timeout-seconds", 600);
            int waitSeconds = number(environment, "max-wait-seconds", 60);
            require(runningSeconds >= 30 && runningSeconds <= 3600, "running-timeout-seconds");
            require(waitSeconds >= 0 && waitSeconds <= 600, "max-wait-seconds");
            return new Settings(enabled.strip().equalsIgnoreCase("true"), number(environment, "queue-per-campaign", 4),
                    number(environment, "requests-per-window", 12),
                    Duration.ofMinutes(number(environment, "window-minutes", 60)),
                    number(environment, "max-active", 1), Duration.ofSeconds(runningSeconds),
                    Duration.ofSeconds(waitSeconds));
        }

        private static int number(Environment environment, String name, int fallback) {
            String text = environment.getProperty(PREFIX + name);
            if (text == null || text.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException malformed) {
                throw new IllegalStateException(PREFIX + name + " must be a whole number");
            }
        }

        private static void require(boolean valid, String name) {
            if (!valid) {
                throw new IllegalStateException(PREFIX + name + " is out of range");
            }
        }
    }

    /**
     * One request for the identifying image of a subject.
     *
     * @param adventure adventure workspace the campaign belongs to
     * @param campaign campaign the image is for
     * @param category scene, portrait or item
     * @param subject stable public reference (scene, character or item definition), never a display name
     * @param appearanceRevision version of the subject's public appearance, at least 1
     * @param appearance public visual description, at most {@value #MAX_APPEARANCE} characters, no control characters
     */
    public record Request(UUID adventure, UUID campaign, AssetCategory category, String subject, long appearanceRevision,
            String appearance) {
        /** @throws IllegalArgumentException naming the field, never its value */
        public Request {
            Objects.requireNonNull(adventure, "adventure");
            Objects.requireNonNull(campaign, "campaign");
            Objects.requireNonNull(category, "category");
            if (subject == null || !PresentationEvent.REFERENCE.matcher(subject).matches()) {
                throw new IllegalArgumentException("invalid image job field: subject");
            }
            if (appearanceRevision < 1) {
                throw new IllegalArgumentException("invalid image job field: appearanceRevision");
            }
            if (appearance == null || appearance.isBlank() || appearance.length() > MAX_APPEARANCE
                    || CONTROL.matcher(appearance).find()) {
                throw new IllegalArgumentException("invalid image job field: appearance");
            }
        }
    }

    /** Immediate answer of {@link #request}. */
    public enum Admission {
        /** A new job was queued and charged; it starts when its campaign and the port have room. */
        QUEUED,
        /** An equal job is already queued or running; the ticket follows that job and nothing is charged. */
        COALESCED,
        /** The subject already shows art for this appearance; nothing is generated. */
        REUSED,
        /** A newer appearance of the subject is shown, queued or running; nothing is generated. */
        STALE_APPEARANCE,
        /** The owner has not enabled generation, or the category is not generated at runtime. */
        DISABLED,
        /** No image capability is configured. */
        UNAVAILABLE,
        /** The campaign already has its full queue waiting. */
        QUEUE_FULL,
        /** The campaign used its generations for the current window. */
        BUDGET_EXHAUSTED
    }

    /** Terminal outcome of one job. */
    public enum Outcome {
        /** The image was stored and is now shown for the subject. */
        ACCEPTED,
        /** The subject already had art for this appearance when the job settled; nothing new was stored. */
        REUSED,
        /** A newer appearance was accepted meanwhile. */
        STALE_APPEARANCE,
        /** A scene requested at a later campaign revision was accepted meanwhile. */
        STALE_SOURCE,
        /** The campaign no longer pins the pack revision of the request. */
        STALE_CONTENT,
        /** The campaign was restored after the request. */
        STALE_GENERATION,
        /** A newer appearance of the same subject was requested before this job was sent. */
        SUPERSEDED,
        /** The job was cancelled. */
        CANCELLED,
        /** No image: the backend, the admission or the registry failed; see the code. */
        FAILED
    }

    /**
     * How a job ended.
     *
     * @param outcome the outcome
     * @param current the art in force for the subject afterwards when known, else null
     * @param errorCode fixed lowercase code when {@link Outcome#FAILED}, else null
     */
    public record Settled(Outcome outcome, ManagedAssets.Metadata current, String errorCode) {
    }

    /**
     * Answer of {@link #request}.
     *
     * @param admission the immediate verdict
     * @param job identity of the queued or coalesced job, else null
     * @param settled completes once with the job's outcome and never exceptionally; null unless queued or coalesced
     * @param current art in force when {@link Admission#REUSED} or a shown newer appearance, else null
     */
    public record Ticket(Admission admission, UUID job, CompletionStage<Settled> settled,
            ManagedAssets.Metadata current) {
        static Ticket of(Admission admission, ManagedAssets.Metadata current) {
            return new Ticket(admission, null, null, current);
        }
    }

    /**
     * What a campaign has used of its budget.
     *
     * @param charged generations charged inside the current window, queued jobs included
     * @param limit generations allowed per window
     * @param queued jobs waiting to be sent
     * @param running whether a job of the campaign is with the port
     */
    public record Budget(int charged, int limit, int queued, boolean running) {
    }

    /**
     * Ask for the identifying image of one subject. Reads the campaign and its art, never writes anything itself and
     * never waits for a backend.
     *
     * @param request the subject and its public appearance
     * @return the verdict; only {@link Admission#QUEUED} charges the budget
     * @throws IllegalArgumentException when the campaign is not in the adventure
     * @throws DataAccessException when storage fails; nothing was queued
     */
    public Ticket request(Request request) {
        Objects.requireNonNull(request, "request");
        if (!settings.enabled() || !CATEGORIES.contains(request.category())) {
            return Ticket.of(Admission.DISABLED, null);
        }
        if (port == null) {
            return Ticket.of(Admission.UNAVAILABLE, null);
        }
        var row = jdbc.query("select state_revision, content_revision_id, recovery_generation from campaign "
                + "where id = ? and adventure_id = ?", (rs, n) -> new Snapshot(rs.getLong(1), rs.getObject(2, UUID.class),
                rs.getLong(3)), request.campaign(), request.adventure()).stream().findFirst();
        if (row.isEmpty()) {
            throw new IllegalArgumentException("campaign is not in the adventure");
        }
        Optional<ManagedAssets.Metadata> shown = assets.inForce(request.adventure(), request.campaign(),
                request.category(), request.subject());
        if (shown.isPresent() && shown.get().appearanceRevision() >= request.appearanceRevision()) {
            return Ticket.of(shown.get().appearanceRevision() == request.appearanceRevision() ? Admission.REUSED
                    : Admission.STALE_APPEARANCE, shown.get());
        }
        var superseded = new ArrayList<Job>();
        Ticket ticket;
        synchronized (this) {
            ticket = admit(request, row.get(), superseded);
        }
        Settled replaced = new Settled(Outcome.SUPERSEDED, null, null);
        superseded.forEach(job -> job.settled.complete(replaced));
        dispatch();
        return ticket;
    }

    /** Decide under the lock; detaches the queued jobs the new one supersedes into {@code superseded}. */
    private Ticket admit(Request request, Snapshot snapshot, List<Job> superseded) {
        if (closed) {
            return Ticket.of(Admission.UNAVAILABLE, null);
        }
        Job equal = active.get(key(request.campaign(), request.category(), request.subject(),
                request.appearanceRevision()));
        if (equal != null) {
            return new Ticket(Admission.COALESCED, equal.id, equal.settled.minimalCompletionStage(), null);
        }
        var older = new ArrayList<Job>();
        for (Job job : active.values()) {
            if (job.sameSubject(request)) {
                if (job.request.appearanceRevision() > request.appearanceRevision()) {
                    return Ticket.of(Admission.STALE_APPEARANCE, null);
                }
                if (!job.running) {
                    older.add(job);
                }
            }
        }
        // A newer appearance makes a queued older one pointless: it would be stale on arrival.
        older.forEach(job -> {
            detach(job, true);
            superseded.add(job);
        });
        long waiting = queue.stream().filter(job -> job.request.campaign().equals(request.campaign())).count();
        if (waiting >= settings.queuePerCampaign()) {
            return Ticket.of(Admission.QUEUE_FULL, null);
        }
        ArrayDeque<Instant> charged = window(request.campaign());
        if (charged.size() >= settings.requestsPerWindow()) {
            return Ticket.of(Admission.BUDGET_EXHAUSTED, null);
        }
        Job job = new Job(UUID.randomUUID(), request, snapshot, clock.instant());
        charged.addLast(job.charge);
        active.put(job.key(), job);
        byId.put(job.id, job);
        queue.addLast(job);
        return new Ticket(Admission.QUEUED, job.id, job.settled.minimalCompletionStage(), null);
    }

    /**
     * Cancel one job. A queued job ends {@link Outcome#CANCELLED} at once and gets its charge back; a running job is
     * cancelled on the port, keeps its charge, and any image it still returns is dropped.
     *
     * @return false when the job is unknown or already ended
     */
    public boolean cancel(UUID job) {
        Objects.requireNonNull(job, "job");
        Job found;
        ImageHandle handle;
        synchronized (this) {
            found = byId.get(job);
            if (found == null) {
                return false;
            }
            found.cancelRequested = true;
            handle = found.handle;
            if (!found.running) {
                detach(found, true);
            }
        }
        if (!found.running) {
            found.settled.complete(new Settled(Outcome.CANCELLED, null, null));
            dispatch();
        } else if (handle != null) {
            handle.cancel();
        }
        return true;
    }

    /** Whether {@link #request} can queue anything at all, as a screen shows it before the player asks (P17.6-C). */
    public enum Capability {
        /** The owner enabled generation and an image port exists. */
        ENABLED,
        /** The owner has not enabled generation; every request is {@link Admission#DISABLED}. */
        DISABLED,
        /** No image capability is configured, or the service closed; every request is {@link Admission#UNAVAILABLE}. */
        UNAVAILABLE
    }

    /**
     * @return the capability {@link #request} applies to scene, portrait and item requests, checked in the same order:
     *         the owner switch first, then the port and the closed state
     */
    public synchronized Capability capability() {
        if (!settings.enabled()) {
            return Capability.DISABLED;
        }
        return port == null || closed ? Capability.UNAVAILABLE : Capability.ENABLED;
    }

    /** @return the campaign's charges in the current window, its waiting jobs and whether one runs */
    public synchronized Budget budget(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        int waiting = (int) queue.stream().filter(job -> job.request.campaign().equals(campaign)).count();
        return new Budget(window(campaign).size(), settings.requestsPerWindow(), waiting, busy.contains(campaign));
    }

    /** End every job: queued ones at once, running ones by a cancel on the port. Later requests are UNAVAILABLE. */
    @Override
    public void close() {
        List<Job> waiting;
        List<ImageHandle> running = new ArrayList<>();
        synchronized (this) {
            closed = true;
            waiting = new ArrayList<>(queue);
            waiting.forEach(job -> detach(job, true));
            for (Job job : byId.values()) {
                job.cancelRequested = true;
                if (job.handle != null) {
                    running.add(job.handle);
                }
            }
        }
        Settled shutdown = new Settled(Outcome.FAILED, null, SHUTDOWN);
        waiting.forEach(job -> job.settled.complete(shutdown));
        running.forEach(ImageHandle::cancel);
    }

    /** Send every job that now has room, oldest first, skipping campaigns that already have one at the port. */
    private void dispatch() {
        var starting = new ArrayList<Job>();
        synchronized (this) {
            Iterator<Job> waiting = queue.iterator();
            while (!closed && busy.size() < settings.maxActive() && waiting.hasNext()) {
                Job job = waiting.next();
                if (busy.add(job.request.campaign())) {
                    waiting.remove();
                    job.running = true;
                    starting.add(job);
                }
            }
        }
        starting.forEach(this::start);
    }

    /** Re-check the subject, then submit; runs without the lock because it reads storage and calls the port. */
    private void start(Job job) {
        Optional<ManagedAssets.Metadata> shown;
        try {
            shown = assets.inForce(job.request.adventure(), job.request.campaign(), job.request.category(),
                    job.request.subject());
        } catch (DataAccessException failure) {
            finish(job, new Settled(Outcome.FAILED, null, STORAGE), true);
            return;
        }
        // Art accepted while the job waited makes the generation pointless; it was never sent, so it costs nothing.
        if (shown.isPresent() && shown.get().appearanceRevision() >= job.request.appearanceRevision()) {
            finish(job, new Settled(shown.get().appearanceRevision() == job.request.appearanceRevision()
                    ? Outcome.REUSED : Outcome.STALE_APPEARANCE, shown.get(), null), true);
            return;
        }
        boolean cancelledBeforeSending;
        synchronized (this) {
            cancelledBeforeSending = job.cancelRequested && detach(job, true);
        }
        if (cancelledBeforeSending) {
            job.settled.complete(new Settled(Outcome.CANCELLED, null, null));
            dispatch();
            return;
        }
        ImageHandle handle;
        try {
            handle = Objects.requireNonNull(admission.submit(port, imageRequest(job.request),
                    new ImageAdmission.Ticket(WorkClass.SPECULATIVE, job.request.campaign(), settings.maxWait())),
                    "handle");
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Image job {0} could not be submitted: {1}", job.id,
                    failure.getClass().getSimpleName());
            finish(job, new Settled(Outcome.FAILED, null, SUBMIT_FAILED), false);
            return;
        }
        boolean cancel;
        synchronized (this) {
            job.handle = handle;
            cancel = job.cancelRequested;
        }
        if (cancel) {
            handle.cancel();
        }
        // Exactly one of the real result and the timeout marker completes this future, so a late image is dropped.
        var bounded = new CompletableFuture<ImageResult>();
        ImageResult timedOut = ImageResult.failed(TIMED_OUT);
        handle.result().whenComplete((result, failure) -> bounded.complete(result != null ? result
                : ImageResult.failed(SUBMIT_FAILED)));
        bounded.completeOnTimeout(timedOut, settings.runningTimeout().toMillis(), TimeUnit.MILLISECONDS);
        Job sent = job;
        bounded.thenAccept(result -> {
            if (result == timedOut) {
                handle.cancel();
            }
            finish(sent, settle(sent, result), false);
        });
    }

    /** Turn a terminal port result into an outcome, publishing a completed image unless the job was cancelled. */
    private Settled settle(Job job, ImageResult result) {
        boolean cancelled;
        synchronized (this) {
            cancelled = job.cancelRequested;
        }
        if (result.status() == ImageResult.Status.CANCELLED || (cancelled && result.status() == ImageResult.Status.COMPLETED)) {
            return new Settled(Outcome.CANCELLED, null, null);
        }
        if (result.status() == ImageResult.Status.FAILED) {
            return new Settled(Outcome.FAILED, null, result.errorCode());
        }
        Request request = job.request;
        try {
            var published = assets.publish(request.campaign(), new ManagedAssets.Candidate(request.category(),
                    request.subject(), request.appearanceRevision(), job.snapshot.stateRevision(),
                    job.snapshot.contentRevision(), CREDIT, result.png()), job.snapshot.generation());
            return new Settled(Outcome.valueOf(published.status().name()), published.current(), null);
        } catch (IllegalArgumentException rejected) {
            // Includes PackException (MEDIA_TYPE, MEDIA_LIMIT): the bytes were not admissible media.
            return new Settled(Outcome.FAILED, null, REJECTED);
        } catch (DataAccessException failure) {
            return new Settled(Outcome.FAILED, null, STORAGE);
        }
    }

    /** End a job once, complete its ticket and let the next one start. */
    private void finish(Job job, Settled settled, boolean refund) {
        synchronized (this) {
            if (!detach(job, refund)) {
                return;
            }
        }
        job.settled.complete(settled);
        dispatch();
    }

    /**
     * Remove a job from every structure under the lock, giving its charge back when asked.
     *
     * @return false when the job had already been detached
     */
    private boolean detach(Job job, boolean refund) {
        if (byId.remove(job.id) == null) {
            return false;
        }
        active.remove(job.key(), job);
        if (job.running) {
            busy.remove(job.request.campaign());
        } else {
            queue.remove(job);
        }
        if (refund) {
            ArrayDeque<Instant> charged = charges.get(job.request.campaign());
            if (charged != null) {
                charged.remove(job.charge);
            }
        }
        return true;
    }

    /** The campaign's charges after dropping those older than the window; call under the lock. */
    private ArrayDeque<Instant> window(UUID campaign) {
        Instant start = clock.instant().minus(settings.window());
        ArrayDeque<Instant> charged = charges.computeIfAbsent(campaign, ignored -> new ArrayDeque<>());
        while (!charged.isEmpty() && !charged.peekFirst().isAfter(start)) {
            charged.removeFirst();
        }
        return charged;
    }

    /**
     * The request every backend receives: fixed framing and size by category, the public appearance text and a seed
     * that depends only on the job's identity, so a retry of the same subject and appearance asks for the same picture.
     */
    static ImageRequest imageRequest(Request request) {
        String framing = switch (request.category()) {
            case SCENE -> "scenic background illustration, wide establishing view, ";
            case PORTRAIT -> "character portrait, head and shoulders, ";
            case ITEM -> "single object illustration, centered on a plain background, ";
            default -> throw new IllegalArgumentException("category is not generated at runtime");
        };
        int width = request.category() == AssetCategory.SCENE ? 1216 : request.category() == AssetCategory.PORTRAIT ? 832
                : 1024;
        int height = request.category() == AssetCategory.SCENE ? 832 : request.category() == AssetCategory.PORTRAIT ? 1216
                : 1024;
        return new ImageRequest(framing + request.appearance().strip(), NEGATIVE_PROMPT, null, width, height, STEPS,
                GUIDANCE, seed(request), null);
    }

    /** First four bytes of the SHA-256 of campaign, category, subject and appearance revision, as an unsigned seed. */
    static long seed(Request request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key(request.campaign(), request.category(),
                    request.subject(), request.appearanceRevision()).getBytes(StandardCharsets.UTF_8));
            return Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt());
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String key(UUID campaign, AssetCategory category, String subject, long appearance) {
        return campaign + "\u0000" + category.wire() + '\u0000' + subject + '\u0000' + appearance;
    }

    /** Campaign facts read when the job was requested; the registry judges the result against them. */
    private record Snapshot(long stateRevision, UUID contentRevision, long generation) {
    }

    /** One queued or running job; mutable fields are guarded by the service's lock. */
    private static final class Job {
        final UUID id;
        final Request request;
        final Snapshot snapshot;
        final Instant charge;
        final CompletableFuture<Settled> settled = new CompletableFuture<>();
        boolean running;
        boolean cancelRequested;
        ImageHandle handle;

        Job(UUID id, Request request, Snapshot snapshot, Instant charge) {
            this.id = id;
            this.request = request;
            this.snapshot = snapshot;
            this.charge = charge;
        }

        String key() {
            return RuntimeImageJobs.key(request.campaign(), request.category(), request.subject(),
                    request.appearanceRevision());
        }

        boolean sameSubject(Request other) {
            return request.campaign().equals(other.campaign()) && request.category() == other.category()
                    && request.subject().equals(other.subject());
        }
    }
}
