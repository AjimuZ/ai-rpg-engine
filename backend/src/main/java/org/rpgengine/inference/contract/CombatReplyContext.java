package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import org.rpgengine.combat.EncounterState;
import org.rpgengine.combat.TacticCandidates;
import org.rpgengine.inference.session.Audience;

/**
 * Trusted input of one combat-reply task (P12.4-B, docs/21 section 8, docs/11 section 7.1): what one addressed non-player
 * participant knows when it answers the player's words or a resolved social outcome.
 *
 * <p><b>Knowledge scope.</b> {@link #of} reads it from the committed encounter: the speaker's own public standing, the public
 * standing of every participant it can see ({@link TacticCandidates#perceives}), the words addressed to it, the public outcome
 * statements of the operation and, optionally, beliefs from that character's own knowledge view. It never holds anyone's
 * Health, Focus, defenses, attacks, abilities, effects, reaction policies, campaign actor, items, offers not yet made, check
 * difficulties or margins. The guard is completed from the same state: the roster ID of every participant the speaker cannot
 * see and the campaign actor of every other participant are markers, so a context or a reply naming one is refused.
 *
 * <p>Statements are supplied by the caller, already worded deterministically (for example by the narration package's event
 * statements), and must be ones the speaker witnessed; the contract never filters visibility of free text itself.
 *
 * @param binding identity, generation and locale of the task; the base revision is the committed revision the reply is about
 * @param subject stable reference of the speaking character, the subject of the task's character audience
 * @param speaker the replying participant's public standing
 * @param observed other participants the speaker can see, in roster order, at most {@link TacticCandidates#MAX_OBSERVED}
 * @param heard the player's words addressed to the speaker, or null when the reply answers an outcome only
 * @param outcome public statements of the resolved outcome the reply must not contradict, at most {@link #MAX_OUTCOME}
 * @param beliefs the character's own beliefs it may voice, at most {@link #MAX_BELIEFS}
 * @param glossary selected-locale terminology
 * @param guard phrases that must not reach this character or the player through it; never rendered
 */
