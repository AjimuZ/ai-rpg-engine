package org.rpgengine.corrections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.exports.CampaignExports;
import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.rpgengine.memory.MemorySummaries;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.preparation.PlanAdmissionService;
import org.rpgengine.preparation.QueryEpochs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owner canon corrections of one knowledge claim, with a concrete preview, a stored backup, a source-linked audit,
 * dependency revalidation and summary invalidation (P16.3-A, QA-27, FR-026, docs/14 section 8).
 *
 * <p><b>What a correction does.</b> The owner names a current claim of an owned campaign and its corrected truth,
 * and optionally a corrected statement (with its language) or, for a {@code PROPERTY} claim, a corrected value. The
 * subject, predicate, object, qualifier and disclosure never change: a different proposition is new canon, not a
 * correction. In one transaction at the next state revision the engine journals a committed
 * {@value #COMMAND_TYPE} operation, appends one {@code INTERNAL} {@value #EVENT_TYPE} event, closes the claim and
 * writes its successor citing that event, carries every current belief about it to the successor (a {@code KNOWS}
 * stance becomes {@code BELIEVES} when the corrected truth is not {@code TRUE}, because knowing needs a true claim),
 * bumps the claim and holder query scopes, re-evaluates every admitted plan, records a {@value #INVALIDATION_REASON}
 * summary invalidation and a public-state outbox intent. World time, the turn, the mode, the pending decision,
 * resources, items and every earlier event are unchanged: history is preserved, and the corrected claim and the
 * replaced beliefs keep their values and are only closed at the correcting revision.
 *
 * <p><b>Preview.</b> {@link #preview} runs exactly that transaction and rolls it back, so the reported impact is
 * what the commit would do, not an estimate: the claim and its successor, its source events, each carried belief,
 * the query scopes, every plan whose disposition changes and every examined summary that would become stale. The
 * preview names no identity the commit would create, so equal state gives equal bytes and digest.
 *
 * <p><b>Apply.</b> {@link #apply} requires the digest of the preview the owner approved. It first takes a
 * full-owner {@link CampaignExports} backup at the expected revision, then reruns the transaction and refuses with
 * {@link Code#PREVIEW_CHANGED} when the impact differs from the approved one. The approved preview bytes and the
 * backup are stored in {@code canon_correction} beside the operation, and both digests are cited by the event and
 * the receipt. A repeated request with the same identity and payload returns the stored outcome and writes nothing.
 *
 * <p><b>Limits.</b> The campaign must be idle (no live mutation lease), at most {@value #MAX_BELIEFS} current beliefs
 * may depend on the claim, and at most {@value #SUMMARY_SCAN} newest summaries are examined for the preview; the
 * invalidation itself applies to every summary that cites the claim, its beliefs, its source events or its
 * entities. The backup is stored in the same database: it is the state before the correction, not an independent
 * disaster-recovery copy, and nothing restores it yet (P16.4).
 */
@Service
public class CanonCorrections {
    /** Journal command type of a correction operation. */
    public static final String COMMAND_TYPE = "CANON_CORRECTION";
    /** Type of the internal audit event a correction appends. */
    public static final String EVENT_TYPE = "CANON_CORRECTED";
    /** Reason code of the summary invalidation a correction records. */
    public static final String INVALIDATION_REASON = "CANON_CORRECTION";
    /** Identifies a preview document. */
    public static final String PREVIEW_FORMAT = "engine.canon-correction-preview";
    /** Version of the preview layout. */
    public static final int PREVIEW_FORMAT_VERSION = 1;
    /** Most current beliefs that may depend on a corrected claim; more is refused, never truncated. */
    public static final int MAX_BELIEFS = 64;
    /** Most changed plans a preview lists individually; the total is always reported. */
    static final int MAX_LISTED_PLANS = 64;
    /** Newest summaries of the current generation a preview examines. */
    static final int SUMMARY_SCAN = 256;
    /** Inclusive length bound of the owner's stated reason. */
    static final int MAX_REASON = 500;
    // Mirrors the reference grammar MemorySummaries.invalidate accepts; a reference outside it (a qualified content
    // id with '#') makes the correction invalidate every summary of the campaign instead, which is the safe side.
    private static final Pattern INVALIDATION_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final JdbcTemplate jdbc;
    private final CampaignExports exports;
    private final MemorySummaries summaries;
    private final TransactionTemplate transaction;
    private final TransactionTemplate reads;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param jdbc storage
     * @param manager transaction manager
     * @param exports source of the pre-correction backup
     * @param summaries summary freshness and invalidation
     */
    public CanonCorrections(JdbcTemplate jdbc, PlatformTransactionManager manager, CampaignExports exports,
            MemorySummaries summaries) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(60);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(30);
    }

    /**
     * Report exactly what {@link #apply} would do now, and write nothing.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign identity
     * @param request the correction
     * @return the preview document and its digest
     * @throws Refused for a missing or foreign scope, a used request identity, a stale revision, a busy campaign, an
     *     unknown or superseded claim, an invalid or empty correction, or too many dependent beliefs
     * @throws IllegalStateException inside an ambient transaction
     */
    public Preview preview(UUID owner, UUID adventure, UUID campaign, Request request) {
        requireScope(owner, adventure, campaign, request);
        Outcome outcome = Objects.requireNonNull(transaction.execute(status -> {
            status.setRollbackOnly();
            return correct(owner, adventure, campaign, request, null);
        }));
        return new Preview(request.expectedRevision(), outcome.previewSha256(), outcome.preview());
    }

    /**
     * Apply a correction whose preview the owner approved, or return the stored outcome of the same request.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign identity
     * @param request the correction; its request identity is unique per campaign
     * @param approvedPreview SHA-256 digest of the preview the owner approved
     * @return the committed outcome
     * @throws Refused for every {@link #preview} refusal except a used identity of the same payload, for
     *     {@link Code#REQUEST_CONFLICT} when the identity was used with another payload, and for
     *     {@link Code#PREVIEW_CHANGED} when the impact now differs from the approved preview
     * @throws IllegalStateException inside an ambient transaction or when the backup exceeds its bounds
     */
    public Applied apply(UUID owner, UUID adventure, UUID campaign, Request request, String approvedPreview) {
        requireScope(owner, adventure, campaign, request);
        CanonicalValues.requireSha256Hex(approvedPreview, "approvedPreview");
        Applied stored = reads.execute(status -> {
            jdbc.execute("set local statement_timeout = '5s'");
            if (jdbc.queryForObject("select count(*) from campaign c join adventure_workspace a on a.id = c.adventure_id "
                    + "where c.id = ? and c.adventure_id = ? and a.owner_id = ?", Integer.class, campaign, adventure,
                    owner) != 1) {
                throw new Refused(Code.SCOPE_UNAVAILABLE);
            }
            return replay(campaign, request);
        });
        if (stored != null) {
            return stored;
        }
        CampaignExports.Export backup;
        try {
            backup = exports.export(owner, adventure, campaign, CampaignExports.Audience.FULL_OWNER);
        } catch (CampaignExports.ScopeUnavailable unavailable) {
            throw new Refused(Code.SCOPE_UNAVAILABLE);
        }
        // The backup must describe exactly the state being corrected; the locked revision check below then proves
        // nothing committed between the export and the correction.
        if (backup.stateRevision() != request.expectedRevision()) {
            throw new Refused(Code.STALE_REVISION);
        }
        return Objects.requireNonNull(transaction.execute(status ->
                correct(owner, adventure, campaign, request, new Approval(approvedPreview, backup)))).applied();
    }

    /**
     * Read the stored audit record of one correction: the approved preview and the pre-correction backup.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign identity
     * @param operation the correction operation ({@link Applied#operationId()})
     * @return the stored record
     * @throws Refused with {@link Code#SCOPE_UNAVAILABLE} for a missing, foreign or other operation
     */
    public Audit audit(UUID owner, UUID adventure, UUID campaign, UUID operation) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(operation, "operation");
        requireNoTransaction();
        return Objects.requireNonNull(reads.execute(status -> {
            jdbc.execute("set local statement_timeout = '30s'");
            var rows = jdbc.query("select k.base_revision, k.preview_sha256, k.preview, k.backup_sha256, k.backup "
                    + "from canon_correction k join operation_journal o on o.id = k.operation_id join campaign c on "
                    + "c.id = o.campaign_id join adventure_workspace a on a.id = c.adventure_id where k.operation_id = ? "
                    + "and c.id = ? and c.adventure_id = ? and a.owner_id = ?", (row, index) -> new Audit(row.getLong(1),
                            row.getString(2), new String(row.getBytes(3), StandardCharsets.UTF_8), row.getString(4),
                            row.getBytes(5)), operation, campaign, adventure, owner);
            if (rows.isEmpty()) {
                throw new Refused(Code.SCOPE_UNAVAILABLE);
            }
            return rows.getFirst();
        }));
    }

    /** Shared argument and transaction checks of preview and apply. */
    private static void requireScope(UUID owner, UUID adventure, UUID campaign, Request request) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(request, "request");
        requireNoTransaction();
    }

    /** A correction is its own transaction; joining a caller's would hold the campaign lock across its work. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("canon corrections require no ambient transaction");
        }
    }

    /**
     * Stored outcome of a committed request with this identity and payload, or null when the identity is unused.
     *
     * @throws Refused with {@link Code#REQUEST_CONFLICT} when the identity belongs to another payload
     */
    private Applied replay(UUID campaign, Request request) {
        var rows = jdbc.query("select payload_hash, command_type, receipt::text from operation_journal where "
                + "campaign_id = ? and idempotency_key = ?", (row, index) -> new String[] {row.getString(1),
                        row.getString(2), row.getString(3)}, campaign, request.requestId());
        if (rows.isEmpty()) {
            return null;
        }
        String[] row = rows.getFirst();
        if (!row[0].equals(payloadHash(request)) || !COMMAND_TYPE.equals(row[1]) || row[2] == null) {
            throw new Refused(Code.REQUEST_CONFLICT);
        }
        JsonNode receipt = json.readTree(row[2]);
        JsonNode details = receipt.get("details");
        return new Applied(UUID.fromString(receipt.get("operationId").asString()), receipt.get("stateRevision").asLong(),
                receipt.get("firstEventSequence").asLong(), UUID.fromString(details.get("successorClaimId").asString()),
                details.get("previewSha256").asString(), details.get("backupSha256").asString(),
                details.get("invalidationSequence").asLong(), details.get("beliefsCarried").asInt(),
                details.get("plansChanged").asInt(), details.get("summariesStale").asInt(), true);
    }

    /**
     * The correction transaction. Without an approval it is a preview: the caller rolls it back and only the
     * preview is used. With one it stores the record and the receipt, and refuses when the impact changed.
     */
    private Outcome correct(UUID owner, UUID adventure, UUID campaign, Request request, Approval approval) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '30s'");
        // Campaign first, the lock order of every canonical writer.
        var locked = jdbc.query("select c.state_revision, c.event_sequence, c.world_time_seconds, r.ruleset_hash "
                + "from campaign c join adventure_workspace a on a.id = c.adventure_id join content_revision r on "
                + "r.id = c.content_revision_id where c.id = ? and c.adventure_id = ? and a.owner_id = ? for update of c",
                (row, index) -> new Locked(row.getLong(1), row.getLong(2), row.getLong(3), row.getString(4)), campaign,
                adventure, owner);
        if (locked.isEmpty()) {
            throw new Refused(Code.SCOPE_UNAVAILABLE);
        }
        Locked state = locked.getFirst();
        Applied stored = replay(campaign, request);
        if (stored != null) {
            if (approval == null) {
                throw new Refused(Code.REQUEST_USED);
            }
            return new Outcome(null, null, stored);
        }
        if (state.revision() != request.expectedRevision()) {
            throw new Refused(Code.STALE_REVISION);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from campaign_mutation_lease where "
                + "campaign_id = ? and expires_at > clock_timestamp())", Boolean.class, campaign))) {
            throw new Refused(Code.CAMPAIGN_BUSY);
        }
        Target claim = target(campaign, request.claimId());
        UUID successorId = UUID.randomUUID();
        KnowledgeChange.Claim successor = successor(claim, request, successorId);
        List<Held> held = jdbc.query("select id, holder_actor_id, stance, acquisition, informant_actor_id, source_ref, "
                + "row_version, learned_event_sequence from knowledge_belief where campaign_id = ? and claim_id = ? and "
                + "superseded_revision is null order by id limit ?", (row, index) -> new Held(row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class), KnowledgeChange.Stance.valueOf(row.getString(3)),
                        KnowledgeChange.Acquisition.valueOf(row.getString(4)), row.getObject(5, UUID.class),
                        row.getString(6), row.getLong(7), row.getLong(8)), campaign, claim.id(), MAX_BELIEFS + 1);
        if (held.size() > MAX_BELIEFS) {
            throw new Refused(Code.TOO_MANY_DEPENDENTS);
        }
        var sourceEvents = jdbc.queryForList("select sequence, event_type, state_revision, visibility from campaign_event "
                + "where campaign_id = ? and sequence in (?, ?) order by sequence", campaign, claim.establishedEvent(),
                claim.truthEvent());
        // Summaries usable before the correction; the ones among them that are stale afterwards are its impact.
        List<UUID> examined = jdbc.queryForList("select m.id from memory_summary m join campaign c on c.id = "
                + "m.campaign_id and c.recovery_generation = m.campaign_generation where m.campaign_id = ? order by "
                + "m.covered_to desc, m.created_at desc, m.id limit ?", UUID.class, campaign, SUMMARY_SCAN);
        List<UUID> usable = examined.stream()
                .filter(id -> summaries.freshness(id).status() != MemorySummaries.Freshness.Status.STALE).toList();

        Instant now = jdbc.queryForObject("select clock_timestamp()", (row, index) -> row.getTimestamp(1).toInstant());
        long revision = Math.addExact(state.revision(), 1);
        long sequence = Math.addExact(state.eventSequence(), 1);
        UUID operation = UUID.randomUUID();
        // COMMITTING first: the receipt is write-once, and it can only be written after the impact is known.
        jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                + "base_revision, status, created_at, updated_at) values (?, ?, ?, ?, ?, ?, 'COMMITTING', ?, ?)", operation,
                campaign, request.requestId(), payloadHash(request), COMMAND_TYPE, state.revision(), Timestamp.from(now),
                Timestamp.from(now));
        if (jdbc.update("update campaign set state_revision = state_revision + 1, event_sequence = event_sequence + 1, "
                + "updated_at = ?, row_version = row_version + 1 where id = ? and state_revision = ?", Timestamp.from(now),
                campaign, state.revision()) != 1) {
            throw new IllegalStateException("campaign changed under its lock");
        }
        var carried = new ArrayList<KnowledgeChange>();
        carried.add(successor);
        ObjectNode payload = json.createObjectNode();
        payload.put("claimId", claim.id().toString());
        payload.put("successorClaimId", successorId.toString());
        payload.put("subjectRef", claim.subjectRef());
        payload.put("truthBefore", claim.truth().name());
        payload.put("truthAfter", request.truth().name());
        payload.put("reason", request.reason());
        ArrayNode carriedBeliefs = payload.putArray("beliefs");
        for (Held belief : held) {
            UUID next = UUID.randomUUID();
            carried.add(new KnowledgeChange.Belief(next, belief.holder(), successorId, carriedStance(belief, request),
                    belief.acquisition(), belief.informant(), belief.sourceRef(),
                    new KnowledgeChange.Supersession(belief.id(), belief.rowVersion()), 0));
            ObjectNode entry = carriedBeliefs.addObject();
            entry.put("from", belief.id().toString());
            entry.put("to", next.toString());
        }
        if (approval != null) {
            payload.put("previewSha256", approval.previewSha256());
            payload.put("backupSha256", approval.backup().sha256());
            payload.put("backupRevision", approval.backup().stateRevision());
        }
        jdbc.update("insert into campaign_event (id, campaign_id, sequence, operation_id, correlation_id, event_type, "
                + "event_version, state_revision, world_time_seconds, ruleset_hash, visibility, payload_schema_version, "
                + "payload, created_at) values (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'INTERNAL', 1, cast(? as jsonb), ?)",
                UUID.randomUUID(), campaign, sequence, operation, operation, EVENT_TYPE, revision, state.worldTime(),
                state.rulesetHash(), json.writeValueAsString(payload), Timestamp.from(now));

        // The successor and the carried beliefs cite the event just written; the database checks that provenance.
        var at = new KnowledgeLedger.Provenance(revision, state.worldTime(), sequence, 1);
        var scopes = new TreeSet<>(KnowledgeLedger.applyWithin(jdbc, campaign, at, carried));
        for (String scope : scopes) {
            QueryEpochs.bump(jdbc, campaign, scope);
        }
        int plansChanged = PlanAdmissionService.invalidateWithin(jdbc, campaign, revision);
        var plans = jdbc.queryForList("select artifact_id::text as artifact, status, reason_code from plan_disposition "
                + "where campaign_id = ? and checked_revision = ? order by artifact_id limit ?", campaign, revision,
                MAX_LISTED_PLANS);
        List<String> refs = invalidationRefs(claim, held);
        long invalidation = summaries.invalidate(campaign, INVALIDATION_REASON, refs);
        List<String> stale = usable.stream()
                .filter(id -> summaries.freshness(id).status() == MemorySummaries.Freshness.Status.STALE)
                .map(UUID::toString).sorted().toList();
        jdbc.update("insert into canonical_outbox (id, campaign_id, operation_id, state_revision, kind, created_at) "
                + "values (?, ?, ?, ?, 'PUBLIC_STATE', ?)", UUID.randomUUID(), campaign, operation, revision,
                Timestamp.from(now));

        ObjectNode preview = previewDocument(campaign, state, request, claim, successor, sourceEvents, held, scopes,
                plansChanged, plans, examined.size(), usable.size(), stale, refs);
        byte[] previewBytes = json.writeValueAsBytes(preview);
        String previewSha256 = sha256(previewBytes);
        if (approval == null) {
            return new Outcome(new String(previewBytes, StandardCharsets.UTF_8), previewSha256, null);
        }
        if (!previewSha256.equals(approval.previewSha256())) {
            throw new Refused(Code.PREVIEW_CHANGED);
        }
        byte[] backup = approval.backup().document();
        jdbc.update("insert into canon_correction (operation_id, base_revision, preview, preview_sha256, backup, "
                + "backup_sha256, created_at) values (?, ?, ?, ?, ?, ?, ?)", operation, state.revision(), previewBytes,
                previewSha256, backup, approval.backup().sha256(), Timestamp.from(now));
        ObjectNode receipt = json.createObjectNode();
        receipt.put("operationId", operation.toString());
        receipt.put("campaignId", campaign.toString());
        receipt.put("stateRevision", revision);
        receipt.put("firstEventSequence", sequence);
        receipt.put("lastEventSequence", sequence);
        ObjectNode details = receipt.putObject("details");
        details.put("kind", "canon-correction");
        details.put("claimId", claim.id().toString());
        details.put("successorClaimId", successorId.toString());
        details.put("previewSha256", previewSha256);
        details.put("backupSha256", approval.backup().sha256());
        details.put("backupRevision", approval.backup().stateRevision());
        details.put("invalidationSequence", invalidation);
        details.put("beliefsCarried", held.size());
        details.put("plansChanged", plansChanged);
        details.put("summariesStale", stale.size());
        receipt.putArray("randomDraws");
        if (jdbc.update("update operation_journal set status = 'COMMITTED', committed_revision = ?, receipt = "
                + "cast(? as jsonb), operation_version = operation_version + 1, updated_at = ?, row_version = row_version "
                + "+ 1 where id = ? and status = 'COMMITTING'", revision, json.writeValueAsString(receipt),
                Timestamp.from(now), operation) != 1) {
            throw new IllegalStateException("correction journal changed under its lock");
        }
        return new Outcome(null, previewSha256, new Applied(operation, revision, sequence, successorId, previewSha256,
                approval.backup().sha256(), invalidation, held.size(), plansChanged, stale.size(), false));
    }

    /** The current claim to correct, read under the campaign lock. */
    private Target target(UUID campaign, UUID claim) {
        var rows = jdbc.query("select subject_ref, predicate, object_ref, qualifier, property_value::text, statement, "
                + "statement_locale, disclosure, truth, row_version, established_revision, established_event_sequence, "
                + "truth_revision, truth_event_sequence from knowledge_claim where campaign_id = ? and id = ? and "
                + "superseded_revision is null", (row, index) -> new Target(claim, row.getString(1),
                        KnowledgeChange.Predicate.valueOf(row.getString(2)), row.getString(3), row.getString(4),
                        row.getString(5), row.getString(6), row.getString(7),
                        KnowledgeChange.Disclosure.valueOf(row.getString(8)), KnowledgeChange.Truth.valueOf(row.getString(9)),
                        row.getLong(10), row.getLong(11), row.getLong(12), row.getLong(13), row.getLong(14)), campaign, claim);
        if (rows.isEmpty()) {
            throw new Refused(Code.CLAIM_UNAVAILABLE);
        }
        return rows.getFirst();
    }

    /**
     * The corrected claim: the same subject, predicate, object, qualifier and disclosure, with the requested truth and
     * the requested or kept statement and value.
     *
     * @throws Refused with {@link Code#SHAPE} for a value on another predicate or an invalid result, and with
     *     {@link Code#NO_CHANGE} when nothing differs
     */
    private KnowledgeChange.Claim successor(Target claim, Request request, UUID successorId) {
        if (request.valueJson() != null && claim.predicate() != KnowledgeChange.Predicate.PROPERTY) {
            throw new Refused(Code.SHAPE);
        }
        String value = request.valueJson() == null ? claim.value() : request.valueJson();
        String statement = request.statement() == null ? claim.statement() : request.statement();
        String locale = request.statement() == null ? claim.statementLocale() : request.statementLocale();
        KnowledgeChange.Claim successor;
        try {
            successor = new KnowledgeChange.Claim(successorId, claim.subjectRef(), claim.predicate(), claim.objectRef(),
                    claim.qualifier(), value, statement, locale, claim.disclosure(), request.truth(),
                    new KnowledgeChange.Supersession(claim.id(), claim.rowVersion()), 0);
        } catch (IllegalArgumentException invalid) {
            throw new Refused(Code.SHAPE);
        }
        boolean sameValue = value == null ? claim.value() == null
                : claim.value() != null && json.readTree(value).equals(json.readTree(claim.value()));
        if (request.truth() == claim.truth() && sameValue && Objects.equals(statement, claim.statement())
                && Objects.equals(locale, claim.statementLocale())) {
            throw new Refused(Code.NO_CHANGE);
        }
        return successor;
    }

    /** A carried belief keeps its stance, except that knowing needs a true claim. */
    private static KnowledgeChange.Stance carriedStance(Held belief, Request request) {
        return belief.stance() == KnowledgeChange.Stance.KNOWS && request.truth() != KnowledgeChange.Truth.TRUE
                ? KnowledgeChange.Stance.BELIEVES : belief.stance();
    }

    /**
     * References whose summaries the correction makes stale: the claim, the replaced beliefs, the claim's source
     * events and the entities it names. When they do not fit the invalidation grammar or bound, the empty list
     * invalidates every summary of the campaign instead.
     */
    private static List<String> invalidationRefs(Target claim, List<Held> held) {
        Set<String> refs = new LinkedHashSet<>();
        refs.add("claim:" + claim.id());
        held.forEach(belief -> refs.add("belief:" + belief.id()));
        refs.add("event:" + claim.establishedEvent());
        refs.add("event:" + claim.truthEvent());
        refs.add(claim.subjectRef());
        if (claim.objectRef() != null) {
            refs.add(claim.objectRef());
        }
        if (refs.size() > MemorySummaries.MAX_INVALIDATION_REFS
                || refs.stream().anyMatch(ref -> !INVALIDATION_REF.matcher(ref).matches())) {
            return List.of();
        }
        return List.copyOf(refs);
    }

    /** The deterministic impact document; it names no identity the correction itself creates. */
    private ObjectNode previewDocument(UUID campaign, Locked state, Request request, Target claim,
            KnowledgeChange.Claim successor, List<java.util.Map<String, Object>> sourceEvents, List<Held> held,
            Set<String> scopes, int plansChanged, List<java.util.Map<String, Object>> plans, int examined, int usable,
            List<String> stale, List<String> refs) {
        ObjectNode document = json.createObjectNode();
        document.put("format", PREVIEW_FORMAT);
        document.put("formatVersion", PREVIEW_FORMAT_VERSION);
        document.put("campaignId", campaign.toString());
        document.put("baseRevision", state.revision());
        document.put("stateRevision", state.revision() + 1);
        document.put("eventSequence", state.eventSequence() + 1);
        document.put("worldTimeSeconds", state.worldTime());
        document.put("reason", request.reason());
        ObjectNode before = proposition(document.putObject("claim"), claim.subjectRef(), claim.predicate(),
                claim.objectRef(), claim.qualifier(), claim.value(), claim.statement(), claim.statementLocale(),
                claim.disclosure(), claim.truth());
        before.put("id", claim.id().toString());
        before.put("rowVersion", claim.rowVersion());
        before.put("establishedRevision", claim.establishedRevision());
        before.put("establishedEvent", claim.establishedEvent());
        before.put("truthRevision", claim.truthRevision());
        before.put("truthEvent", claim.truthEvent());
        proposition(document.putObject("successor"), successor.subjectRef(), successor.predicate(),
                successor.objectRef(), successor.qualifier(), successor.valueJson(), successor.statement(),
                successor.statementLocale(), successor.disclosure(), successor.truth());
        ArrayNode events = document.putArray("sourceEvents");
        for (var event : sourceEvents) {
            ObjectNode entry = events.addObject();
            entry.put("sequence", ((Number) event.get("sequence")).longValue());
            entry.put("eventType", (String) event.get("event_type"));
            entry.put("stateRevision", ((Number) event.get("state_revision")).longValue());
            entry.put("visibility", (String) event.get("visibility"));
        }
        ArrayNode beliefs = document.putArray("beliefs");
        for (Held belief : held) {
            ObjectNode entry = beliefs.addObject();
            entry.put("id", belief.id().toString());
            entry.put("holderId", belief.holder().toString());
            entry.put("rowVersion", belief.rowVersion());
            entry.put("stance", belief.stance().name());
            entry.put("carriedStance", carriedStance(belief, request).name());
            entry.put("acquisition", belief.acquisition().name());
            entry.put("learnedEvent", belief.learnedEvent());
        }
        ArrayNode scopeList = document.putArray("queryScopes");
        scopes.forEach(scopeList::add);
        ObjectNode planImpact = document.putObject("plans");
        planImpact.put("changed", plansChanged);
        ArrayNode listed = planImpact.putArray("listed");
        for (var plan : plans) {
            ObjectNode entry = listed.addObject();
            entry.put("artifactId", (String) plan.get("artifact"));
            entry.put("status", (String) plan.get("status"));
            entry.put("reason", (String) plan.get("reason_code"));
        }
        ObjectNode summaryImpact = document.putObject("summaries");
        summaryImpact.put("examined", examined);
        summaryImpact.put("usable", usable);
        ArrayNode staleList = summaryImpact.putArray("stale");
        stale.forEach(staleList::add);
        ObjectNode invalidation = document.putObject("invalidation");
        invalidation.put("reason", INVALIDATION_REASON);
        invalidation.put("wholeCampaign", refs.isEmpty());
        ArrayNode refList = invalidation.putArray("refs");
        refs.forEach(refList::add);
        return document;
    }

    /** Write one proposition's fields in a fixed order; absent fields are explicit nulls. */
    private ObjectNode proposition(ObjectNode node, String subject, KnowledgeChange.Predicate predicate, String object,
            String qualifier, String value, String statement, String locale, KnowledgeChange.Disclosure disclosure,
            KnowledgeChange.Truth truth) {
        node.put("subjectRef", subject);
        node.put("predicate", predicate.name());
        node.put("objectRef", object);
        node.put("qualifier", qualifier);
        if (value == null) {
            node.putNull("value");
        } else {
            node.set("value", json.readTree(value));
        }
        node.put("statement", statement);
        node.put("statementLocale", locale);
        node.put("disclosure", disclosure.name());
        node.put("truth", truth.name());
        return node;
    }

    /** Canonical payload hash of a request, so a repeated identity with another payload is a conflict. */
    private String payloadHash(Request request) {
        ObjectNode node = json.createObjectNode();
        node.put("commandType", COMMAND_TYPE);
        node.put("expectedRevision", request.expectedRevision());
        node.put("claimId", request.claimId().toString());
        node.put("truth", request.truth().name());
        node.put("statement", request.statement());
        node.put("statementLocale", request.statementLocale());
        node.put("value", request.valueJson());
        node.put("reason", request.reason());
        return sha256(json.writeValueAsBytes(node));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * One requested correction of a claim.
     *
     * @param requestId owner-chosen identity, 1..128 characters, unique per campaign
     * @param expectedRevision committed revision the owner saw; any other current revision is refused
     * @param claimId current claim of the campaign to correct
     * @param truth corrected truth
     * @param statement corrected statement, or null to keep the current one
     * @param statementLocale {@code en} or {@code it}, required with and only with a statement
     * @param valueJson corrected JSON scalar of a {@code PROPERTY} claim, or null to keep the current one
     * @param reason why the owner corrects it, 1..{@value #MAX_REASON} characters, kept in the audit event
     */
    public record Request(String requestId, long expectedRevision, UUID claimId, KnowledgeChange.Truth truth,
            String statement, String statementLocale, String valueJson, String reason) {
        /** Reject malformed requests before any database access. */
        public Request {
            CanonicalValues.requireText(requestId, "requestId", 128);
            CanonicalValues.requireNonNegative(expectedRevision, "expectedRevision");
            Objects.requireNonNull(claimId, "claimId");
            Objects.requireNonNull(truth, "truth");
            CanonicalValues.optionalText(statement, "statement", 2000);
            if ((statement == null) != (statementLocale == null)
                    || statementLocale != null && !Set.of("en", "it").contains(statementLocale)) {
                throw new IllegalArgumentException("a corrected statement needs its language (en or it)");
            }
            CanonicalValues.requireText(reason, "reason", MAX_REASON);
        }
    }

    /**
     * What a correction would do now.
     *
     * @param baseRevision revision the preview describes
     * @param sha256 digest to pass to {@link #apply} as the approval
     * @param document UTF-8 JSON impact document ({@value #PREVIEW_FORMAT})
     */
    public record Preview(long baseRevision, String sha256, String document) {
    }

    /**
     * A committed correction.
     *
     * @param operationId journal operation of the correction
     * @param stateRevision revision it committed
     * @param eventSequence sequence of its audit event
     * @param successorClaimId the corrected claim
     * @param previewSha256 digest of the approved and stored preview
     * @param backupSha256 digest of the stored pre-correction backup
     * @param invalidationSequence the summary invalidation it recorded
     * @param beliefsCarried beliefs carried to the corrected claim
     * @param plansChanged plans whose disposition changed
     * @param summariesStale examined summaries that became stale
     * @param replayed true when an earlier identical request was found and nothing was written
     */
    public record Applied(UUID operationId, long stateRevision, long eventSequence, UUID successorClaimId,
            String previewSha256, String backupSha256, long invalidationSequence, int beliefsCarried, int plansChanged,
            int summariesStale, boolean replayed) {
    }

    /**
     * The stored record of one correction.
     *
     * @param baseRevision revision the backup describes
     * @param previewSha256 digest of {@link #preview()}
     * @param preview the approved preview document
     * @param backupSha256 digest of {@link #backup()}
     * @param backup the full-owner campaign export taken before the correction
     */
    public record Audit(long baseRevision, String previewSha256, String preview, String backupSha256, byte[] backup) {
        /** Copy the backup so the record cannot be changed after it is read. */
        public Audit {
            backup = backup.clone();
        }

        /** @return a copy of the backup bytes */
        @Override
        public byte[] backup() {
            return backup.clone();
        }
    }

    /** Fixed refusal codes; a refusal writes nothing. */
    public enum Code {
        /** The owner, adventure or campaign is missing, foreign or mismatched. */
        SCOPE_UNAVAILABLE,
        /** A preview named a request identity that is already used. */
        REQUEST_USED,
        /** The request identity was used with another payload or command. */
        REQUEST_CONFLICT,
        /** The campaign is no longer at the expected revision. */
        STALE_REVISION,
        /** An operation holds the campaign's mutation lease. */
        CAMPAIGN_BUSY,
        /** The claim is unknown, foreign or already superseded. */
        CLAIM_UNAVAILABLE,
        /** The corrected claim would be malformed, or a value was given for a claim that is not a property. */
        SHAPE,
        /** The correction changes nothing. */
        NO_CHANGE,
        /** More than {@value CanonCorrections#MAX_BELIEFS} current beliefs depend on the claim. */
        TOO_MANY_DEPENDENTS,
        /** The impact differs from the approved preview; preview again. */
        PREVIEW_CHANGED
    }

    /** A refused correction with its fixed code; it retains no identities or diagnostics. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Code code;

        /** @param code why the correction was refused */
        public Refused(Code code) {
            super("canon correction refused: " + code);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** @return why the correction was refused */
        public Code code() {
            return code;
        }
    }

    /** Campaign counters and ruleset read under the lock. */
    private record Locked(long revision, long eventSequence, long worldTime, String rulesetHash) {
    }

    /** The claim being corrected. */
    private record Target(UUID id, String subjectRef, KnowledgeChange.Predicate predicate, String objectRef,
            String qualifier, String value, String statement, String statementLocale,
            KnowledgeChange.Disclosure disclosure, KnowledgeChange.Truth truth, long rowVersion,
            long establishedRevision, long establishedEvent, long truthRevision, long truthEvent) {
    }

    /** A current belief about the claim. */
    private record Held(UUID id, UUID holder, KnowledgeChange.Stance stance, KnowledgeChange.Acquisition acquisition,
            UUID informant, String sourceRef, long rowVersion, long learnedEvent) {
    }

    /** What the owner approved, plus the backup taken for it. */
    private record Approval(String previewSha256, CampaignExports.Export backup) {
    }

    /** Result of one correction transaction: the preview, or the committed outcome. */
    private record Outcome(String preview, String previewSha256, Applied applied) {
    }
}
