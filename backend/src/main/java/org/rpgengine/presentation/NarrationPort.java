package org.rpgengine.presentation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runtime-neutral boundary through which a post-commit presentation job asks for narration of
 * one committed operation (P07.5, P08.5). The request carries references only: an implementation
 * reads the audience-filtered resolved event bundle itself and may never receive or return world
 * mutations. The engine wires the grounded narration publisher ({@code
 * org.rpgengine.narration.GroundedNarration}), which validates the output against the public
 * event bundle before returning it; with no ready runtime it reports {@link Outcome#UNAVAILABLE}.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>Called outside any database transaction, at most once per job attempt, by the worker
 *       holding that attempt's lease. A call may repeat for a later attempt or a later retry
 *       round of the same operation; implementations write nothing durable themselves.</li>
 *   <li>Return within the job lease ({@link PresentationJobService#LEASE_SECONDS} seconds). A
 *       result delivered after the lease expired or after the job was settled elsewhere is
 *       discarded by the job service.</li>
 *   <li>{@link Outcome#DELIVERED} carries a {@link Publication} that passed every mechanical
 *       check for exactly this operation and revision. Publication is decided by the settling
 *       transaction, which stores it only when the lease is still held, no cancellation was
 *       requested and the campaign's locale and glossary generation still equal the
 *       publication's; otherwise it is discarded and never shown.</li>
 *   <li>Throwing is equivalent to {@link Outcome#FAILED}; nothing is retried inside the call.</li>
 * </ul>
 */
public interface NarrationPort {
    /**
     * Narrate one committed operation.
     *
     * @param request references of the committed operation and attempt
     * @return delivery result; never null
     */
    Result narrate(Request request);

    /** Result kind of one narration attempt. */
    enum Outcome {
        /** Validated narration for this operation exists and may be published. */
        DELIVERED,
        /** No narration runtime is configured or ready; retrying now would not help. */
        UNAVAILABLE,
        /**
         * The output stayed invalid after its single repair, the narrator declined, or the event
         * bundle cannot be narrated within the contract. The deterministic receipt is the final
         * presentation of this round at once: asking again in the same round would be the
         * unbounded loop docs/07 section 9 forbids.
         */
        REJECTED,
        /** A transient failure; the job retries while attempts remain. */
        FAILED
    }

    /**
     * Outcome of one attempt with the validated narration when delivered.
     *
     * @param outcome attempt outcome
     * @param publication validated narration, present exactly for {@link Outcome#DELIVERED}
     */
    record Result(Outcome outcome, Publication publication) {
        /** @throws IllegalArgumentException when the publication does not match the outcome */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.DELIVERED) != (publication != null)) {
                throw new IllegalArgumentException("exactly a delivered result carries a publication");
            }
        }

        /** @return a delivered result */
        public static Result delivered(Publication publication) {
            return new Result(Outcome.DELIVERED, Objects.requireNonNull(publication, "publication"));
        }

        /** @return an undelivered result of {@code outcome} */
        public static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    /**
     * Reference-only narration request.
     *
     * @param adventureId owning adventure
     * @param campaignId owning campaign
     * @param operationId committed operation to narrate
     * @param committedRevision revision the operation produced
     * @param attempt 1-based attempt number within the job round
     * @param jobId presentation job round identity; with {@code attempt} it identifies the call
     * @param campaignGeneration campaign recovery generation the job round belongs to
     */
    record Request(UUID adventureId, UUID campaignId, UUID operationId, long committedRevision, int attempt,
            UUID jobId, long campaignGeneration) {
        /** Reject incomplete references before any implementation sees them. */
        public Request {
            Objects.requireNonNull(adventureId, "adventureId");
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(jobId, "jobId");
            if (committedRevision <= 0 || attempt < 1 || campaignGeneration < 0) {
                throw new IllegalArgumentException("committed revision and attempt must be positive");
            }
        }
    }

    /**
     * Validated narration of one operation, ready to be stored by the settling transaction.
     * Every string was produced in {@code locale} and passed the narration contract's checks;
     * speaker names come from the trusted context, never from model output.
     *
     * @param locale passage locale (the binding's output locale)
     * @param localeRevision campaign locale revision the narration was bound to
     * @param glossaryRevision campaign glossary revision the narration was bound to
     * @param templateHash SHA-256 of the instruction template and schema used
     * @param repaired true when the accepted output came from the single repair
     * @param passages one to {@link #MAX_PASSAGES} attributed passages in order
     * @param suggestions zero to {@link #MAX_SUGGESTIONS} editable quick replies
     * @param runtime runtime name of the accepted attempt, null when unknown
     * @param model effective model of the accepted attempt, null when unknown
     * @param effort effective effort of the accepted attempt, null when unknown
     */
    record Publication(SupportedLocale locale, long localeRevision, long glossaryRevision, String templateHash,
            boolean repaired, List<Passage> passages, List<Suggestion> suggestions, String runtime, String model,
            String effort) {
        /** Most passages, equal to the narration contract's bound. */
        public static final int MAX_PASSAGES = 8;
        /** Most suggestions, equal to the narration contract's bound. */
        public static final int MAX_SUGGESTIONS = 3;
        private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

        /** @throws IllegalArgumentException for out-of-bound lists, counters or a malformed hash */
        public Publication {
            Objects.requireNonNull(locale, "locale");
            Objects.requireNonNull(templateHash, "templateHash");
            passages = List.copyOf(passages);
            suggestions = List.copyOf(suggestions);
            if (localeRevision < 0 || glossaryRevision < 0 || !HASH.matcher(templateHash).matches()
                    || passages.isEmpty() || passages.size() > MAX_PASSAGES || suggestions.size() > MAX_SUGGESTIONS) {
                throw new IllegalArgumentException("publication outside the narration contract bounds");
            }
        }
    }

    /** Passage kinds, matching the narration contract. */
    enum PassageKind {
        /** Narrator prose; no speaker. */
        NARRATION,
        /** Words of one known non-player speaker. */
        CHARACTER_SPEECH
    }

    /**
     * One attributed passage.
     *
     * @param kind narration or character speech
     * @param speakerActorId known speaker for character speech, null for narration
     * @param speakerName display name of that speaker from the trusted context, null for narration
     * @param text accepted selected-locale prose
     * @param references cited event and fact identities
     */
    record Passage(PassageKind kind, UUID speakerActorId, String speakerName, String text, List<String> references) {
        /** @throws IllegalArgumentException when the speaker does not match the kind */
        public Passage {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            references = List.copyOf(references);
            boolean speech = kind == PassageKind.CHARACTER_SPEECH;
            if (speech != (speakerActorId != null) || speech != (speakerName != null)) {
                throw new IllegalArgumentException("exactly character speech names a speaker");
            }
        }
    }

    /** Quick-reply kinds, matching the narration contract. */
    enum SuggestionKind {
        /** A reply to what a character said. */
        REPLY,
        /** A question the player could ask. */
        QUESTION,
        /** One available action of the pending decision. */
        ACTION
    }

    /**
     * One editable quick reply; selecting it only fills the composer and never submits.
     *
     * @param kind reply, question or action
     * @param label concise selected-locale label
     * @param draftText editable selected-locale draft
     * @param actionId available action of the pending decision, present exactly for actions
     */
    record Suggestion(SuggestionKind kind, String label, String draftText, String actionId) {
        /** @throws IllegalArgumentException when the action reference does not match the kind */
        public Suggestion {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(draftText, "draftText");
            if ((kind == SuggestionKind.ACTION) != (actionId != null)) {
                throw new IllegalArgumentException("exactly an action suggestion names an action");
            }
        }
    }
}
