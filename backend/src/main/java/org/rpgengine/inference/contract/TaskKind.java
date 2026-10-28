package org.rpgengine.inference.contract;

import java.time.Duration;

import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.SessionScope;

/**
 * Application task kinds implemented as typed contracts. Each kind fixes its wire name, its
 * logical {@link AiRole}, its {@link Audience}, its scheduling {@link WorkClass} and its default
 * local budget (docs/06 section 10); the remaining docs/21 kinds (content, encounter, tactic, guide,
 * world direction, plan review) acquire contracts in later packages.
 *
 * <p>Role and audience are properties of the kind, not of the caller: {@link #scope} derives the
 * whole session scope from the kind and the task binding, so a caller cannot run a contract in
 * another role's or audience's session (P08.3). A future character-audience kind will take its
 * subject from its context. Intent and narration are public interactive work; the two quest-plan
 * kinds (P08.7) are privileged preparation work of the quest planner, which the fair scheduler
 * runs only when no interactive task waits and preempts for any player turn. The two summary kinds
 * (P09.3) belong to the {@link AiRole#SUMMARIZER}, whose sessions take exactly the audience of their
 * inputs; one kind per audience keeps a public and a privileged summary in separate sessions, and
 * both are {@link WorkClass#CONTINUITY} background work.
 */
public enum TaskKind {
    /** Free-text player declaration to one typed intention: a proposal only. */
    INTENT("intent", AiRole.INTERPRETER, 60),
    /** Public resolved-event bundle to attributed passages and quick replies. */
    NARRATION("narration", AiRole.NARRATOR, 90),
    /** Privileged world slice to a first versioned conditional quest plan: an artifact, never a command. */
    QUEST_PLAN("quest-plan", AiRole.QUEST_PLANNER, 60),
    /** Bounded refinement of an admitted plan whose branches went stale: replaces only those branches. */
    QUEST_PLAN_REFINE("quest-plan-refine", AiRole.QUEST_PLANNER, 60),
    /** Public events and the player's knowledge to a source-linked continuity summary for the player audience. */
    SUMMARY("summary", AiRole.SUMMARIZER, 60),
    /** Public events and the privileged record to a source-linked continuity summary for privileged tasks. */
    SUMMARY_PRIVILEGED("summary-privileged", AiRole.SUMMARIZER, 60);

    private final String wire;
    private final AiRole role;
    private final int budgetSeconds;

    /**
     * @param wire envelope {@code taskKind} value
     * @param role logical role; its envelope name is {@link AiRole#wire()}
     * @param budgetSeconds proposed default budget; for the planner kinds two 30-second background
     *        attempts (docs/28 section 9), so the single repair fits
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
     *         player action are what the player waits for, so both are interactive; quest planning
     *         prepares an accepted quest and is {@link WorkClass#PREPARATION} background work;
     *         summaries are bounded {@link WorkClass#CONTINUITY} work that later turns may use but
     *         never wait for
     */
    public WorkClass workClass() {
        return switch (role) {
            case QUEST_PLANNER -> WorkClass.PREPARATION;
            case SUMMARIZER -> WorkClass.CONTINUITY;
            default -> WorkClass.INTERACTIVE;
        };
    }

    /**
     * @return the disclosure audience: public for the P08.2 kinds and {@link #SUMMARY}, privileged
     *         for the planner kinds, whose context may hold hidden canon ({@link AiRole#QUEST_PLANNER}
     *         permits nothing else), and for {@link #SUMMARY_PRIVILEGED}
     */
    public Audience audience() {
        return role == AiRole.QUEST_PLANNER || this == SUMMARY_PRIVILEGED ? Audience.PRIVILEGED : Audience.PUBLIC;
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
