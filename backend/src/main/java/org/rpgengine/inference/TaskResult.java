package org.rpgengine.inference;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Terminal outcome of one task. The output is untrusted text: structured output constrains
 * generation only, so callers validate it structurally and semantically before any use
 * (docs/06 section 6). Error codes are fixed, non-sensitive identifiers; runtime messages,
 * prompts and account data never appear here.
 *
 * @param taskId the request identity
 * @param status terminal status
 * @param output final agent message, present exactly when {@code status} is
 *        {@link Status#COMPLETED}
 * @param errorCode fixed lowercase code, absent exactly when {@code status} is
 *        {@link Status#COMPLETED}
 * @param provenance runtime, version and effective settings of the turn that ran, absent when
 *        no turn was started
 * @param freshSession true when the runtime conversation behind the logical session was
 *        created for this task, so the caller knew nothing of its earlier context
 */
public record TaskResult(UUID taskId, Status status, String output, String errorCode, Provenance provenance,
        boolean freshSession) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{0,63}|[a-z][A-Za-z0-9]{0,63}");

    /** Terminal statuses of a task. */
    public enum Status {
        /** The runtime confirmed a completed turn and returned its final output. */
        COMPLETED,
        /** Cancelled by the caller or by shutdown; a started turn was confirmed interrupted. */
        CANCELLED,
        /** The runtime reported failure or broke the protocol/isolation contract. */
        FAILED,
        /** The budget expired; a started turn was confirmed interrupted. */
        TIMED_OUT,
        /**
         * The outcome could not be confirmed (lost connection, unacknowledged request or
         * interruption). The adapter reconciles the runtime before running further work; the
         * proposal has no authority, so a fresh attempt is safe while nothing was committed.
         */
        UNRESOLVED,
        /** No runtime is configured, connected, signed in or compatible; nothing was sent. */
        UNAVAILABLE,
        /** Admission refused (queue full, duplicate identity or stopping); nothing was sent. */
        REJECTED
    }

    /**
     * Provenance recorded with each task (ADR 0020 decision 5).
     *
     * @param runtime runtime name, for example {@code codex}
     * @param runtimeVersion pinned runtime version
     * @param model effective model sent on the turn
     * @param effort effective reasoning effort sent on the turn
     * @param degradedFrom the owner's preferred pair when an owner-approved alternative ran
     *        because the preferred pair was unsupported (P08.4); null when the preferred pair ran
     */
    public record Provenance(String runtime, String runtimeVersion, String model, String effort,
            EffectiveSettings degradedFrom) {
        /** Provenance of a turn that ran the owner's preferred pair. */
        public Provenance(String runtime, String runtimeVersion, String model, String effort) {
            this(runtime, runtimeVersion, model, effort, null);
        }

        /** Require every component except {@code degradedFrom}. */
        public Provenance {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(runtimeVersion, "runtimeVersion");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(effort, "effort");
        }
    }

    /**
     * Enforce the output/error-code pairing.
     *
     * @throws IllegalArgumentException when output or error code contradict the status, or
     *         the code is not a fixed identifier
     */
    public TaskResult {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(status, "status");
        boolean completed = status == Status.COMPLETED;
        if (completed != (output != null) || completed == (errorCode != null)) {
            throw new IllegalArgumentException("output is required exactly for completed tasks");
        }
        if (errorCode != null && !CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("error code must be a fixed identifier");
        }
    }

    /**
     * @param taskId request identity
     * @param status non-completed status
     * @param errorCode fixed code
     * @return a result that never reached a runtime turn
     */
    public static TaskResult withoutTurn(UUID taskId, Status status, String errorCode) {
        return new TaskResult(taskId, status, null, errorCode, null, false);
    }
}
