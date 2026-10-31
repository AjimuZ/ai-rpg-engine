package org.rpgengine.inference.slots;

import java.util.Objects;
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
 * {@link BackendProbe} that answers from the one task port the installation runs. It sends nothing:
 * it reads the port's non-sensitive status, so it is bounded and cannot spend quota.
 *
 * <p>A backend other than the active runtime has no adapter in this process and is
 * {@code UNREACHABLE} with {@code adapter-not-installed}. For the active runtime the verdict is the
 * port's state; a ready runtime is {@code FAILED} with {@code model-not-approved} when the owner's
 * allowlist for that backend does not list the exact pair. A ready verdict therefore means the
 * runtime is signed in and the pair is not excluded: it does not prove the account serves that
 * model, which only a task proves.
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
        RuntimeStatus status = port.get().status();
        if (!status.runtime().equals(backend)) {
            return Outcome.unreachable("adapter-not-installed");
        }
        return switch (status.state()) {
            case READY -> allowlist.configured(backend) && !allowlist.permits(backend, settings)
                    ? Outcome.failed("model-not-approved") : Outcome.ready();
            case STARTING -> Outcome.unreachable("runtime-starting");
            case DISABLED -> Outcome.unreachable("adapter-not-installed");
            case UNAVAILABLE -> Outcome.unreachable(status.reasonCode() != null
                    && CODE.matcher(status.reasonCode()).matches() ? status.reasonCode() : "runtime-unavailable");
            case STOPPING, STOPPED -> Outcome.unreachable("runtime-stopped");
        };
    }
}
