package org.rpgengine.commands;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.progression.RewardSettlement;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short database-only boundary for a trusted resolved operation. Serializes commits
 * per campaign, rechecks its snapshot, writes every effect and returns a receipt only
 * after COMMIT succeeds. Preparation, authorization and later delivery belong to
 * callers/workers; this internal service has no controller, network port or callback.
 * Any validation, persistence, timeout or commit failure rolls back all its writes.
 */
@Service
public class CanonicalCommitService {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Wire database dependencies only; missing infrastructure fails application startup. */
    public CanonicalCommitService(EntityManager entities, PlatformTransactionManager manager, JdbcTemplate jdbc) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Commit a fully resolved plan or return the stored receipt for its matching retry.
     * Returns only detached scalar data after the transaction has ended. Rejects an
     * ambient transaction, stale/cancelled plan, changed payload or invalid scope;
     * propagates database failures including deferred constraints at COMMIT.
     */
    public Receipt commit(ResolvedCommit plan) {
        Objects.requireNonNull(plan, "plan");
        // Joining/suspending a caller's transaction could keep locks open across later
        // inference, or return success before the caller commits. Neither is allowed.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("canonical commit requires no ambient transaction");
        }
        return Objects.requireNonNull(transaction.execute(status -> write(plan)));
    }

    /**
     * Apply one plan under database locks; input is immutable and output is tentative
     * until TransactionTemplate commits. Any exception escapes to force rollback.
     */
    private Receipt write(ResolvedCommit plan) {
        // Bound database contention separately from external work, which is absent.
        entities.createNativeQuery("set local lock_timeout = '2s'").executeUpdate();
        entities.createNativeQuery("set local statement_timeout = '5s'").executeUpdate();
        var context = plan.context();
        Campaign campaign = entities.createQuery(
                "select c from Campaign c where c.id = :id and c.adventureId = :adventure", Campaign.class)
                .setParameter("id", context.campaignId()).setParameter("adventure", context.adventureId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        // Always lock campaign before journal. A competing duplicate then observes
        // the winner's committed receipt, while cancellation is protected by this row lock.
        CampaignOperation operation = entities.createQuery(
                "select o from CampaignOperation o where o.id = :id and o.campaignId = :campaign",
                CampaignOperation.class).setParameter("id", context.operationId())
                .setParameter("campaign", context.campaignId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        if (!operation.matchesPayload(context.payloadHash())) {
            throw new IllegalStateException("operation payload conflict");
        }
        // Replay precedes freshness checks: a lost response must not spend twice or
        // demand that the now-obsolete base revision become current again.
        if (operation.getCommittedRevision() != null) {
            return receipt(operation);
        }
        if (operation.getStatus() != OperationStatus.VALIDATING
                || operation.getOperationVersion() != context.expectedOperationVersion()
                || operation.getBaseRevision() != context.expectedRevision()
                || campaign.getCounters().stateRevision() != context.expectedRevision()
                || !campaign.getContentRevisionId().equals(context.contentRevisionId())) {
            throw new IllegalStateException("stale or ineligible resolved operation");
        }
        MutationLeaseService.requireHeld(jdbc, context);
        ContentRevision content = entities.find(ContentRevision.class, campaign.getContentRevisionId());
        Instant now = Instant.now();
        long firstSequence = Math.addExact(campaign.getCounters().eventSequence(), 1);
        var world = plan.world();
        var counters = campaign.commitWorld(context.expectedRevision(), world.advancesTurn(),
                world.elapsedSeconds(), plan.events().size(), world.mode(), world.pendingDecision(), now);
        operation.transitionTo(OperationStatus.COMMITTING, now);

        for (var change : plan.resources()) {
            ActorResource resource = entities.createQuery(
                    "select r from ActorResource r where r.id = :id and r.campaignId = :campaign",
                    ActorResource.class).setParameter("id", change.resourceId())
                    .setParameter("campaign", campaign.getId()).getSingleResult();
            if (resource.getRowVersion() != change.expectedRowVersion()) {
                throw new IllegalStateException("stale resource version");
            }
            // @Version also catches a writer racing after this read, including writers
            // outside this service. A failure rolls back previously flushed effects.
            resource.adjust(change.delta(), counters.stateRevision(), now);
        }
        for (int index = 0; index < plan.events().size(); index++) {
            var event = plan.events().get(index);
            long sequence = Math.addExact(firstSequence, index);
            entities.persist(CampaignEvent.append(new CampaignEvent.Envelope(campaign.getId(), sequence,
                    operation.getId(), event.actorId(), event.causationEventId(), operation.getId(),
                    counters.stateRevision(), counters.worldTimeSeconds(), content.getRuleset().rulesetHash(),
                    event.visibility()), event.body(), now));
        }
        for (var reward : plan.rewards()) {
            entities.persist(RewardSettlement.settle(campaign.getId(), reward.sourceKey(), reward.recipientKey(),
                    operation.getId(), counters.stateRevision(), reward.rewardsJson(), now));
        }

        // The backend owns receipt identity/order; caller details cannot replace them.
        var details = json.readTree(plan.receiptDetails());
        if (!details.isObject()) {
            throw new IllegalArgumentException("receiptDetails must be an object");
        }
        var receipt = json.createObjectNode();
        receipt.put("operationId", operation.getId().toString());
        receipt.put("campaignId", campaign.getId().toString());
        receipt.put("stateRevision", counters.stateRevision());
        receipt.put("firstEventSequence", firstSequence);
        receipt.put("lastEventSequence", counters.eventSequence());
        receipt.set("details", details);
        // Read durable reservations under the same campaign lock. Caller details
        // cannot forge this list or disclose seed material; failed commits retain draws.
        var randomDraws = receipt.putArray("randomDraws");
        for (var draw : RandomDrawService.recorded(jdbc, campaign.getId(), operation.getId())) {
            var reference = randomDraws.addObject();
            reference.put("id", draw.id().toString());
            reference.put("ordinal", draw.ordinal());
            reference.put("sides", draw.sides());
            reference.put("value", draw.value());
            reference.put("algorithm", draw.algorithm());
            reference.put("version", draw.version());
        }
        operation.recordCommit(counters.stateRevision(), json.writeValueAsString(receipt), now);
        entities.persist(new OutboxMessage(operation, OutboxMessage.Kind.PUBLIC_STATE, now));
        if (plan.narrationRequired()) {
            entities.persist(new OutboxMessage(operation, OutboxMessage.Kind.NARRATION_REQUIRED, now));
        }
        entities.flush();
        // Release with the outcome, never before it: rollback retains the permit and
        // a committed duplicate can replay even though its old permit is now retired.
        MutationLeaseService.releaseHeld(jdbc, context.campaignId(), context.operationId(), context.lease());
        // Read the stored JSONB rendering so the first response and a later replay are
        // identical, even though PostgreSQL normalizes whitespace and object key order.
        entities.refresh(operation);
        return receipt(operation);
    }

    /** Copy a committed journal outcome into detached scalars; a missing outcome is a programming error. */
    private static Receipt receipt(CampaignOperation operation) {
        return new Receipt(operation.getId(), operation.getCommittedRevision(), operation.getReceipt());
    }

    /** Durable outcome returned only after COMMIT; JSON includes owner-safe details and event references. */
    public record Receipt(UUID operationId, long stateRevision, String json) {
    }
}
