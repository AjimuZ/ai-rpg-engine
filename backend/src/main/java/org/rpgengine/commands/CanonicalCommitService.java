package org.rpgengine.commands;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.characters.CharacterService;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.inventory.InventoryWriter;
import org.rpgengine.inventory.ItemPlan;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.rpgengine.preparation.JobRequest;
import org.rpgengine.preparation.PlanActivations;
import org.rpgengine.preparation.PlanAdmissionService;
import org.rpgengine.preparation.PreparationJobService;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.PresentationJobService;
import org.rpgengine.quests.QuestWriter;
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
 * after COMMIT succeeds. Registered player operations recheck hash-bound typed
 * evidence, common semantic eligibility and the P07.3 current-state rules here, and
 * their effects must be attributable to the intention's participants. Effect calculation
 * belongs to {@link CommandExecutionService}/{@link LocalActionResolver}. Delivery belongs to
 * workers: the journal update emits the {@code state.committed} stream message through the V11
 * trigger, and a narration-requiring commit inserts its post-commit presentation job here, in
 * the same transaction. Cited plan branches are rechecked before any effect and recorded once
 * (P07.6); every commit then re-evaluates admitted plans against the rows it changed. Typed
 * item mutations, creation ledger entries and equipment changes (P10.2) are written beside the
 * events that announce them, guarded by the exact owner, quantity and storage version the resolver
 * read; quest status rows, satisfied objectives and first visits (P10.3) are written beside the events
 * that announce them, and the reward settlements of ended quests are persisted with the events so a
 * replayed source fails on the settlement's uniqueness; knowledge changes are written beside the events they cite, and after a registered player
 * intention the player's perception at its resulting location updates its last known locations
 * of other actors (P09.1); the query scopes both touch are bumped with the preparation scopes.
 * This internal service has no controller, network port or callback.
 * Any validation, persistence, timeout or commit failure rolls back all its writes.
 */
