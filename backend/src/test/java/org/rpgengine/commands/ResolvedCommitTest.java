package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the pre-transaction work bound and immutable snapshot contract; no database or network is used. */
class ResolvedCommitTest {
    /** Construct a stateless unit-test fixture; no setup or external dependencies are required. */
    ResolvedCommitTest() {
    }

    /** Reject unbounded/ambiguous effects before acquiring locks, and isolate a plan from caller list mutation. */
    @Test
    void boundsAndFreezesResolvedEffects() {
        var context = new ResolvedCommit.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 0, 2);
        var world = new ResolvedCommit.WorldChange(true, 1, CampaignMode.EXPLORATION, null);
        var event = new ResolvedCommit.Event(null, null, EventVisibility.PUBLIC,
                new CampaignEvent.Body("changed", 1, 1, "{}"));
        var input = new ArrayList<>(List.of(event));
        var plan = new ResolvedCommit(context, world, List.of(), input, List.of(), "{}", false);
        input.clear();
        assertEquals(List.of(event), plan.events());
        assertThrows(UnsupportedOperationException.class, () -> plan.events().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedCommit(context, world, List.of(), List.of(), List.of(), "{}", false));
        assertEquals(128, new ResolvedCommit(context, world, List.of(), Collections.nCopies(128, event),
                List.of(), "{}", false).events().size());
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(),
                Collections.nCopies(129, event), List.of(), "{}", false));
        var cost = new ResolvedCommit.ResourceChange(UUID.randomUUID(), 0, -1);
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(cost, cost),
                List.of(event), List.of(), "{}", false));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world,
                Collections.nCopies(129, cost), List.of(event), List.of(), "{}", false));
        var reward = new ResolvedCommit.Reward("source", "recipient", "{}");
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(),
                List.of(event), Collections.nCopies(129, reward), "{}", false));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(),
                List.of(event), List.of(), "{\"text\":\"" + "x".repeat(32_768) + "\"}", false));
    }
}
