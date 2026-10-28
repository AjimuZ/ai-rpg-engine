package org.rpgengine.memory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.contract.ContractCall;
import org.rpgengine.inference.contract.ContractOutcome;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.contract.DisclosureGuard;
import org.rpgengine.inference.contract.GlossaryTerm;
import org.rpgengine.inference.contract.PublicFact;
import org.rpgengine.inference.contract.SummaryContext;
import org.rpgengine.inference.contract.SummaryContract;
import org.rpgengine.inference.contract.SummaryProposal;
import org.rpgengine.inference.contract.TaskBinding;
import org.rpgengine.inference.contract.TaskKind;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.memory.ContextItem.Section;
import org.rpgengine.memory.ContextItem.Standing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one continuity-summary job (P09.3, docs/08 section 8, docs/21 section 10): claim, assemble
 * the audience's context with {@link ContextAssembler}, run the {@code summary} contract through the
 * fair scheduler as {@link org.rpgengine.inference.scheduling.WorkClass#CONTINUITY} background work
 * with one bounded repair, and record the accepted summary with its exact coverage and sources.
 *
 * <p><b>Context.</b> The envelope is assembled for the job's audience with the job's events as
 * explicit focus references and without any earlier summary, so a summary is never built from
 * another summary. Only a contiguous run of the requested events starting at the range's first
 * event is supplied: when the budget drops an event, the events after it are left for the next job,
 * so coverage never has a gap. Supplied sources are the audience's beliefs, common knowledge, open
 * obligations and, for the privileged audience, facts with truth; sightings and planning assumptions
 * are never summary input. A public summary carries the player's withheld phrases as its
 * disclosure guard.
 *
 * <p><b>Failure mapping.</b> Invalid output after the repair, a runtime that did not complete and a
 * source that changed before recording use the job's bounded attempts; a declined summary, a
 * context refused by its scope guard, an oversized context and a range without suppliable events
 * end the job. Nothing here is on the path of a player action, and no failure touches canonical
 * state.
 */
@Component
public class ContinuitySummarizer {
    /** Policy hash of summary tasks: the audience rules of this class, versioned by text. */
    public static final String POLICY_HASH = ContextEnvelope.sha256("memory-summary-policy-v1: audience-filtered "
            + "events, beliefs, common knowledge, obligations; facts only for privileged; no assumptions");
    /** Upper bound of the shared and task instructions plus the request envelope, in estimated tokens. */
    static final int INSTRUCTION_TOKENS = 1_500;
    /**
     * Summary input budget: the standard 12,000 input tokens, with most optional room given to the
     * events being summarized and to the knowledge that must survive in the summary.
     */
    static final ContextBudget BUDGET = new ContextBudget(ContextBudget.STANDARD_INPUT, ContextBudget.STANDARD_OUTPUT,
            ContextBudget.STANDARD_SAFETY, Map.of(Section.STATE, 500, Section.OBLIGATIONS, 1_500, Section.KNOWLEDGE,
                    2_500, Section.HISTORY, 5_000));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final MemorySummaries summaries;
    private final ContextAssembler assembler;
    private final ContractRunner runner;

    /** Spring constructor: the installation's fair scheduler on the system UTC clock. */
    @Autowired
    public ContinuitySummarizer(JdbcTemplate jdbc, MemorySummaries summaries, ContextAssembler assembler,
            InferenceScheduler scheduler) {
        this(jdbc, summaries, assembler, new ContractRunner(scheduler, Clock.systemUTC()));
    }

    /**
     * Explicit runner, for embedding and for suites that put a scripted port behind a real scheduler.
     *
     * @param runner contract runner over the fair scheduler
     */
    public ContinuitySummarizer(JdbcTemplate jdbc, MemorySummaries summaries, ContextAssembler assembler,
            ContractRunner runner) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Recover expired work, then claim and run one summary job to its recording or bounded failure.
     * Blocks for at most the contract budget plus {@link MemorySettings#WAIT_MARGIN}; call it outside
     * any transaction, from the memory worker.
     *
     * @param holder worker identity owning the lease
     * @return empty when summaries are off or nothing is due, otherwise what happened
     */
    public Optional<Processed> processNext(UUID holder) {
        Objects.requireNonNull(holder, "holder");
        if (!summaries.settings().summariesEnabled()) {
            return Optional.empty();
        }
        summaries.recover();
        var claimed = summaries.claim(holder);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        MemorySummaries.Lease lease = claimed.get();
        try {
            return Optional.of(run(lease));
        } catch (IllegalStateException lost) {
            // An expired or fenced lease leaves no authority: lease recovery decides what happens next.
            return Optional.of(new Processed(lease.jobId(), Processed.Outcome.LEASE_LOST, "LEASE_LOST", null));
        }
    }

    /** Assemble, run, validate and record one claimed attempt. */
    private Processed run(MemorySummaries.Lease lease) {
        UUID campaign = lease.campaignId();
        boolean privileged = lease.audience().equals("privileged");
        Audience audience = privileged ? Audience.PRIVILEGED : Audience.PUBLIC;
        var requested = jdbc.queryForList("select sequence from campaign_event where campaign_id = ? and visibility = "
                + "'PUBLIC' and sequence between ? and ? order by sequence limit ?", Long.class, campaign,
                lease.rangeFrom(), lease.rangeTo(), MemorySummaries.MAX_RANGE);
        if (requested.isEmpty()) {
            return failed(lease, Processed.Outcome.FAILED, "EMPTY_RANGE", false);
        }
        long invalidationMark = jdbc.queryForObject("select coalesce(max(sequence), 0) from memory_invalidation "
                + "where campaign_id = ?", Long.class, campaign);
        var assembled = assembler.assemble(new ContextAssembler.Request(lease.adventureId(), campaign, AiRole.SUMMARIZER,
                audience, null, requested.stream().map(sequence -> "event:" + sequence).toList(), null,
                INSTRUCTION_TOKENS, BUDGET, POLICY_HASH, false));
        if (assembled instanceof ContextAssembler.TooLarge) {
            return failed(lease, Processed.Outcome.FAILED, ContextAssembler.TOO_LARGE, false);
        }
        ContextEnvelope envelope = ((ContextAssembler.Assembled) assembled).envelope();
        ContextEnvelope.Stamp stamp = envelope.stamp();
        if (stamp.campaignGeneration() != lease.generation()) {
            return failed(lease, Processed.Outcome.FAILED, "GENERATION_RETIRED", false);
        }
        List<PublicFact> events = events(envelope, requested);
        if (events.isEmpty()) {
            return failed(lease, Processed.Outcome.FAILED, ContextAssembler.TOO_LARGE, false);
        }
        UUID taskId = UUID.nameUUIDFromBytes(("summary-task:" + lease.jobId() + ":" + lease.attempt())
                .getBytes(StandardCharsets.UTF_8));
        var binding = new TaskBinding(taskId, lease.adventureId(), campaign, stamp.campaignGeneration(),
                stamp.stateRevision(), stamp.locale(), stamp.localeRevision(), stamp.glossaryRevision(), stamp.packHash(),
                POLICY_HASH);
        var guard = privileged ? DisclosureGuard.none()
                : new DisclosureGuard(assembler.withheldPhrases(campaign, stamp.holderId()));
        List<GlossaryTerm> glossary = envelope.glossary().size() > SummaryContext.MAX_GLOSSARY
                ? envelope.glossary().subList(0, SummaryContext.MAX_GLOSSARY) : envelope.glossary();
        var context = new SummaryContext(binding, events, sources(envelope), entities(envelope), glossary, guard);
        var contract = new SummaryContract(privileged ? TaskKind.SUMMARY_PRIVILEGED : TaskKind.SUMMARY);
        ContractCall<SummaryProposal> call;
        try {
            call = runner.start(contract, context, summaries.settings().slot(), summaries.settings().budget());
        } catch (IllegalArgumentException oversized) {
            return failed(lease, Processed.Outcome.FAILED, ContextAssembler.TOO_LARGE, false);
        }
        ContractOutcome<SummaryProposal> outcome = await(call);
        return switch (outcome) {
            case ContractOutcome.Accepted<SummaryProposal> accepted -> switch (accepted.value()) {
                case SummaryProposal.Summarized summarized -> record(lease, contract, context, stamp, summarized,
                        invalidationMark);
                case SummaryProposal.CannotSummarize declined ->
                        failed(lease, Processed.Outcome.DECLINED, "SUMMARIZER_DECLINED", false);
            };
            case ContractOutcome.Rejected<SummaryProposal> rejected ->
                    failed(lease, Processed.Outcome.INVALID, "INVALID_SUMMARY", true);
            case ContractOutcome.Refused<SummaryProposal> refused ->
                    failed(lease, Processed.Outcome.FAILED, "CONTEXT_SCOPE", false);
            case ContractOutcome.NotCompleted<SummaryProposal> notCompleted ->
                    notCompleted.status() == TaskResult.Status.UNAVAILABLE
                            ? failed(lease, Processed.Outcome.UNAVAILABLE, "RUNTIME_UNAVAILABLE", true)
                            : failed(lease, Processed.Outcome.FAILED, "RUNTIME_FAILED", true);
        };
    }

    /** Store the accepted summary; a source that changed meanwhile releases the attempt for a retry. */
    private Processed record(MemorySummaries.Lease lease, SummaryContract contract, SummaryContext context,
            ContextEnvelope.Stamp stamp, SummaryProposal.Summarized summarized, long invalidationMark) {
        var body = new LinkedHashMap<String, Object>();
        body.put("summary", summarized.summary());
        body.put("obligations", notes(summarized.obligations()));
        body.put("entities", summarized.entities());
        body.put("uncertainties", notes(summarized.uncertainties()));
        var covered = summarized.coveredEvents().stream().map(ref -> Long.valueOf(ref.substring("event:".length())))
                .sorted().toList();
        var recording = new MemorySummaries.Recording(covered, context.sources().stream()
                .map(SummaryContext.Source::id).toList(), summarized.entities(), JSON.writeValueAsString(body),
                contract.render(context, List.of()).contextDigest(), contract.templateHash(), stamp.campaignGeneration(),
                stamp.stateRevision(), stamp.locale().tag(), stamp.localeRevision(), stamp.glossaryRevision(),
                invalidationMark);
        var recorded = summaries.record(lease, recording);
        if (recorded.summaryId() == null) {
            return failed(lease, Processed.Outcome.STALE, "STALE_SOURCE", true);
        }
        return new Processed(lease.jobId(), Processed.Outcome.RECORDED, null, recorded.summaryId());
    }

    /**
     * @return the supplied events: the selected event items of the requested run, in sequence order,
     *         stopping at the first requested event the budget dropped so coverage has no gap
     */
    private static List<PublicFact> events(ContextEnvelope envelope, List<Long> requested) {
        var selected = new HashMap<String, ContextItem>();
        envelope.items().get(Section.HISTORY).stream().filter(item -> item.standing() == Standing.EVENT)
                .forEach(item -> selected.put(item.ref(), item));
        var events = new ArrayList<PublicFact>();
        for (long sequence : requested) {
            ContextItem item = selected.get("event:" + sequence);
            if (item == null || events.size() == SummaryContext.MAX_EVENTS) {
                break;
            }
            Object statement = item.fields().get("statement");
            String text = statement instanceof String value ? value : String.valueOf(item.fields().get("type"));
            events.add(new PublicFact(item.ref(), cut(text)));
        }
        return events;
    }

    /**
     * @return the audience's knowledge as summary sources: open obligations first (they must survive),
     *         then beliefs, common knowledge and, for a privileged envelope, facts, at most
     *         {@link SummaryContext#MAX_SOURCES}
     */
    private static List<SummaryContext.Source> sources(ContextEnvelope envelope) {
        var statements = new HashMap<String, String>();
        envelope.all().stream().filter(item -> item.standing() == Standing.FACT)
                .forEach(item -> statements.put(item.ref(), claimText(item.fields())));
        var out = new ArrayList<SummaryContext.Source>();
        for (ContextItem item : envelope.items().get(Section.OBLIGATIONS)) {
            if (item.standing() == Standing.OBLIGATION && out.size() < SummaryContext.MAX_SOURCES) {
                out.add(new SummaryContext.Source(item.ref(), SummaryContext.Source.OBLIGATION,
                        cut(String.valueOf(item.fields().get("terms")))));
            }
        }
        for (ContextItem item : envelope.items().get(Section.KNOWLEDGE)) {
            if (out.size() == SummaryContext.MAX_SOURCES) {
                break;
            }
            switch (item.standing()) {
                case BELIEF -> {
                    String text;
                    if (item.fields().containsKey("holder")) {
                        // A privileged belief names its holder and claim; state it with the claim's text.
                        String claim = statements.get(String.valueOf(item.fields().get("claim")));
                        text = claim == null ? null : item.fields().get("holderName") + " ("
                                + item.fields().get("stance") + "): " + claim;
                    } else {
                        text = claimText(item.fields()) + " (" + item.fields().get("stance") + ")";
                    }
                    if (text != null) {
                        out.add(new SummaryContext.Source(item.ref(), SummaryContext.Source.BELIEF, cut(text)));
                    }
                }
                case COMMON_KNOWLEDGE -> out.add(new SummaryContext.Source(item.ref(), "common-knowledge",
                        cut(claimText(item.fields()))));
                case FACT -> out.add(new SummaryContext.Source(item.ref(), "fact",
                        cut(claimText(item.fields()) + " (" + item.fields().get("truth") + ")")));
                default -> {
                    // Sightings are current-state perspectives, not history to condense.
                }
            }
        }
        return out;
    }

    /** @return a claim's statement, or a compact rendering of its typed proposition */
    private static String claimText(Map<String, Object> fields) {
        if (fields.get("statement") instanceof String statement) {
            return statement;
        }
        Object subject = fields.get("subjectName") != null ? fields.get("subjectName") : fields.get("subject");
        var text = new StringBuilder(String.valueOf(subject)).append(' ').append(fields.get("predicate"));
        if (fields.get("qualifier") != null) {
            text.append(' ').append(fields.get("qualifier"));
        }
        if (fields.get("value") != null) {
            text.append(": ").append(fields.get("value") instanceof tools.jackson.databind.JsonNode node
                    ? (node.isString() ? node.asString() : node.toString()) : fields.get("value"));
        }
        if (fields.get("object") != null) {
            text.append(' ').append(fields.get("objectName") != null ? fields.get("objectName") : fields.get("object"));
        }
        return text.toString();
    }

    /**
     * @return the actors (with the names the audience knows) and locations (with their bound
     *         glossary rendering, else their identity) the selected items name, at most
     *         {@link SummaryContext#MAX_ENTITIES}
     */
    private static List<SummaryContext.Entity> entities(ContextEnvelope envelope) {
        var renderings = new HashMap<String, String>();
        envelope.glossary().forEach(term -> renderings.putIfAbsent(term.termId(), term.rendering()));
        var names = new LinkedHashMap<String, String>();
        for (ContextItem item : envelope.all()) {
            var fields = item.fields();
            if (item.standing() == Standing.STATE && item.ref().startsWith("actor:") && fields.get("name") != null) {
                names.putIfAbsent(item.ref(), String.valueOf(fields.get("name")));
            }
            for (String key : List.of("subject", "object", "debtor", "creditor", "holder")) {
                if (fields.get(key) instanceof String ref && ref.startsWith("actor:")
                        && fields.get(key + "Name") instanceof String name) {
                    names.putIfAbsent(ref, name);
                }
            }
            for (String key : List.of("location", "subject")) {
                if (fields.get(key) instanceof String ref && ref.startsWith("location:")) {
                    String id = ref.substring("location:".length());
                    names.putIfAbsent(ref, renderings.getOrDefault(id, id));
                }
            }
        }
        var out = new ArrayList<SummaryContext.Entity>();
        for (var entry : names.entrySet()) {
            if (out.size() == SummaryContext.MAX_ENTITIES) {
                break;
            }
            String name = entry.getValue().length() > 120 ? entry.getValue().substring(0, 120) : entry.getValue();
            out.add(new SummaryContext.Entity(entry.getKey(), name));
        }
        return out;
    }

    /** @return notes as ordered {@code ref}/{@code note} maps */
    private static List<Map<String, Object>> notes(List<SummaryProposal.Note> notes) {
        var out = new ArrayList<Map<String, Object>>();
        for (SummaryProposal.Note note : notes) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("ref", note.ref());
            entry.put("note", note.note());
            out.add(entry);
        }
        return out;
    }

    /** @return text cut to the contract's fact bound at a code-point boundary */
    private static String cut(String text) {
        int max = PublicFact.MAX_TEXT;
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    /** Wait for the contract outcome; an expired wait cancels the call and counts as not completed. */
    private ContractOutcome<SummaryProposal> await(ContractCall<SummaryProposal> call) {
        Duration wait = summaries.settings().budget().plus(MemorySettings.WAIT_MARGIN);
        try {
            return call.outcome().toCompletableFuture().get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            call.cancel();
        } catch (InterruptedException interrupted) {
            call.cancel();
            Thread.currentThread().interrupt();
        } catch (ExecutionException impossible) {
            // The outcome stage never completes exceptionally; treat a violation as a failed turn.
        }
        return new ContractOutcome.NotCompleted<>(TaskResult.Status.TIMED_OUT, "wait-expired", null, List.of());
    }

    /** Release the attempt with a failure code and report it. */
    private Processed failed(MemorySummaries.Lease lease, Processed.Outcome outcome, String code, boolean retryable) {
        summaries.fail(lease, code, retryable);
        return new Processed(lease.jobId(), outcome, code, null);
    }

    /**
     * Result of {@link #processNext}.
     *
     * @param jobId processed job
     * @param outcome what happened
     * @param code fixed failure code, null when recorded
     * @param summaryId stored summary, null otherwise
     */
    public record Processed(UUID jobId, Outcome outcome, String code, UUID summaryId) {
        /** Processing outcomes. */
        public enum Outcome {
            /** The summary passed validation and the source recheck and is stored. */
            RECORDED,
            /** A supplied source changed before recording; the attempt was released for a retry. */
            STALE,
            /** The summarizer answered cannot-complete. */
            DECLINED,
            /** The output stayed invalid after the single repair. */
            INVALID,
            /** No runtime was available. */
            UNAVAILABLE,
            /** Any other bounded failure. */
            FAILED,
            /** The lease was lost; recovery owns the job now. */
            LEASE_LOST
        }
    }
}
