package org.rpgengine.inference.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.catalog.HardwareProfileTest.fixture;
import static org.rpgengine.inference.catalog.ModelCatalogTest.JSON;
import static org.rpgengine.inference.catalog.ModelCatalogTest.entry;
import static org.rpgengine.inference.catalog.ModelCatalogTest.file;
import static org.rpgengine.inference.catalog.ModelCatalogTest.mutated;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.catalog.ModelAdvisor.Advice;
import org.rpgengine.inference.catalog.ModelAdvisor.Assessment;
import org.rpgengine.inference.catalog.ModelAdvisor.Fit;
import org.rpgengine.inference.catalog.ModelAdvisor.Note;
import org.rpgengine.inference.catalog.ModelAdvisor.Preset;
import org.rpgengine.inference.catalog.ModelAdvisor.PresetName;
import org.rpgengine.inference.catalog.ModelAdvisor.PresetSlot;
import tools.jackson.databind.node.ObjectNode;

/**
 * Advisor rules against the synthetic hardware profiles of docs/34 section 5 (no GPU, 6 GB, 8 GB,
 * 12 GB with 16 GB RAM, 24 GB) and the bundled catalog. Expected numbers are derived by hand from
 * the documented fit rule so a change to the rule or the catalog estimates shows up here.
 */
class ModelAdvisorTest {
    static final long MIB = 1L << 20;
    static final String G26 = "gemma-4-26b-a4b-it-qat-q4_0";
    static final String G12 = "gemma-4-12b-it-qat-q4_0";
    static final String QWEN = "qwen3.5-9b-q4_k_m";
    static final String E4B = "gemma-4-e4b-it-qat-q4_0";
    static final String E2B = "gemma-4-e2b-it-qat-q4_0";
    static final String WAI = "wai-illustrious-sdxl-v17";
    static final String SDXL = "sdxl-base-1.0";
    static final String PONY = "pony-diffusion-v6-xl";

    final ModelCatalog catalog = ModelCatalog.bundled();
    final ModelAdvisor advisor = new ModelAdvisor();

    Advice advise(String profile) throws IOException {
        return advisor.advise(catalog, fixture(profile));
    }

