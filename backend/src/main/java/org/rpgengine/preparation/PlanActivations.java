package org.rpgengine.preparation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.persistence.CanonicalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Use of admitted plan branches by an ordinary canonical command (docs/28 section 7, "at
 * actual use"; QA-36). A plan never changes world facts, time, rewards or draws: the trusted
 * resolver builds every effect of the command, and the command's canonical commit only cites
 * the branches it used. Inside that commit, under the campaign lock held by every canonical
 * writer and after the command's own exact revision, lease and rule checks, this class
 * re-evaluates the cited artifact against current rows and records one immutable
 * {@code plan_activation} row per branch in the same transaction.
 *
 * <p>Checks, in order: the artifact belongs to the campaign and passed the P07.6 gate; its
 * admission's recovery generation is current; its latest disposition is VALIDATED or
 * PARTIALLY_INVALIDATED; each cited branch is an accepted branch; no cited branch was already
 * activated; and every dependency that guards the whole artifact or a cited branch is fresh
 * now. A plan validated earlier therefore never substitutes for the command's second check:
 * if state changed after admission, this check wins and the whole commit rolls back. A
 * matching retry of a committed command replays its receipt before reaching this class.
 * These static helpers require an existing transaction and never commit one themselves.
 */
public final class PlanActivations {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Static helpers only; callers supply the transaction-bound JdbcTemplate. */
    private PlanActivations() {
    }

    /**
     * Require that the cited branches are usable right now. Reads only.
     *
     * @param campaign campaign whose lock the caller already holds
     * @param request cited artifact and branches
     * @throws Rejected with a stable reason code when the plan cannot be used; the caller
     *         must roll back the whole command
     * @throws IllegalStateException outside a transaction
     */
    public static void requireUsable(JdbcTemplate jdbc, UUID campaign, Request request) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(request, "request");
        PreparationSupport.requireTransaction();
        var rows = jdbc.queryForList("select ad.gate_version, ad.campaign_generation = c.recovery_generation "
                + "as current_generation, d.status from quest_plan_artifact a join campaign c on c.id = a.campaign_id "
                + "join plan_admission ad on ad.artifact_id = a.id cross join lateral (select status from "
                + "plan_disposition where artifact_id = a.id order by sequence desc limit 1) d "
                + "where a.id = ? and a.campaign_id = ?", request.artifactId(), campaign);
        // A foreign artifact is indistinguishable from an unknown one.
        if (rows.isEmpty()) {
            throw new Rejected("PLAN_UNKNOWN");
        }
        var row = rows.getFirst();
        // Artifacts admitted before the transactional gate never had scope/content/author checks.
        if (row.get("gate_version") == null) {
            throw new Rejected("PLAN_NOT_USABLE");
        }
        if (!Boolean.TRUE.equals(row.get("current_generation"))) {
            throw new Rejected("PLAN_GENERATION_RETIRED");
        }
        Object status = row.get("status");
        if (!"VALIDATED".equals(status) && !"PARTIALLY_INVALIDATED".equals(status)) {
            throw new Rejected("PLAN_NOT_USABLE");
        }
        for (String branch : request.branchRefs()) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select accepted_branches @> jsonb_build_array(?::text) "
                    + "from plan_admission where artifact_id = ?", Boolean.class, branch, request.artifactId()))) {
                throw new Rejected("PLAN_BRANCH_NOT_ACCEPTED");
            }
        }
        Integer used = jdbc.queryForObject("select count(*) from plan_activation where artifact_id = ? "
                + "and branch_ref in (select jsonb_array_elements_text(?::jsonb))", Integer.class,
                request.artifactId(), request.branchesJson());
        if (used != null && used > 0) {
            throw new Rejected("PLAN_BRANCH_ALREADY_ACTIVATED");
        }
        // Re-evaluate live, never trust a disposition: an ARTIFACT dependency guards every
        // branch; a BRANCH dependency guards the string branch references it lists.
        Integer stale = jdbc.queryForObject("select count(*) from plan_dependency p where p.artifact_id = ? "
                + "and p.campaign_id = ? and not plan_dependency_fresh(?, p) and (p.severity = 'ARTIFACT' "
                + "or exists (select 1 from jsonb_array_elements(p.branch_refs) b "
                + "where jsonb_typeof(b) = 'string' and b <@ ?::jsonb))", Integer.class, request.artifactId(),
                campaign, campaign, request.branchesJson());
        if (stale != null && stale > 0) {
            throw new Rejected("PLAN_DEPENDENCY_STALE");
        }
    }

    /**
     * Record the cited branches as used by one command, inside its canonical transaction
     * after the journal outcome was written; a deferred V12 trigger proves at COMMIT that
     * the operation committed at exactly {@code committedRevision}. The primary key rejects
     * a concurrent second use of the same branch.
     *
     * @param operation committing operation
     * @param committedRevision revision the operation commits at
     */
    public static void recordWithin(JdbcTemplate jdbc, UUID campaign, UUID operation, long committedRevision,
            Request request) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(request, "request");
        CanonicalValues.requirePositive(committedRevision, "committedRevision");
        PreparationSupport.requireTransaction();
        for (String branch : request.branchRefs()) {
            jdbc.update("insert into plan_activation (artifact_id, branch_ref, campaign_id, operation_id, "
                    + "committed_revision, created_at) values (?, ?, ?, ?, ?, clock_timestamp())",
                    request.artifactId(), branch, campaign, operation, committedRevision);
        }
    }

    /**
     * Branches of one admitted artifact that a command used.
     *
     * @param artifactId admitted artifact of the command's campaign
     * @param branchRefs one to eight distinct accepted branch references, 1..128 characters each
     */
    public record Request(UUID artifactId, List<String> branchRefs) {
        /** Copy and bound the branch list; duplicates and empty lists fail before any lock. */
        public Request {
            Objects.requireNonNull(artifactId, "artifactId");
            branchRefs = List.copyOf(Objects.requireNonNull(branchRefs, "branchRefs"));
            if (branchRefs.isEmpty() || branchRefs.size() > 8) {
                throw new IllegalArgumentException("an activation cites 1..8 branches");
            }
            branchRefs.forEach(branch -> CanonicalValues.requireText(branch, "branchRef", 128));
            if (new HashSet<>(branchRefs).size() != branchRefs.size()) {
                throw new IllegalArgumentException("duplicate branch reference");
            }
        }

        /** JSON array of the cited branches, for set comparisons in SQL. */
        String branchesJson() {
            return JSON.writeValueAsString(branchRefs);
        }
    }

    /** A plan use that the canonical commit must refuse; the reason is a stable internal code. */
    public static final class Rejected extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        /** Create a rejection with its reason code, also used as the message. */
        Rejected(String reason) {
            super("plan activation rejected: " + reason);
            this.reason = reason;
        }

        /** Stable reason code such as {@code PLAN_DEPENDENCY_STALE}. */
        public String reason() {
            return reason;
        }
    }
}
