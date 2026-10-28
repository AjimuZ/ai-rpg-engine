package org.rpgengine.inference;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * One bounded inference task. The request carries already-authorized, audience-filtered text
 * only; building that context is the caller's job (P08.2, P09.2), and nothing in a request can
 * select a runtime, model, tool, path or flag.
 *
 * @param taskId caller-chosen identity, used for correlation and idempotent bookkeeping; a
 *        port never runs two tasks with the same live identity
 * @param sessionId logical AI session (ADR 0005). The caller guarantees that one session never
 *        mixes campaigns, roles, audiences or policies (docs/06 section 7, P08.3); an adapter
 *        maps it to at most one runtime conversation and may replace that conversation, which
 *        it reports through {@link TaskResult#freshSession()}
 * @param slot logical weight class; resolved through {@link SlotAssignments}
 * @param instructions task text, at most {@link #MAX_INSTRUCTION_BYTES} UTF-8 bytes
 * @param outputSchema JSON Schema object text constraining the final output, at most
 *        {@link #MAX_SCHEMA_BYTES} UTF-8 bytes; structural syntax is checked by the adapter
 * @param timeout total budget from submission, including queue time, between
 *        {@link #MIN_TIMEOUT} and {@link #MAX_TIMEOUT} (docs/06 section 10)
 */
public record TaskRequest(UUID taskId, UUID sessionId, InferenceSlot slot, String instructions,
        String outputSchema, Duration timeout) {
    /** Upper bound of the instruction text; keeps one frame well below the 1 MiB wire limit. */
    public static final int MAX_INSTRUCTION_BYTES = 256 * 1024;
    /** Upper bound of the output schema text. */
    public static final int MAX_SCHEMA_BYTES = 64 * 1024;
    /** Shortest accepted budget. */
    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    /** Longest accepted budget: the interactive operation planning ceiling of docs/06 section 10. */
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(180);

    /**
     * Reject incomplete or oversized requests before any adapter sees them.
     *
     * @throws NullPointerException for a missing component
     * @throws IllegalArgumentException for empty or oversized text or an out-of-range timeout
     */
    public TaskRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(timeout, "timeout");
        if (instructions.isBlank() || instructions.getBytes(StandardCharsets.UTF_8).length > MAX_INSTRUCTION_BYTES) {
            throw new IllegalArgumentException("instructions must be non-blank and bounded");
        }
        if (outputSchema.isBlank() || outputSchema.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("output schema must be non-blank and bounded");
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout outside the supported budget");
        }
    }
}
