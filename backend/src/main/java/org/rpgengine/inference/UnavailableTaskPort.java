package org.rpgengine.inference;

import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Task port used when no narration runtime is configured
 * ({@code engine.inference.codex.transport} unset or {@code disabled}, the default for host
 * development and tests). It never contacts a process or network: every task ends
 * {@link TaskResult.Status#UNAVAILABLE} with {@code runtime-not-configured}, so the
 * deterministic game, receipts and saved data keep working without inference.
 */
@Component
@ConditionalOnProperty(name = "engine.inference.codex.transport", havingValue = "disabled", matchIfMissing = true)
public class UnavailableTaskPort implements NarrationTaskPort {
    /** Fixed code reported for every task and in the status. */
    public static final String REASON = "runtime-not-configured";

    /** Stateless port. */
    public UnavailableTaskPort() {
    }

    /** @return a completed handle with status UNAVAILABLE */
    @Override
    public TaskHandle submit(TaskRequest request) {
        Objects.requireNonNull(request, "request");
        return new CompletedTaskHandle(TaskResult.withoutTurn(request.taskId(), TaskResult.Status.UNAVAILABLE, REASON));
    }

    /** No conversation exists; nothing to retire. */
    @Override
    public void retireSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    /** @return DISABLED with {@link #REASON} and empty counts */
    @Override
    public RuntimeStatus status() {
        return new RuntimeStatus("none", RuntimeStatus.State.DISABLED, REASON, 0, 0);
    }
}
