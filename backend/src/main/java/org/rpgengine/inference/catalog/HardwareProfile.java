package org.rpgengine.inference.catalog;

import static org.rpgengine.inference.catalog.JsonFields.array;
import static org.rpgengine.inference.catalog.JsonFields.bool;
import static org.rpgengine.inference.catalog.JsonFields.choice;
import static org.rpgengine.inference.catalog.JsonFields.instant;
import static org.rpgengine.inference.catalog.JsonFields.integer;
import static org.rpgengine.inference.catalog.JsonFields.names;
import static org.rpgengine.inference.catalog.JsonFields.object;
import static org.rpgengine.inference.catalog.JsonFields.optionalLong;
import static org.rpgengine.inference.catalog.JsonFields.optionalText;
import static org.rpgengine.inference.catalog.JsonFields.text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The hardware profile written by {@code ./start.sh --models profile} to {@code .local/hardware.json}
 * (schema {@code rpg-engine.hardware-profile/1}, docs/34 section 5). It is the advisor's only view
 * of the machine. Sizes are MiB as the tools report them; a {@code null} size means the tool did
 * not answer. The profile contains hardware models, sizes and versions only, never identifiers.
 *
 * @param generatedAt when the launcher wrote the profile
 * @param platform {@code windows} (Git Bash), {@code wsl}, {@code linux}, {@code macos} or
 *     {@code other}
 * @param cpu processor model and logical cores
 * @param memory system memory as seen by the launcher
 * @param gpuSource {@code nvidia-smi} (the driver tool answered), {@code unavailable} (no tool) or
 *     {@code failed} (the tool returned an error)
 * @param gpus GPUs reported by the driver tool, possibly empty
 * @param disk free space where the private model directory lives
 * @param containerEngine the container engine, or one with {@code available == false}
 * @param containerGpu what the pinned model-server image saw through GPU passthrough
 */
