package org.rpgengine.inference.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.rpgengine.inference.catalog.CatalogEntry.ImageRequirements;
import org.rpgengine.inference.catalog.CatalogEntry.Kind;
import org.rpgengine.inference.catalog.CatalogEntry.LanguageSupport;
import org.rpgengine.inference.catalog.CatalogEntry.LicenseClass;
import org.rpgengine.inference.catalog.CatalogEntry.TextRequirements;

/**
 * Hardware advisor (ADR 0021 decision 4, docs/34 section 5): ranks every catalog entry against a
 * {@link HardwareProfile}, explains each verdict, recommends one default per kind and fills the
 * local-only, hybrid and cloud-first presets. It is pure and deterministic: the same catalog,
 * profile and settings always give the same advice. It ranks and explains and never blocks: every
 * entry is assessed, including those that do not fit, and the owner may still choose any of them.
 *
 * <p>Fit rules, all sizes in bytes:
 * <ul>
 *   <li>GPU budget: the free VRAM of the first GPU (the container's view when the GPU check saw a
 *       CUDA device, otherwise the driver tool's) minus {@link Settings#vramMarginMiB()}. Free VRAM
 *       already excludes the desktop and other applications at profiling time.</li>
 *   <li>Memory budget: the container engine's memory (the WSL 2 VM under Docker Desktop) or, with
 *       no engine, system memory, minus {@link Settings#ramReserveMiB()} for the engine, database
 *       and operating system.</li>
 *   <li>Text: files + {@code kvFixedBytes} + {@code computeBytes} + {@code kvBytesPerToken} x
 *       context. The largest context that fits (rounded down to 1,024 tokens, capped at
 *       {@code contextMax}) must reach {@link Settings#minimumContext()}; the proposed context is
 *       the smaller of that and {@code contextDefault}. GPU is tried first, then CPU memory.</li>
 *   <li>Image: files + {@code activationBytes} on the GPU only, since CPU diffusion is too slow
 *       for play.</li>
 * </ul>
 *
 * <p>Ranking within a kind: GPU fits, then CPU fits, then entries that do not fit; then entries
 * with a measured profile; then license class (permissive and use-restricted together, then
 * restricted, then unknown); then language coverage (English and Italian, evaluated over named over
 * unnamed); then, for CPU fits only, smaller files first because CPU decoding speed is bounded by
 * memory bandwidth divided by model size; finally catalog order. The recommended default is the
 * first ranked entry that fits, has every file digest verified from downloaded bytes and has a
 * permissive or use-restricted license; there may be none.
 */
public final class ModelAdvisor {
    private static final long MIB = 1L << 20;
    private static final int CONTEXT_STEP = 1024;

    private final Settings settings;

    /** Create an advisor with explicit settings. */
    public ModelAdvisor(Settings settings) {
        this.settings = settings;
    }

    /** Create an advisor with {@link Settings#DEFAULTS}. */
    public ModelAdvisor() {
        this(Settings.DEFAULTS);
    }

    /**
     * Advisor settings.
     *
     * @param vramMarginMiB safety margin kept free on the GPU (default 1,024 MiB, docs/34 section 3)
     * @param ramReserveMiB memory left to the engine, database and operating system (default 3,072 MiB)
     * @param minimumContext smallest useful text context in tokens (default 16,384)
     */
    public record Settings(long vramMarginMiB, long ramReserveMiB, int minimumContext) {
        /** Defaults documented in docs/34 section 5. */
        public static final Settings DEFAULTS = new Settings(1024, 3072, 16_384);
    }

    /** Where an entry fits. */
    public enum Fit {
        /** Entirely in GPU memory. */
        GPU,
        /** Text only: in system or container memory, decoded on the CPU (slow). */
        CPU,
        /** Needs more VRAM or memory than the profile offers. */
        TOO_LARGE
    }

