package org.rpgengine.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.InferenceSlot;
import org.springframework.mock.env.MockEnvironment;

/**
 * Planning is off unless the owner enables it (the Economy profile), its budgets fit the job
 * lease, and malformed or out-of-range settings fail startup naming only the property.
 */
class PlanningSettingsTest {
    @Test
    void defaultsAreDisabledAndBounded() {
        var defaults = PlanningSettings.from(new MockEnvironment());
        assertThat(defaults).isEqualTo(PlanningSettings.defaults());
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.budget().plus(PlanningSettings.WAIT_MARGIN)).isLessThan(defaults.lease());
        assertThat(defaults.maxAttempts()).isEqualTo(2);
        assertThat(defaults.replanAllowance()).isEqualTo(3);
        var configured = PlanningSettings.from(new MockEnvironment().withProperty("engine.planning.enabled", "true")
                .withProperty("engine.planning.slot", "deliberate").withProperty("engine.planning.replan-allowance", "0"));
        assertThat(configured.enabled()).isTrue();
        assertThat(configured.slot()).isEqualTo(InferenceSlot.DELIBERATE);
        assertThat(configured.replanAllowance()).isZero();
    }

    @Test
    void invalidValuesFailWithThePropertyNameOnly() {
        assertThatThrownBy(() -> PlanningSettings.from(new MockEnvironment()
                .withProperty("engine.planning.budget-seconds", "115"))).hasMessage(
                        "engine.planning.budget-seconds is out of range");
        assertThatThrownBy(() -> PlanningSettings.from(new MockEnvironment()
                .withProperty("engine.planning.max-attempts", "secret-value"))).hasMessage(
                        "engine.planning.* contains a malformed value");
        assertThatThrownBy(() -> new PlanningSettings(true, InferenceSlot.STANDARD, Duration.ofSeconds(60),
                Duration.ofSeconds(120), 2, Duration.ofHours(1), 6, Duration.ofDays(7), 100))
                .hasMessage("engine.planning.replan-allowance is out of range");
    }
}
