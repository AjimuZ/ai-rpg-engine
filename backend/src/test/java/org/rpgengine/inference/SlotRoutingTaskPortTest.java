package org.rpgengine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.TaskResult.Status;

/** Routing follows the owner's assignment, refuses what no adapter serves and never falls back. */
class SlotRoutingTaskPortTest {
    /** Adapter double that records what reaches it and answers with a fixed status. */
    static final class RecordingAdapter implements NarrationTaskPort {
        final String runtime;
        RuntimeStatus.State state = RuntimeStatus.State.READY;
        String reason;
        int queued;
        int active;
        final List<TaskRequest> requests = new ArrayList<>();
        final List<UUID> retired = new ArrayList<>();
        final List<String> references = new ArrayList<>();
        DispatchJournal journal;

        RecordingAdapter(String runtime) {
            this.runtime = runtime;
        }

        @Override
        public TaskHandle submit(TaskRequest request) {
            requests.add(request);
            return new CompletedTaskHandle(TaskResult.withoutTurn(request.taskId(), Status.UNAVAILABLE, "from-" + runtime));
        }

        @Override
        public void retireSession(UUID sessionId) {
            retired.add(sessionId);
        }

        @Override
        public RuntimeStatus status() {
            return new RuntimeStatus(runtime, state, reason, queued, active);
        }

        @Override
        public void attachJournal(DispatchJournal attached) {
            journal = attached;
        }

        @Override
        public void reconcile(Collection<String> handed) {
            references.addAll(handed);
        }
    }

    private final Map<InferenceSlot, SlotAssignment> assigned = new EnumMap<>(InferenceSlot.class);
    private final RecordingAdapter local = new RecordingAdapter("local");
    private final RecordingAdapter codex = new RecordingAdapter("codex");

    private ProfileResolver profiles(Optional<InferenceSlot> fixed) {
        return new ProfileResolver(slot -> Optional.ofNullable(assigned.get(slot)), ModelAllowlist.empty(), fixed);
    }

    private SlotRoutingTaskPort port() {
        return new SlotRoutingTaskPort(List.of(local, codex), profiles(Optional.empty()));
    }

    private void assign(InferenceSlot slot, String backend) {
        assigned.put(slot, SlotAssignment.of(backend, new EffectiveSettings("model-a", "low")));
    }

    private static TaskRequest request(InferenceSlot slot) {
        return new TaskRequest(UUID.randomUUID(), UUID.randomUUID(), slot, "x", "{}", Duration.ofSeconds(5));
    }

    private static TaskResult result(TaskHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get();
    }

    @Test
    void eachSlotGoesToTheAdapterOfItsOwnBackend() throws Exception {
        assign(InferenceSlot.LIGHT, "local");
        assign(InferenceSlot.STANDARD, "codex");
        var port = port();
        assertThat(result(port.submit(request(InferenceSlot.LIGHT))).errorCode()).isEqualTo("from-local");
        assertThat(result(port.submit(request(InferenceSlot.STANDARD))).errorCode()).isEqualTo("from-codex");
        assertThat(local.requests).hasSize(1);
        assertThat(codex.requests).hasSize(1);
        // The next task follows a changed assignment.
        assign(InferenceSlot.LIGHT, "codex");
        assertThat(result(port.submit(request(InferenceSlot.LIGHT))).errorCode()).isEqualTo("from-codex");
    }

    @Test
    void fixedQualityRoutesEveryTaskThroughItsSlot() throws Exception {
        assign(InferenceSlot.LIGHT, "local");
        assign(InferenceSlot.DELIBERATE, "codex");
        var port = new SlotRoutingTaskPort(List.of(local, codex), profiles(Optional.of(InferenceSlot.DELIBERATE)));
        assertThat(result(port.submit(request(InferenceSlot.LIGHT))).errorCode()).isEqualTo("from-codex");
        assertThat(local.requests).isEmpty();
    }

