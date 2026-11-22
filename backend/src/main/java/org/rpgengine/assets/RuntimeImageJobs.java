package org.rpgengine.assets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

import javax.imageio.ImageIO;
import org.rpgengine.content.PackPresentation;
import org.rpgengine.content.WorkspaceProjection;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.admission.ImageAdmission;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.presentation.PresentationEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded runtime image jobs for scenes, portraits and items (P17.6-A, durable since P17.6-D; docs/23 section 4,
 * ADR 0056): a request for one missing identifying image of a subject becomes at most one generation, whose result
 * passes an acceptance review and is then published through {@link ManagedAssets} and judged there against the
 * campaign as it is when the result arrives.
 *
 * <p>Always holds:
 * <ul>
 *   <li>nothing is generated unless the owner enabled it ({@value Settings#ENABLED}), an image port exists and the
 *       campaign's pinned pack declares the category {@link PackPresentation#BOUNDED} (the default when it declares
 *       nothing); a refusal is an answer, never an exception, and play goes on with catalog or built-in art;</li>
 *   <li>a subject that already shows art for the requested appearance, accepted or authored by the pinned pack,
 *       is {@link Admission#REUSED} and costs nothing; an equal request while one is queued or running is
 *       {@link Admission#COALESCED} into the same job; a queued request for an older appearance of the same subject is
 *       {@link Outcome#SUPERSEDED} by a newer one and never reaches the port;</li>
 *   <li>every job is a {@code runtime_image_job} row (V38) before it can reach the port, and that row is claimed
 *       ({@code QUEUED} to {@code RUNNING} with this instance's runner identity) before the job is sent. Only the
 *       instance that claimed a row can settle it, and {@link #recover()} ends the rows a previous process left
 *       {@code RUNNING} without sending them again, so a result is never published twice and the result of a job that
 *       ended meanwhile is dropped;</li>
 *   <li>at most one job per campaign and at most {@link Settings#maxActive()} jobs in total are with the port; each
 *       campaign queues at most {@link Settings#queuePerCampaign()} more and is charged at most
 *       {@link Settings#requestsPerWindow()} generations per rolling {@link Settings#window()}. A charge is the row's
 *       {@code charged_at}, so it survives a restart. It is taken when a job is queued, kept once the port received it
 *       (whatever the outcome, because a backend may have spent the work), and given back only when the job ends
 *       before it was sent;</li>
 *   <li>a completed image is stored only when it passes {@link #review} and only through
 *       {@link ManagedAssets#publish(UUID, ManagedAssets.Candidate, long)} with the state revision, pinned pack revision
 *       and recovery generation read when the job was requested, so a late, superseded or pre-restore result never
 *       replaces what is shown; a result that arrives after the job's {@link Settings#runningTimeout()} or after a
 *       cancel is dropped. The accepted job row keeps the image's hash and provenance, and the credit line names the
 *       backend that made it;</li>
 *   <li>the port receives only a prompt made of a fixed category framing and the caller's public appearance text, the
 *       picture settings and a seed derived from the job's stable identity: no campaign, actor, world row or hidden
 *       fact. The caller promises that the appearance text is public (docs/23 section 3); this class cannot tell.</li>
 * </ul>
 */
@Service
public class RuntimeImageJobs implements AutoCloseable {
    /** Categories this pipeline generates; every other category answers {@link Admission#DISABLED}. */
    public static final Set<AssetCategory> CATEGORIES = Set.copyOf(EnumSet.of(AssetCategory.SCENE,
            AssetCategory.PORTRAIT, AssetCategory.ITEM));
    /** Longest accepted public appearance text, in characters. */
    public static final int MAX_APPEARANCE = 1000;
    /** First part of the credit line of every generated image; {@link #credit} adds the backend. */
    public static final String CREDIT = "Generated image for this campaign";
    /** Failure code of a job that held the port longer than its running timeout. */
    public static final String TIMED_OUT = "image-job-timeout";
    /** Failure code of a result the registry refused (bytes or request invalid). */
    public static final String REJECTED = "image-job-rejected";
    /** Failure code of an image that failed the acceptance review; nothing was stored. */
    public static final String REVIEW_REJECTED = "image-review-rejected";
    /** Failure code of a job that was with the port when the previous process ended; it is not sent again. */
    public static final String INTERRUPTED = "image-job-interrupted";
    /** Failure code of a result whose job row had already ended, as after a restart; the image is dropped. */
    public static final String LATE = "image-job-late";
    /** Failure code of a queued job that another instance of this service claimed or ended first. */
    public static final String ELSEWHERE = "image-job-elsewhere";
    /** Failure code of a storage failure while checking or publishing. */
    public static final String STORAGE = "image-job-storage";
    /** Failure code of a submission that threw instead of answering. */
    public static final String SUBMIT_FAILED = "image-job-submit-failed";
    /** Failure code of the service closing while the job waited or ran; the row stays open for the next start. */
    public static final String SHUTDOWN = "image-job-shutdown";
    /** Failure code stored in place of a backend code that is not a plain lowercase word list. */
    public static final String FAILED_CODE = "image-job-failed";
    /** Name and version of the acceptance review recorded in an accepted image's provenance. */
    public static final String REVIEW = "automatic-v1";
    static final String NEGATIVE_PROMPT = "text, watermark, signature, logo, frame, nsfw, gore";
    static final int STEPS = 28;
    static final double GUIDANCE = 6.0;
    static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern BACKEND = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    private static final Pattern MODEL_HASH = Pattern.compile("[0-9a-f]{8,128}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final String OPEN = "('QUEUED', 'RUNNING')";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(RuntimeImageJobs.class.getName());

    private final JdbcTemplate jdbc;
    private final ManagedAssets assets;
    private final ImageGenerationPort port;
    private final ImageAdmission admission;
    private final Settings settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final WorkspaceProjection projection;
    /** This instance's identity in claimed rows; a new process has a new one. */
    private final UUID runner = UUID.randomUUID();
    /** Queued and running jobs this instance holds, by identity key; guarded by {@code this}. */
    private final Map<String, Job> active = new HashMap<>();
    private final Map<UUID, Job> byId = new HashMap<>();
    /** Jobs not yet sent, oldest first, across campaigns; guarded by {@code this}. */
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    /** Campaigns with a job of this instance at the port; guarded by {@code this}. */
    private final Set<UUID> busy = new HashSet<>();
    private boolean closed;

    /**
     * Spring wiring. Without an image port bean (no worker address or admission switch) every request is
     * {@link Admission#UNAVAILABLE}; without an admission bean the port is called directly; without a managed-folder
     * root accepted images stay in the database only.
     *
     * @throws IllegalStateException when a setting is out of range; the message names the setting only
     */
    @Autowired
    public RuntimeImageJobs(JdbcTemplate jdbc, PlatformTransactionManager manager, ManagedAssets assets,
            ObjectProvider<ImageGenerationPort> ports, ObjectProvider<ImageAdmission> admissions,
            ObjectProvider<WorkspaceProjection> projections, Environment environment) {
        this(jdbc, manager, assets, ports.getIfUnique(), admissions.getIfAvailable(ImageAdmission::direct),
                Settings.from(environment), Clock.systemUTC(), projections.getIfAvailable());
    }

    /**
     * @param port the image port, or null when no image capability is configured
     * @param clock source of the budget window's time and of the rows' instants
     * @param projection managed-folder writer for accepted images, or null when folders are not configured
     */
    RuntimeImageJobs(JdbcTemplate jdbc, PlatformTransactionManager manager, ManagedAssets assets,
            ImageGenerationPort port, ImageAdmission admission, Settings settings, Clock clock,
            WorkspaceProjection projection) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.port = port;
        this.admission = Objects.requireNonNull(admission, "admission");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projection = projection;
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
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
        /**
         * The owner has not enabled generation, the category is not generated at runtime, or the pinned pack's policy
         * for the category is not {@link PackPresentation#BOUNDED}.
         */
        DISABLED,
        /** No image capability is configured. */
        UNAVAILABLE,
        /** The campaign already has its full queue waiting. */
        QUEUE_FULL,
        /** The campaign used its generations for the current window. */
        BUDGET_EXHAUSTED
    }

    /** Terminal outcome of one job; each name is also the job row's status. */
    public enum Outcome {
        /** The image passed the review, was stored and is now shown for the subject. */
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
        /** No image: the backend, the admission, the review or the registry failed, or a restart ended it; see the code. */
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
     * @param settled completes once with the job's outcome and never exceptionally; null unless queued or coalesced,
     *        and null for a job coalesced into a row this instance does not hold (another instance's, or one
     *        {@link #recover()} has not resumed yet), whose state the row itself reports
     * @param current art in force when {@link Admission#REUSED} or a shown newer appearance, else null
     */
    public record Ticket(Admission admission, UUID job, CompletionStage<Settled> settled,
            ManagedAssets.Metadata current) {
        static Ticket of(Admission admission, ManagedAssets.Metadata current) {
            return new Ticket(admission, null, null, current);
        }
    }

    /**
     * What a campaign has used of its budget, as its job rows record it.
     *
     * @param charged generations charged inside the current window, queued jobs included
     * @param limit generations allowed per window
     * @param queued jobs waiting to be sent
     * @param running whether a job of the campaign is with the port
     */
    public record Budget(int charged, int limit, int queued, boolean running) {
    }

    /**
     * The newest job of one subject, as the playthrough screen reports it (P17.6-D).
     *
     * @param job identity of the job
     * @param category its category
     * @param subject its subject
     * @param outcome how it ended, or null while it is queued or running
     * @param errorCode fixed lowercase code when {@link Outcome#FAILED}, else null
     */
    public record Recorded(UUID job, AssetCategory category, String subject, Outcome outcome, String errorCode) {
    }

    /**
     * Totals of one {@link #recover()} pass.
     *
     * @param resumed queued rows taken back into this instance's queue
     * @param interrupted rows a previous process had sent, ended {@link #INTERRUPTED} with their charge kept
     * @param stale open rows of a campaign restored or gone since, ended {@link Outcome#STALE_GENERATION}
     * @param cancelled queued rows ended {@link Outcome#CANCELLED} with their charge given back, because generation is
     *        now disabled or unavailable
     */
    public record Recovery(int resumed, int interrupted, int stale, int cancelled) {
    }

    /**
     * Ask for the identifying image of one subject. Reads the campaign and its art, writes at most the job row and
     * the supersession of older queued rows of the same subject, and never waits for a backend.
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
        if (!PackPresentation.BOUNDED.equals(assets.imagePolicy(request.adventure(), request.campaign(),
                request.category()))) {
            return Ticket.of(Admission.DISABLED, null);
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

    /**
     * Decide under the lock and record the decision in one transaction; detaches the queued jobs the new one supersedes
     * into {@code superseded}.
     */
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
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Decision decision;
        try {
            decision = transaction.execute(status -> decide(request, snapshot, older, id, now));
        } catch (DuplicateKeyException race) {
            // Another instance queued the same subject and appearance between the check and the insert.
            return foreign(request).map(open -> new Ticket(Admission.COALESCED, open, null, null))
                    .orElseThrow(() -> race);
        }
        if (decision.supersededOlder()) {
            // A newer appearance makes a queued older one pointless: it would be stale on arrival.
            older.forEach(job -> {
                detach(job);
                superseded.add(job);
            });
        }
        if (decision.ticket().admission() != Admission.QUEUED) {
            return decision.ticket();
        }
        Job job = new Job(id, request, snapshot);
        active.put(job.key(), job);
        byId.put(job.id, job);
        queue.addLast(job);
        return new Ticket(Admission.QUEUED, job.id, job.settled.minimalCompletionStage(), null);
    }

    /** The transactional part of {@link #admit}: open rows elsewhere, supersession, queue and budget, then the row. */
    private Decision decide(Request request, Snapshot snapshot, List<Job> older, UUID id, Instant now) {
        for (Object[] open : jdbc.query("select id, appearance_revision from runtime_image_job where campaign_id = ? "
                + "and category = ? and subject = ? and status in " + OPEN, (rs, n) -> new Object[] {
                        rs.getObject(1, UUID.class), rs.getLong(2)}, request.campaign(), request.category().wire(),
                request.subject())) {
            if (byId.containsKey((UUID) open[0])) {
                continue;
            }
            long revision = (long) open[1];
            if (revision > request.appearanceRevision()) {
                return new Decision(Ticket.of(Admission.STALE_APPEARANCE, null), false);
            }
            if (revision == request.appearanceRevision()) {
                return new Decision(new Ticket(Admission.COALESCED, (UUID) open[0], null, null), false);
            }
        }
        for (Job job : older) {
            jdbc.update("update runtime_image_job set status = 'SUPERSEDED', charged_at = null, settled_at = ? "
                    + "where id = ? and status = 'QUEUED'", at(now), job.id);
        }
        int waiting = jdbc.queryForObject("select count(*) from runtime_image_job where campaign_id = ? and "
                + "status = 'QUEUED'", Integer.class, request.campaign());
        if (waiting >= settings.queuePerCampaign()) {
            return new Decision(Ticket.of(Admission.QUEUE_FULL, null), true);
        }
        if (charged(request.campaign(), now) >= settings.requestsPerWindow()) {
            return new Decision(Ticket.of(Admission.BUDGET_EXHAUSTED, null), true);
        }
        jdbc.update("insert into runtime_image_job (id, adventure_id, campaign_id, category, subject, "
                + "appearance_revision, appearance, source_revision, content_revision_id, recovery_generation, status, "
                + "charged_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)", id, request.adventure(),
                request.campaign(), request.category().wire(), request.subject(), request.appearanceRevision(),
                request.appearance(), snapshot.stateRevision(), snapshot.contentRevision(), snapshot.generation(), at(now),
                at(now));
        return new Decision(new Ticket(Admission.QUEUED, id, null, null), true);
    }

    /** The open row of the request's subject and appearance, if any. */
    private Optional<UUID> foreign(Request request) {
        return jdbc.query("select id from runtime_image_job where campaign_id = ? and category = ? and subject = ? and "
                + "appearance_revision = ? and status in " + OPEN, (rs, n) -> rs.getObject(1, UUID.class),
                request.campaign(), request.category().wire(), request.subject(), request.appearanceRevision())
                .stream().findFirst();
    }

    /**
     * Cancel one job this instance holds. A queued job ends {@link Outcome#CANCELLED} at once and gets its charge
     * back; a running job is cancelled on the port, keeps its charge, and any image it still returns is dropped.
     *
     * @return false when the job is unknown here or already ended
     */
    public boolean cancel(UUID job) {
        Objects.requireNonNull(job, "job");
        Job found;
        ImageHandle handle;
        boolean queued;
        synchronized (this) {
            found = byId.get(job);
            if (found == null) {
                return false;
            }
            found.cancelRequested = true;
            handle = found.handle;
            queued = !found.running;
            if (queued) {
                detach(found);
            }
        }
        if (queued) {
            record(found, Outcome.CANCELLED, null, true, null, null);
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
     *         the owner switch first, then the port and the closed state; a pack's category policy is not part of it
     */
    public synchronized Capability capability() {
        if (!settings.enabled()) {
            return Capability.DISABLED;
        }
        return port == null || closed ? Capability.UNAVAILABLE : Capability.ENABLED;
    }

    /**
     * @return the campaign's charges in the current window, its waiting jobs and whether one runs, from its job rows
     * @throws DataAccessException when storage fails
     */
    public Budget budget(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        int charged = charged(campaign, clock.instant());
        Map<String, Object> open = jdbc.queryForMap("select count(*) filter (where status = 'QUEUED') as queued, "
                + "count(*) filter (where status = 'RUNNING') as running from runtime_image_job where campaign_id = ? "
                + "and status in " + OPEN, campaign);
        return new Budget(charged, settings.requestsPerWindow(), ((Number) open.get("queued")).intValue(),
                ((Number) open.get("running")).longValue() > 0);
    }

    /**
     * The newest job of each subject of a campaign, at most {@value RuntimeImageRequests#MAX_TRACKED} subjects.
     *
     * @throws DataAccessException when storage fails
     */
    public List<Recorded> latest(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        return jdbc.query("select distinct on (category, subject) id, category, subject, status, error_code from "
                + "runtime_image_job where campaign_id = ? order by category, subject, ordinal desc limit "
                + RuntimeImageRequests.MAX_TRACKED, (rs, n) -> {
                    String status = rs.getString(4);
                    return new Recorded(rs.getObject(1, UUID.class), AssetCategory.fromWire(rs.getString(2)),
                            rs.getString(3), status.equals("QUEUED") || status.equals("RUNNING") ? null
                                    : Outcome.valueOf(status), rs.getString(5));
                }, campaign);
    }

    /** Recover the rows of the previous process once the application is ready; a failure is logged, never fatal. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        try {
            Recovery report = recover();
            LOG.log(System.Logger.Level.INFO, "Image jobs recovered: {0} resumed, {1} interrupted, {2} stale, "
                    + "{3} cancelled", report.resumed(), report.interrupted(), report.stale(), report.cancelled());
        } catch (DataAccessException failure) {
            LOG.log(System.Logger.Level.WARNING, "Image job recovery failed: {0}", failure.getClass().getSimpleName());
        }
    }

    /**
     * Settle the open rows this instance does not hold, oldest first, and send what may run. A row whose campaign
     * was restored since (another recovery generation) or no longer exists ends {@link Outcome#STALE_GENERATION}; a
     * row another process had sent ends {@link Outcome#FAILED} {@link #INTERRUPTED} with its charge kept, because the
     * backend may have done the work and sending it again would generate twice; a queued row is resumed when
     * generation is enabled and available, and otherwise ends {@link Outcome#CANCELLED} with its charge given back.
     *
     * @return the totals
     * @throws DataAccessException when storage fails; rows already settled stay settled
     */
    public Recovery recover() {
        int resumed = 0;
        int interrupted = 0;
        int stale = 0;
        int cancelled = 0;
        var rows = jdbc.query("select j.id, j.status, j.runner_id, j.recovery_generation, c.recovery_generation, "
                + "j.adventure_id, j.campaign_id, j.category, j.subject, j.appearance_revision, j.appearance, "
                + "j.source_revision, j.content_revision_id from runtime_image_job j left join campaign c on c.id = "
                + "j.campaign_id and c.adventure_id = j.adventure_id where j.status in " + OPEN + " order by j.ordinal",
                (rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getLong(4), (Long) rs.getObject(5), new Request(rs.getObject(6, UUID.class),
                                rs.getObject(7, UUID.class), AssetCategory.fromWire(rs.getString(8)), rs.getString(9),
                                rs.getLong(10), rs.getString(11)), new Snapshot(rs.getLong(12),
                                rs.getObject(13, UUID.class), rs.getLong(4))));
        Instant now = clock.instant();
        for (Open row : rows) {
            boolean running = row.status().equals("RUNNING");
            synchronized (this) {
                if (byId.containsKey(row.id()) || (running && runner.equals(row.runner()))) {
                    continue;
                }
            }
            if (row.campaignGeneration() == null || row.campaignGeneration() != row.generation()) {
                stale += end(row.id(), Outcome.STALE_GENERATION, null, !running, now);
            } else if (running) {
                interrupted += end(row.id(), Outcome.FAILED, INTERRUPTED, false, now);
            } else if (capability() == Capability.ENABLED) {
                synchronized (this) {
                    Job job = new Job(row.id(), row.request(), row.snapshot());
                    if (!closed && active.putIfAbsent(job.key(), job) == null) {
                        byId.put(job.id, job);
                        queue.addLast(job);
                        resumed++;
                    }
                }
            } else {
                cancelled += end(row.id(), Outcome.CANCELLED, null, true, now);
            }
        }
        dispatch();
        return new Recovery(resumed, interrupted, stale, cancelled);
    }

    /** End one open row this instance does not hold; returns 1 when it changed. */
    private int end(UUID id, Outcome outcome, String code, boolean refund, Instant now) {
        return jdbc.update("update runtime_image_job set status = ?, error_code = ?, charged_at = case when ? then null "
                + "else charged_at end, settled_at = ? where id = ? and status in " + OPEN, outcome.name(), code, refund,
                at(now), id);
    }

    /**
     * Stop sending: tickets of queued and running jobs complete {@link Outcome#FAILED} {@link #SHUTDOWN} and running
     * jobs are cancelled on the port, but their rows stay open, so the next start resumes the queued ones and ends the
     * running ones as {@link #INTERRUPTED}. Later requests are UNAVAILABLE.
     */
    @Override
    public void close() {
        List<Job> held;
        List<ImageHandle> running = new ArrayList<>();
        synchronized (this) {
            closed = true;
            held = new ArrayList<>(byId.values());
            for (Job job : held) {
                job.cancelRequested = true;
                if (job.handle != null) {
                    running.add(job.handle);
                }
            }
            held.forEach(this::detach);
        }
        Settled shutdown = new Settled(Outcome.FAILED, null, SHUTDOWN);
        held.forEach(job -> job.settled.complete(shutdown));
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

    /** Re-check the subject, claim the row, then submit; runs without the lock because it reads storage and calls the port. */
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
            cancelledBeforeSending = job.cancelRequested;
        }
        if (cancelledBeforeSending) {
            finish(job, new Settled(Outcome.CANCELLED, null, null), true);
            return;
        }
        int claimed;
        try {
            claimed = jdbc.update("update runtime_image_job set status = 'RUNNING', runner_id = ?, sent_at = ? "
                    + "where id = ? and status = 'QUEUED'", runner, at(clock.instant()), job.id);
        } catch (DataAccessException failure) {
            finish(job, new Settled(Outcome.FAILED, null, STORAGE), true);
            return;
        }
        if (claimed == 0) {
            // The row ended or was sent elsewhere (another instance's recovery); this instance must not send it.
            release(job, new Settled(Outcome.FAILED, null, ELSEWHERE));
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
            settle(sent, result);
        });
    }

    /**
     * End a sent job from its terminal port result: a completed image that passes the review is published in the same
     * transaction that ends the job row, and only while this instance still holds that row.
     */
    private void settle(Job job, ImageResult result) {
        boolean cancelled;
        synchronized (this) {
            cancelled = job.cancelRequested;
        }
        if (result.status() == ImageResult.Status.CANCELLED || (cancelled && result.status() == ImageResult.Status.COMPLETED)) {
            finish(job, new Settled(Outcome.CANCELLED, null, null), false);
            return;
        }
        if (result.status() == ImageResult.Status.FAILED) {
            finish(job, new Settled(Outcome.FAILED, null, code(result.errorCode())), false);
            return;
        }
        Optional<String> refusal = review(imageRequest(job.request), result);
        if (refusal.isPresent()) {
            LOG.log(System.Logger.Level.WARNING, "Image job {0} failed the acceptance review: {1}", job.id,
                    refusal.get());
            finish(job, new Settled(Outcome.FAILED, null, REVIEW_REJECTED), false);
            return;
        }
        Request request = job.request;
        String credit = credit(result.provenance());
        Settled settled;
        try {
            settled = transaction.execute(status -> {
                if (jdbc.query("select 1 from runtime_image_job where id = ? and status = 'RUNNING' and runner_id = ? "
                        + "for update", (rs, n) -> 1, job.id, runner).isEmpty()) {
                    return null;
                }
                var published = assets.publish(request.campaign(), new ManagedAssets.Candidate(request.category(),
                        request.subject(), request.appearanceRevision(), job.snapshot.stateRevision(),
                        job.snapshot.contentRevision(), credit, result.png()), job.snapshot.generation());
                Outcome outcome = Outcome.valueOf(published.status().name());
                boolean accepted = outcome == Outcome.ACCEPTED;
                jdbc.update("update runtime_image_job set status = ?, settled_at = ?, sha256 = ?, provenance = "
                        + "cast(? as jsonb) where id = ?", outcome.name(), at(clock.instant()),
                        accepted ? published.current().sha256() : null,
                        accepted ? provenance(result.provenance(), credit) : null, job.id);
                return new Settled(outcome, published.current(), null);
            });
        } catch (IllegalArgumentException rejected) {
            // Includes PackException (MEDIA_TYPE, MEDIA_LIMIT): the bytes were not admissible media.
            finish(job, new Settled(Outcome.FAILED, null, REJECTED), false);
            return;
        } catch (DataAccessException failure) {
            finish(job, new Settled(Outcome.FAILED, null, STORAGE), false);
            return;
        }
        if (settled == null) {
            LOG.log(System.Logger.Level.INFO, "Image job {0} returned after its row ended; the image was dropped", job.id);
            release(job, new Settled(Outcome.FAILED, null, LATE));
            return;
        }
        release(job, settled);
        if (settled.outcome() == Outcome.ACCEPTED && projection != null) {
            try {
                projection.generated(request.adventure(), request.campaign(), request.category().wire(),
                        settled.current().sha256());
            } catch (RuntimeException failure) {
                // The database keeps the accepted image; the next start writes the missing file.
                LOG.log(System.Logger.Level.WARNING, "Image job {0} could not write its managed-folder copy: {1}",
                        job.id, failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * The acceptance review every completed image passes before it may be stored ({@value #REVIEW}). It checks that
     * the provenance describes exactly these bytes and exactly the request sent (size, seed, steps, guidance and both
     * prompt hashes), that the bytes decode to a picture of the requested size, and that the picture is not one flat
     * colour, as a backend's blank or filtered output is. It does not judge what the picture shows.
     *
     * @return empty when the image passes, else the name of the first failed check (for logs only)
     */
    static Optional<String> review(ImageRequest sent, ImageResult result) {
        ImageProvenance provenance = result.provenance();
        byte[] png = result.png();
        if (!hex(png).equals(provenance.outputSha256()) || provenance.outputBytes() != png.length) {
            return Optional.of("output-hash");
        }
        if (provenance.width() != sent.width() || provenance.height() != sent.height()
                || provenance.seed() != sent.seed() || provenance.steps() != sent.steps()
                || Double.compare(provenance.guidance(), sent.guidance()) != 0) {
            return Optional.of("settings");
        }
        if (!hex(sent.prompt().getBytes(StandardCharsets.UTF_8)).equals(provenance.promptSha256())
                || !hex(sent.negativePrompt().getBytes(StandardCharsets.UTF_8)).equals(provenance.negativePromptSha256())) {
            return Optional.of("prompt");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException undecodable) {
            return Optional.of("decode");
        }
        if (image == null) {
            return Optional.of("decode");
        }
        if (image.getWidth() != sent.width() || image.getHeight() != sent.height()) {
            return Optional.of("pixels");
        }
        int first = image.getRGB(0, 0);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != first) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of("uniform");
    }

    /**
     * The credit line of a generated image: {@value #CREDIT} plus the backend that made it and, when the server
     * reported one, the first twelve characters of its checkpoint hash. Nothing the backend reported is invented here:
     * a backend name outside plain characters reads {@code image backend}, and a hash outside lowercase hexadecimal is
     * left out.
     */
    static String credit(ImageProvenance provenance) {
        String backend = BACKEND.matcher(provenance.backend()).matches() ? provenance.backend() : "image backend";
        String hash = provenance.serverModelHash();
        return CREDIT + " (" + backend + (hash != null && MODEL_HASH.matcher(hash).matches()
                ? ", model " + hash.substring(0, 12) : "") + ")";
    }

    /** The provenance object stored with an accepted job: what the adapter reported, the review and the credit. */
    private String provenance(ImageProvenance provenance, String credit) {
        var record = new LinkedHashMap<String, Object>();
        record.put("backend", provenance.backend());
        record.put("model", provenance.model());
        record.put("serverModelHash", provenance.serverModelHash());
        record.put("sampler", provenance.sampler());
        record.put("seed", provenance.seed());
        record.put("steps", provenance.steps());
        record.put("guidance", provenance.guidance());
        record.put("width", provenance.width());
        record.put("height", provenance.height());
        record.put("promptSha256", provenance.promptSha256());
        record.put("negativePromptSha256", provenance.negativePromptSha256());
        record.put("outputSha256", provenance.outputSha256());
        record.put("outputBytes", provenance.outputBytes());
        record.put("review", Map.of("method", REVIEW, "result", "passed", "reviewedAt",
                clock.instant().toString()));
        record.put("credit", credit);
        return JSON.writeValueAsString(record);
    }

    /** End a job once with its row: detach it, record the outcome, complete its ticket and let the next one start. */
    private void finish(Job job, Settled settled, boolean refund) {
        synchronized (this) {
            if (!detach(job)) {
                return;
            }
        }
        record(job, settled.outcome(), settled.errorCode(), refund, null, null);
        job.settled.complete(settled);
        dispatch();
    }

    /** End a job whose row was already settled (or is not this instance's): no row is written. */
    private void release(Job job, Settled settled) {
        synchronized (this) {
            if (!detach(job)) {
                return;
            }
        }
        job.settled.complete(settled);
        dispatch();
    }

    /**
     * Write a job's terminal state, only while the row is queued or held by this instance; a storage failure is
     * logged and leaves the row for the next start's {@link #recover()}.
     */
    private void record(Job job, Outcome outcome, String code, boolean refund, String sha256, String provenance) {
        try {
            jdbc.update("update runtime_image_job set status = ?, error_code = ?, charged_at = case when ? then null "
                    + "else charged_at end, settled_at = ?, sha256 = ?, provenance = cast(? as jsonb) where id = ? and "
                    + "(status = 'QUEUED' or (status = 'RUNNING' and runner_id = ?))", outcome.name(),
                    outcome == Outcome.FAILED ? code(code) : null, refund, at(clock.instant()), sha256, provenance,
                    job.id, runner);
        } catch (DataAccessException failure) {
            LOG.log(System.Logger.Level.WARNING, "Image job {0} could not record its end: {1}", job.id,
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Remove a job from every in-memory structure under the lock.
     *
     * @return false when the job had already been detached
     */
    private boolean detach(Job job) {
        if (byId.remove(job.id) == null) {
            return false;
        }
        active.remove(job.key(), job);
        if (job.running) {
            busy.remove(job.request.campaign());
        } else {
            queue.remove(job);
        }
        return true;
    }

    /** Charges of the campaign whose instant lies inside the window ending now. */
    private int charged(UUID campaign, Instant now) {
        return jdbc.queryForObject("select count(*) from runtime_image_job where campaign_id = ? and charged_at > ?",
                Integer.class, campaign, at(now.minus(settings.window())));
    }

    /** A plain lowercase code, or {@link #FAILED_CODE} for anything else. */
    static String code(String code) {
        return code != null && CODE.matcher(code).matches() ? code : FAILED_CODE;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
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

    /** What {@link #decide} concluded and whether it superseded the older queued jobs it was given. */
    private record Decision(Ticket ticket, boolean supersededOlder) {
    }

    /** One open row read by {@link #recover()}; {@code campaignGeneration} is null when the campaign is gone. */
    private record Open(UUID id, String status, UUID runner, long generation, Long campaignGeneration, Request request,
            Snapshot snapshot) {
    }

    /** One queued or running job this instance holds; mutable fields are guarded by the service's lock. */
    private static final class Job {
        final UUID id;
        final Request request;
        final Snapshot snapshot;
        final CompletableFuture<Settled> settled = new CompletableFuture<>();
        boolean running;
        boolean cancelRequested;
        ImageHandle handle;

        Job(UUID id, Request request, Snapshot snapshot) {
            this.id = id;
            this.request = request;
            this.snapshot = snapshot;
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