public record CombatReplyContext(TaskBinding binding, String subject, Speaker speaker,
        List<TacticCandidates.Observed> observed, PublicFact heard, List<PublicFact> outcome, List<PublicFact> beliefs,
        List<GlossaryTerm> glossary, DisclosureGuard guard) implements ContractContext {
    /** Most outcome statements per reply. */
    public static final int MAX_OUTCOME = 8;
    /** Most beliefs per reply. */
    public static final int MAX_BELIEFS = 16;
    /** Longest display name of the speaker. */
    public static final int MAX_NAME = 120;

    /**
     * Public standing of the replying participant.
     *
     * @param participantId roster ID
     * @param name public display name the player already sees
     * @param opponent whether it opposes the player
     * @param zone its zone
     * @param status {@code ACTIVE} or {@code SURRENDERED}: a surrendered participant stays addressable by voice
     */
    public record Speaker(String participantId, String name, boolean opponent, String zone, EncounterState.Status status) {
        /** Require identities and a standing that can still speak. */
        public Speaker {
            ContextValues.reference(participantId, "speaker id");
            ContextValues.text(name, "speaker name", MAX_NAME);
            Objects.requireNonNull(zone, "zone");
            if (status != EncounterState.Status.ACTIVE && status != EncounterState.Status.SURRENDERED) {
                throw new IllegalArgumentException("only a standing or surrendered participant replies");
            }
        }
    }

    /**
     * Freeze and bound the context.
     *
     * @throws IllegalArgumentException for a subject that is not a stable reference, nothing to reply to, too many entries, or
     *         one fact identity used twice across heard words, outcome and beliefs
     */
    public CombatReplyContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(guard, "guard");
        Audience.character(subject);
        observed = List.copyOf(observed);
        if (observed.size() > TacticCandidates.MAX_OBSERVED) {
            throw new IllegalArgumentException("too many observed participants");
        }
        outcome = ContextValues.distinct(outcome, "outcome", 0, MAX_OUTCOME, PublicFact::id);
        beliefs = ContextValues.distinct(beliefs, "beliefs", 0, MAX_BELIEFS, PublicFact::id);
        glossary = List.copyOf(glossary);
        if (heard == null && outcome.isEmpty()) {
            throw new IllegalArgumentException("a reply answers heard words or a resolved outcome");
        }
        var ids = new HashSet<String>();
        for (PublicFact fact : facts(heard, outcome, beliefs)) {
            if (!ids.add(fact.id())) {
                throw new IllegalArgumentException("heard, outcome and belief identities must be distinct");
            }
        }
    }

    /**
     * Whether a participant can be asked for a reply.
     *
     * @param state the committed encounter
     * @param participantId roster ID of the addressed participant
     * @return true for a non-player participant that is standing or surrendered; a defeated or escaped one says nothing
     */
    public static boolean canReply(EncounterState state, String participantId) {
        var speaker = state.participant(participantId);
        return speaker != null && !speaker.player() && (speaker.status() == EncounterState.Status.ACTIVE
                || speaker.status() == EncounterState.Status.SURRENDERED);
    }

    /**
     * Read the knowledge-scoped context of one reply from the committed encounter.
     *
     * @param binding task binding at the committed revision
     * @param state the committed encounter
     * @param speakerId roster ID of the addressed participant; {@link #canReply} must hold
     * @param name the speaker's public display name
     * @param heard the player's words addressed to the speaker, or null
     * @param outcome public outcome statements the speaker witnessed
     * @param beliefs beliefs from the speaker's own knowledge view
     * @param glossary selected-locale terminology
     * @param secrets further phrases that must reach neither the speaker nor the player through the reply
     * @return the context, with the guard completed from the state
     * @throws IllegalArgumentException when the participant cannot reply or a value is out of bounds
     */
    public static CombatReplyContext of(TaskBinding binding, EncounterState state, String speakerId, String name,
            PublicFact heard, List<PublicFact> outcome, List<PublicFact> beliefs, List<GlossaryTerm> glossary,
            List<String> secrets) {
        if (!canReply(state, speakerId)) {
            throw new IllegalArgumentException("the addressed participant cannot reply");
        }
        var self = state.participant(speakerId);
        var observed = new ArrayList<TacticCandidates.Observed>();
        // Folded marker text to marker, so a secret repeated by the state is listed once.
        var markers = new LinkedHashMap<String, String>();
        secrets.forEach(secret -> markers.putIfAbsent(TextChecks.fold(secret), secret));
        for (var other : state.participants()) {
            if (other.id().equals(self.id())) {
                continue;
            }
            if (TacticCandidates.perceives(state.space(), self, other)) {
                observed.add(new TacticCandidates.Observed(other.id(), self.opposes(other), other.zone(), other.status(),
                        other.guarding()));
            } else if (TextChecks.fold(other.id()).length() >= DisclosureGuard.MIN_MARKER) {
                // A roster ID shorter than a marker would match ordinary words; it is still never rendered by this context.
                markers.putIfAbsent(TextChecks.fold(other.id()), other.id());
            }
            if (other.actorId() != null) {
                markers.putIfAbsent(TextChecks.fold(other.actorId().toString()), other.actorId().toString());
            }
        }
        var speaker = new Speaker(self.id(), name, self.opposes(state.player()), self.zone(), self.status());
        return new CombatReplyContext(binding, subject(state, speakerId), speaker, observed, heard, outcome, beliefs, glossary,
                new DisclosureGuard(List.copyOf(markers.values())));
    }

    /**
     * The stable character reference of a participant, the same rule as {@link TacticContext#subject}.
     *
     * @param state the encounter
     * @param participantId roster ID
     * @return {@code actor:<uuid>} for a participant portraying a campaign actor, otherwise
     *         {@code combatant:<encounter>:<rosterId>}
     */
    public static String subject(EncounterState state, String participantId) {
        var participant = Objects.requireNonNull(state.participant(participantId), "participant");
        return participant.actorId() != null ? "actor:" + participant.actorId()
                : "combatant:" + state.id() + ":" + participant.id();
    }

    /** @return every fact the reply may cite: the heard words, then the outcome, then the beliefs */
    public List<PublicFact> citable() {
        return facts(heard, outcome, beliefs);
    }

    /** @return heard words (when present), outcome and beliefs in that order */
    private static List<PublicFact> facts(PublicFact heard, List<PublicFact> outcome, List<PublicFact> beliefs) {
        var all = new ArrayList<PublicFact>();
        if (heard != null) {
            all.add(heard);
        }
        all.addAll(outcome);
        all.addAll(beliefs);
        return all;
    }
}
