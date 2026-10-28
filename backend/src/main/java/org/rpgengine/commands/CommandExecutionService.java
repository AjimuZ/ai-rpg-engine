package org.rpgengine.commands;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local execution of registered player intentions (P07.4): admission, deterministic
 * calculation, durable draws and one canonical commit, with every outcome recorded in the
 * operation journal. It runs in the caller's thread with no inference, network client or
 * background worker; narration becomes a durable post-commit presentation job that the
 * presentation worker settles later (P07.5).
 *
 * <p>Sequence for a fresh request, each step its own bounded transaction:
 * <ol>
 *   <li>{@link OperationAdmissionService#submit} journals the operation as ACCEPTED and grants
 *       the campaign mutation lease atomically (or replays/rejects without writes).</li>
 *   <li><em>Prepare</em> locks campaign then journal, moves the operation to VALIDATING, re-runs
 *       {@link CommandSemantics} and {@link CommandRules} on current rows and reads a detached
 *       snapshot. A rule failure here commits REJECTED and releases the lease.</li>
 *   <li>{@link LocalActionResolver} calculates the effects; every die reserves one durable draw
 *       through {@link RandomDrawService}, which commits each reservation independently.</li>
 *   <li>{@link CanonicalCommitService#commit} rechecks revision, lease, typed evidence, semantics
 *       and rules, applies every effect, writes events, receipt and outbox, and releases the lease.</li>
 * </ol>
 * After durable admission an execution failure is never reported as "nothing happened": a rule
 * failure at commit records REJECTED, any other failure records FAILED_BEFORE_COMMIT, and the
 * lease is released in both cases. Reserved draws always survive, bound to that operation, so a
 * later diagnosis can see them and the operation can never be re-resolved with new values.
 * If even that bookkeeping fails, the original error propagates and the lease expiry plus
 * admission cleanup or startup reconciliation move the operation to FAILED_BEFORE_COMMIT.
 *
 * <p>A matching retry only replays: it returns the stored status and receipt and never resumes
 * or re-executes an earlier attempt, because the admission journal intentionally stores the
 * payload hash and not the payload.
 *
 * <p>Cancellation (P07.5, {@link OperationCancellationService}) races these steps under the
 * campaign lock. When it wins, the operation is CANCELLED and its permit fenced, so the next
 * prepare, draw or commit here fails its status/version/lease check; the bookkeeping above then
 * leaves the terminal status untouched and this method reports CANCELLED. When the commit wins,
 * this method reports the commit. Every status change is announced on the public stream by the
 * journal trigger, never by this class.
 */
@Service
public class CommandExecutionService {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OperationAdmissionService admissions;
    private final CommandRuleCatalogs catalogs;
    private final RandomDrawService draws;
    private final CanonicalCommitService commits;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * Wire the command pipeline; missing infrastructure fails application startup.
     *
     * @param admissions durable admission and status lookup
     * @param catalogs pinned rule-fact loader used outside locks
     * @param draws durable draw reservation
     * @param commits canonical commit boundary
     * @param entities JPA context for journal transitions
     * @param jdbc JDBC access joined to the bounded transactions
     * @param manager shared transaction manager
     */
    public CommandExecutionService(OperationAdmissionService admissions, CommandRuleCatalogs catalogs,
            RandomDrawService draws, CanonicalCommitService commits, EntityManager entities, JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.draws = Objects.requireNonNull(draws, "draws");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Admit and locally resolve one command, or replay an earlier identical request.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign target campaign
     * @param command structurally validated envelope
     * @return final operation view (COMMITTED, REJECTED, FAILED_BEFORE_COMMIT or CANCELLED for a
     *         fresh request; the stored progress for a replay) and the player-safe receipt when committed
     * @throws OperationAdmissionFailure for admission outcomes; nothing was journaled
     * @throws IllegalArgumentException if the command cannot be canonicalized within bounds
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws RuntimeException storage failures that also prevented recording the outcome
     */
    public Execution execute(UUID owner, UUID adventure, UUID campaign, OperationAdmissionService.Command command) {
        var admission = admissions.submit(owner, adventure, campaign, command);
        UUID operation = admission.operation().operationId();
        if (admission.replayed()) {
            return current(owner, adventure, campaign, operation, true);
        }
        var token = admission.lease().token();
        try {
            // Rebuild typed evidence from the same canonical bytes admission hashed.
            var intent = CommandIntent.from(owner, command);
            var content = catalogs.preload(owner, adventure, campaign, intent.command().type());
            Prepared prepared = Objects.requireNonNull(transaction.execute(status ->
                    prepare(owner, adventure, campaign, operation, token, intent, content)));
            if (prepared.context() != null) {
                var context = prepared.context();
                var resolution = LocalActionResolver.resolve(intent.command(), prepared.snapshot(), prepared.catalog(),
                        (ordinal, sides) -> draws.reserve(context, ordinal, sides).value());
                commits.commit(new ResolvedCommit(context, resolution.world(), resolution.resources(),
                        resolution.events(), List.of(), resolution.receiptDetails(), true,
                        new ResolvedCommit.Preparation(resolution.queryScopes(), List.of()), intent,
                        resolution.placements()));
            }
        } catch (OperationAdmissionFailure rejected) {
            // Current state changed after admission in a way the rules refuse; nothing was applied.
            settle(adventure, campaign, operation, token, OperationStatus.REJECTED, null);
        } catch (RuntimeException failure) {
            settle(adventure, campaign, operation, token, OperationStatus.FAILED_BEFORE_COMMIT, failure);
        }
        return current(owner, adventure, campaign, operation, false);
    }

    /**
     * Read one operation's status and, once committed, its player-safe receipt. Read-only;
     * foreign and missing identities are indistinguishable.
     *
     * @throws OperationAdmissionFailure {@code SCOPE_UNAVAILABLE} for missing or foreign identities
     */
    public Execution status(UUID owner, UUID adventure, UUID campaign, UUID operation) {
        return current(owner, adventure, campaign, operation, false);
    }

    /** Scoped status read followed by the write-once receipt of a committed operation. */
    private Execution current(UUID owner, UUID adventure, UUID campaign, UUID operation, boolean replayed) {
        var view = admissions.status(owner, adventure, campaign, operation);
        Map<String, Object> receipt = null;
        if (view.committedRevision() != null) {
            // Scope is already authorized; a committed receipt can never change afterwards.
            String stored = jdbc.queryForObject("select receipt::text from operation_journal where id = ? "
                    + "and campaign_id = ?", String.class, operation, campaign);
            receipt = CommandReceipts.project(stored);
        }
        return new Execution(view, replayed, receipt);
    }

    /**
     * Move the admitted operation to VALIDATING and capture the snapshot, or record REJECTED.
     * Runs inside one transaction holding campaign then journal locks; returns a prepared
     * value whose context is null when the operation was rejected (that outcome commits).
     */
    private Prepared prepare(UUID owner, UUID adventure, UUID campaign, UUID operation,
            MutationLeaseService.Token token, CommandIntent intent, CommandRuleCatalogs.Content content) {
        MutationLeaseService.lockScope(jdbc, adventure, campaign, operation);
        CampaignOperation journal = entities.find(CampaignOperation.class, operation);
        var pinned = jdbc.queryForObject("select state_revision, content_revision_id from campaign where id = ?",
                (row, index) -> Map.entry(row.getLong(1), row.getObject(2, UUID.class)), campaign);
        if (journal.getStatus() != OperationStatus.ACCEPTED || journal.getBaseRevision() != pinned.getKey()) {
            throw new IllegalStateException("operation is no longer awaiting local resolution");
        }
        Instant now = Instant.now();
        journal.transitionTo(OperationStatus.PLANNING, now);
        journal.transitionTo(OperationStatus.VALIDATING, now);
        entities.flush();
        var context = new ResolvedCommit.Context(adventure, campaign, operation, pinned.getValue(),
                journal.getPayloadHash(), journal.getBaseRevision(), journal.getOperationVersion(), token);
        MutationLeaseService.requireHeld(jdbc, context);
        try {
            // Admission's checks are not a reusable stamp: re-derive every premise under this lock.
            UUID player = CommandSemantics.requireEligible(jdbc, owner, adventure, campaign, intent.command());
            Set<UUID> participants = CommandRules.require(jdbc, campaign, player, intent.command(), content);
            var catalog = intent.command() instanceof TypedCommand.UseAbility ? content.require(pinned.getValue()) : null;
            return new Prepared(context, catalog, snapshot(campaign, player, participants, catalog));
        } catch (OperationAdmissionFailure rejected) {
            journal.transitionTo(OperationStatus.REJECTED, now);
            MutationLeaseService.releaseHeld(jdbc, campaign, operation, token);
            entities.flush();
            return new Prepared(null, null, null);
        }
    }

    /**
     * Read the detached facts {@link LocalActionResolver} needs, under the prepare locks.
     * Character state may be absent for legacy saves; an expression that then reads an
     * attribute fails closed during calculation instead of reading zero.
     */
    private LocalActionResolver.Snapshot snapshot(UUID campaign, UUID player, Set<UUID> participants,
            CommandRuleCatalog catalog) {
        var actor = jdbc.queryForObject("select location_id, row_version from actor where campaign_id = ? and id = ?",
                (row, index) -> Map.entry(java.util.Optional.ofNullable(row.getString(1)), row.getLong(2)),
                campaign, player);
        var state = jdbc.query("select attributes::text, skills::text from character_state "
                + "where campaign_id = ? and actor_id = ?", (row, index) -> new String[] {row.getString(1),
                row.getString(2)}, campaign, player);
        var pools = new LinkedHashMap<UUID, Map<String, LocalActionResolver.Pool>>();
        var vocabularies = new HashMap<UUID, String>();
        for (UUID participant : participants.stream().sorted().toList()) {
            var byKey = new LinkedHashMap<String, LocalActionResolver.Pool>();
            jdbc.query("select resource_key, id, row_version, minimum_value, current_value, maximum_value "
                    + "from actor_resource where campaign_id = ? and actor_id = ? order by resource_key", row -> {
                        byKey.put(row.getString(1), new LocalActionResolver.Pool(row.getObject(2, UUID.class),
                                row.getLong(3), row.getLong(4), row.getLong(5), row.getLong(6)));
                    }, campaign, participant);
            pools.put(participant, byKey);
            String origin = jdbc.queryForObject("select definition_id from actor where campaign_id = ? and id = ?",
                    String.class, campaign, participant);
            String pack = catalog == null || origin == null ? null : catalog.actorPacks().get(origin);
            if (pack != null) {
                vocabularies.put(participant, pack);
            }
        }
        return new LocalActionResolver.Snapshot(player, actor.getKey().orElse(null), actor.getValue(),
                state.isEmpty() ? Map.of() : integers(state.getFirst()[0]),
                state.isEmpty() ? Map.of() : integers(state.getFirst()[1]), pools, vocabularies);
    }

    /** Integral members of a stored JSON object; other values are omitted and so read as unknown. */
    private static Map<String, Long> integers(String object) {
        var values = new HashMap<String, Long>();
        JSON.readTree(object).properties().forEach(entry -> {
            if (entry.getValue().isIntegralNumber() && entry.getValue().canConvertToLong()) {
                values.put(entry.getKey(), entry.getValue().longValue());
            }
        });
        return values;
    }

    /**
     * Record a terminal pre-commit outcome and release this operation's lease. A committed or
     * already terminal operation is left unchanged, so a failure that raced a successful commit
     * still reports the commit. If recording fails, the original failure (or, for a rejection,
     * the recording failure) propagates with the other suppressed.
     */
    private void settle(UUID adventure, UUID campaign, UUID operation, MutationLeaseService.Token token,
            OperationStatus outcome, RuntimeException cause) {
        try {
            transaction.executeWithoutResult(status -> {
                MutationLeaseService.lockScope(jdbc, adventure, campaign, operation);
                CampaignOperation journal = entities.find(CampaignOperation.class, operation);
                Instant now = Instant.now();
                switch (journal.getStatus()) {
                    // REJECTED is reachable only from VALIDATING; earlier failures are infrastructure failures.
                    case VALIDATING -> journal.transitionTo(outcome, now);
                    case ACCEPTED, PLANNING -> journal.transitionTo(OperationStatus.FAILED_BEFORE_COMMIT, now);
                    default -> { }
                }
                MutationLeaseService.releaseHeld(jdbc, campaign, operation, token);
                entities.flush();
            });
        } catch (RuntimeException recording) {
            if (cause == null) {
                throw recording;
            }
            cause.addSuppressed(recording);
            throw cause;
        }
    }

    /**
     * Output of the prepare transaction.
     *
     * @param context commit snapshot tokens, or null when the operation was rejected
     * @param catalog pinned rule facts for ability use, otherwise null
     * @param snapshot detached facts for calculation, or null when rejected
     */
    private record Prepared(ResolvedCommit.Context context, CommandRuleCatalog catalog,
            LocalActionResolver.Snapshot snapshot) { }

    /**
     * Detached result of {@link #execute} or {@link #status}.
     *
     * @param operation current durable operation view
     * @param replayed true when an identical earlier request was found (nothing was written)
     * @param receipt player-safe receipt projection of a committed operation, otherwise null
     */
    public record Execution(OperationAdmissionService.OperationView operation, boolean replayed,
            Map<String, Object> receipt) {
        /** Freeze the projection. */
        public Execution {
            Objects.requireNonNull(operation, "operation");
            receipt = receipt == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(receipt));
        }
    }
}
