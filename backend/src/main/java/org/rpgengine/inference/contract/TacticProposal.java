package org.rpgengine.inference.contract;

import java.util.Objects;

import org.rpgengine.combat.TacticalIntent;

/** Accepted result of a tactic-choice task (P12.3): one candidate choice or an explicit inability to choose. */
public sealed interface TacticProposal {
    /**
     * One supplied candidate with a permitted parameter. It is a proposal: the caller still revalidates it against a menu read
     * from the committed state ({@code TacticCandidates.Menu#admits}) before the scheduler performs it.
     *
     * @param candidateId the chosen candidate's identity
     * @param intent the choice as a tactical intention: {@link TacticalIntent.MoveZone}, {@link TacticalIntent.Strike},
     *        {@link TacticalIntent.Guard} or {@link TacticalIntent.EndActivation}
     * @param reasonCode optional diagnostic code from the bounded set; it authorizes nothing
     */
    record Chosen(String candidateId, TacticalIntent intent, String reasonCode) implements TacticProposal {
        /** Require the candidate and the intention. */
        public Chosen {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(intent, "intent");
        }
    }

    /**
     * The runtime could not choose; the caller uses its deterministic fallback.
     *
     * @param reasonCode fixed reason, {@code missing-information}
     */
    record CannotChoose(String reasonCode) implements TacticProposal {
        /** Require the reason. */
        public CannotChoose {
            Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }
}
