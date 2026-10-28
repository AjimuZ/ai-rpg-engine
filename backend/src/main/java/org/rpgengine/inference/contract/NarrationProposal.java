package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepted narration output (docs/21 section 8). Acceptance means the output passed the
 * mechanical structure, reference, scope, glossary, number and locale checks; it is still a
 * candidate that P08.5 publishes only if its binding is current. Passage and suggestion
 * identifiers are output-local: the publisher assigns stable message identities.
 */
public sealed interface NarrationProposal permits NarrationProposal.Narrated, NarrationProposal.CannotNarrate {

    /**
     * Ordered passages and optional suggestions.
     *
     * @param passages one to eight attributed passages
     * @param suggestions zero to three editable quick replies for the pending decision
     */
    record Narrated(List<Passage> passages, List<Suggestion> suggestions) implements NarrationProposal {
        /** Freeze both lists. */
        public Narrated {
            passages = List.copyOf(passages);
            suggestions = List.copyOf(suggestions);
        }
    }

    /**
     * The narrator declined; the publisher shows the deterministic receipt instead.
     *
     * @param reasonCode {@code missing-information} or {@code out-of-scope}
     */
    record CannotNarrate(String reasonCode) implements NarrationProposal {
    }

    /** Passage kinds. */
    enum PassageKind {
        /** Narrator prose; no speaker. */
        NARRATION,
        /** Words of one known non-player speaker. */
        CHARACTER_SPEECH
    }

    /**
     * One attributed passage.
     *
     * @param passageId output-local identifier
     * @param kind narration or character speech
     * @param speakerId known speaker for character speech, null for narration
     * @param text selected-locale prose
     * @param references cited event and fact identities, at least one
     * @param quoteIds protected quotes reproduced verbatim in the text
     */
    record Passage(String passageId, PassageKind kind, UUID speakerId, String text, List<String> references,
            List<String> quoteIds) {
        /** Freeze the lists. */
        public Passage {
            Objects.requireNonNull(text, "text");
            references = List.copyOf(references);
            quoteIds = List.copyOf(quoteIds);
        }
    }

    /** Quick-reply kinds. */
    enum SuggestionKind {
        /** A reply to what a character said. */
        REPLY,
        /** A question the player could ask. */
        QUESTION,
        /** One available action of the pending decision. */
        ACTION
    }

    /**
     * One editable quick reply. Selecting it only fills the composer; the player still submits
     * explicitly and the submission passes ordinary validation (docs/07 section 10).
     *
     * @param suggestionId output-local identifier
     * @param kind reply, question or action
     * @param label concise selected-locale label
     * @param draftText editable selected-locale draft
     * @param actionId available action of the pending decision, present exactly for actions
     */
    record Suggestion(String suggestionId, SuggestionKind kind, String label, String draftText, String actionId) {
    }
}
