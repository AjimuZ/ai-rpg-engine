package org.rpgengine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Reading of the private owner settings behind profile resolution: slot backends, alternatives,
 * the default backend, allowlists and the {@code fixedQuality} slot. Every malformed value must
 * fail startup with a message that does not echo it.
 */
class ProfileSettingsTest {
    private static final EffectiveSettings A_LOW = new EffectiveSettings("model-a", "low");
    private static final EffectiveSettings B_HIGH = new EffectiveSettings("model-b", "high");

    private static MockEnvironment env(String... pairs) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < pairs.length; i += 2) {
            environment.setProperty(pairs[i], pairs[i + 1]);
        }
        return environment;
    }

    @Test
    void aSlotWithoutBackendUsesTheInstallationDefaultAndThenCodex() {
        var plain = new ConfiguredSlotAssignments(env("engine.inference.slots.light.model", "model-a",
                "engine.inference.slots.light.effort", "low"));
        assertThat(plain.resolve(InferenceSlot.LIGHT)).contains(SlotAssignment.of("codex", A_LOW));

        var selected = new ConfiguredSlotAssignments(env("engine.inference.backend", "claude",
                "engine.inference.slots.light.model", "model-a", "engine.inference.slots.light.effort", "low"));
        assertThat(selected.resolve(InferenceSlot.LIGHT)).contains(SlotAssignment.of("claude", A_LOW));
        assertThat(selected.resolve(InferenceSlot.STANDARD)).isEmpty();
    }

    @Test
    void aSlotBackendOverridesTheDefaultAndAlternativesAreParsedInOrder() {
        var assignments = new ConfiguredSlotAssignments(env("engine.inference.backend", "claude",
                "engine.inference.slots.deliberate.backend", "codex",
                "engine.inference.slots.deliberate.model", "model-b",
                "engine.inference.slots.deliberate.effort", "high",
                "engine.inference.slots.deliberate.alternatives", "model-a@low, model-a@medium"));
        assertThat(assignments.resolve(InferenceSlot.DELIBERATE)).contains(new SlotAssignment("codex", B_HIGH,
                List.of(A_LOW, new EffectiveSettings("model-a", "medium"))));
    }

    @Test
    void invalidSlotSettingsFailStartupWithoutEchoingTheValues() {
        String[][] bad = {
            {"engine.inference.slots.light.backend", "elsewhere-secret"},
            {"engine.inference.slots.light.alternatives", "secret-model"},
            {"engine.inference.slots.light.alternatives", "model-a@low"},
            {"engine.inference.slots.light.alternatives", "model-a@low,model-a@low,model-a@medium,m1@low,m2@low"},
        };
        for (String[] extra : bad) {
            MockEnvironment environment = env("engine.inference.slots.light.model", "model-a",
                    "engine.inference.slots.light.effort", "low", extra[0], extra[1]);
            assertThatThrownBy(() -> new ConfiguredSlotAssignments(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("slot light")
                    .satisfies(failure -> assertThat(failure.toString()).doesNotContain("secret"));
        }
        // A backend or alternatives list without a preferred pair is half an assignment.
        assertThatThrownBy(() -> new ConfiguredSlotAssignments(env("engine.inference.slots.light.backend", "codex")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("slot light needs both model and effort");
        assertThatThrownBy(() -> new ConfiguredSlotAssignments(env("engine.inference.slots.standard.alternatives",
                "model-a@low"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slot standard needs both model and effort");
    }

    @Test
    void allowlistsAreReadPerBackendAndBlankMeansUnset() {
        ModelAllowlist allowlist = ModelAllowlist.from(env("engine.inference.allowlist.claude", "model-a@low, model-b@high",
                "engine.inference.allowlist.codex", "  "));
        assertThat(allowlist.configured("claude")).isTrue();
        assertThat(allowlist.permits("claude", A_LOW)).isTrue();
        assertThat(allowlist.permits("claude", B_HIGH)).isTrue();
        assertThat(allowlist.permits("claude", new EffectiveSettings("model-a", "high"))).isFalse();
        assertThat(allowlist.configured("codex")).isFalse();
        assertThat(allowlist.permits("codex", A_LOW)).isFalse();
        assertThat(ModelAllowlist.from(env()).configured("claude")).isFalse();
    }

    @Test
    void malformedOrOversizedAllowlistsFailStartupNamingOnlyTheBackend() {
        assertThatThrownBy(() -> ModelAllowlist.from(env("engine.inference.allowlist.claude", "secret-model")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("backend claude")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("secret"));
        String oversized = String.join(",", java.util.stream.IntStream.range(0, ModelAllowlist.MAX_ENTRIES + 1)
                .mapToObj(i -> "model-" + i + "@low").toList());
        assertThatThrownBy(() -> ModelAllowlist.from(env("engine.inference.allowlist.codex", oversized)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("backend codex");
    }
}
