package org.rpgengine.content;

import java.util.List;
import java.util.Objects;

/**
 * Whether this engine build can load a campaign's pinned content closure (P09.5, docs/08 section 10),
 * as reported by {@link PackRegistry#diagnose}. A diagnosis names only fixed codes and the public
 * coordinates (pack ID and version) of the revision concerned; it never carries file bytes, paths,
 * hashes, parser messages or definition text, so it can be shown to the owner as restore/import
 * guidance.
 *
 * @param status overall result
 * @param issues what is missing or unusable; empty unless {@link Status#MISSING}
 */
public record ClosureDiagnosis(Status status, List<Issue> issues) {
    /** A loadable closure. */
    public static final ClosureDiagnosis AVAILABLE = new ClosureDiagnosis(Status.AVAILABLE, List.of());
    /** A legacy revision admitted before sealing (no stored file inventory). */
    public static final ClosureDiagnosis UNSEALED = new ClosureDiagnosis(Status.UNSEALED, List.of());

    /** Copy the issues and require them exactly for {@link Status#MISSING}. */
    public ClosureDiagnosis {
        Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues);
        if ((status == Status.MISSING) == issues.isEmpty()) {
            throw new IllegalArgumentException("a missing closure names its issues, and only then");
        }
    }

    /**
     * @return true when mutation of a campaign pinning this closure must pause until the content is
     *         restored or imported again; a legacy unsealed revision never pauses, because nothing
     *         that once existed was lost and no restore can supply it
     */
    public boolean pausesMutation() {
        return status == Status.MISSING;
    }

    /** Overall diagnosis. */
    public enum Status {
        /** Every pinned revision loads, verifies and compiles under this engine build. */
        AVAILABLE,
        /**
         * The root revision predates sealed storage: its bytes were never stored. Rules that need
         * definitions keep failing closed ({@code COMMAND_CONTENT_UNAVAILABLE}); other rules work.
         */
        UNSEALED,
        /** At least one pinned revision is absent, unverifiable or unusable; see the issues. */
        MISSING
    }

    /**
     * One missing or unusable part of the closure.
     *
     * @param code fixed reason
     * @param packId pack identity of the revision concerned, null when even that is unknown
     * @param packVersion its version, null when unknown
     */
    public record Issue(Code code, String packId, String packVersion) {
        /** Require the code. */
        public Issue {
            Objects.requireNonNull(code, "code");
        }
    }

    /** Fixed issue codes; the public API spells them as their names. */
    public enum Code {
        /** The pinned root revision does not exist in the campaign's adventure. */
        REVISION_MISSING,
        /** A revision was sealed for another ruleset than this engine build provides. */
        RULESET_UNAVAILABLE,
        /** The stored bytes of a sealed revision no longer reproduce its pinned content hash. */
        CONTENT_CORRUPT,
        /** A dependency pinned by ID, version and hash is not a sealed revision of the adventure. */
        DEPENDENCY_MISSING,
        /** The closure exceeds the engine's bounds, has a cycle or conflicting versions, or does not compile. */
        CLOSURE_INVALID
    }
}
