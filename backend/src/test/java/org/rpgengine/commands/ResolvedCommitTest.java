package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.inventory.ItemChange;
import org.rpgengine.inventory.ItemPlan;
import org.rpgengine.preparation.PlanActivations;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the pre-transaction work bound and immutable snapshot contract; no database or network is used. */
class ResolvedCommitTest {
    /** Construct a stateless unit-test fixture; no setup or external dependencies are required. */
    ResolvedCommitTest() {
    }

    /** The world clock moves by at most one in-world day per plan, and a rest ends each condition once. */
    @Test
    void boundsTheClockStepAndConditionRemovals() {
        assertEquals(86_400, new ResolvedCommit.WorldChange(true, 86_400, CampaignMode.EXPLORATION, null).elapsedSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedCommit.WorldChange(true, 86_401, CampaignMode.EXPLORATION, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedCommit.WorldChange(true, -1, CampaignMode.EXPLORATION, null));
        var context = new ResolvedCommit.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
        var world = new ResolvedCommit.WorldChange(true, 600, CampaignMode.EXPLORATION, null);
        var event = new ResolvedCommit.Event(null, null, EventVisibility.PUBLIC,
                new CampaignEvent.Body("REST_COMPLETED", 1, 1, "{}"));
        UUID actor = UUID.randomUUID();
        var removal = new ResolvedCommit.ConditionRemoval(actor, "dazed");
        var plan = new ResolvedCommit(context, world, List.of(), List.of(event), List.of(), "{}", false,
                ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), org.rpgengine.inventory.ItemPlan.NONE,
                org.rpgengine.quests.QuestChanges.NONE, List.of(removal));
        assertEquals(List.of(removal), plan.conditions());
        assertThrows(UnsupportedOperationException.class, () -> plan.conditions().clear());
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(), List.of(event),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(),
                org.rpgengine.inventory.ItemPlan.NONE, org.rpgengine.quests.QuestChanges.NONE, List.of(removal, removal)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(), List.of(event),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(),
                org.rpgengine.inventory.ItemPlan.NONE, org.rpgengine.quests.QuestChanges.NONE,
                java.util.Collections.nCopies(17, removal)));
        assertThrows(NullPointerException.class, () -> new ResolvedCommit.ConditionRemoval(null, "dazed"));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit.ConditionRemoval(actor, " "));
        // Plans built before P10.4 carry no removal.
        assertTrue(new ResolvedCommit(context, world, List.of(), List.of(event), List.of(), "{}", false).conditions().isEmpty());
    }

    /** An encounter entry moves the campaign into encounter mode and cites one of the plan's events (P11.1). */
    @Test
    void anEncounterEntryNeedsEncounterModeAndItsEvent() throws Exception {
        var context = new ResolvedCommit.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
        var event = new ResolvedCommit.Event(null, null, EventVisibility.PUBLIC,
                new CampaignEvent.Body("ENCOUNTER_STARTED", 1, 1, "{}"));
        var rules = org.rpgengine.combat.EncounterPack.rules(org.rpgengine.combat.EncounterPack.files());
        var frozen = org.rpgengine.combat.FrozenEncounter.freeze(UUID.randomUUID(), rules.templates().get("trail-ambush"),
                rules.profiles().get("sample:armory"), new org.rpgengine.combat.FrozenEncounter.PlayerFacts(UUID.randomUUID(),
                        java.util.Map.of("awareness", 2L, "might", 2L), new org.rpgengine.combat.FrozenEncounter.Pool(8, 8),
                        null, List.of(), java.util.Set.of()), null);
        var tactical = new ResolvedCommit.WorldChange(true, 0, CampaignMode.ENCOUNTER, null);
        var plan = new ResolvedCommit(context, tactical, List.of(), List.of(event), List.of(), "{}", false,
                ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), org.rpgengine.inventory.ItemPlan.NONE,
                org.rpgengine.quests.QuestChanges.NONE, List.of(), new ResolvedCommit.EncounterEntry(frozen, 0));
        assertEquals(frozen, plan.encounter().frozen());
        var exploring = new ResolvedCommit.WorldChange(true, 0, CampaignMode.EXPLORATION, null);
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, exploring, List.of(), List.of(event),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(),
                org.rpgengine.inventory.ItemPlan.NONE, org.rpgengine.quests.QuestChanges.NONE, List.of(),
                new ResolvedCommit.EncounterEntry(frozen, 0)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, tactical, List.of(), List.of(event),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(),
                org.rpgengine.inventory.ItemPlan.NONE, org.rpgengine.quests.QuestChanges.NONE, List.of(),
                new ResolvedCommit.EncounterEntry(frozen, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit.EncounterEntry(frozen, -1));
        // Plans built before P11.1 carry no entry.
        assertNull(new ResolvedCommit(context, exploring, List.of(), List.of(event), List.of(), "{}", false).encounter());
    }

    /** Tactical progress keeps encounter mode, carries the scheduler's world time and never rides on an entry (P11.2). */
    @Test
    void tacticalProgressStaysInEncounterModeWithItsTime() {
        var context = new ResolvedCommit.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
        var event = new ResolvedCommit.Event(null, null, EventVisibility.PUBLIC,
                new CampaignEvent.Body("ROUND_ENDED", 1, 1, "{}"));
        var progress = new org.rpgengine.combat.TacticalPlan(UUID.randomUUID(), 4,
                List.of(new org.rpgengine.combat.TacticalPlan.Step(org.rpgengine.combat.EncounterPhase.ROUND_END, 1, null,
                        null, 0, List.of(), "NONE")), List.of(),
                List.of(new org.rpgengine.combat.TacticalPlan.Note("ROUND_ENDED", java.util.Map.of("round", 1), false)), 8,
                java.util.Map.of());
        var plan = new ResolvedCommit(context, new ResolvedCommit.WorldChange(true, 8, CampaignMode.ENCOUNTER, null),
                List.of(), List.of(event), List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(),
                List.of(), org.rpgengine.inventory.ItemPlan.NONE, org.rpgengine.quests.QuestChanges.NONE, List.of(), null,
                progress);
        assertEquals(progress, plan.progress());
        for (var world : List.of(new ResolvedCommit.WorldChange(true, 8, CampaignMode.EXPLORATION, null),
                new ResolvedCommit.WorldChange(true, 0, CampaignMode.ENCOUNTER, null))) {
            assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(),
                    List.of(event), List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(),
                    org.rpgengine.inventory.ItemPlan.NONE, org.rpgengine.quests.QuestChanges.NONE, List.of(), null,
                    progress), world.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> new org.rpgengine.combat.TacticalPlan(UUID.randomUUID(), 0,
                List.of(), List.of(), progress.notes(), 0, java.util.Map.of()), "a plan writes at least one state");
        assertNull(new ResolvedCommit(context, new ResolvedCommit.WorldChange(true, 0, CampaignMode.EXPLORATION, null),
                List.of(), List.of(event), List.of(), "{}", false).progress());
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

    /** Item creations must cite an event of the same plan, and a reward-sourced creation needs its settlement. */
    @Test
    void tiesItemCreationsToThePlansEventsAndRewards() {
        var context = new ResolvedCommit.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
        var world = new ResolvedCommit.WorldChange(true, 1, CampaignMode.EXPLORATION, null);
        var event = new ResolvedCommit.Event(null, null, EventVisibility.PUBLIC,
                new CampaignEvent.Body("ITEM_CREATED", 1, 1, "{}"));
        UUID actor = UUID.randomUUID();
        var coin = new ItemChange.Insert(UUID.randomUUID(), actor, "coin", 3, 9);
        java.util.function.Function<ItemPlan.Creation, ItemPlan> plan = creation ->
                new ItemPlan(List.of(coin), List.of(creation), List.of(), List.of());
        var effect = new ItemPlan.Creation(actor, "coin", 3, ItemPlan.SourceKind.EFFECT, "op#effects/0", 0);
        var committed = new ResolvedCommit(context, world, List.of(), List.of(event), List.of(), "{}", false,
                ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), plan.apply(effect));
        assertEquals(List.of(coin), committed.items().changes());
        // The announcing event must exist in this plan.
        var late = new ItemPlan.Creation(actor, "coin", 3, ItemPlan.SourceKind.EFFECT, "op#effects/0", 1);
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(), List.of(event),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), plan.apply(late)));
        // A reward-sourced creation names a settlement in the same plan, by its exact source key.
        var reward = new ItemPlan.Creation(actor, "coin", 3, ItemPlan.SourceKind.REWARD, "quest:rescue", 0);
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(), List.of(event),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), plan.apply(reward)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCommit(context, world, List.of(), List.of(event),
                List.of(new ResolvedCommit.Reward("quest:other", "hero", "{}")), "{}", false,
                ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), plan.apply(reward)));
        new ResolvedCommit(context, world, List.of(), List.of(event),
                List.of(new ResolvedCommit.Reward("quest:rescue", "hero", "{}")), "{}", false,
                ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), plan.apply(reward));
        // Older constructors carry no item effects.
        assertEquals(ItemPlan.NONE, new ResolvedCommit(context, world, List.of(), List.of(event), List.of(), "{}", false).items());
    }
}