    /** Explanations attached to an assessment or to the whole advice. */
    public enum Note {
        /** The driver tool found no NVIDIA GPU or is not installed. */
        NO_GPU,
        /** The driver tool returned an error. */
        GPU_TOOL_FAILED,
        /** The GPU fits but no container has been seen using it yet ({@code --gpu-check} not run or failed). */
        CONTAINER_GPU_UNVERIFIED,
        /** The pinned image ran and saw no CUDA device: local GPU inference cannot work as configured. */
        CONTAINER_GPU_NOT_VISIBLE,
        /** The proposed context is below the entry's default because the full default does not fit. */
        CONTEXT_REDUCED,
        /** CPU-only text decoding; expect low speed. */
        CPU_ONLY_SLOW,
        /** Needs more GPU memory than is free. */
        EXCEEDS_VRAM,
        /** Needs more memory than the container engine or system offers. */
        EXCEEDS_MEMORY,
        /** No measured profile exists; the memory figures are estimates. */
        ESTIMATE_ONLY,
        /** At least one file digest was not recomputed from downloaded bytes. */
        HASH_UNVERIFIED,
        /** The owner must confirm the license before a fetch. */
        LICENSE_CONFIRMATION,
        /** The license has conditions beyond use restrictions, such as a ban on monetized use. */
        LICENSE_RESTRICTED,
        /** Italian coverage is claimed only as part of an unnamed multilingual set, or not at all. */
        ITALIAN_UNCONFIRMED,
        /** The model can produce explicit content; safe-content defaults apply. */
        NSFW_CAPABLE,
        /** One memory module occupies a multi-slot board: single-channel memory makes CPU offload slow. */
        SINGLE_MEMORY_MODULE
    }

    /**
     * The verdict for one entry.
     *
     * @param entryId catalog identifier
     * @param kind text or image
     * @param fit where it fits
     * @param requiredBytes bytes needed at {@code context} (or at the minimum context when it does
     *     not fit); for images, files plus activations
     * @param budgetBytes the budget that was compared: GPU for {@code GPU} and image entries, memory
     *     for {@code CPU}, the GPU budget again for {@code TOO_LARGE} text when a GPU exists
     * @param context proposed text context, or {@code null} for images and entries that do not fit
     * @param defaultEligible whether the entry may be recommended as a default
     * @param notes explanations, in {@link Note} order
     */
    public record Assessment(String entryId, Kind kind, Fit fit, long requiredBytes, long budgetBytes,
            Integer context, boolean defaultEligible, List<Note> notes) {
        /** Copy the note list. */
        public Assessment {
            notes = List.copyOf(notes);
        }
    }

    /** Preset names proposed in docs/34 section 3. */
    public enum PresetName {
        LOCAL_ONLY, HYBRID, CLOUD_FIRST
    }

    /**
     * One slot of a preset.
     *
     * @param slot {@code text.light}, {@code text.standard}, {@code text.deliberate} or {@code image}
     * @param backend {@code local} or {@code local-image} (a catalog entry), {@code runtime} (the
     *     configured agent runtime or an owner-chosen API model) or {@code none} (no image source)
     * @param entryId catalog identifier for local backends, otherwise {@code null}
     */
    public record PresetSlot(String slot, String backend, String entryId) {
    }

    /**
     * A preset: one assignment per slot. A preset is unavailable when it would need a local text
     * model and none is recommended.
     */
    public record Preset(PresetName name, boolean available, List<PresetSlot> slots) {
        /** Copy the slot list. */
        public Preset {
            slots = List.copyOf(slots);
        }
    }

    /**
     * Complete advice.
     *
     * @param gpuBudgetBytes usable GPU bytes (0 without a GPU)
     * @param memoryBudgetBytes usable container or system memory bytes (never negative)
     * @param hostNotes notes about the machine rather than an entry
     * @param text text entries in rank order
     * @param image image entries in rank order
     * @param recommendedText recommended text entry, or {@code null}
     * @param recommendedImage recommended image entry, or {@code null}
     * @param presets local-only, hybrid and cloud-first, in that order
     */
    public record Advice(long gpuBudgetBytes, long memoryBudgetBytes, List<Note> hostNotes, List<Assessment> text,
            List<Assessment> image, String recommendedText, String recommendedImage, List<Preset> presets) {
        /** Copy the lists. */
        public Advice {
            hostNotes = List.copyOf(hostNotes);
            text = List.copyOf(text);
            image = List.copyOf(image);
            presets = List.copyOf(presets);
        }
    }

