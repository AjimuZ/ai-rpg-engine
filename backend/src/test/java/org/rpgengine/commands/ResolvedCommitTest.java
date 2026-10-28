package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.preparation.PlanActivations;

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
                UUID.randomUUID(), "a".repeat(64), 0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
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
        // Placements are bounded, unique per actor and carry a valid location reference.
        var placement = new ResolvedCommit.Placement(UUID.randomUUID(), 0, "grove");
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(),
                List.of(event), List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null,
                List.of(placement, placement)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(),
                List.of(event), List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null,
                Collections.nCopies(17, placement)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit.Placement(UUID.randomUUID(), 0, " "));
        assertEquals(List.of(), new ResolvedCommit(context, world, List.of(), List.of(event), List.of(), "{}", false)
                .placements());
    }

    /** Plan citations are bounded, distinct and frozen before any lock is taken. */
    @Test
    void boundsPlanActivationCitations() {
        var artifact = UUID.randomUUID();
        var branches = new ArrayList<>(List.of("interview"));
        var activation = new PlanActivations.Request(artifact, branches);
        branches.add("document");
        assertEquals(List.of("interview"), activation.branchRefs());
        assertThrows(UnsupportedOperationException.class, () -> activation.branchRefs().clear());
        assertThrows(IllegalArgumentException.class, () -> new PlanActivations.Request(artifact, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlanActivations.Request(artifact,
                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")));
        assertThrows(IllegalArgumentException.class, () -> new PlanActivations.Request(artifact, List.of("a", "a")));
        assertThrows(IllegalArgumentException.class, () -> new PlanActivations.Request(artifact,
                List.of("x".repeat(129))));
        assertEquals(List.of(), new ResolvedCommit.Preparation(List.of(), List.of()).activations());
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit.Preparation(List.of(), List.of(),
                List.of(activation, new PlanActivations.Request(artifact, List.of("document")))));
        var citations = new ArrayList<PlanActivations.Request>();
        for (int index = 0; index < 5; index++) {
            citations.add(new PlanActivations.Request(UUID.randomUUID(), List.of("branch")));
        }
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit.Preparation(List.of(), List.of(), citations));
        assertEquals(4, new ResolvedCommit.Preparation(List.of(), List.of(), citations.subList(0, 4)).activations().size());
    }
}
