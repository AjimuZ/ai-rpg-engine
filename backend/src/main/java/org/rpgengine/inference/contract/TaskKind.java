package org.rpgengine.inference.contract;

import java.time.Duration;

import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.SessionScope;

/**
 * Application task kinds implemented as typed contracts. Each kind fixes its wire name, its
 * logical {@link AiRole}, its {@link Audience} and its default local budget (docs/06 section 10);
 * the remaining docs/21 kinds (content, encounter, tactic, guide, summary, planning) acquire
 * contracts in later packages.
 *
 * <p>Role and audience are properties of the kind, not of the caller: {@link #scope} derives the
 * whole session scope from the kind and the task binding, so a caller cannot run a contract in
 * another role's or audience's session (P08.3). A future character-audience kind will take its
 * subject from its context; both kinds here are public.
 */
public enum TaskKind {
    /** Free-text player declaration to one typed intention: a proposal only. */
    INTENT("intent", AiRole.INTERPRETER, 60),
    /** Public resolved-event bundle to attributed passages and quick replies. */
    NARRATION("narration", AiRole.NARRATOR, 90);

    private final String wire;
    private final AiRole role;
    private final int budgetSeconds;

    /**
     * @param wire envelope {@code taskKind} value
     * @param role logical role; its envelope name is {@link AiRole#wire()}
     * @param budgetSeconds proposed default budget
     */
    TaskKind(String wire, AiRole role, int budgetSeconds) {
        this.wire = wire;
        this.role = role;
        this.budgetSeconds = budgetSeconds;
    }

    /** @return the envelope task kind */
    public String wire() {
        return wire;
    }

    /** @return the logical role; interpretation and narration are separate roles with separate sessions */
    public AiRole role() {
        return role;
    }

    /**
     * @return the scheduling class (P08.6); intent interpretation and the narration of a committed
     *         player action are what the player waits for, so both are interactive
     */
    public WorkClass workClass() {
        return WorkClass.INTERACTIVE;
    }

    /** @return the disclosure audience; every P08.2 kind runs for the public player audience */
    public Audience audience() {
        return Audience.PUBLIC;
    }

    /**
     * Derive the session scope of one task of this kind.
     *
     * @param binding the task's trusted binding
     * @return scope keyed by the binding's campaign, generation and hashes and this kind's role
     *         and audience
     */
    public SessionScope scope(TaskBinding binding) {
        return new SessionScope(binding.adventureId(), binding.campaignId(), binding.campaignGeneration(), role,
                audience(), binding.packHash(), binding.policyHash());
    }

    /** @return the default budget from submission to the last attempt, including one repair */
    public Duration defaultBudget() {
        return Duration.ofSeconds(budgetSeconds);
    }
}
