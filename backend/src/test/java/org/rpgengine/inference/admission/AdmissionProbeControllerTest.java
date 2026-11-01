package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.rpgengine.inference.admission.AdmissionFixtures.AGING;
import static org.rpgengine.inference.admission.AdmissionFixtures.CAPACITY;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.TTL;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rpgengine.identity.LocalHttpAdmission;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.TextResidency;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * The owner-only probe route under the opt-in switch. One test runs both fixed requests through the real
 * {@link TextResidencyDriver}, {@link LeaseExecutor}, {@link AdmissionCoordinator} and {@link LeasedImageAdmission},
 * with scripted workers, a scripted free-memory reading and an in-memory lease store; the scheduler is a stand-in
 * that claims residency and ends its task when the claim is ready. No GPU, model server, image adapter, database or
 * real scheduler takes part, and nothing here shows how they behave.
 */
class AdmissionProbeControllerTest {
    private static final String PATH = "/api/v1/models/admission-probe";
    private static final String TEXT_MODEL = "text-a";
    private static final String CALLER_TEXT = "caller words";
    private static final AdmissionProbeController.CampaignRef CAMPAIGN = new AdmissionProbeController.CampaignRef(
            UUID.randomUUID(), UUID.randomUUID(), 3);
    private static final Duration LONG = Duration.ofHours(1);
    private static final Duration SHORT = Duration.ofMillis(50);
    private static final Duration BUDGET = Duration.ofSeconds(1);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdmissionProbeController.class)
            .withBean(InferenceScheduler.class, () -> mock(InferenceScheduler.class))
            .withBean(ImageAdmission.class, () -> (adapter, request, ticket) -> null)
            .withBean(AdmissionProbeController.Campaigns.class, () -> () -> Optional.of(CAMPAIGN));
    private final List<AutoCloseable> closers = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closer : closers) {
            closer.close();
        }
    }

    // ---------------------------------------------------------------- stand-ins

    private static MockHttpServletRequest owner() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE, UUID.randomUUID());
        return request;
    }

    private static ObjectProvider<ImageGenerationPort> providing(ImageGenerationPort adapter) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (adapter != null) {
            factory.registerSingleton("port", adapter);
        }
        return factory.getBeanProvider(ImageGenerationPort.class);
    }

    private static ImageGenerationPort port() {
        return new ImageGenerationPort() {
            @Override
            public String backend() {
                return "recording";
            }

            @Override
            public ImageHandle submit(ImageRequest request) {
                throw new AssertionError("only the admission may reach the adapter in this test");
            }
        };
    }

    private static final class StubTask implements TaskHandle {
        final CompletableFuture<TaskResult> answer = new CompletableFuture<>();
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public UUID taskId() {
            return UUID.randomUUID();
        }

        @Override
        public CompletableFuture<TaskResult> result() {
            return answer;
        }

        @Override
        public boolean cancel() {
            cancels.incrementAndGet();
            return true;
        }
    }

    private static final class StubImage implements ImageHandle {
        final CompletableFuture<ImageResult> answer = new CompletableFuture<>();
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public CompletableFuture<ImageResult> result() {
            return answer;
        }

        @Override
        public boolean cancel() {
            cancels.incrementAndGet();
            return true;
        }
    }

    private static InferenceScheduler schedulerAnswering(StubTask task) {
        InferenceScheduler scheduler = mock(InferenceScheduler.class);
        when(scheduler.submit(any())).thenReturn(task);
        return scheduler;
    }

    private static AdmissionProbeController controller(InferenceScheduler scheduler, ImageAdmission images,
            ImageGenerationPort adapter, AdmissionProbeController.Campaigns campaigns) {
        return new AdmissionProbeController(scheduler, images, providing(adapter), campaigns, BUDGET, SHORT, SHORT);
    }

    private Map<?, ?> body(ResponseEntity<?> reply) {
        assertThat(reply.getStatusCode().value()).isEqualTo(200);
        return (Map<?, ?>) reply.getBody();
    }

    private static Map<String, String> fields(String status, String code) {
        return Map.of("status", status, "code", code);
    }

    private static ImageResult picture() {
        return ImageResult.completed(new byte[] {1, 2, 3}, new ImageProvenance("recording", "image-a", null, null, 7, 20,
                6.0, 1024, 1024, "a", "b", "c", 3));
    }

    // ---------------------------------------------------------------- the switch and the owner

    @Test
    void withTheSwitchOnTheControllerExistsAndOnlyThePostRouteIsMapped() throws Exception {
        runner.withPropertyValues("engine.inference.admission.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AdmissionProbeController.class);
        });
        Method method = AdmissionProbeController.class.getMethod("probe", jakarta.servlet.http.HttpServletRequest.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(PATH);
        assertThat(method.isAnnotationPresent(RequestMapping.class)).isFalse();
        // The only input is the request itself, which the method reads for the owner attribute and nothing else.
        assertThat(method.getParameterTypes()).containsExactly(jakarta.servlet.http.HttpServletRequest.class);
        assertThat(method.getParameterAnnotations()[0]).isEmpty();
    }

    @Test
    void withoutTheSwitchNoControllerExists() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AdmissionProbeController.class));
        runner.withPropertyValues("engine.inference.admission.enabled=false").run(context -> assertThat(context)
                .hasNotFailed().doesNotHaveBean(AdmissionProbeController.class));
    }

    @Test
    void aRequestWithoutTheOwnerIsRefusedAndSendsNothing() {
        InferenceScheduler scheduler = mock(InferenceScheduler.class);
        ImageAdmission images = mock(ImageAdmission.class);
        ImageGenerationPort adapter = port();
        AdmissionProbeController controller = controller(scheduler, images, adapter, () -> Optional.of(CAMPAIGN));

        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", PATH);
        ResponseEntity<?> reply = controller.probe(anonymous);
        assertThat(reply.getStatusCode().value()).isEqualTo(403);
        assertThat(reply.getBody()).isEqualTo(Map.of("code", "LOCAL_ADMISSION_DENIED"));
        anonymous.setAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE, "not-a-uuid");
        assertThat(controller.probe(anonymous).getStatusCode().value()).isEqualTo(403);

        verifyNoInteractions(scheduler, images);
    }

    // ---------------------------------------------------------------- both requests through the executor

    @Test
    void anOwnerRequestSendsTheFixedTextAndImageRequestsThroughTheLeaseExecutor() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        SteppedClock clock = new SteppedClock(T0);
        MemoryLeaseStore store = new MemoryLeaseStore();
        AdmissionCoordinator coordinator = new AdmissionCoordinator(
                new AdmissionSettings(CAPACITY, TTL, AGING, Map.of(Modality.TEXT, 1, Modality.IMAGE, 4, Modality.SPEECH, 4)),
                store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        ScriptedWorker textServer = new ScriptedWorker(event -> events.add("text " + event));
        ScriptedWorker imageServer = new ScriptedWorker(event -> events.add("image " + event));
        LeaseExecutor executor = new LeaseExecutor(coordinator, Map.of(Modality.TEXT, textServer, Modality.IMAGE, imageServer),
                () -> OptionalLong.of(100), () -> false, new LeaseExecutorSettings(0, 0, 0, 3, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(5)), clock, clock::advance);
        LeasedImageAdmission images = new LeasedImageAdmission(executor, coordinator, LARGE, clock, Duration.ofMillis(2),
                Duration.ofSeconds(5), Duration.ofSeconds(20));
        TextResidencyDriver driver = new TextResidencyDriver(executor, coordinator, slot -> Optional.of(TEXT_MODEL), LARGE,
                clock, Duration.ofHours(1), images);
        closers.add(images::close);
        closers.add(driver::stop);
        List<ImageRequest> sentImages = Collections.synchronizedList(new ArrayList<>());
        ImageGenerationPort adapter = new ImageGenerationPort() {
            @Override
            public String backend() {
                return "recording";
            }

            @Override
            public ImageHandle submit(ImageRequest request) {
                events.add("image submit");
                sentImages.add(request);
                StubImage handle = new StubImage();
                handle.answer.complete(picture());
                return handle;
            }
        };
        // The scheduler stand-in claims residency like the real one and ends its task as soon as the claim is ready.
        List<ScheduledTask> sentTasks = Collections.synchronizedList(new ArrayList<>());
        InferenceScheduler scheduler = mock(InferenceScheduler.class);
        when(scheduler.submit(any())).thenAnswer(call -> {
            ScheduledTask task = call.getArgument(0);
            sentTasks.add(task);
            StubTask handle = new StubTask();
            CompletableFuture<TextResidency.Claim> claim = new CompletableFuture<>();
            claim.complete(driver.claim(task, new TextResidency.Listener() {
                @Override
                public void ready() {
                    events.add("text ready");
                    handle.answer.complete(new TaskResult(task.taskId(), TaskResult.Status.COMPLETED, "{}", null, null, false));
                    claim.thenAccept(held -> held.release(false));
                }

                @Override
                public void refused(String code) {
                    handle.answer.complete(TaskResult.withoutTurn(task.taskId(), TaskResult.Status.UNAVAILABLE, code));
                }

                @Override
                public void mustStop() {
                    // The stand-in's task has already ended when this could matter.
                }
            }));
            return handle;
        });
        AdmissionProbeController controller = new AdmissionProbeController(scheduler, images, providing(adapter),
                () -> Optional.of(CAMPAIGN), BUDGET, LONG, LONG);

        CompletableFuture<ResponseEntity<?>> reply = CompletableFuture.supplyAsync(() -> controller.probe(owner()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!reply.isDone()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the probe did not end");
            }
            // The two requests reach the coordinator in either order. An image granted while the text model is still
            // held is put back and asks again after the executor's recheck pause, which only the stepped clock passes.
            clock.advance(Duration.ofSeconds(10));
            driver.tickNow();
            Thread.sleep(5);
        }

        assertThat(body(reply.get())).isEqualTo(Map.of("text", fields("COMPLETED", "none"),
                "image", fields("COMPLETED", "none")));
        // Both requests reached their servers through the executor's leases, the text model first.
        assertThat(events).containsSubsequence("text worker load " + TEXT_MODEL, "text ready", "text worker unload " + TEXT_MODEL,
                "image worker load " + LeasedImageAdmission.CURRENT_CHECKPOINT, "image submit");
        // A delayed image is granted again at each recheck, so there may be several image rows but one text row.
        assertThat(store.rows.values()).extracting(LeaseRecord::modality).contains(Modality.TEXT, Modality.IMAGE)
                .containsOnly(Modality.TEXT, Modality.IMAGE);
        assertThat(store.rows.values().stream().filter(row -> row.modality() == Modality.TEXT).count()).isEqualTo(1);
        assertThat(executor.metrics().get("leases_active")).isZero();

        // The task and the image are the fixed ones, in an isolated line of the campaign.
        assertThat(sentTasks).singleElement().satisfies(task -> {
            assertThat(task.scope().campaignId()).isEqualTo(CAMPAIGN.campaignId());
            assertThat(task.scope().adventureId()).isEqualTo(CAMPAIGN.adventureId());
            assertThat(task.scope().campaignGeneration()).isEqualTo(3);
            assertThat(task.scope().role()).isEqualTo(AiRole.TACTICIAN);
            assertThat(task.scope().audience()).isEqualTo(Audience.character("admission-probe"));
            assertThat(task.workClass()).isEqualTo(WorkClass.INTERACTIVE);
            assertThat(task.slot()).isEqualTo(InferenceSlot.LIGHT);
            assertThat(task.instructions()).isEqualTo("Reply with a JSON object whose field ok is true.");
            assertThat(task.timeout()).isEqualTo(BUDGET);
        });
        assertThat(sentImages).containsExactly(new ImageRequest("a plain grey square", "", null, ImageRequest.MIN_SIDE,
                ImageRequest.MIN_SIDE, 1, 1.0, 0, null));
    }

    @Test
    void noCallerTextReachesEitherRequestOrTheReply() throws Exception {
        StubTask task = new StubTask();
        task.answer.complete(new TaskResult(UUID.randomUUID(), TaskResult.Status.COMPLETED, CALLER_TEXT, null, null, false));
        InferenceScheduler scheduler = schedulerAnswering(task);
        AtomicReference<ImageRequest> sent = new AtomicReference<>();
        AtomicReference<ImageAdmission.Ticket> ticket = new AtomicReference<>();
        ImageAdmission images = (adapter, request, given) -> {
            sent.set(request);
            ticket.set(given);
            StubImage handle = new StubImage();
            handle.answer.complete(picture());
            return handle;
        };
        AdmissionProbeController controller = controller(scheduler, images, port(), () -> Optional.of(CAMPAIGN));
        MockHttpServletRequest request = owner();
        request.addParameter("prompt", CALLER_TEXT);
        request.addParameter("instructions", CALLER_TEXT);
        request.addHeader("X-Prompt", CALLER_TEXT);
        request.setContent(CALLER_TEXT.getBytes());

        ResponseEntity<?> reply = controller.probe(request);

        // The task's own output is not echoed either.
        assertThat(json.writeValueAsString(body(reply))).doesNotContain(CALLER_TEXT);
        ArgumentCaptor<ScheduledTask> captured = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(scheduler).submit(captured.capture());
        assertThat(captured.getValue().instructions()).doesNotContain(CALLER_TEXT);
        assertThat(captured.getValue().outputSchema()).doesNotContain(CALLER_TEXT);
        assertThat(sent.get().prompt()).doesNotContain(CALLER_TEXT);
        assertThat(sent.get().negativePrompt()).doesNotContain(CALLER_TEXT);
        assertThat(ticket.get().maxWait()).isEqualTo(SHORT);
        assertThat(ticket.get().workClass()).isEqualTo(WorkClass.PREPARATION);
    }

    // ---------------------------------------------------------------- failures end with fixed codes

    @Test
    void withoutAnImagePortTheImageRequestIsRefusedWithAFixedCodeAndTheTextRequestStillRuns() throws Exception {
        StubTask task = new StubTask();
        task.answer.complete(new TaskResult(UUID.randomUUID(), TaskResult.Status.COMPLETED, "{}", null, null, false));
        ImageAdmission images = mock(ImageAdmission.class);
        AdmissionProbeController controller = controller(schedulerAnswering(task), images, null, () -> Optional.of(CAMPAIGN));

        assertThat(body(controller.probe(owner()))).isEqualTo(Map.of("text", fields("COMPLETED", "none"),
                "image", fields("UNAVAILABLE", "no-image-port")));
        verifyNoInteractions(images);
    }

    @Test
    void aFailedImageAndARefusedTextTaskReportTheirFixedCodes() throws Exception {
        StubTask task = new StubTask();
        task.answer.complete(TaskResult.withoutTurn(UUID.randomUUID(), TaskResult.Status.REJECTED, "queue-full"));
        ImageAdmission images = (adapter, request, ticket) -> {
            StubImage handle = new StubImage();
            handle.answer.complete(ImageResult.failed(ImageAdmission.LOAD_FAILED));
            return handle;
        };
        AdmissionProbeController controller = controller(schedulerAnswering(task), images, port(), () -> Optional.of(CAMPAIGN));

        assertThat(body(controller.probe(owner()))).isEqualTo(Map.of("text", fields("REJECTED", "queue-full"),
                "image", fields("FAILED", "image-admission-load-failed")));
    }

    @Test
    void aCodeOutsideTheFixedSyntaxIsReplacedSoNoTextLeaks() throws Exception {
        StubTask task = new StubTask();
        task.answer.complete(new TaskResult(UUID.randomUUID(), TaskResult.Status.COMPLETED, "{}", null, null, false));
        ImageAdmission images = (adapter, request, ticket) -> {
            StubImage handle = new StubImage();
            handle.answer.complete(ImageResult.failed("http://host:1/" + CALLER_TEXT));
            return handle;
        };
        AdmissionProbeController controller = controller(schedulerAnswering(task), images, port(), () -> Optional.of(CAMPAIGN));

        Map<?, ?> reply = body(controller.probe(owner()));
        assertThat(reply.get("image")).isEqualTo(fields("FAILED", "unknown-code"));
        assertThat(json.writeValueAsString(reply)).doesNotContain(CALLER_TEXT).doesNotContain("http");
    }

    @Test
    void anInstallWithoutACampaignRefusesTheTextRequestWithoutSubmittingIt() throws Exception {
        InferenceScheduler scheduler = mock(InferenceScheduler.class);
        ImageAdmission images = (adapter, request, ticket) -> {
            StubImage handle = new StubImage();
            handle.answer.complete(picture());
            return handle;
        };
        AdmissionProbeController controller = controller(scheduler, images, port(), Optional::empty);

        assertThat(body(controller.probe(owner()))).isEqualTo(Map.of("text", fields("UNAVAILABLE", "no-campaign"),
                "image", fields("COMPLETED", "none")));
        verify(scheduler, never()).submit(any());
    }

    @Test
    void aFailureWhileSubmittingIsAFixedCodeAndNotAnException() throws Exception {
        InferenceScheduler scheduler = mock(InferenceScheduler.class);
        when(scheduler.submit(any())).thenThrow(new IllegalStateException(CALLER_TEXT));
        ImageAdmission images = (adapter, request, ticket) -> {
            throw new IllegalStateException(CALLER_TEXT);
        };
        AdmissionProbeController controller = controller(scheduler, images, port(), () -> Optional.of(CAMPAIGN));

        Map<?, ?> reply = body(controller.probe(owner()));
        assertThat(reply).isEqualTo(Map.of("text", fields("UNAVAILABLE", "submit-failed"),
                "image", fields("UNAVAILABLE", "submit-failed")));
        assertThat(json.writeValueAsString(reply)).doesNotContain(CALLER_TEXT);
    }

    @Test
    void aRequestThatNeverEndsIsCancelledWhenItsWaitIsOverAndTheCallDoesNotHang() throws Exception {
        StubTask task = new StubTask();
        StubImage image = new StubImage();
        AdmissionProbeController controller = controller(schedulerAnswering(task), (adapter, request, ticket) -> image,
                port(), () -> Optional.of(CAMPAIGN));

        long started = System.nanoTime();
        Map<?, ?> reply = body(controller.probe(owner()));

        assertThat(reply).isEqualTo(Map.of("text", fields("UNAVAILABLE", "wait-expired"),
                "image", fields("UNAVAILABLE", "wait-expired")));
        assertThat(task.cancels).hasValue(1);
        assertThat(image.cancels).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aSecondProbeWhileOneRunsIsRefusedBusy() throws Exception {
        StubTask task = new StubTask();
        StubImage image = new StubImage();
        InferenceScheduler scheduler = schedulerAnswering(task);
        // The first probe waits for its text request for the whole budget plus slack; the second arrives meanwhile.
        AdmissionProbeController controller = new AdmissionProbeController(scheduler, (adapter, request, ticket) -> image,
                providing(port()), () -> Optional.of(CAMPAIGN), BUDGET, SHORT, Duration.ofSeconds(2));

        CompletableFuture<ResponseEntity<?>> first = CompletableFuture.supplyAsync(() -> controller.probe(owner()));
        verify(scheduler, org.mockito.Mockito.timeout(5000)).submit(any());
        ResponseEntity<?> second = controller.probe(owner());

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).isEqualTo(Map.of("code", "PROBE_BUSY"));
        assertThat(body(first.get(20, TimeUnit.SECONDS)).get("text")).isEqualTo(fields("UNAVAILABLE", "wait-expired"));
        // Once the first ended, the next probe runs again.
        task.answer.complete(TaskResult.withoutTurn(UUID.randomUUID(), TaskResult.Status.REJECTED, "queue-full"));
        image.answer.complete(picture());
        assertThat(controller.probe(owner()).getStatusCode().value()).isEqualTo(200);
    }
}
