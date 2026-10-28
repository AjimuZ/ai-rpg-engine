package org.rpgengine.inference.contract;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;

/**
 * Runs one contract task through the runtime-neutral port with validation and exactly one bounded
 * repair (docs/07 section 9, docs/21 section 8), in the session of the task's own scope (P08.3).
 *
 * <p>Session scoping: the caller never names a session. The runner derives the
 * {@link SessionScope} from the contract's kind (role and audience) and the context's binding
 * (adventure, campaign, generation, pack and policy hashes) and submits through
 * {@link ScopedSessions}, so an interpreter and a narrator, two campaigns, or a public and a
 * privileged task can never share a runtime conversation, and a privileged planner session can
 * never be passed in to render narration. Both attempts of a call use the same scope.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Render the context; a guard marker in it ends the call {@link ContractOutcome.Refused}
 *       without submitting anything.</li>
 *   <li>Submit attempt 1 with the binding's task identity and the whole budget.</li>
 *   <li>A non-completed task status ends the call {@link ContractOutcome.NotCompleted}: transport
 *       failures, unavailability, timeouts and scope refusals of {@link ScopedSessions} (a stale
 *       campaign generation) are not output defects and are never repaired.</li>
 *   <li>A completed output is validated. Acceptance ends the call. Violations start the single
 *       repair: the same canonical context, the same session scope, a derived task identity and
 *       only the remaining budget, with the violation codes and pointers (never the rejected text)
 *       appended. A remaining budget below the port minimum ends the call
 *       {@link ContractOutcome.Rejected} instead.</li>
 *   <li>The repair's outcome is final: accepted, rejected, or not completed.</li>
 * </ol>
 * A valid but disallowed proposal is a gameplay rejection for the caller, not a reason to ask
 * again; this runner never loops beyond one repair.
 *
 * <p>Thread-safety: one runner serves concurrent calls. Continuations run on whichever thread
 * completes the port's result stage; they only validate in memory and submit, which never blocks.
 */
public final class ContractRunner {
    private final ScopedSessions sessions;
    private final Clock clock;

