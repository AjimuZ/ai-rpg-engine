package org.rpgengine.memory;

import org.rpgengine.inference.contract.GlossaryTerm;

/**
 * Test-only bridge to the package-private charges of P09.2 budgeting, so suites in other packages
 * can check that every rendered part of an envelope was charged.
 */
public final class ContextCosts {
    /** Static helpers only. */
    private ContextCosts() {
    }

    /** @return the selection charge of one item's own rendering */
    public static long item(ContextItem item) {
        return ContextSelection.cost(item);
    }

    /** @return the charge of one rendered glossary entry */
    public static long term(GlossaryTerm term) {
        return ContextEnvelope.glossaryCost(term);
    }

    /** @return the fixed envelope overhead charged before any item */
    public static long overhead(ContextEnvelope envelope) {
        return ContextEnvelope.overhead(envelope.stamp(), envelope.budget(), envelope.glossaryAvailable());
    }
}
