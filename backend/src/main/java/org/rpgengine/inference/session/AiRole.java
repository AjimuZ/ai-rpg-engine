package org.rpgengine.inference.session;

import java.util.EnumSet;
import java.util.Set;

/**
 * Logical AI roles of the engine (docs/06 section 6, docs/28 section 3) with the audience kinds
 * each may be scoped to. The role is part of a {@link SessionScope}, so two roles never share a
 * runtime conversation even when they share an audience.
 *
 * <p>Invariant, enforced by {@link SessionScope} and by a test over every constant: a
 * {@linkplain #playerFacing() player-facing} role never permits {@link Audience.Kind#PRIVILEGED}.
 * This is what makes "never reuse a privileged planner thread to render player narration"
 * structural: the narrator can only obtain a public session, and a planner session is never
 * public, so no scope (and therefore no session) can serve both.
 *
 * <p>Only {@link #INTERPRETER} and {@link #NARRATOR} have implemented contracts (P08.2). The
 * other roles fix their audience rules now so that later contracts (P08.7 planning, P12 tactics,
 * P15 guides, P09 summaries, P13 encounters) inherit the separation instead of inventing it.
 */
public enum AiRole {
    /** Free-text declaration to a typed intention; the clarification is shown to the player. */
    INTERPRETER("interpreter", true, EnumSet.of(Audience.Kind.PUBLIC)),
    /** Public resolved events to attributed narration and quick replies. */
    NARRATOR("narrator", true, EnumSet.of(Audience.Kind.PUBLIC)),
    /** Guide persona answering with its own senses and discoveries; shown to the player. */
    GUIDE("guide", true, EnumSet.of(Audience.Kind.CHARACTER)),
    /** One NPC or companion choosing among legal candidates from its own observations. */
    TACTICIAN("tactician", false, EnumSet.of(Audience.Kind.CHARACTER)),
    /** Memory summary; a summary has exactly the audience of its inputs (docs/08 section 8). */
    SUMMARIZER("summarizer", false, EnumSet.allOf(Audience.Kind.class)),
    /** Candidate world content from canon and author constraints. */
    CONTENT_PROPOSER("content-proposer", false, EnumSet.of(Audience.Kind.PRIVILEGED)),
    /** Candidate encounter roster, zones and tactics; hidden participants are secret. */
    ENCOUNTER_PROPOSER("encounter-proposer", false, EnumSet.of(Audience.Kind.PRIVILEGED)),
    /** Versioned quest-plan artifacts from an authorized world slice (docs/28 section 6). */
    QUEST_PLANNER("quest-planner", false, EnumSet.of(Audience.Kind.PRIVILEGED)),
    /** Occasional world-development proposals from faction and NPC goals. */
    WORLD_DIRECTOR("world-director", false, EnumSet.of(Audience.Kind.PRIVILEGED)),
    /** Optional review of one plan with the minimum permitted source context. */
    PLAN_REVIEWER("plan-reviewer", false, EnumSet.of(Audience.Kind.PRIVILEGED));

    private final String wire;
    private final boolean playerFacing;
    private final Set<Audience.Kind> audiences;

    /**
     * @param wire envelope role name
     * @param playerFacing whether the role's human-facing output reaches the player
     * @param audiences audience kinds the role may be scoped to
     */
    AiRole(String wire, boolean playerFacing, Set<Audience.Kind> audiences) {
        this.wire = wire;
        this.playerFacing = playerFacing;
        this.audiences = Set.copyOf(audiences);
    }

    /** @return the lowercase envelope role name */
    public String wire() {
        return wire;
    }

    /** @return true when the role's human-facing strings are shown to the player */
    public boolean playerFacing() {
        return playerFacing;
    }

    /**
     * @param audience candidate audience
     * @return true when this role may run in that audience
     */
    public boolean permits(Audience audience) {
        return audiences.contains(audience.kind());
    }
}
