package org.rpgengine.content;

import java.util.Objects;
import java.util.Set;

/**
 * What the running engine offers to packs: its own version, the one ruleset it implements, the
 * policy profiles that ruleset accepts and the module IDs it can load (docs/09 sections 4 and 10).
 *
 * <p>The compiler checks a manifest's {@code engineCompatibility} range, {@code ruleset} block and
 * {@code modules} list against this value. Tests may pass other contracts to exercise mismatches.
 *
 * @param engineVersion exact SemVer of the engine build
 * @param rulesetId stable ID of the implemented ruleset
 * @param rulesetVersion exact SemVer of the implemented ruleset
 * @param policyProfiles policy profiles the ruleset accepts
 * @param modules module IDs this engine can load; empty while no module runtime exists
 */
public record EngineContract(String engineVersion, String rulesetId, String rulesetVersion,
        Set<String> policyProfiles, Set<String> modules) {
    /** Pack contract revision this compiler implements; reported in unsupported-module diagnostics. */
    public static final int PACK_CONTRACT = 1;

    /** Validate both versions eagerly so a misconfigured contract fails at construction, not per pack. */
    public EngineContract {
        Objects.requireNonNull(rulesetId, "rulesetId");
        Versions.version(engineVersion);
        Versions.version(rulesetVersion);
        policyProfiles = Set.copyOf(policyProfiles);
        modules = Set.copyOf(modules);
    }

    /**
     * Contract of this build: engine 0.1.0, the built-in {@code skirmish} ruleset at provisional
     * version 1.0.0 with the {@code standard} profile, and no loadable modules (ADR 0017).
     */
    public static EngineContract current() {
        return new EngineContract("0.1.0", "skirmish", "1.0.0", Set.of("standard"), Set.of());
    }
}
