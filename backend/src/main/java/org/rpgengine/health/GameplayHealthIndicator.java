package org.rpgengine.health;

import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Gameplay-readiness component "narrationRuntime", deliberately separate from storage readiness.
 * It reads the runtime-neutral task port's cached status, so it performs no inference, consumes
 * no turn and never blocks on the runtime (docs/22 section 5). UP only when the selected runtime
 * is connected, compatible and signed in; every other state (no runtime configured, connecting,
 * sign-in required, reconciling, stopping) is UNKNOWN. UNKNOWN is the lowest-ranked status, so
 * the root aggregate stays UP (storage health only), while the "gameplay" group maps UNKNOWN to
 * HTTP 503 (application.properties) so launchers never mistake it for ready. No reason code or
 * other detail is exposed. Saved-data inspection and the storage readiness group are unaffected.
 */
@Component("narrationRuntime")
public class GameplayHealthIndicator implements HealthIndicator {
    private final NarrationTaskPort tasks;

    /** @param tasks the installation's task port */
    public GameplayHealthIndicator(NarrationTaskPort tasks) {
        this.tasks = tasks;
    }

    /** @return UP when the runtime is READY, otherwise UNKNOWN; never fails */
    @Override
    public Health health() {
        return tasks.status().state() == RuntimeStatus.State.READY ? Health.up().build() : Health.unknown().build();
    }
}
