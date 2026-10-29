package org.rpgengine.inference.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Parses the hardware-profile fixtures, which {@code tools/container_packaging/test_hardware_profile.py}
 * proves byte-identical to the launcher's output for recorded tool answers, and rejects malformed
 * or inconsistent profiles.
 */
class HardwareProfileTest {
    static final List<String> FIXTURES = List.of("reference-host", "reference-host-gpu-visible", "no-gpu", "gpu-6gb",
            "gpu-8gb", "gpu-24gb");
    static final JsonMapper JSON = JsonMapper.builder().build();

    static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream in = HardwareProfileTest.class.getClassLoader()
                .getResourceAsStream("inference/hardware-profiles/" + name + ".json")) {
            return in.readAllBytes();
        }
    }

    static HardwareProfile fixture(String name) throws IOException {
        return HardwareProfile.parse(fixtureBytes(name));
    }

    static void rejects(String fixture, Consumer<ObjectNode> change, String code, String pointer) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(fixtureBytes(fixture));
        change.accept(root);
        byte[] bytes = JSON.writeValueAsBytes(root);
        assertThatThrownBy(() -> HardwareProfile.parse(bytes)).isInstanceOfSatisfying(CatalogException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.pointer()).isEqualTo(pointer);
        });
    }

    @Test
    void everyLauncherFixtureParses() throws IOException {
        for (String name : FIXTURES) {
            assertThat(fixture(name).generatedAt()).as(name).isNotNull();
        }
    }

    @Test
    void referenceHostProfileKeepsTheDriverToolVramAndTheVmMemory() throws IOException {
        HardwareProfile profile = fixture("reference-host");
        assertThat(profile.platform()).isEqualTo("windows");
        assertThat(profile.cpu().model()).isEqualTo("Intel(R) Core(TM) i5-14600KF");
        assertThat(profile.cpu().logicalCores()).isEqualTo(20L);
        assertThat(profile.memory().modules()).containsExactly(new HardwareProfile.Module(16384, 5600L));
        assertThat(profile.memory().moduleSlots()).isEqualTo(4L);
        HardwareProfile.Gpu gpu = profile.primaryGpu();
        assertThat(gpu.name()).isEqualTo("NVIDIA GeForce RTX 4070");
        assertThat(gpu.memoryTotalMiB()).as("12 GB from the driver tool, not the 4 GB adapter field").isEqualTo(12282L);
        assertThat(gpu.memoryUsedMiB()).isEqualTo(1270L);
        assertThat(profile.containerEngine().memoryTotalMiB()).isEqualTo(7823L);
        assertThat(profile.containerGpu().status()).isEqualTo("not-checked");
        assertThat(profile.containerGpu().visible()).isFalse();
        HardwareProfile visible = fixture("reference-host-gpu-visible");
        assertThat(visible.containerGpu().visible()).isTrue();
        assertThat(visible.containerGpu().devices()).containsExactly(
                new HardwareProfile.Device("CUDA0", "NVIDIA GeForce RTX 4070", 12281, 10776));
    }

    @Test
    void noGpuProfileHasNoDevices() throws IOException {
        HardwareProfile profile = fixture("no-gpu");
        assertThat(profile.gpuSource()).isEqualTo("unavailable");
        assertThat(profile.primaryGpu()).isNull();
        assertThat(profile.containerGpu().status()).isEqualTo("not-visible");
        assertThat(profile.memory().modules()).isNull();
    }

    @Test
    void rejectsMalformedAndInconsistentProfiles() throws IOException {
        rejects("reference-host", r -> r.put("hostname", "x"), "UNKNOWN_FIELD", "/hostname");
        rejects("reference-host", r -> r.put("schema", "rpg-engine.hardware-profile/2"), "SCHEMA", "/schema");
        rejects("reference-host", r -> r.put("generatedAt", "yesterday"), "DATE", "/generatedAt");
        rejects("reference-host", r -> r.put("gpuSource", "unavailable"), "GPUS", "/gpus");
        rejects("reference-host", r -> ((ObjectNode) r.get("disk")).put("path", "/home/owner"), "VALUE", "/disk/path");
        rejects("reference-host", r -> ((ObjectNode) r.get("containerGpu")).put("status", "visible"), "CONTAINER_GPU",
                "/containerGpu/status");
        rejects("gpu-8gb", r -> ((ObjectNode) r.get("containerGpu")).put("status", "not-visible"), "CONTAINER_GPU",
                "/containerGpu/status");
        rejects("gpu-8gb", r -> ((ObjectNode) r.get("containerGpu")).put("status", "not-checked"), "CONTAINER_GPU",
                "/containerGpu/status");
        rejects("reference-host", r -> ((ArrayNode) r.get("containerGpu").get("devices")).add(JSON.createObjectNode()
                .put("name", "Vulkan0").put("description", "x").put("memoryTotalMiB", 1).put("memoryFreeMiB", 1)),
                "CONTAINER_GPU", "/containerGpu/devices");
        rejects("no-gpu", r -> r.set("containerEngine", JSON.createObjectNode().put("available", false).put("cpus", 4)),
                "UNKNOWN_FIELD", "/containerEngine/cpus");
        rejects("reference-host", r -> ((ObjectNode) r.get("gpus").get(0)).put("memoryTotalMiB", -1), "RANGE",
                "/gpus/0/memoryTotalMiB");
        rejects("reference-host", r -> ((ObjectNode) r.get("memory")).put("scope", "cloud"), "VALUE", "/memory/scope");
    }
}
