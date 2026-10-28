package org.rpgengine.inference.contract;

/**
 * Application task kinds implemented as typed contracts. Each kind fixes its logical role, wire
 * name and default local budget (docs/06 section 10); the remaining docs/21 kinds (content,
 * encounter, tactic, guide, summary, planning) acquire contracts in later packages.
 */
public enum TaskKind {
    /** Free-text player declaration to one typed intention: a proposal only. */
    INTENT("intent", "interpreter", 60),
    /** Public resolved-event bundle to attributed passages and quick replies. */
    NARRATION("narration", "narrator", 90);

    private final String wire;
    private final String role;
    private final int budgetSeconds;

    /**
     * @param wire envelope {@code taskKind} value
     * @param role envelope {@code role} value
     * @param budgetSeconds proposed default budget
     */
    TaskKind(String wire, String role, int budgetSeconds) {
        this.wire = wire;
        this.role = role;
        this.budgetSeconds = budgetSeconds;
    }

    /** @return the envelope task kind */
    public String wire() {
        return wire;
    }

    /** @return the envelope role; every P08.2 kind runs for the public player audience */
    public String role() {
        return role;
    }

    /** @return the default budget from submission to the last attempt, including one repair */
    public java.time.Duration defaultBudget() {
        return java.time.Duration.ofSeconds(budgetSeconds);
    }
}
