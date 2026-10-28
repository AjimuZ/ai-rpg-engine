package org.rpgengine.inference;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Resolves a task's logical profile (docs/06 section 9) to the explicit model and effort of one
 * turn, through the owner's slot assignment and against the assigned backend's catalog or
 * approved allowlist (ADR 0021 decisions 2 and 3, P08.4).
 *
 * <p>Resolution runs for every task and keeps no state between tasks, so a prior expensive task
 * can never leave an ordinary one with an inherited setting. It proceeds in this order, and each
 * refusal is a fixed code that the adapter reports without contacting the runtime:
 * <ol>
 *   <li><b>Profile.</b> The requested slot is {@code light}, {@code standard} or
 *       {@code deliberate}. When the owner selected {@code fixedQuality}, every request resolves
 *       through that one slot instead, whatever weight class the caller asked for.</li>
 *   <li><b>Assignment.</b> An unassigned slot is {@code slot-unassigned}: there is no shipped
 *       default model.</li>
 *   <li><b>Backend.</b> The slot's backend must be the backend of the adapter resolving the task,
 *       otherwise {@code slot-backend-mismatch}. The resolver never reroutes to another backend,
 *       because that would change provider, billing class and privacy class silently.</li>
 *   <li><b>Catalog.</b> A usable pair must be in the backend's discovered catalog (when it has
 *       one) and in the owner's allowlist (when one is configured). With neither source the pair
 *       cannot be verified and the task ends {@code catalog-unavailable}.</li>
 *   <li><b>Degradation.</b> The owner's primary pair is tried first, then the owner-approved
 *       alternatives in order. The first usable pair wins and is reported as degraded when it is
 *       not the primary. When none is usable the task ends {@code slot-unsupported}; the engine
 *       never invents a substitute.</li>
 * </ol>
 *
 * <p>Instances are immutable apart from the assignment source, which implementations must keep
 * thread-safe.
 */
public final class ProfileResolver {
    /** Outcome of one resolution. */
    public sealed interface Resolution permits Resolved, Refused {
    }

    /**
     * A usable pair.
     *
     * @param slot the slot the owner's assignment was read from: the requested slot, or the
     *        fixed-quality slot that replaced it
     * @param settings model and effort to send explicitly on the turn
     * @param degradedFrom the owner's primary pair when an approved alternative was selected
     *        because the primary was unsupported; null when the primary pair runs
     */
    public record Resolved(InferenceSlot slot, EffectiveSettings settings, EffectiveSettings degradedFrom)
            implements Resolution {
        /** Require the slot and the settings. */
        public Resolved {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(settings, "settings");
        }

        /** @return whether an approved alternative replaced the primary pair */
        public boolean degraded() {
            return degradedFrom != null;
        }
    }

    /**
     * A refusal; nothing is sent to any runtime.
     *
     * @param code fixed task error code: {@code slot-unassigned}, {@code slot-backend-mismatch},
     *        {@code catalog-unavailable} or {@code slot-unsupported}
     */
    public record Refused(String code) implements Resolution {
        /** Require the code. */
        public Refused {
            Objects.requireNonNull(code, "code");
        }
    }

    private final SlotAssignments assignments;
    private final ModelAllowlist allowlist;
    private final Optional<InferenceSlot> fixedQuality;

    /**
     * @param assignments owner slot assignment
     * @param allowlist owner-approved pairs per backend
     * @param fixedQuality the owner-selected {@code fixedQuality} slot, or empty to resolve each
     *        task's requested slot
     */
    public ProfileResolver(SlotAssignments assignments, ModelAllowlist allowlist,
            Optional<InferenceSlot> fixedQuality) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.fixedQuality = Objects.requireNonNull(fixedQuality, "fixedQuality");
    }

    /**
     * Resolve one task.
     *
     * @param requested the slot named by the task
     * @param backend backend of the adapter that resolves the task
     * @param discovered tests a pair against the backend's discovered catalog, or null when the
     *        backend has no enumerable catalog and only the allowlist can approve a pair
     * @return the explicit settings to use, or the fixed refusal code
     */
    public Resolution resolve(InferenceSlot requested, String backend, Predicate<EffectiveSettings> discovered) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(backend, "backend");
        InferenceSlot slot = fixedQuality.orElse(requested);
        Optional<SlotAssignment> assigned = assignments.resolve(slot);
        if (assigned.isEmpty()) {
            return new Refused("slot-unassigned");
        }
        SlotAssignment assignment = assigned.get();
        if (!assignment.backend().equals(backend)) {
            return new Refused("slot-backend-mismatch");
        }
        if (discovered == null && !allowlist.configured(backend)) {
            return new Refused("catalog-unavailable");
        }
        for (EffectiveSettings candidate : assignment.candidates()) {
            boolean listed = discovered == null || discovered.test(candidate);
            boolean approved = !allowlist.configured(backend) || allowlist.permits(backend, candidate);
            if (listed && approved) {
                return new Resolved(slot, candidate, candidate.equals(assignment.primary()) ? null
                        : assignment.primary());
            }
        }
        return new Refused("slot-unsupported");
    }
}
