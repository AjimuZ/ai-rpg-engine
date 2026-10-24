package org.rpgengine.commands;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.progression.RewardSettlement;

/**
 * Immutable, bounded output of trusted backend resolution, never a transport DTO or
 * an arbitrary patch/callback. Context identifies the admitted operation; world,
 * resource deltas, events and settlements describe its resolved effects. Receipt
 * details must already be owner-safe. Construction rejects malformed/oversized input;
 * current state and database JSON validity are rechecked during commit.
 *
 * <p>No executable work can be supplied: inference and other network preparation must
 * finish before this value enters the commit service. Later rule modules extend the
 * explicit effect types instead of adding generic entity writes.
 */
public record ResolvedCommit(Context context, WorldChange world, List<ResourceChange> resources,
        List<Event> events, List<Reward> rewards, String receiptDetails, boolean narrationRequired) {
    /** Copy bounded lists to freeze the plan; reject duplicate resource effects and missing events. */
    public ResolvedCommit {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(world, "world");
        resources = bounded(resources, 128, "resources");
        events = bounded(events, 128, "events");
        rewards = bounded(rewards, 128, "rewards");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("a canonical commit requires an event");
        }
        var identities = new HashSet<UUID>();
        for (ResourceChange change : resources) {
            if (!identities.add(change.resourceId())) {
                throw new IllegalArgumentException("duplicate resource effect");
            }
        }
        CanonicalValues.requireJsonObject(receiptDetails, "receiptDetails", 32_768);
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
}
