package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;

/**
 * Typed result of the combat-reply contract (P12.4-B): presentation text only. Nothing in it can pay, move, agree, end a fight
 * or choose for the player; the speaker's identity is never part of it and comes from the trusted context.
 */
public sealed interface CombatReplyProposal {
    /**
     * A validated reply.
     *
     * @param speech the speaker's own words, or null when it only reacts
     * @param narration one short description of the speaker's visible reaction to the outcome, or null
     * @param references cited heard, outcome and belief identities; every outcome statement is among them
     */
    record Replied(String speech, String narration, List<String> references) implements CombatReplyProposal {
        /** Require at least one line and freeze the references. */
        public Replied {
            if (speech == null && narration == null) {
                throw new IllegalArgumentException("a reply has words or a reaction");
            }
            references = List.copyOf(references);
        }
    }

    /**
     * The runtime declined to reply within the rules.
     *
     * @param reasonCode {@code missing-information} or {@code out-of-scope}
     */
    record CannotReply(String reasonCode) implements CombatReplyProposal {
        /** Require the reason. */
        public CannotReply {
            Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }
}
