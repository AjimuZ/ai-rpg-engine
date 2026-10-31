package org.rpgengine.inference.slots;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.SlotAssignment;

/**
 * One validated owner assignment of a slot: backend, model, effort, the owner's class declaration
 * for an endpoint, and the fallbacks the owner configured (ADR 0021 decisions 2 and 8).
 *
 * <p>A fallback is configuration, never a failover: it is considered only when it is listed here,
 * and only within the class of the primary assignment. The constructor refuses a list that would
 * cross a class, so no stored setting can ever describe a silent class change.
 *
 * <p>The image slot carries no effort; it is stored as {@value #IMAGE_EFFORT}.
 *
 * @param slot the slot assigned
 * @param backend protocol adapter name; must be allowed to serve {@code slot}
 * @param model catalog model identifier
 * @param effort reasoning effort, or {@value #IMAGE_EFFORT} for the image slot
 * @param declaredClass {@code local} or {@code metered} for an {@value SlotBackends#DECLARED_CLASS_BACKEND}
 *        endpoint; null for every other backend
 * @param fallbacks ordered same-class alternatives, at most {@link SlotAssignment#MAX_ALTERNATIVES}
 */
public record SlotSetting(ModelSlot slot, String backend, String model, String effort, BackendClass declaredClass,
        List<Fallback> fallbacks) {
    /** Effort stored for the image slot, which has none. */
    public static final String IMAGE_EFFORT = "none";

    /** A configured fallback; its class is derived from the backend, never stored. */
    public record Fallback(String backend, String model, String effort) {
        /** Require every component. */
        public Fallback {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(effort, "effort");
        }
    }

    /** A refusal with a fixed public code; the message never echoes a value. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        Invalid(String code) {
            super(code);
            this.code = code;
        }

        /**
         * @return one of {@code SLOT_BACKEND_UNSUPPORTED}, {@code SLOT_MODEL_INVALID},
         *         {@code SLOT_CLASS_DECLARATION_INVALID}, {@code FALLBACK_INVALID} or
         *         {@code FALLBACK_CLASS_CROSSING}
         */
        public String code() {
            return code;
        }
    }

    /**
     * Validate and freeze the assignment.
     *
     * @throws NullPointerException for a missing component
     * @throws Invalid for a backend that cannot serve the slot, a malformed model or effort, a class
     *         declaration that is missing, superfluous or not {@code local}/{@code metered}, an
     *         invalid or duplicate fallback, or a fallback outside the primary assignment's class
     */
    public SlotSetting {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(effort, "effort");
        Objects.requireNonNull(fallbacks, "fallbacks");
        if (!SlotBackends.serves(slot, backend)) {
            throw new Invalid("SLOT_BACKEND_UNSUPPORTED");
        }
        validPair(slot, model, effort, "SLOT_MODEL_INVALID");
        boolean declares = backend.equals(SlotBackends.DECLARED_CLASS_BACKEND);
        if (declares != (declaredClass != null) || (declares && declaredClass == BackendClass.SUBSCRIPTION)) {
            throw new Invalid("SLOT_CLASS_DECLARATION_INVALID");
        }
        fallbacks = List.copyOf(fallbacks);
        if (fallbacks.size() > SlotAssignment.MAX_ALTERNATIVES) {
            throw new Invalid("FALLBACK_INVALID");
        }
        BackendClass own = declaredClass != null ? declaredClass : SlotBackends.fixedClass(backend).orElseThrow();
        Set<Fallback> seen = new HashSet<>(Set.of(new Fallback(backend, model, effort)));
        for (Fallback entry : fallbacks) {
            if (!SlotBackends.serves(slot, entry.backend())) {
                throw new Invalid("FALLBACK_INVALID");
            }
            validPair(slot, entry.model(), entry.effort(), "FALLBACK_INVALID");
            if (!seen.add(entry)) {
                throw new Invalid("FALLBACK_INVALID");
            }
            if (entryClass(backend, declaredClass, entry).filter(own::equals).isEmpty()) {
                throw new Invalid("FALLBACK_CLASS_CROSSING");
            }
        }
    }

    private static void validPair(ModelSlot slot, String model, String effort, String code) {
        try {
            new EffectiveSettings(model, effort);
        } catch (IllegalArgumentException e) {
            throw new Invalid(code);
        }
        if (slot == ModelSlot.IMAGE && !effort.equals(IMAGE_EFFORT)) {
            throw new Invalid(code);
        }
    }

    /**
     * The class of a fallback entry, or empty when it cannot be established. An endpoint entry
     * inherits the owner's declaration only when the primary is itself an endpoint, because a
     * declaration says nothing about a different endpoint behind a different primary.
     */
    private static Optional<BackendClass> entryClass(String primaryBackend, BackendClass primaryDeclared,
            Fallback entry) {
        if (entry.backend().equals(SlotBackends.DECLARED_CLASS_BACKEND)) {
            return primaryBackend.equals(SlotBackends.DECLARED_CLASS_BACKEND) ? Optional.of(primaryDeclared)
                    : Optional.empty();
        }
        return SlotBackends.fixedClass(entry.backend());
    }

    /**
     * @param slot the slot assigned
     * @param backend protocol adapter name
     * @param model catalog model identifier
     * @param effort reasoning effort; null for the image slot
     * @param declaredClass the owner's class declaration for an endpoint, else null
     * @param fallbacks ordered same-class alternatives
     * @return the validated assignment
     * @throws Invalid under the rules of the canonical constructor
     */
    public static SlotSetting of(ModelSlot slot, String backend, String model, String effort,
            BackendClass declaredClass, List<Fallback> fallbacks) {
        String resolvedEffort = effort == null && slot == ModelSlot.IMAGE ? IMAGE_EFFORT : effort;
        return new SlotSetting(slot, backend, model, Objects.requireNonNull(resolvedEffort, "effort"),
                declaredClass, fallbacks);
    }

    /** @return the billing and privacy class of the primary assignment */
    public BackendClass backendClass() {
        return declaredClass != null ? declaredClass : SlotBackends.fixedClass(backend).orElseThrow();
    }

    /**
     * @return the class of {@code entry} under this assignment; always the primary's class for a
     *         setting this constructor accepted
     */
    public BackendClass classOf(Fallback entry) {
        return entryClass(backend, declaredClass, entry).orElseThrow();
    }

    /** @return the primary model and effort */
    public EffectiveSettings primary() {
        return new EffectiveSettings(model, effort);
    }

    /**
     * Project a text assignment onto the resolver's view. Only fallbacks on the primary's own
     * backend appear, because {@code ProfileResolver} resolves one backend per task (ADR 0021
     * decision 8); fallbacks on another backend of the same class stay in
     * {@link SlotFallback} and are never offered to a different adapter implicitly.
     *
     * @return the assignment the profile resolver understands
     * @throws IllegalStateException for the image slot
     */
    public SlotAssignment toAssignment() {
        if (slot == ModelSlot.IMAGE) {
            throw new IllegalStateException("the image slot has no text assignment");
        }
        List<EffectiveSettings> alternatives = new ArrayList<>();
        for (Fallback entry : fallbacks) {
            if (entry.backend().equals(backend)) {
                alternatives.add(new EffectiveSettings(entry.model(), entry.effort()));
            }
        }
        return new SlotAssignment(backend, primary(), alternatives);
    }
}
