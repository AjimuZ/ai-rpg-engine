package org.rpgengine.inference.slots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.InferenceSlot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL round trip of slot settings, restart behavior and fail-closed handling of a damaged row. */
@SpringBootTest
class SlotSettingsPersistenceTest {
    private static final Instant AT = Instant.parse("2026-10-31T10:00:00Z");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcSlotSettingsStore store;
    @Autowired private SlotSettingsService service;
    @Autowired private PersistedSlotAssignments assignments;

    /** Construct the injected fixture; missing PostgreSQL fails application startup. */
    SlotSettingsPersistenceTest() {
    }

    /** Remove every slot row (fallbacks cascade) and drop the cache so each test starts unassigned. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("delete from inference_slot_setting");
        service.forget();
    }

    @Test
    void aSavedSettingRoundTripsWithItsFallbacksAcknowledgementAndProbe() {
        SlotSetting setting = SlotSetting.of(ModelSlot.STANDARD, "codex", "model-a", "medium", null, List.of(
                new SlotSetting.Fallback("claude", "model-b", "low"), new SlotSetting.Fallback("codex", "model-c", "high")));
        ProbeResult probe = new ProbeResult(ProbeState.FAILED, "model-not-approved", AT.plusSeconds(5), 12);
        store.save(new SlotSettingsStore.Stored(setting, BackendClass.SUBSCRIPTION, AT, probe, AT));

        var loaded = store.loadAll();
        assertThat(loaded).containsOnlyKeys(ModelSlot.STANDARD);
        var row = loaded.get(ModelSlot.STANDARD);
        assertThat(row.setting()).isEqualTo(setting);
        assertThat(row.setting().fallbacks()).extracting(SlotSetting.Fallback::model).containsExactly("model-b", "model-c");
        assertThat(row.acknowledgedClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(row.acknowledgedAt()).isEqualTo(AT);
        assertThat(row.probe()).isEqualTo(probe);
        assertThat(row.updatedAt()).isEqualTo(AT);
        assertThat(jdbc.queryForObject("select backend_class from inference_slot_setting where slot = 'text.standard'",
                String.class)).isEqualTo("subscription");
    }

    @Test
    void replacingASlotReplacesItsFallbacksAndKeepsOneRow() {
        SlotSetting first = SlotSetting.of(ModelSlot.LIGHT, "codex", "m1", "low", null,
                List.of(new SlotSetting.Fallback("claude", "f1", "low"), new SlotSetting.Fallback("claude", "f2", "low")));
        store.save(new SlotSettingsStore.Stored(first, BackendClass.SUBSCRIPTION, AT, ProbeResult.notRun(), AT));
        SlotSetting second = SlotSetting.of(ModelSlot.LIGHT, "local", "m2", "low", null, List.of());
        store.save(new SlotSettingsStore.Stored(second, null, null, ProbeResult.notRun(), AT.plusSeconds(1)));
        assertThat(store.loadAll().get(ModelSlot.LIGHT).setting()).isEqualTo(second);
        assertThat(jdbc.queryForObject("select count(*) from inference_slot_fallback", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select row_version from inference_slot_setting", Long.class)).isEqualTo(1L);
    }

    @Test
    void aProbeIsStoredOnlyForAnExistingSlot() {
        ProbeResult ready = new ProbeResult(ProbeState.READY, null, AT, 3);
        assertThat(store.saveProbe(ModelSlot.IMAGE, ready)).isFalse();
        SlotSetting image = SlotSetting.of(ModelSlot.IMAGE, "local-image", "sdxl", null, null, List.of());
        store.save(new SlotSettingsStore.Stored(image, null, null, ProbeResult.notRun(), AT));
        assertThat(store.saveProbe(ModelSlot.IMAGE, ready)).isTrue();
        assertThat(store.loadAll().get(ModelSlot.IMAGE).probe()).isEqualTo(ready);
        assertThat(store.loadAll().get(ModelSlot.IMAGE).setting().effort()).isEqualTo("none");
    }

    @Test
    void settingsSavedThroughTheServiceSurviveARestartAndFeedTheResolver() {
        service.update(new SlotSettingsService.Request(ModelSlot.DELIBERATE, "codex", "large", "high", null,
                List.of(new SlotSetting.Fallback("claude", "other", "high")), BackendClass.SUBSCRIPTION));
        assertThat(service.probe(ModelSlot.DELIBERATE).probe().state()).isEqualTo(ProbeState.UNREACHABLE);

        // A new service over the same database is what a restarted process builds.
        SlotSettingsService restarted = new SlotSettingsService(store, (backend, settings) -> BackendProbe.Outcome.ready(),
                slot -> java.util.Optional.empty(), java.time.Clock.systemUTC());
        var status = restarted.status().get(2);
        assertThat(status.source()).isEqualTo(SlotSettingsService.Source.SETTINGS);
        assertThat(status.backend()).isEqualTo("codex");
        assertThat(status.model()).isEqualTo("large");
        assertThat(status.effort()).isEqualTo("high");
        assertThat(status.backendClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(status.acknowledgedClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(status.fallbacks()).containsExactly(new SlotSetting.Fallback("claude", "other", "high"));
        assertThat(status.probe().state()).isEqualTo(ProbeState.UNREACHABLE);
        // The test installation configures no runtime, so no adapter serves the codex backend.
        assertThat(status.probe().code()).isEqualTo("adapter-not-installed");

        var assignment = assignments.resolve(InferenceSlot.DELIBERATE).orElseThrow();
        assertThat(assignment.backend()).isEqualTo("codex");
        assertThat(assignment.primary().model()).isEqualTo("large");
        assertThat(assignment.alternatives()).isEmpty();
    }

    @Test
    void aDamagedRowIsUnreadableAndNeverReplacedByAnotherAssignment() {
        SlotSetting setting = SlotSetting.of(ModelSlot.STANDARD, "codex", "model-a", "medium", null, List.of());
        store.save(new SlotSettingsStore.Stored(setting, BackendClass.SUBSCRIPTION, AT, ProbeResult.notRun(), AT));
        // A fallback of another class cannot be written through the service; write one around it.
        jdbc.update("insert into inference_slot_fallback (slot, position, backend, model, effort) values "
                + "('text.standard', 0, 'anthropic-api', 'model-b', 'low')");
        assertThatThrownBy(store::loadAll).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("model-b").hasMessageNotContaining("anthropic");
        service.forget();
        assertThat(assignments.resolve(InferenceSlot.STANDARD)).isEmpty();
    }

    @Test
    void aStoredClassThatDisagreesWithTheBackendIsUnreadable() {
        jdbc.update("insert into inference_slot_setting (slot, backend, model, effort, backend_class, updated_at) "
                + "values ('text.light', 'codex', 'model-a', 'low', 'local', now())");
        assertThatThrownBy(store::loadAll).isInstanceOf(IllegalStateException.class);
    }
}