@Service
public class CanonicalCommitService {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final CommandRuleCatalogs catalogs;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Wire database dependencies and the pinned rule-fact loader; missing infrastructure fails
     * application startup.
     */
    public CanonicalCommitService(EntityManager entities, PlatformTransactionManager manager, JdbcTemplate jdbc,
            CommandRuleCatalogs catalogs) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
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
        // Load frozen pinned rule facts before taking locks; scope is still decided under the lock.
        // Every registered intention needs them now, WAIT included: the observation after its
        // effects decides concealment from the same catalog as the rules.
        var intent = plan.intent();
        var rules = intent == null ? CommandRuleCatalogs.Content.UNAVAILABLE : catalogs.pinned(intent.ownerId(),
                plan.context().adventureId(), plan.context().campaignId());
        return Objects.requireNonNull(transaction.execute(status -> write(plan, rules)));
    }

    /**
     * Apply one plan under database locks; input is immutable and output is tentative
     * until TransactionTemplate commits. Any exception escapes to force rollback.
     */
    private Receipt write(ResolvedCommit plan, CommandRuleCatalogs.Content rules) {
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
        UUID player = null;
        // A registered player action cannot use the legacy internal overload to skip
        // semantic checks. Bind every field of the intention to the admitted identity,
        // then re-read eligibility in this same transaction before changing any entity.
        if (TypedCommand.registered(operation.getCommandType()) || plan.intent() != null) {
            CommandIntent intent = plan.intent();
            if (intent == null || !intent.command().type().equals(operation.getCommandType())
                    || !intent.requestId().equals(operation.getIdempotencyKey())
                    || intent.expectedRevision() != context.expectedRevision()
                    || !intent.payloadHash().equals(context.payloadHash())) {
                throw new IllegalStateException("missing or mismatched typed command evidence");
            }
            player = CommandSemantics.requireEligible(jdbc, intent.ownerId(), context.adventureId(),
                    context.campaignId(), intent.command());
            // Re-derive every rule premise from current state, then bind effects to participants.
            var participants = CommandRules.require(jdbc, context.campaignId(), player, intent.command(), rules);
            CommandRules.requireAttributed(jdbc, context.campaignId(), participants, plan);
        }
        // A cited plan is rechecked against current rows before any effect is written; a
        // plan validated earlier never substitutes for this command's own second check.
        for (PlanActivations.Request activation : plan.preparation().activations()) {
            PlanActivations.requireUsable(jdbc, campaign.getId(), activation);
        }
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
        for (var placement : plan.placements()) {
            // Movement is an explicit typed effect, never a generic field write. The storage version
            // makes a concurrent actor writer (including one outside this service) a stale-plan
            // failure; the row-version trigger additionally requires exactly one step.
            if (jdbc.update("update actor set location_id = ?, row_version = row_version + 1 where id = ? "
                    + "and campaign_id = ? and row_version = ?", placement.locationId(), placement.actorId(),
                    campaign.getId(), placement.expectedRowVersion()) != 1) {
                throw new IllegalStateException("stale or out-of-scope actor placement");
            }
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
        // Items follow the flush: the creation ledger cites this operation's events by sequence. A
        // stale or constraint-violating item change throws and rolls back every earlier write.
        InventoryWriter.applyWithin(jdbc, campaign.getId(), operation.getId(), counters.stateRevision(), firstSequence,
                plan.items(), now);
        applyEquipment(plan.items(), rules, campaign, counters.stateRevision());
        // Quest rows cite the same events by sequence; a stale row version or an ended quest throws and
        // rolls back everything, including the reward settlement and the units it created (P10.3).
        QuestWriter.applyWithin(jdbc, campaign.getId(), counters.stateRevision(), firstSequence, plan.quests());
        if (plan.narrationRequired()) {
            // The owed presentation is durable exactly when the world change is (P07.5): one
            // NARRATION job per operation, checked against this committed revision at COMMIT.
            PresentationJobService.enqueueWithin(jdbc, context.adventureId(), campaign.getId(), operation.getId(),
                    counters.stateRevision());
        }
        // Preparation intents share this transaction and the campaign lock already held
        // (lock order campaign, then job): epochs move with the write that changed the
        // scopes, critical jobs exist exactly when the world change does, each cited plan
        // branch is recorded as used once, and every plan whose recorded epoch or row
        // version this commit changed is invalidated before any later admission or use.
        var preparation = plan.preparation();
        // Knowledge cites this operation's events, which the flush above made visible to SQL. The
        // observation runs after placements and knowledge changes, so this operation's own movement
        // and reveals decide what the player perceives; a null perception records nothing.
        var at = new KnowledgeLedger.Provenance(counters.stateRevision(), counters.worldTimeSeconds(), firstSequence,
                plan.events().size());
        var scopes = new LinkedHashSet<>(preparation.queryScopes());
        scopes.addAll(InventoryWriter.scopes(plan.items()));
        scopes.addAll(QuestWriter.scopes(plan.quests()));
        scopes.addAll(KnowledgeLedger.applyWithin(jdbc, campaign.getId(), at, plan.knowledge()));
        if (player != null) {
            var perception = CommandRules.perceived(jdbc, campaign.getId(), player, rules);
            if (perception != null) {
                scopes.addAll(KnowledgeLedger.observeWithin(jdbc, campaign.getId(), player, perception.location(),
                        perception.actors(), at));
            }
        }
        for (String scope : scopes) {
            QueryEpochs.bump(jdbc, campaign.getId(), scope);
        }
        for (JobRequest job : preparation.jobs()) {
            PreparationJobService.enqueueWithin(jdbc, context.adventureId(), campaign.getId(), operation.getId(), job);
        }
        for (PlanActivations.Request activation : preparation.activations()) {
            PlanActivations.recordWithin(jdbc, campaign.getId(), operation.getId(), counters.stateRevision(),
                    activation);
        }
        PlanAdmissionService.invalidateWithin(jdbc, campaign.getId(), counters.stateRevision());
        // Release with the outcome, never before it: rollback retains the permit and
        // a committed duplicate can replay even though its old permit is now retired.
        MutationLeaseService.releaseHeld(jdbc, context.campaignId(), context.operationId(), context.lease());
        // Read the stored JSONB rendering so the first response and a later replay are
        // identical, even though PostgreSQL normalizes whitespace and object key order.
        entities.refresh(operation);
        return receipt(operation);
    }

    /**
     * Apply the worn-state changes through the character rules, which re-check slots, tags,
     * capacity and hands against the rows just written. Runs inside the commit's transaction.
     */
    private void applyEquipment(ItemPlan items, CommandRuleCatalogs.Content rules, Campaign campaign, long revision) {
        if (items.equipment().isEmpty()) {
            return;
        }
        var characters = new CharacterService(jdbc);
        var catalog = rules.require(campaign.getContentRevisionId()).characters();
        for (ItemPlan.EquipmentChange change : items.equipment()) {
            switch (change) {
                case ItemPlan.Equip equip -> characters.equip(campaign.getId(), equip.actorId(), equip.itemInstanceId(),
                        catalog, revision);
                case ItemPlan.Unequip unequip -> characters.unequip(campaign.getId(), unequip.actorId(),
                        unequip.itemInstanceId());
            }
        }
    }

    /** Copy a committed journal outcome into detached scalars; a missing outcome is a programming error. */
    private static Receipt receipt(CampaignOperation operation) {
        return new Receipt(operation.getId(), operation.getCommittedRevision(), operation.getReceipt());
    }

    /** Durable outcome returned only after COMMIT; JSON includes owner-safe details and event references. */
    public record Receipt(UUID operationId, long stateRevision, String json) {
    }
}
