package org.rpgengine.planning;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.contract.ContractCall;
import org.rpgengine.inference.contract.ContractOutcome;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.contract.QuestPlanContext;
import org.rpgengine.inference.contract.QuestPlanContract;
import org.rpgengine.inference.contract.QuestPlanProposal;
import org.rpgengine.inference.contract.TaskKind;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.preparation.JobRequest;
import org.rpgengine.preparation.PlanAdmissionService;
import org.rpgengine.preparation.PlanAdmissionService.Dependency;
import org.rpgengine.preparation.PlanAdmissionService.Severity;
import org.rpgengine.preparation.PreparationJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Optional bounded quest planning over the durable preparation foundations (P08.7, docs/28
 * sections 4 to 7): request, run, selectively replan and look up one quest's preparation.
 * Nothing here is on the path of a player action. Ordinary commands, their canonical commits and
 * grounded narration never call this class, never wait for it and never read plan text, so a quest
 * plays from its authored or procedural baseline whether planning is disabled, queued, running,
 * failed, rejected or invalidated; {@link #standing} only reports which of those holds.
 *
 * <p><b>Run</b> ({@link #processNext}): claim one preparation job, assemble and capture the
 * privileged snapshot ({@link QuestPlanAssembler}), run the {@code quest-plan} contract (or
 * {@code quest-plan-refine} for a later version) through the fair scheduler as
 * {@link org.rpgengine.inference.scheduling.WorkClass#PREPARATION} background work with one
 * bounded repair, then convert the validated plan into an admission proposal and pass it through
 * the transactional {@link PlanAdmissionService} gate. The proposal keeps the snapshot's manifest
 * observation for observation; entries that only branch predicates cite are narrowed to those
 * branches (selective dependency validation), entries the plan never references become observed
 * only, and every other entry keeps whole-artifact severity.
 * Failure mapping: a declined plan, an unsupported target, an oversized slice and a context
 * refused by its scope guard end the job; invalid output, a stale snapshot race and runtime
 * outcomes other than completion use the job's bounded attempts; a cancellation request
 * discards any result.
 *
 * <p><b>Replan</b> ({@link #replan}): when the latest admitted version of a plan has stale
 * branches (its typed dependencies changed in a canonical commit), request one
 * {@code refine-v<version>} job. The deduplication key makes that at most one job per artifact
 * version, and {@link PlanningSettings#replanAllowance()} bounds the refinements of one plan, so
 * unrelated revisions never cause work and repeated changes cannot loop. The surviving branches
 * stay usable meanwhile; the refined version supersedes its predecessor only when admitted.
 *
 * <p>Plan identity is backend-owned: one plan per campaign and quest target ({@link #planId}),
 * versions increase by one per admitted artifact, never derived from model text.
 */
@Component
public class QuestPlanning {
    /** The only preparation-job kind this class runs. */
    static final String KIND = "QUEST_PLAN";
    /** Opaque job-level usage estimate; the scheduler reserves its own per-task estimate. */
    static final long JOB_USAGE_ESTIMATE = 20_000;
    private static final Set<String> PENDING = Set.of("QUEUED", "LEASED", "RUNNING", "CANCEL_REQUESTED",
            "RESULT_RECEIVED", "VALIDATING", "RECONCILIATION_REQUIRED");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final PreparationJobService jobs;
    private final PlanAdmissionService admissions;
    private final QuestPlanAssembler assembler;
    private final ContractRunner runner;
    private final PlanningSettings settings;
    private final Map<TaskKind, QuestPlanContract> contracts = new EnumMap<>(TaskKind.class);

    /**
     * Spring constructor: the installation's fair scheduler and {@code engine.planning.*}.
     *
     * @throws IllegalArgumentException for out-of-range planning settings
     */
    @Autowired
    public QuestPlanning(JdbcTemplate jdbc, PreparationJobService jobs, PlanAdmissionService admissions,
            QuestPlanAssembler assembler, InferenceScheduler scheduler, Environment environment) {
        this(jdbc, jobs, admissions, assembler, new ContractRunner(scheduler, Clock.systemUTC()),
                PlanningSettings.from(environment));
    }

    /**
     * Explicit runner and settings, for embedding and for suites that put a scripted port behind
     * a real scheduler.
     *
     * @param runner contract runner over the fair scheduler
     * @param settings validated planning settings
     */
    public QuestPlanning(JdbcTemplate jdbc, PreparationJobService jobs, PlanAdmissionService admissions,
            QuestPlanAssembler assembler, ContractRunner runner, PlanningSettings settings) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.settings = Objects.requireNonNull(settings, "settings");
        contracts.put(TaskKind.QUEST_PLAN, new QuestPlanContract(TaskKind.QUEST_PLAN));
        contracts.put(TaskKind.QUEST_PLAN_REFINE, new QuestPlanContract(TaskKind.QUEST_PLAN_REFINE));
    }

    /** @return the validated settings */
    public PlanningSettings settings() {
        return settings;
    }

    /** @return the stable plan identity of one quest target in one campaign */
    public static UUID planId(UUID campaign, String target) {
        return UUID.nameUUIDFromBytes(("quest-plan:" + campaign + ":" + target).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Ask for a first plan of a known quest; never waits for it. Deduplicated: asking again for
     * the same quest returns the existing job.
     *
     * @param target {@code known-quest:<uuid>}
     * @return DISABLED without any write when planning is off, otherwise REQUESTED with the job
     * @throws IllegalArgumentException for an unsupported target or unknown campaign
     */
    public Request request(UUID adventure, UUID campaign, String target) {
        if (!QuestPlanAssembler.supports(target)) {
            throw new IllegalArgumentException("unsupported plan target");
        }
        if (!settings.enabled()) {
            return new Request(Request.Outcome.DISABLED, null);
        }
        var job = jobs.enqueue(adventure, campaign, job(target, "initial",
                QuestPlanAssembler.sha256("initial|" + target)));
        return new Request(Request.Outcome.REQUESTED, job.jobId());
    }

    /**
     * Claim and run one preparation job to its admission, rejection or bounded failure. Blocks
     * the calling worker for at most the contract budget plus {@link PlanningSettings#WAIT_MARGIN};
     * call it outside any transaction, from the planning worker.
     *
     * @param holder worker identity owning the lease
     * @return empty when planning is off or nothing is claimable, otherwise what happened
     */
    public Optional<Processed> processNext(UUID holder) {
        Objects.requireNonNull(holder, "holder");
        if (!settings.enabled()) {
            return Optional.empty();
        }
        var claimed = jobs.claim(holder, settings.lease());
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        PreparationJobService.Lease lease = claimed.get();
        var job = jdbc.queryForMap("select adventure_id, kind, target, contract_version from ai_job where id = ?",
                lease.jobId());
        String target = (String) job.get("target");
        // The planner is the only consumer of ai_job; a kind or contract it cannot run fails closed.
        if (!KIND.equals(job.get("kind")) || !QuestPlanContract.JOB_CONTRACT.equals(job.get("contract_version"))
                || !QuestPlanAssembler.supports(target)) {
            return Optional.of(failed(lease, Processed.Outcome.FAILED, "UNSUPPORTED_JOB", false));
        }
        try {
            return Optional.of(run(lease, (UUID) job.get("adventure_id"), target));
        } catch (IllegalStateException lost) {
            // A state conflict (an expired or fenced lease, a retired generation, a conflicting
            // result) means this worker has no authority left: nothing more is written, and lease
            // expiry plus startup or lease recovery decide what happens to the job.
            return Optional.of(new Processed(lease.jobId(), Processed.Outcome.LEASE_LOST, "LEASE_LOST", null));
        }
    }

    /** Assemble, run, validate and admit one claimed attempt. */
    private Processed run(PreparationJobService.Lease lease, UUID adventure, String target) {
        UUID campaign = lease.campaignId();
        QuestPlanAssembler.PlanIdentity identity = identity(campaign, target);
        UUID taskId = UUID.nameUUIDFromBytes(("quest-plan-task:" + lease.jobId() + ":" + lease.attempt())
                .getBytes(StandardCharsets.UTF_8));
        QuestPlanAssembler.Assembly assembly;
        try {
            assembly = assembler.assemble(adventure, campaign, target, identity, taskId,
                    settings.maxHorizon().toSeconds());
        } catch (IllegalArgumentException unknown) {
            return failed(lease, Processed.Outcome.FAILED, "UNSUPPORTED_TARGET", false);
        } catch (IllegalStateException stale) {
            boolean tooLarge = QuestPlanAssembler.TOO_LARGE.equals(stale.getMessage());
            return failed(lease, Processed.Outcome.FAILED, tooLarge ? "CONTEXT_TOO_LARGE" : "STALE_SNAPSHOT", !tooLarge);
        }
        jobs.bindSnapshot(lease, assembly.snapshot().id());
        jobs.start(lease, settings.lease(), 0, "inference-scheduler", taskId.toString());
        QuestPlanContext context = assembly.context();
        QuestPlanContract contract = contracts.get(identity.version() == 1 ? TaskKind.QUEST_PLAN
                : TaskKind.QUEST_PLAN_REFINE);
        ContractCall<QuestPlanProposal> call;
        try {
            call = runner.start(contract, context, settings.slot(), settings.budget());
        } catch (IllegalArgumentException oversized) {
            // Rendered instructions above the task port bound: the slice cannot be planned as is.
            return failed(lease, Processed.Outcome.FAILED, "CONTEXT_TOO_LARGE", false);
        }
        ContractOutcome<QuestPlanProposal> outcome = await(call);
        if (cancelRequested(lease)) {
            // A cancellation discards whatever the planner returned; nothing is recorded.
            jobs.confirmCancelled(lease);
            return new Processed(lease.jobId(), Processed.Outcome.CANCELLED, "CANCELLED", null);
        }
        return switch (outcome) {
            case ContractOutcome.Accepted<QuestPlanProposal> accepted -> switch (accepted.value()) {
                case QuestPlanProposal.Planned planned -> admit(lease, target, identity, assembly, planned);
                case QuestPlanProposal.CannotPlan declined ->
                        failed(lease, Processed.Outcome.DECLINED, "PLANNER_DECLINED", false);
            };
            case ContractOutcome.Rejected<QuestPlanProposal> rejected ->
                    failed(lease, Processed.Outcome.INVALID, "INVALID_PLAN", true);
            case ContractOutcome.Refused<QuestPlanProposal> refused ->
                    failed(lease, Processed.Outcome.FAILED, "CONTEXT_SCOPE", false);
            case ContractOutcome.NotCompleted<QuestPlanProposal> notCompleted ->
                    notCompleted.status() == TaskResult.Status.UNAVAILABLE
                            ? failed(lease, Processed.Outcome.UNAVAILABLE, "RUNTIME_UNAVAILABLE", true)
                            : failed(lease, Processed.Outcome.FAILED, "RUNTIME_FAILED", true);
        };
    }

    /** Record the result digest and pass the converted proposal through the transactional gate. */
    private Processed admit(PreparationJobService.Lease lease, String target, QuestPlanAssembler.PlanIdentity identity,
            QuestPlanAssembler.Assembly assembly, QuestPlanProposal.Planned planned) {
        PlanAdmissionService.Proposal proposal = proposal(target, identity, assembly.manifest(), planned);
        jobs.recordResult(lease, proposal.resultDigest(), 0);
        PlanAdmissionService.Admission admission = admissions.admit(lease, proposal);
        return switch (admission.outcome()) {
            case ACCEPTED, DUPLICATE -> new Processed(lease.jobId(), Processed.Outcome.ADMITTED, null,
                    admission.artifactId());
            case REJECTED -> new Processed(lease.jobId(), Processed.Outcome.GATE_REJECTED, admission.reason(), null);
        };
    }

    /**
     * Convert a validated plan into an admission proposal: the snapshot manifest in order with
     * every observation unchanged. A root-cited entry keeps its captured whole-artifact severity;
     * an entry only branch predicates read guards exactly those (sorted) branches; an entry the
     * plan never references becomes observed only (branch severity with no branches), so a change
     * to an unrelated supplied row, such as the player's own pools, never invalidates the plan.
     */
    static PlanAdmissionService.Proposal proposal(String target, QuestPlanAssembler.PlanIdentity identity,
            List<Dependency> manifest, QuestPlanProposal.Planned planned) {
        var dependencies = new ArrayList<Dependency>();
        for (int index = 0; index < manifest.size(); index++) {
            Dependency captured = manifest.get(index);
            QuestPlanProposal.Citation citation = planned.citations().get("dep-" + index);
            if (citation != null && (citation.root() || citation.branches().isEmpty())) {
                dependencies.add(captured);
            } else {
                Set<String> branches = citation == null ? Set.of() : new TreeSet<>(citation.branches());
                dependencies.add(new Dependency(captured.kind(), captured.reference(), captured.expectedVersion(),
                        captured.predicateJson(), captured.audience(), captured.scope(), captured.epoch(),
                        captured.resultDigest(), Severity.BRANCH, JSON.writeValueAsString(branches)));
            }
        }
        var reasons = new ArrayList<Map<String, String>>();
        planned.rejectedBranches().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> reasons.add(Map.of("branchId", entry.getKey(), "reason", entry.getValue())));
        return new PlanAdmissionService.Proposal(identity.planId(), identity.version(), target,
                QuestPlanContract.JOB_CONTRACT, planned.bodyJson(), dependencies,
                JSON.writeValueAsString(planned.acceptedBranches()),
                JSON.writeValueAsString(new TreeSet<>(planned.rejectedBranches().keySet())),
                JSON.writeValueAsString(reasons), QuestPlanContract.VALIDATOR_VERSION,
                QuestPlanAssembler.sha256(planned.bodyJson()),
                new PlanAdmissionService.References(planned.entityRefs(), planned.contentRefs(),
                        planned.authorConstraintRefs()));
    }

    /**
     * Request bounded refinements for every plan of the campaign whose latest version has stale
     * branches. Unchanged plans, plans with a refinement already requested for their latest
     * version and plans whose allowance is spent are skipped.
     *
     * @return number of new refinement jobs
     */
    public int replan(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        if (!settings.enabled()) {
            return 0;
        }
        UUID adventure = jdbc.queryForObject("select adventure_id from campaign where id = ?", UUID.class, campaign);
        int created = 0;
        for (var latest : jdbc.queryForList("select distinct on (plan_id) id, plan_version, quest_target, "
                + "dependency_fingerprint from quest_plan_artifact where campaign_id = ? "
                + "order by plan_id, plan_version desc", campaign)) {
            BranchState state = branchState(campaign, (UUID) latest.get("id"));
            if (state.stale().isEmpty()) {
                continue;
            }
            String target = (String) latest.get("quest_target");
            Integer used = jdbc.queryForObject("select count(*) from ai_job j join campaign c on c.id = j.campaign_id "
                    + "where j.campaign_id = ? and j.kind = ? and j.target = ? and j.purpose like 'refine-v%' "
                    + "and j.campaign_generation = c.recovery_generation", Integer.class, campaign, KIND, target);
            String purpose = "refine-v" + latest.get("plan_version");
            if (used != null && used >= settings.replanAllowance()) {
                continue;
            }
            if (jobs.enqueue(adventure, campaign, job(target, purpose, (String) latest.get("dependency_fingerprint")))
                    .created()) {
                created++;
            }
        }
        return created;
    }

    /**
     * Report, without waiting or writing, whether a quest currently has usable preparation. A
     * branch is usable when the latest version of the plan is admitted in the current generation,
     * its latest disposition is VALIDATED or PARTIALLY_INVALIDATED, the branch was accepted and
     * never activated, and no dependency guarding it or the whole artifact is stale right now.
     * This is protected diagnostics: it carries identities only, never plan text, and no client
     * endpoint exposes it.
     *
     * @param target {@code known-quest:<uuid>}
     * @return PREPARED with the usable branches, or BASELINE with the reason play stays on baseline
     */
    public Standing standing(UUID campaign, String target) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(target, "target");
        if (!settings.enabled()) {
            return Standing.baseline("PLANNING_DISABLED");
        }
        var latest = jdbc.queryForList("select a.id, a.plan_version, d.status, ad.campaign_generation = "
                + "c.recovery_generation as current from quest_plan_artifact a join campaign c on c.id = a.campaign_id "
                + "join plan_admission ad on ad.artifact_id = a.id cross join lateral (select status from "
                + "plan_disposition where artifact_id = a.id order by sequence desc limit 1) d "
                + "where a.campaign_id = ? and a.plan_id = ? order by a.plan_version desc limit 1", campaign,
                planId(campaign, target));
        if (!latest.isEmpty()) {
            var row = latest.getFirst();
            BranchState state = branchState(campaign, (UUID) row.get("id"));
            boolean usableStatus = Set.of("VALIDATED", "PARTIALLY_INVALIDATED").contains(row.get("status"));
            if (Boolean.TRUE.equals(row.get("current")) && usableStatus && !state.usable().isEmpty()) {
                return new Standing(Standing.Mode.PREPARED, null, (UUID) row.get("id"),
                        ((Number) row.get("plan_version")).intValue(), state.usable());
            }
        }
        var job = jdbc.queryForList("select j.status, j.failure_code from ai_job j join campaign c on c.id = "
                + "j.campaign_id where j.campaign_id = ? and j.kind = ? and j.target = ? and j.campaign_generation = "
                + "c.recovery_generation order by j.created_at desc, j.id limit 1", campaign, KIND, target);
        if (!job.isEmpty() && PENDING.contains(job.getFirst().get("status"))) {
            return Standing.baseline("PLAN_PENDING");
        }
        if (!latest.isEmpty()) {
            return Standing.baseline("PLAN_INVALIDATED");
        }
        if (!job.isEmpty() && "FAILED".equals(job.getFirst().get("status"))) {
            return Standing.baseline("PLAN_FAILED");
        }
        return Standing.baseline(job.isEmpty() ? "NOT_REQUESTED" : "PLAN_UNAVAILABLE");
    }

    /** Backend-owned identity, version and refinement of the next attempt for a target. */
    private QuestPlanAssembler.PlanIdentity identity(UUID campaign, String target) {
        UUID plan = planId(campaign, target);
        var latest = jdbc.queryForList("select id, plan_version from quest_plan_artifact where campaign_id = ? "
                + "and plan_id = ? order by plan_version desc limit 1", campaign, plan);
        if (latest.isEmpty()) {
            return new QuestPlanAssembler.PlanIdentity(plan, 1, null, null);
        }
        BranchState state = branchState(campaign, (UUID) latest.getFirst().get("id"));
        return new QuestPlanAssembler.PlanIdentity(plan, ((Number) latest.getFirst().get("plan_version")).intValue() + 1,
                plan, new QuestPlanContext.Refinement(state.stale(), state.usable()));
    }

    /**
     * Live branch state of one artifact: accepted branches that a stale dependency guards (all of
     * them when a whole-artifact dependency is stale or the disposition is not usable), the
     * remaining unactivated ones as usable. Activated branches are in neither list.
     */
    private BranchState branchState(UUID campaign, UUID artifact) {
        String accepted = jdbc.queryForObject("select accepted_branches::text from plan_admission where artifact_id = ?",
                String.class, artifact);
        var branches = new LinkedHashSet<String>();
        JSON.readTree(accepted).forEach(node -> {
            if (node.isString()) {
                branches.add(node.asString());
            }
        });
        branches.removeAll(jdbc.queryForList("select branch_ref from plan_activation where artifact_id = ?",
                String.class, artifact));
        String status = jdbc.queryForObject("select status from plan_disposition where artifact_id = ? "
                + "order by sequence desc limit 1", String.class, artifact);
        var stale = new LinkedHashSet<String>();
        boolean whole = !Set.of("VALIDATED", "PARTIALLY_INVALIDATED").contains(status);
        for (var dependency : jdbc.queryForList("select severity, branch_refs::text as refs from plan_dependency p "
                + "where p.artifact_id = ? and not plan_dependency_fresh(?, p)", artifact, campaign)) {
            if ("ARTIFACT".equals(dependency.get("severity"))) {
                whole = true;
            }
            JSON.readTree((String) dependency.get("refs")).forEach(node -> {
                if (node.isString() && branches.contains(node.asString())) {
                    stale.add(node.asString());
                }
            });
        }
        if (whole) {
            stale.addAll(branches);
        }
        var usable = new ArrayList<>(branches);
        usable.removeAll(stale);
        return new BranchState(List.copyOf(stale), List.copyOf(usable));
    }

    /** Wait for the contract outcome; an expired wait cancels the call and counts as not completed. */
    private ContractOutcome<QuestPlanProposal> await(ContractCall<QuestPlanProposal> call) {
        Duration wait = settings.budget().plus(PlanningSettings.WAIT_MARGIN);
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

    /** @return whether someone requested cancellation of the leased job meanwhile */
    private boolean cancelRequested(PreparationJobService.Lease lease) {
        return "CANCEL_REQUESTED".equals(jdbc.queryForObject("select status from ai_job where id = ?", String.class,
                lease.jobId()));
    }

    /** Close the attempt with a failure code and report it. */
    private Processed failed(PreparationJobService.Lease lease, Processed.Outcome outcome, String code,
            boolean retryable) {
        jobs.fail(lease, code, retryable);
        return new Processed(lease.jobId(), outcome, code, null);
    }

    /** One preparation-job request for this contract. */
    private JobRequest job(String target, String purpose, String fingerprint) {
        String schemaHash = QuestPlanAssembler.sha256(contracts.get(TaskKind.QUEST_PLAN).outputSchema());
        return new JobRequest(KIND, target, purpose, QuestPlanContract.JOB_CONTRACT, fingerprint,
                QuestPlanAssembler.sha256(target + "|" + purpose), schemaHash, QuestPlanAssembler.POLICY_HASH,
                settings.priority(), false, Duration.ZERO, settings.timeToLive(), settings.maxAttempts(),
                JOB_USAGE_ESTIMATE, JOB_USAGE_ESTIMATE);
    }

    /**
     * Live branch state.
     *
     * @param stale accepted branches guarded by a stale dependency
     * @param usable accepted, unactivated branches with only fresh guards
     */
    private record BranchState(List<String> stale, List<String> usable) {
    }

    /**
     * Result of {@link #request}.
     *
     * @param outcome DISABLED or REQUESTED
     * @param jobId durable job identity when requested
     */
    public record Request(Outcome outcome, UUID jobId) {
        /** Request outcomes. */
        public enum Outcome {
            /** Planning is off; nothing was written and the quest plays from its baseline. */
            DISABLED,
            /** A job exists (new or deduplicated). */
            REQUESTED
        }
    }

    /**
     * Result of {@link #processNext}.
     *
     * @param jobId processed job
     * @param outcome what happened
     * @param code fixed failure or gate reason code, null when admitted
     * @param artifactId admitted artifact, null otherwise
     */
    public record Processed(UUID jobId, Outcome outcome, String code, UUID artifactId) {
        /** Processing outcomes. */
        public enum Outcome {
            /** The plan passed both admission checks and is stored. */
            ADMITTED,
            /** The transactional gate rejected a stale proposal (one regeneration may follow). */
            GATE_REJECTED,
            /** The planner answered cannot-plan. */
            DECLINED,
            /** The output stayed invalid after the single repair. */
            INVALID,
            /** No runtime was available. */
            UNAVAILABLE,
            /** Any other bounded failure. */
            FAILED,
            /** A cancellation request discarded the attempt. */
            CANCELLED,
            /** The lease was lost; another worker or recovery owns the job now. */
            LEASE_LOST
        }
    }

    /**
     * Preparation standing of one quest.
     *
     * @param mode PREPARED or BASELINE
     * @param reason why play stays on the baseline; null when prepared
     * @param artifactId latest usable artifact; null on baseline
     * @param version its plan version; null on baseline
     * @param usableBranches branches an ordinary command may cite now; empty on baseline
     */
    public record Standing(Mode mode, String reason, UUID artifactId, Integer version, List<String> usableBranches) {
        /** Freeze the branch list. */
        public Standing {
            usableBranches = List.copyOf(usableBranches);
        }

        /** @return a baseline standing with a fixed reason */
        static Standing baseline(String reason) {
            return new Standing(Mode.BASELINE, reason, null, null, List.of());
        }

        /** Standing modes. */
        public enum Mode {
            /** Admitted, fresh branches exist. */
            PREPARED,
            /** No usable preparation; the quest plays from its authored or procedural baseline. */
            BASELINE
        }
    }
}
