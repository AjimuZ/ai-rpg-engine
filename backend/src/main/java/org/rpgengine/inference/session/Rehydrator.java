package org.rpgengine.inference.session;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the continuity block that the first task of a renewed session carries (P09.4, docs/08
 * section 7). The implementation lives with the memory layer
 * ({@code org.rpgengine.memory.SessionRehydration}); this port keeps the session registry free of
 * storage and context-assembly code.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>The block is assembled for exactly the scope's role and audience from committed canonical
 *       state, so it never carries anything the audience may not see.</li>
 *   <li>It is data framed as never being an instruction; it marks the conversation as a renewal,
 *       states where play stands (the pending decision) and forbids replaying the opening or
 *       inventing history.</li>
 *   <li>It writes no canonical state. Capturing an operational checkpoint or queuing a summary
 *       job is allowed, because neither advances the campaign.</li>
 *   <li>Expected failures are results, never exceptions: {@link Deferred} only when the caller
 *       allowed deferral and a commit may be in progress, otherwise {@link Unavailable}.</li>
 * </ul>
 * Rehydration is supplementary: every contract task already carries its own audience-filtered
 * canonical context, so a missing block costs continuity, never correctness.
 */
public interface Rehydrator {
    /** A rehydrator that never supplies a block (tests and installations without memory). */
    Rehydrator NONE = (scope, cause, deferrable) -> new Unavailable("rehydration-unconfigured");

    /**
     * Prepare the continuity block of a new session of {@code scope}.
     *
     * @param scope scope of the session about to open
     * @param cause wire name of why the previous session of the line ended, for example
     *        {@code turn-ceiling}
     * @param deferrable true when the previous session is still usable, so an unsafe boundary may
     *        postpone the renewal instead of rehydrating from a campaign in mid-commit
     * @return the block, a deferral or an unavailability code
     */
    Result prepare(SessionScope scope, String cause, boolean deferrable);

    /** Outcome of {@link #prepare}. */
    sealed interface Result permits Ready, Deferred, Unavailable {
    }

    /**
     * A block to prepend to the first task of the new session.
     *
     * @param text complete block, ending without a trailing separator
     * @param basis SUMMARY, CHECKPOINT or STATE: what the continuity rests on
     * @param revision canonical state revision the block was assembled at
     * @param digest lowercase SHA-256 hex of the block's continuity JSON
     * @param summaryId continuity summary included in the block, exactly when the basis is SUMMARY
     * @param checkpoint the verified operational checkpoint, or null when none could be verified
     * @param checkpointStatus verification status of the newest checkpoint ({@code VALID},
     *        {@code OUTDATED}, {@code NONE}, {@code UNSAFE}, ...)
     */
    record Ready(String text, String basis, long revision, String digest, UUID summaryId, Checkpoint checkpoint,
            String checkpointStatus) implements Result {
        private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

        /** @throws IllegalArgumentException for an inconsistent result */
        public Ready {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(checkpointStatus, "checkpointStatus");
            if (text.isBlank() || !java.util.Set.of("SUMMARY", "CHECKPOINT", "STATE").contains(basis)
                    || revision < 0 || digest == null || !DIGEST.matcher(digest).matches()
                    || ("SUMMARY".equals(basis) != (summaryId != null))) {
                throw new IllegalArgumentException("inconsistent rehydration");
            }
        }
    }

    /**
     * The verified checkpoint a block was checked against.
     *
     * @param id checkpoint identity
     * @param revision state revision it describes
     * @param hash SHA-256 of its canonical snapshot; recorded durably, never rendered into a block
     */
    record Checkpoint(UUID id, long revision, String hash) {
        /** @throws NullPointerException for a missing component */
        public Checkpoint {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(hash, "hash");
        }
    }

    /**
     * The renewal should wait: a commit may be in progress and the previous session still works.
     *
     * @param code fixed reason, for example {@code unsafe-boundary}
     */
    record Deferred(String code) implements Result {
        /** @throws NullPointerException for a missing code */
        public Deferred {
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * No block could be built; the session opens without one.
     *
     * @param code fixed reason, for example {@code context-too-large}
     */
    record Unavailable(String code) implements Result {
        /** @throws NullPointerException for a missing code */
        public Unavailable {
            Objects.requireNonNull(code, "code");
        }
    }
}
