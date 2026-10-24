package org.rpgengine.commands;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.rules.DeterministicDraw;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal backend-only durable draw reservation, with no network dependencies or
 * world mutations. Inputs are a trusted operation snapshot, ordinal and positive
 * bound; output is a detached immutable draw after COMMIT. Scope, conflicts, stale
 * plans and persistence failures abort the transaction. Seed material never leaves
 * this boundary; it is private database data, not a presentation or transport entity.
 */
@Service
public class RandomDrawService {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SecureRandom entropy = new SecureRandom();

    /** Wire shared transactional database dependencies; reject null dependencies at startup. */
    public RandomDrawService(EntityManager entities, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Reserve/replay one 1..sides draw for ordinal 0..127. Existing matching records
     * replay even after world changes or cancellation, but never permit a changed
     * bound. New records require the exact current VALIDATING snapshot. Ambient
     * transactions fail so canonical rollback cannot undo this earlier reservation.
     */
    public Draw reserve(ResolvedCommit.Context context, int ordinal, int sides) {
        Objects.requireNonNull(context, "context");
        DeterministicDraw.validate(ordinal, sides);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("draw reservation requires no ambient transaction");
        }
        return Objects.requireNonNull(transaction.execute(status -> allocate(context, ordinal, sides)));
    }

    /** Allocate under campaign-then-journal locks shared with canonical commit; any failure rolls back preparation. */
    private Draw allocate(ResolvedCommit.Context c, int ordinal, int sides) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '5s'");
        Campaign campaign = entities.createQuery(
                "select c from Campaign c where c.id = :id and c.adventureId = :adventure", Campaign.class)
                .setParameter("id", c.campaignId()).setParameter("adventure", c.adventureId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        CampaignOperation operation = entities.createQuery(
                "select o from CampaignOperation o where o.id = :id and o.campaignId = :campaign",
                CampaignOperation.class).setParameter("id", c.operationId()).setParameter("campaign", c.campaignId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        if (!operation.matchesPayload(c.payloadHash())) {
            throw new IllegalStateException("operation payload conflict");
        }
        List<Draw> existing = recorded(jdbc, c.campaignId(), c.operationId());
        for (Draw draw : existing) {
            if (draw.ordinal() == ordinal) {
                if (draw.sides() != sides) {
                    throw new IllegalStateException("draw bound conflict");
                }
                return draw;
            }
        }
        if (operation.getStatus() != OperationStatus.VALIDATING
                || operation.getOperationVersion() != c.expectedOperationVersion()
                || operation.getBaseRevision() != c.expectedRevision()
                || campaign.getCounters().stateRevision() != c.expectedRevision()
                || !campaign.getContentRevisionId().equals(c.contentRevisionId())) {
            throw new IllegalStateException("stale or ineligible draw operation");
        }
        MutationLeaseService.requireHeld(jdbc, c);
        // The campaign lock serializes lazy seed creation as well as all draw inserts.
        var seeds = jdbc.query("select seed from campaign_random_seed where campaign_id = ?",
                (row, index) -> row.getBytes(1), c.campaignId());
        byte[] seed = seeds.isEmpty() ? new byte[32] : seeds.getFirst();
        try {
            if (seeds.isEmpty()) {
                entropy.nextBytes(seed);
                jdbc.update("insert into campaign_random_seed values (?, ?, ?, ?, now())", c.campaignId(), seed,
                        DeterministicDraw.ALGORITHM, DeterministicDraw.VERSION);
            }
            Draw draw = new Draw(UUID.randomUUID(), ordinal, sides,
                    DeterministicDraw.value(seed, c.operationId(), ordinal, sides),
                    DeterministicDraw.ALGORITHM, DeterministicDraw.VERSION);
            jdbc.update("insert into operation_random_draw values (?, ?, ?, ?, ?, ?, ?, ?, now())",
                    draw.id(), c.campaignId(), c.operationId(), ordinal, sides, draw.value(), draw.algorithm(), draw.version());
            return draw;
        } finally {
            // Reduce lifetime of the transient copy; persisted bytes remain protected backup data.
            Arrays.fill(seed, (byte) 0);
        }
    }

    /** Read only scoped immutable records in ordinal order; SQL/mapping errors propagate without exposing seed bytes. */
    static List<Draw> recorded(JdbcTemplate jdbc, UUID campaign, UUID operation) {
        return jdbc.query("select id, ordinal, sides, value, algorithm, algorithm_version from operation_random_draw "
                + "where campaign_id = ? and operation_id = ? order by ordinal",
                (row, index) -> new Draw(row.getObject(1, UUID.class), row.getInt(2), row.getInt(3),
                        row.getInt(4), row.getString(5), row.getInt(6)), campaign, operation);
    }

    /** Private-until-commit detached mechanical evidence; contains neither seed bytes nor a seed reference. */
    public record Draw(UUID id, int ordinal, int sides, int value, String algorithm, int version) {
    }
}
