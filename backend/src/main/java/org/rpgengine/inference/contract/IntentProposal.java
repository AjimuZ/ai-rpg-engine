package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;

import org.rpgengine.commands.TypedCommand;

/**
 * Accepted result of an intent interpretation. A proposal grants nothing: a
 * {@link Proposed} command must still be submitted by the player's own request through ordinary
 * admission, rule validation and idempotency, and a clarification only returns a question to
 * the player (docs/07 section 10).
 */
public sealed interface IntentProposal permits IntentProposal.Proposed, IntentProposal.Clarification,
        IntentProposal.CannotComplete {

    /**
     * Exactly one typed intention.
     *
     * @param command typed intention ready for admission; never an effect
     * @param sourceFactIds public facts the interpretation relied on
     */
    record Proposed(TypedCommand command, List<String> sourceFactIds) implements IntentProposal {
        /** Freeze the citations. */
        public Proposed {
            Objects.requireNonNull(command, "command");
            sourceFactIds = List.copyOf(sourceFactIds);
        }
    }

    /**
     * A focused question with bounded interpretations, for consequential ambiguity.
     *
     * @param question selected-locale question for the player
     * @param interpretations two to four candidate readings
     */
    record Clarification(String question, List<Interpretation> interpretations) implements IntentProposal {
        /** Freeze the interpretations. */
        public Clarification {
            Objects.requireNonNull(question, "question");
            interpretations = List.copyOf(interpretations);
        }
    }

    /**
     * One candidate reading of an ambiguous declaration; choosing it prepares a draft only.
     *
     * @param interpretationId output-local identifier
     * @param label selected-locale label
     * @param intentKind intention the reading would propose
     * @param referenceIds known option identities the reading refers to
     */
    record Interpretation(String interpretationId, String label, IntentKind intentKind, List<String> referenceIds) {
        /** Freeze the references. */
        public Interpretation {
            referenceIds = List.copyOf(referenceIds);
        }
    }

    /**
     * The declaration cannot be mapped to a supported intention.
     *
     * @param reasonCode {@code unsupported-intention}, {@code missing-information} or {@code not-permitted}
     */
    record CannotComplete(String reasonCode) implements IntentProposal {
    }
}
