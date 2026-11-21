package org.rpgengine.inference.contract;

import java.util.Objects;

/**
 * Turns the outcome of one guide call into what the player sees (P15.3-A, docs/10 section 10). Pure: the same outcome and
 * revision always give the same decision.
 *
 * <p><b>Guarantee: guide failure changes nothing.</b> The decision takes no campaign state, writes nothing and returns no
 * intention, so neither it nor the answer it presents can spend, move, complete or execute anything. {@link Decision.Silent}
 * only means that no generated answer is shown: the sheet, the journal and the legal actions are whatever the committed state
 * allows, exactly as without a runtime. An answer is shown only when the campaign is still at the revision it was grounded in,
 * so a late answer never advises on a newer state.
 *
 * <p>Causes of silence are the same as for a combat reply: {@link Cause#ANSWER_INVALID} (invalid after the single repair,
 * declined, or refused before submission because the context held a guard marker), {@link Cause#PROVIDER_UNAVAILABLE},
 * {@link Cause#INTERRUPTED} and {@link Cause#STALE}. The detail code is for logs only.
 */
public final class GuideFallback {
    /** Static entry point only. */
    private GuideFallback() {
    }

    /** Why no generated answer is shown. */
    public enum Cause {
        /** The answer was invalid, declined or refused before submission. */
        ANSWER_INVALID,
        /** The runtime did not complete the task. */
        PROVIDER_UNAVAILABLE,
        /** The call was cancelled before it completed. */
        INTERRUPTED,
        /** The campaign was committed again after the revision the answer was grounded in. */
        STALE
    }

    /** What the player sees after one guide call. */
    public sealed interface Decision {
        /**
         * Show the validated answer, attributed by the caller to the persona of the trusted context.
         *
         * @param answer the accepted answer
         */
        record Shown(GuideProposal.Answered answer) implements Decision {
            /** Require the answer. */
            public Shown {
                Objects.requireNonNull(answer, "answer");
            }
        }

        /**
         * Show no generated answer; everything committed stays as it is.
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
     * Decide what one guide call shows.
     *
     * @param outcome the terminal outcome of the guide call
     * @param committedRevision the campaign's committed state revision now, compared with the binding's base revision
     * @return {@link Decision.Shown} for an accepted answer still at its revision, otherwise {@link Decision.Silent}
     */
    public static Decision decide(ContractOutcome<GuideProposal> outcome, long committedRevision) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome) {
            case ContractOutcome.Accepted<GuideProposal> accepted -> switch (accepted.value()) {
                case GuideProposal.Answered answer -> accepted.binding().baseRevision() == committedRevision
                        ? new Decision.Shown(answer) : new Decision.Silent(Cause.STALE, "stale-revision");
                case GuideProposal.CannotAnswer none -> new Decision.Silent(Cause.ANSWER_INVALID, none.reasonCode());
            };
            case ContractOutcome.Rejected<GuideProposal> rejected -> new Decision.Silent(Cause.ANSWER_INVALID,
                    rejected.violations().isEmpty() ? "rejected" : rejected.violations().getFirst().code());
            case ContractOutcome.Refused<GuideProposal> refused -> new Decision.Silent(Cause.ANSWER_INVALID, refused.code());
            case ContractOutcome.NotCompleted<GuideProposal> ended -> new Decision.Silent(
                    ended.status() == org.rpgengine.inference.TaskResult.Status.CANCELLED ? Cause.INTERRUPTED
                            : Cause.PROVIDER_UNAVAILABLE, ended.errorCode());
        };
    }
}
