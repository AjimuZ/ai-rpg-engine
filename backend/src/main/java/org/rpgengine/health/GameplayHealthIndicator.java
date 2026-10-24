package org.rpgengine.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Gameplay-readiness component "narrationRuntime", deliberately separate from storage readiness.
 * No narration runtime adapter exists in this shell, so gameplay is reported
 * UNKNOWN rather than claimed. UNKNOWN is the lowest-ranked status, so the root
 * aggregate stays UP (storage health only), while the "gameplay" group maps UNKNOWN
 * to HTTP 503 (application.properties) so launchers never mistake it for ready. A later task replaces
 * this with a check of the runtime connection and account state that performs no
 * inference and consumes no turn (docs/22 section 5). Saved-data inspection and the
 * storage readiness group are unaffected by this state.
 */
@Component("narrationRuntime")
public class GameplayHealthIndicator implements HealthIndicator {
    /** Construct the stateless indicator. */
    public GameplayHealthIndicator() {
    }

    /** Report UNKNOWN: gameplay readiness is not yet established; never fails. */
    @Override
    public Health health() {
        return Health.unknown().build();
    }
}
