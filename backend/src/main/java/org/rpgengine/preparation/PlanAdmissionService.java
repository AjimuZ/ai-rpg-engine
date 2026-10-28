package org.rpgengine.preparation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

import org.rpgengine.persistence.CanonicalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional admission of versioned quest-plan artifacts and their dependency
 * records (docs/28 section 7, second admission check; QA-36). The provisional validator
 * (P08.7) runs outside any transaction; this gate repeats every check that later world
 * changes could falsify, under the campaign lock that every canonical writer also takes:
 *
 * <ol>
 *   <li><b>Fencing and idempotency:</b> the attempt's holder, fence, live lease, current
 *       generation and recorded result digest; a repeated call returns the same receipt or
 *       rejection, and unique keys allow one artifact per attempt and plan version.</li>
 *   <li><b>Scope:</b> the snapshot's generation is current, and a public snapshot carries
 *       neither planner-private dependencies nor references to secret definitions.</li>
 *   <li><b>Content:</b> the snapshot's pinned revision, pack hash and ruleset hash are the
 *       campaign's own, and every cited definition exists in that sealed closure.</li>
 *   <li><b>Author:</b> the snapshot's author-constraint digest equals the pinned root
 *       policy's, and every cited constraint is a required or fixed authored entity.</li>
 *   <li><b>Dependencies:</b> each recorded QUERY epoch is current (which catches phantoms)
 *       and each typed version dependency still names its exact row version; every cited
 *       campaign entity must be covered by a recorded dependency, so a plan cannot rely on
 *       an unrecorded read. Predicates and CONSTRAINT rows fail closed until P08.7.</li>
 *   <li><b>Lineage:</b> the version is new and builds on an admitted predecessor.</li>
 * </ol>
 *
 * <p>The proposal must keep the snapshot's dependency manifest: the same observations in the same
 * order. Since P08.7 the provisional validator may narrow a captured whole-artifact entry to the
 * branches whose typed predicates read it, so a later change invalidates only those branches;
 * it can never drop an entry, alter an observation or weaken an entry to fewer branches.
 *
 * <p>Then it stores the immutable artifact, dependencies, one admission receipt carrying
 * the gate version and checked references, and a VALIDATED disposition atomically. A stale
 * proposal is rejected, never copied forward, and may trigger at most one regeneration.
 * Admission and invalidation are metadata writes: they never advance state revision, turn,
 * world time, random draws, rewards or the event sequence. A validated plan is preparation,
 * never canonical truth; only an ordinary canonical command can use it, through
 * {@link PlanActivations}.
 */
@Service
public class PlanAdmissionService {
    /** Version of this transactional gate, recorded on every receipt it writes. */
    public static final String GATE_VERSION = "plan-gate-v1";
    // Only quest-plan jobs produce artifacts admitted here.
    static final String QUEST_PLAN = "QUEST_PLAN";
    // Predecessor states a new version may build on; SUPERSEDED implies a version conflict.
    private static final Set<String> BUILDABLE = Set.of("VALIDATED", "PARTIALLY_INVALIDATED", "INVALIDATED");
    private static final String UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    // Closed typed-version grammar, mirroring plan_reference_version in V12. ENTITY_FIELD
    // names world rows; FACT names player-knowledge records. Anything else is unsupported.
    static final Pattern ENTITY_REFERENCE = Pattern.compile("^(actor|resource|item|character):" + UUID_TEXT + "$");
    static final Pattern FACT_REFERENCE = Pattern.compile("^(known-fact|known-quest):" + UUID_TEXT + "$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JobRecoveryService recovery;
    private final PlanContentCatalogs catalogs;

    /** Wire local persistence, the startup reconciliation gate and the pinned content loader. */
    public PlanAdmissionService(JdbcTemplate jdbc, PlatformTransactionManager manager, JobRecoveryService recovery,
            PlanContentCatalogs catalogs) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        transaction = PreparationSupport.template(manager);
    }

