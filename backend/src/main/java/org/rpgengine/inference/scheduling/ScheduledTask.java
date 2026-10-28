package org.rpgengine.inference.scheduling;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.session.SessionScope;

/**
 * One task offered to the {@link InferenceScheduler}: everything a {@link TaskRequest} needs except
 * the logical session, which the scheduler obtains from the scope at dispatch time, plus the
 * task's {@link WorkClass}. The scope names the logical role/audience line the task belongs to;
 * the scheduler never runs two tasks of one line at the same time and dispatches each line's
 * tasks in admission order.
 *
 * @param taskId caller task identity; unique for the lifetime of the durable ledger, so a repeated
 *        identity is refused even after a restart
 * @param scope isolation key of the task's session (campaign, generation, role, audience, hashes)
 * @param workClass priority class
 * @param slot logical inference slot
 * @param instructions task text, already filtered for the scope's audience; never stored
 * @param outputSchema JSON Schema object text; never stored
 * @param timeout total budget from submission, including the time spent queued, within the
 *        {@link TaskRequest} range
 */
public record ScheduledTask(UUID taskId, SessionScope scope, WorkClass workClass, InferenceSlot slot,
        String instructions, String outputSchema, Duration timeout) {
    /** Bytes per estimated usage unit: a conservative token estimate for mixed English/Italian text. */
    static final int BYTES_PER_UNIT = 4;

    /**
     * Reject a task the port would refuse, so refusals happen before anything is recorded.
     *
     * @throws NullPointerException for a missing component
     * @throws IllegalArgumentException for blank or oversized text or an out-of-range timeout
     */
    public ScheduledTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(workClass, "workClass");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(timeout, "timeout");
        if (instructions.isBlank() || bytes(instructions) > TaskRequest.MAX_INSTRUCTION_BYTES) {
            throw new IllegalArgumentException("instructions must be non-blank and bounded");
        }
        if (outputSchema.isBlank() || bytes(outputSchema) > TaskRequest.MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("output schema must be non-blank and bounded");
        }
        if (timeout.compareTo(TaskRequest.MIN_TIMEOUT) < 0 || timeout.compareTo(TaskRequest.MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout outside the supported budget");
        }
    }

    /**
     * Usage units reserved for this task before dispatch (docs/28 section 9): the input size in
     * {@value #BYTES_PER_UNIT}-byte units, rounded up, plus the slot's output allowance. It is an
     * estimate, labelled as such wherever it is reported; the runtime port reports no observed
     * token counts yet, so the reservation is also the charge of a task that started a turn.
     *
     * @return estimated usage units, at least 1
     */
    public long estimatedUsage() {
        long input = bytes(instructions) + (long) bytes(outputSchema);
        return (input + BYTES_PER_UNIT - 1) / BYTES_PER_UNIT + outputAllowance(slot);
    }

    /**
     * @param slot logical slot
     * @return estimated output units of one turn of that weight class
     */
    static long outputAllowance(InferenceSlot slot) {
        return switch (slot) {
            case LIGHT -> 1_024;
            case STANDARD -> 2_048;
            case DELIBERATE -> 4_096;
        };
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
