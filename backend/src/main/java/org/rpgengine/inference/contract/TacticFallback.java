package org.rpgengine.inference.contract;

import java.util.Objects;

import org.rpgengine.combat.TacticCandidates;
import org.rpgengine.combat.TacticalIntent;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Turns the outcome of one tactic-choice call into the decision of a non-player activation, with the deterministic fallback and
 * the player's interruption notice (P12.4-A, docs/11 section 12, docs/21 section 7). Pure: the same outcome and menu always give
 * the same decision.
 *
 * <p><b>Guarantee.</b> Every outcome yields a decision, so a failing runtime can never stall a fight. The choice of a runtime is
 * only ever a proposal: it becomes {@link Decision.Proposed} when, and only when, the menu read again from the committed state
 * still admits its intention. Everything else is a {@link Decision.Fallback}, which tells the caller to let the fixed
 * deterministic tactic of {@code CombatScheduler} play the activation: approach the nearest opponent when none is in reach, make
 * at most one strike at the first reachable opponent (nearest, then roster order), take the Guard action with what is left, and
 * otherwise end the activation. That tactic reads the committed state alone and draws nothing for its own decision, so it does not
 * depend on the runtime, its repair or its timing. A declared objective is not pursued yet: non-player participants carry none.
 *
 * <p><b>Causes.</b>
 * <ul>
 *   <li>{@link Cause#TACTIC_INVALID}: the output stayed invalid after the single repair, the runtime said it could not choose,
 *       the context was refused before submission, or the choice is no longer legal in the committed state (a stale choice is
 *       refused, never forced);</li>
 *   <li>{@link Cause#PROVIDER_UNAVAILABLE}: the task port did not complete an attempt (no runtime, failure, timeout, an
 *       unconfirmed result or an admission refusal), which is never repaired;</li>
 *   <li>{@link Cause#INTERRUPTED}: the call was cancelled, for example by shutdown.</li>
 * </ul>
 *
 * <p><b>Notice.</b> A fallback carries a short public sentence in the narrative language of the binding. It names no participant,
 * candidate, target, reason code or rejected text, so it exposes no hidden strategy, and the detail code stays out of it.
 */
public final class TacticFallback {
    /** Static entry point only. */
    private TacticFallback() {
    }

    /** Why a runtime choice was not used. */
    public enum Cause {
        /** The choice was invalid, absent, refused before submission or no longer legal. */
        TACTIC_INVALID("tactic-fallback.invalid"),
        /** The runtime did not complete the task. */
        PROVIDER_UNAVAILABLE("tactic-fallback.unavailable"),
        /** The call was cancelled before it completed. */
        INTERRUPTED("tactic-fallback.interrupted");

        private final String noticeCode;

        Cause(String noticeCode) {
            this.noticeCode = noticeCode;
        }

        /** @return the stable code of this cause's player notice */
        public String noticeCode() {
            return noticeCode;
        }
    }

    /**
     * The player-facing sentence of one fallback.
     *
     * @param code stable notice code of the cause
     * @param locale narrative language of the text
     * @param text public sentence; it names no participant, candidate or reason
     */
    public record Notice(String code, SupportedLocale locale, String text) {
        /** Require every field. */
        public Notice {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(locale, "locale");
            Objects.requireNonNull(text, "text");
        }
    }

    /** What one non-player activation does. */
    public sealed interface Decision {
        /**
         * The runtime's choice, still legal in the committed state.
         *
         * @param candidateId chosen candidate identity
         * @param intent the admitted intention
         * @param reasonCode optional diagnostic code, which authorizes nothing
         */
        record Proposed(String candidateId, TacticalIntent intent, String reasonCode) implements Decision {
            /** Require the candidate and the intention. */
            public Proposed {
                Objects.requireNonNull(candidateId, "candidateId");
                Objects.requireNonNull(intent, "intent");
            }
        }

        /**
         * The activation is played by the deterministic tactic.
         *
         * @param cause why the runtime's choice was not used
         * @param detail fixed diagnostic code for logs and receipts, never output text and never shown to the player
         * @param notice the public interruption message
         */
        record Fallback(Cause cause, String detail, Notice notice) implements Decision {
            /** Require every field. */
            public Fallback {
                Objects.requireNonNull(cause, "cause");
                Objects.requireNonNull(detail, "detail");
                Objects.requireNonNull(notice, "notice");
            }
        }
    }

    /**
     * Decide one activation.
     *
     * @param outcome the terminal outcome of the tactic call
     * @param committed the acting participant's menu read again from the committed encounter state
     * @return {@link Decision.Proposed} for an accepted choice the committed menu admits, otherwise a {@link Decision.Fallback}
     *         with the cause of the outcome
     */
    public static Decision decide(ContractOutcome<TacticProposal> outcome, TacticCandidates.Menu committed) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(committed, "committed");
        SupportedLocale locale = outcome.binding().outputLocale();
        return switch (outcome) {
            case ContractOutcome.Accepted<TacticProposal> accepted -> switch (accepted.value()) {
                case TacticProposal.Chosen chosen -> committed.admits(chosen.intent())
                        ? new Decision.Proposed(chosen.candidateId(), chosen.intent(), chosen.reasonCode())
                        : fallback(Cause.TACTIC_INVALID, "stale-choice", locale);
                case TacticProposal.CannotChoose none -> fallback(Cause.TACTIC_INVALID, none.reasonCode(), locale);
            };
            case ContractOutcome.Rejected<TacticProposal> rejected -> fallback(Cause.TACTIC_INVALID,
                    rejected.violations().isEmpty() ? "rejected" : rejected.violations().getFirst().code(), locale);
            case ContractOutcome.Refused<TacticProposal> refused -> fallback(Cause.TACTIC_INVALID, refused.code(), locale);
            case ContractOutcome.NotCompleted<TacticProposal> ended -> fallback(
                    ended.status() == org.rpgengine.inference.TaskResult.Status.CANCELLED ? Cause.INTERRUPTED
                            : Cause.PROVIDER_UNAVAILABLE, ended.errorCode(), locale);
        };
    }

    /** @return the fallback decision of a cause, with its notice in the given language */
    private static Decision.Fallback fallback(Cause cause, String detail, SupportedLocale locale) {
        return new Decision.Fallback(cause, detail, new Notice(cause.noticeCode(), locale, text(cause, locale)));
    }

    /** @return the public sentence of a cause; both languages say the same thing and name nothing of the choice */
    static String text(Cause cause, SupportedLocale locale) {
        boolean italian = locale == SupportedLocale.IT;
        return switch (cause) {
            case TACTIC_INVALID -> italian
                    ? "La scelta tattica non era utilizzabile: per questo turno il partecipante agisce con la tattica standard."
                    : "The tactic choice could not be used, so this participant acts by the standard tactic this turn.";
            case PROVIDER_UNAVAILABLE -> italian
                    ? "L'assistenza tattica non è disponibile: per questo turno il partecipante agisce con la tattica standard."
                    : "Tactic assistance is unavailable, so this participant acts by the standard tactic this turn.";
            case INTERRUPTED -> italian
                    ? "L'assistenza tattica è stata interrotta: per questo turno il partecipante agisce con la tattica standard."
                    : "Tactic assistance was interrupted, so this participant acts by the standard tactic this turn.";
        };
    }
}