    /** Assess every entry of {@code catalog} against {@code profile} and build the presets. */
    public Advice advise(ModelCatalog catalog, HardwareProfile profile) {
        Set<Note> hostNotes = EnumSet.noneOf(Note.class);
        long gpuBudget = gpuBudget(profile, hostNotes);
        long memoryBudget = Math.max(0, (memoryMiB(profile) - settings.ramReserveMiB()) * MIB);
        HardwareProfile.Memory memory = profile.memory();
        if (memory.modules() != null && memory.modules().size() == 1 && memory.moduleSlots() != null
                && memory.moduleSlots() > 1) {
            hostNotes.add(Note.SINGLE_MEMORY_MODULE);
        }
        List<Ranked> text = new ArrayList<>();
        List<Ranked> image = new ArrayList<>();
        List<CatalogEntry> entries = catalog.entries();
        for (int i = 0; i < entries.size(); i++) {
            CatalogEntry entry = entries.get(i);
            Assessment assessment = entry.kind() == Kind.TEXT
                    ? assessText(entry, gpuBudget, memoryBudget, hostNotes)
                    : assessImage(entry, gpuBudget, hostNotes);
            (entry.kind() == Kind.TEXT ? text : image).add(new Ranked(entry, assessment, i));
        }
        text.sort(ORDER);
        image.sort(ORDER);
        String recommendedText = recommended(text);
        String recommendedImage = recommended(image);
        return new Advice(gpuBudget, memoryBudget, List.copyOf(hostNotes),
                text.stream().map(Ranked::assessment).toList(), image.stream().map(Ranked::assessment).toList(),
                recommendedText, recommendedImage, presets(recommendedText, recommendedImage));
    }

    private record Ranked(CatalogEntry entry, Assessment assessment, int position) {
    }

    private static final Comparator<Ranked> ORDER = Comparator
            .comparingInt((Ranked r) -> r.assessment().fit().ordinal())
            .thenComparingInt(r -> r.entry().measured() == null ? 1 : 0)
            .thenComparingInt(r -> licenseRank(r.entry().license().licenseClass()))
            .thenComparingInt(r -> -languageScore(r.entry()))
            .thenComparingLong(r -> r.assessment().fit() == Fit.CPU ? r.entry().fileBytes() : 0)
            .thenComparingInt(Ranked::position);

    private static int licenseRank(LicenseClass licenseClass) {
        return switch (licenseClass) {
            case PERMISSIVE, USE_RESTRICTED -> 0;
            case RESTRICTED -> 1;
            case UNKNOWN -> 2;
        };
    }

    /** Coverage score over English and Italian: evaluated 3, named 2, unnamed 1, absent 0. */
    private static int languageScore(CatalogEntry entry) {
        int score = 0;
        for (String code : List.of("en", "it")) {
            LanguageSupport support = entry.languages().get(code);
            score += support == null ? 0 : support.ordinal() + 1;
        }
        return score;
    }

