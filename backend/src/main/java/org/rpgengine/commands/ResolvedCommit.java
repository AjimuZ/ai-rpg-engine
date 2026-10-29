package org.rpgengine.commands;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.inventory.ItemPlan;
import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.preparation.JobRequest;
import org.rpgengine.preparation.PlanActivations;
import org.rpgengine.quests.QuestChanges;
import org.rpgengine.progression.RewardSettlement;

/**
 * Immutable, bounded output of trusted backend resolution, never a transport DTO or
 * an arbitrary patch/callback. Context identifies the admitted operation; world,
 * resource deltas, events and settlements describe its resolved effects. Receipt
 * details must already be owner-safe. Construction rejects malformed/oversized input;
 * current state and database JSON validity are rechecked during commit. Registered
 * player commands also require immutable typed intent bound to the admitted hash;
 * internal legacy operations retain their separate trusted construction contract.
 *
 * <p>No executable work can be supplied: inference and other network preparation must
 * finish before this value enters the commit service. Later rule modules extend the
 * explicit effect types instead of adding generic entity writes; P07.4 adds actor
 * {@link Placement placements} for movement and P09.1 typed {@link KnowledgeChange knowledge changes}
 * (claims, beliefs, sightings, commitments), each citing one of this plan's events as provenance.
 * P10.2 adds an {@link ItemPlan}: instance mutations that provably conserve units, the creation
 * ledger entries that link every created unit to its source and announcing event, and equipment
 * changes. P10.3 adds {@link QuestChanges}: quest status rows, satisfied objectives and first visits,
 * each citing the plan event that announced it; a quest ending settles its item rewards through the
 * same plan's {@link Reward}s and {@link ItemPlan} creations. Preparation intents
 * (query-scope epoch bumps, critical preparation jobs and cited plan branches) are data
 * too; they commit or roll back with the world change that caused them.
 */