    /**
     * Admit the result of one attempt. Idempotent per attempt: a repeated call after an
     * acceptance returns DUPLICATE with the same receipt, after a rejection the same
     * rejection. Integrity mismatches between proposal and job throw without writing.
     * The pinned content catalog is loaded before any lock is taken.
     *
     * @param lease the lease whose attempt recorded the result
     * @param proposal backend-validated plan content, references and dependencies
     * @return ACCEPTED with the receipt, REJECTED with a reason code, or DUPLICATE
     * @throws IllegalStateException for stale leases, a retired generation, a result digest
     *         other than the recorded one, or before startup reconciliation
     * @throws IllegalArgumentException when the proposal does not belong to the job or omits
     *         the snapshot's dependency manifest
     */
    public Admission admit(PreparationJobService.Lease lease, Proposal proposal) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(proposal, "proposal");
        PreparationSupport.requireNoTransaction();
        if (!recovery.isReconciled()) {
            throw new IllegalStateException("job recovery has not completed");
        }
        var content = catalogs.preload(lease.campaignId());
        return transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            // NO KEY UPDATE conflicts with canonical commits (FOR UPDATE) and with share
            // locks of enqueue/snapshot/recovery, but not with foreign-key checks.
            var campaigns = jdbc.queryForList("select state_revision, recovery_generation, content_revision_id "
                    + "from campaign where id = ? for no key update", lease.campaignId());
            if (campaigns.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            long revision = ((Number) campaigns.getFirst().get("state_revision")).longValue();
            long generation = ((Number) campaigns.getFirst().get("recovery_generation")).longValue();
            UUID pinned = (UUID) campaigns.getFirst().get("content_revision_id");
            JobRow row = JobRow.lock(jdbc, lease.campaignId(), lease.jobId());
            var attempts = jdbc.queryForList("select fence, lease_holder, outcome, result_digest, snapshot_id, "
                    + "failure_code from ai_job_attempt where job_id = ? and attempt_number = ?", lease.jobId(),
                    lease.attempt());
            if (attempts.isEmpty() || ((Number) attempts.getFirst().get("fence")).longValue() != lease.fence()
                    || !lease.holder().equals(attempts.getFirst().get("lease_holder"))) {
                throw new IllegalStateException("stale job lease");
            }
            Map<String, Object> attempt = attempts.getFirst();
            // Historical receipts remain audit evidence, but cannot authorize a callback
            // after restore retired its generation.
            if (!row.currentGeneration()) {
                throw new IllegalStateException("retired job generation");
            }
            if (!proposal.resultDigest().equals(attempt.get("result_digest"))) {
                throw new IllegalStateException("conflicting job result");
            }
            var receipts = jdbc.query("select id, artifact_id from plan_admission where job_id = ? "
                    + "and attempt_number = ?", (result, index) -> new Admission(Outcome.DUPLICATE,
                    result.getObject(1, UUID.class), result.getObject(2, UUID.class), null), lease.jobId(),
                    lease.attempt());
            if (!receipts.isEmpty()) {
                return receipts.getFirst();
            }
            if ("REJECTED".equals(attempt.get("outcome"))) {
                return new Admission(Outcome.REJECTED, null, null, (String) attempt.get("failure_code"));
            }
            // Any other outcome means the attempt ended without a result to admit; a
            // cancellation request or lost lease leaves no RESULT_RECEIVED holder here.
            row.requireHeldBy(lease, Set.of("RESULT_RECEIVED"));
            if (!QUEST_PLAN.equals(row.kind()) || !proposal.contractVersion().equals(row.contractVersion())
                    || !proposal.questTarget().equals(row.target())) {
                throw new IllegalArgumentException("proposal does not belong to the job");
            }
            UUID snapshotId = (UUID) attempt.get("snapshot_id");
            // The result cannot remove or replace anything the engine supplied: every recorded
            // observation stays in order with identical fields. JSONB comparison normalizes
            // object whitespace and key order and compares numbers by value; only the two
            // reliance fields (severity, branch references) are excluded, and retained() below
            // decides whether their change keeps the captured protection.
            SnapshotFacts snapshot = jdbc.queryForObject("select context_digest, audience, campaign_generation, "
                    + "content_revision_id, pack_hash, ruleset_hash, author_constraint_hash, "
                    + "jsonb_array_length(dependency_manifest) = jsonb_array_length(?::jsonb) and not exists ("
                    + "select 1 from jsonb_array_elements(dependency_manifest) with ordinality m(entry, position) "
                    + "where (m.entry - 'severity' - 'branchRefsJson') is distinct from "
                    + "((?::jsonb -> (m.position::int - 1)) - 'severity' - 'branchRefsJson')) "
                    + "from planning_snapshot where id = ? and campaign_id = ?",
                    (result, index) -> new SnapshotFacts(snapshotId, result.getString(1),
                            Audience.valueOf(result.getString(2)), result.getLong(3), result.getObject(4, UUID.class),
                            result.getString(5), result.getString(6), result.getString(7), result.getBoolean(8)),
                    manifest(proposal.dependencies()), manifest(proposal.dependencies()), snapshotId,
                    lease.campaignId());
            if (!Objects.requireNonNull(snapshot).sameManifest()
                    || !retained(snapshotId, proposal.dependencies(), proposal.references())) {
                throw new IllegalArgumentException("proposal must retain the snapshot dependency manifest");
            }
            updateJob("VALIDATING", "", lease);
            // Locale changes do not change world revision. Public text nevertheless
            // must not acquire a new admission under obsolete locale/glossary tokens.
            jdbc.queryForList("select campaign_id from presentation_campaign_settings where campaign_id = ? for share",
                    UUID.class, lease.campaignId());
            boolean stalePresentation = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select s.audience = 'PUBLIC' and (s.locale_revision <> coalesce(p.locale_revision, 0) "
                    + "or s.glossary_revision <> coalesce(p.glossary_revision, 0)) from planning_snapshot s "
                    + "left join presentation_campaign_settings p on p.campaign_id = s.campaign_id where s.id = ?",
                    Boolean.class, snapshotId));
            String reason = stalePresentation ? "STALE_PRESENTATION"
                    : gate(lease.campaignId(), generation, pinned, content.matching(pinned), snapshot, proposal);
            if (reason != null) {
                reject(lease, row, reason);
                return new Admission(Outcome.REJECTED, null, null, reason);
            }
            return accept(lease, proposal, snapshot, revision, generation);
        });
    }

    /**
     * Append invalidation dispositions for current artifacts whose recorded query epochs or
     * typed versions no longer match, in its own transaction under the campaign lock.
     *
     * @return number of artifacts whose disposition changed
     * @throws IllegalArgumentException for an unknown campaign
     */
    public int invalidateStale(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        PreparationSupport.requireNoTransaction();
        return Objects.requireNonNull(transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            var revisions = jdbc.queryForList("select state_revision from campaign where id = ? for no key update",
                    Long.class, campaign);
            if (revisions.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            return invalidateWithin(jdbc, campaign, revisions.getFirst());
        }));
    }

    /**
     * Invalidate inside the caller's transaction, which must hold the campaign lock
     * (canonical commits call this after writing effects and bumping epochs). Every stored
     * dependency is re-evaluated by the V12 {@code plan_dependency_fresh} function: a QUERY
     * epoch that moved or a typed reference whose row version changed (including a removed
     * or foreign row) is stale. A stale ARTIFACT-severity dependency makes the artifact
     * INVALIDATED; stale BRANCH-severity ones only make a VALIDATED artifact
     * PARTIALLY_INVALIDATED, and a BRANCH row without branch references (observed only, P08.7)
     * changes no disposition. The reason is DEPENDENCY_CHANGED when a typed version moved,
     * otherwise QUERY_EPOCH_CHANGED. Prior bytes are never edited; each change is one more
     * disposition row, and an unchanged stale state adds nothing, so repeated commits
     * cannot loop.
     *
     * @param revision canonical state revision the check ran against
     * @return number of artifacts whose disposition changed
     */
    public static int invalidateWithin(JdbcTemplate jdbc, UUID campaign, long revision) {
        Objects.requireNonNull(campaign, "campaign");
        CanonicalValues.requireNonNegative(revision, "revision");
        PreparationSupport.requireTransaction();
        jdbc.queryForObject("select id from campaign where id = ? for no key update", UUID.class, campaign);
        return jdbc.update("with latest as (select a.id, d.status, d.sequence from quest_plan_artifact a "
                + "cross join lateral (select status, sequence from plan_disposition where artifact_id = a.id "
                + "order by sequence desc limit 1) d where a.campaign_id = ? "
                + "and d.status in ('VALIDATED', 'PARTIALLY_INVALIDATED')), "
                + "stale as (select l.id, l.status, l.sequence, bool_or(p.severity = 'ARTIFACT') as whole, "
                + "bool_or(p.kind <> 'QUERY') as versioned from latest l "
                + "join plan_dependency p on p.artifact_id = l.id where not plan_dependency_fresh(?, p) "
                + "and (p.severity = 'ARTIFACT' or jsonb_array_length(p.branch_refs) > 0) "
                + "group by l.id, l.status, l.sequence) "
                + "insert into plan_disposition (artifact_id, sequence, campaign_id, status, reason_code, "
                + "checked_revision, created_at) select id, sequence + 1, ?, case when whole then 'INVALIDATED' "
                + "else 'PARTIALLY_INVALIDATED' end, case when versioned then 'DEPENDENCY_CHANGED' "
                + "else 'QUERY_EPOCH_CHANGED' end, ?, clock_timestamp() from stale "
                + "where whole or status = 'VALIDATED'", campaign, campaign, campaign, revision);
    }

    /**
     * Run the transactional gate under the caller's campaign lock and return the first
     * rejection reason, or null when the proposal may be admitted. Reasons are checked in a
     * fixed order (scope, content, author, dependency support and coverage, freshness,
     * lineage) so equal inputs always yield the same code. Only reads are performed.
     *
     * @param catalog pinned facts matching the locked content revision, or null when unavailable
     */
    private String gate(UUID campaign, long generation, UUID pinned, PlanContentCatalog catalog,
            SnapshotFacts snapshot, Proposal proposal) {
        References references = proposal.references();
        // Scope: a restored generation cannot admit older input even with equal revisions,
        // and public input can never carry private reads or secret definitions.
        if (snapshot.generation() != generation) {
            return "STALE_GENERATION";
        }
        if (snapshot.audience() == Audience.PUBLIC
                && proposal.dependencies().stream().anyMatch(value -> value.audience() == Audience.PLANNER_PRIVATE)) {
            return "AUDIENCE_SCOPE";
        }
        // Content: the snapshot must describe the campaign's own sealed pinned closure.
        if (catalog == null) {
            return "CONTENT_UNAVAILABLE";
        }
        var hashes = jdbc.queryForMap("select content_hash, ruleset_hash from content_revision where id = ?", pinned);
        if (!pinned.equals(snapshot.contentRevision()) || !snapshot.packHash().equals(hashes.get("content_hash"))
                || !snapshot.rulesetHash().equals(hashes.get("ruleset_hash"))) {
            return "STALE_CONTENT";
        }
        if (!catalog.definitions().containsAll(references.contentRefs())) {
            return "UNKNOWN_CONTENT";
        }
        if (snapshot.audience() == Audience.PUBLIC
                && references.contentRefs().stream().anyMatch(catalog.secretDefinitions()::contains)) {
            return "AUDIENCE_SCOPE";
        }
        // Author: constraints are recomputed from the pinned policy, never trusted from input.
        if (!catalog.authorConstraintHash().equals(snapshot.authorConstraintHash())) {
            return "STALE_AUTHOR_CONSTRAINTS";
        }
        if (!catalog.constrainedDefinitions().containsAll(references.authorConstraintRefs())) {
            return "UNKNOWN_AUTHOR_CONSTRAINT";
        }
        // Dependencies: every row must have a typed evaluator. P08.7 plan predicates are compiled
        // onto the version/query reads they cite, so raw predicate rows still have none.
        var recorded = new HashSet<String>();
        for (Dependency dependency : proposal.dependencies()) {
            if (!supported(dependency)) {
                return "UNSUPPORTED_DEPENDENCY";
            }
            recorded.add(dependency.kind() == Kind.QUERY ? dependency.scope() : dependency.reference());
        }
        // A cited entity whose read was not recorded could change without detection.
        if (!recorded.containsAll(references.entityRefs())) {
            return "UNRECORDED_DEPENDENCY";
        }
        for (Dependency dependency : proposal.dependencies()) {
            if (dependency.kind() == Kind.QUERY) {
                if (dependency.epoch() != QueryEpochs.current(jdbc, campaign, dependency.scope())) {
                    return "STALE_QUERY_EPOCH";
                }
            } else if (!Objects.equals(dependency.expectedVersion(), referenceVersion(campaign, dependency.reference()))) {
                return "STALE_DEPENDENCY";
            }
        }
        // Covered only by a query scope: the entity itself must still exist in this campaign.
        for (String reference : references.entityRefs()) {
            if (referenceVersion(campaign, reference) == null) {
                return "UNKNOWN_REFERENCE";
            }
        }
        Integer taken = jdbc.queryForObject("select count(*) from quest_plan_artifact where campaign_id = ? "
                + "and plan_id = ? and plan_version = ?", Integer.class, campaign, proposal.planId(),
                proposal.planVersion());
        if (taken != null && taken > 0) {
            return "PLAN_VERSION_CONFLICT";
        }
        if (proposal.planVersion() > 1) {
            var predecessors = jdbc.queryForList("select a.quest_target, (select status from plan_disposition "
                    + "where artifact_id = a.id order by sequence desc limit 1) as status from quest_plan_artifact a "
                    + "where a.campaign_id = ? and a.plan_id = ? and a.plan_version = ?", campaign,
                    proposal.planId(), proposal.planVersion() - 1);
            if (predecessors.isEmpty() || !proposal.questTarget().equals(predecessors.getFirst().get("quest_target"))
                    || !BUILDABLE.contains(predecessors.getFirst().get("status"))) {
                return "STALE_PREDECESSOR";
            }
        }
        return null;
    }

    /**
     * Whether the proposal keeps the protection each captured manifest entry had (P08.7). The
     * assembler records reads before any plan exists, conservatively; the provisional validator
     * may then narrow an entry it can attribute to specific branches. Accepted per position:
     * <ul>
     *   <li>the proposal entry guards the whole artifact (always at least as strict);</li>
     *   <li>or the captured entry guarded the whole artifact and the proposal entry guards a
     *       list of branch references (selective validation narrowed it). An empty list means
     *       observed only: the read stays recorded and rechecked but guards no branch, which is
     *       refused for any campaign entity the proposal cites;</li>
     *   <li>or both guard branches and the proposal lists every captured branch reference.</li>
     * </ul>
     * Observation fields are compared separately in SQL; the caller holds the campaign lock.
     */
    private boolean retained(UUID snapshotId, List<Dependency> dependencies, References references) {
        var captured = jdbc.query("select m.entry ->> 'severity', m.entry ->> 'branchRefsJson' from planning_snapshot s "
                + "cross join lateral jsonb_array_elements(s.dependency_manifest) with ordinality m(entry, position) "
                + "where s.id = ? order by m.position", (row, index) -> new String[] {row.getString(1), row.getString(2)},
                snapshotId);
        for (int index = 0; index < dependencies.size(); index++) {
            Dependency proposed = dependencies.get(index);
            if (proposed.severity() == Severity.ARTIFACT) {
                continue;
            }
            List<String> branches = branchRefs(proposed.branchRefsJson());
            String guarded = proposed.kind() == Kind.QUERY ? proposed.scope() : proposed.reference();
            if (branches.isEmpty() && references.entityRefs().contains(guarded)) {
                return false;
            }
            if (Severity.BRANCH.name().equals(captured.get(index)[0])
                    && !branches.containsAll(branchRefs(captured.get(index)[1]))) {
                return false;
            }
        }
        return true;
    }

    /** @return the string elements of a branch-reference array; other elements are ignored */
    private static List<String> branchRefs(String json) {
        var refs = new ArrayList<String>();
        JSON.readTree(json).forEach(node -> {
            if (node.isString()) {
                refs.add(node.asString());
            }
        });
        return refs;
    }

    /**
     * Whether the gate can evaluate a dependency: QUERY rows by epoch; ENTITY_FIELD and FACT
     * rows only as an exact version of a reference in the closed V12 grammar, without a
     * predicate. CONSTRAINT rows and raw predicates have no evaluator: P08.7 plan predicates are
     * evaluated by the provisional validator and compiled onto the typed reads they cite.
     */
    static boolean supported(Dependency dependency) {
        return switch (dependency.kind()) {
            case QUERY -> true;
            case ENTITY_FIELD -> dependency.predicateJson() == null && dependency.expectedVersion() != null
                    && ENTITY_REFERENCE.matcher(dependency.reference()).matches();
            case FACT -> dependency.predicateJson() == null && dependency.expectedVersion() != null
                    && FACT_REFERENCE.matcher(dependency.reference()).matches();
            case CONSTRAINT -> false;
        };
    }

    /** Current row version of a typed reference in this campaign; null when absent or foreign. */
    private Long referenceVersion(UUID campaign, String reference) {
        return jdbc.queryForObject("select plan_reference_version(?, ?)", Long.class, campaign, reference);
    }

    /**
     * Close the attempt as REJECTED and release the job: one fresh regeneration is
     * allowed while attempts and deadline remain, otherwise the job fails with reason.
     */
    private void reject(PreparationJobService.Lease lease, JobRow row, String reason) {
        jdbc.update("update ai_job_attempt set outcome = 'REJECTED', failure_code = ?, finished_at = "
                + "clock_timestamp() where job_id = ? and attempt_number = ?", reason, lease.jobId(), lease.attempt());
        if (row.regenerations() < 1 && row.retryable()) {
            updateJob("QUEUED", "regeneration_count = regeneration_count + 1, ", lease);
        } else {
            jdbc.update("update ai_job set status = 'FAILED', failure_code = ?, " + JobRow.RELEASE + " where id = ?",
                    reason, lease.jobId());
        }
    }

    /**
     * Store the artifact, dependencies, receipt (with the gate version and the references it
     * rechecked) and dispositions, then complete the attempt and job. Unique keys on version,
     * attempt and admission key make a racing duplicate fail the whole transaction instead of
     * storing a second artifact.
     */
    private Admission accept(PreparationJobService.Lease lease, Proposal proposal, SnapshotFacts snapshot,
            long revision, long generation) {
        UUID campaign = lease.campaignId();
        UUID artifact = UUID.randomUUID();
        UUID admission = UUID.randomUUID();
        String fingerprint = fingerprint(proposal.dependencies());
        jdbc.update("insert into quest_plan_artifact (id, campaign_id, plan_id, plan_version, predecessor_version, "
                + "quest_target, contract_version, snapshot_id, body, dependency_fingerprint, job_id, attempt_number, "
                + "created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, clock_timestamp())", artifact,
                campaign, proposal.planId(), proposal.planVersion(),
                proposal.planVersion() > 1 ? proposal.planVersion() - 1 : null, proposal.questTarget(),
                proposal.contractVersion(), snapshot.id(), proposal.bodyJson(), fingerprint, lease.jobId(),
                lease.attempt());
        List<Object[]> dependencies = new ArrayList<>();
        for (int ordinal = 0; ordinal < proposal.dependencies().size(); ordinal++) {
            Dependency dependency = proposal.dependencies().get(ordinal);
            dependencies.add(new Object[] {artifact, ordinal, campaign, dependency.kind().name(),
                    dependency.reference(), dependency.expectedVersion(), dependency.predicateJson(),
                    dependency.audience() == null ? null : dependency.audience().name(), dependency.scope(),
                    dependency.epoch(), dependency.resultDigest(), dependency.severity().name(),
                    dependency.branchRefsJson()});
        }
        jdbc.batchUpdate("insert into plan_dependency (artifact_id, ordinal, campaign_id, kind, reference, "
                + "expected_version, predicate, audience, scope_key, query_epoch, result_digest, severity, branch_refs) "
                + "values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb)", dependencies);
        jdbc.update("insert into plan_admission (id, campaign_id, artifact_id, job_id, attempt_number, "
                + "validator_version, checked_revision, campaign_generation, dependency_fingerprint, accepted_branches, "
                + "rejected_branches, reasons, source_hash, result_hash, admission_key, gate_version, "
                + "checked_references, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, "
                + "?, ?, ?, ?, ?::jsonb, clock_timestamp())",
                admission, campaign, artifact, lease.jobId(), lease.attempt(), proposal.validatorVersion(), revision,
                generation, fingerprint, proposal.acceptedBranchesJson(), proposal.rejectedBranchesJson(),
                proposal.reasonsJson(), snapshot.contextDigest(), proposal.resultDigest(),
                PreparationSupport.sha256(lease.jobId(), lease.attempt()), GATE_VERSION,
                proposal.references().json());
        jdbc.update("insert into plan_disposition (artifact_id, sequence, campaign_id, status, reason_code, "
                + "checked_revision, created_at) values (?, 1, ?, 'VALIDATED', 'ADMITTED', ?, clock_timestamp())",
                artifact, campaign, revision);
        if (proposal.planVersion() > 1) {
            jdbc.update("insert into plan_disposition (artifact_id, sequence, campaign_id, status, reason_code, "
                    + "checked_revision, created_at) select a.id, (select max(sequence) + 1 from plan_disposition "
                    + "where artifact_id = a.id), a.campaign_id, 'SUPERSEDED', 'NEW_VERSION', ?, clock_timestamp() "
                    + "from quest_plan_artifact a where a.campaign_id = ? and a.plan_id = ? and a.plan_version = ?",
                    revision, campaign, proposal.planId(), proposal.planVersion() - 1);
        }
        jdbc.update("update ai_job_attempt set outcome = 'ACCEPTED', finished_at = clock_timestamp() "
                + "where job_id = ? and attempt_number = ?", lease.jobId(), lease.attempt());
        jdbc.update("update ai_job set status = 'COMPLETED', result_digest = ?, " + JobRow.RELEASE + " where id = ?",
                proposal.resultDigest(), lease.jobId());
        return new Admission(Outcome.ACCEPTED, admission, artifact, null);
    }

    /**
     * Change job status. VALIDATING keeps the lease (the V6 lease rule requires it);
     * any other status releases it by consuming one fence.
     */
    private void updateJob(String status, String extra, PreparationJobService.Lease lease) {
        String tail = "VALIDATING".equals(status)
                ? "row_version = row_version + 1, updated_at = clock_timestamp()" : JobRow.RELEASE;
        jdbc.update("update ai_job set status = ?, " + extra + tail + " where id = ?", status, lease.jobId());
    }

    /**
     * SHA-256 over unambiguously framed dependency fields in ordinal order; nullable fields
     * are encoded as "0" (absent) or "1" followed by the value.
     */
    static String fingerprint(List<Dependency> dependencies) {
        List<Object> parts = new ArrayList<>();
        parts.add(dependencies.size());
        for (Dependency dependency : dependencies) {
            parts.add(dependency.kind());
            parts.add(dependency.reference());
            parts.add(optional(dependency.expectedVersion()));
            parts.add(optional(dependency.predicateJson()));
            parts.add(optional(dependency.audience()));
            parts.add(optional(dependency.scope()));
            parts.add(optional(dependency.epoch()));
            parts.add(optional(dependency.resultDigest()));
            parts.add(dependency.severity());
            parts.add(dependency.branchRefsJson());
        }
        return PreparationSupport.sha256(parts.toArray());
    }

    /** Encode immutable typed observations as bounded JSON; serialization errors abort the caller. */
    static String manifest(List<Dependency> dependencies) {
        return JsonMapper.builder().build().writeValueAsString(dependencies);
    }

    /** Presence-tagged encoding for one nullable fingerprint field. */
    private static String optional(Object value) {
        return value == null ? "0" : "1" + value;
    }

    /** Result of one admission call. */
    public enum Outcome {
        /** A new artifact and receipt were stored. */
        ACCEPTED,
        /** The proposal was stale; the attempt is closed as REJECTED. */
        REJECTED,
        /** The attempt was already admitted; the existing receipt is returned. */
        DUPLICATE
    }

    /** Dependency kinds, mirroring {@code plan_dependency.kind}. */
    public enum Kind {
        /** A versioned entity field. */
        ENTITY_FIELD,
        /** A canonical fact. */
        FACT,
        /** A bounded query scope tracked by an epoch (catches phantoms). */
        QUERY,
        /** An author or rules constraint. */
        CONSTRAINT
    }

    /** What a stale dependency invalidates. */
    public enum Severity {
        /** Only the listed branches. */
        BRANCH,
        /** The whole artifact. */
        ARTIFACT
    }

    /**
     * Admission outcome.
     *
     * @param outcome accepted, rejected or duplicate
     * @param admissionId receipt identity (null when rejected)
     * @param artifactId stored artifact (null when rejected)
     * @param reason rejection reason code (null otherwise)
     */
    public record Admission(Outcome outcome, UUID admissionId, UUID artifactId, String reason) {
    }

    /**
     * One recorded dependency; the shape mirrors {@code plan_dependency_shape_ck}.
     *
     * @param kind dependency kind
     * @param reference bounded reference (entity field, fact key, query definition), 1..256 characters
     * @param expectedVersion expected version for non-query dependencies, or null
     * @param predicateJson optional JSON object predicate, at most 4096 bytes
     * @param audience audience of a query, required exactly for QUERY
     * @param scope query scope key, required exactly for QUERY
     * @param epoch scope epoch read by the planner, required exactly for QUERY
     * @param resultDigest SHA-256 of the query result read, required exactly for QUERY
     * @param severity what staleness invalidates
     * @param branchRefsJson JSON array of affected branch references, at most 4096 bytes
     */
    public record Dependency(Kind kind, String reference, Long expectedVersion, String predicateJson,
            Audience audience, String scope, Long epoch, String resultDigest, Severity severity,
            String branchRefsJson) {
        /** Reject shapes the database would reject, before any transaction opens. */
        public Dependency {
            Objects.requireNonNull(kind, "kind");
            CanonicalValues.requireText(reference, "reference", 256);
            Objects.requireNonNull(severity, "severity");
            PreparationSupport.requireJsonArray(branchRefsJson, "branchRefsJson", 4096);
            if (predicateJson != null) {
                CanonicalValues.requireJsonObject(predicateJson, "predicateJson", 4096);
            }
            if (kind == Kind.QUERY) {
                Objects.requireNonNull(audience, "audience");
                PreparationSupport.require(PreparationSupport.SCOPE, scope, "scope");
                CanonicalValues.requireNonNegative(Objects.requireNonNull(epoch, "epoch"), "epoch");
                CanonicalValues.requireSha256Hex(resultDigest, "resultDigest");
                if (expectedVersion != null) {
                    throw new IllegalArgumentException("query dependencies carry no expected version");
                }
            } else {
                if (audience != null || scope != null || epoch != null || resultDigest != null) {
                    throw new IllegalArgumentException("only query dependencies carry audience, scope, epoch or digest");
                }
                if (expectedVersion == null && predicateJson == null) {
                    throw new IllegalArgumentException("dependency needs an expected version or a predicate");
                }
                if (expectedVersion != null) {
                    CanonicalValues.requireNonNegative(expectedVersion, "expectedVersion");
                }
            }
        }

        /** Build a QUERY dependency on one epoch-tracked scope. */
        public static Dependency query(String reference, String predicateJson, Audience audience, String scope,
                long epoch, String resultDigest, Severity severity, String branchRefsJson) {
            return new Dependency(Kind.QUERY, reference, null, predicateJson, audience, scope, epoch, resultDigest,
                    severity, branchRefsJson);
        }

        /** Build a versioned non-query dependency. */
        public static Dependency version(Kind kind, String reference, long expectedVersion, Severity severity,
                String branchRefsJson) {
            return new Dependency(kind, reference, expectedVersion, null, null, null, null, null, severity,
                    branchRefsJson);
        }
    }

    /**
     * Backend-validated plan proposal for one attempt result.
     *
     * @param planId stable plan identity across versions
     * @param planVersion 1..10000; above 1 refines the previous version
     * @param questTarget quest target; must equal the job target
     * @param contractVersion task contract; must equal the job's
     * @param bodyJson JSON object plan body, at most 65536 bytes
     * @param dependencies at most 512 recorded dependencies
     * @param acceptedBranchesJson JSON array, at most 8192 bytes; activation compares its string elements
     * @param rejectedBranchesJson JSON array, at most 8192 bytes
     * @param reasonsJson JSON array of validation reasons, at most 8192 bytes
     * @param validatorVersion provisional validator version
     * @param resultDigest digest recorded by the attempt result
     * @param references typed references the provisional validator extracted from the body
     */
    public record Proposal(UUID planId, int planVersion, String questTarget, String contractVersion, String bodyJson,
            List<Dependency> dependencies, String acceptedBranchesJson, String rejectedBranchesJson,
            String reasonsJson, String validatorVersion, String resultDigest, References references) {
        /** Reject malformed proposals before any transaction opens; copies the dependency list. */
        public Proposal {
            Objects.requireNonNull(planId, "planId");
            if (planVersion < 1 || planVersion > 10000) {
                throw new IllegalArgumentException("planVersion must be 1..10000");
            }
            CanonicalValues.requireText(questTarget, "questTarget", 256);
            PreparationSupport.require(PreparationSupport.VERSION, contractVersion, "contractVersion");
            CanonicalValues.requireJsonObject(bodyJson, "bodyJson", 65536);
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
            if (dependencies.size() > 512) {
                throw new IllegalArgumentException("at most 512 dependencies");
            }
            PreparationSupport.requireJsonArray(acceptedBranchesJson, "acceptedBranchesJson", 8192);
            PreparationSupport.requireJsonArray(rejectedBranchesJson, "rejectedBranchesJson", 8192);
            PreparationSupport.requireJsonArray(reasonsJson, "reasonsJson", 8192);
            PreparationSupport.require(PreparationSupport.VERSION, validatorVersion, "validatorVersion");
            CanonicalValues.requireSha256Hex(resultDigest, "resultDigest");
            Objects.requireNonNull(references, "references");
        }

        /** Proposal that cites no campaign entity, pinned definition or author constraint. */
        public Proposal(UUID planId, int planVersion, String questTarget, String contractVersion, String bodyJson,
                List<Dependency> dependencies, String acceptedBranchesJson, String rejectedBranchesJson,
                String reasonsJson, String validatorVersion, String resultDigest) {
            this(planId, planVersion, questTarget, contractVersion, bodyJson, dependencies, acceptedBranchesJson,
                    rejectedBranchesJson, reasonsJson, validatorVersion, resultDigest, References.NONE);
        }
    }

    /**
     * Typed references a plan relies on, extracted from its body by the provisional
     * validator and rechecked by the gate. The body's own wording is never trusted to name
     * them. Each list holds at most 256 distinct values, and the sorted receipt rendering is
     * at most 32768 bytes (the V12 column bound).
     *
     * @param entityRefs campaign rows in the closed V12 grammar ({@code actor:}, {@code resource:},
     *        {@code item:}, {@code character:}, {@code known-fact:} or {@code known-quest:}
     *        followed by a lowercase UUID); each must exist and be covered by a recorded
     *        dependency on the same reference or a QUERY scope of the same name
     * @param contentRefs pinned definition identities (root IDs or {@code <packId>#<id>})
     * @param authorConstraintRefs identities of required or fixed authored entities the plan
     *        claims to preserve
     */
    public record References(List<String> entityRefs, List<String> contentRefs, List<String> authorConstraintRefs) {
        /** No references. */
        public static final References NONE = new References(List.of(), List.of(), List.of());

        /** Copy, bound and validate each list; duplicates and malformed entity references fail. */
        public References {
            entityRefs = distinct(entityRefs, "entityRefs");
            contentRefs = distinct(contentRefs, "contentRefs");
            authorConstraintRefs = distinct(authorConstraintRefs, "authorConstraintRefs");
            for (String reference : entityRefs) {
                if (!ENTITY_REFERENCE.matcher(reference).matches() && !FACT_REFERENCE.matcher(reference).matches()) {
                    throw new IllegalArgumentException("unsupported entity reference");
                }
            }
            // Record fields are assigned only after this body, so render the parameters.
            if (render(entityRefs, contentRefs, authorConstraintRefs).getBytes(StandardCharsets.UTF_8).length
                    > 32768) {
                throw new IllegalArgumentException("references exceed 32768 bytes");
            }
        }

        /** Deterministic receipt rendering with sorted lists. */
        String json() {
            return render(entityRefs, contentRefs, authorConstraintRefs);
        }

        /** Render the three lists sorted, in a fixed key order. */
        private static String render(List<String> entityRefs, List<String> contentRefs,
                List<String> authorConstraintRefs) {
            var value = new LinkedHashMap<String, Object>();
            value.put("entityRefs", new TreeSet<>(entityRefs));
            value.put("contentRefs", new TreeSet<>(contentRefs));
            value.put("authorConstraintRefs", new TreeSet<>(authorConstraintRefs));
            return JSON.writeValueAsString(value);
        }

        /** Return an immutable copy of at most 256 distinct bounded strings. */
        private static List<String> distinct(List<String> values, String name) {
            var copy = List.copyOf(Objects.requireNonNull(values, name));
            if (copy.size() > 256) {
                throw new IllegalArgumentException(name + " exceeds 256");
            }
            copy.forEach(value -> CanonicalValues.requireText(value, name, 256));
            if (new HashSet<>(copy).size() != copy.size()) {
                throw new IllegalArgumentException("duplicate " + name);
            }
            return copy;
        }
    }

    /**
     * Snapshot facts read under the admission lock.
     *
     * @param id snapshot identity bound by the attempt
     * @param contextDigest database-computed digest, stored as the receipt's source hash
     * @param audience visibility the snapshot was built for
     * @param generation campaign recovery generation at capture
     * @param contentRevision pinned content revision at capture
     * @param packHash pinned pack hash at capture
     * @param rulesetHash pinned ruleset hash at capture
     * @param authorConstraintHash author-constraint digest checked at capture
     * @param sameManifest whether the proposal kept the exact engine-recorded manifest
     */
    private record SnapshotFacts(UUID id, String contextDigest, Audience audience, long generation,
            UUID contentRevision, String packHash, String rulesetHash, String authorConstraintHash,
            boolean sameManifest) {
    }
}
