package org.rpgengine.inference.contract;

import java.util.List;

/**
 * Validated output of the continuity-summary contract (P09.3). A proposal is derived text only: it
 * never becomes a canonical fact, and the caller stores it with its exact coverage and sources.
 */
public sealed interface SummaryProposal {
    /**
     * An accepted summary.
     *
     * @param summary summary prose in the output locale
     * @param coveredEvents the supplied events the summary covers, in supplied order; always all of them
     * @param obligations one note per supplied open obligation, in output order
     * @param entities supplied actor and location references the summary concerns
     * @param uncertainties notes on supplied beliefs the summary reports as uncertain
     */
    record Summarized(String summary, List<String> coveredEvents, List<Note> obligations, List<String> entities,
            List<Note> uncertainties) implements SummaryProposal {
        /** Freeze the lists. */
        public Summarized {
            coveredEvents = List.copyOf(coveredEvents);
            obligations = List.copyOf(obligations);
            entities = List.copyOf(entities);
            uncertainties = List.copyOf(uncertainties);
        }
    }

    /**
     * The summarizer declined; nothing is stored and the bounded job decides about a retry.
     *
     * @param reasonCode {@code missing-information} or {@code out-of-scope}
     */
    record CannotSummarize(String reasonCode) implements SummaryProposal {
    }

    /**
     * A short note attached to one supplied record.
     *
     * @param ref supplied source reference
     * @param note note text in the output locale
     */
    record Note(String ref, String note) {
    }
}
