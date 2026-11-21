package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;

/**
 * Typed result of the guide contract (P15.3-A): advice text only. It holds no command, intention, draft, resource amount or
 * state patch, so nothing in it can be executed; a suggestion is the identity of an action the context already offered, and the
 * player still submits it through the ordinary flow.
 */
public sealed interface GuideProposal {
    /**
     * A validated answer.
     *
     * @param answer the persona's advice or explanation
     * @param references cited question and knowledge identities; empty only when {@code uncertain}
     * @param suggestions identities of legal actions the persona recommends, each one supplied by the context
     * @param uncertain whether the persona says its sources are incomplete
     */
    record Answered(String answer, List<String> references, List<String> suggestions, boolean uncertain)
            implements GuideProposal {
        /** Require the text and freeze the lists. */
        public Answered {
            Objects.requireNonNull(answer, "answer");
            references = List.copyOf(references);
            suggestions = List.copyOf(suggestions);
        }
    }

    /**
     * The runtime declined to answer within the rules.
     *
     * @param reasonCode {@code missing-information} or {@code out-of-scope}
     */
    record CannotAnswer(String reasonCode) implements GuideProposal {
        /** Require the reason. */
        public CannotAnswer {
            Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }
}
