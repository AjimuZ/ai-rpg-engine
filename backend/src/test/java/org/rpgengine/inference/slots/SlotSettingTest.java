package org.rpgengine.inference.slots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;

/** Class badges, same-class fallback rules and the explicit refusal of {@link SlotFallback}. */
class SlotSettingTest {
    private static SlotSetting.Fallback fb(String backend, String model) {
        return new SlotSetting.Fallback(backend, model, "low");
    }

    private static SlotSetting text(String backend, BackendClass declared, SlotSetting.Fallback... fallbacks) {
        return SlotSetting.of(ModelSlot.STANDARD, backend, "model-a", "medium", declared, List.of(fallbacks));
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
        } catch (SlotSetting.Invalid refused) {
            return refused.code();
        }
        return "accepted";
    }

    @Test
    void everyBackendHasItsClassAndBadge() {
        assertThat(text("codex", null).backendClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(text("claude", null).backendClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(text("local", null).backendClass()).isEqualTo(BackendClass.LOCAL);
        assertThat(text("anthropic-api", null).backendClass()).isEqualTo(BackendClass.METERED);
        assertThat(text("openai-compatible", BackendClass.LOCAL).backendClass()).isEqualTo(BackendClass.LOCAL);
        assertThat(text("openai-compatible", BackendClass.METERED).backendClass()).isEqualTo(BackendClass.METERED);
        assertThat(SlotSetting.of(ModelSlot.IMAGE, "local-image", "m", null, null, List.of()).backendClass())
                .isEqualTo(BackendClass.LOCAL);
        assertThat(SlotSetting.of(ModelSlot.IMAGE, "a1111-http", "m", null, null, List.of()).backendClass())
                .isEqualTo(BackendClass.LOCAL);
        assertThat(SlotSetting.of(ModelSlot.IMAGE, "openai-images", "m", null, null, List.of()).backendClass())
                .isEqualTo(BackendClass.METERED);

        assertThat(BackendClass.LOCAL.billing()).isEqualTo("free");
        assertThat(BackendClass.LOCAL.privacy()).isEqualTo("this-machine");
        assertThat(BackendClass.LOCAL.leavesMachine()).isFalse();
        assertThat(BackendClass.SUBSCRIPTION.billing()).isEqualTo("plan");
        assertThat(BackendClass.SUBSCRIPTION.privacy()).isEqualTo("runtime-vendor");
        assertThat(BackendClass.SUBSCRIPTION.leavesMachine()).isTrue();
        assertThat(BackendClass.METERED.billing()).isEqualTo("per-use");
        assertThat(BackendClass.METERED.privacy()).isEqualTo("api-vendor");
        assertThat(BackendClass.METERED.leavesMachine()).isTrue();
    }

    @Test
    void anEndpointNeedsADeclaredLocalOrMeteredClassAndNothingElseMayDeclareOne() {
        assertThat(codeOf(() -> text("openai-compatible", null))).isEqualTo("SLOT_CLASS_DECLARATION_INVALID");
        assertThat(codeOf(() -> text("openai-compatible", BackendClass.SUBSCRIPTION)))
                .isEqualTo("SLOT_CLASS_DECLARATION_INVALID");
        assertThat(codeOf(() -> text("codex", BackendClass.LOCAL))).isEqualTo("SLOT_CLASS_DECLARATION_INVALID");
        assertThat(codeOf(() -> text("local", BackendClass.METERED))).isEqualTo("SLOT_CLASS_DECLARATION_INVALID");
    }

    @Test
    void aBackendMustBeAllowedToServeTheSlot() {
        assertThat(codeOf(() -> text("local-image", null))).isEqualTo("SLOT_BACKEND_UNSUPPORTED");
        assertThat(codeOf(() -> text("nonsense", null))).isEqualTo("SLOT_BACKEND_UNSUPPORTED");
        assertThat(codeOf(() -> SlotSetting.of(ModelSlot.IMAGE, "codex", "m", null, null, List.of())))
                .isEqualTo("SLOT_BACKEND_UNSUPPORTED");
    }

    @Test
    void modelAndEffortAreValidatedAndTheImageSlotHasNoEffort() {
        assertThat(codeOf(() -> SlotSetting.of(ModelSlot.STANDARD, "codex", "bad model!", "low", null, List.of())))
                .isEqualTo("SLOT_MODEL_INVALID");
        assertThat(codeOf(() -> SlotSetting.of(ModelSlot.STANDARD, "codex", "m", "HIGH", null, List.of())))
                .isEqualTo("SLOT_MODEL_INVALID");
        assertThat(codeOf(() -> SlotSetting.of(ModelSlot.IMAGE, "local-image", "m", "high", null, List.of())))
                .isEqualTo("SLOT_MODEL_INVALID");
        assertThat(SlotSetting.of(ModelSlot.IMAGE, "local-image", "m", null, null, List.of()).effort())
                .isEqualTo("none");
        assertThatThrownBy(() -> SlotSetting.of(ModelSlot.STANDARD, "codex", "m", null, null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFallbackStaysInTheClassOfThePrimary() {
        SlotSetting subscription = text("codex", null, fb("claude", "model-b"), fb("codex", "model-c"));
        assertThat(subscription.fallbacks()).hasSize(2);
        assertThat(subscription.classOf(subscription.fallbacks().get(0))).isEqualTo(BackendClass.SUBSCRIPTION);

        assertThat(codeOf(() -> text("codex", null, fb("anthropic-api", "model-b")))).isEqualTo("FALLBACK_CLASS_CROSSING");
        assertThat(codeOf(() -> text("codex", null, fb("local", "model-b")))).isEqualTo("FALLBACK_CLASS_CROSSING");
        assertThat(codeOf(() -> text("local", null, fb("codex", "model-b")))).isEqualTo("FALLBACK_CLASS_CROSSING");
        assertThat(codeOf(() -> text("anthropic-api", null, fb("claude", "model-b")))).isEqualTo("FALLBACK_CLASS_CROSSING");
        // An endpoint inherits the owner's declaration only beside another endpoint of the same slot.
        assertThat(codeOf(() -> text("codex", null, fb("openai-compatible", "model-b")))).isEqualTo("FALLBACK_CLASS_CROSSING");
        assertThat(codeOf(() -> text("openai-compatible", BackendClass.LOCAL, fb("anthropic-api", "model-b"))))
                .isEqualTo("FALLBACK_CLASS_CROSSING");
        assertThat(codeOf(() -> text("openai-compatible", BackendClass.LOCAL, fb("local", "model-b")))).isEqualTo("accepted");
        assertThat(codeOf(() -> text("openai-compatible", BackendClass.METERED, fb("anthropic-api", "model-b"))))
                .isEqualTo("accepted");
        assertThat(codeOf(() -> SlotSetting.of(ModelSlot.IMAGE, "local-image", "m", null, null,
                List.of(new SlotSetting.Fallback("openai-images", "m2", "none"))))).isEqualTo("FALLBACK_CLASS_CROSSING");
    }

    @Test
    void aFallbackListIsBoundedDistinctAndWellFormed() {
        assertThat(codeOf(() -> text("codex", null, fb("codex", "model-a-x"), fb("codex", "m2"), fb("codex", "m3"),
                fb("codex", "m4"), fb("codex", "m5")))).isEqualTo("FALLBACK_INVALID");
        assertThat(codeOf(() -> text("codex", null, fb("codex", "m2"), fb("codex", "m2")))).isEqualTo("FALLBACK_INVALID");
        assertThat(codeOf(() -> text("codex", null, new SlotSetting.Fallback("codex", "model-a", "medium"))))
                .isEqualTo("FALLBACK_INVALID");
        assertThat(codeOf(() -> text("codex", null, fb("local-image", "m2")))).isEqualTo("FALLBACK_INVALID");
        assertThat(codeOf(() -> text("codex", null, fb("codex", "bad model")))).isEqualTo("FALLBACK_INVALID");
    }

    @Test
    void theResolverSeesOnlySameBackendFallbacks() {
        SlotSetting setting = text("codex", null, fb("claude", "model-b"), fb("codex", "model-c"));
        var assignment = setting.toAssignment();
        assertThat(assignment.backend()).isEqualTo("codex");
        assertThat(assignment.primary()).isEqualTo(new EffectiveSettings("model-a", "medium"));
        assertThat(assignment.alternatives()).containsExactly(new EffectiveSettings("model-c", "low"));
        assertThatThrownBy(() -> SlotSetting.of(ModelSlot.IMAGE, "local-image", "m", null, null, List.of())
                .toAssignment()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutConfiguredFallbacksAnUnavailablePrimaryIsAnExplicitRefusal() {
        SlotSetting setting = text("codex", null);
        List<String> asked = new java.util.ArrayList<>();
        var selection = SlotFallback.select(setting, candidate -> {
            asked.add(candidate.backend() + ":" + candidate.model());
            return false;
        });
        assertThat(selection).isEqualTo(new SlotFallback.Unavailable("slot-unavailable"));
        assertThat(asked).containsExactly("codex:model-a");
    }

    @Test
    void aConfiguredSameClassFallbackServesOnlyWhenThePrimaryIsUnavailable() {
        SlotSetting setting = text("codex", null, fb("claude", "model-b"), fb("codex", "model-c"));
        var primaryUp = SlotFallback.select(setting, candidate -> true);
        assertThat(primaryUp).isEqualTo(new SlotFallback.Chosen(new SlotSetting.Fallback("codex", "model-a", "medium"), false));

        Set<String> up = Set.of("codex:model-c");
        var second = SlotFallback.select(setting, candidate -> up.contains(candidate.backend() + ":" + candidate.model()));
        assertThat(second).isEqualTo(new SlotFallback.Chosen(fb("codex", "model-c"), true));

        var none = SlotFallback.select(setting, candidate -> false);
        assertThat(none).isEqualTo(new SlotFallback.Unavailable("slot-unavailable"));
    }

    @Test
    void aMeteredAvailableModelIsNeverChosenForASubscriptionSlot() {
        // The setting cannot even hold the metered candidate, so no availability test can reach it.
        assertThat(codeOf(() -> text("codex", null, fb("anthropic-api", "model-b")))).isEqualTo("FALLBACK_CLASS_CROSSING");
        SlotSetting setting = text("codex", null);
        var selection = SlotFallback.select(setting, candidate -> candidate.backend().equals("anthropic-api"));
        assertThat(selection).isInstanceOf(SlotFallback.Unavailable.class);
    }
}
