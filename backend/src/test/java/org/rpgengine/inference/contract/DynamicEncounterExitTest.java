package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.contract.ContractFixtures.binding;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CapabilitySnapshot;
import org.rpgengine.combat.EncounterDifficulty;
import org.rpgengine.combat.EncounterFeasibility;
import org.rpgengine.combat.EncounterObjective;
import org.rpgengine.combat.EncounterOption;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.combat.FrozenEncounter;
import org.rpgengine.combat.OpeningPolicy;
import org.rpgengine.combat.PlanningFixtures;
import org.rpgengine.inference.contract.EncounterAdmission.Result;
import org.rpgengine.inference.contract.EncounterAdmission.Source;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.rules.ZoneGraph;

/**
 * P13 exit, planning half (P13.5-A, docs/12 section 3, QA-14): two eligible scenes at different locations, planned from the same
 * party, yield valid encounters that differ. Built only from the existing pieces: {@link EncounterOption} eligibility, the
 * threat budget, {@link EncounterFallback} and {@link EncounterAdmission}.
 *
 * <p>Fixture: the {@link EncounterAdmissionTest} party (one level-1 player at Standard, band exactly 8, wielding a {@code blade}).
 * The {@code trailhead} scene offers the ambush and the patrol, and the runtime composes a raider. The {@code ford} scene offers
 * a one-elite ford crossing (cost 12, over the band) and then a two-minor ford crossing (4 + 4), and the runtime cannot compose.
 *
 * <p>Not covered here: settlement and progression "exactly once from final state". Nothing calls them from play yet.
 */
class DynamicEncounterExitTest {
    static final String FORD = "ford";
    final EncounterContract contract = new EncounterContract();

    /** Two eligible scenes: a proposed composition in one, the first authored template that fits in the other; both valid. */
    @Test
    void twoEligibleScenesYieldValidAndDifferentEncounters() {
        var trailhead = EncounterContractTest.context(SupportedLocale.EN);
        var ford = fordContext();
        var entry = EncounterAdmissionTest.entry(EncounterAdmissionTest.ENCOUNTER, EncounterAdmissionTest.facts("blade"));

        var first = (Result.Admitted) EncounterAdmission.admit(
                EncounterFallback.decide(EncounterAdmissionTest.accepted(trailhead, raiderComposition(trailhead)), trailhead),
                trailhead, entry);
        var refused = new ContractOutcome.Refused<EncounterProposal>("context-scope", ford.binding());
        var second = (Result.Admitted) EncounterAdmission.admit(EncounterFallback.decide(refused, ford), ford, entry);

        assertThat(first.source()).isEqualTo(Source.PROPOSED);
        assertThat(first.frozen().templateId()).isEqualTo("ambush");
        assertThat(hostiles(first)).containsExactly("raider");
        assertThat(second.source()).isEqualTo(Source.AUTHORED);
        assertThat(second.cause()).isEqualTo(EncounterFallback.Cause.PROPOSAL_INVALID);
        assertThat(second.frozen().templateId()).as("the elite crossing is over the band, the pair fits").isEqualTo("ford-pair");
        assertThat(hostiles(second)).containsExactly("eel", "heron");

        for (var scene : List.of(first, second)) {
            var context = scene == first ? trailhead : ford;
            var budget = context.budget();
            assertThat(scene.threat().cost()).as("inside the band of the party").isBetween(budget.minimum(), budget.maximum());
            assertThat(EncounterFeasibility.check(scene.frozen()).findings()).as("no unreachable objective or hard counter")
                    .isEmpty();
            assertThat(scene.frozen().participants()).extracting(FrozenEncounter.Participant::id).startsWith("player");
        }

        assertThat(first.encounter().location()).isNotEqualTo(second.encounter().location());
        assertThat(first.frozen().templateId()).isNotEqualTo(second.frozen().templateId());
        assertThat(hostiles(first)).isNotEqualTo(hostiles(second));
        assertThat(first.frozen().definitionHash()).isNotEqualTo(second.frozen().definitionHash());
    }

    /** The same two scenes planned again give the same encounters: planning is deterministic. */
    @Test
    void planningTheSameScenesAgainGivesTheSameEncounters() {
        var entry = EncounterAdmissionTest.entry(EncounterAdmissionTest.ENCOUNTER, EncounterAdmissionTest.facts("blade"));
        var hashes = new java.util.ArrayList<String>();
        for (int run = 0; run < 2; run++) {
            var trailhead = EncounterContractTest.context(SupportedLocale.EN);
            var ford = fordContext();
            var first = (Result.Admitted) EncounterAdmission.admit(
                    EncounterFallback.decide(EncounterAdmissionTest.accepted(trailhead, raiderComposition(trailhead)), trailhead),
                    trailhead, entry);
            var refused = new ContractOutcome.Refused<EncounterProposal>("context-scope", ford.binding());
            var second = (Result.Admitted) EncounterAdmission.admit(EncounterFallback.decide(refused, ford), ford, entry);
            hashes.add(first.frozen().definitionHash() + " " + second.frozen().definitionHash());
        }
        assertThat(hashes.get(1)).isEqualTo(hashes.get(0));
    }

