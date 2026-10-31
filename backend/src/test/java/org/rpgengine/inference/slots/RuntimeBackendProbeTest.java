package org.rpgengine.inference.slots;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.UnavailableTaskPort;

/** The probe answers from the task port's status and the owner's allowlist, and sends nothing. */
class RuntimeBackendProbeTest {
    private static final EffectiveSettings PAIR = new EffectiveSettings("model-a", "low");

    private static Supplier<NarrationTaskPort> port(RuntimeStatus status) {
        NarrationTaskPort port = new NarrationTaskPort() {
            @Override
            public TaskHandle submit(TaskRequest request) {
                throw new AssertionError("a probe never submits a task");
            }

            @Override
            public void retireSession(UUID sessionId) {
                throw new AssertionError("a probe never touches a session");
            }

            @Override
            public RuntimeStatus status() {
                return status;
            }
        };
        return () -> port;
    }

    private static RuntimeStatus status(RuntimeStatus.State state, String reason) {
        return new RuntimeStatus("codex", state, reason, 0, 0);
    }

    @Test
    void aReadyRuntimeWithNoAllowlistIsReady() {
        var probe = new RuntimeBackendProbe(port(status(RuntimeStatus.State.READY, null)), ModelAllowlist.empty());
        assertThat(probe.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.ready());
    }

    @Test
    void aReadyRuntimeFailsAPairTheAllowlistDoesNotApprove() {
        var allowlist = ModelAllowlist.of(Map.of("codex", List.of(new EffectiveSettings("other", "low"))));
        var probe = new RuntimeBackendProbe(port(status(RuntimeStatus.State.READY, null)), allowlist);
        assertThat(probe.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.failed("model-not-approved"));
        var approved = ModelAllowlist.of(Map.of("codex", List.of(PAIR)));
        assertThat(new RuntimeBackendProbe(port(status(RuntimeStatus.State.READY, null)), approved)
                .probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.ready());
    }

    @Test
    void anUnavailableRuntimeIsUnreachableWithItsFixedReason() {
        var probe = new RuntimeBackendProbe(port(status(RuntimeStatus.State.UNAVAILABLE, "not-signed-in")),
                ModelAllowlist.empty());
        assertThat(probe.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("not-signed-in"));
        var odd = new RuntimeBackendProbe(port(status(RuntimeStatus.State.UNAVAILABLE, "Not Signed In!")),
                ModelAllowlist.empty());
        assertThat(odd.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("runtime-unavailable"));
        var starting = new RuntimeBackendProbe(port(status(RuntimeStatus.State.STARTING, null)), ModelAllowlist.empty());
        assertThat(starting.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("runtime-starting"));
        var stopped = new RuntimeBackendProbe(port(status(RuntimeStatus.State.STOPPED, null)), ModelAllowlist.empty());
        assertThat(stopped.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("runtime-stopped"));
    }

    @Test
    void underRoutingEachBackendAnswersFromItsOwnAdapter() {
        NarrationTaskPort routed = new NarrationTaskPort() {
            @Override
            public TaskHandle submit(TaskRequest request) {
                throw new AssertionError("a probe never submits a task");
            }

            @Override
            public void retireSession(UUID sessionId) {
                throw new AssertionError("a probe never touches a session");
            }

            @Override
            public RuntimeStatus status() {
                return new RuntimeStatus("routed", RuntimeStatus.State.READY, null, 0, 0);
            }

            @Override
            public Optional<RuntimeStatus> statusOf(String backend) {
                return backend.equals("local")
                        ? Optional.of(new RuntimeStatus("local", RuntimeStatus.State.UNAVAILABLE, "model-server-starting", 0, 0))
                        : Optional.empty();
            }
        };
        var probe = new RuntimeBackendProbe(() -> routed, ModelAllowlist.empty());
        assertThat(probe.probe("local", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("model-server-starting"));
        assertThat(probe.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("adapter-not-installed"));
    }

    @Test
    void aBackendWithoutAnAdapterIsUnreachable() {
        var probe = new RuntimeBackendProbe(port(status(RuntimeStatus.State.READY, null)), ModelAllowlist.empty());
        assertThat(probe.probe("local", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("adapter-not-installed"));
        assertThat(probe.probe("local-image", new EffectiveSettings("m", "none")))
                .isEqualTo(BackendProbe.Outcome.unreachable("adapter-not-installed"));
        var none = new RuntimeBackendProbe(UnavailableTaskPort::new, ModelAllowlist.empty());
        assertThat(none.probe("codex", PAIR)).isEqualTo(BackendProbe.Outcome.unreachable("adapter-not-installed"));
    }
}
