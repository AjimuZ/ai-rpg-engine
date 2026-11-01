package org.rpgengine.inference.admission;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.identity.LocalHttpAdmission;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.SessionScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-only entry point that sends one fixed text request and one fixed image request through the admission path:
 * {@code POST /api/v1/models/admission-probe}. The route exists only while {@code engine.inference.admission.enabled}
 * is {@code true}; with the switch off no bean is built and the path has no mapping. The path sits under
 * {@code /api/v1/models/} so that {@link LocalHttpAdmission} admits the owner (session, origin and CSRF proof) and
 * refuses everyone else before this class runs; a request without the owner attribute is refused here as well.
 *
 * <p>The text request goes through {@link InferenceScheduler#submit}, so it takes the turn permit and claims
 * residency. The image request goes through {@link ImageAdmission#submit} with the image port that exists beside the
 * switch. The route reads no body, parameter or header: both prompts are constants of this class, so no caller text
 * reaches a model.
 *
 * <p>The reply holds only fixed fields: for each request a {@code status} (an enum name or {@value #UNAVAILABLE})
 * and a {@code code} (a lowercase fixed code or {@value #NO_CODE}). Output, images, prompts, model names and endpoints
 * never appear, and a code outside the fixed-code syntax is replaced by {@value #UNKNOWN_CODE}.
 *
 * <p>Waits are bounded: a request that has not ended when its wait is over is cancelled and reported
 * {@value #WAIT_EXPIRED}. One probe runs at a time; another is refused with 409 {@code PROBE_BUSY}. The text request
 * needs a campaign for the scheduler's ledger (see {@link Campaigns}). It runs in an isolated role and audience line
 * of that campaign, so it never joins a player-facing conversation, and it leaves one ledger row of a task whose
 * instructions are never stored.
 */
@RestController
@Conditional(AdmissionConfiguration.Enabled.class)
public class AdmissionProbeController {
    /** The text request's code when the install has no campaign to scope the scheduler's ledger row. */
    static final String NO_CAMPAIGN = "no-campaign";
    /** The image request's code when no image port exists, for example without {@code engine.inference.image-worker.url}. */
    static final String NO_IMAGE_PORT = "no-image-port";
    /** A request that had not ended when its wait was over; it was cancelled. */
    static final String WAIT_EXPIRED = "wait-expired";
    /** A request that could not be handed over. */
    static final String SUBMIT_FAILED = "submit-failed";
    /** Stands for a code outside the fixed-code syntax. */
    static final String UNKNOWN_CODE = "unknown-code";
    /** The {@code code} of a request that ended without one. */
    static final String NO_CODE = "none";
    /** Status of a request that never reached a port or whose wait expired. */
    static final String UNAVAILABLE = "UNAVAILABLE";

    /** Placeholder, not measured: the text task's whole budget, queue and residency wait included. */
    static final Duration TEXT_BUDGET = Duration.ofSeconds(30);
    /** Placeholder, not measured: how long the image job may wait for its model before the fallback code. */
    static final Duration IMAGE_WAIT = Duration.ofSeconds(30);
    /** Placeholder, not measured: slack after a request's own deadline before this class stops waiting. */
    static final Duration SLACK = Duration.ofSeconds(10);

    private static final String TEXT_INSTRUCTIONS = "Reply with a JSON object whose field ok is true.";
    private static final String TEXT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
            + "\"required\":[\"ok\"],\"additionalProperties\":false}";
    private static final String PROBE_SUBJECT = "admission-probe";
    private static final String ZERO_HASH = "0".repeat(64);
    private static final Pattern FIXED_CODE = Pattern.compile("[a-z][a-z0-9-]{0,63}|[a-z][A-Za-z0-9]{0,63}");
    private static final System.Logger LOG = System.getLogger(AdmissionProbeController.class.getName());

    /** A campaign of the install, as the scheduler's ledger needs it to accept a task. */
    record CampaignRef(UUID adventureId, UUID campaignId, long generation) {
    }

    /** Where the text request finds a campaign for the scheduler's ledger row. */
    @FunctionalInterface
    interface Campaigns {
        /** @return a campaign with its adventure and recovery generation, or empty when the install has none */
        Optional<CampaignRef> first();
    }

    /** One request in flight: its end, how to cancel it, and how long to wait for it. */
    private record Pending(CompletableFuture<Map<String, String>> ended, Runnable cancel, Duration limit) {
        static Pending refused(String code) {
            return new Pending(CompletableFuture.completedFuture(result(UNAVAILABLE, code)), () -> { }, Duration.ZERO);
        }
    }

    private final InferenceScheduler scheduler;
    private final ImageAdmission images;
    private final ObjectProvider<ImageGenerationPort> port;
    private final Campaigns campaigns;
    private final Duration textBudget;
    private final Duration imageWait;
    private final Duration slack;
    private final AtomicBoolean busy = new AtomicBoolean();

    /**
     * @param scheduler the installation's scheduler; a missing bean fails startup
     * @param images the image path behind the switch
     * @param port the image port, if one exists
     * @param campaigns says which campaign scopes the text task
     */
    @Autowired
    AdmissionProbeController(InferenceScheduler scheduler, ImageAdmission images, ObjectProvider<ImageGenerationPort> port,
            Campaigns campaigns) {
        this(scheduler, images, port, campaigns, TEXT_BUDGET, IMAGE_WAIT, SLACK);
    }

    /**
     * Waits injected for tests: {@code textBudget} is the task's budget (at least one second), {@code imageWait} the
     * ticket's wait, and {@code slack} how long past those this class keeps waiting before it cancels.
     */
    AdmissionProbeController(InferenceScheduler scheduler, ImageAdmission images, ObjectProvider<ImageGenerationPort> port,
            Campaigns campaigns, Duration textBudget, Duration imageWait, Duration slack) {
        this.slack = Objects.requireNonNull(slack, "slack");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.images = Objects.requireNonNull(images, "images");
        this.port = Objects.requireNonNull(port, "port");
        this.campaigns = Objects.requireNonNull(campaigns, "campaigns");
        this.textBudget = Objects.requireNonNull(textBudget, "textBudget");
        this.imageWait = Objects.requireNonNull(imageWait, "imageWait");
    }

    /**
     * Send both requests at once and wait for both, each within its own bound.
     *
     * @param request admitted HTTP request
     * @return 200 with {@code {"text": {"status", "code"}, "image": {"status", "code"}}}; 403
     *         {@code LOCAL_ADMISSION_DENIED} without the owner; 409 {@code PROBE_BUSY} while another probe runs
     */
    @PostMapping("/api/v1/models/admission-probe")
    public ResponseEntity<?> probe(HttpServletRequest request) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return ResponseEntity.status(403).body(Map.of("code", "LOCAL_ADMISSION_DENIED"));
        }
        if (!busy.compareAndSet(false, true)) {
            return ResponseEntity.status(409).body(Map.of("code", "PROBE_BUSY"));
        }
        try {
            Pending text = sendText();
            Pending image = sendImage(owner);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", await(text));
            body.put("image", await(image));
            return ResponseEntity.ok(body);
        } finally {
            busy.set(false);
        }
    }

    private Pending sendText() {
        try {
            Optional<CampaignRef> campaign = campaigns.first();
            if (campaign.isEmpty()) {
                return Pending.refused(NO_CAMPAIGN);
            }
            // A role and a character audience nothing else uses, so the task joins no player-facing conversation.
            SessionScope scope = new SessionScope(campaign.get().adventureId(), campaign.get().campaignId(),
                    campaign.get().generation(), AiRole.TACTICIAN, Audience.character(PROBE_SUBJECT), ZERO_HASH,
                    ZERO_HASH);
            TaskHandle handle = scheduler.submit(new ScheduledTask(UUID.randomUUID(), scope, WorkClass.INTERACTIVE,
                    InferenceSlot.LIGHT, TEXT_INSTRUCTIONS, TEXT_SCHEMA, textBudget));
            CompletableFuture<Map<String, String>> ended = new CompletableFuture<>();
            handle.result().whenComplete((outcome, failure) -> ended.complete(outcome == null
                    ? result(UNAVAILABLE, SUBMIT_FAILED) : result(outcome.status().name(), outcome.errorCode())));
            return new Pending(ended, handle::cancel, textBudget.plus(slack));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "admission probe text request failed: " + failure.getClass().getName());
            return Pending.refused(SUBMIT_FAILED);
        }
    }

    private Pending sendImage(UUID owner) {
        try {
            ImageGenerationPort adapter = port.getIfAvailable();
            if (adapter == null) {
                return Pending.refused(NO_IMAGE_PORT);
            }
            // The smallest picture the request bounds allow, with a fixed seed.
            ImageRequest fixed = new ImageRequest("a plain grey square", "", null, ImageRequest.MIN_SIDE,
                    ImageRequest.MIN_SIDE, 1, 1.0, 0, null);
            ImageHandle handle = images.submit(adapter, fixed,
                    new ImageAdmission.Ticket(WorkClass.PREPARATION, owner, imageWait));
            CompletableFuture<Map<String, String>> ended = new CompletableFuture<>();
            handle.result().whenComplete((outcome, failure) -> ended.complete(outcome == null
                    ? result(UNAVAILABLE, SUBMIT_FAILED) : result(outcome.status().name(), outcome.errorCode())));
            return new Pending(ended, handle::cancel, imageWait.plus(slack));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "admission probe image request failed: " + failure.getClass().getName());
            return Pending.refused(SUBMIT_FAILED);
        }
    }

    /** @return the request's fixed fields; a request still open when its wait is over is cancelled and reported expired */
    private static Map<String, String> await(Pending pending) {
        try {
            return pending.ended().get(pending.limit().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            pending.cancel().run();
            return result(UNAVAILABLE, WAIT_EXPIRED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.cancel().run();
            return result(UNAVAILABLE, WAIT_EXPIRED);
        } catch (ExecutionException impossible) {
            return result(UNAVAILABLE, SUBMIT_FAILED);
        }
    }

    private static Map<String, String> result(String status, String code) {
        String fixed = code == null ? NO_CODE : FIXED_CODE.matcher(code).matches() ? code : UNKNOWN_CODE;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", status);
        fields.put("code", fixed);
        return fields;
    }
}