    private long gpuBudget(HardwareProfile profile, Set<Note> hostNotes) {
        HardwareProfile.ContainerGpu container = profile.containerGpu();
        if (profile.gpuSource().equals("failed")) {
            hostNotes.add(Note.GPU_TOOL_FAILED);
        }
        Long freeMiB = null;
        if (container.visible()) {
            freeMiB = container.devices().stream().filter(HardwareProfile.Device::cuda).findFirst()
                    .map(HardwareProfile.Device::memoryFreeMiB).orElse(null);
        } else if (profile.primaryGpu() != null) {
            HardwareProfile.Gpu gpu = profile.primaryGpu();
            freeMiB = gpu.memoryFreeMiB() != null ? gpu.memoryFreeMiB()
                    : gpu.memoryTotalMiB() != null && gpu.memoryUsedMiB() != null
                            ? gpu.memoryTotalMiB() - gpu.memoryUsedMiB() : null;
            hostNotes.add(container.status().equals("not-visible")
                    ? Note.CONTAINER_GPU_NOT_VISIBLE : Note.CONTAINER_GPU_UNVERIFIED);
        }
        if (freeMiB == null) {
            hostNotes.add(Note.NO_GPU);
            return 0;
        }
        return Math.max(0, (freeMiB - settings.vramMarginMiB()) * MIB);
    }

    private static long memoryMiB(HardwareProfile profile) {
        HardwareProfile.ContainerEngine engine = profile.containerEngine();
        if (engine.available() && engine.memoryTotalMiB() != null) {
            return engine.memoryTotalMiB();
        }
        return profile.memory().totalMiB() == null ? 0 : profile.memory().totalMiB();
    }

    private Assessment assessText(CatalogEntry entry, long gpuBudget, long memoryBudget, Set<Note> hostNotes) {
        TextRequirements r = entry.textRequirements();
        long fixed = entry.fileBytes() + r.kvFixedBytes() + r.computeBytes();
        List<Note> notes = new ArrayList<>();
        Fit fit = Fit.TOO_LARGE;
        long budget = gpuBudget > 0 ? gpuBudget : memoryBudget;
        Integer context = null;
        int onGpu = maxContext(gpuBudget, fixed, r);
        int onCpu = maxContext(memoryBudget, fixed, r);
        if (onGpu >= settings.minimumContext()) {
            fit = Fit.GPU;
            budget = gpuBudget;
            context = Math.min(onGpu, r.contextDefault());
        } else if (onCpu >= settings.minimumContext()) {
            fit = Fit.CPU;
            budget = memoryBudget;
            context = Math.min(onCpu, r.contextDefault());
        }
        long required = fixed + r.kvBytesPerToken() * (context == null ? settings.minimumContext() : context);
        if (fit == Fit.GPU && hostNotes.contains(Note.CONTAINER_GPU_NOT_VISIBLE)) {
            notes.add(Note.CONTAINER_GPU_NOT_VISIBLE);
        } else if (fit == Fit.GPU && hostNotes.contains(Note.CONTAINER_GPU_UNVERIFIED)) {
            notes.add(Note.CONTAINER_GPU_UNVERIFIED);
        }
        if (context != null && context < r.contextDefault()) {
            notes.add(Note.CONTEXT_REDUCED);
        }
        if (fit == Fit.CPU) {
            notes.add(Note.CPU_ONLY_SLOW);
        }
        if (fit != Fit.GPU && gpuBudget > 0) {
            notes.add(Note.EXCEEDS_VRAM);
        }
        if (fit == Fit.TOO_LARGE) {
            notes.add(Note.EXCEEDS_MEMORY);
        }
        LanguageSupport italian = entry.languages().get("it");
        if (italian == null || italian == LanguageSupport.UNNAMED) {
            notes.add(Note.ITALIAN_UNCONFIRMED);
        }
        return assessment(entry, fit, required, budget, context, notes);
    }

    private Assessment assessImage(CatalogEntry entry, long gpuBudget, Set<Note> hostNotes) {
        ImageRequirements r = entry.imageRequirements();
        long required = entry.fileBytes() + r.activationBytes();
        List<Note> notes = new ArrayList<>();
        Fit fit = gpuBudget > 0 && required <= gpuBudget ? Fit.GPU : Fit.TOO_LARGE;
        if (fit == Fit.GPU && hostNotes.contains(Note.CONTAINER_GPU_NOT_VISIBLE)) {
            notes.add(Note.CONTAINER_GPU_NOT_VISIBLE);
        } else if (fit == Fit.GPU && hostNotes.contains(Note.CONTAINER_GPU_UNVERIFIED)) {
            notes.add(Note.CONTAINER_GPU_UNVERIFIED);
        }
        if (fit == Fit.TOO_LARGE) {
            notes.add(gpuBudget > 0 ? Note.EXCEEDS_VRAM : Note.NO_GPU);
        }
        return assessment(entry, fit, required, gpuBudget, null, notes);
    }

