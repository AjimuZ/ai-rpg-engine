package org.rpgengine.inference.slots;

import java.util.Optional;

/**
 * Billing and privacy class of an assignment (ADR 0021 decision 8, docs/34 section 11). The class
 * is derived from the backend (and, for an owner-run endpoint, the owner's declaration); it is
 * never inferred from a model name. Each class carries the fixed billing and privacy codes the
 * Settings panel renders as a badge, so the wording stays localizable outside the backend.
 */
public enum BackendClass {
    /** Data stays on the owner's machine (or LAN for an owner-run server); no per-use cost. */
    LOCAL("local", "free", "this-machine"),
    /** An agent runtime: the owner's plan pays, and the runtime vendor receives the prompts. */
    SUBSCRIPTION("subscription", "plan", "runtime-vendor"),
    /** An API vendor receives the prompts and bills per token or per image. */
    METERED("metered", "per-use", "api-vendor");

    private final String wire;
    private final String billing;
    private final String privacy;

    BackendClass(String wire, String billing, String privacy) {
        this.wire = wire;
        this.billing = billing;
        this.privacy = privacy;
    }

    /** @return the fixed identifier used on the wire and in storage */
    public String wire() {
        return wire;
    }

    /** @return the fixed billing code: {@code free}, {@code plan} or {@code per-use} */
    public String billing() {
        return billing;
    }

    /** @return the fixed privacy code: {@code this-machine}, {@code runtime-vendor} or {@code api-vendor} */
    public String privacy() {
        return privacy;
    }

    /** @return whether prompts, including any secret canon they carry, leave the owner's machine */
    public boolean leavesMachine() {
        return this != LOCAL;
    }

    /**
     * @param text a wire identifier, or null
     * @return the class it names, or empty for null and for anything outside the closed set
     */
    public static Optional<BackendClass> parse(String text) {
        for (BackendClass value : values()) {
            if (value.wire.equals(text)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
