package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.TextResidency;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.SessionScope;
import org.springframework.mock.env.MockEnvironment;

/**
 * The reading window on its own, and through the production wiring: the options, the coordinator and the executor come
 * from {@link AdmissionConfiguration}, the driver and the image path from {@link TextResidencyConfiguration}, and only
 * the window's clock is stepped, so no test waits for it. The servers are {@link ScriptedWorker}s and the card a scripted
 * reading, so this shows the rule and nothing about a real player, model or card.
 */
class ReadingWindowTest {
    private static final long MIB = 1024L * 1024L;
    private static final Duration LENGTH = Duration.ofSeconds(60);
    private static final String TEXT = "gemma-4-E2B_q4_0-it";
    private static final String CHECKPOINT = "image-a";

    private final SteppedClock clock = new SteppedClock(T0);
    private final ReadingWindow window = new ReadingWindow(clock, LENGTH);
    private final AdmissionConfiguration configuration = new AdmissionConfiguration();
    private final ScriptedWorker textServer = new ScriptedWorker(event -> { });
    private final ScriptedWorker imageServer = new ScriptedWorker(event -> { });
    private LeaseExecutor executor;
    private LeasedImageAdmission images;
    private TextResidencyDriver driver;

    private void build() {
        AdmissionOptions options = AdmissionOptions.from(new MockEnvironment()
                .withProperty("engine.inference.admission.gpu-budget-mib", "8192")
                .withProperty("engine.inference.admission.headroom-mib", "0")
                .withProperty("engine.inference.admission.unmeasured-extra-headroom-mib", "0"));
        AdmissionCoordinator coordinator = configuration.admissionCoordinator(options, new MemoryLeaseStore());
        AdmissionControls controls = new AdmissionControls(Map.of(Modality.TEXT, textServer, Modality.IMAGE, imageServer));
        executor = configuration.leaseExecutor(coordinator, controls, () -> OptionalLong.of(8192 * MIB), options, window);
        TextResidencyConfiguration residency = new TextResidencyConfiguration();
        images = residency.imageAdmission(executor, coordinator, options);
        ProfileResolver profiles = new ProfileResolver(
                slot -> Optional.of(SlotAssignment.of("local", new EffectiveSettings(TEXT, "medium"))),
                ModelAllowlist.empty(), Optional.empty());
        NarrationTaskPort port = mock(NarrationTaskPort.class);
        when(port.listsModel("local", TEXT)).thenReturn(Optional.of(true));
        driver = residency.textResidencyDriver(executor, coordinator, options, profiles, port, images, window);
    }

    @AfterEach
    void stop() {
        if (driver != null) {
            driver.stop();
            images.close();
        }
    }

    private void finishATurn() throws Exception {
        var task = new ScheduledTask(UUID.randomUUID(), new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0,
                AiRole.NARRATOR, Audience.PUBLIC, "a".repeat(64), "b".repeat(64)), WorkClass.INTERACTIVE,
                InferenceSlot.STANDARD, "Task.", "{\"type\":\"object\"}", Duration.ofSeconds(60));
        List<String> ready = new CopyOnWriteArrayList<>();
        TextResidency.Claim claim = driver.claim(task, new TextResidency.Listener() {
            @Override
            public void ready() {
                ready.add("ready");
            }

            @Override
            public void refused(String code) {
                ready.add("refused " + code);
            }

            @Override
            public void mustStop() {
                ready.add("stop");
            }
        });
        driver.awaitIdle();
        assertThat(ready).containsExactly("ready");
        claim.release(false);
        driver.awaitIdle();
    }

    private ImageResult imageResult() throws Exception {
        ImageGenerationPort port = new ImageGenerationPort() {
            @Override
            public String backend() {
                return "recording";
            }

            @Override
            public ImageHandle submit(ImageRequest request) {
                CompletableFuture<ImageResult> answer = CompletableFuture.completedFuture(ImageResult.completed(
                        new byte[] {1, 2, 3}, new ImageProvenance("recording", CHECKPOINT, null, null, 7, 20, 6.0, 1024,
                                1024, "a", "b", "c", 3)));
                return new ImageHandle() {
                    @Override
                    public CompletableFuture<ImageResult> result() {
                        return answer;
                    }

                    @Override
                    public boolean cancel() {
                        return false;
                    }
                };
            }
        };
        ImageRequest request = new ImageRequest("a castle", "", CHECKPOINT, 1024, 1024, 20, 6.0, 7, null);
        ImageHandle handle = images.submit(port, request,
                new ImageAdmission.Ticket(WorkClass.PREPARATION, UUID.randomUUID(), Duration.ZERO));
        driver.tickNow();
        return handle.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- the window

    @Test
    void itIsClosedUntilATurnEndsOpenWithinItsLengthAndClosedAtItsEnd() {
        assertThat(window.getAsBoolean()).as("before any turn").isFalse();

        window.turnEnded();
        assertThat(window.getAsBoolean()).as("as the turn ends").isTrue();

        clock.advance(LENGTH.minusMillis(1));
        assertThat(window.getAsBoolean()).as("just inside").isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(window.getAsBoolean()).as("at its end").isFalse();
    }

    @Test
    void aLaterTurnRestartsIt() {
        window.turnEnded();
        clock.advance(LENGTH.minusSeconds(1));
        window.turnEnded();
        clock.advance(LENGTH.minusSeconds(1));
        assertThat(window.getAsBoolean()).isTrue();
    }

    @Test
    void aZeroLengthIsNeverOpen() {
        ReadingWindow off = new ReadingWindow(clock, Duration.ZERO);
        off.turnEnded();
        assertThat(off.getAsBoolean()).isFalse();
    }

    @Test
    void aClockSetBackDoesNotKeepItOpen() {
        window.turnEnded();
        clock.advance(Duration.ofSeconds(-1));
        assertThat(window.getAsBoolean()).isFalse();
    }

    @Test
    void aNegativeLengthIsRefused() {
        assertThatThrownBy(() -> new ReadingWindow(clock, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- the executor, through the production wiring

    @Test
    void anImageBatchWaitsWhileThePlayerReadsAndRunsOnceTheWindowCloses() throws Exception {
        build();
        driver.start();
        driver.awaitIdle();
        finishATurn();
        assertThat(textServer.holds(TEXT)).isTrue();

        ImageResult deferred = imageResult();
        assertThat(deferred.errorCode()).isEqualTo(ImageAdmission.DEFERRED);
        assertThat(ImageAdmission.usesCatalogArt(deferred)).isTrue();
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
        assertThat(textServer.holds(TEXT)).as("the next turn finds its model").isTrue();
        assertThat(executor.metrics()).containsEntry("deferrals", 1L);

        clock.advance(LENGTH);
        ImageResult done = imageResult();
        assertThat(done.errorCode()).isNull();
        assertThat(ImageAdmission.usesCatalogArt(done)).isFalse();
    }

    @Test
    void anImageBatchIsNotDeferredBeforeAnyTurnEnded() throws Exception {
        build();
        driver.start();
        driver.awaitIdle();

        ImageResult done = imageResult();

        assertThat(done.errorCode()).isNull();
        assertThat(executor.metrics()).containsEntry("deferrals", 0L);
    }
}
