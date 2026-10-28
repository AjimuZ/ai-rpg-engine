package org.rpgengine.inference;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Slot assignments read once at startup from private settings
 * {@code engine.inference.slots.<light|standard|deliberate>.model} and {@code .effort}
 * (environment variables such as {@code ENGINE_INFERENCE_SLOTS_STANDARD_MODEL}). No model name
 * is shipped as a default: an unassigned slot stays empty. A slot with only one of the two
 * values, or with a malformed identifier, is a configuration error that fails startup rather
 * than silently degrading. Catalog membership is checked by the adapter on each task.
 */
@Component
public class ConfiguredSlotAssignments implements SlotAssignments {
    private final Map<InferenceSlot, EffectiveSettings> assignments = new EnumMap<>(InferenceSlot.class);

    /**
     * @param environment Spring environment holding the private settings
     * @throws IllegalStateException when a slot is half-assigned or holds an invalid identifier
     */
    public ConfiguredSlotAssignments(Environment environment) {
        for (InferenceSlot slot : InferenceSlot.values()) {
            String prefix = "engine.inference.slots." + slot.key() + ".";
            String model = blankToNull(environment.getProperty(prefix + "model"));
            String effort = blankToNull(environment.getProperty(prefix + "effort"));
            if (model == null && effort == null) {
                continue;
            }
            if (model == null || effort == null) {
                throw new IllegalStateException("slot " + slot.key() + " needs both model and effort");
            }
            try {
                assignments.put(slot, new EffectiveSettings(model, effort));
            } catch (IllegalArgumentException e) {
                // Never echo the configured value: settings are private owner data.
                throw new IllegalStateException("slot " + slot.key() + " has an invalid model or effort", e);
            }
        }
    }

    /** @return the configured settings for {@code slot}, or empty when unassigned */
    @Override
    public Optional<EffectiveSettings> resolve(InferenceSlot slot) {
        return Optional.ofNullable(assignments.get(slot));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
