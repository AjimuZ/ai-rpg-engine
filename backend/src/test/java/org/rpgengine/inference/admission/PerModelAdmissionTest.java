package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.TextResidency;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.SessionScope;
import org.springframework.mock.env.MockEnvironment;

/**
 * Per-model sizes and the extra headroom of an unmeasured model (P21.4-E.1), through the production wiring: the
 * options, the coordinator and the executor come from {@link AdmissionConfiguration}, and the driver and the image
 * path are given the options' size lookup, exactly as {@link TextResidencyConfiguration} does. The card is a scripted
 * free-memory reading and the servers are {@link ScriptedWorker}s, so every figure below is arithmetic on the
 * sizes recorded in P21.2-D and shows nothing about a real card.
 */
class PerModelAdmissionTest {
    private static final long MIB = 1024L * 1024L;
    private static final String DEFAULT_MODEL = "gemma-4-12b-it-qat-q4_0";
    private static final String CHECKPOINT = "image-a";

    /** The documented reference-host combination (docs/34 section 10). */
    private static final String[] REFERENCE_HOST = {
            "engine.inference.admission.enabled=true",
            "engine.inference.admission.gpu-budget-mib=10969",
            "engine.inference.admission.ram-budget-mib=7000",
            "engine.inference.admission.headroom-mib=256"};

    /** Every measured model: router ID, then the GPU and RAM MiB recorded for it. */
    private static final Object[][] MEASURED = {
            {DEFAULT_MODEL, 10_621L, 6_356L},
            {"Qwen3.5-9B-Q4_K_M", 10_251L, 5_749L},
            {"gemma-4-E2B_q4_0-it", 2_658L, 1_993L},
            {"gemma-4-E4B_q4_0-it", 5_333L, 3_367L},
            {"gemma-4-26B_q4_0-it", 10_274L, 5_804L}};

    private final AdmissionConfiguration configuration = new AdmissionConfiguration();
    private final ScriptedWorker textServer = new ScriptedWorker(event -> { });
    private final ScriptedWorker imageServer = new ScriptedWorker(event -> { });
    private volatile long freeMib;
    private AdmissionOptions options;
    private AdmissionCoordinator coordinator;
    private LeaseExecutor executor;
    private LeasedImageAdmission images;
    private TextResidencyDriver driver;
    private Optional<String> served = Optional.of(DEFAULT_MODEL);
    private final RecordingListener listener = new RecordingListener();

    private static final class RecordingListener implements TextResidency.Listener {
        final List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void ready() {
            heard.add("ready");
        }

        @Override
        public void refused(String code) {
            heard.add("refused " + code);
        }

        @Override
        public void mustStop() {
            heard.add("stop");
        }
    }

    private void build(String... settings) {
        MockEnvironment environment = new MockEnvironment();
        for (String setting : settings) {
            int equals = setting.indexOf('=');
            environment.setProperty(setting.substring(0, equals), setting.substring(equals + 1));
        }
        options = AdmissionOptions.from(environment);
        coordinator = configuration.admissionCoordinator(options, new MemoryLeaseStore());
        AdmissionControls controls = new AdmissionControls(Map.of(Modality.TEXT, textServer, Modality.IMAGE, imageServer));
        executor = configuration.leaseExecutor(coordinator, controls, () -> OptionalLong.of(freeMib * MIB), options);
        images = new LeasedImageAdmission(executor, coordinator, model -> options.sizes().demandFor(Modality.IMAGE, model),
                Clock.systemUTC(), Duration.ofMillis(2), Duration.ofSeconds(5), Duration.ofSeconds(20));
        driver = new TextResidencyDriver(executor, coordinator, slot -> served,
                model -> options.sizes().demandFor(Modality.TEXT, model), Clock.systemUTC(), Duration.ofHours(1), images);
    }

    @AfterEach
    void stop() {
        if (driver != null) {
            driver.stop();
            images.close();
        }
    }

