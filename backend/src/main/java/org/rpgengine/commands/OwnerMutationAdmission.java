package org.rpgengine.commands;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared database admission guard for all campaign permit grants and renewals.
 * Callers hold its owner lock until commit, before taking campaign/journal/lease
 * locks. Releases and canonical commits only remove permits and need no owner lock;
 * they must never acquire it after locking a campaign. This ordering bounds live
 * permits across adventures without an in-memory semaphore or background scheduler.
 */
final class OwnerMutationAdmission {
    /** Owner-wide live mutation permits; independent of the inference job concurrency policy. */
    static final int MAX_ACTIVE = 2;

    /** Transaction utility, not a service with its own transaction or cached counts. */
    private OwnerMutationAdmission() {
    }

    /** Lock an existing owner before any campaign; NO KEY UPDATE permits unrelated FK checks. */
    static void lockOwner(JdbcTemplate jdbc, UUID owner) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '5s'");
        if (jdbc.query("select id from identity_owner_profile where id = ? for no key update",
                (row, index) -> row.getObject(1, UUID.class), owner).isEmpty()) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE);
        }
    }

    /** Resolve immutable campaign scope and lock its owner for trusted internal lease callers. */
    static UUID lockCampaignOwner(JdbcTemplate jdbc, UUID adventure, UUID campaign) {
        jdbc.execute("set local statement_timeout = '5s'");
        var owners = jdbc.query("select a.owner_id from campaign c join adventure_workspace a "
                        + "on a.id = c.adventure_id where c.id = ? and a.id = ?",
                (row, index) -> row.getObject(1, UUID.class), campaign, adventure);
        if (owners.isEmpty()) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE);
        }
        UUID owner = owners.getFirst();
        lockOwner(jdbc, owner);
        return owner;
    }

    /**
     * Check capacity only for a new/reacquired permit, under the owner lock. Matching
     * live retries/renewals already occupy a slot and must not reject at the cap.
     * Database time decides liveness; expired slots are available without erasing evidence.
     */
    static void requireCapacity(JdbcTemplate jdbc, UUID owner) {
        Integer active = jdbc.queryForObject("select count(*) from campaign_mutation_lease l "
                        + "join campaign c on c.id = l.campaign_id join adventure_workspace a on a.id = c.adventure_id "
                        + "where a.owner_id = ? and l.expires_at > clock_timestamp()", Integer.class, owner);
        if (active == null || active >= MAX_ACTIVE) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.ADMISSION_LIMIT);
        }
    }
}
