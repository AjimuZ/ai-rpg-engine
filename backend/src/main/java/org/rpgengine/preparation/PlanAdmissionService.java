package org.rpgengine.preparation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

import org.rpgengine.persistence.CanonicalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional admission of versioned quest-plan artifacts and their dependency
 * records (docs/28 section 7, second admission check). Under the campaign lock that
 * every canonical writer also takes, admission re-checks the job lease, fence,
 * generation and cancellation, the recorded query epochs (which catch phantoms) and
 * the plan predecessor/version, then stores the immutable artifact, its dependencies,
 * one admission receipt and a VALIDATED disposition atomically. A stale proposal is
 * rejected, never copied forward, and may trigger at most one fresh regeneration.
 * Admission and invalidation are metadata writes: they never advance state revision,
 * turn, world time, random draws or the event sequence, and a validated plan is
 * preparation, never canonical truth. ENTITY_FIELD, FACT and CONSTRAINT dependencies
 * fail closed until semantic evaluators arrive at P08.7;
 * dispatch-side use of this service arrives at P07.6.
 */
@Service
public class PlanAdmissionService {
    // Only quest-plan jobs produce artifacts admitted here.
    static final String QUEST_PLAN = "QUEST_PLAN";
    // Predecessor states a new version may build on; SUPERSEDED implies a version conflict.
    private static final Set<String> BUILDABLE = Set.of("VALIDATED", "PARTIALLY_INVALIDATED", "INVALIDATED");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JobRecoveryService recovery;

