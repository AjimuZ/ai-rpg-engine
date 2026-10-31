package org.rpgengine.inference.slots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.SlotAssignments;
import org.rpgengine.inference.slots.SlotSettingsService.Request;
import org.rpgengine.inference.slots.SlotSettingsService.Source;

/** Class-change acknowledgement, probe recording, environment precedence and fail-closed reads. */
class SlotSettingsServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    /** In-memory store; {@code failSaves} simulates unavailable storage. */
    static final class MemoryStore implements SlotSettingsStore {
        final Map<ModelSlot, Stored> rows = new EnumMap<>(ModelSlot.class);
        boolean failSaves;
        boolean failLoads;

        @Override
        public Map<ModelSlot, Stored> loadAll() {
            if (failLoads) {
                throw new IllegalStateException("unreadable");
            }
            return new EnumMap<>(rows);
        }

        @Override
        public void save(Stored stored) {
            if (failSaves) {
                throw new org.springframework.dao.DataAccessResourceFailureException("down");
            }
            rows.put(stored.setting().slot(), stored);
        }

        @Override
        public boolean saveProbe(ModelSlot slot, ProbeResult probe) {
            Stored row = rows.get(slot);
            if (row == null) {
                return false;
            }
            rows.put(slot, new Stored(row.setting(), row.acknowledgedClass(), row.acknowledgedAt(), probe,
                    row.updatedAt()));
            return true;
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final Map<InferenceSlot, SlotAssignment> environment = new HashMap<>();
    private final Map<String, BackendProbe.Outcome> verdicts = new HashMap<>();
    private final List<String> probed = new ArrayList<>();
    private Runnable duringProbe = () -> { };
    private SlotSettingsService service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    private SlotSettingsService newService() {
        SlotAssignments configured = slot -> Optional.ofNullable(environment.get(slot));
        BackendProbe probe = (backend, settings) -> {
            probed.add(backend + ":" + settings.model());
            duringProbe.run();
            return verdicts.getOrDefault(backend + ":" + settings.model(), BackendProbe.Outcome.ready());
        };
        return new SlotSettingsService(store, probe, configured, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Request request(ModelSlot slot, String backend, String model, BackendClass declared,
            BackendClass acknowledge, SlotSetting.Fallback... fallbacks) {
        return new Request(slot, backend, model, slot == ModelSlot.IMAGE ? null : "medium", declared,
                List.of(fallbacks), acknowledge);
    }

    @Test
    void everySlotStartsUnassignedAndNothingIsClaimedAboutIt() {
        var status = service.status();
        assertThat(status).extracting(SlotSettingsService.SlotStatus::slot)
                .containsExactly(ModelSlot.LIGHT, ModelSlot.STANDARD, ModelSlot.DELIBERATE, ModelSlot.IMAGE);
        assertThat(status).allSatisfy(slot -> {
            assertThat(slot.source()).isEqualTo(Source.UNASSIGNED);
            assertThat(slot.backend()).isNull();
            assertThat(slot.backendClass()).isNull();
            assertThat(slot.probe()).isEqualTo(ProbeResult.notRun());
        });
    }

    @Test
    void theFourSlotsAreAssignedIndependently() {
        service.update(request(ModelSlot.LIGHT, "local", "small", null, null));
        service.update(request(ModelSlot.STANDARD, "local", "medium-model", null, null));
        service.update(request(ModelSlot.DELIBERATE, "codex", "large", null, BackendClass.SUBSCRIPTION));
        service.update(request(ModelSlot.IMAGE, "local-image", "sdxl", null, null));
        var status = service.status();
        assertThat(status).extracting(SlotSettingsService.SlotStatus::model)
                .containsExactly("small", "medium-model", "large", "sdxl");
        assertThat(status).extracting(SlotSettingsService.SlotStatus::backendClass).containsExactly(
                BackendClass.LOCAL, BackendClass.LOCAL, BackendClass.SUBSCRIPTION, BackendClass.LOCAL);
        service.update(request(ModelSlot.LIGHT, "local", "smaller", null, null));
        assertThat(service.status()).extracting(SlotSettingsService.SlotStatus::model)
                .containsExactly("smaller", "medium-model", "large", "sdxl");
    }

    @Test
    void movingAnUnassignedSlotOffTheMachineNeedsAnAcknowledgement() {
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "codex", "m", null, null)))
                .isInstanceOfSatisfying(SlotSettingsService.AcknowledgementRequired.class, refusal -> {
                    assertThat(refusal.from()).isEqualTo(BackendClass.LOCAL);
                    assertThat(refusal.to()).isEqualTo(BackendClass.SUBSCRIPTION);
                });
        assertThat(store.rows).isEmpty();
        assertThat(service.setting(ModelSlot.STANDARD)).isEmpty();
        // The acknowledgement must name the new class: another class does not count.
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "codex", "m", null, BackendClass.METERED)))
                .isInstanceOf(SlotSettingsService.AcknowledgementRequired.class);
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "codex", "m", null, BackendClass.LOCAL)))
                .isInstanceOf(SlotSettingsService.AcknowledgementRequired.class);
        assertThat(store.rows).isEmpty();

        var saved = service.update(request(ModelSlot.STANDARD, "codex", "m", null, BackendClass.SUBSCRIPTION));
        assertThat(saved.source()).isEqualTo(Source.SETTINGS);
        assertThat(saved.backendClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(saved.acknowledgedClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(saved.acknowledgedAt()).isEqualTo(NOW);
        assertThat(store.rows.get(ModelSlot.STANDARD).acknowledgedClass()).isEqualTo(BackendClass.SUBSCRIPTION);
    }

    @Test
    void aFirstLocalAssignmentNeedsNoAcknowledgementAndRecordsNone() {
        var saved = service.update(request(ModelSlot.LIGHT, "local", "m", null, null));
        assertThat(saved.acknowledgedClass()).isNull();
        assertThat(saved.acknowledgedAt()).isNull();
    }

    @Test
    void aClassChangeOfAnAssignedSlotNeedsAnAcknowledgementInBothDirections() {
        service.update(request(ModelSlot.STANDARD, "codex", "m", null, BackendClass.SUBSCRIPTION));
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "anthropic-api", "m", null, null)))
                .isInstanceOfSatisfying(SlotSettingsService.AcknowledgementRequired.class, refusal -> {
                    assertThat(refusal.from()).isEqualTo(BackendClass.SUBSCRIPTION);
                    assertThat(refusal.to()).isEqualTo(BackendClass.METERED);
                });
        assertThat(service.setting(ModelSlot.STANDARD).orElseThrow().backend()).isEqualTo("codex");
        // Back to the machine is a class change too: the badge the owner relied on changes.
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "local", "m", null, null)))
                .isInstanceOf(SlotSettingsService.AcknowledgementRequired.class);
        var local = service.update(request(ModelSlot.STANDARD, "local", "m", null, BackendClass.LOCAL));
        assertThat(local.backendClass()).isEqualTo(BackendClass.LOCAL);
        assertThat(local.acknowledgedClass()).isEqualTo(BackendClass.LOCAL);
    }

    @Test
    void anEndpointDeclaredClassChangeNeedsAnAcknowledgement() {
        service.update(request(ModelSlot.STANDARD, "openai-compatible", "m", BackendClass.LOCAL, null));
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "openai-compatible", "m",
                BackendClass.METERED, null))).isInstanceOf(SlotSettingsService.AcknowledgementRequired.class);
        var metered = service.update(request(ModelSlot.STANDARD, "openai-compatible", "m", BackendClass.METERED,
                BackendClass.METERED));
        assertThat(metered.backendClass()).isEqualTo(BackendClass.METERED);
    }

    @Test
    void aChangeInsideOneClassNeedsNoAcknowledgementAndKeepsTheRecordedOne() {
        service.update(request(ModelSlot.STANDARD, "codex", "m", null, BackendClass.SUBSCRIPTION));
        var changed = service.update(request(ModelSlot.STANDARD, "claude", "other", null, null));
        assertThat(changed.backend()).isEqualTo("claude");
        assertThat(changed.acknowledgedClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(changed.acknowledgedAt()).isEqualTo(NOW);
    }

    @Test
    void fallbackConfigurationIsSavedOnlyWithinTheClass() {
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "codex", "m", null,
                BackendClass.SUBSCRIPTION, new SlotSetting.Fallback("anthropic-api", "x", "low"))))
                .isInstanceOfSatisfying(SlotSetting.Invalid.class,
                        refusal -> assertThat(refusal.code()).isEqualTo("FALLBACK_CLASS_CROSSING"));
        assertThat(store.rows).isEmpty();
        var saved = service.update(request(ModelSlot.STANDARD, "codex", "m", null, BackendClass.SUBSCRIPTION,
                new SlotSetting.Fallback("claude", "x", "low")));
        assertThat(saved.fallbacks()).containsExactly(new SlotSetting.Fallback("claude", "x", "low"));
    }

    @Test
    void theEnvironmentAssignmentIsShownAndItsClassCountsForTheAcknowledgement() {
        environment.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", new EffectiveSettings("env-model", "low")));
        var shown = service.status().get(1);
        assertThat(shown.source()).isEqualTo(Source.ENVIRONMENT);
        assertThat(shown.backend()).isEqualTo("codex");
        assertThat(shown.backendClass()).isEqualTo(BackendClass.SUBSCRIPTION);

        // Saving the same class over it needs nothing; leaving it needs the acknowledgement.
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "local", "m", null, null)))
                .isInstanceOfSatisfying(SlotSettingsService.AcknowledgementRequired.class,
                        refusal -> assertThat(refusal.from()).isEqualTo(BackendClass.SUBSCRIPTION));
        assertThat(service.update(request(ModelSlot.STANDARD, "claude", "m", null, null)).source())
                .isEqualTo(Source.SETTINGS);
    }

    @Test
    void anEnvironmentEndpointOfUnknownClassAlwaysNeedsAnAcknowledgement() {
        environment.put(InferenceSlot.LIGHT, SlotAssignment.of("openai-compatible", new EffectiveSettings("e", "low")));
        assertThat(service.status().get(0).backendClass()).isNull();
        assertThatThrownBy(() -> service.update(request(ModelSlot.LIGHT, "local", "m", null, null)))
                .isInstanceOfSatisfying(SlotSettingsService.AcknowledgementRequired.class,
                        refusal -> assertThat(refusal.from()).isNull());
        assertThat(service.update(request(ModelSlot.LIGHT, "local", "m", null, BackendClass.LOCAL)).source())
                .isEqualTo(Source.SETTINGS);
    }

    @Test
    void probesRecordReadyFailedAndUnreachableResults() {
        service.update(request(ModelSlot.STANDARD, "local", "ok", null, null));
        service.update(request(ModelSlot.LIGHT, "local", "refused", null, null));
        service.update(request(ModelSlot.DELIBERATE, "local", "down", null, null));
        verdicts.put("local:refused", BackendProbe.Outcome.failed("model-not-approved"));
        verdicts.put("local:down", BackendProbe.Outcome.unreachable("adapter-not-installed"));

        var ready = service.probe(ModelSlot.STANDARD).probe();
        assertThat(ready.state()).isEqualTo(ProbeState.READY);
        assertThat(ready.code()).isNull();
        assertThat(ready.at()).isEqualTo(NOW);
        var failed = service.probe(ModelSlot.LIGHT).probe();
        assertThat(failed.state()).isEqualTo(ProbeState.FAILED);
        assertThat(failed.code()).isEqualTo("model-not-approved");
        var unreachable = service.probe(ModelSlot.DELIBERATE).probe();
        assertThat(unreachable.state()).isEqualTo(ProbeState.UNREACHABLE);
        assertThat(unreachable.code()).isEqualTo("adapter-not-installed");

        // Visible in the status and in storage, so a restart keeps it.
        assertThat(service.status().get(1).probe()).isEqualTo(ready);
        assertThat(store.rows.get(ModelSlot.LIGHT).probe()).isEqualTo(failed);
        assertThat(newService().status().get(2).probe()).isEqualTo(unreachable);
    }

    @Test
    void aProbeThatThrowsIsReportedAsAnUnreachableVerdict() {
        service.update(request(ModelSlot.STANDARD, "local", "boom", null, null));
        duringProbe = () -> {
            throw new IllegalStateException("defect");
        };
        var result = service.probe(ModelSlot.STANDARD).probe();
        assertThat(result.state()).isEqualTo(ProbeState.UNREACHABLE);
        assertThat(result.code()).isEqualTo("probe-error");
    }

    @Test
    void anUnassignedSlotCannotBeProbed() {
        environment.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", new EffectiveSettings("e", "low")));
        assertThatThrownBy(() -> service.probe(ModelSlot.STANDARD)).isInstanceOf(SlotSettingsService.NotAssigned.class);
        assertThatThrownBy(() -> service.probe(ModelSlot.IMAGE)).isInstanceOf(SlotSettingsService.NotAssigned.class);
        assertThat(probed).isEmpty();
    }

    @Test
    void aChangedAssignmentLosesItsProbeResult() {
        service.update(request(ModelSlot.STANDARD, "local", "m", null, null));
        assertThat(service.probe(ModelSlot.STANDARD).probe().state()).isEqualTo(ProbeState.READY);
        var same = service.update(request(ModelSlot.STANDARD, "local", "m", null, null));
        assertThat(same.probe().state()).isEqualTo(ProbeState.READY);
        var changed = service.update(request(ModelSlot.STANDARD, "local", "m2", null, null));
        assertThat(changed.probe()).isEqualTo(ProbeResult.notRun());
    }

    @Test
    void aProbeFinishingAfterTheAssignmentChangedIsDiscarded() {
        service.update(request(ModelSlot.STANDARD, "local", "old", null, null));
        duringProbe = () -> service.update(request(ModelSlot.STANDARD, "local", "new", null, null));
        var status = service.probe(ModelSlot.STANDARD);
        assertThat(status.model()).isEqualTo("new");
        assertThat(status.probe()).isEqualTo(ProbeResult.notRun());
        assertThat(store.rows.get(ModelSlot.STANDARD).probe()).isEqualTo(ProbeResult.notRun());
    }

    @Test
    void settingsSurviveARestart() {
        service.update(request(ModelSlot.DELIBERATE, "codex", "large", null, BackendClass.SUBSCRIPTION,
                new SlotSetting.Fallback("claude", "other", "high")));
        service.probe(ModelSlot.DELIBERATE);
        SlotSettingsService restarted = newService();
        var status = restarted.status().get(2);
        assertThat(status.source()).isEqualTo(Source.SETTINGS);
        assertThat(status.model()).isEqualTo("large");
        assertThat(status.fallbacks()).containsExactly(new SlotSetting.Fallback("claude", "other", "high"));
        assertThat(status.acknowledgedClass()).isEqualTo(BackendClass.SUBSCRIPTION);
        assertThat(status.probe().state()).isEqualTo(ProbeState.READY);
    }

    @Test
    void aFailedSaveChangesNothingVisible() {
        service.update(request(ModelSlot.STANDARD, "local", "kept", null, null));
        store.failSaves = true;
        assertThatThrownBy(() -> service.update(request(ModelSlot.STANDARD, "local", "lost", null, null)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.setting(ModelSlot.STANDARD).orElseThrow().model()).isEqualTo("kept");
    }

    @Test
    void choosingUsesOnlyTheConfiguredSameClassCandidates() {
        service.update(request(ModelSlot.STANDARD, "codex", "primary", null, BackendClass.SUBSCRIPTION,
                new SlotSetting.Fallback("claude", "backup", "low")));
        assertThat(service.choose(ModelSlot.STANDARD)).isEqualTo(new SlotFallback.Chosen(
                new SlotSetting.Fallback("codex", "primary", "medium"), false));
        verdicts.put("codex:primary", BackendProbe.Outcome.unreachable("runtime-unavailable"));
        assertThat(service.choose(ModelSlot.STANDARD)).isEqualTo(new SlotFallback.Chosen(
                new SlotSetting.Fallback("claude", "backup", "low"), true));
        verdicts.put("claude:backup", BackendProbe.Outcome.failed("model-not-approved"));
        assertThat(service.choose(ModelSlot.STANDARD)).isEqualTo(new SlotFallback.Unavailable("slot-unavailable"));
        // The metered backend is available but was never configured: unavailability stays unavailability.
        verdicts.put("anthropic-api:primary", BackendProbe.Outcome.ready());
        assertThat(probed).doesNotContain("anthropic-api:primary");
        assertThatThrownBy(() -> service.choose(ModelSlot.LIGHT)).isInstanceOf(SlotSettingsService.NotAssigned.class);
    }

    @Test
    void theResolverReadsStoredTextSlotsAheadOfTheEnvironmentAndFailsClosed() {
        environment.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", new EffectiveSettings("env", "low")));
        environment.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", new EffectiveSettings("env-light", "low")));
        SlotAssignments configured = slot -> Optional.ofNullable(environment.get(slot));
        var assignments = new PersistedSlotAssignments(service, configured);
        assertThat(assignments.resolve(InferenceSlot.STANDARD).orElseThrow().primary().model()).isEqualTo("env");

        service.update(request(ModelSlot.STANDARD, "codex", "stored", null, null));
        assertThat(assignments.resolve(InferenceSlot.STANDARD).orElseThrow().primary().model()).isEqualTo("stored");
        assertThat(assignments.resolve(InferenceSlot.LIGHT).orElseThrow().primary().model()).isEqualTo("env-light");
        assertThat(assignments.resolve(InferenceSlot.DELIBERATE)).isEmpty();

        // Unreadable storage must not hand a slot to the environment's possibly different class.
        SlotSettingsService broken = newService();
        store.failLoads = true;
        var closed = new PersistedSlotAssignments(broken, configured);
        assertThat(closed.resolve(InferenceSlot.STANDARD)).isEmpty();
        assertThat(closed.resolve(InferenceSlot.LIGHT)).isEmpty();
    }
}