    static Assessment of(List<Assessment> list, String id) {
        return list.stream().filter(a -> a.entryId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void referenceHostRecommendsTheAdrDefaultsAndFlagsUnprovenPassthrough() throws IOException {
        Advice advice = advise("reference-host");
        assertThat(advice.gpuBudgetBytes()).isEqualTo((11012 - 1024) * MIB);
        assertThat(advice.memoryBudgetBytes()).isEqualTo((7823 - 3072) * MIB);
        assertThat(advice.hostNotes()).containsExactlyInAnyOrder(Note.CONTAINER_GPU_UNVERIFIED, Note.SINGLE_MEMORY_MODULE);
        assertThat(advice.text()).extracting(Assessment::entryId).containsExactly(G12, QWEN, E4B, E2B, G26);
        assertThat(advice.recommendedText()).isEqualTo(G12);
        assertThat(advice.recommendedImage()).isEqualTo(WAI);
        Assessment gemma = of(advice.text(), G12);
        assertThat(gemma.fit()).isEqualTo(Fit.GPU);
        assertThat(gemma.context()).isEqualTo(32768);
        // files 6,975,879,296 + sliding-window cache 503,316,480 + compute 805,306,368 + 16,384 x 32,768
        assertThat(gemma.requiredBytes()).isEqualTo(8_821_373_056L);
        assertThat(gemma.notes()).containsExactly(Note.CONTAINER_GPU_UNVERIFIED, Note.ESTIMATE_ONLY,
                Note.ITALIAN_UNCONFIRMED);
        Assessment large = of(advice.text(), G26);
        assertThat(large.fit()).isEqualTo(Fit.TOO_LARGE);
        assertThat(large.context()).isNull();
        assertThat(large.defaultEligible()).isFalse();
        assertThat(large.notes()).contains(Note.EXCEEDS_VRAM, Note.EXCEEDS_MEMORY);
        assertThat(advice.image()).extracting(Assessment::entryId).containsExactly(WAI, SDXL, PONY);
        assertThat(advice.image()).extracting(Assessment::fit).containsOnly(Fit.GPU);
        assertThat(of(advice.image(), WAI).notes()).containsExactly(Note.CONTAINER_GPU_UNVERIFIED, Note.ESTIMATE_ONLY,
                Note.LICENSE_CONFIRMATION, Note.NSFW_CAPABLE);
        Assessment pony = of(advice.image(), PONY);
        assertThat(pony.defaultEligible()).as("a restricted license is never a default").isFalse();
        assertThat(pony.notes()).contains(Note.LICENSE_RESTRICTED);
        assertThat(pony.requiredBytes()).isEqualTo(6_938_041_050L + 334_641_162L + 2560 * MIB);
    }

    @Test
    void referenceHostPresetsShareOneResidentTextModel() throws IOException {
        List<Preset> presets = advise("reference-host").presets();
        assertThat(presets).extracting(Preset::name).containsExactly(PresetName.LOCAL_ONLY, PresetName.HYBRID,
                PresetName.CLOUD_FIRST);
        assertThat(presets).allMatch(Preset::available);
        assertThat(presets.get(0).slots()).containsExactly(new PresetSlot("text.light", "local", G12),
                new PresetSlot("text.standard", "local", G12), new PresetSlot("text.deliberate", "local", G12),
                new PresetSlot("image", "local-image", WAI));
        assertThat(presets.get(1).slots()).containsExactly(new PresetSlot("text.light", "local", G12),
                new PresetSlot("text.standard", "local", G12), new PresetSlot("text.deliberate", "runtime", null),
                new PresetSlot("image", "local-image", WAI));
        assertThat(presets.get(2).slots()).containsExactly(new PresetSlot("text.light", "runtime", null),
                new PresetSlot("text.standard", "runtime", null), new PresetSlot("text.deliberate", "runtime", null),
                new PresetSlot("image", "local-image", WAI));
    }

    @Test
    void containerViewOfFreeVramIsPreferredOnceTheGpuCheckPassed() throws IOException {
        Advice advice = advise("reference-host-gpu-visible");
        assertThat(advice.gpuBudgetBytes()).isEqualTo((10776 - 1024) * MIB);
        assertThat(advice.hostNotes()).containsExactly(Note.SINGLE_MEMORY_MODULE);
        assertThat(of(advice.text(), G12).notes()).doesNotContain(Note.CONTAINER_GPU_UNVERIFIED);
        assertThat(advice.recommendedText()).isEqualTo(G12);
        assertThat(advice.recommendedImage()).isEqualTo(WAI);
    }

    @Test
    void noGpuPrefersTheSmallestModelThatFitsInMemory() throws IOException {
        Advice advice = advise("no-gpu");
        assertThat(advice.gpuBudgetBytes()).isZero();
        assertThat(advice.memoryBudgetBytes()).isEqualTo((15932 - 3072) * MIB);
        assertThat(advice.hostNotes()).containsExactly(Note.NO_GPU);
        assertThat(advice.text()).extracting(Assessment::entryId).containsExactly(E2B, E4B, QWEN, G12, G26);
        assertThat(advice.text()).extracting(Assessment::fit).containsExactly(Fit.CPU, Fit.CPU, Fit.CPU, Fit.CPU,
                Fit.TOO_LARGE);
        assertThat(of(advice.text(), E2B).notes()).containsExactly(Note.CPU_ONLY_SLOW, Note.ESTIMATE_ONLY,
                Note.ITALIAN_UNCONFIRMED);
        assertThat(advice.recommendedText()).isEqualTo(E2B);
        assertThat(advice.recommendedImage()).isNull();
        assertThat(advice.image()).allSatisfy(a -> {
            assertThat(a.fit()).isEqualTo(Fit.TOO_LARGE);
            assertThat(a.notes()).contains(Note.NO_GPU);
        });
        Preset local = advice.presets().get(0);
        assertThat(local.available()).isTrue();
        assertThat(local.slots()).contains(new PresetSlot("text.standard", "local", E2B),
                new PresetSlot("image", "none", null));
    }

    @Test
    void sixGigabytesFitOnlyTheSmallestModelOnTheGpu() throws IOException {
        Advice advice = advise("gpu-6gb");
        assertThat(advice.gpuBudgetBytes()).isEqualTo((5800 - 1024) * MIB);
        assertThat(advice.text()).extracting(Assessment::entryId).containsExactly(E2B, E4B, QWEN, G12, G26);
        assertThat(advice.text()).extracting(Assessment::fit).containsExactly(Fit.GPU, Fit.CPU, Fit.CPU, Fit.CPU,
                Fit.TOO_LARGE);
        assertThat(of(advice.text(), E4B).notes()).contains(Note.CPU_ONLY_SLOW, Note.EXCEEDS_VRAM);
        assertThat(advice.recommendedText()).isEqualTo(E2B);
        assertThat(advice.recommendedImage()).isNull();
        assertThat(advice.image()).allSatisfy(a -> assertThat(a.notes()).contains(Note.EXCEEDS_VRAM));
    }

    @Test
    void eightGigabytesReduceTheContextOfTheLargestFittingModel() throws IOException {
        Advice advice = advise("gpu-8gb");
        assertThat(advice.gpuBudgetBytes()).isEqualTo((7110 - 1024) * MIB);
        assertThat(advice.hostNotes()).as("two modules in two slots are dual channel").isEmpty();
        assertThat(advice.text()).extracting(Assessment::entryId).containsExactly(E4B, E2B, QWEN, G12, G26);
        Assessment e4b = of(advice.text(), E4B);
        assertThat(e4b.fit()).isEqualTo(Fit.GPU);
        // (6,381,633,536 - 5,867,972,960) / 16,384 = 31,351 tokens, rounded down to 30,720
        assertThat(e4b.context()).isEqualTo(30720);
        assertThat(e4b.notes()).contains(Note.CONTEXT_REDUCED);
        assertThat(of(advice.text(), QWEN).fit()).isEqualTo(Fit.CPU);
        assertThat(advice.recommendedText()).isEqualTo(E4B);
        assertThat(advice.recommendedImage()).isNull();
    }

    @Test
    void twentyFourGigabytesRecommendTheLargestModel() throws IOException {
        Advice advice = advise("gpu-24gb");
        assertThat(advice.text()).extracting(Assessment::fit).containsOnly(Fit.GPU);
        assertThat(advice.text()).extracting(Assessment::entryId).containsExactly(G26, G12, QWEN, E4B, E2B);
        assertThat(of(advice.text(), G26).context()).isEqualTo(32768);
        assertThat(advice.recommendedText()).isEqualTo(G26);
        assertThat(advice.recommendedImage()).isEqualTo(WAI);
    }

    @Test
    void everyEntryIsAssessedSoNothingIsHidden() throws IOException {
        for (String profile : HardwareProfileTest.FIXTURES) {
            Advice advice = advise(profile);
            assertThat(advice.text().size() + advice.image().size()).as(profile).isEqualTo(catalog.entries().size());
        }
    }

    @Test
    void unverifiedDigestsAreNeverDefaults() throws IOException {
        ModelCatalog changed = mutated(r -> ((ObjectNode) file(r, 1, 0).get("verification")).put("method", "source-api"));
        Advice advice = advisor.advise(changed, fixture("reference-host"));
        assertThat(of(advice.text(), G12).notes()).contains(Note.HASH_UNVERIFIED);
        assertThat(of(advice.text(), G12).defaultEligible()).isFalse();
        assertThat(advice.recommendedText()).isEqualTo(QWEN);
    }

    @Test
    void measuredProfilesAndItalianEvidenceOutrankCatalogOrder() throws IOException {
        ModelCatalog measured = mutated(r -> entry(r, 3).set("measured", JSON.createObjectNode().put("date", "2026-11-02")
                .put("host", "reference-host").put("peakVramBytes", 6_000_000_000L).put("context", 32768)));
        assertThat(advisor.advise(measured, fixture("reference-host")).recommendedText()).isEqualTo(E4B);
        ModelCatalog italian = mutated(r -> ((ObjectNode) entry(r, 2).get("languages")).put("it", "evaluated"));
        Advice advice = advisor.advise(italian, fixture("reference-host"));
        assertThat(advice.recommendedText()).isEqualTo(QWEN);
        assertThat(of(advice.text(), QWEN).notes()).doesNotContain(Note.ITALIAN_UNCONFIRMED);
    }

    @Test
    void settingsChangeTheBudgets() throws IOException {
        ModelAdvisor strict = new ModelAdvisor(new ModelAdvisor.Settings(4096, 3072, 16_384));
        Advice advice = strict.advise(catalog, fixture("reference-host"));
        assertThat(advice.gpuBudgetBytes()).isEqualTo((11012 - 4096) * MIB);
        // 6,912 MiB leave no room for the 12B model, and 4,751 MiB of VM memory none either; the 9B
        // model fits with (7,247,757,312 - 6,404,302,048) / 32,768 = 25,740 tokens, rounded to 25,600.
        assertThat(of(advice.text(), G12).fit()).isEqualTo(Fit.TOO_LARGE);
        assertThat(advice.recommendedText()).isEqualTo(QWEN);
        assertThat(of(advice.text(), QWEN).context()).isEqualTo(25600);
        assertThat(of(advice.text(), QWEN).notes()).contains(Note.CONTEXT_REDUCED);
        assertThat(advice.recommendedImage()).as("the image checkpoints need about 9 GiB").isNull();
    }
}
