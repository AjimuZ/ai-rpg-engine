package org.rpgengine.inference.slots;

import java.util.Optional;
import java.util.Set;
import org.rpgengine.inference.SlotAssignment;

/**
 * Which protocol adapters may serve which slot, and the billing and privacy class each one fixes
 * (docs/34 section 4). The sets are closed: membership does not mean an adapter is installed, only
 * that ADR 0021 decision 1 names the protocol. Stateless.
 */
public final class SlotBackends {
    /** Image protocol adapters of ADR 0021 decision 1. */
    public static final Set<String> IMAGE_BACKENDS = Set.of("local-image", "a1111-http", "openai-images");
    /** The one backend whose class the owner declares: an owner-run or hosted compatible endpoint. */
    public static final String DECLARED_CLASS_BACKEND = "openai-compatible";

    private SlotBackends() {
    }

    /**
     * @param slot a slot
     * @param backend protocol adapter name
     * @return whether the adapter protocol may serve the slot
     */
    public static boolean serves(ModelSlot slot, String backend) {
        return slot == ModelSlot.IMAGE ? IMAGE_BACKENDS.contains(backend) : SlotAssignment.TEXT_BACKENDS.contains(backend);
    }

    /**
     * @param backend protocol adapter name
     * @return the class the protocol fixes, or empty when the owner must declare it
     *         ({@value #DECLARED_CLASS_BACKEND}) or the backend is unknown
     */
    public static Optional<BackendClass> fixedClass(String backend) {
        return switch (backend) {
            case "codex", "claude" -> Optional.of(BackendClass.SUBSCRIPTION);
            case "local", "local-image", "a1111-http" -> Optional.of(BackendClass.LOCAL);
            case "anthropic-api", "openai-images" -> Optional.of(BackendClass.METERED);
            default -> Optional.empty();
        };
    }
}
