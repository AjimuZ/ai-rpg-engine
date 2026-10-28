package org.rpgengine.inference;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Slot assignments read once at startup from private settings
 * {@code engine.inference.slots.<light|standard|deliberate>.*} (environment variables such as
 * {@code ENGINE_INFERENCE_SLOTS_STANDARD_MODEL}):
 * <ul>
 *   <li>{@code model} and {@code effort}: the preferred pair, both or neither;</li>
 *   <li>{@code backend}: the serving backend, defaulting to {@code engine.inference.backend}
 *       (itself {@value #DEFAULT_BACKEND} when unset), which is the default-runtime role that
 *       ADR 0020 gives {@code NARRATION_RUNTIME} for the text slots;</li>
 *   <li>{@code alternatives}: optional comma-separated {@code model@effort} degradation list.</li>
 * </ul>
 * No model name is shipped as a default: an unassigned slot stays empty. A slot with only one of
 * the pair, a malformed identifier, an unknown backend or alternatives without a preferred pair is
 * a configuration error that fails startup rather than silently degrading, and no message echoes
 * a configured value. Catalog and allowlist membership is checked by {@link ProfileResolver} on
 * each task, not here, because the catalog is only known once a runtime is connected.
 */
@Component
public class ConfiguredSlotAssignments implements SlotAssignments {
    /** Backend of a slot that names none and of an installation that selects none (ADR 0020). */
    public static final String DEFAULT_BACKEND = "codex";

    private final Map<InferenceSlot, SlotAssignment> assignments = new EnumMap<>(InferenceSlot.class);

    /**
     * @param environment Spring environment holding the private settings
     * @throws IllegalStateException when a slot is half-assigned or holds an invalid value
     */
    public ConfiguredSlotAssignments(Environment environment) {
        String defaultBackend = blankToNull(environment.getProperty("engine.inference.backend"));
        for (InferenceSlot slot : InferenceSlot.values()) {
            String prefix = "engine.inference.slots." + slot.key() + ".";
            String model = blankToNull(environment.getProperty(prefix + "model"));
            String effort = blankToNull(environment.getProperty(prefix + "effort"));
            String backend = blankToNull(environment.getProperty(prefix + "backend"));
            String alternatives = blankToNull(environment.getProperty(prefix + "alternatives"));
            if (model == null && effort == null) {
                if (backend != null || alternatives != null) {
                    throw new IllegalStateException("slot " + slot.key() + " needs both model and effort");
                }
                continue;
            }
            if (model == null || effort == null) {
                throw new IllegalStateException("slot " + slot.key() + " needs both model and effort");
            }
            try {
                List<EffectiveSettings> approved = new ArrayList<>();
                if (alternatives != null) {
                    for (String entry : alternatives.split(",")) {
                        approved.add(EffectiveSettings.parse(entry.strip()));
                    }
                }
                assignments.put(slot, new SlotAssignment(
                        backend != null ? backend : defaultBackend != null ? defaultBackend : DEFAULT_BACKEND,
                        new EffectiveSettings(model, effort), approved));
            } catch (IllegalArgumentException e) {
                // Never echo the configured value: settings are private owner data.
                throw new IllegalStateException("slot " + slot.key()
                        + " has an invalid backend, model, effort or alternatives list", e);
            }
        }
    }

    /** @return the configured assignment for {@code slot}, or empty when unassigned */
    @Override
    public Optional<SlotAssignment> resolve(InferenceSlot slot) {
        return Optional.ofNullable(assignments.get(slot));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
