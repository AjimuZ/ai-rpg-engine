package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.rpgengine.inference.session.Audience;

/**
 * Trusted input of one guide task (P15.3-A, docs/10 section 10, docs/21 section 9): what an authored guide persona may use to
 * answer one question of the player.
 *
 * <p><b>Knowledge scope.</b> The context holds only what the persona's own profile grants: its public name and senses, the
 * player's question, the facts and rule explanations the persona knows (its own senses and discoveries) and the legal actions
 * the player could submit. It never holds hidden canon, planner text, other characters' beliefs, a draft of the player's, a
 * campaign actor or any value that could be changed by an answer. The caller assembles the facts from the persona's own
 * knowledge view; the contract never filters visibility itself. The guard lists secrets the answer must not repeat, and a
 * context that already contains one is refused before submission.
 *
 * <p><b>Session scope.</b> The audience is {@code character:guide:<personaId>} (see {@link #subject}), run in the
 * {@code guide} role. A guide therefore shares no conversation with the public narrator, with any other persona, or with a
 * non-player character of the same name.
 *
 * @param binding identity, generation and locale of the task; the base revision is the committed revision the answer is about
 * @param subject stable reference of the persona, the subject of the task's character audience
 * @param persona the persona's public presentation
 * @param question the player's question, cited by identity
 * @param knowledge facts, rule explanations and discoveries the persona knows, at most {@link #MAX_KNOWLEDGE}
 * @param actions legal actions the persona may recommend, by identity and label, at most {@link #MAX_ACTIONS}
 * @param glossary selected-locale terminology
 * @param guard phrases that must not reach the player through the persona; never rendered
 */
public record GuideContext(TaskBinding binding, String subject, Persona persona, PublicFact question,
        List<PublicFact> knowledge, List<PublicFact> actions, List<GlossaryTerm> glossary, DisclosureGuard guard)
        implements ContractContext {
    /** Most knowledge facts per answer. */
    public static final int MAX_KNOWLEDGE = 32;
    /** Most recommendable actions per answer. */
    public static final int MAX_ACTIONS = 16;
    /** Longest persona name. */
    public static final int MAX_NAME = 120;
    /** Longest description of the persona's senses. */
    public static final int MAX_SENSES = 400;
    /** Prefix that keeps a persona's character audience apart from actors and combatants. */
    public static final String SUBJECT_PREFIX = "guide:";

    /**
     * Public presentation of a guide persona.
     *
     * @param name display name the player already sees
     * @param senses authored statement of what the persona can perceive, so it can say what it cannot know
     */
    public record Persona(String name, String senses) {
        /** Require bounded text. */
        public Persona {
            ContextValues.text(name, "persona name", MAX_NAME);
            ContextValues.text(senses, "persona senses", MAX_SENSES);
        }
    }

    /**
     * Freeze and bound the context.
     *
     * @throws IllegalArgumentException for a subject that is not a guide reference, too many entries, or one identity used
     *         twice across the question, the knowledge and the actions
     */
    public GuideContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(persona, "persona");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(guard, "guard");
        Audience.character(subject);
        if (!subject.startsWith(SUBJECT_PREFIX)) {
            throw new IllegalArgumentException("a guide audience is named guide:<persona>");
        }
        knowledge = ContextValues.distinct(knowledge, "knowledge", 0, MAX_KNOWLEDGE, PublicFact::id);
        actions = ContextValues.distinct(actions, "actions", 0, MAX_ACTIONS, PublicFact::id);
        glossary = List.copyOf(glossary);
        var ids = new HashSet<String>();
        for (PublicFact fact : everything(question, knowledge, actions)) {
            if (!ids.add(fact.id())) {
                throw new IllegalArgumentException("question, knowledge and action identities must be distinct");
            }
        }
    }

    /**
     * @param personaId stable reference of the persona as authored
     * @return the persona's subject, {@code guide:<personaId>}
     * @throws IllegalArgumentException for a malformed reference
     */
    public static String subject(String personaId) {
        return SUBJECT_PREFIX + ContextValues.reference(personaId, "persona id");
    }

    /** @return the facts an answer may cite: the question, then the knowledge; actions are suggested, never cited */
    public List<PublicFact> citable() {
        var all = new ArrayList<PublicFact>();
        all.add(question);
        all.addAll(knowledge);
        return all;
    }

    /** @return question, knowledge and actions in that order */
    private static List<PublicFact> everything(PublicFact question, List<PublicFact> knowledge, List<PublicFact> actions) {
        var all = new ArrayList<PublicFact>();
        all.add(question);
        all.addAll(knowledge);
        all.addAll(actions);
        return all;
    }
}
