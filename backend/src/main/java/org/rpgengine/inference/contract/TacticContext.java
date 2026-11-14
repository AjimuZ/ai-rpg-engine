package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;

import org.rpgengine.combat.TacticCandidates;
import org.rpgengine.inference.session.Audience;

/**
 * Trusted input of one tactic-choice task (P12.3, docs/21 section 7): the acting non-player participant's knowledge-scoped
 * menu and, optionally, beliefs that character holds.
 *
 * <p>The menu is built by {@link TacticCandidates#menu} and already holds only what the actor observes: its own values, the
 * public standing of the participants it can see, and its legal candidates. The beliefs must come from that character's own
 * knowledge view; the contract never filters visibility itself. The guard holds phrases the character must not know, such as
 * the identity of a participant hidden from it; one occurring in the rendered context refuses the task before submission.
 *
 * @param binding identity, generation and locale of the task; the base revision is the revision the menu was read at
 * @param menu the actor's observations and legal candidates
 * @param subject stable reference of the acting character, the subject of the task's character audience; see {@link #subject}
 * @param beliefs the character's own beliefs relevant to the choice, at most {@link #MAX_BELIEFS}
 * @param guard phrases that must not reach this character; never rendered
 */
public record TacticContext(TaskBinding binding, TacticCandidates.Menu menu, String subject, List<PublicFact> beliefs,
        DisclosureGuard guard) implements ContractContext {
    /** Most beliefs per task. */
    public static final int MAX_BELIEFS = 16;

    /**
     * Freeze and bound the context.
     *
     * @throws IllegalArgumentException for a subject that is not a stable reference, too many or duplicate beliefs, or a belief
     *         sharing an identity with a candidate
     */
    public TacticContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(guard, "guard");
        Audience.character(subject);
        beliefs = ContextValues.distinct(beliefs, "beliefs", 0, MAX_BELIEFS, PublicFact::id);
        for (PublicFact belief : beliefs) {
            if (menu.candidate(belief.id()) != null) {
                throw new IllegalArgumentException("belief and candidate identities must be distinct");
            }
        }
    }

    /**
     * Context of a menu without beliefs, in the acting participant's default subject.
     *
     * @param binding task binding
     * @param menu the actor's menu
     * @param guard phrases that must not reach the actor
     * @return the context
     */
    public static TacticContext of(TaskBinding binding, TacticCandidates.Menu menu, DisclosureGuard guard) {
        return new TacticContext(binding, menu, subject(menu), List.of(), guard);
    }

    /**
     * The stable character reference of a menu's actor.
     *
     * @param menu the actor's menu
     * @return {@code actor:<uuid>} for a participant portraying a campaign actor, so its sessions follow that character across
     *         fights; otherwise {@code combatant:<encounter>:<rosterId>}, a creature that exists only in this encounter
     */
    public static String subject(TacticCandidates.Menu menu) {
        return menu.campaignActor() != null ? "actor:" + menu.campaignActor()
                : "combatant:" + menu.encounterId() + ":" + menu.actorId();
    }

    /** @return no glossary: a tactic choice has no human-facing output text */
    @Override
    public List<GlossaryTerm> glossary() {
        return List.of();
    }
}
