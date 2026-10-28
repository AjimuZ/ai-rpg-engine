package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.inference.TaskResult;

/**
 * Terminal outcome of one contract call: at most two task attempts (the original and one repair).
 * Every variant keeps the binding, so a caller can check {@link TaskBinding#presentationCurrent}
 * before showing anything, and the attempt records, which carry provenance and fixed violation
 * codes but never output text.
 *
 * @param <T> typed proposal
 */
public sealed interface ContractOutcome<T> permits ContractOutcome.Accepted, ContractOutcome.Rejected,
        ContractOutcome.NotCompleted, ContractOutcome.Refused {
    /** @return binding the call was made for */
    TaskBinding binding();

    /** @return attempts in submission order; empty when nothing was submitted */
    List<Attempt> attempts();

    /**
     * A validated proposal.
     *
     * @param value accepted typed proposal
     * @param warnings non-blocking warning codes of the accepted attempt
     * @param binding task binding
     * @param attempts one or two attempts; two means the repair was accepted
     * @param <T> typed proposal
     */
    record Accepted<T>(T value, List<String> warnings, TaskBinding binding, List<Attempt> attempts)
            implements ContractOutcome<T> {
        /** Freeze the lists. */
        public Accepted {
            Objects.requireNonNull(value, "value");
            warnings = List.copyOf(warnings);
            attempts = List.copyOf(attempts);
        }

        /** @return true when the accepted output came from the single repair */
        public boolean repaired() {
            return attempts.size() > 1;
        }
    }

    /**
     * The output stayed invalid: after the single repair, or with no budget left for one. The
     * caller falls back deterministically (a receipt, a tactic default, a clarification prompt).
     *
     * @param violations violations of the last attempt
     * @param binding task binding
     * @param attempts attempts made
     * @param <T> typed proposal
     */
    record Rejected<T>(List<ContractViolation> violations, TaskBinding binding, List<Attempt> attempts)
            implements ContractOutcome<T> {
        /** Freeze the lists. */
        public Rejected {
            violations = List.copyOf(violations);
            attempts = List.copyOf(attempts);
        }
    }

    /**
     * The task port did not complete an attempt (unavailable, rejected, failed, timed out,
     * cancelled or unresolved). Repair is never attempted for these: they are not output defects.
     *
     * @param status terminal status of the last attempt
     * @param errorCode fixed error code of that attempt
     * @param binding task binding
     * @param attempts attempts made
     * @param <T> typed proposal
     */
    record NotCompleted<T>(TaskResult.Status status, String errorCode, TaskBinding binding, List<Attempt> attempts)
            implements ContractOutcome<T> {
        /** Freeze the attempts. */
        public NotCompleted {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(errorCode, "errorCode");
            attempts = List.copyOf(attempts);
        }
    }

    /**
     * Nothing was submitted because the context failed a pre-submission check.
     *
     * @param code fixed code, {@code context-scope} when the context contained a guard marker
     * @param binding task binding
     * @param <T> typed proposal
     */
    record Refused<T>(String code, TaskBinding binding) implements ContractOutcome<T> {
        /** @return always empty */
        @Override
        public List<Attempt> attempts() {
            return List.of();
        }
    }

    /**
     * One task attempt.
     *
     * @param portTaskId task identity used on the task port
     * @param repair true for the single repair attempt
     * @param status terminal task status
     * @param errorCode fixed error code, null when completed
     * @param violationCodes codes of the validation violations, empty when accepted or not completed
     * @param provenance runtime and effective settings, null when no turn ran
     * @param freshSession whether the runtime conversation was new for this attempt
     */
    record Attempt(UUID portTaskId, boolean repair, TaskResult.Status status, String errorCode,
            List<String> violationCodes, TaskResult.Provenance provenance, boolean freshSession) {
        /** Freeze the codes. */
        public Attempt {
            violationCodes = List.copyOf(violationCodes);
        }
    }
}