    /** A scene whose templates all exceed the band yields no encounter, never an unavoidable fight. */
    @Test
    void aSceneWithNoTemplateInsideTheBandYieldsNoEncounter() {
        var ford = new EncounterContext(binding(SupportedLocale.EN), fordSnapshot(), List.of(new EncounterOption(fordElite(), 1)));
        var refused = new ContractOutcome.Refused<EncounterProposal>("context-scope", ford.binding());
        var entry = EncounterAdmissionTest.entry(EncounterAdmissionTest.ENCOUNTER, EncounterAdmissionTest.facts("blade"));
        assertThat(EncounterAdmission.admit(EncounterFallback.decide(refused, ford), ford, entry))
                .isEqualTo(new Result.NoEncounter(EncounterFallback.Cause.PROPOSAL_INVALID, "context-scope"));
    }

    /** The runtime's accepted composition for the trailhead: the raider alone (cost 8) on the ridge. */
    EncounterProposal.Composed raiderComposition(EncounterContext context) {
        var body = EncounterContractTest.body(context.snapshot().hash(), "ambush",
                List.of(EncounterContractTest.place("raider", "ridge")), null);
        var validation = contract.validate(context, output(contract, context, "proposed", body));
        assertThat(validation.accepted()).as(validation.violations().toString()).isTrue();
        return (EncounterProposal.Composed) validation.value();
    }

    /** Hostile participant IDs of the admitted encounter, in authored order. */
    static List<String> hostiles(Result.Admitted admitted) {
        return admitted.encounter().participants().stream().filter(p -> p.side() == EncounterTemplate.Side.HOSTILE)
                .map(EncounterTemplate.Participant::id).toList();
    }

    /** The ford scene: the over-band elite crossing is preferred, the two-minor pair is next. */
    static EncounterContext fordContext() {
        return new EncounterContext(binding(SupportedLocale.EN), fordSnapshot(),
                List.of(new EncounterOption(fordElite(), 1), new EncounterOption(fordPair(), 1)));
    }

    /** The fixture player alone at the ford, full Health, at Standard. */
    static CapabilitySnapshot fordSnapshot() {
        var player = new CapabilitySnapshot.Actor(PlanningFixtures.PLAYER, CapabilitySnapshot.Membership.PLAYER,
                CapabilitySnapshot.Status.ACTIVE, 1, new FrozenEncounter.Pool(8, 8), Map.of(), List.of(), Set.of(), Set.of(),
                Map.of(), Map.of());
        return new CapabilitySnapshot(PlanningFixtures.PACK_HASH, "profile-hash", 7, FORD, Set.of("shallows"),
                EncounterDifficulty.STANDARD, List.of(player));
    }

    /** One elite heron: cost 12, over the Standard band of 8. */
    static EncounterTemplate fordElite() {
        return crossing("ford-elite", List.of(hostile("heron", EncounterTemplate.Threat.ELITE)));
    }

    /** An eel and a heron, minor each: cost 4 + 4 = 8, exactly the band. */
    static EncounterTemplate fordPair() {
        return crossing("ford-pair", List.of(hostile("eel", EncounterTemplate.Threat.MINOR),
                hostile("heron", EncounterTemplate.Threat.MINOR)));
    }

    static EncounterTemplate.Participant hostile(String id, EncounterTemplate.Threat threat) {
        return new EncounterTemplate.Participant(id, EncounterTemplate.Side.HOSTILE, "reeds", null, threat,
                EncounterAdmissionTest.stats(1));
    }

    /** A bank for the player and reeds for the hostiles, to be defeated. */
    static EncounterTemplate crossing(String id, List<EncounterTemplate.Participant> hostiles) {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        zones.put("bank", EncounterAdmissionTest.zone("bank", 0));
        zones.put("reeds", EncounterAdmissionTest.zone("reeds", 0));
        return new EncounterTemplate(id, "pack", FORD, new ZoneGraph(zones, List.of(EncounterAdmissionTest.edge("bank", "reeds"),
                EncounterAdmissionTest.edge("reeds", "bank"))), "bank", Set.of(), hostiles,
                List.of(new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0)),
                OpeningPolicy.INITIATIVE);
    }
}
