package org.rpgengine.inference.contract;

import java.util.List;

/**
 * Result of validating one untrusted output against its context.
 *
 * @param value the typed proposal, present exactly when there are no violations
 * @param violations every structural and semantic violation found; empty when accepted
 * @param warnings fixed codes of non-blocking signals, such as an uncertain locale signal
 * @param <T> typed proposal
 */
public record Validation<T>(T value, List<ContractViolation> violations, List<String> warnings) {
    /** Enforce the value/violation pairing and freeze both lists. */
    public Validation {
        violations = List.copyOf(violations);
        warnings = List.copyOf(warnings);
        if ((value == null) == violations.isEmpty()) {
            throw new IllegalArgumentException("a value is present exactly when there are no violations");
        }
    }

    /** @return true when the output was accepted */
    public boolean accepted() {
        return value != null;
    }

    /** @return a rejected validation */
    static <T> Validation<T> rejected(List<ContractViolation> violations, List<String> warnings) {
        return new Validation<>(null, violations, warnings);
    }
}
