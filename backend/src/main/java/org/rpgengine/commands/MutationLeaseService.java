package org.rpgengine.commands;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database-only campaign mutation permits for trusted internal workers. Inputs are
 * scoped journal identities and opaque worker tokens; outputs are detached grants.
 * Busy, expired, stale or out-of-scope requests fail without changing canonical data.
 * Grants and renewals lock owner before campaign/journal for owner-wide admission;
 * release locks campaign then journal, matching commit and draw reservation.
 * There is no scheduler, automatic renewal, network wait or public authorization API.
 */
@Service
public class MutationLeaseService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /** Wire the shared transaction manager and JDBC connection; missing dependencies fail startup. */
    public MutationLeaseService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Acquire at most one permit per campaign for an eligible current operation.
     * Matching live retries return the existing grant without extending its deadline;
     * expired/released permits get a strictly larger fence. Duration is 1..300 whole
     * seconds. A conflicting live owner, stale revision or terminal journal fails.
     * New/reacquired permits also enforce the shared two-live-permits owner cap.
     */
    public Grant acquire(UUID adventure, UUID campaign, UUID operation, UUID holder, Duration duration) {
        Objects.requireNonNull(holder, "holder");
        long seconds = seconds(duration);
        requireNoTransaction();
        return transaction.execute(status -> {
            UUID owner = OwnerMutationAdmission.lockCampaignOwner(jdbc, adventure, campaign);
            lockEligible(adventure, campaign, operation);
            jdbc.update("insert into campaign_mutation_lease (campaign_id) values (?) on conflict do nothing", campaign);
            var live = jdbc.query("select holder_id, fence, expires_at from campaign_mutation_lease "
                            + "where campaign_id = ? and expires_at > clock_timestamp()",
                    (row, index) -> new Grant(new Token(row.getObject(1, UUID.class), row.getLong(2)),
                            row.getTimestamp(3).toInstant()), campaign);
            if (!live.isEmpty()) {
                var grant = live.getFirst();
                if (!grant.token().holderId().equals(holder) || !Boolean.TRUE.equals(jdbc.queryForObject(
                        "select operation_id = ? from campaign_mutation_lease where campaign_id = ?",
                        Boolean.class, operation, campaign))) {
                    throw new IllegalStateException("campaign mutation is busy");
                }
                return grant;
            }
            OwnerMutationAdmission.requireCapacity(jdbc, owner);
            return jdbc.queryForObject("update campaign_mutation_lease set fence = fence + 1, operation_id = ?, "
                            + "holder_id = ?, expires_at = clock_timestamp() + (? * interval '1 second') "
                            + "where campaign_id = ? returning fence, expires_at",
                    (row, index) -> new Grant(new Token(holder, row.getLong(1)), row.getTimestamp(2).toInstant()),
                    operation, holder, seconds, campaign);
        });
    }

    /** Renew a still-live exact permit using database time; expired permits cannot be resurrected by renewal. */
    public Grant renew(UUID adventure, UUID campaign, UUID operation, Token token, Duration duration) {
        Objects.requireNonNull(token, "token");
        long seconds = seconds(duration);
        requireNoTransaction();
        return transaction.execute(status -> {
            // Serialize with admissions: a renewal cannot revive a slot already counted as expired.
            OwnerMutationAdmission.lockCampaignOwner(jdbc, adventure, campaign);
            lockEligible(adventure, campaign, operation);
            var grants = jdbc.query("update campaign_mutation_lease set expires_at = greatest(expires_at, "
                            + "clock_timestamp() + (? * interval '1 second')) where campaign_id = ? "
                            + "and operation_id = ? and holder_id = ? and fence = ? and expires_at > clock_timestamp() "
                            + "returning expires_at",
                    (row, index) -> new Grant(token, row.getTimestamp(1).toInstant()),
                    seconds, campaign, operation, token.holderId(), token.fence());
            if (grants.isEmpty()) {
                throw new IllegalStateException("mutation lease lost");
            }
            return grants.getFirst();
        });
    }

    /**
     * Release an exact permit, including after cancellation/expiry. A stale token
     * returns false and cannot release a successor's lease; scope/SQL errors propagate.
     */
    public boolean release(UUID adventure, UUID campaign, UUID operation, Token token) {
        Objects.requireNonNull(token, "token");
        requireNoTransaction();
        return Boolean.TRUE.equals(transaction.execute(status -> {
            lockScope(jdbc, adventure, campaign, operation);
            return releaseHeld(jdbc, campaign, operation, token);
        }));
    }

    /** Check duration before opening a transaction; reject fractions, zero and excessive worker deadlines. */
    private static long seconds(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.getNano() != 0 || duration.getSeconds() < 1 || duration.getSeconds() > 300) {
            throw new IllegalArgumentException("lease duration must be 1..300 whole seconds");
        }
        return duration.getSeconds();
    }

    /** Reject joined/suspended caller work so no permit is returned before its own COMMIT. */
    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("lease/recovery requires no ambient transaction");
        }
    }

    /** Lock scoped rows with bounded waits; reject stale or non-precommit operations without modifying them. */
    private void lockEligible(UUID adventure, UUID campaign, UUID operation) {
        lockScope(jdbc, adventure, campaign, operation);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select o.base_revision = c.state_revision "
                        + "and o.status in ('ACCEPTED', 'PLANNING', 'VALIDATING') "
                        + "from operation_journal o join campaign c on c.id = o.campaign_id "
                        + "where c.id = ? and o.id = ?", Boolean.class, campaign, operation))) {
            throw new IllegalStateException("stale or ineligible lease operation");
        }
    }

    /**
     * Acquire the shared campaign-before-journal locks inside the caller's transaction, with the
     * 2-second lock and 5-second statement bounds. Every journal writer (admission, execution,
     * cancellation, recovery and post-commit presentation) uses this order before touching lease
     * or job rows; missing or mismatched identities fail closed.
     *
     * @throws org.springframework.dao.EmptyResultDataAccessException for an out-of-scope identity
     * @throws org.springframework.dao.DataAccessException for lock or statement timeouts
     */
    public static void lockScope(JdbcTemplate jdbc, UUID adventure, UUID campaign, UUID operation) {
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(operation, "operation");
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '5s'");
        jdbc.queryForObject("select id from campaign where id = ? and adventure_id = ? for update",
                UUID.class, campaign, adventure);
        jdbc.queryForObject("select id from operation_journal where id = ? and campaign_id = ? for update",
                UUID.class, operation, campaign);
    }

    /**
     * Validate under the caller's campaign/journal locks before any new effects.
     * Expiry uses current database time, not transaction start or the worker's clock.
     * A takeover cannot pass the campaign lock until this bounded transaction ends.
     */
    static void requireHeld(JdbcTemplate jdbc, ResolvedCommit.Context context) {
        var token = context.lease();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from campaign_mutation_lease "
                        + "where campaign_id = ? and operation_id = ? and holder_id = ? and fence = ? "
                        + "and expires_at > clock_timestamp())", Boolean.class,
                context.campaignId(), context.operationId(), token.holderId(), token.fence()))) {
            throw new IllegalStateException("mutation lease lost");
        }
    }

    /**
     * Retire whatever permit this operation holds, without its token, inside a transaction that
     * already holds the campaign and journal locks (P07.5 cancellation). The fence advances, so a
     * worker still holding the old token can neither draw, commit nor renew afterwards.
     *
     * @return true if a permit belonged to the operation and was retired
     */
    static boolean releaseOperation(JdbcTemplate jdbc, UUID campaign, UUID operation) {
        return jdbc.update("update campaign_mutation_lease set fence = fence + 1, operation_id = null, "
                + "holder_id = null, expires_at = null where campaign_id = ? and operation_id = ?",
                campaign, operation) == 1;
    }

    /** Clear only the matching permit inside a locked transaction; keep/increment its high-water fence. */
    static boolean releaseHeld(JdbcTemplate jdbc, UUID campaign, UUID operation, Token token) {
        return jdbc.update("update campaign_mutation_lease set fence = fence + 1, operation_id = null, "
                        + "holder_id = null, expires_at = null where campaign_id = ? and operation_id = ? "
                        + "and holder_id = ? and fence = ?", campaign, operation, token.holderId(), token.fence()) == 1;
    }

    /** Opaque worker identity and positive high-water fence; malformed tokens fail before database access. */
    public record Token(UUID holderId, long fence) {
        /** Validate a detached capability; possession does not replace owner/campaign authorization. */
        public Token {
            Objects.requireNonNull(holderId, "holderId");
            if (fence <= 0) {
                throw new IllegalArgumentException("fence must be positive");
            }
        }
    }

    /** Detached permit and database deadline; renewal is explicit and never promises an upstream task stopped. */
    public record Grant(Token token, Instant expiresAt) {
    }
}
