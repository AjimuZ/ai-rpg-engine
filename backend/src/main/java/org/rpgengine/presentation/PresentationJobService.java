package org.rpgengine.presentation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.rpgengine.commands.CampaignOperation;
import org.rpgengine.commands.MutationLeaseService;
import org.rpgengine.commands.OperationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable post-commit presentation jobs (P07.5, V11/V13 {@code presentation_job}) and the
 * publication of validated narration (P08.5, V13 {@code narration_publication}). A committed
 * operation that requires narration gets exactly one round-1 NARRATION job, inserted by the
 * canonical commit transaction, so the owed presentation can be neither lost by a crash nor
 * duplicated by a retry. Workers move it through the journal's presentation states without ever
 * touching world rows or calling the canonical commit service:
 *
 * <ol>
 *   <li><em>Claim</em> (one transaction, campaign then journal then job locks): QUEUED becomes
 *       RUNNING with one more attempt, a fresh fence and a {@value #LEASE_SECONDS}-second
 *       database-clock lease; the journal moves COMMITTED to NARRATING.</li>
 *   <li><em>Narrate</em> outside any transaction through {@link NarrationPort}.</li>
 *   <li><em>Settle</em> (one transaction, same locks): only the exact live lease holder may
 *       finish. DELIVERED stores the validated narration, provided the campaign's locale and
 *       glossary generation still equal the narration's, and ends the job and journal DELIVERED;
 *       stale narration is discarded and the attempt counts as failed. UNAVAILABLE, REJECTED
 *       (invalid after the single repair, or declined), a cancellation request or exhausted
 *       attempts end the job PENDING and the journal COMMITTED_NARRATION_PENDING: the stored
 *       player-safe receipt is then the deterministic presentation of the action. A transient
 *       failure with attempts left requeues the job with backoff and keeps the journal NARRATING.
 *       A late worker (lost lease, newer fence, settled job) writes nothing.</li>
 * </ol>
 *
 * <p>Narration-only retry ({@link #retry}): an operation whose journal is
 * COMMITTED_NARRATION_PENDING may get one more round at a time, at most {@value #MAX_ROUNDS}
 * rounds in total. A retry round runs exactly like round 1 but never changes the journal, whose
 * terminal status keeps recording that the action was saved without narration; a delivered retry
 * round only adds the publication. Rounds never re-run the action, re-roll draws, rewrite the
 * receipt or advance any canonical counter.
 *
 * <p>Expired leases are interrupted attempts: the next pass requeues them while attempts remain,
 * otherwise ends them PENDING. A job created under an older campaign recovery generation (a
 * restored save) is retired PENDING. Journal status changes emit stream messages through the V11
 * trigger, so each presentation outcome of round 1 is announced exactly once; retry rounds are
 * observed through the operation view.
 */
@Service
public class PresentationJobService {
    /** Attempts per job, counting interrupted attempts; matches the bounded retry policy of docs/07 §9. */
    public static final int MAX_ATTEMPTS = 3;
    /**
     * Lease of one attempt in whole seconds of database time. It covers the default 90-second
     * narration budget (docs/06 section 10) plus the publisher's bounded wait margin, so a live
     * attempt never loses its lease while the task port is still inside its budget.
     */
    public static final int LEASE_SECONDS = 120;
    /** Most narration rounds per operation: round 1 and up to three narration-only retries. */
    public static final int MAX_ROUNDS = 4;
    /** Upper bound of the retry backoff in seconds. */
    static final int MAX_BACKOFF_SECONDS = 30;
    private static final int PAGE = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final NarrationPort narration;
    private final TransactionTemplate transaction;
    private final TransactionTemplate reader;

    /**
     * Wire local persistence and the narration boundary; missing dependencies fail startup.
     *
     * @param entities JPA context used for journal transitions
     * @param jdbc JDBC access joined to the bounded transactions
     * @param manager shared transaction manager
     * @param narration narration boundary called outside transactions
     */
    public PresentationJobService(EntityManager entities, JdbcTemplate jdbc, PlatformTransactionManager manager,
            NarrationPort narration) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.narration = Objects.requireNonNull(narration, "narration");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        reader = new TransactionTemplate(manager);
        reader.setReadOnly(true);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reader.setTimeout(10);
    }

    /**
     * Insert the NARRATION job of a committing operation inside the canonical commit transaction,
     * which already holds the campaign and journal locks. The deferred V11 foreign key requires the
     * operation to be committed at exactly {@code committedRevision} when the transaction ends.
     *
     * @throws IllegalStateException if the campaign is not in the adventure's scope
     * @throws org.springframework.dao.DataAccessException for a duplicate job or storage failure
     */
    public static void enqueueWithin(JdbcTemplate jdbc, UUID adventure, UUID campaign, UUID operation,
            long committedRevision) {
        int inserted = jdbc.update("insert into presentation_job (id, adventure_id, campaign_id, campaign_generation, "
                        + "operation_id, committed_revision, kind, status, max_attempts, due_at, created_at, updated_at) "
                        + "select ?, c.adventure_id, c.id, c.recovery_generation, ?, ?, 'NARRATION', 'QUEUED', ?, "
                        + "clock_timestamp(), clock_timestamp(), clock_timestamp() from campaign c "
                        + "where c.id = ? and c.adventure_id = ?",
                UUID.randomUUID(), operation, committedRevision, MAX_ATTEMPTS, campaign, adventure);
        if (inserted != 1) {
            throw new IllegalStateException("presentation job scope mismatch");
        }
    }

    /**
     * Stop optional narration of a committed operation (docs/07 §8, "after canonical commit"),
     * inside a transaction that already holds the campaign and journal locks. A queued job ends
     * PENDING at once and the journal becomes COMMITTED_NARRATION_PENDING; a running job records
     * the request and its worker settles PENDING, discarding any narration. Committed effects are
     * never touched.
     *
     * @return what happened to the presentation work
     */
    public static Stop requestStopWithin(EntityManager entities, JdbcTemplate jdbc, UUID campaign, UUID operation) {
        // Every round is locked; the partial unique index guarantees at most one is in flight.
        List<String[]> rounds = jdbc.query("select id::text, status from presentation_job where campaign_id = ? "
                + "and operation_id = ? and kind = 'NARRATION' order by round for update",
                (row, index) -> new String[] {row.getString(1), row.getString(2)}, campaign, operation);
        if (rounds.isEmpty()) {
            return Stop.NONE;
        }
        String[] active = rounds.stream().filter(r -> r[1].equals("QUEUED") || r[1].equals("RUNNING"))
                .findFirst().orElse(null);
        if (active == null) {
            return Stop.SETTLED;
        }
        UUID id = UUID.fromString(active[0]);
        if (active[1].equals("QUEUED")) {
            jdbc.update("update presentation_job set status = 'PENDING', outcome_code = 'NARRATION_CANCELLED', "
                    + "cancel_requested_at = clock_timestamp(), updated_at = clock_timestamp(), "
                    + "row_version = row_version + 1 where id = ?", id);
            // A no-op for a retry round, whose journal is already COMMITTED_NARRATION_PENDING.
            settleJournal(entities, operation, OperationStatus.COMMITTED_NARRATION_PENDING);
            return Stop.STOPPED;
        }
        // Write-once: a repeated request keeps the first timestamp and writes nothing.
        jdbc.update("update presentation_job set cancel_requested_at = clock_timestamp(), "
                + "updated_at = clock_timestamp(), row_version = row_version + 1 where id = ? "
                + "and cancel_requested_at is null", id);
        return Stop.REQUESTED;
    }

    /**
     * Request a narration-only retry of one committed operation whose narration is pending: one
     * new QUEUED round under the campaign's current recovery generation, claimed by the worker like
     * any other job. Takes the campaign, journal and job locks in the shared order. The journal,
     * world rows, receipt and draws are never written; equal concurrent requests serialize on the
     * locks and the second one reports {@link Retry#IN_PROGRESS}.
     *
     * <p>The caller must already have authorized the owner for this exact adventure, campaign and
     * operation (for example through the operation status read).
     *
     * @return what the request did; only {@link Retry#QUEUED} wrote anything
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.EmptyResultDataAccessException for an out-of-scope identity
     * @throws org.springframework.dao.DataAccessException for lock timeouts or storage failures;
     *         nothing was written and repeating the request is safe
     */
    public Retry retry(UUID adventure, UUID campaign, UUID operation) {
        requireNoTransaction();
        return transaction.execute(status -> {
            MutationLeaseService.lockScope(jdbc, adventure, campaign, operation);
            CampaignOperation journal = entities.find(CampaignOperation.class, operation);
            switch (journal.getStatus()) {
                case COMMITTED, NARRATING -> {
                    return Retry.IN_PROGRESS;
                }
                case DELIVERED -> {
                    return Retry.ALREADY_NARRATED;
                }
                case COMMITTED_NARRATION_PENDING -> { }
                default -> {
                    return Retry.NOT_COMMITTED;
                }
            }
            if (published(jdbc, campaign, operation)) {
                return Retry.ALREADY_NARRATED;
            }
            List<String> rounds = jdbc.query("select status from presentation_job where campaign_id = ? "
                    + "and operation_id = ? and kind = 'NARRATION' order by round for update",
                    (row, index) -> row.getString(1), campaign, operation);
            if (rounds.contains("QUEUED") || rounds.contains("RUNNING")) {
                return Retry.IN_PROGRESS;
            }
            if (rounds.size() >= MAX_ROUNDS) {
                return Retry.EXHAUSTED;
            }
            // A legacy commit without any job gets round 1; the deferred foreign key re-checks the
            // committed revision at COMMIT exactly as for the commit-time job.
            jdbc.update("insert into presentation_job (id, adventure_id, campaign_id, campaign_generation, "
                            + "operation_id, committed_revision, kind, status, max_attempts, due_at, created_at, "
                            + "updated_at, round) select ?, c.adventure_id, c.id, c.recovery_generation, ?, ?, "
                            + "'NARRATION', 'QUEUED', ?, clock_timestamp(), clock_timestamp(), clock_timestamp(), ? "
                            + "from campaign c where c.id = ? and c.adventure_id = ?",
                    UUID.randomUUID(), operation, journal.getCommittedRevision(), MAX_ATTEMPTS, rounds.size() + 1,
                    campaign, adventure);
            return Retry.QUEUED;
        });
    }

    /**
     * Read the presentation state of one operation in one read-only snapshot: the journal status,
     * the stored publication if any, and whether a round is in flight or a retry is still allowed.
     * The caller must already have authorized the scope.
     *
     * @return presentation view; {@link Presentation#mode()} is null for an uncommitted operation
     * @throws IllegalStateException if called inside an ambient transaction or the stored
     *         publication is not valid JSON
     */
    public Presentation presentation(UUID campaign, UUID operation) {
        requireNoTransaction();
        return reader.execute(status -> {
            String journal = jdbc.queryForObject("select status from operation_journal where id = ? and campaign_id = ?",
                    String.class, operation, campaign);
            var rounds = jdbc.query("select status, outcome_code from presentation_job where campaign_id = ? "
                    + "and operation_id = ? and kind = 'NARRATION' order by round",
                    (row, index) -> new String[] {row.getString(1), row.getString(2)}, campaign, operation);
            var stored = jdbc.query("select passage_locale, repaired, passages::text, suggestions::text "
                    + "from narration_publication where campaign_id = ? and operation_id = ?",
                    (row, index) -> new String[] {row.getString(1), String.valueOf(row.getBoolean(2)), row.getString(3),
                            row.getString(4)}, campaign, operation);
            Map<String, Object> narration = stored.isEmpty() ? null : narrationView(operation, stored.getFirst());
            return Presentation.of(OperationStatus.valueOf(journal), rounds, narration);
        });
    }

    /**
     * Process at most {@code limit} jobs: interrupt expired attempts, then claim, narrate and
     * settle due jobs one at a time.
     *
     * @return number of passes that made progress
     * @throws IllegalStateException if called inside an ambient transaction
     */
    public int drain(int limit) {
        int processed = 0;
        while (processed < limit && runOnce()) {
            processed++;
        }
        return processed;
    }

    /**
     * Run one pass: interrupt expired attempts, then claim the oldest due job, call the
     * narration port outside any transaction and settle the result.
     *
     * @return true if a job was claimed or settled, false when nothing was due
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for lock timeouts or storage failures;
     *         the job then stays queued or its lease expires and is interrupted later
     */
    public boolean runOnce() {
        requireNoTransaction();
        boolean progressed = interruptExpired() > 0;
        Candidate candidate = transaction.execute(status -> {
            jdbc.execute("set local statement_timeout = '5s'");
            var due = jdbc.query("select id, adventure_id, campaign_id, operation_id from presentation_job "
                    + "where status = 'QUEUED' and due_at <= clock_timestamp() order by due_at, id limit 1",
                    (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class), row.getObject(4, UUID.class)));
            return due.isEmpty() ? null : due.getFirst();
        });
        if (candidate == null) {
            return progressed;
        }
        Claim claim = transaction.execute(status -> claim(candidate));
        if (claim == null) {
            // Settled without narration (retired, raced or nothing left to narrate).
            return true;
        }
        NarrationPort.Result outcome;
        try {
            outcome = Objects.requireNonNull(narration.narrate(claim.request()), "narration result");
        } catch (RuntimeException failure) {
            // Port failures are transient by contract; the bounded attempt budget decides the rest.
            outcome = NarrationPort.Result.of(NarrationPort.Outcome.FAILED);
        }
        NarrationPort.Result result = outcome;
        transaction.executeWithoutResult(status -> settle(claim, result));
        return true;
    }

    /**
     * Startup reconciliation before the web server and worker start: retire jobs of older
     * recovery generations and interrupt expired attempts. Queued jobs stay queued so missing
     * narration resumes after a restart; live leases are preserved. Keyset pages of 100, one
     * transaction per job; any failure aborts startup.
     *
     * @return number of jobs changed
     */
    public long reconcile() {
        requireNoTransaction();
        long changed = 0;
        Candidate after = null;
        while (true) {
            Candidate cursor = after;
            List<Candidate> page = transaction.execute(status -> {
                jdbc.execute("set local statement_timeout = '5s'");
                String query = "select id, adventure_id, campaign_id, operation_id from presentation_job "
                        + "where status in ('QUEUED', 'RUNNING') ";
                Object[] arguments = cursor == null ? new Object[0] : new Object[] {cursor.campaign(), cursor.job()};
                if (cursor != null) {
                    query += "and (campaign_id, id) > (?, ?) ";
                }
                return jdbc.query(query + "order by campaign_id, id limit " + PAGE,
                        (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                                row.getObject(3, UUID.class), row.getObject(4, UUID.class)), arguments);
            });
            if (page.isEmpty()) {
                return changed;
            }
            for (Candidate candidate : page) {
                if (Boolean.TRUE.equals(transaction.execute(status -> recover(candidate, true)))) {
                    changed++;
                }
                after = candidate;
            }
        }
    }

    /** Interrupt up to 16 expired attempts, one transaction each; returns how many changed. */
    private int interruptExpired() {
        List<Candidate> expired = transaction.execute(status -> {
            jdbc.execute("set local statement_timeout = '5s'");
            return jdbc.query("select id, adventure_id, campaign_id, operation_id from presentation_job "
                    + "where status = 'RUNNING' and lease_expires_at <= clock_timestamp() "
                    + "order by lease_expires_at, id limit 16",
                    (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class), row.getObject(4, UUID.class)));
        });
        int changed = 0;
        for (Candidate candidate : expired) {
            if (Boolean.TRUE.equals(transaction.execute(status -> recover(candidate, false)))) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Re-read one job under the shared locks and retire it if its generation is obsolete, or
     * interrupt it if its lease expired. With {@code startup} false only expiry is handled.
     */
    private boolean recover(Candidate candidate, boolean startup) {
        MutationLeaseService.lockScope(jdbc, candidate.adventure(), candidate.campaign(), candidate.operation());
        Job job = job(candidate.job());
        if (job.terminal()) {
            return false;
        }
        if (job.generation() != job.currentGeneration()) {
            if (!startup && !job.expired()) {
                return false;
            }
            finish(job, "PENDING", "GENERATION_RETIRED", OperationStatus.COMMITTED_NARRATION_PENDING);
            return true;
        }
        if (job.running() && job.expired()) {
            // The attempt was interrupted (crash, stall or lost lease): count it and retry if allowed.
            retryOrExhaust(job);
            return true;
        }
        return false;
    }

    /** Claim one due job under campaign, journal and job locks; null when settled without narration. */
    private Claim claim(Candidate candidate) {
        MutationLeaseService.lockScope(jdbc, candidate.adventure(), candidate.campaign(), candidate.operation());
        Job job = job(candidate.job());
        if (!job.queued() || !job.due()) {
            return null;
        }
        if (job.generation() != job.currentGeneration()) {
            finish(job, "PENDING", "GENERATION_RETIRED", OperationStatus.COMMITTED_NARRATION_PENDING);
            return null;
        }
        CampaignOperation journal = entities.find(CampaignOperation.class, job.operation());
        OperationStatus current = journal.getStatus();
        if (current != OperationStatus.COMMITTED && current != OperationStatus.NARRATING
                && current != OperationStatus.COMMITTED_NARRATION_PENDING) {
            // The journal was settled by another writer; there is nothing left to present.
            finish(job, "PENDING", "NARRATION_FAILED", null);
            return null;
        }
        if (current == OperationStatus.COMMITTED_NARRATION_PENDING && published(jdbc, job.campaign(), job.operation())) {
            // A retry round queued before an earlier round was published has nothing left to add.
            finish(job, "PENDING", "NARRATION_FAILED", null);
            return null;
        }
        UUID holder = UUID.randomUUID();
        var claimed = jdbc.queryForObject("update presentation_job set status = 'RUNNING', "
                        + "attempt_count = attempt_count + 1, fence = fence + 1, lease_holder = ?, "
                        + "lease_expires_at = clock_timestamp() + (? * interval '1 second'), "
                        + "updated_at = clock_timestamp(), row_version = row_version + 1 where id = ? "
                        + "returning fence, attempt_count",
                (row, index) -> new long[] {row.getLong(1), row.getLong(2)}, holder, LEASE_SECONDS, job.id());
        if (journal.getStatus() == OperationStatus.COMMITTED) {
            journal.transitionTo(OperationStatus.NARRATING, Instant.now());
            entities.flush();
        }
        return new Claim(job.id(), candidate, holder, claimed[0], new NarrationPort.Request(candidate.adventure(),
                candidate.campaign(), job.operation(), job.revision(), (int) claimed[1], job.id(), job.generation()));
    }

    /**
     * Settle one attempt if this worker still holds the exact live lease; otherwise discard the
     * result without writing anything.
     */
    private void settle(Claim claim, NarrationPort.Result result) {
        var c = claim.candidate();
        MutationLeaseService.lockScope(jdbc, c.adventure(), c.campaign(), c.operation());
        Job job = job(claim.job());
        if (!job.running() || !claim.holder().equals(job.holder()) || job.fence() != claim.fence() || job.expired()) {
            return;
        }
        if (job.generation() != job.currentGeneration()) {
            finish(job, "PENDING", "GENERATION_RETIRED", OperationStatus.COMMITTED_NARRATION_PENDING);
        } else if (job.cancelRequested()) {
            // Cancellation wins over a narration produced meanwhile; that narration is discarded.
            finish(job, "PENDING", "NARRATION_CANCELLED", OperationStatus.COMMITTED_NARRATION_PENDING);
        } else {
            switch (result.outcome()) {
                case DELIVERED -> deliver(job, result.publication());
                case UNAVAILABLE -> finish(job, "PENDING", "NARRATION_UNAVAILABLE",
                        OperationStatus.COMMITTED_NARRATION_PENDING);
                // The single repair already happened inside the attempt: show the receipt instead.
                case REJECTED -> finish(job, "PENDING", "NARRATION_FAILED", OperationStatus.COMMITTED_NARRATION_PENDING);
                case FAILED -> retryOrExhaust(job);
            }
        }
    }

    /**
     * Publish validated narration under the settle locks. The campaign's presentation settings are
     * re-read here, after narration, because a locale switch or glossary edit may have happened
     * while the port ran: narration bound to an older generation must never be shown (docs/29
     * section 5), so it is discarded and the attempt counts as a failure, which lets a later
     * attempt narrate in the current locale. Round 1 moves the journal to DELIVERED; a retry round
     * leaves the terminal COMMITTED_NARRATION_PENDING journal unchanged.
     */
    private void deliver(Job job, NarrationPort.Publication publication) {
        var settings = jdbc.queryForObject("select narrative_locale, locale_revision, glossary_revision "
                        + "from presentation_campaign_settings where campaign_id = ? for share",
                (row, index) -> new Object[] {row.getString(1), row.getLong(2), row.getLong(3)}, job.campaign());
        boolean current = publication.locale().tag().equals(settings[0])
                && publication.localeRevision() == (long) settings[1]
                && publication.glossaryRevision() == (long) settings[2];
        if (!current) {
            retryOrExhaust(job);
            return;
        }
        jdbc.update("insert into narration_publication (campaign_id, operation_id, adventure_id, job_id, "
                        + "committed_revision, campaign_generation, passage_locale, locale_revision, glossary_revision, "
                        + "contract_version, template_hash, repaired, passages, suggestions, runtime, model, effort, "
                        + "published_at) select j.campaign_id, j.operation_id, j.adventure_id, j.id, j.committed_revision, "
                        + "j.campaign_generation, ?, ?, ?, 1, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, clock_timestamp() "
                        + "from presentation_job j where j.id = ?",
                publication.locale().tag(), publication.localeRevision(), publication.glossaryRevision(),
                publication.templateHash(), publication.repaired(), JSON.writeValueAsString(passages(publication)),
                JSON.writeValueAsString(suggestions(publication)), publication.runtime(), publication.model(),
                publication.effort(), job.id());
        CampaignOperation journal = entities.find(CampaignOperation.class, job.operation());
        finish(job, "DELIVERED", "NARRATION_DELIVERED",
                journal.getStatus() == OperationStatus.NARRATING ? OperationStatus.DELIVERED : null);
    }

    /** Stored passage array: kind, speaker identity and name from the trusted context, text, references. */
    private static List<Map<String, Object>> passages(NarrationPort.Publication publication) {
        var stored = new ArrayList<Map<String, Object>>();
        for (NarrationPort.Passage passage : publication.passages()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("kind", passage.kind().name());
            entry.put("speakerActorId", passage.speakerActorId() == null ? null : passage.speakerActorId().toString());
            entry.put("speakerName", passage.speakerName());
            entry.put("text", passage.text());
            entry.put("references", passage.references());
            stored.add(entry);
        }
        return stored;
    }

    /** Stored suggestion array. */
    private static List<Map<String, Object>> suggestions(NarrationPort.Publication publication) {
        var stored = new ArrayList<Map<String, Object>>();
        for (NarrationPort.Suggestion suggestion : publication.suggestions()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("kind", suggestion.kind().name());
            entry.put("label", suggestion.label());
            entry.put("draftText", suggestion.draftText());
            entry.put("actionId", suggestion.actionId());
            stored.add(entry);
        }
        return stored;
    }

    /**
     * Player view of a stored publication. Message identities are assigned here from the operation
     * and the passage order, never by the model; references and provenance stay internal.
     */
    private static Map<String, Object> narrationView(UUID operation, String[] stored) {
        var view = new LinkedHashMap<String, Object>();
        view.put("locale", stored[0]);
        view.put("repaired", Boolean.parseBoolean(stored[1]));
        var passages = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (var passage : JSON.readTree(stored[2])) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("messageId", operation + ":p" + (++index));
            entry.put("kind", passage.path("kind").asString());
            if (passage.path("speakerActorId").isString()) {
                var speaker = new LinkedHashMap<String, Object>();
                speaker.put("actorId", passage.path("speakerActorId").asString());
                speaker.put("name", passage.path("speakerName").asString());
                entry.put("speaker", speaker);
            } else {
                entry.put("speaker", null);
            }
            entry.put("text", passage.path("text").asString());
            entry.put("locale", stored[0]);
            passages.add(entry);
        }
        view.put("passages", List.copyOf(passages));
        var suggestions = new ArrayList<Map<String, Object>>();
        index = 0;
        for (var suggestion : JSON.readTree(stored[3])) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("suggestionId", operation + ":s" + (++index));
            entry.put("kind", suggestion.path("kind").asString());
            entry.put("label", suggestion.path("label").asString());
            entry.put("draftText", suggestion.path("draftText").asString());
            entry.put("actionId", suggestion.path("actionId").isString() ? suggestion.path("actionId").asString() : null);
            suggestions.add(entry);
        }
        view.put("suggestions", List.copyOf(suggestions));
        return view;
    }

    /** @return true when the operation already has published narration */
    private static boolean published(JdbcTemplate jdbc, UUID campaign, UUID operation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from narration_publication "
                + "where campaign_id = ? and operation_id = ?)", Boolean.class, campaign, operation));
    }

    /** Requeue an ended attempt with exponential backoff while attempts remain, else end it PENDING. */
    private void retryOrExhaust(Job job) {
        if (job.cancelRequested()) {
            finish(job, "PENDING", "NARRATION_CANCELLED", OperationStatus.COMMITTED_NARRATION_PENDING);
        } else if (job.attempts() < job.maxAttempts()) {
            long backoff = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(job.attempts(), 5));
            jdbc.update("update presentation_job set status = 'QUEUED', lease_holder = null, lease_expires_at = null, "
                    + "fence = fence + 1, due_at = clock_timestamp() + (? * interval '1 second'), "
                    + "updated_at = clock_timestamp(), row_version = row_version + 1 where id = ?", backoff, job.id());
        } else {
            finish(job, "PENDING", "NARRATION_FAILED", OperationStatus.COMMITTED_NARRATION_PENDING);
        }
    }

    /**
     * End a job with a terminal status and outcome, releasing its lease with one fence step if it
     * was running, then move the journal to {@code journalStatus} (null keeps the journal as is).
     */
    private void finish(Job job, String status, String outcome, OperationStatus journalStatus) {
        jdbc.update("update presentation_job set status = ?, outcome_code = ?, lease_holder = null, "
                + "lease_expires_at = null, fence = fence + ?, updated_at = clock_timestamp(), "
                + "row_version = row_version + 1 where id = ?", status, outcome, job.running() ? 1 : 0, job.id());
        if (journalStatus != null) {
            settleJournal(entities, job.operation(), journalStatus);
        }
    }

    /**
     * Move the locked journal row to a presentation outcome unless it is already there. An illegal
     * transition (for example a journal settled by another writer) throws and rolls back.
     */
    private static void settleJournal(EntityManager entities, UUID operation, OperationStatus target) {
        CampaignOperation journal = entities.find(CampaignOperation.class, operation);
        if (journal.getStatus() != target) {
            journal.transitionTo(target, Instant.now());
            entities.flush();
        }
    }

    /** Read one job row, plus the campaign's current generation and database-clock predicates. */
    private Job job(UUID id) {
        return jdbc.queryForObject("select j.id, j.operation_id, j.committed_revision, j.status, j.lease_holder, j.fence, "
                        + "j.attempt_count, j.max_attempts, j.campaign_generation, c.recovery_generation, "
                        + "coalesce(j.lease_expires_at <= clock_timestamp(), false), j.due_at <= clock_timestamp(), "
                        + "j.cancel_requested_at is not null, j.campaign_id from presentation_job j "
                        + "join campaign c on c.id = j.campaign_id where j.id = ? for update of j",
                (row, index) -> new Job(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getLong(3),
                        row.getString(4), row.getObject(5, UUID.class), row.getLong(6), row.getInt(7), row.getInt(8),
                        row.getLong(9), row.getLong(10), row.getBoolean(11), row.getBoolean(12), row.getBoolean(13),
                        row.getObject(14, UUID.class)),
                id);
    }

    /** Reject joined caller work: a claim or settlement must be durable before anyone acts on it. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("presentation jobs require no ambient transaction");
        }
    }

    /** Outcome of {@link #requestStopWithin}. */
    public enum Stop {
        /** The operation has no presentation job (legacy commit or narration not required). */
        NONE,
        /** A queued job ended PENDING; the journal is COMMITTED_NARRATION_PENDING. */
        STOPPED,
        /** A running job recorded the request; its worker settles PENDING. */
        REQUESTED,
        /** The job had already ended. */
        SETTLED
    }

    /** Outcome of {@link #retry}. */
    public enum Retry {
        /** A new round was queued; the worker narrates it like any other job. */
        QUEUED,
        /** A round of this operation is queued or running, or its first narration is still owed. */
        IN_PROGRESS,
        /** Narration of this operation is already published. */
        ALREADY_NARRATED,
        /** Every allowed round was used; the receipt remains the presentation. */
        EXHAUSTED,
        /** The operation never committed, so there is no narration to retry. */
        NOT_COMMITTED
    }

    /** How a committed operation is presented to the player. */
    public enum Mode {
        /** Narration is being produced (round 1 before settlement, or a queued/running retry). */
        IN_PROGRESS,
        /** Validated narration is published. */
        NARRATED,
        /** Deterministic fallback: the stored player-safe receipt is the presentation. */
        RECEIPT
    }

    /**
     * Presentation state of one operation.
     *
     * @param mode presentation mode, null when the operation never committed
     * @param reason public reason of the receipt fallback; null unless {@link Mode#RECEIPT}, and null
     *        for a legacy delivery that stored no text
     * @param retriesRemaining narration-only retry rounds still allowed (0 unless {@link Mode#RECEIPT})
     * @param narration published player view, null unless {@link Mode#NARRATED}
     */
    public record Presentation(Mode mode, String reason, int retriesRemaining, Map<String, Object> narration) {
        /** Reasons the public contract names (docs/13 section 4); anything else reads as a failure. */
        static final List<String> REASONS = List.of("NARRATION_UNAVAILABLE", "NARRATION_CANCELLED", "NARRATION_FAILED",
                "GENERATION_RETIRED");

        /**
         * Derive the view from the journal status, the rounds in order and the publication.
         * Publication wins over every job state; an active round means in progress; otherwise a
         * committed operation without narration falls back to its receipt, naming the reason of
         * its latest round ({@code NARRATION_INTERRUPTED} for legacy commits without any round).
         * Public since P09.5 so the resume card derives the same view inside its own snapshot.
         *
         * @param journal the operation's journal status
         * @param rounds NARRATION rounds in order, each {@code {status, outcome_code}}
         * @param narration the published player view, or null when nothing is published
         * @return the presentation view
         */
        public static Presentation of(OperationStatus journal, List<String[]> rounds, Map<String, Object> narration) {
            if (!journal.isCommitted()) {
                return new Presentation(null, null, 0, null);
            }
            if (narration != null) {
                return new Presentation(Mode.NARRATED, null, 0, narration);
            }
            if (journal == OperationStatus.DELIVERED) {
                // Delivered before publications were stored (P07.5 test doubles): no text to show.
                return new Presentation(Mode.RECEIPT, null, 0, null);
            }
            boolean active = rounds.stream().anyMatch(r -> r[0].equals("QUEUED") || r[0].equals("RUNNING"));
            if (active || journal == OperationStatus.COMMITTED || journal == OperationStatus.NARRATING) {
                return new Presentation(Mode.IN_PROGRESS, null, 0, null);
            }
            String latest = rounds.isEmpty() ? null : rounds.getLast()[1];
            String reason = latest == null ? "NARRATION_INTERRUPTED" : REASONS.contains(latest) ? latest : "NARRATION_FAILED";
            return new Presentation(Mode.RECEIPT, reason, journal == OperationStatus.COMMITTED_NARRATION_PENDING
                    ? Math.max(0, MAX_ROUNDS - rounds.size()) : 0, null);
        }
    }

    /** Scoped job identity read without locks; every step re-reads it under locks. */
    private record Candidate(UUID job, UUID adventure, UUID campaign, UUID operation) {
    }

    /** Detached claim held by one worker between its claim and settle transactions. */
    private record Claim(UUID job, Candidate candidate, UUID holder, long fence, NarrationPort.Request request) {
    }

    /** Locked job row with its campaign's current generation and clock predicates. */
    private record Job(UUID id, UUID operation, long revision, String status, UUID holder, long fence, int attempts,
            int maxAttempts, long generation, long currentGeneration, boolean expired, boolean due,
            boolean cancelRequested, UUID campaign) {
        boolean queued() {
            return status.equals("QUEUED");
        }

        boolean running() {
            return status.equals("RUNNING");
        }

        boolean terminal() {
            return !queued() && !running();
        }
    }
}
