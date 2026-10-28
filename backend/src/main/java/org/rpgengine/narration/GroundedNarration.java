package org.rpgengine.narration;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.contract.ContractCall;
import org.rpgengine.inference.contract.ContractOutcome;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.contract.KnownActor;
import org.rpgengine.inference.contract.NarrationContext;
import org.rpgengine.inference.contract.NarrationContract;
import org.rpgengine.inference.contract.NarrationProposal;
import org.rpgengine.inference.contract.TaskKind;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.NarrationPort;
import org.rpgengine.presentation.PresentationJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The engine's {@link NarrationPort} (P08.5): grounded narration of one committed operation
 * through the narration contract, validated before anything can be published.
 *
 * <p>One call: when the selected runtime reports itself disabled, unavailable or stopping, the
 * answer is {@link NarrationPort.Outcome#UNAVAILABLE} at once and nothing is read; otherwise
 * assemble the public context ({@link NarrationContextAssembler}); run the
 * narration contract with {@link ContractRunner} in the campaign's public narrator session on the
 * {@code standard} slot with the default 90-second narration budget, which includes the single
 * bounded repair; wait at most the budget plus {@link #WAIT_MARGIN}; map the terminal outcome:
 * <ul>
 *   <li>accepted narrated output becomes a {@link NarrationPort.Publication} (speaker names from
 *       the trusted context; passages and suggestions exactly as validated);</li>
 *   <li>an unnarratable context, the narrator's cannot-complete answer, output still invalid
 *       after the repair, and a context refused by the disclosure guard are
 *       {@link NarrationPort.Outcome#REJECTED}: the receipt is shown at once;</li>
 *   <li>an unavailable runtime is {@link NarrationPort.Outcome#UNAVAILABLE};</li>
 *   <li>any other transport outcome (failed, timed out, unresolved, cancelled, a stale session
 *       generation) and an expired wait are {@link NarrationPort.Outcome#FAILED}, which the job
 *       retries within its attempt budget.</li>
 * </ul>
 * Nothing is written here: the job's settling transaction alone decides publication, including
 * the final locale and glossary currency check. Raw output text, prompts and runtime diagnostics
 * never leave this class except as the validated publication.
 *
 * <p>The caller is the single presentation worker, so at most one narration turn runs at a time,
 * matching the serialized-inference default of P01.7 until P08.6 qualifies more.
 */
@Component
public class GroundedNarration implements NarrationPort {
    /** Wait beyond the contract budget for the port's own terminal result after its deadline. */
    static final Duration WAIT_MARGIN = Duration.ofSeconds(10);

    private final NarrationTaskPort port;
    private final NarrationContextAssembler assembler;
    private final ContractRunner runner;
    private final NarrationContract contract;
    private final Duration budget;

    /**
     * Spring constructor: the installation's scoped submitter and the default narration budget.
     *
     * @param port the installation's task port, read only for its readiness
     * @param assembler public context assembler
     * @param sessions scoped task submitter (P08.3) over the same port
     */
    @Autowired
    public GroundedNarration(NarrationTaskPort port, NarrationContextAssembler assembler, ScopedSessions sessions) {
        this(port, assembler, new ContractRunner(sessions, Clock.systemUTC()), new NarrationContract(),
                TaskKind.NARRATION.defaultBudget());
    }

    /**
     * @param port task port whose readiness is checked first; tasks go through {@code runner}
     * @param assembler public context assembler
     * @param runner contract runner over the scoped submitter
     * @param contract narration contract
     * @param budget total budget of both attempts; with {@link #WAIT_MARGIN} it must fit the job lease
     * @throws IllegalArgumentException when the budget is outside the task port range or the lease
     */
    GroundedNarration(NarrationTaskPort port, NarrationContextAssembler assembler, ContractRunner runner,
            NarrationContract contract, Duration budget) {
        this.port = Objects.requireNonNull(port, "port");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.contract = Objects.requireNonNull(contract, "contract");
        if (budget.compareTo(TaskRequest.MIN_TIMEOUT) < 0 || budget.compareTo(TaskRequest.MAX_TIMEOUT) > 0
                || budget.plus(WAIT_MARGIN).compareTo(Duration.ofSeconds(PresentationJobService.LEASE_SECONDS)) >= 0) {
            throw new IllegalArgumentException("narration budget must fit the task port range and the job lease");
        }
        this.budget = budget;
    }

    /** {@inheritDoc} */
    @Override
    public Result narrate(Request request) {
        switch (port.status().state()) {
            // STARTING queues tasks, so only states that end every task UNAVAILABLE short-circuit.
            case DISABLED, UNAVAILABLE, STOPPING, STOPPED -> {
                return Result.of(Outcome.UNAVAILABLE);
            }
            case STARTING, READY -> { }
        }
        NarrationContextAssembler.Assembly assembly = assembler.assemble(request);
        if (!(assembly instanceof NarrationContextAssembler.Assembly.Ready ready)) {
            return Result.of(Outcome.REJECTED);
        }
        NarrationContext context = ready.context();
        ContractCall<NarrationProposal> call = runner.start(contract, context, InferenceSlot.STANDARD, budget);
        ContractOutcome<NarrationProposal> outcome;
        try {
            outcome = call.outcome().toCompletableFuture().get(budget.plus(WAIT_MARGIN).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            call.cancel();
            return Result.of(Outcome.FAILED);
        } catch (InterruptedException interrupted) {
            call.cancel();
            Thread.currentThread().interrupt();
            return Result.of(Outcome.FAILED);
        } catch (ExecutionException impossible) {
            // The outcome stage never completes exceptionally; treat a violation as transient.
            return Result.of(Outcome.FAILED);
        }
        return switch (outcome) {
            case ContractOutcome.Accepted<NarrationProposal> accepted -> switch (accepted.value()) {
                case NarrationProposal.Narrated narrated -> Result.delivered(publication(context, narrated, accepted));
                case NarrationProposal.CannotNarrate declined -> Result.of(Outcome.REJECTED);
            };
            case ContractOutcome.Rejected<NarrationProposal> rejected -> Result.of(Outcome.REJECTED);
            case ContractOutcome.Refused<NarrationProposal> refused -> Result.of(Outcome.REJECTED);
            case ContractOutcome.NotCompleted<NarrationProposal> missing ->
                    Result.of(missing.status() == TaskResult.Status.UNAVAILABLE ? Outcome.UNAVAILABLE : Outcome.FAILED);
        };
    }

    /** Map validated output to the runtime-neutral publication; names come from the context. */
    private Publication publication(NarrationContext context, NarrationProposal.Narrated narrated,
            ContractOutcome.Accepted<NarrationProposal> accepted) {
        var passages = new ArrayList<Passage>();
        for (NarrationProposal.Passage passage : narrated.passages()) {
            boolean speech = passage.kind() == NarrationProposal.PassageKind.CHARACTER_SPEECH;
            UUID speaker = speech ? passage.speakerId() : null;
            String name = speech ? context.speakers().stream().filter(s -> s.actorId().equals(speaker))
                    .map(KnownActor::name).findFirst().orElseThrow() : null;
            passages.add(new Passage(speech ? PassageKind.CHARACTER_SPEECH : PassageKind.NARRATION, speaker, name,
                    passage.text(), passage.references()));
        }
        var suggestions = new ArrayList<Suggestion>();
        for (NarrationProposal.Suggestion suggestion : narrated.suggestions()) {
            suggestions.add(new Suggestion(SuggestionKind.valueOf(suggestion.kind().name()), suggestion.label(),
                    suggestion.draftText(), suggestion.actionId()));
        }
        List<ContractOutcome.Attempt> attempts = accepted.attempts();
        TaskResult.Provenance provenance = attempts.isEmpty() ? null : attempts.getLast().provenance();
        var binding = accepted.binding();
        return new Publication(binding.outputLocale(), binding.localeRevision(), binding.glossaryRevision(),
                contract.templateHash(), accepted.repaired(), passages, suggestions,
                provenance == null ? null : provenance.runtime(), provenance == null ? null : provenance.model(),
                provenance == null ? null : provenance.effort());
    }
}
