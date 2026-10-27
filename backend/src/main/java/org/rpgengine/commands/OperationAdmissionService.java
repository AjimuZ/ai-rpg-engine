package org.rpgengine.commands;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable command admission (docs/07 §3) and owner-scoped operation lookup.
 *
 * <p>One bounded READ COMMITTED transaction locks the owner, then the scoped campaign
 * (the shared campaign-before-journal-before-lease order), and either replays the journal row already stored for the request identity,
 * rejects the request, or journals a new {@link OperationStatus#ACCEPTED} operation and
 * grants it the campaign mutation lease with a fresh fence in the same commit. Because
 * acceptance and lease grant are atomic, no competing admitter can slip between them,
 * and a crash leaves either nothing or an operation that already owns its permit.
 *
 * <p>Rejections are deliberately not journaled: stale, busy, limited and conflicting
 * requests consume nothing and may be retried with a new request identity. Identical
 * retries are recognized before any stale/busy/limit check, so a lost HTTP response is
 * always answered with the original operation even after the campaign moved on. Command
 * semantics use a closed typed vocabulary and current mode/decision/player checks. This
 * service stores only the canonical hash, never the payload itself; resolution is separate.
 */
@Service
public class OperationAdmissionService {
    /** Inclusive canonical command size; HTTP bodies are capped lower, this guards direct callers. */
    public static final int MAX_CANONICAL_BYTES = 32_768;
    /**
     * Owner-wide limit of concurrently leased campaign operations. Each campaign already
     * admits one action at a time; this bounds parallel work across an owner's campaigns
     * while the narration queue keeps its own conservative single-active-turn default.
     */
    public static final int MAX_ACTIVE_PER_OWNER = OwnerMutationAdmission.MAX_ACTIVE;
    /**
     * Initial permit length, matching the docs/06 interactive planning budget. Workers
     * renew through {@link MutationLeaseService}; an abandoned permit expires and its
     * operation fails before commit at the next admission or startup reconciliation.
     */
    public static final Duration LEASE = Duration.ofSeconds(180);
    /** Upper-case envelope syntax; the closed typed registry is checked after matching replay. */
    private static final Pattern COMMAND_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate reads;

    /**
     * Wire local persistence only; missing dependencies fail application startup.
     *
     * @param entities JPA context used for the journal entity and its version checks
     * @param jdbc JDBC access on the same transactional connection for locks and leases
     * @param manager shared transaction manager
     */
    public OperationAdmissionService(EntityManager entities, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(10);
    }

    /**
     * Admit one command or replay its earlier admission.
     *
     * <p>Order of checks (all under the campaign lock): scope, request identity
     * (replay/conflict), expected revision, typed/common semantic validation, retirement
     * of expired permits and pre-commit operations without a live permit, campaign busy, owner admission limit,
     * then journal insert plus lease grant.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign target campaign
     * @param command structurally validated envelope; typed meaning is checked atomically for new requests
     * @return the durable operation view; a new admission also carries its lease grant
     * @throws OperationAdmissionFailure for scope, conflict, stale, typed/semantic, busy or limit outcomes
     * @throws IllegalArgumentException if the command cannot be canonicalized within bounds
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for lock/statement timeouts or storage failures
     */
    public Admission submit(UUID owner, UUID adventure, UUID campaign, Command command) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(command, "command");
        // Hash before opening the transaction so CPU work never extends lock hold time.
        byte[] canonical = CommandCanonicalForm.encode(command.commandType(),
                command.expectedRevision(), command.payload(), MAX_CANONICAL_BYTES);
        String hash = CommandCanonicalForm.sha256(canonical);
        // The permit must be durable before the caller can act on it (same rule as MutationLeaseService).
        MutationLeaseService.requireNoTransaction();
        return transaction.execute(status -> admit(owner, adventure, campaign, command, hash, canonical));
    }

    /**
     * Read the current status of one operation in an owned campaign. This is the safe
     * retry/reconnect path: it never changes state, leases or counters.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign that must own the operation
     * @param operation operation identity
     * @return the current operation view
     * @throws OperationAdmissionFailure with {@code SCOPE_UNAVAILABLE} for missing or foreign identities
     */
    public OperationView status(UUID owner, UUID adventure, UUID campaign, UUID operation) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(operation, "operation");
        MutationLeaseService.requireNoTransaction();
        return reads.execute(status -> {
            jdbc.execute("set local statement_timeout = '5s'");
            // One owner-scoped join: a foreign owner, adventure or campaign is indistinguishable from absence.
            List<UUID> found = jdbc.query("select o.id from operation_journal o join campaign c on c.id = o.campaign_id "
                            + "join adventure_workspace a on a.id = c.adventure_id where o.id = ? and c.id = ? "
                            + "and c.adventure_id = ? and a.owner_id = ?",
                    (row, index) -> row.getObject(1, UUID.class), operation, campaign, adventure, owner);
            if (found.isEmpty()) {
                throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE);
            }
            return OperationView.of(entities.find(CampaignOperation.class, found.getFirst()));
        });
    }

    /** Transaction body of {@link #submit}; any exception rolls back every write made here. */
    private Admission admit(UUID owner, UUID adventure, UUID campaign, Command command, String hash, byte[] canonical) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '5s'");
        OwnerMutationAdmission.lockOwner(jdbc, owner);
        List<Long> revision = jdbc.query("select c.state_revision from campaign c join adventure_workspace a "
                        + "on a.id = c.adventure_id where c.id = ? and c.adventure_id = ? and a.owner_id = ? for update of c",
                (row, index) -> row.getLong(1), campaign, adventure, owner);
        if (revision.isEmpty()) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE);
        }

        // Idempotency comes first: the campaign lock makes this lookup and the later
        // insert atomic, so concurrent duplicates observe exactly one stored row.
        List<UUID> existing = jdbc.query("select id from operation_journal where campaign_id = ? and idempotency_key = ?",
                (row, index) -> row.getObject(1, UUID.class), campaign, command.requestId());
        if (!existing.isEmpty()) {
            CampaignOperation stored = entities.find(CampaignOperation.class, existing.getFirst());
            if (!stored.matchesPayload(hash)) {
                throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.REQUEST_CONFLICT);
            }
            return new Admission(OperationView.of(stored), true, null);
        }

        if (revision.getFirst() != command.expectedRevision()) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.STALE_REVISION);
        }

        // Decode the frozen hash input, never a mutable map that may have changed while
        // waiting for locks. Replay above intentionally preserves historical admissions.
        TypedCommand intention = TypedCommand.decode(canonical);
        CommandSemantics.requireEligible(jdbc, owner, adventure, campaign, intention);

        Instant now = jdbc.queryForObject("select clock_timestamp()", (row, index) -> row.getTimestamp(1).toInstant());
        // One database cutoff classifies both cleanup and busy. If a lease lapses
        // between those statements, treating it as busy is conservative; a subsequent
        // request can retire it. Mixing clock reads could overwrite the lease after
        // cleanup skipped its still-live journal, stranding that operation as active.
        retireAbandoned(campaign, now);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from campaign_mutation_lease "
                + "where campaign_id = ? and expires_at > ?)", Boolean.class, campaign, Timestamp.from(now)))) {
            // Busy is rejected, never queued behind a future state the caller has not seen.
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.CAMPAIGN_BUSY);
        }
        OwnerMutationAdmission.requireCapacity(jdbc, owner);

        CampaignOperation operation = CampaignOperation.accept(campaign, command.requestId(), hash,
                command.commandType(), command.expectedRevision(), now);
        entities.persist(operation);
        // The lease row references (campaign_id, operation_id) in the journal, so flush first.
        entities.flush();
        UUID holder = UUID.randomUUID();
        jdbc.update("insert into campaign_mutation_lease (campaign_id) values (?) on conflict do nothing", campaign);
        var grant = jdbc.queryForObject("update campaign_mutation_lease set fence = fence + 1, operation_id = ?, "
                        + "holder_id = ?, expires_at = clock_timestamp() + (? * interval '1 second') "
                        + "where campaign_id = ? returning fence, expires_at",
                (row, index) -> new MutationLeaseService.Grant(new MutationLeaseService.Token(holder, row.getLong(1)),
                        row.getTimestamp(2).toInstant()),
                operation.getId(), holder, LEASE.getSeconds(), campaign);
        return new Admission(OperationView.of(operation), false, grant);
    }

    /**
     * Retire expired permits, then fail every pre-commit operation of this campaign that no
     * longer owns a live permit. Since admission grants the permit atomically, such rows
     * are abandoned work (expired, released or legacy); leaving them ACCEPTED would make
     * them look alive forever. Committed outcomes and narration states are untouched here;
     * startup reconciliation remains responsible for presentation recovery. The supplied
     * database cutoff is shared with the subsequent busy check, so one lease cannot be
     * classified as live for cleanup but expired for takeover within this admission.
     */
    private void retireAbandoned(UUID campaign, Instant now) {
        // Keep the high-water row and advance its fence so late callbacks of the old holder fail.
        jdbc.update("update campaign_mutation_lease set fence = fence + 1, operation_id = null, holder_id = null, "
                + "expires_at = null where campaign_id = ? and expires_at <= ?", campaign, Timestamp.from(now));
        List<UUID> abandoned = jdbc.query("select o.id from operation_journal o where o.campaign_id = ? "
                        + "and o.status in ('ACCEPTED', 'PLANNING', 'VALIDATING', 'COMMITTING') "
                        + "and not exists(select 1 from campaign_mutation_lease l where l.campaign_id = o.campaign_id "
                        + "and l.operation_id = o.id and l.expires_at > ?) order by o.id for update of o",
                (row, index) -> row.getObject(1, UUID.class), campaign, Timestamp.from(now));
        for (UUID id : abandoned) {
            entities.find(CampaignOperation.class, id).recoverAfterInterruption(false, now);
        }
        entities.flush();
    }

    /**
     * Validated command envelope (docs/13 §3).
     *
     * @param requestId caller idempotency identity, unique per campaign (canonical UUID text over HTTP)
     * @param expectedRevision committed campaign revision the caller observed
     * @param commandType upper-case type identifier
     * @param payload bounded JSON object; hashed, not stored
     */
    public record Command(String requestId, long expectedRevision, String commandType, Map<String, Object> payload) {
        /** Reject malformed envelopes before any database access. */
        public Command {
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty() || requestId.length() > 128 || requestId.isBlank()) {
                throw new IllegalArgumentException("requestId must be 1..128 characters");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must be non-negative");
            }
            Objects.requireNonNull(commandType, "commandType");
            if (!COMMAND_TYPE.matcher(commandType).matches()) {
                throw new IllegalArgumentException("commandType is not a valid identifier");
            }
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * Result of {@link #submit}.
     *
     * @param operation current durable operation view
     * @param replayed true when an identical earlier request was found (nothing was written)
     * @param lease permit of a new admission for the internal worker; null on replay and
     *        never exposed to the browser
     */
    public record Admission(OperationView operation, boolean replayed, MutationLeaseService.Grant lease) {
    }

    /**
     * Owner-visible operation progress. The stored receipt is intentionally absent: it
     * can contain resolver details, and its player-safe projection is P07.4 work.
     *
     * @param operationId durable operation identity
     * @param campaignId owning campaign
     * @param commandType admitted command type
     * @param status current lifecycle status
     * @param baseRevision revision the command targeted
     * @param committedRevision revision produced by the commit, or null
     * @param operationVersion monotonic progress counter of the operation
     * @param createdAt acceptance time
     * @param updatedAt last progress time
     */
    public record OperationView(UUID operationId, UUID campaignId, String commandType, OperationStatus status,
            long baseRevision, Long committedRevision, long operationVersion, Instant createdAt, Instant updatedAt) {
        /** Copy detached values from a managed entity. */
        static OperationView of(CampaignOperation operation) {
            return new OperationView(operation.getId(), operation.getCampaignId(), operation.getCommandType(),
                    operation.getStatus(), operation.getBaseRevision(), operation.getCommittedRevision(),
                    operation.getOperationVersion(), operation.getCreatedAt(), operation.getUpdatedAt());
        }
    }
}
