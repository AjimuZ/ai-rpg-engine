package org.rpgengine.memory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.inference.session.Audience;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Source-linked continuity summaries, their bounded background jobs, freshness and explicit
 * invalidation (P09.3, docs/08 section 8, docs/21 section 10, ADR 0005).
 *
 * <p><b>What a summary is.</b> A derived, lossy retrieval aid for exactly one audience
 * ({@code public} or {@code privileged}; character audiences have no history yet). It records the
 * exact set of event sequences it covers, every knowledge record supplied to it with that record's
 * row version, the entities it names, the revision, generation, locale and glossary revisions it was
 * assembled under, and the digest of its context. It never becomes canonical state and never
 * replaces structured current state during context assembly.
 *
 * <p><b>Freshness</b> ({@link #freshness}, {@link #usableWithin}) is computed, never stored:
 * <ul>
 *   <li>{@code STALE} when the campaign's recovery generation changed (restore), the output locale
 *       or its locale or glossary revision changed, a supplied knowledge record changed since
 *       (belief or claim supersession, truth determination, commitment resolution: each steps
 *       the record's row version), or a later {@code memory_invalidation} names one of its
 *       events, sources or entities, or the whole campaign. A stale summary is never assembled
 *       into a context.</li>
 *   <li>{@code BEHIND} when it is otherwise valid but newer public events exist; its coverage stays
 *       precise and the assembler adds the recent events beside it.</li>
 *   <li>{@code CURRENT} otherwise.</li>
 * </ul>
 * Broad query-scope epochs are not used for freshness: a sighting epoch steps on almost every
 * action, which would make every summary stale for no reason (ADR 0014: revalidate the relevant
 * dependencies instead of everything).
 *
 * <p><b>Jobs</b> are bounded: one unfinished job per campaign and audience (database index), at
 * most {@link MemorySettings#maxPending()} unfinished jobs installation-wide (checked under an
 * advisory lock), at most 32 events per job, {@link MemorySettings#maxAttempts()} fenced attempts,
 * a finite claim window, and no automatic retry of a range whose job already failed. A job is
 * requested after {@link MemorySettings#interval()} significant actions or at a scene boundary.
 * A failed job never invalidates a committed action and never blocks a command; context assembly
 * then uses the recent-event window and the operational checkpoint.
 *
 * <p><b>Ordering.</b> Recording a summary and writing an invalidation take the same per-campaign
 * advisory transaction lock, so invalidation sequences become visible in order per campaign and a
 * summary's invalidation high-water mark never skips a concurrent invalidation.
 */
@Service
public class MemorySummaries {
    /** Most events one summary job covers; equal to the contract bound. */
    public static final int MAX_RANGE = 32;
    /** Most invalidation references per call. */
    public static final int MAX_INVALIDATION_REFS = 64;
    /** Newest summaries examined when looking for a usable one. */
    static final int USABLE_SCAN = 8;
    private static final Set<String> AUDIENCES = Set.of("public", "privileged");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MemorySettings settings;
    private final Clock clock;

    /** Spring constructor: {@code engine.memory.*} and the system UTC clock. */
    @Autowired
    public MemorySummaries(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment environment) {
        this(jdbc, manager, MemorySettings.from(environment), Clock.systemUTC());
    }

    /**
     * @param jdbc storage
     * @param manager transaction manager
     * @param settings validated memory settings
     * @param clock lease, window and backoff time source
     */
    public MemorySummaries(JdbcTemplate jdbc, PlatformTransactionManager manager, MemorySettings settings, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        this.transactions.setTimeout(15);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return the validated settings */
    public MemorySettings settings() {
        return settings;
    }

    /**
     * Request a continuity summary of an audience's uncovered public events when the policy says
     * one is due. Never waits for inference and never writes canonical state.
     *
     * @param campaign campaign identity
     * @param audience {@link Audience#PUBLIC} or {@link Audience#PRIVILEGED}
     * @param force request even when fewer than {@link MemorySettings#interval()} significant
     *        actions and no scene boundary are uncovered
     * @return DISABLED (summaries off, nothing written), PENDING (an unfinished job exists),
     *         UP_TO_DATE (no uncovered public event), NOT_DUE, GAVE_UP (a job for exactly this range
     *         already failed), THROTTLED (installation bound reached) or REQUESTED with the new job
     * @throws IllegalArgumentException for a character audience or an unknown campaign
     */
    public Request request(UUID campaign, Audience audience, boolean force) {
        Objects.requireNonNull(campaign, "campaign");
        String wire = audienceWire(audience);
        if (!settings.summariesEnabled()) {
            return new Request(Request.Outcome.DISABLED, null, 0, 0);
        }
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            // One installation-wide lock makes the pending-count bound exact.
            jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended('memory_summary_job', 0))) l",
                    Integer.class);
            var rows = jdbc.query("select c.adventure_id, c.recovery_generation, (select actor_id from campaign_creation "
                    + "where campaign_id = c.id) from campaign c where c.id = ?", (row, index) -> new Object[] {
                            row.getObject(1, UUID.class), row.getLong(2), row.getObject(3, UUID.class)}, campaign);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            UUID adventure = (UUID) rows.getFirst()[0];
            long generation = (long) rows.getFirst()[1];
            UUID player = (UUID) rows.getFirst()[2];
            var pending = jdbc.query("select id, range_from, range_to from memory_summary_job where campaign_id = ? "
                    + "and audience = ? and status in ('QUEUED', 'RUNNING')", (row, index) -> new Request(
                            Request.Outcome.PENDING, row.getObject(1, UUID.class), row.getLong(2), row.getLong(3)),
                    campaign, wire);
            if (!pending.isEmpty()) {
                return pending.getFirst();
            }
            long frontier = usableWithin(jdbc, campaign, wire).map(Usable::coveredTo).orElse(0L);
            var uncovered = jdbc.query("select sequence, operation_id, event_type, payload ->> 'actorId' from "
                    + "campaign_event where campaign_id = ? and visibility = 'PUBLIC' and sequence > ? order by sequence "
                    + "limit ?", (row, index) -> new Object[] {row.getLong(1), row.getObject(2, UUID.class),
                            row.getString(3), row.getString(4)}, campaign, frontier, MAX_RANGE);
            if (uncovered.isEmpty()) {
                return new Request(Request.Outcome.UP_TO_DATE, null, 0, 0);
            }
            long from = (long) uncovered.getFirst()[0];
            long to = (long) uncovered.getLast()[0];
            var operations = new LinkedHashSet<UUID>();
            boolean scene = false;
            for (Object[] event : uncovered) {
                if (event[1] != null) {
                    operations.add((UUID) event[1]);
                }
                // The player character changing location is the scene boundary of exploration play.
                scene |= "ACTOR_MOVED".equals(event[2]) && player != null && player.toString().equals(event[3]);
            }
            if (!force && operations.size() < settings.interval() && !scene) {
                return new Request(Request.Outcome.NOT_DUE, null, from, to);
            }
            Integer failed = jdbc.queryForObject("select count(*) from memory_summary_job where campaign_id = ? "
                    + "and campaign_generation = ? and audience = ? and range_from = ? and range_to = ? "
                    + "and status in ('FAILED', 'CANCELLED')", Integer.class, campaign, generation, wire, from, to);
            if (failed != null && failed > 0) {
                return new Request(Request.Outcome.GAVE_UP, null, from, to);
            }
            Integer unfinished = jdbc.queryForObject("select count(*) from memory_summary_job where status in "
                    + "('QUEUED', 'RUNNING')", Integer.class);
            if (unfinished != null && unfinished >= settings.maxPending()) {
                return new Request(Request.Outcome.THROTTLED, null, from, to);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("insert into memory_summary_job (id, adventure_id, campaign_id, campaign_generation, audience, "
                    + "range_from, range_to, status, max_attempts, due_at, expires_at, created_at, updated_at) values "
                    + "(?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?)", id, adventure, campaign, generation, wire, from, to,
                    settings.maxAttempts(), Timestamp.from(now), Timestamp.from(now.plus(settings.timeToLive())),
                    Timestamp.from(now), Timestamp.from(now));
            return new Request(Request.Outcome.REQUESTED, id, from, to);
        }));
    }

    /**
     * Retire what can no longer run: queued jobs past their window fail {@code EXPIRED}, queued jobs
     * of a retired recovery generation are {@code CANCELLED} ({@code GENERATION_RETIRED}), and a
     * running job whose lease expired is queued again with a fresh fence when attempts and window
     * remain, otherwise failed {@code LEASE_EXPIRED}. Re-running a summary attempt is always safe:
     * it writes only derived rows, idempotently per job.
     *
     * @return number of jobs changed
     */
    public int recover() {
        Timestamp now = Timestamp.from(clock.instant());
        return Objects.requireNonNull(transactions.execute(status -> {
            int changed = jdbc.update("update memory_summary_job set status = 'FAILED', failure_code = 'EXPIRED', "
                    + "updated_at = ?, row_version = row_version + 1 where status = 'QUEUED' and expires_at <= ?", now, now);
            changed += jdbc.update("update memory_summary_job j set status = 'CANCELLED', failure_code = "
                    + "'GENERATION_RETIRED', updated_at = ?, row_version = j.row_version + 1 from campaign c where "
                    + "c.id = j.campaign_id and j.status = 'QUEUED' and j.campaign_generation <> c.recovery_generation", now);
            changed += jdbc.update("update memory_summary_job set status = case when attempt_count < max_attempts and "
                    + "expires_at > ? then 'QUEUED' else 'FAILED' end, failure_code = 'LEASE_EXPIRED', lease_holder = null, "
                    + "lease_expires_at = null, fence = fence + 1, due_at = case when attempt_count < max_attempts and "
                    + "expires_at > ? then ? else due_at end, updated_at = ?, row_version = row_version + 1 "
                    + "where status = 'RUNNING' and lease_expires_at <= ?", now, now, now, now, now);
            return changed;
        }));
    }

    /**
     * Claim the next due job of a current generation.
     *
     * @param holder worker identity
     * @return the fenced lease, or empty when summaries are off or nothing is due
     */
    public Optional<Lease> claim(UUID holder) {
        Objects.requireNonNull(holder, "holder");
        if (!settings.summariesEnabled()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return transactions.execute(status -> jdbc.query("update memory_summary_job j set status = 'RUNNING', "
                + "attempt_count = attempt_count + 1, fence = fence + 1, lease_holder = ?, lease_expires_at = ?, "
                + "updated_at = ?, row_version = row_version + 1 where id = (select q.id from memory_summary_job q "
                + "join campaign c on c.id = q.campaign_id and c.recovery_generation = q.campaign_generation "
                + "where q.status = 'QUEUED' and q.due_at <= ? and q.expires_at > ? order by q.due_at, q.id "
                + "limit 1 for update of q skip locked) returning id, adventure_id, campaign_id, campaign_generation, "
                + "audience, range_from, range_to, attempt_count, fence", (row, index) -> new Lease(
                        row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class),
                        row.getLong(4), row.getString(5), row.getLong(6), row.getLong(7), row.getInt(8), row.getLong(9),
                        holder), holder, Timestamp.from(now.plus(settings.lease())), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now)).stream().findFirst());
    }

    /**
     * Release a lease after a failed attempt. A retryable failure queues the job again after a
     * linear backoff (30 seconds per attempt) when attempts and window remain; otherwise the job
     * fails for good. The fence steps, so the released worker can never record a result.
     *
     * @param lease the attempt's lease
     * @param code fixed failure code
     * @param retryable whether another attempt may succeed
     * @return true when the job was queued again
     * @throws IllegalStateException when the lease is no longer held
     */
    public boolean fail(Lease lease, String code, boolean retryable) {
        Objects.requireNonNull(lease, "lease");
        if (code == null || !REASON.matcher(code).matches()) {
            throw new IllegalArgumentException("failure code must be a fixed identifier");
        }
        Instant now = clock.instant();
        Instant next = now.plus(Duration.ofSeconds(30L * lease.attempt()));
        return Objects.requireNonNull(transactions.execute(status -> {
            var rows = jdbc.query("select attempt_count < max_attempts and expires_at > ? from memory_summary_job "
                    + "where id = ? and status = 'RUNNING' and lease_holder = ? and fence = ? for update",
                    (row, index) -> row.getBoolean(1), Timestamp.from(next), lease.jobId(), lease.holder(), lease.fence());
            if (rows.isEmpty()) {
                throw new IllegalStateException("summary job lease lost");
            }
            boolean again = retryable && rows.getFirst();
            jdbc.update("update memory_summary_job set status = ?, failure_code = ?, lease_holder = null, "
                    + "lease_expires_at = null, fence = fence + 1, due_at = case when ? then ? else due_at end, "
                    + "updated_at = ?, row_version = row_version + 1 where id = ?", again ? "QUEUED" : "FAILED", code,
                    again, Timestamp.from(next), Timestamp.from(now), lease.jobId());
            return again;
        }));
    }

    /**
     * Store an accepted summary and complete its job, unless its sources changed meanwhile.
     *
     * <p>Under the campaign's memory lock, this rechecks the lease and fence, the recovery generation,
     * the locale and glossary revisions, that no supplied knowledge record changed after the
     * assembled revision, and that no invalidation written after assembly names the summary's
     * events, sources or entities. A failed recheck writes nothing and reports STALE; the caller
     * releases the attempt as retryable so the next attempt reads the new sources.
     *
     * @param lease the attempt's lease
     * @param recording the validated summary and the provenance of its context
     * @return RECORDED with the summary, or STALE with the first changed component
     * @throws IllegalStateException when the lease is no longer held
     */
    public Recorded record(Lease lease, Recording recording) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(recording, "recording");
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            lockCampaign(lease.campaignId());
            Integer held = jdbc.queryForObject("select count(*) from memory_summary_job where id = ? and status = "
                    + "'RUNNING' and lease_holder = ? and fence = ? and lease_expires_at > ?", Integer.class, lease.jobId(),
                    lease.holder(), lease.fence(), Timestamp.from(now));
            if (held == null || held != 1) {
                throw new IllegalStateException("summary job lease lost");
            }
            var tokens = jdbc.query("select c.recovery_generation, coalesce(p.narrative_locale, w.source_locale), "
                    + "coalesce(p.locale_revision, 0), coalesce(p.glossary_revision, 0) from campaign c join "
                    + "adventure_workspace w on w.id = c.adventure_id left join presentation_campaign_settings p on "
                    + "p.campaign_id = c.id where c.id = ?", (row, index) -> new Object[] {row.getLong(1),
                            row.getString(2), row.getLong(3), row.getLong(4)}, lease.campaignId()).getFirst();
            if ((long) tokens[0] != lease.generation() || (long) tokens[0] != recording.generation()) {
                return new Recorded(null, "generation");
            }
            if (!tokens[1].equals(recording.locale()) || (long) tokens[2] != recording.localeRevision()) {
                return new Recorded(null, "locale");
            }
            if ((long) tokens[3] != recording.glossaryRevision()) {
                return new Recorded(null, "glossary");
            }
            var versions = sourceVersions(lease.campaignId(), recording.sourceRefs(), recording.revision());
            for (String ref : recording.sourceRefs()) {
                if (!versions.containsKey(ref)) {
                    return new Recorded(null, "source:" + ref);
                }
            }
            var refs = new ArrayList<String>();
            recording.coveredEvents().forEach(sequence -> refs.add("event:" + sequence));
            refs.addAll(recording.sourceRefs());
            refs.addAll(recording.entityRefs());
            var invalidated = jdbc.queryForList("select reason from memory_invalidation where campaign_id = ? and "
                    + "sequence > ? and (cardinality(refs) = 0 or refs && cast(? as text[])) order by sequence limit 1",
                    String.class, lease.campaignId(), recording.invalidationMark(), refs.toArray(new String[0]));
            if (!invalidated.isEmpty()) {
                return new Recorded(null, "invalidated:" + invalidated.getFirst());
            }
            long highWater = jdbc.queryForObject("select coalesce(max(sequence), 0) from memory_invalidation where "
                    + "campaign_id = ?", Long.class, lease.campaignId());
            long eventHighWater = jdbc.queryForObject("select coalesce(max(sequence), 0) from campaign_event where "
                    + "campaign_id = ? and state_revision <= ?", Long.class, lease.campaignId(), recording.revision());
            var sources = new ArrayList<Map<String, Object>>();
            for (String ref : recording.sourceRefs()) {
                var entry = new java.util.LinkedHashMap<String, Object>();
                entry.put("ref", ref);
                entry.put("version", versions.get(ref));
                sources.add(entry);
            }
            UUID id = UUID.randomUUID();
            List<Long> covered = recording.coveredEvents();
            jdbc.update("insert into memory_summary (id, campaign_id, job_id, campaign_generation, audience, kind, "
                    + "covered_from, covered_to, covered_events, source_revision, event_high_water, locale, locale_revision, "
                    + "glossary_revision, invalidation_high_water, context_digest, template_hash, sources, entity_refs, "
                    + "body, body_digest, created_at) values (?, ?, ?, ?, ?, 'CONTINUITY', ?, ?, cast(? as bigint[]), ?, ?, "
                    + "?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?)", id,
                    lease.campaignId(), lease.jobId(), lease.generation(), lease.audience(), covered.getFirst(),
                    covered.getLast(), covered.toArray(new Long[0]), recording.revision(), eventHighWater,
                    recording.locale(), recording.localeRevision(), recording.glossaryRevision(), highWater,
                    recording.contextDigest(), recording.templateHash(), JSON.writeValueAsString(sources),
                    JSON.writeValueAsString(recording.entityRefs()), recording.bodyJson(), ContextEnvelope.sha256(
                            recording.bodyJson()), Timestamp.from(now));
            jdbc.update("update memory_summary_job set status = 'COMPLETED', summary_id = ?, failure_code = null, "
                    + "lease_holder = null, lease_expires_at = null, updated_at = ?, row_version = row_version + 1 "
                    + "where id = ?", id, Timestamp.from(now), lease.jobId());
            return new Recorded(id, null);
        }));
    }

    /**
     * Mark derived summaries stale, for example after an administrative correction. Call it inside
     * the correcting canonical transaction so the invalidation commits with the correction.
     *
     * @param campaign campaign identity
     * @param reason fixed code such as {@code CORRECTION}
     * @param refs references whose summaries become stale ({@code event:<sequence>},
     *        {@code actor:}, {@code location:}, {@code claim:}, {@code belief:},
     *        {@code commitment:}); empty invalidates every earlier summary of the campaign
     * @return the invalidation sequence
     * @throws IllegalArgumentException for a malformed reason or reference, more than
     *         {@value #MAX_INVALIDATION_REFS} references or an unknown campaign
     */
    public long invalidate(UUID campaign, String reason, List<String> refs) {
        Objects.requireNonNull(campaign, "campaign");
        if (reason == null || !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("invalidation reason must be a fixed identifier");
        }
        var distinct = List.copyOf(new LinkedHashSet<>(refs));
        if (distinct.size() > MAX_INVALIDATION_REFS || distinct.stream().anyMatch(ref -> !REFERENCE.matcher(ref).matches())) {
            throw new IllegalArgumentException("at most " + MAX_INVALIDATION_REFS + " stable references");
        }
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            lockCampaign(campaign);
            var revision = jdbc.queryForList("select state_revision from campaign where id = ?", Long.class, campaign);
            if (revision.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            return jdbc.queryForObject("insert into memory_invalidation (campaign_id, reason, refs, state_revision, "
                    + "created_at) values (?, ?, cast(? as text[]), ?, ?) returning sequence", Long.class, campaign, reason,
                    distinct.toArray(new String[0]), revision.getFirst(), Timestamp.from(now));
        }));
    }

    /**
     * @param summary summary identity
     * @return its freshness now
     * @throws IllegalArgumentException for an unknown summary
     */
    public Freshness freshness(UUID summary) {
        Objects.requireNonNull(summary, "summary");
        return Objects.requireNonNull(transactions.execute(status -> evaluate(jdbc, summary)));
    }

    /**
     * Newest summary of an audience that is not stale, read through the caller's connection so the
     * context assembler can use it inside its own snapshot. At most {@value #USABLE_SCAN} newest
     * summaries of the current generation are examined.
     *
     * @param jdbc storage, inside the caller's transaction
     * @param campaign campaign identity
     * @param audience {@code public} or {@code privileged}
     * @return the usable summary with its freshness, or empty
     */
    public static Optional<Usable> usableWithin(JdbcTemplate jdbc, UUID campaign, String audience) {
        var candidates = jdbc.query("select m.id from memory_summary m join campaign c on c.id = m.campaign_id and "
                + "c.recovery_generation = m.campaign_generation where m.campaign_id = ? and m.audience = ? "
                + "order by m.covered_to desc, m.created_at desc limit ?", (row, index) -> row.getObject(1, UUID.class),
                campaign, audience, USABLE_SCAN);
        for (UUID id : candidates) {
            Freshness freshness = evaluate(jdbc, id);
            if (freshness.status() != Freshness.Status.STALE) {
                return Optional.of(jdbc.query("select covered_from, covered_to, covered_events, locale, source_revision, "
                        + "body::text, entity_refs::text from memory_summary where id = ?", (row, index) -> {
                            var covered = new ArrayList<Long>();
                            for (Long sequence : (Long[]) row.getArray(3).getArray()) {
                                covered.add(sequence);
                            }
                            var entities = new ArrayList<String>();
                            JSON.readTree(row.getString(7)).forEach(node -> entities.add(node.asString()));
                            return new Usable(id, row.getLong(1), row.getLong(2), List.copyOf(covered), row.getString(4),
                                    row.getLong(5), JSON.readTree(row.getString(6)), List.copyOf(entities), freshness);
                        }, id).getFirst());
            }
        }
        return Optional.empty();
    }

    /** Freshness of one stored summary; see the class description. */
    static Freshness evaluate(JdbcTemplate jdbc, UUID summary) {
        var rows = jdbc.query("select m.campaign_id, m.campaign_generation <> c.recovery_generation, m.locale <> "
                + "coalesce(p.narrative_locale, w.source_locale) or m.locale_revision <> coalesce(p.locale_revision, 0), "
                + "m.glossary_revision <> coalesce(p.glossary_revision, 0), m.covered_to from memory_summary m join campaign c "
                + "on c.id = m.campaign_id join adventure_workspace w on w.id = c.adventure_id left join "
                + "presentation_campaign_settings p on p.campaign_id = c.id where m.id = ?", (row, index) -> new Object[] {
                        row.getObject(1, UUID.class), row.getBoolean(2), row.getBoolean(3), row.getBoolean(4),
                        row.getLong(5)}, summary);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("unknown summary");
        }
        Object[] row = rows.getFirst();
        var reasons = new ArrayList<String>();
        if ((boolean) row[1]) {
            reasons.add("generation");
        }
        if ((boolean) row[2]) {
            reasons.add("locale");
        }
        if ((boolean) row[3]) {
            reasons.add("glossary");
        }
        // A supplied record whose row version moved (or that vanished) was superseded, determined or resolved.
        jdbc.queryForList("select s ->> 'ref' from memory_summary m cross join jsonb_array_elements(m.sources) s "
                + "where m.id = ? and (s ->> 'version')::bigint is distinct from (case split_part(s ->> 'ref', ':', 1) "
                + "when 'belief' then (select b.row_version from knowledge_belief b where b.campaign_id = m.campaign_id "
                + "and b.id = split_part(s ->> 'ref', ':', 2)::uuid) when 'claim' then (select k.row_version from "
                + "knowledge_claim k where k.campaign_id = m.campaign_id and k.id = split_part(s ->> 'ref', ':', 2)::uuid) "
                + "when 'commitment' then (select a.row_version from actor_commitment a where a.campaign_id = m.campaign_id "
                + "and a.id = split_part(s ->> 'ref', ':', 2)::uuid) end) order by 1", String.class, summary)
                .forEach(ref -> reasons.add("source:" + ref));
        jdbc.queryForList("select distinct i.reason from memory_summary m join memory_invalidation i on i.campaign_id = "
                + "m.campaign_id and i.sequence > m.invalidation_high_water where m.id = ? and (cardinality(i.refs) = 0 "
                + "or i.refs && (array(select 'event:' || e from unnest(m.covered_events) e) || array(select s ->> 'ref' "
                + "from jsonb_array_elements(m.sources) s) || array(select x #>> '{}' from jsonb_array_elements(m.entity_refs) x))) "
                + "order by 1", String.class, summary).forEach(reason -> reasons.add("invalidated:" + reason));
        int uncovered = jdbc.queryForObject("select count(*) from (select 1 from campaign_event where campaign_id = ? and "
                + "visibility = 'PUBLIC' and sequence > ? limit 1000) newer", Integer.class, row[0], row[4]);
        Freshness.Status status = !reasons.isEmpty() ? Freshness.Status.STALE
                : uncovered > 0 ? Freshness.Status.BEHIND : Freshness.Status.CURRENT;
        return new Freshness(status, List.copyOf(reasons), uncovered);
    }

    /**
     * Current row versions of the supplied knowledge records that did not change after the
     * assembled revision; a missing key means the record changed (or does not exist).
     */
    private Map<String, Long> sourceVersions(UUID campaign, List<String> refs, long revision) {
        var byKind = new HashMap<String, List<UUID>>();
        for (String ref : refs) {
            int colon = ref.indexOf(':');
            byKind.computeIfAbsent(ref.substring(0, colon), kind -> new ArrayList<>())
                    .add(UUID.fromString(ref.substring(colon + 1)));
        }
        var versions = new HashMap<String, Long>();
        // Each changing write records the revision it happened at, so "unchanged after the assembled
        // revision" is exact even though the summary was assembled in an earlier snapshot.
        query(versions, "belief", "select id, row_version from knowledge_belief where campaign_id = ? and id = any(?) "
                + "and coalesce(superseded_revision, 0) <= ?", campaign, byKind.get("belief"), revision);
        query(versions, "claim", "select id, row_version from knowledge_claim where campaign_id = ? and id = any(?) "
                + "and coalesce(superseded_revision, 0) <= ? and coalesce(truth_revision, 0) <= ?", campaign,
                byKind.get("claim"), revision, revision);
        query(versions, "commitment", "select id, row_version from actor_commitment where campaign_id = ? and "
                + "id = any(?) and coalesce(resolved_revision, 0) <= ?", campaign, byKind.get("commitment"), revision);
        return versions;
    }

    /** Run one version query for a kind when it has identities. */
    private void query(Map<String, Long> versions, String kind, String sql, UUID campaign, List<UUID> ids,
            Object... revisions) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        var args = new ArrayList<Object>(List.of(campaign, ids.toArray(new UUID[0])));
        args.addAll(List.of(revisions));
        jdbc.query(sql, row -> {
            versions.put(kind + ":" + row.getObject(1, UUID.class), row.getLong(2));
        }, args.toArray());
    }

    /** Serialize summary recording and invalidation per campaign without touching the campaign row. */
    private void lockCampaign(UUID campaign) {
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended('memory:' || cast(? as text), 0))) l",
                Integer.class, campaign);
    }

    /** @return the stored audience name of a supported summary audience */
    static String audienceWire(Audience audience) {
        Objects.requireNonNull(audience, "audience");
        String wire = audience.wire();
        if (!AUDIENCES.contains(wire)) {
            throw new IllegalArgumentException("summaries exist for the public and privileged audiences only");
        }
        return wire;
    }

    /**
     * Result of {@link #request}.
     *
     * @param outcome what happened
     * @param jobId new or pending job; null otherwise
     * @param rangeFrom first uncovered event sequence considered (0 when none)
     * @param rangeTo last uncovered event sequence considered (0 when none)
     */
    public record Request(Outcome outcome, UUID jobId, long rangeFrom, long rangeTo) {
        /** Request outcomes; see {@link MemorySummaries#request}. */
        public enum Outcome {
            DISABLED, PENDING, UP_TO_DATE, NOT_DUE, GAVE_UP, THROTTLED, REQUESTED
        }
    }

    /**
     * A claimed attempt.
     *
     * @param jobId job identity
     * @param adventureId owning adventure
     * @param campaignId owning campaign
     * @param generation recovery generation the job belongs to
     * @param audience {@code public} or {@code privileged}
     * @param rangeFrom first requested event sequence
     * @param rangeTo last requested event sequence
     * @param attempt attempt number, from 1
     * @param fence fence of this attempt
     * @param holder worker identity
     */
    public record Lease(UUID jobId, UUID adventureId, UUID campaignId, long generation, String audience, long rangeFrom,
            long rangeTo, int attempt, long fence, UUID holder) {
    }

    /**
     * A validated summary with the provenance of the context it was produced from.
     *
     * @param coveredEvents covered event sequences, increasing, 1..{@value #MAX_RANGE}
     * @param sourceRefs every knowledge record supplied ({@code belief:}, {@code claim:} or
     *        {@code commitment:} references)
     * @param entityRefs entity references the summary names
     * @param bodyJson stored body: summary, obligations, entities and uncertainties
     * @param contextDigest digest of the contract context
     * @param templateHash contract template hash (prompt provenance)
     * @param generation recovery generation of the assembled context
     * @param revision state revision of the assembled context
     * @param locale output locale tag
     * @param localeRevision locale revision of the assembled context
     * @param glossaryRevision glossary revision of the assembled context
     * @param invalidationMark newest invalidation sequence read before assembly
     */
    public record Recording(List<Long> coveredEvents, List<String> sourceRefs, List<String> entityRefs, String bodyJson,
            String contextDigest, String templateHash, long generation, long revision, String locale,
            long localeRevision, long glossaryRevision, long invalidationMark) {
        /** @throws IllegalArgumentException for an empty, unordered or oversized coverage or a malformed source */
        public Recording {
            coveredEvents = List.copyOf(coveredEvents);
            sourceRefs = List.copyOf(sourceRefs);
            entityRefs = List.copyOf(entityRefs);
            if (coveredEvents.isEmpty() || coveredEvents.size() > MAX_RANGE) {
                throw new IllegalArgumentException("a summary covers 1.." + MAX_RANGE + " events");
            }
            for (int index = 1; index < coveredEvents.size(); index++) {
                if (coveredEvents.get(index) <= coveredEvents.get(index - 1)) {
                    throw new IllegalArgumentException("covered events must increase");
                }
            }
            for (String ref : sourceRefs) {
                if (!ref.matches("(belief|claim|commitment):[0-9a-f-]{36}")) {
                    throw new IllegalArgumentException("summary sources are belief, claim or commitment references");
                }
            }
            Objects.requireNonNull(bodyJson, "bodyJson");
            Objects.requireNonNull(locale, "locale");
        }
    }

    /**
     * Result of {@link #record}.
     *
     * @param summaryId stored summary, null when stale
     * @param stale first changed component, null when recorded
     */
    public record Recorded(UUID summaryId, String stale) {
    }

    /**
     * Freshness of a summary now.
     *
     * @param status CURRENT, BEHIND or STALE
     * @param reasons why it is stale ({@code generation}, {@code locale}, {@code glossary},
     *        {@code source:<ref>}, {@code invalidated:<reason>}); empty unless stale
     * @param uncoveredEvents newer public events, counted up to 1,000
     */
    public record Freshness(Status status, List<String> reasons, int uncoveredEvents) {
        /** Freshness statuses. */
        public enum Status {
            CURRENT, BEHIND, STALE
        }
    }

    /**
     * A summary the assembler may use.
     *
     * @param id summary identity
     * @param coveredFrom first covered event sequence
     * @param coveredTo last covered event sequence
     * @param coveredEvents exact covered sequences
     * @param locale language of the text
     * @param sourceRevision revision of its sources
     * @param body stored body
     * @param entityRefs entities it names
     * @param freshness CURRENT or BEHIND
     */
    public record Usable(UUID id, long coveredFrom, long coveredTo, List<Long> coveredEvents, String locale,
            long sourceRevision, JsonNode body, List<String> entityRefs, Freshness freshness) {
    }
}