    @Test
    void whatNoAdapterServesIsRefusedAndNeverRerouted() throws Exception {
        assign(InferenceSlot.STANDARD, "claude");
        var port = port();
        TaskResult mismatch = result(port.submit(request(InferenceSlot.STANDARD)));
        assertThat(mismatch.status()).isEqualTo(Status.FAILED);
        assertThat(mismatch.errorCode()).isEqualTo("slot-backend-mismatch");
        assertThat(mismatch.provenance()).isNull();
        TaskResult unassigned = result(port.submit(request(InferenceSlot.LIGHT)));
        assertThat(unassigned.status()).isEqualTo(Status.FAILED);
        assertThat(unassigned.errorCode()).isEqualTo("slot-unassigned");
        assertThat(local.requests).isEmpty();
        assertThat(codex.requests).isEmpty();
    }

    @Test
    void theUnavailablePortGetsNoRouteEvenWhenItsNameIsAssigned() throws Exception {
        assign(InferenceSlot.STANDARD, "codex");
        var port = new SlotRoutingTaskPort(List.of(new UnavailableTaskPort(), local), profiles(Optional.empty()));
        assertThat(result(port.submit(request(InferenceSlot.STANDARD))).errorCode()).isEqualTo("slot-backend-mismatch");
        assertThat(port.statusOf("none")).isEmpty();
    }

    @Test
    void theStatusIsTheMostUsableAdaptersWithTheCountsOfAll() {
        var port = port();
        codex.state = RuntimeStatus.State.UNAVAILABLE;
        codex.reason = "sign-in-required";
        codex.active = 1;
        local.queued = 2;
        RuntimeStatus status = port.status();
        assertThat(status.runtime()).isEqualTo(SlotRoutingTaskPort.RUNTIME);
        assertThat(status.state()).isEqualTo(RuntimeStatus.State.READY);
        assertThat(status.reasonCode()).isNull();
        assertThat(status.queued()).isEqualTo(2);
        // Unconfirmed work of the adapter that is down still keeps the scheduler's permit held.
        assertThat(status.active()).isEqualTo(1);

        local.state = RuntimeStatus.State.UNAVAILABLE;
        local.reason = "runtime-unreachable";
        assertThat(port.status().state()).isEqualTo(RuntimeStatus.State.UNAVAILABLE);
        assertThat(port.status().reasonCode()).isEqualTo("runtime-unreachable");

        var withUnconfigured = new SlotRoutingTaskPort(List.of(new UnavailableTaskPort(), local),
                profiles(Optional.empty()));
        assertThat(withUnconfigured.status().state()).isEqualTo(RuntimeStatus.State.UNAVAILABLE);
        local.state = RuntimeStatus.State.STARTING;
        assertThat(withUnconfigured.status().state()).isEqualTo(RuntimeStatus.State.STARTING);
    }

    @Test
    void theStatusOfOneBackendComesFromItsOwnAdapter() {
        var port = port();
        codex.state = RuntimeStatus.State.UNAVAILABLE;
        assertThat(port.statusOf("local").orElseThrow().state()).isEqualTo(RuntimeStatus.State.READY);
        assertThat(port.statusOf("codex").orElseThrow().state()).isEqualTo(RuntimeStatus.State.UNAVAILABLE);
        assertThat(port.statusOf("claude")).isEmpty();
        assertThat(port.statusOf(SlotRoutingTaskPort.RUNTIME)).isEmpty();
    }

    @Test
    void retirementJournalAndReferencesReachEveryAdapter() {
        var port = port();
        UUID session = UUID.randomUUID();
        port.retireSession(session);
        assertThat(local.retired).containsExactly(session);
        assertThat(codex.retired).containsExactly(session);
        DispatchJournal journal = (task, reference) -> true;
        port.attachJournal(journal);
        assertThat(local.journal).isSameAs(journal);
        assertThat(codex.journal).isSameAs(journal);
        port.reconcile(List.of("thread-1"));
        assertThat(local.references).containsExactly("thread-1");
        assertThat(codex.references).containsExactly("thread-1");
    }

    @Test
    void aMisconfiguredRouterFailsAtConstruction() {
        assertThatThrownBy(() -> new SlotRoutingTaskPort(List.of(), profiles(Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotRoutingTaskPort(List.of(local, new RecordingAdapter("local")),
                profiles(Optional.empty()))).isInstanceOf(IllegalArgumentException.class);
    }
}
