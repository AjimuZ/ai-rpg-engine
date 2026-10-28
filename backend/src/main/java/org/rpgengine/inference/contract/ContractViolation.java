package org.rpgengine.inference.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One reason an untrusted contract output was rejected. Violations carry a fixed code and a JSON
 * pointer into the output, never output text, so they can be logged, counted and sent back in a
 * repair instruction without echoing model prose or leaking context.
 *
 * @param kind whether the output broke the shape or the meaning of the contract
 * @param code fixed lowercase identifier, for example {@code unknown-reference}
 * @param path RFC 6901 pointer into the output document; empty for the whole document
 */
public record ContractViolation(Kind kind, String code, String path) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final int MAX_PATH = 256;

    /** Broad class of a violation. */
    public enum Kind {
        /** Syntax, field set, type, bound or echo failures: the output is not the contract shape. */
        STRUCTURAL,
        /** Well-formed output that contradicts the supplied context, scope, locale or agency rules. */
        SEMANTIC
    }

    /**
     * Require a fixed code and a bounded pointer.
     *
     * @throws IllegalArgumentException for a free-form code or an oversized path
     */
    public ContractViolation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("violation code must be a fixed identifier");
        }
        if (path.length() > MAX_PATH) {
            // Pointers are built from schema field names and indexes only; truncation keeps them bounded
            // even if a future caller passes a model-chosen key.
            path = path.substring(0, MAX_PATH);
        }
    }

    /** @return a structural violation */
    static ContractViolation structural(String code, String path) {
        return new ContractViolation(Kind.STRUCTURAL, code, path);
    }

    /** @return a semantic violation */
    static ContractViolation semantic(String code, String path) {
        return new ContractViolation(Kind.SEMANTIC, code, path);
    }
}
