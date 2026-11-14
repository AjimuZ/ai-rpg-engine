package org.rpgengine.inference.contract;

import java.util.Objects;

/**
 * Turns the outcome of one combat-reply call into what the player sees (P12.4-B, docs/11 section 7.1, docs/21 section 8). Pure:
 * the same outcome and revision always give the same decision.
 *
 * <p><b>Guarantee: dialogue failure changes nothing.</b> This decision takes no encounter, writes nothing and returns no
 * intention, so neither it nor the reply it presents can pay, move, answer an offer, advance the scheduler or end a fight. A
 * {@link Decision.Silent} only means that no generated line is shown: the committed events and their deterministic statements
 * stay what the transcript shows, the open offer stays open, and the player's legal actions are whatever the committed state
 * allows, exactly as without a runtime. A reply is shown only when the call accepted one and the campaign is still at the
 * revision the reply was grounded in, so a late reply never speaks over a newer state.
 *
 * <p><b>Causes of silence.</b>
 * <ul>
 *   <li>{@link Cause#REPLY_INVALID}: the output stayed invalid after the single repair, the runtime said it could not reply,
 *       or the context was refused before submission because it held a guard marker;</li>
 *   <li>{@link Cause#PROVIDER_UNAVAILABLE}: the task port did not complete an attempt (no runtime, failure, timeout, an
 *       unconfirmed result or an admission refusal), which is never repaired;</li>
 *   <li>{@link Cause#INTERRUPTED}: the call was cancelled, for example by shutdown;</li>
 *   <li>{@link Cause#STALE}: the campaign was committed again after the context was read.</li>
 * </ul>
 * No notice is shown for silence: the rule-driven sentences of the committed events already answer the player, so a missing
 * flourish does not change the experience (docs/11 section 12). The detail code is for logs only.
 */
public final class CombatReplyFallback {
    /** Static entry point only. */
    private CombatReplyFallback() {
    }

    /** Why no generated reply is shown. */
    public enum Cause {
        /** The reply was invalid, declined or refused before submission. */
        REPLY_INVALID,
        /** The runtime did not complete the task. */
        PROVIDER_UNAVAILABLE,
        /** The call was cancelled before it completed. */
        INTERRUPTED,
        /** The campaign was committed again after the revision the reply was grounded in. */
        STALE
    }

    /** What the player sees after one reply call. */
    public sealed interface Decision {
        /**
         * Show the validated reply, attributed by the caller to the speaker of the trusted context.
         *
         * @param reply the accepted reply
         */
        record Shown(CombatReplyProposal.Replied reply) implements Decision {
            /** Require the reply. */
            public Shown {
                Objects.requireNonNull(reply, "reply");
            }
        }

        /**
         * Show no generated line; everything committed stays as it is.
         *
         * @param cause why
         * @param detail fixed diagnostic code for logs, never output text and never shown to the player
         */
        record Silent(Cause cause, String detail) implements Decision {
            /** Require every field. */
            public Silent {
                Objects.requireNonNull(cause, "cause");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }

    /**
     * Decide what one reply call shows.
     *
     * @param outcome the terminal outcome of the reply call
     * @param committedRevision the campaign's committed state revision now, compared with the binding's base revision
     * @return {@link Decision.Shown} for an accepted reply still at its revision, otherwise {@link Decision.Silent}
     */
    public static Decision decide(ContractOutcome<CombatReplyProposal> outcome, long committedRevision) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome) {
            case ContractOutcome.Accepted<CombatReplyProposal> accepted -> switch (accepted.value()) {
                case CombatReplyProposal.Replied reply -> accepted.binding().baseRevision() == committedRevision
                        ? new Decision.Shown(reply) : new Decision.Silent(Cause.STALE, "stale-revision");
                case CombatReplyProposal.CannotReply none -> new Decision.Silent(Cause.REPLY_INVALID, none.reasonCode());
            };
            case ContractOutcome.Rejected<CombatReplyProposal> rejected -> new Decision.Silent(Cause.REPLY_INVALID,
                    rejected.violations().isEmpty() ? "rejected" : rejected.violations().getFirst().code());
            case ContractOutcome.Refused<CombatReplyProposal> refused -> new Decision.Silent(Cause.REPLY_INVALID,
                    refused.code());
            case ContractOutcome.NotCompleted<CombatReplyProposal> ended -> new Decision.Silent(
                    ended.status() == org.rpgengine.inference.TaskResult.Status.CANCELLED ? Cause.INTERRUPTED
                            : Cause.PROVIDER_UNAVAILABLE, ended.errorCode());
        };
    }
}