public record ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources,
        List<Event> events, List<Reward> rewards, String receiptDetails, boolean narrationRequired,
        Preparation preparation, CommandIntent intent, List<Placement> placements, List<KnowledgeChange> knowledge,
        ItemPlan items, QuestChanges quests) {
    /** Most events one plan may carry; the quest engine budgets its steps against what the command already used. */
    public static final int MAX_EVENTS = 128;

    /**
     * Copy bounded lists to freeze the plan; reject duplicate resource or placement effects,
     * missing events, knowledge changes and item creations citing an event outside this plan, and a
     * reward-sourced creation without the reward settlement it claims as its source.
     */
    public ResolvedCommit {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(preparation, "preparation");
        resources = bounded(resources, 128, "resources");
        placements = bounded(placements, 16, "placements");
        events = bounded(events, MAX_EVENTS, "events");
        rewards = bounded(rewards, 128, "rewards");
        knowledge = bounded(knowledge, 128, "knowledge");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("a canonical commit requires an event");
        }
        var identities = new HashSet<UUID>();
        for (ResourceChange change : resources) {
            if (!identities.add(change.resourceId())) {
                throw new IllegalArgumentException("duplicate resource effect");
            }
        }
        var placed = new HashSet<UUID>();
        for (Placement placement : placements) {
            if (!placed.add(placement.actorId())) {
                throw new IllegalArgumentException("duplicate placement effect");
            }
        }
        for (KnowledgeChange change : knowledge) {
            if (change.sourceEvent() >= events.size()) {
                throw new IllegalArgumentException("knowledge change cites an event outside this plan");
            }
        }
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(quests, "quests");
        for (QuestChanges.StateWrite write : quests.states()) {
            if (write.eventIndex() >= events.size()) {
                throw new IllegalArgumentException("quest state cites an event outside this plan");
            }
        }
        for (QuestChanges.ObjectiveWrite write : quests.objectives()) {
            if (write.eventIndex() >= events.size()) {
                throw new IllegalArgumentException("quest objective cites an event outside this plan");
            }
        }
        var settled = new HashSet<String>();
        for (Reward reward : rewards) {
            if (!settled.add(reward.sourceKey() + "\u0000" + reward.recipientKey())) {
                throw new IllegalArgumentException("a reward source settles once per recipient");
            }
        }
        for (ItemPlan.Creation creation : items.creations()) {
            if (creation.eventIndex() >= events.size()) {
                throw new IllegalArgumentException("item creation cites an event outside this plan");
            }
            if (creation.kind() == ItemPlan.SourceKind.REWARD
                    && rewards.stream().noneMatch(reward -> reward.sourceKey().equals(creation.sourceKey()))) {
                throw new IllegalArgumentException("a reward-sourced item creation needs its reward settlement");
            }
        }
        CanonicalValues.requireJsonObject(receiptDetails, "receiptDetails", 32_768);
    }

    /** Plan without quest effects, the shape used before P10.3. */
    public ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources, List<Event> events,
            List<Reward> rewards, String receiptDetails, boolean narrationRequired, Preparation preparation,
            CommandIntent intent, List<Placement> placements, List<KnowledgeChange> knowledge, ItemPlan items) {
        this(context, world, resources, events, rewards, receiptDetails, narrationRequired, preparation, intent,
                placements, knowledge, items, QuestChanges.NONE);
    }

    /** Plan without item effects, the shape used before P10.2. */
    public ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources, List<Event> events,
            List<Reward> rewards, String receiptDetails, boolean narrationRequired, Preparation preparation,
            CommandIntent intent, List<Placement> placements, List<KnowledgeChange> knowledge) {
        this(context, world, resources, events, rewards, receiptDetails, narrationRequired, preparation, intent,
                placements, knowledge, ItemPlan.NONE, QuestChanges.NONE);
    }

    /** Plan without knowledge changes, the shape used before P09.1. */
    public ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources, List<Event> events,
            List<Reward> rewards, String receiptDetails, boolean narrationRequired, Preparation preparation,
            CommandIntent intent, List<Placement> placements) {
        this(context, world, resources, events, rewards, receiptDetails, narrationRequired, preparation, intent,
                placements, List.of(), ItemPlan.NONE, QuestChanges.NONE);
    }

    /** Plan without actor placements, the shape used before P07.4 movement execution. */
    public ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources, List<Event> events,
            List<Reward> rewards, String receiptDetails, boolean narrationRequired, Preparation preparation,
            CommandIntent intent) {
        this(context, world, resources, events, rewards, receiptDetails, narrationRequired, preparation, intent,
                List.of(), List.of(), ItemPlan.NONE, QuestChanges.NONE);
    }

    /**
     * Compatibility constructor for trusted internal operations outside the player registry.
     * Registered player commands require the full constructor's typed evidence at commit;
     * omitting it cannot bypass semantic validation.
     */
    public ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources, List<Event> events,
            List<Reward> rewards, String receiptDetails, boolean narrationRequired, Preparation preparation) {
        this(context, world, resources, events, rewards, receiptDetails, narrationRequired, preparation, null,
                List.of(), List.of(), ItemPlan.NONE, QuestChanges.NONE);
    }

    /** Build a commit without preparation intents (no epoch bumps, no critical jobs). */
    public ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources, List<Event> events,
            List<Reward> rewards, String receiptDetails, boolean narrationRequired) {
        this(context, world, resources, events, rewards, receiptDetails, narrationRequired, Preparation.NONE, null,
                List.of(), List.of(), ItemPlan.NONE, QuestChanges.NONE);
    }

    /** Return a defensive copy of at most limit non-null entries; fail before taking any locks. */
    private static <T> List<T> bounded(List<T> values, int limit, String name) {
        if (values.size() > limit) {
            throw new IllegalArgumentException(name + " exceeds " + limit);
        }
        return List.copyOf(values);
    }

    /** Scope, pinned content and expected domain versions from the backend's planning snapshot. */
    public record Context(UUID adventureId, UUID campaignId, UUID operationId, UUID contentRevisionId,
            String payloadHash, long expectedRevision, long expectedOperationVersion, MutationLeaseService.Token lease) {
        /** Reject missing identities, malformed hash or negative counters. */
        public Context {
            Objects.requireNonNull(adventureId, "adventureId");
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(contentRevisionId, "contentRevisionId");
            Objects.requireNonNull(lease, "lease");
            CanonicalValues.requireSha256Hex(payloadHash, "payloadHash");
            CanonicalValues.requireNonNegative(expectedRevision, "expectedRevision");
            CanonicalValues.requireNonNegative(expectedOperationVersion, "expectedOperationVersion");
        }
    }

    /** Resolved time/turn effects and the resulting mode/decision; null clears the decision. */
    public record WorldChange(boolean advancesTurn, long elapsedSeconds, CampaignMode mode, String pendingDecision) {
        /** Reject negative duration, missing mode or an oversized/non-object decision. */
        public WorldChange {
            CanonicalValues.requireNonNegative(elapsedSeconds, "elapsedSeconds");
            Objects.requireNonNull(mode, "mode");
            if (pendingDecision != null) {
                CanonicalValues.requireJsonObject(pendingDecision, "pendingDecision", 16_384);
            }
        }
    }

    /** Signed backend-calculated adjustment to a resource read at expectedRowVersion. */
    public record ResourceChange(UUID resourceId, long expectedRowVersion, long delta) {
        /** Reject a missing identity or negative storage version; bounds are checked on current state. */
        public ResourceChange {
            Objects.requireNonNull(resourceId, "resourceId");
            CanonicalValues.requireNonNegative(expectedRowVersion, "expectedRowVersion");
        }
    }

    /**
     * Move one campaign actor to a stable content location read at {@code expectedRowVersion}.
     * Reachability is a rule premise checked by {@link CommandRules} for player intentions;
     * this value only carries the resolved destination.
     */
    public record Placement(UUID actorId, long expectedRowVersion, String locationId) {
        /** Reject a missing actor, negative storage version or malformed location reference. */
        public Placement {
            Objects.requireNonNull(actorId, "actorId");
            CanonicalValues.requireNonNegative(expectedRowVersion, "expectedRowVersion");
            CanonicalValues.requireText(locationId, "locationId", 256);
        }
    }

    /** Event body with optional actor/earlier causation; commit supplies all ordering and rule provenance. */
    public record Event(UUID actorId, UUID causationEventId, EventVisibility visibility, CampaignEvent.Body body) {
        /** Require visibility and body; database composite keys enforce optional reference scope. */
        public Event {
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(body, "body");
        }
    }

    /** One-time settlement identity and backend-resolved payload; actual effects accompany the plan. */
    public record Reward(String sourceKey, String recipientKey, String rewardsJson) {
        /** Reject invalid keys or oversized/non-object reward data before entering the transaction. */
        public Reward {
            CanonicalValues.requireText(sourceKey, "sourceKey", 256);
            CanonicalValues.requireText(recipientKey, "recipientKey", 256);
            CanonicalValues.requireJsonObject(rewardsJson, "rewards", RewardSettlement.MAX_REWARDS_BYTES);
        }
    }

    /**
     * Preparation intents written atomically with the canonical effects (docs/28
     * section 7). Each scope's query epoch is bumped once, current plans whose recorded
     * reads changed are invalidated, and each job is enqueued deduplicated with this
     * operation as its origin; replaying an identical intent finds the same job.
     * Activations cite admitted plan branches the trusted resolver used: the commit
     * rechecks them under its lock before writing any effect and records each use once,
     * but a plan contributes no effect of its own (P07.6).
     *
     * @param queryScopes at most 64 distinct query scopes the effects can change
     * @param jobs at most 16 critical or ordinary preparation jobs to create
     * @param activations at most four cited artifacts, each at most once
     */
    public record Preparation(List<String> queryScopes, List<JobRequest> jobs,
            List<PlanActivations.Request> activations) {
        /** No scopes, no jobs and no plan use. */
        public static final Preparation NONE = new Preparation(List.of(), List.of(), List.of());

        /** Copy and bound every list; reject duplicate scopes and artifacts so each entry is meaningful. */
        public Preparation {
            queryScopes = bounded(queryScopes, 64, "queryScopes");
            jobs = bounded(jobs, 16, "jobs");
            activations = bounded(activations, 4, "activations");
            if (new HashSet<>(queryScopes).size() != queryScopes.size()) {
                throw new IllegalArgumentException("duplicate query scope");
            }
            if (activations.stream().map(PlanActivations.Request::artifactId).distinct().count()
                    != activations.size()) {
                throw new IllegalArgumentException("duplicate plan activation");
            }
        }

        /** Intents without plan use, the shape used before P07.6. */
        public Preparation(List<String> queryScopes, List<JobRequest> jobs) {
            this(queryScopes, jobs, List.of());
        }
    }
}