public record HardwareProfile(Instant generatedAt, String platform, Cpu cpu, Memory memory, String gpuSource,
        List<Gpu> gpus, Disk disk, ContainerEngine containerEngine, ContainerGpu containerGpu) {
    /** The only supported profile format. */
    public static final String SCHEMA = "rpg-engine.hardware-profile/1";
    private static final long MAX_MIB = 1L << 40;

    /** Copy the GPU list so a parsed profile is immutable. */
    public HardwareProfile {
        gpus = List.copyOf(gpus);
    }

    /** Processor model (may be {@code null}) and logical cores (may be {@code null}). */
    public record Cpu(String model, Long logicalCores) {
    }

    /**
     * System memory.
     *
     * @param scope {@code host}, or {@code vm} under WSL where the kernel sees only the VM
     * @param totalMiB total memory, or {@code null}
     * @param availableMiB memory available at profiling time, or {@code null}
     * @param modules installed modules (Windows only), or {@code null} when unknown
     * @param moduleSlots number of module slots on the board, or {@code null} when unknown
     */
    public record Memory(String scope, Long totalMiB, Long availableMiB, List<Module> modules, Long moduleSlots) {
    }

    /** One memory module: capacity and configured speed in MT/s (may be {@code null}). */
    public record Module(long capacityMiB, Long speedMTs) {
    }

    /**
     * One GPU as reported by the driver tool. Free memory is what remained after the desktop and
     * other applications when the profile was written.
     */
    public record Gpu(long index, String name, Long memoryTotalMiB, Long memoryUsedMiB, Long memoryFreeMiB,
            String driverVersion, String computeCapability) {
    }

    /** Free space at {@code path} (always {@code .local}), or {@code null} when unknown. */
    public record Disk(String path, Long freeMiB) {
    }

    /**
     * Container engine facts. When {@code available} is false every other component is
     * {@code null}. Under Docker Desktop, {@code memoryTotalMiB} is the WSL 2 VM's memory, which
     * bounds every container including a CPU-only model.
     */
    public record ContainerEngine(boolean available, String osType, String serverVersion, String operatingSystem,
            Long memoryTotalMiB, Long cpus) {
    }

    /**
     * Container GPU check.
     *
     * @param status {@code visible}, {@code not-visible}, {@code failed}, {@code not-checked} or
     *     {@code engine-unavailable}
     * @param image the pinned image reference that was (or would be) run
     * @param devices non-CPU devices the image listed; empty unless the check ran
     */
    public record ContainerGpu(String status, String image, List<Device> devices) {
        /** Copy the device list. */
        public ContainerGpu {
            devices = List.copyOf(devices);
        }

        /** True when the check ran and the image saw a CUDA device. */
        public boolean visible() {
            return "visible".equals(status);
        }
    }

    /** A device listed by llama-server {@code --list-devices}, such as {@code CUDA0}. */
    public record Device(String name, String description, long memoryTotalMiB, long memoryFreeMiB) {
        /** True for an NVIDIA CUDA device. */
        public boolean cuda() {
            return name.startsWith("CUDA");
        }
    }

    /** Parse {@code bytes} as a profile; any violation is a {@link CatalogException}. */
    public static HardwareProfile parse(byte[] bytes) {
        JsonNode root = object(JsonFields.document(bytes), "", names("schema", "generatedAt", "platform", "cpu",
                "memory", "gpuSource", "gpus", "disk", "containerEngine", "containerGpu"));
        if (!SCHEMA.equals(text(root.get("schema"), "/schema", 64))) {
            throw new CatalogException("SCHEMA", "/schema", "expected " + SCHEMA);
        }
        Instant generatedAt = instant(root.get("generatedAt"), "/generatedAt");
        String platform = choice(root.get("platform"), "/platform", Set.of("windows", "wsl", "linux", "macos", "other"));
        JsonNode cpuNode = object(root.get("cpu"), "/cpu", names("model", "logicalCores"));
        Cpu cpu = new Cpu(optionalText(cpuNode.get("model"), "/cpu/model", 200),
                optionalLong(cpuNode.get("logicalCores"), "/cpu/logicalCores", 1, 4096));
        Memory memory = memory(root.get("memory"));
        String gpuSource = choice(root.get("gpuSource"), "/gpuSource", Set.of("nvidia-smi", "unavailable", "failed"));
        List<Gpu> gpus = new ArrayList<>();
        List<JsonNode> gpuNodes = array(root.get("gpus"), "/gpus", 0, 16);
        if (!gpuSource.equals("nvidia-smi") && !gpuNodes.isEmpty()) {
            throw new CatalogException("GPUS", "/gpus", "GPUs are listed only when the driver tool answered");
        }
        for (int i = 0; i < gpuNodes.size(); i++) {
            gpus.add(gpu(gpuNodes.get(i), "/gpus/" + i));
        }
        JsonNode diskNode = object(root.get("disk"), "/disk", names("path", "freeMiB"));
        Disk disk = new Disk(choice(diskNode.get("path"), "/disk/path", Set.of(".local")),
                optionalLong(diskNode.get("freeMiB"), "/disk/freeMiB", 0, MAX_MIB));
        return new HardwareProfile(generatedAt, platform, cpu, memory, gpuSource, gpus, disk,
                engine(root.get("containerEngine")), containerGpu(root.get("containerGpu")));
    }

    /** The first GPU the driver tool listed, or {@code null}. Multi-GPU scheduling is not decided. */
    public Gpu primaryGpu() {
        return gpus.isEmpty() ? null : gpus.get(0);
    }

    private static Memory memory(JsonNode node) {
        object(node, "/memory", names("scope", "totalMiB", "availableMiB", "modules", "moduleSlots"));
        List<Module> modules = null;
        if (!node.get("modules").isNull()) {
            modules = new ArrayList<>();
            List<JsonNode> items = array(node.get("modules"), "/memory/modules", 1, 64);
            for (int i = 0; i < items.size(); i++) {
                String at = "/memory/modules/" + i;
                JsonNode module = object(items.get(i), at, names("capacityMiB", "speedMTs"));
                modules.add(new Module(integer(module.get("capacityMiB"), at + "/capacityMiB", 1, MAX_MIB),
                        optionalLong(module.get("speedMTs"), at + "/speedMTs", 0, 100_000)));
            }
            modules = List.copyOf(modules);
        }
        return new Memory(choice(node.get("scope"), "/memory/scope", Set.of("host", "vm")),
                optionalLong(node.get("totalMiB"), "/memory/totalMiB", 1, MAX_MIB),
                optionalLong(node.get("availableMiB"), "/memory/availableMiB", 0, MAX_MIB), modules,
                optionalLong(node.get("moduleSlots"), "/memory/moduleSlots", 1, 64));
    }

    private static Gpu gpu(JsonNode node, String at) {
        object(node, at, names("index", "name", "memoryTotalMiB", "memoryUsedMiB", "memoryFreeMiB", "driverVersion",
                "computeCapability"));
        return new Gpu(integer(node.get("index"), at + "/index", 0, 64), optionalText(node.get("name"), at + "/name", 200),
                optionalLong(node.get("memoryTotalMiB"), at + "/memoryTotalMiB", 0, MAX_MIB),
                optionalLong(node.get("memoryUsedMiB"), at + "/memoryUsedMiB", 0, MAX_MIB),
                optionalLong(node.get("memoryFreeMiB"), at + "/memoryFreeMiB", 0, MAX_MIB),
                optionalText(node.get("driverVersion"), at + "/driverVersion", 40),
                optionalText(node.get("computeCapability"), at + "/computeCapability", 10));
    }

    private static ContainerEngine engine(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new CatalogException("TYPE", "/containerEngine", "expected an object");
        }
        if (node.has("available") && node.get("available").isBoolean() && !node.get("available").booleanValue()) {
            object(node, "/containerEngine", names("available"));
            return new ContainerEngine(false, null, null, null, null, null);
        }
        object(node, "/containerEngine", names("available", "osType", "serverVersion", "operatingSystem",
                "memoryTotalMiB", "cpus"));
        bool(node.get("available"), "/containerEngine/available");
        return new ContainerEngine(true, optionalText(node.get("osType"), "/containerEngine/osType", 40),
                optionalText(node.get("serverVersion"), "/containerEngine/serverVersion", 40),
                optionalText(node.get("operatingSystem"), "/containerEngine/operatingSystem", 200),
                optionalLong(node.get("memoryTotalMiB"), "/containerEngine/memoryTotalMiB", 1, MAX_MIB),
                optionalLong(node.get("cpus"), "/containerEngine/cpus", 1, 4096));
    }

    private static ContainerGpu containerGpu(JsonNode node) {
        object(node, "/containerGpu", names("status", "image", "devices"));
        String status = choice(node.get("status"), "/containerGpu/status",
                Set.of("visible", "not-visible", "failed", "not-checked", "engine-unavailable"));
        String image = text(node.get("image"), "/containerGpu/image", 300);
        List<Device> devices = new ArrayList<>();
        List<JsonNode> items = array(node.get("devices"), "/containerGpu/devices", 0, 16);
        for (int i = 0; i < items.size(); i++) {
            String at = "/containerGpu/devices/" + i;
            JsonNode device = object(items.get(i), at, names("name", "description", "memoryTotalMiB", "memoryFreeMiB"));
            devices.add(new Device(text(device.get("name"), at + "/name", 40),
                    text(device.get("description"), at + "/description", 200),
                    integer(device.get("memoryTotalMiB"), at + "/memoryTotalMiB", 0, MAX_MIB),
                    integer(device.get("memoryFreeMiB"), at + "/memoryFreeMiB", 0, MAX_MIB)));
        }
        boolean cuda = devices.stream().anyMatch(Device::cuda);
        if (status.equals("visible") != cuda) {
            throw new CatalogException("CONTAINER_GPU", "/containerGpu/status",
                    "status visible requires, and only it allows, a CUDA device");
        }
        if ((status.equals("not-checked") || status.equals("engine-unavailable")) && !devices.isEmpty()) {
            throw new CatalogException("CONTAINER_GPU", "/containerGpu/devices", "devices require a completed check");
        }
        return new ContainerGpu(status, image, devices);
    }
}
