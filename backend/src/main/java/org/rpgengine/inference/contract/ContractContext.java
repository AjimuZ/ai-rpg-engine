package org.rpgengine.inference.contract;

import java.util.List;

/**
 * Trusted, audience-filtered input of one contract task. Implementations are immutable records
 * assembled by backend code from authorized projections; they never contain raw entities,
 * credentials, another campaign's history or privileged planner text.
 */
public interface ContractContext {
    /** @return identity and presentation generation the task is bound to */
    TaskBinding binding();

    /** @return selected-locale terminology the output must respect */
    List<GlossaryTerm> glossary();

    /** @return privileged phrases the output must not contain; never rendered */
    DisclosureGuard guard();
}
