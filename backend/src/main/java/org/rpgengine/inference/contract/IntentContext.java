package org.rpgengine.inference.contract;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted input of one intent interpretation (docs/21 section 4): the acting character, the
 * player's submitted text and the options that character may currently address. Options are
 * already filtered to what the player knows; the contract only checks that a proposal stays
 * inside them. Current-state legality (reachability, costs, cooldowns) remains the job of
 * command rule validation after admission.
 *
 * @param binding identity and presentation generation
 * @param actorId the authorized acting character
 * @param playerText the exact submitted declaration, 1..{@link #MAX_PLAYER_TEXT} characters
 * @param allowedKinds intentions the current mode supports, at least one
 * @param destinations known reachable destination candidates
 * @param recipients known actors the player may address
 * @param abilities learned abilities with their permitted known targets
 * @param maxWaitSeconds longest wait the current state allows, 1..86,400
 * @param facts public facts the interpretation may cite
 * @param glossary selected-locale terminology for clarification text
 * @param guard privileged phrases the clarification must not reveal
 */
public record IntentContext(TaskBinding binding, UUID actorId, String playerText, Set<IntentKind> allowedKinds,
        List<Destination> destinations, List<KnownActor> recipients, List<AbilityOption> abilities, long maxWaitSeconds,
        List<PublicFact> facts, List<GlossaryTerm> glossary, DisclosureGuard guard) implements ContractContext {
    /** Longest player declaration; equal to the typed speech bound. */
    public static final int MAX_PLAYER_TEXT = 4000;
    /** Most candidates of each list. */
    public static final int MAX_OPTIONS = 32;
    /** Most cited facts. */
    public static final int MAX_FACTS = 64;
    /** Most glossary entries. */
    public static final int MAX_GLOSSARY = 128;

    /**
     * Freeze and bound every list.
     *
     * @throws IllegalArgumentException for empty kinds, oversized or duplicate options, or the
     *         acting character listed as its own recipient or target
     */
    public IntentContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(guard, "guard");
        ContextValues.text(playerText, "player text", MAX_PLAYER_TEXT);
        if (allowedKinds == null || allowedKinds.isEmpty()) {
            throw new IllegalArgumentException("at least one intention kind is required");
        }
        allowedKinds = Set.copyOf(EnumSet.copyOf(allowedKinds));
        destinations = ContextValues.distinct(destinations, "destinations", 0, MAX_OPTIONS, Destination::destinationId);
        recipients = ContextValues.distinct(recipients, "recipients", 0, MAX_OPTIONS, KnownActor::actorId);
        abilities = ContextValues.distinct(abilities, "abilities", 0, MAX_OPTIONS, AbilityOption::abilityId);
        facts = ContextValues.distinct(facts, "facts", 0, MAX_FACTS, PublicFact::id);
        glossary = ContextValues.distinct(glossary, "glossary", 0, MAX_GLOSSARY, GlossaryTerm::termId);
        if (maxWaitSeconds < 1 || maxWaitSeconds > 86_400) {
            throw new IllegalArgumentException("maximum wait must be 1..86400 seconds");
        }
        for (KnownActor recipient : recipients) {
            if (recipient.actorId().equals(actorId)) {
                throw new IllegalArgumentException("the acting character cannot address itself");
            }
        }
    }

    /**
     * A known destination.
     *
     * @param destinationId stable content reference of the destination
     * @param label known localized name
     */
    public record Destination(String destinationId, String label) {
        /** @throws IllegalArgumentException for a malformed reference or unbounded label */
        public Destination {
            ContextValues.reference(destinationId, "destination id");
            ContextValues.text(label, "destination label", KnownActor.MAX_NAME);
        }
    }

    /**
     * A learned ability and the known actors it may target.
     *
     * @param abilityId stable content reference of the ability
     * @param name known localized name
     * @param maxTargets most explicit targets, 0..8 (the typed command bound)
     * @param targets permitted known targets
     */
    public record AbilityOption(String abilityId, String name, int maxTargets, List<KnownActor> targets) {
        /** @throws IllegalArgumentException for malformed values or duplicate targets */
        public AbilityOption {
            ContextValues.reference(abilityId, "ability id");
            ContextValues.text(name, "ability name", KnownActor.MAX_NAME);
            if (maxTargets < 0 || maxTargets > 8) {
                throw new IllegalArgumentException("ability target count must be 0..8");
            }
            targets = ContextValues.distinct(targets, "targets", 0, MAX_OPTIONS, KnownActor::actorId);
        }
    }
}