    /**
     * @param sessions the installation's scoped submitter of the task port
     * @param clock clock for the shared attempt budget
     */
    public ContractRunner(ScopedSessions sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Start one contract call.
     *
     * @param contract task contract
     * @param context trusted context; its binding and the contract's kind determine the session scope
     * @param slot logical inference slot
     * @param budget total budget for both attempts, {@link TaskRequest#MIN_TIMEOUT}..{@link TaskRequest#MAX_TIMEOUT}
     * @return running call
     * @throws IllegalArgumentException for an out-of-range budget or oversized instructions
     */
    public <C extends ContractContext, T> ContractCall<T> start(TaskContract<C, T> contract, C context,
            InferenceSlot slot, Duration budget) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(slot, "slot");
        if (budget.compareTo(TaskRequest.MIN_TIMEOUT) < 0 || budget.compareTo(TaskRequest.MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("budget outside the supported range");
        }
        SessionScope scope = contract.kind().scope(context.binding());
        var call = new Call<>(contract, context, scope, slot, clock.instant().plus(budget));
        TaskContract.RenderedTask first;
        try {
            first = contract.render(context, List.of());
        } catch (ContextScopeException refused) {
            call.promise.complete(new ContractOutcome.Refused<>("context-scope", context.binding()));
            return call;
        }
        call.submit(context.binding().taskId(), first, budget, false);
        return call;
    }

    /** @return the derived, stable task identity of the repair attempt of {@code taskId} */
    static UUID repairTaskId(UUID taskId) {
        return UUID.nameUUIDFromBytes(("contract-repair:" + taskId).getBytes(StandardCharsets.UTF_8));
    }

    /** State of one call; all mutation happens under its monitor. */
    private final class Call<C extends ContractContext, T> implements ContractCall<T> {
        private final TaskContract<C, T> contract;
        private final C context;
        private final SessionScope scope;
        private final InferenceSlot slot;
        private final Instant deadline;
        private final CompletableFuture<ContractOutcome<T>> promise = new CompletableFuture<>();
        private final List<ContractOutcome.Attempt> attempts = new ArrayList<>();
        private TaskHandle current;
        private boolean cancelled;

        Call(TaskContract<C, T> contract, C context, SessionScope scope, InferenceSlot slot, Instant deadline) {
            this.contract = contract;
            this.context = context;
            this.scope = scope;
            this.slot = slot;
            this.deadline = deadline;
        }

        @Override
        public CompletionStage<ContractOutcome<T>> outcome() {
            return promise.minimalCompletionStage();
        }

        @Override
        public boolean cancel() {
            TaskHandle handle;
            synchronized (this) {
                if (promise.isDone()) {
                    return false;
                }
                cancelled = true;
                handle = current;
            }
            if (handle != null) {
                handle.cancel();
            }
            return true;
        }

        /** Submit one attempt and continue when its result arrives. */
        void submit(UUID portTaskId, TaskContract.RenderedTask rendered, Duration timeout, boolean repair) {
            TaskHandle handle = sessions.submit(scope, portTaskId, slot, rendered.instructions(),
                    contract.outputSchema(), timeout);
            boolean cancelNow;
            synchronized (this) {
                current = handle;
                // A cancel that raced with this submission still reaches the new attempt.
                cancelNow = cancelled;
            }
            if (cancelNow) {
                handle.cancel();
            }
            handle.result().whenComplete((result, failure) -> {
                try {
                    if (failure != null || result == null) {
                        // The port contract forbids this; treat it as an unconfirmed outcome.
                        finish(new ContractOutcome.NotCompleted<>(TaskResult.Status.UNRESOLVED, "port-contract",
                                context.binding(), record(portTaskId, repair, null, List.of())));
                    } else {
                        handle(portTaskId, repair, result);
                    }
                } catch (RuntimeException unexpected) {
                    finish(new ContractOutcome.NotCompleted<>(TaskResult.Status.FAILED, "contract-error",
                            context.binding(), List.copyOf(attempts)));
                }
            });
        }

        /** Validate a terminal result and decide between acceptance, repair and fallback. */
        private void handle(UUID portTaskId, boolean repair, TaskResult result) {
            if (result.status() != TaskResult.Status.COMPLETED) {
                finish(new ContractOutcome.NotCompleted<>(result.status(), result.errorCode(), context.binding(),
                        record(portTaskId, repair, result, List.of())));
                return;
            }
            Validation<T> validation = contract.validate(context, result.output());
            List<String> codes = validation.violations().stream().map(ContractViolation::code).toList();
            List<ContractOutcome.Attempt> made = record(portTaskId, repair, result, codes);
            if (validation.accepted()) {
                finish(new ContractOutcome.Accepted<>(validation.value(), validation.warnings(), context.binding(), made));
                return;
            }
            Duration remaining = Duration.between(clock.instant(), deadline);
            boolean stop;
            synchronized (this) {
                stop = repair || cancelled;
            }
            if (stop && !repair) {
                finish(new ContractOutcome.NotCompleted<>(TaskResult.Status.CANCELLED, "cancelled", context.binding(), made));
                return;
            }
            if (stop || remaining.compareTo(TaskRequest.MIN_TIMEOUT) < 0) {
                finish(new ContractOutcome.Rejected<>(validation.violations(), context.binding(), made));
                return;
            }
            TaskContract.RenderedTask rendered = contract.render(context, validation.violations());
            submit(repairTaskId(context.binding().taskId()), rendered, remaining, true);
        }

        /** Append one attempt and return a snapshot of all attempts. */
        private synchronized List<ContractOutcome.Attempt> record(UUID portTaskId, boolean repair, TaskResult result,
                List<String> codes) {
            attempts.add(new ContractOutcome.Attempt(portTaskId, repair,
                    result == null ? TaskResult.Status.UNRESOLVED : result.status(),
                    result == null ? "port-contract" : result.errorCode(), codes,
                    result == null ? null : result.provenance(), result != null && result.freshSession()));
            return List.copyOf(attempts);
        }

        /** Complete the call once; later completions are ignored. */
        private void finish(ContractOutcome<T> outcome) {
            promise.complete(outcome);
        }
    }
}
