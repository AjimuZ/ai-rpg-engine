package org.rpgengine.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.content.CampaignDependencies;
import org.rpgengine.content.ClosureDiagnosis;
import org.rpgengine.inference.session.Audience;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restart reconciliation of derived memory (P09.5, docs/08 section 10, ADR 0005).
 *
 * <p>Unfinished operations, mutation leases, presentation jobs, inference tasks and AI-session
 * records already have their own startup reconciliation (P07, P08.6, P09.4) that runs before
 * this pass. This pass reconciles what memory derives from canonical state, campaign by campaign
 * in pages of {@value #PAGE}, and never writes canonical state (no revision, turn, world time,
 * event, journal, receipt or transcript changes):
 * <ol>
 *   <li><b>Summary jobs</b> ({@link MemorySummaries#recover}, once per pass and whether or not
 *       summaries are enabled): expired, retired-generation and rolled-back work is retired and
 *       expired leases are released.</li>
 *   <li><b>Pinned content</b> ({@link CampaignDependencies}): every campaign's pinned closure is
 *       diagnosed once per process. A {@code MISSING} closure pauses the campaign's mutation at
 *       command admission and is shown on the resume card; nothing substitutes another pack.</li>
 *   <li><b>Checkpoint</b>: the newest operational checkpoint is verified. An unusable one
 *       ({@code CORRUPT}, {@code UNSUPPORTED}, {@code INCOMPATIBLE}, {@code DIVERGED}) is discarded,
 *       newest first, until a usable or no checkpoint remains, and a new one is captured from
 *       canonical state at a safe boundary ("recover from canonical state; never fabricate a clean
 *       state"). A checkpoint that diverges from canonical state means state changed outside the
 *       command path (at the same revision) or was rolled back (ahead of the campaign); every
 *       earlier summary of the campaign is then invalidated ({@value #DIVERGED}), because no
 *       summary of that history can be trusted.</li>
 *   <li><b>Summaries</b>: a summary built from a revision or events the campaign no longer has
 *       (state rolled back without a generation change) is invalidated once ({@value #ROLLED_BACK}),
 *       so it stays stale even after the campaign grows past it again. For each audience that has
 *       summaries but no usable one, a replacement is requested when summaries are enabled.</li>
 *   <li><b>Narration</b>: committed actions still awaiting narration are counted. Queued narration
 *       resumes through its presentation job; narration that ended pending keeps its receipt and its
 *       narration-only retries. Nothing here re-runs an action or a draw.</li>
 * </ol>
 *
 * <p>Every campaign is reconciled independently: a failure is counted and logged by exception
 * class and the pass continues. The pass is derived-data maintenance, so a failure never blocks
 * startup; the safety-relevant decisions (the mutation pause, summary freshness, checkpoint
 * verification) are re-derived wherever they are used.
 */
@Service
public class MemoryReconciliation {
    /** Campaigns read per page. */
    static final int PAGE = 100;
    /** Invalidation reason after a diverged checkpoint. */
    public static final String DIVERGED = "CHECKPOINT_DIVERGED";
    /** Invalidation reason after a summary of a rolled-back history was found. */
    public static final String ROLLED_BACK = "STATE_ROLLED_BACK";
    /** Most checkpoints discarded per campaign; the configurable retention never exceeds it. */
    static final int MAX_DISCARDS = 64;
    private static final System.Logger LOG = System.getLogger(MemoryReconciliation.class.getName());
    private static final List<OperationalCheckpoints.Verification.Status> UNUSABLE = List.of(
            OperationalCheckpoints.Verification.Status.CORRUPT, OperationalCheckpoints.Verification.Status.UNSUPPORTED,
            OperationalCheckpoints.Verification.Status.INCOMPATIBLE, OperationalCheckpoints.Verification.Status.DIVERGED);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;
    private final CampaignDependencies dependencies;
    private final OperationalCheckpoints checkpoints;
    private final MemorySummaries summaries;

    /**
     * @param jdbc storage
     * @param manager transaction manager; the summary inspection reads one snapshot per campaign
     * @param dependencies pinned content diagnosis
     * @param checkpoints checkpoint verification, discard and capture
     * @param summaries summary jobs, freshness, invalidation and requests; their settings decide
     *        whether replacements are requested
     */
    public MemoryReconciliation(JdbcTemplate jdbc, PlatformTransactionManager manager, CampaignDependencies dependencies,
            OperationalCheckpoints checkpoints, MemorySummaries summaries) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(15);
    }

    /**
     * Run one full pass over every campaign.
     *
     * @return totals of the pass; per-campaign failures are counted, never thrown
     * @throws IllegalStateException inside an ambient transaction
     */
    public Report reconcile() {
        requireNoTransaction();
        var totals = new Report.Builder();
        try {
            totals.summaryJobsRetired = summaries.recover();
        } catch (RuntimeException failure) {
            totals.failures++;
            LOG.log(System.Logger.Level.WARNING, "memory reconciliation: summary job recovery failed: "
                    + failure.getClass().getName());
        }
        UUID after = null;
        while (true) {
            List<UUID> page = after == null
                    ? jdbc.queryForList("select id from campaign order by id limit ?", UUID.class, PAGE)
                    : jdbc.queryForList("select id from campaign where id > ? order by id limit ?", UUID.class, after, PAGE);
            for (UUID campaign : page) {
                try {
                    totals.add(reconcile(campaign));
                } catch (RuntimeException failure) {
                    totals.failures++;
                    LOG.log(System.Logger.Level.WARNING, "memory reconciliation failed for campaign " + campaign + ": "
                            + failure.getClass().getName());
                }
            }
            if (page.size() < PAGE) {
                break;
            }
            after = page.getLast();
        }
        Report report = totals.build();
        LOG.log(System.Logger.Level.INFO, "memory reconciliation: " + report);
        return report;
    }

    /**
     * Reconcile one campaign (steps 2–5 of the class description).
     *
     * @param campaign campaign identity
     * @return what was found and done
     * @throws IllegalArgumentException for an unknown campaign
     * @throws IllegalStateException inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public CampaignResult reconcile(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        requireNoTransaction();
        ClosureDiagnosis content = dependencies.forCampaign(campaign)
                .orElseThrow(() -> new IllegalArgumentException("unknown campaign"));
        if (content.pausesMutation()) {
            // Codes and public coordinates only; never paths, hashes or pack text.
            LOG.log(System.Logger.Level.WARNING, "campaign " + campaign + " pauses mutation: pinned content "
                    + content.issues());
        }
        CheckpointResult checkpoint = checkpoint(campaign);
        boolean rolledBack = rolledBackSummaries(campaign);
        if (rolledBack) {
            summaries.invalidate(campaign, ROLLED_BACK, List.of());
        }
        var audiences = new ArrayList<SummaryResult>();
        for (Audience audience : List.of(Audience.PUBLIC, Audience.PRIVILEGED)) {
            audiences.add(summary(campaign, audience));
        }
        int[] narration = narration(campaign);
        return new CampaignResult(campaign, content, checkpoint, rolledBack, List.copyOf(audiences), narration[0],
                narration[1]);
    }

    /** Verify, discard unusable checkpoints newest first, invalidate after a divergence, then recapture. */
    private CheckpointResult checkpoint(UUID campaign) {
        var verification = checkpoints.verify(campaign);
        OperationalCheckpoints.Verification.Status found = verification.status();
        var discarded = new ArrayList<OperationalCheckpoints.Verification.Status>();
        boolean diverged = false;
        while (UNUSABLE.contains(verification.status()) && discarded.size() < MAX_DISCARDS) {
            diverged |= verification.status() == OperationalCheckpoints.Verification.Status.DIVERGED;
            checkpoints.discard(campaign, verification.checkpointId());
            discarded.add(verification.status());
            verification = checkpoints.verify(campaign);
        }
        if (diverged) {
            // Canonical state is the authority (docs/08 section 2); summaries of the diverged history are not.
            summaries.invalidate(campaign, DIVERGED, List.of());
        }
        OperationalCheckpoints.Capture.Outcome captured = null;
        if (verification.status() == OperationalCheckpoints.Verification.Status.NONE
                || verification.status() == OperationalCheckpoints.Verification.Status.OUTDATED) {
            captured = checkpoints.capture(campaign).outcome();
            verification = checkpoints.verify(campaign);
        }
        if (!discarded.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "campaign " + campaign + ": discarded unusable checkpoints " + discarded
                    + ", now " + verification.status());
        }
        return new CheckpointResult(found, List.copyOf(discarded), diverged, captured, verification.status());
    }

    /**
     * @return true when a current-generation summary was built from a revision or events the
     *         campaign no longer has and no whole-campaign invalidation since covers it yet
     */
    private boolean rolledBackSummaries(UUID campaign) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from memory_summary m join campaign c "
                + "on c.id = m.campaign_id and c.recovery_generation = m.campaign_generation where m.campaign_id = ? and "
                + "(m.source_revision > c.state_revision or m.event_high_water > c.event_sequence) and not exists("
                + "select 1 from memory_invalidation i where i.campaign_id = m.campaign_id and i.sequence > "
                + "m.invalidation_high_water and cardinality(i.refs) = 0))", Boolean.class, campaign));
    }

    /** Classify the audience's summaries and request a replacement of a stale history when enabled. */
    private SummaryResult summary(UUID campaign, Audience audience) {
        String wire = MemorySummaries.audienceWire(audience);
        SummaryResult.State state = Objects.requireNonNull(reads.execute(status -> {
            boolean any = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from memory_summary where "
                    + "campaign_id = ? and audience = ?)", Boolean.class, campaign, wire));
            if (!any) {
                return SummaryResult.State.NONE;
            }
            return MemorySummaries.usableWithin(jdbc, campaign, wire)
                    .map(usable -> usable.freshness().status() == MemorySummaries.Freshness.Status.CURRENT
                            ? SummaryResult.State.CURRENT : SummaryResult.State.BEHIND)
                    .orElse(SummaryResult.State.STALE);
        }));
        MemorySummaries.Request.Outcome requested = null;
        if (state == SummaryResult.State.STALE && summaries.settings().summariesEnabled()) {
            requested = summaries.request(campaign, audience, true).outcome();
        }
        return new SummaryResult(audience.wire(), state, requested);
    }

    /** @return committed actions awaiting narration: {resuming through a queued or running job, ended pending} */
    private int[] narration(UUID campaign) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) filter (where active), count(*) filter (where "
                + "not active) from (select exists(select 1 from presentation_job j where j.campaign_id = o.campaign_id and "
                + "j.operation_id = o.id and j.status in ('QUEUED', 'RUNNING')) as active from operation_journal o where "
                + "o.campaign_id = ? and o.status in ('COMMITTED', 'NARRATING', 'COMMITTED_NARRATION_PENDING') and not "
                + "exists(select 1 from narration_publication p where p.campaign_id = o.campaign_id and p.operation_id = "
                + "o.id)) owed", (row, index) -> new int[] {row.getInt(1), row.getInt(2)}, campaign));
    }

    /** Checkpoint capture, verification and the diagnosis refuse a caller's transaction. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("memory reconciliation requires no ambient transaction");
        }
    }

    /**
     * Checkpoint step of one campaign.
     *
     * @param found verification status before reconciliation
     * @param discarded statuses of the discarded checkpoints, newest first
     * @param diverged whether a discarded checkpoint diverged, which invalidated the summaries
     * @param captured capture outcome when a new checkpoint was attempted, otherwise null
     * @param now verification status after reconciliation
     */
    public record CheckpointResult(OperationalCheckpoints.Verification.Status found,
            List<OperationalCheckpoints.Verification.Status> discarded, boolean diverged,
            OperationalCheckpoints.Capture.Outcome captured, OperationalCheckpoints.Verification.Status now) {
    }

    /**
     * Summary step of one audience.
     *
     * @param audience {@code public} or {@code privileged}
     * @param state the audience's summaries now
     * @param requested request outcome for a stale history when summaries are enabled, otherwise null
     */
    public record SummaryResult(String audience, State state, MemorySummaries.Request.Outcome requested) {
        /** Summary state of an audience. */
        public enum State {
            /** No summary was ever recorded. */
            NONE,
            /** The newest usable summary covers every public event. */
            CURRENT,
            /** The newest usable summary is valid and newer events exist. */
            BEHIND,
            /** Summaries exist, none is usable. */
            STALE
        }
    }

    /**
     * Result of one campaign.
     *
     * @param campaignId campaign identity
     * @param content pinned content diagnosis
     * @param checkpoint checkpoint step
     * @param rolledBack whether summaries of a rolled-back history were invalidated
     * @param summaries public then privileged summary step
     * @param narrationResuming committed actions whose narration job is queued or running
     * @param narrationPending committed actions whose narration ended pending (receipt shown, retry possible)
     */
    public record CampaignResult(UUID campaignId, ClosureDiagnosis content, CheckpointResult checkpoint,
            boolean rolledBack, List<SummaryResult> summaries, int narrationResuming, int narrationPending) {
    }

    /**
     * Totals of one pass.
     *
     * @param campaigns campaigns reconciled without failure
     * @param failures campaigns (or the job recovery) that failed and were skipped
     * @param summaryJobsRetired summary jobs changed by {@link MemorySummaries#recover}
     * @param paused campaigns whose pinned content is missing, so mutation is paused
     * @param checkpointsDiscarded unusable checkpoints discarded
     * @param checkpointsCaptured checkpoints captured from canonical state
     * @param invalidations whole-campaign summary invalidations after divergence or rollback
     * @param staleAudiences audiences with summaries but no usable one
     * @param summariesRequested replacement summary jobs created
     * @param narrationResuming committed actions whose narration resumes through a job
     * @param narrationPending committed actions whose narration ended pending
     */
    public record Report(int campaigns, int failures, int summaryJobsRetired, int paused, int checkpointsDiscarded,
            int checkpointsCaptured, int invalidations, int staleAudiences, int summariesRequested, int narrationResuming,
            int narrationPending) {
        /** Mutable accumulator of a pass. */
        private static final class Builder {
            private int campaigns;
            private int failures;
            private int summaryJobsRetired;
            private int paused;
            private int checkpointsDiscarded;
            private int checkpointsCaptured;
            private int invalidations;
            private int staleAudiences;
            private int summariesRequested;
            private int narrationResuming;
            private int narrationPending;

            /** Add one campaign's result. */
            void add(CampaignResult result) {
                campaigns++;
                paused += result.content().pausesMutation() ? 1 : 0;
                checkpointsDiscarded += result.checkpoint().discarded().size();
                checkpointsCaptured += result.checkpoint().captured() == OperationalCheckpoints.Capture.Outcome.CAPTURED
                        ? 1 : 0;
                invalidations += (result.checkpoint().diverged() ? 1 : 0) + (result.rolledBack() ? 1 : 0);
                for (SummaryResult summary : result.summaries()) {
                    staleAudiences += summary.state() == SummaryResult.State.STALE ? 1 : 0;
                    summariesRequested += summary.requested() == MemorySummaries.Request.Outcome.REQUESTED ? 1 : 0;
                }
                narrationResuming += result.narrationResuming();
                narrationPending += result.narrationPending();
            }

            /** @return the immutable totals */
            Report build() {
                return new Report(campaigns, failures, summaryJobsRetired, paused, checkpointsDiscarded,
                        checkpointsCaptured, invalidations, staleAudiences, summariesRequested, narrationResuming,
                        narrationPending);
            }
        }
    }
}
