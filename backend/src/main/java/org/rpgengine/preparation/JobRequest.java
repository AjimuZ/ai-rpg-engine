package org.rpgengine.preparation;

import java.time.Duration;
import java.util.Objects;

import org.rpgengine.persistence.CanonicalValues;

/**
 * Backend-computed description of one preparation job (docs/28 section 5). Every
 * hash is computed by trusted code before enqueueing; nothing here is executable.
 * Identity fields (kind, target, purpose, contract version, dependency fingerprint
 * and policy hash) together with the campaign generation form the deduplication key,
 * so an identical request, including a replayed outbox intent, finds the same job.
 *
 * @param kind upper-case job kind such as {@code QUEST_PLAN}
 * @param target bounded logical target such as a quest key (1..256 characters)
 * @param purpose lower-case refinement purpose such as {@code initial}
 * @param contractVersion versioned task contract identifier
 * @param dependencyFingerprint SHA-256 of the dependencies the request was based on
 * @param payloadHash SHA-256 of the bounded request payload
 * @param schemaHash SHA-256 of the expected result schema
 * @param policyHash SHA-256 of the effective tool/sandbox/audience policy
 * @param priority 0..1000, higher is claimed first
 * @param critical whether the job must be created by its originating canonical commit
 * @param delay non-negative time before the job becomes claimable
 * @param timeToLive positive window after the due time; later claims are refused
 * @param maxAttempts 1..20 leases in total, including retries and regenerations
 * @param estimatedUsage non-negative opaque usage estimate (accounting arrives at P08.6)
 * @param reservedUsage non-negative opaque usage reservation
 */
public record JobRequest(String kind, String target, String purpose, String contractVersion,
        String dependencyFingerprint, String payloadHash, String schemaHash, String policyHash, int priority,
        boolean critical, Duration delay, Duration timeToLive, int maxAttempts, long estimatedUsage,
        long reservedUsage) {
    /** Reject malformed or out-of-range fields before any transaction is opened. */
    public JobRequest {
        PreparationSupport.require(PreparationSupport.CODE, kind, "kind");
        CanonicalValues.requireText(target, "target", 256);
        PreparationSupport.require(PreparationSupport.PURPOSE, purpose, "purpose");
        PreparationSupport.require(PreparationSupport.VERSION, contractVersion, "contractVersion");
        CanonicalValues.requireSha256Hex(dependencyFingerprint, "dependencyFingerprint");
        CanonicalValues.requireSha256Hex(payloadHash, "payloadHash");
        CanonicalValues.requireSha256Hex(schemaHash, "schemaHash");
        CanonicalValues.requireSha256Hex(policyHash, "policyHash");
        if (priority < 0 || priority > 1000) {
            throw new IllegalArgumentException("priority must be 0..1000");
        }
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(timeToLive, "timeToLive");
        // Whole seconds keep the SQL interval arithmetic exact; one day bounds both.
        if (delay.isNegative() || delay.getNano() != 0 || delay.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("delay must be 0..86400 whole seconds");
        }
        if (timeToLive.getNano() != 0 || timeToLive.getSeconds() < 1 || timeToLive.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("timeToLive must be 1..86400 whole seconds");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("maxAttempts must be 1..20");
        }
        CanonicalValues.requireNonNegative(estimatedUsage, "estimatedUsage");
        CanonicalValues.requireNonNegative(reservedUsage, "reservedUsage");
    }
}
