package org.rpgengine.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
     * Provisional ruleset hash recorded on every admitted ContentRevision.
     *
     * <p>Until the deterministic rules kernel publishes a hash of its executable rules (P07), this is
     * the SHA-256 of a canonical descriptor: a domain label, the ruleset ID and version, the sorted
     * policy profiles and the pack contract revision, each length-prefixed so no two descriptors share
     * an encoding. It changes whenever any of those inputs change, which is enough to pin a revision
     * to the ruleset contract it was validated against; it does not yet fingerprint rule behavior.
     *
     * @return lowercase hexadecimal SHA-256
     */
    public String rulesetHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder descriptor = new StringBuilder();
            for (String part : descriptorParts()) {
                descriptor.append(part.length()).append(':').append(part).append(';');
            }
            return HexFormat.of().formatHex(digest.digest(descriptor.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Ordered descriptor fields; profiles are sorted so set iteration order cannot change the hash. */
    private java.util.List<String> descriptorParts() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add("rpgengine.ruleset.descriptor.v1");
        parts.add(rulesetId);
        parts.add(rulesetVersion);
        // The profile count keeps the variable-length list unambiguous.
        parts.add("profiles=" + policyProfiles.size());
        parts.addAll(new TreeSet<>(policyProfiles));
        parts.add("contract=" + PACK_CONTRACT);
        return parts;
    }

    /**
     * Contract of this build: engine 0.1.0, the built-in {@code skirmish} ruleset at provisional
     * version 1.0.0 with the {@code standard} profile, and no loadable modules (ADR 0017).
     */
    public static EngineContract current() {
        return new EngineContract("0.1.0", "skirmish", "1.0.0", Set.of("standard"), Set.of());
    }
}
