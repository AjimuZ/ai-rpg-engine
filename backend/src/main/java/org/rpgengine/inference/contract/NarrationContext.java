package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted input of one narration task: the public resolved-event bundle of one committed operation
 * and the pending decision the narration must stop at (docs/21 section 8, docs/07 section 6).
 *
 * <p>Every list holds only what the player audience may know. The binding's base revision is the
 * revision the operation committed; the narration describes that state and nothing later.
 *
 * @param binding identity and presentation generation; {@code baseRevision} is the committed revision
 * @param operationId committed operation being narrated
 * @param playerActorId the player's character: never a narrated speaker, because player passages
 *        come from the player's own submission
 * @param events resolved public events, 1..{@link #MAX_EVENTS}; every one must be narrated
 * @param facts additional public facts the narration may cite
 * @param speakers known non-player actors that may speak
 * @param displayNumbers numbers the receipt permits prose to state, besides digits already present
 *        in supplied texts
 * @param pendingDecision the next player decision the narration must stop at
 * @param glossary selected-locale terminology
 * @param quotes protected verbatim quotes available to this narration
 * @param guard privileged phrases that must not appear
 */
public record NarrationContext(TaskBinding binding, UUID operationId, UUID playerActorId, List<PublicFact> events,
        List<PublicFact> facts, List<KnownActor> speakers, List<Long> displayNumbers, PendingDecision pendingDecision,
        List<GlossaryTerm> glossary, List<ProtectedQuote> quotes, DisclosureGuard guard) implements ContractContext {
    /** Most resolved events per narration. */
    public static final int MAX_EVENTS = 32;
    /** Most cited facts. */
    public static final int MAX_FACTS = 64;
    /** Most speakers. */
    public static final int MAX_SPEAKERS = 32;
    /** Most displayable numbers. */
    public static final int MAX_NUMBERS = 64;
    /** Most glossary entries. */
    public static final int MAX_GLOSSARY = 128;
    /** Most protected quotes. */
    public static final int MAX_QUOTES = 16;

    /**
     * Freeze and bound every list.
     *
     * @throws IllegalArgumentException for out-of-bound or duplicate entries, an event and a fact
     *         sharing an identity, or the player listed as a speaker
     */
    public NarrationContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerActorId, "playerActorId");
        Objects.requireNonNull(pendingDecision, "pendingDecision");
        Objects.requireNonNull(guard, "guard");
        events = ContextValues.distinct(events, "events", 1, MAX_EVENTS, PublicFact::id);
        facts = ContextValues.distinct(facts, "facts", 0, MAX_FACTS, PublicFact::id);
        speakers = ContextValues.distinct(speakers, "speakers", 0, MAX_SPEAKERS, KnownActor::actorId);
        displayNumbers = ContextValues.distinct(displayNumbers, "display numbers", 0, MAX_NUMBERS, n -> n);
        glossary = ContextValues.distinct(glossary, "glossary", 0, MAX_GLOSSARY, GlossaryTerm::termId);
        quotes = ContextValues.distinct(quotes, "quotes", 0, MAX_QUOTES, ProtectedQuote::quoteId);
        for (PublicFact fact : facts) {
            if (events.stream().anyMatch(e -> e.id().equals(fact.id()))) {
                throw new IllegalArgumentException("event and fact identities must be distinct");
            }
        }
        if (speakers.stream().anyMatch(s -> s.actorId().equals(playerActorId))) {
            throw new IllegalArgumentException("the player's character cannot be a narrated speaker");
        }
        for (long number : displayNumbers) {
            if (number < 0) {
                throw new IllegalArgumentException("display numbers are non-negative magnitudes");
            }
        }
    }

    /**
     * The next decision the player faces; narration stops here and suggestions refer to it.
     *
     * @param decisionId stable decision reference
     * @param prompt localized public description of the decision
     * @param actions available intentions an action suggestion may prepare, at most {@link #MAX_ACTIONS}
     */
    public record PendingDecision(String decisionId, String prompt, List<ActionOption> actions) {
        /** Most available actions. */
        public static final int MAX_ACTIONS = 16;

        /** @throws IllegalArgumentException for malformed or duplicate entries */
        public PendingDecision {
            ContextValues.reference(decisionId, "decision id");
            ContextValues.text(prompt, "decision prompt", PublicFact.MAX_TEXT);
            actions = ContextValues.distinct(actions, "actions", 0, MAX_ACTIONS, ActionOption::actionId);
        }
    }

    /**
     * An available intention the player may choose.
     *
     * @param actionId stable action reference
     * @param label localized label
     */
    public record ActionOption(String actionId, String label) {
        /** @throws IllegalArgumentException for a malformed reference or unbounded label */
        public ActionOption {
            ContextValues.reference(actionId, "action id");
            ContextValues.text(label, "action label", KnownActor.MAX_NAME);
        }
    }
}
