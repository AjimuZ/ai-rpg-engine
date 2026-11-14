package org.rpgengine.inference.contract;

import java.util.Objects;

import org.rpgengine.combat.EncounterOption;
import org.rpgengine.inference.TaskResult;

/**
 * Turns the outcome of one encounter-proposal call into the encounter to enter, with the deterministic safe fallback (P13.2-A,
 * docs/12 section 3 step 7). Pure: the same outcome and context always give the same decision.
 *
 * <p><b>Guarantee.</b> Every outcome yields a decision, and none depends on the runtime beyond an accepted composition. A
 * composition becomes {@link Decision.Proposed} only when the context read again before entry still offers its template and
 * its snapshot hash is unchanged, so the composition was checked against the same capabilities and budget. Otherwise the
 * decision is the first eligible authored template, unchanged, that passes the threat check ({@link EncounterOption#fallback}),
 * or {@link Decision.NoEncounter} when none does: the engine never invents an unavoidable fight to fill the gap.
 *
 * <p><b>Causes.</b>
 * <ul>
 *   <li>{@link Cause#PROPOSAL_INVALID}: the output stayed invalid after the single repair, the runtime said it could not
 *       compose, the context was refused before submission, or the composition is stale;</li>
 *   <li>{@link Cause#PROVIDER_UNAVAILABLE}: the task port did not complete an attempt, which is never repaired;</li>
 *   <li>{@link Cause#INTERRUPTED}: the call was cancelled.</li>
 * </ul>
 * No player notice is produced: an authored encounter is an ordinary encounter, and whether to mention that no encounter
 * started belongs to the caller.
 */
public final class EncounterFallback {
    /** Static entry point only. */
    private EncounterFallback() {
    }

    /** Why a runtime composition was not used. */
    public enum Cause {
        /** The composition was invalid, absent, refused before submission or stale. */
        PROPOSAL_INVALID,
        /** The runtime did not complete the task. */
        PROVIDER_UNAVAILABLE,
        /** The call was cancelled before it completed. */
        INTERRUPTED
    }

    /** Which encounter, if any, planning hands to entry. */
    public sealed interface Decision {
        /**
         * The runtime's composition, still valid for the context read before entry.
         *
         * @param composition the accepted composition
         */
        record Proposed(EncounterProposal.Composed composition) implements Decision {
            /** Require the composition. */
            public Proposed {
                Objects.requireNonNull(composition, "composition");
            }
        }

        /**
         * The first eligible authored template that fits the budget, unchanged.
         *
         * @param cause why the runtime's composition was not used
         * @param detail fixed diagnostic code for logs and receipts, never output text
         * @param authored the admitted authored template
         */
        record Fallback(Cause cause, String detail, EncounterOption.Admitted authored) implements Decision {
            /** Require every field. */
            public Fallback {
                Objects.requireNonNull(cause, "cause");
                Objects.requireNonNull(detail, "detail");
                Objects.requireNonNull(authored, "authored");
            }
        }

        /**
         * No eligible authored template fits the budget: no valid encounter exists and no fight starts.
         *
         * @param cause why the runtime's composition was not used
         * @param detail fixed diagnostic code for logs and receipts, never output text
         */
        record NoEncounter(Cause cause, String detail) implements Decision {
            /** Require every field. */
            public NoEncounter {
                Objects.requireNonNull(cause, "cause");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }

    /**
     * Decide the encounter of one planning call.
     *
     * @param outcome the terminal outcome of the encounter-proposal call
     * @param current the planning context read again from committed state before entry
     * @return {@link Decision.Proposed} for an accepted composition whose template is still offered at the same snapshot hash;
     *         otherwise {@link Decision.Fallback} with the first authored template that fits, or {@link Decision.NoEncounter}
     */
    public static Decision decide(ContractOutcome<EncounterProposal> outcome, EncounterContext current) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(current, "current");
        return switch (outcome) {
            case ContractOutcome.Accepted<EncounterProposal> accepted -> switch (accepted.value()) {
                case EncounterProposal.Composed composed -> {
                    if (!composed.snapshotHash().equals(current.snapshot().hash())) {
                        yield fallback(Cause.PROPOSAL_INVALID, "stale-snapshot", current);
                    }
                    if (!composed.option().equals(current.option(composed.option().template().id()))) {
                        yield fallback(Cause.PROPOSAL_INVALID, "stale-option", current);
                    }
                    yield new Decision.Proposed(composed);
                }
                case EncounterProposal.CannotCompose none -> fallback(Cause.PROPOSAL_INVALID, none.reasonCode(), current);
            };
            case ContractOutcome.Rejected<EncounterProposal> rejected -> fallback(Cause.PROPOSAL_INVALID,
                    rejected.violations().isEmpty() ? "rejected" : rejected.violations().getFirst().code(), current);
            case ContractOutcome.Refused<EncounterProposal> refused -> fallback(Cause.PROPOSAL_INVALID, refused.code(), current);
            case ContractOutcome.NotCompleted<EncounterProposal> ended -> fallback(
                    ended.status() == TaskResult.Status.CANCELLED ? Cause.INTERRUPTED : Cause.PROVIDER_UNAVAILABLE,
                    ended.errorCode(), current);
        };
    }

    /** @return the authored fallback of the current context, or no encounter when none fits */
    private static Decision fallback(Cause cause, String detail, EncounterContext current) {
        EncounterOption.Admitted authored = EncounterOption.fallback(current.options(), current.budget());
        return authored == null ? new Decision.NoEncounter(cause, detail) : new Decision.Fallback(cause, detail, authored);
    }
}