    /** Wire local persistence only. */
    public PlanAdmissionService(JdbcTemplate jdbc, PlatformTransactionManager manager, JobRecoveryService recovery) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        transaction = PreparationSupport.template(manager);
    }

    /**
     * Admit the result of one attempt. Idempotent per attempt: a repeated call after an
     * acceptance returns DUPLICATE with the same receipt, after a rejection the same
     * rejection. Integrity mismatches between proposal and job throw without writing.
     *
     * @param lease the lease whose attempt recorded the result
     * @param proposal backend-validated plan content and dependencies
     * @return ACCEPTED with the receipt, REJECTED with a reason code, or DUPLICATE
     * @throws IllegalStateException for stale leases or a result digest other than the recorded one
     * @throws IllegalArgumentException when the proposal does not belong to the job
     */
    public Admission admit(PreparationJobService.Lease lease, Proposal proposal) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(proposal, "proposal");
        PreparationSupport.requireNoTransaction();
        if (!recovery.isReconciled()) {
            throw new IllegalStateException("job recovery has not completed");
        }
        return transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            // NO KEY UPDATE conflicts with canonical commits (FOR UPDATE) and with share
            // locks of enqueue/snapshot/recovery, but not with foreign-key checks.
            var campaigns = jdbc.queryForList("select state_revision, recovery_generation from campaign "
                    + "where id = ? for no key update", lease.campaignId());
            if (campaigns.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            long revision = ((Number) campaigns.getFirst().get("state_revision")).longValue();
            long generation = ((Number) campaigns.getFirst().get("recovery_generation")).longValue();
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
            // Any other outcome means the attempt ended without a result to admit.
            row.requireHeldBy(lease, Set.of("RESULT_RECEIVED"));
            if (!proposal.resultDigest().equals(attempt.get("result_digest"))) {
                throw new IllegalStateException("conflicting job result");
            }
            if (!QUEST_PLAN.equals(row.kind()) || !proposal.contractVersion().equals(row.contractVersion())
                    || !proposal.questTarget().equals(row.target())) {
                throw new IllegalArgumentException("proposal does not belong to the job");
            }
            UUID snapshot = (UUID) attempt.get("snapshot_id");
            String sourceHash = jdbc.queryForObject("select context_digest from planning_snapshot "
                    + "where id = ? and campaign_id = ?", String.class, snapshot, lease.campaignId());
            // The result cannot remove or replace anything the engine supplied. JSONB
            // comparison normalizes object whitespace and key order, preserving array order.
            boolean sameManifest = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select dependency_manifest = ?::jsonb from planning_snapshot where id = ?",
                    Boolean.class, manifest(proposal.dependencies()), snapshot));
            if (!sameManifest) {
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
                    Boolean.class, snapshot));
            String reason = stalePresentation ? "STALE_PRESENTATION" : staleness(lease.campaignId(), proposal);
            if (reason != null) {
                reject(lease, row, reason);
                return new Admission(Outcome.REJECTED, null, null, reason);
            }
            return accept(lease, proposal, snapshot, sourceHash, revision, generation);
        });
    }

    /**
     * Append invalidation dispositions for current artifacts whose recorded query epochs
     * no longer match, in its own transaction under the campaign lock.
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
     * (canonical commits call this after bumping epochs). A stale ARTIFACT-severity
     * query makes the artifact INVALIDATED; stale BRANCH-severity queries only make a
     * VALIDATED artifact PARTIALLY_INVALIDATED. Prior bytes are never edited; each
     * change is one more disposition row, and an unchanged stale state adds nothing,
     * so repeated commits cannot loop.
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
                + "stale as (select l.id, l.status, l.sequence, bool_or(p.severity = 'ARTIFACT') as whole "
                + "from latest l join plan_dependency p on p.artifact_id = l.id and p.kind = 'QUERY' "
                + "left join query_epoch e on e.campaign_id = ? and e.scope_key = p.scope_key "
                + "where p.query_epoch <> coalesce(e.epoch, 0) group by l.id, l.status, l.sequence) "
                + "insert into plan_disposition (artifact_id, sequence, campaign_id, status, reason_code, "
                + "checked_revision, created_at) select id, sequence + 1, ?, case when whole then 'INVALIDATED' "
                + "else 'PARTIALLY_INVALIDATED' end, 'QUERY_EPOCH_CHANGED', ?, clock_timestamp() from stale "
                + "where whole or status = 'VALIDATED'", campaign, campaign, campaign, revision);
    }

    /**
     * Return the first staleness reason, or null when the proposal is still current:
     * every QUERY epoch must equal the current epoch, the version must be new and a
     * version above 1 must build on an admitted predecessor with the same target.
     */
    private String staleness(UUID campaign, Proposal proposal) {
        for (Dependency dependency : proposal.dependencies()) {
            // Persist the full schema now, but never label unevaluated predicates as valid.
            // Typed semantic evaluators are deliberately gated on P08.7.
            if (dependency.kind() != Kind.QUERY) {
                return "UNSUPPORTED_DEPENDENCY";
            }
            if (dependency.kind() == Kind.QUERY
                    && dependency.epoch() != QueryEpochs.current(jdbc, campaign, dependency.scope())) {
                return "STALE_QUERY_EPOCH";
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
     * Store the artifact, dependencies, receipt and dispositions, then complete the
     * attempt and job. Unique keys on version, attempt and admission key make a racing
     * duplicate fail the whole transaction instead of storing a second artifact.
     */
    private Admission accept(PreparationJobService.Lease lease, Proposal proposal, UUID snapshot, String sourceHash,
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
                proposal.contractVersion(), snapshot, proposal.bodyJson(), fingerprint, lease.jobId(),
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
                + "rejected_branches, reasons, source_hash, result_hash, admission_key, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, clock_timestamp())",
                admission, campaign, artifact, lease.jobId(), lease.attempt(), proposal.validatorVersion(), revision,
                generation, fingerprint, proposal.acceptedBranchesJson(), proposal.rejectedBranchesJson(),
                proposal.reasonsJson(), sourceHash, proposal.resultDigest(),
                PreparationSupport.sha256(lease.jobId(), lease.attempt()));
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
     * @param acceptedBranchesJson JSON array, at most 8192 bytes
     * @param rejectedBranchesJson JSON array, at most 8192 bytes
     * @param reasonsJson JSON array of validation reasons, at most 8192 bytes
     * @param validatorVersion provisional validator version
     * @param resultDigest digest recorded by the attempt result
     */
    public record Proposal(UUID planId, int planVersion, String questTarget, String contractVersion, String bodyJson,
            List<Dependency> dependencies, String acceptedBranchesJson, String rejectedBranchesJson,
            String reasonsJson, String validatorVersion, String resultDigest) {
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
        }
    }
}
