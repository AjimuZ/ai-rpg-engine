package org.rpgengine.preparation;

import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Package-private view of one job row read under its row lock, with lease liveness,
 * deadline and generation evaluated by the database clock in the same statement.
 * Callers must already hold any campaign lock they need (lock order: campaign, job).
 *
 * @param status current lifecycle status
 * @param holder current lease holder or null
 * @param fence current fence high-water mark
 * @param attempts consumed attempt count (the active attempt's number while leased)
 * @param maxAttempts total attempt allowance
 * @param regenerations stale-world regenerations already consumed
 * @param leaseLive whether a lease exists and has not expired
 * @param unexpired whether the job's own deadline has not passed
 * @param currentGeneration whether the job belongs to the campaign's current generation
 * @param cancelRequested whether cancellation was ever requested
 * @param kind job kind
 * @param target logical target
 * @param contractVersion task contract version
 * @param policyHash effective policy hash
 * @param snapshot snapshot bound by the latest attempt, or null
 */
record JobRow(String status, UUID holder, long fence, int attempts, int maxAttempts, int regenerations,
        boolean leaseLive, boolean unexpired, boolean currentGeneration, boolean cancelRequested, String kind,
        String target, String contractVersion, String policyHash, UUID snapshot) {
    // Shared release assignment: every lease release consumes one fence so the old
    // holder's token can never match again (V6 ai_job_fence rule).
    static final String RELEASE = "fence = fence + 1, lease_holder = null, lease_expires_at = null, "
            + "row_version = row_version + 1, updated_at = clock_timestamp()";

    /**
     * Lock one job of one campaign and return its state; an unknown or foreign job
     * fails with IllegalArgumentException so cross-campaign identifiers reveal nothing.
     */
    static JobRow lock(JdbcTemplate jdbc, UUID campaign, UUID job) {
        var rows = jdbc.query("select j.status, j.lease_holder, j.fence, j.attempt_count, j.max_attempts, "
                        + "j.regeneration_count, coalesce(j.lease_expires_at > clock_timestamp(), false), "
                        + "j.expires_at > clock_timestamp(), j.campaign_generation = c.recovery_generation, "
                        + "j.cancel_requested_at is not null, j.kind, j.target, j.contract_version, j.policy_hash, "
                        + "j.snapshot_id from ai_job j join campaign c on c.id = j.campaign_id "
                        + "where j.id = ? and j.campaign_id = ? for update of j",
                (row, index) -> new JobRow(row.getString(1), row.getObject(2, UUID.class), row.getLong(3),
                        row.getInt(4), row.getInt(5), row.getInt(6), row.getBoolean(7), row.getBoolean(8),
                        row.getBoolean(9), row.getBoolean(10), row.getString(11), row.getString(12),
                        row.getString(13), row.getString(14), row.getObject(15, UUID.class)), job, campaign);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("unknown job");
        }
        return rows.getFirst();
    }

    /**
     * Lock the lease's job and require that the lease is still the current one: same
     * holder, fence and attempt, unexpired, current campaign generation and one of the
     * allowed statuses. Any mismatch is a lost lease and fails without writing.
     */
    static JobRow requireLease(JdbcTemplate jdbc, PreparationJobService.Lease lease, Set<String> allowed) {
        // Hold the generation stable through the write, in campaign/job lock order.
        jdbc.queryForObject("select id from campaign where id = ? for share", UUID.class, lease.campaignId());
        return lock(jdbc, lease.campaignId(), lease.jobId()).requireHeldBy(lease, allowed);
    }

    /** Check an already locked row against a lease (same rules as {@link #requireLease}); returns this row. */
    JobRow requireHeldBy(PreparationJobService.Lease lease, Set<String> allowed) {
        if (!lease.holder().equals(holder) || fence != lease.fence() || attempts != lease.attempt() || !leaseLive
                || !unexpired || !currentGeneration || !allowed.contains(status)) {
            throw new IllegalStateException("stale job lease");
        }
        return this;
    }

    /**
     * Whether a released job may be leased again: attempts and deadline remain and
     * nobody asked to cancel it. Retries never reset the attempt count.
     */
    boolean retryable() {
        return attempts < maxAttempts && unexpired && !cancelRequested;
    }
}