    /** Add the entry-wide notes and the default eligibility shared by both kinds. */
    private static Assessment assessment(CatalogEntry entry, Fit fit, long required, long budget, Integer context,
            List<Note> notes) {
        if (entry.measured() == null) {
            notes.add(Note.ESTIMATE_ONLY);
        }
        if (!entry.verified()) {
            notes.add(Note.HASH_UNVERIFIED);
        }
        if (entry.license().confirmationRequired()) {
            notes.add(Note.LICENSE_CONFIRMATION);
        }
        LicenseClass licenseClass = entry.license().licenseClass();
        if (licenseClass == LicenseClass.RESTRICTED || licenseClass == LicenseClass.UNKNOWN) {
            notes.add(Note.LICENSE_RESTRICTED);
        }
        if (entry.contentNotes().contains("nsfw-capable")) {
            notes.add(Note.NSFW_CAPABLE);
        }
        notes.sort(Comparator.naturalOrder());
        boolean eligible = fit != Fit.TOO_LARGE && entry.verified() && licenseRank(licenseClass) == 0;
        return new Assessment(entry.id(), entry.kind(), fit, required, budget, context, eligible, notes);
    }

    /** Largest context, in whole steps up to {@code contextMax}, whose total fits {@code budget}; 0 if none. */
    private static int maxContext(long budget, long fixed, TextRequirements r) {
        if (budget <= fixed) {
            return 0;
        }
        long tokens = (budget - fixed) / r.kvBytesPerToken();
        long capped = Math.min(tokens, r.contextMax());
        return (int) (capped / CONTEXT_STEP * CONTEXT_STEP);
    }

    private static String recommended(List<Ranked> ranked) {
        return ranked.stream().filter(r -> r.assessment().defaultEligible()).findFirst()
                .map(r -> r.entry().id()).orElse(null);
    }

    private static List<Preset> presets(String textId, String imageId) {
        PresetSlot imageSlot = imageId == null
                ? new PresetSlot("image", "none", null) : new PresetSlot("image", "local-image", imageId);
        Map<String, PresetSlot> local = Map.of(
                "text.light", new PresetSlot("text.light", "local", textId),
                "text.standard", new PresetSlot("text.standard", "local", textId),
                "text.deliberate", new PresetSlot("text.deliberate", "local", textId));
        PresetSlot runtimeLight = new PresetSlot("text.light", "runtime", null);
        PresetSlot runtimeStandard = new PresetSlot("text.standard", "runtime", null);
        PresetSlot runtimeDeliberate = new PresetSlot("text.deliberate", "runtime", null);
        boolean localText = textId != null;
        // One resident text model serves every local text slot: two models do not fit together on
        // the reference host and swapping costs seconds (docs/34 section 3).
        Preset localOnly = new Preset(PresetName.LOCAL_ONLY, localText, localText
                ? List.of(local.get("text.light"), local.get("text.standard"), local.get("text.deliberate"), imageSlot)
                : List.of(runtimeLight, runtimeStandard, runtimeDeliberate, imageSlot));
        Preset hybrid = new Preset(PresetName.HYBRID, localText, localText
                ? List.of(local.get("text.light"), local.get("text.standard"), runtimeDeliberate, imageSlot)
                : List.of(runtimeLight, runtimeStandard, runtimeDeliberate, imageSlot));
        Preset cloudFirst = new Preset(PresetName.CLOUD_FIRST, true,
                List.of(runtimeLight, runtimeStandard, runtimeDeliberate, imageSlot));
        return List.of(localOnly, hybrid, cloudFirst);
    }
}