    private TextResidency.Claim claim() throws Exception {
        AiRole role = AiRole.NARRATOR;
        var task = new ScheduledTask(UUID.randomUUID(), new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, role,
                Audience.PUBLIC, "a".repeat(64), "b".repeat(64)), WorkClass.INTERACTIVE, InferenceSlot.STANDARD, "Task.",
                "{\"type\":\"object\"}", Duration.ofSeconds(60));
        TextResidency.Claim claim = driver.claim(task, listener);
        driver.awaitIdle();
        return claim;
    }

    // ---------------------------------------------------------------- the lookup

    @Test
    void eachMeasuredModelIsRequestedWithItsOwnRecordedSizeAndNotTheWholeBudget() throws Exception {
        for (Object[] row : MEASURED) {
            String model = (String) row[0];
            Demand recorded = new Demand((long) row[1] * MIB, (long) row[2] * MIB);
            build(REFERENCE_HOST);
            served = Optional.of(model);
            freeMib = 10_969;
            driver.start();
            claim();

            assertThat(listener.heard).as(model).containsExactly("ready");
            assertThat(coordinator.leases()).as(model).singleElement().satisfies(lease -> {
                assertThat(lease.model()).isEqualTo(model);
                assertThat(lease.demand()).isEqualTo(recorded);
                assertThat(lease.demand()).isNotEqualTo(options.capacity());
            });
            assertThat(textServer.holds(model)).as(model).isTrue();
            listener.heard.clear();
            driver.stop();
            images.close();
            textServer.unload(model);
        }
    }

    @Test
    void theRecordedSizeIsThePeakMinusTheReadingBeforeTheLoad() {
        // docs/34 section 7: VRAM before / peak, MiB.
        long[][] vram = {{1_313, 11_934}, {1_271, 11_522}, {1_262, 3_920}, {1_273, 6_606}, {1_272, 11_546}};
        for (int row = 0; row < MEASURED.length; row++) {
            assertThat((long) MEASURED[row][1]).as((String) MEASURED[row][0]).isEqualTo(vram[row][1] - vram[row][0]);
            assertThat(ModelSizes.RECORDED.get((String) MEASURED[row][0]).gpuBytes()).isEqualTo((long) MEASURED[row][1] * MIB);
            assertThat(ModelSizes.RECORDED.get((String) MEASURED[row][0]).ramBytes()).isEqualTo((long) MEASURED[row][2] * MIB);
        }
    }

    @Test
    void aSizeAboveTheGpuBudgetAndAnUnknownIdAreRejected() {
        ModelSizes small = ModelSizes.recorded(new Demand(10_000 * MIB, 7_000 * MIB), new Demand(10_000 * MIB, 7_000 * MIB),
                new Demand(10_000 * MIB, 7_000 * MIB));
        assertThatThrownBy(() -> small.measured(DEFAULT_MODEL)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the budget");
        assertThatThrownBy(() -> small.demandFor(Modality.TEXT, DEFAULT_MODEL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> small.measured("no-such-model")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no recorded size");
        assertThat(small.measured("gemma-4-E4B_q4_0-it")).isEqualTo(new Demand(5_333 * MIB, 3_367 * MIB));

        // A RAM size above the RAM budget is refused the same way.
        ModelSizes lowRam = ModelSizes.recorded(new Demand(12_000 * MIB, 1_000 * MIB), new Demand(MIB, 0), new Demand(MIB, 0));
        assertThatThrownBy(() -> lowRam.measured("gemma-4-E4B_q4_0-it")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ModelSizes(new Demand(MIB, 0), Map.of("bad model", new Demand(1, 0)), new Demand(1, 0),
                new Demand(1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelSizes(new Demand(MIB, 0), Map.of("ok", Demand.NONE), new Demand(1, 0),
                new Demand(1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> small.demandFor(Modality.SPEECH, "voice")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMeasuredModelLargerThanTheOwnersBudgetEndsItsClaimUnavailable() throws Exception {
        build("engine.inference.admission.enabled=true", "engine.inference.admission.gpu-budget-mib=8192");
        freeMib = 12_000;
        driver.start();
        claim();

        assertThat(listener.heard).containsExactly("refused " + TextResidency.UNAVAILABLE);
        assertThat(coordinator.leases()).isEmpty();
        assertThat(textServer.holds(DEFAULT_MODEL)).isFalse();
    }

    @Test
    void anImageCheckpointIsNeverMeasuredAndAnUnlistedTextModelUsesTheFallback() {
        build(REFERENCE_HOST);
        ModelSizes sizes = options.sizes();
        assertThat(sizes.isMeasured(Modality.TEXT, DEFAULT_MODEL)).isTrue();
        assertThat(sizes.isMeasured(Modality.IMAGE, DEFAULT_MODEL)).isFalse();
        assertThat(sizes.isMeasured(Modality.IMAGE, CHECKPOINT)).isFalse();
        assertThat(sizes.isMeasured(Modality.TEXT, "not-measured")).isFalse();
        assertThat(sizes.demandFor(Modality.TEXT, "not-measured")).isEqualTo(options.capacity());
        assertThat(sizes.demandFor(Modality.IMAGE, CHECKPOINT)).isEqualTo(options.capacity());
        assertThat(sizes.demandFor(Modality.IMAGE, "image-current")).isEqualTo(options.capacity());
    }

    // ---------------------------------------------------------------- the reference host

    @Test
    void theDefaultTextModelsColdLoadIsAdmittedUnderTheReferenceHostSettings() throws Exception {
        build(REFERENCE_HOST);
        // 12,282 MiB card, 1,313 MiB in use before the load (docs/34 section 7).
        freeMib = 12_282 - 1_313;
        assertThat(options.capacity().gpuBytes()).isEqualTo(freeMib * MIB);
        // 10,621 MiB (the load) + 256 MiB (headroom) = 10,877 MiB, which 10,969 MiB covers with 92 MiB to spare.
        assertThat(freeMib).isEqualTo(10_621 + 256 + 92);
        driver.start();
        claim();

        assertThat(listener.heard).containsExactly("ready");
        assertThat(textServer.holds(DEFAULT_MODEL)).isTrue();
        assertThat(coordinator.leases()).singleElement().satisfies(lease -> {
            assertThat(lease.demand()).isEqualTo(new Demand(10_621 * MIB, 6_356 * MIB));
            assertThat(lease.demand().fitsIn(options.capacity())).isTrue();
        });
    }

    @Test
    void theSameCardUnderTheDefaultHeadroomWouldNotAdmitIt() throws Exception {
        // The defaults of docs/34 section 10: 1,024 MiB of headroom needs 10,621 + 1,024 = 11,645 MiB free.
        build("engine.inference.admission.enabled=true", "engine.inference.admission.gpu-budget-mib=10969",
                "engine.inference.admission.ram-budget-mib=7000");
        freeMib = 10_969;
        driver.start();
        claim();

        assertThat(listener.heard).isEmpty();
        assertThat(textServer.holds(DEFAULT_MODEL)).isFalse();
        assertThat(executor.metrics()).containsEntry("requests_delayed", 1L);
    }

    @Test
    void theReferenceHostSettingsAdmitTheTwentySixBModelButLeaveLittleSlack() throws Exception {
        build(REFERENCE_HOST);
        served = Optional.of("gemma-4-26B_q4_0-it");
        freeMib = 10_969;
        driver.start();
        claim();

        // 10,274 + 256 = 10,530 MiB of 10,969 MiB: admitted on paper, 439 MiB to spare.
        assertThat(listener.heard).containsExactly("ready");
    }

    // ---------------------------------------------------------------- unmeasured models run alone with more headroom

    @Test
    void anUnmeasuredTextModelTakesTheWholeBudgetSoNothingRunsBesideIt() throws Exception {
        build(REFERENCE_HOST);
        served = Optional.of("not-measured");
        freeMib = 10_969 + 256 + 1_024;
        driver.start();
        claim();
        assertThat(listener.heard).containsExactly("ready");
        assertThat(coordinator.leases()).singleElement().satisfies(lease ->
                assertThat(lease.demand()).isEqualTo(options.capacity()));

        // The smallest measured model, wanted meanwhile, cannot be granted beside it.
        served = Optional.of("gemma-4-E2B_q4_0-it");
        RecordingListener other = new RecordingListener();
        driver.claim(task(), other);
        driver.awaitIdle();
        driver.tickNow();
        assertThat(other.heard).isEmpty();
        assertThat(coordinator.leases()).hasSize(1);
        assertThat(coordinator.waiting()).singleElement().satisfies(waiting ->
                assertThat(waiting.model()).isEqualTo("gemma-4-E2B_q4_0-it"));
        assertThat(textServer.holds("gemma-4-E2B_q4_0-it")).isFalse();
    }

    @Test
    void aMeasuredModelFitsBesideAnotherMeasuredOneOnPaper() throws Exception {
        build(REFERENCE_HOST);
        freeMib = 10_969;
        served = Optional.of("gemma-4-E2B_q4_0-it");
        driver.start();
        claim();
        served = Optional.of("gemma-4-E4B_q4_0-it");
        claim();

        // 2,658 + 5,333 = 7,991 MiB of GPU and 1,993 + 3,367 = 5,360 MiB of RAM, inside 10,969 and 7,000.
        assertThat(coordinator.leases()).extracting(Lease::model)
                .containsExactlyInAnyOrder("gemma-4-E2B_q4_0-it", "gemma-4-E4B_q4_0-it");
    }

    @Test
    void anUnmeasuredTextModelNeedsTheExtraHeadroomBeforeItLoadsAndAMeasuredOneDoesNot() throws Exception {
        String[] settings = {"engine.inference.admission.enabled=true", "engine.inference.admission.gpu-budget-mib=10969",
                "engine.inference.admission.headroom-mib=256", "engine.inference.admission.unmeasured-extra-headroom-mib=512"};
        long needs = 10_969 + 256 + 512;

        build(settings);
        served = Optional.of("not-measured");
        freeMib = needs - 1;
        driver.start();
        claim();
        assertThat(listener.heard).isEmpty();
        assertThat(textServer.holds("not-measured")).isFalse();
        assertThat(executor.metrics()).containsEntry("requests_delayed", 1L);
        driver.stop();
        images.close();

        build(settings);
        served = Optional.of("not-measured");
        freeMib = needs;
        driver.start();
        claim();
        assertThat(listener.heard).containsExactly("ready");
        driver.stop();
        images.close();

        // The same reading that delays the unmeasured model is plenty for a measured one: 10,621 + 256 = 10,877.
        build(settings);
        served = Optional.of(DEFAULT_MODEL);
        freeMib = 10_621 + 256;
        listener.heard.clear();
        driver.start();
        claim();
        assertThat(listener.heard).containsExactly("ready");
    }

    @Test
    void anImageCheckpointRunsAloneAndNeedsTheExtraHeadroomBeforeItLoads() throws Exception {
        String[] settings = {"engine.inference.admission.enabled=true", "engine.inference.admission.gpu-budget-mib=10969",
                "engine.inference.admission.headroom-mib=256", "engine.inference.admission.unmeasured-extra-headroom-mib=512"};
        build(settings);
        freeMib = 10_969 + 256;
        ImageHandle delayed = submitImage(Duration.ZERO);
        assertThat(coordinator.waiting()).singleElement().satisfies(waiting -> {
            assertThat(waiting.modality()).isEqualTo(Modality.IMAGE);
            assertThat(waiting.demand()).isEqualTo(options.capacity());
        });
        driver.tickNow();

        // Enough for a measured model's headroom, not for the extra margin an unmeasured one asks.
        ImageResult refused = delayed.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(refused.errorCode()).isEqualTo(ImageAdmission.DELAYED);
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();

        freeMib = 10_969 + 256 + 512;
        ImageHandle admitted = submitImage(Duration.ZERO);
        driver.tickNow();
        ImageResult done = admitted.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(ImageAdmission.usesCatalogArt(done)).isFalse();
        assertThat(done.errorCode()).isNull();
    }

    private ScheduledTask task() {
        return new ScheduledTask(UUID.randomUUID(), new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0,
                AiRole.NARRATOR, Audience.PUBLIC, "a".repeat(64), "b".repeat(64)), WorkClass.INTERACTIVE,
                InferenceSlot.STANDARD, "Task.", "{\"type\":\"object\"}", Duration.ofSeconds(60));
    }

    private ImageHandle submitImage(Duration wait) {
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
        return images.submit(port, request, new ImageAdmission.Ticket(WorkClass.INTERACTIVE, UUID.randomUUID(), wait));
    }
}
