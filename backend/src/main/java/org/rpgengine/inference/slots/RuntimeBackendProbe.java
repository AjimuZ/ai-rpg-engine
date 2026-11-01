package org.rpgengine.inference.slots;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link BackendProbe} that answers from the task port the installation runs. It sends nothing:
 * it reads the port's non-sensitive status of that backend, so it is bounded and cannot spend quota.
 *
 * <p>A backend without an adapter in this process is {@code UNREACHABLE} with
 * {@code adapter-not-installed}. For an installed backend the verdict is its adapter's state; a ready
 * one is {@code FAILED} with {@code model-not-approved} when the owner's allowlist for that backend
 * does not list the exact pair, or {@code FAILED} with {@code model-not-listed} when the backend
 * enumerates its models and its current list lacks the model. A ready verdict therefore means the
 * adapter is usable (signed in, or the model server listed its models), the pair is not excluded and,
 * where the backend lists models, the model is listed: it does not prove the account or server can
 * run that model, which only a task proves.
 */
@Component
public class RuntimeBackendProbe implements BackendProbe {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private final Supplier<NarrationTaskPort> port;
    private final ModelAllowlist allowlist;

    /**
     * The task port is looked up on each probe, not injected: the adapter depends on the profile
     * resolver, which reads the slot settings, which own this probe, so holding the port here would
     * be a startup cycle.
     *
     * @param port provider of the installation's task port
     * @param allowlist the owner's approved pairs
     */
    @Autowired
    public RuntimeBackendProbe(ObjectProvider<NarrationTaskPort> port, ModelAllowlist allowlist) {
        this(port::getObject, allowlist);
    }

    RuntimeBackendProbe(Supplier<NarrationTaskPort> port, ModelAllowlist allowlist) {
        this.port = Objects.requireNonNull(port, "port");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
    }

    @Override
    public Outcome probe(String backend, EffectiveSettings settings) {
        Optional<RuntimeStatus> installed = port.get().statusOf(backend);
        if (installed.isEmpty()) {
            return Outcome.unreachable("adapter-not-installed");
        }
        RuntimeStatus status = installed.get();
        return switch (status.state()) {
            case READY -> {
                if (allowlist.configured(backend) && !allowlist.permits(backend, settings)) {
                    yield Outcome.failed("model-not-approved");
                }
                // The local router names models by file stem, so an ID copied from the catalog can be
                // absent from its list; tasks would end slot-unsupported, and the probe says so first.
                yield port.get().listsModel(backend, settings.model()).orElse(true) ? Outcome.ready()
                        : Outcome.failed("model-not-listed");
            }
            case STARTING -> Outcome.unreachable("runtime-starting");
            case DISABLED -> Outcome.unreachable("adapter-not-installed");
            case UNAVAILABLE -> Outcome.unreachable(status.reasonCode() != null
                    && CODE.matcher(status.reasonCode()).matches() ? status.reasonCode() : "runtime-unavailable");
            case STOPPING, STOPPED -> Outcome.unreachable("runtime-stopped");
        };
    }
}
