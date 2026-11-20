package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatAttack;
import org.rpgengine.combat.EncounterFeasibility;
import org.rpgengine.combat.EncounterGear;
import org.rpgengine.combat.EncounterObjective;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.combat.FrozenEncounter;
import org.rpgengine.combat.OpeningPolicy;
import org.rpgengine.combat.PlanningFixtures;
import org.rpgengine.inference.contract.EncounterAdmission.Result;
import org.rpgengine.inference.contract.EncounterAdmission.Source;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Admission of a planned encounter (P13.3-A, docs/12 section 3 steps 6-9, QA-14): the decided encounter is frozen once for the
 * player, exactly as entry freezes it, and admitted only when no objective is unreachable and no hostile participant is a
 * hidden hard counter; an infeasible proposal or authored template is never entered, and an admitted definition is entered
 * only while it still matches the committed state.
 *
 * <p>Fixture: the {@link EncounterContractTest} party (one level-1 player at Standard, band exactly 8) with every role 2. Its
 * {@code blade} deals at most 8 in melee and its {@code pick} is the same blade with penetration 4. Authored templates beside
 * the ambush and the patrol: {@link #crypt()}, whose standard golem wears armor 10, and {@link #vault()}, whose standard warden
 * fills the only doorway to the vault the player must reach. Both cost exactly 8.
 */
class EncounterAdmissionTest {
    static final UUID ENCOUNTER = UUID.fromString("00000000-0000-4000-8000-0000000000f3");
    static final CombatProfile PROFILE = CombatProfile.resolve("pack",
            Map.of("force", "force", "finesse", "finesse", "insight", "insight", "resolve", "resolve"), "health", null, Map.of());
    static final FrozenEncounter.Library LIBRARY = library();
    final EncounterContract contract = new EncounterContract();

    /** QA-14 path: a feasible composition is frozen once, exactly as entry freezes it, and entered only while unchanged. */
    @Test
    void aFeasibleCompositionIsFrozenOnceAndEnteredOnlyWhileUnchanged() {
        var context = EncounterContractTest.context(SupportedLocale.EN);
        var composed = compose(context, "ambush", "raider", "ridge");
        var decision = EncounterFallback.decide(accepted(context, composed), context);
        var entry = entry(ENCOUNTER, facts("blade"));
        var admitted = (Result.Admitted) EncounterAdmission.admit(decision, context, entry);
        assertThat(admitted.source()).isEqualTo(Source.PROPOSED);
        assertThat(admitted.cause()).isNull();
        assertThat(admitted.detail()).isNull();
        assertThat(admitted.encounter()).isEqualTo(composed.encounter());
        assertThat(admitted.threat()).isEqualTo(composed.verdict());
        assertThat(admitted.frozen().definition()).isEqualTo(FrozenEncounter.freeze(ENCOUNTER, composed.encounter(), PROFILE,
                facts("blade"), null, LIBRARY).definition());
        assertThat(admitted.frozen().participants()).extracting(FrozenEncounter.Participant::id)
                .containsExactly("player", "guide", "raider");
        assertThat(admitted.frozen().participants().get(2).zone()).isEqualTo("ridge");
        assertThat(admitted.frozen().openingTarget()).isNull();
        String hash = admitted.frozen().definitionHash();

        assertThat(EncounterAdmission.unchanged(admitted, context, entry)).isTrue();
        assertThat(EncounterAdmission.unchanged(admitted, EncounterContractTest.context(SupportedLocale.EN),
                entry(ENCOUNTER, facts("blade")))).as("a fresh reading of the same state").isTrue();
        assertThat(EncounterAdmission.unchanged(admitted, context, entry(ENCOUNTER, facts()))).as("the blade was put away")
                .isFalse();
        assertThat(EncounterAdmission.unchanged(admitted, context,
                entry(UUID.fromString("00000000-0000-4000-8000-0000000000f4"), facts("blade")))).as("another identity").isFalse();
        assertThat(EncounterAdmission.unchanged(admitted, EncounterContractTest.context(SupportedLocale.EN, 6,
                PlanningFixtures.ambush(), PlanningFixtures.patrol()), entry)).as("hurt: another snapshot").isFalse();
        assertThat(EncounterAdmission.unchanged(admitted, EncounterContractTest.context(SupportedLocale.EN, 8,
                PlanningFixtures.patrol()), entry)).as("the ambush is no longer offered").isFalse();
        assertThat(admitted.frozen().definitionHash()).as("nothing changed the admitted definition").isEqualTo(hash);

        assertThatThrownBy(() -> new Result.Admitted(Source.PROPOSED, null, null, admitted.snapshotHash(), admitted.option(),
                crypt(), admitted.threat(), FrozenEncounter.freeze(ENCOUNTER, crypt(), PROFILE, facts("blade"), null, LIBRARY)))
                .as("an infeasible frozen encounter cannot be admitted").isInstanceOf(IllegalArgumentException.class);
    }

    /** QA-14: a proposed hidden hard counter is never entered; entry gets the first authored template that is also feasible. */
    @Test
    void aProposedHardCounterFallsBackToTheFirstFeasibleAuthoredTemplate() {
        var context = EncounterContractTest.context(SupportedLocale.EN, 8, crypt(), PlanningFixtures.patrol());
        var decision = EncounterFallback.decide(accepted(context, compose(context, "crypt", "golem", "vault")), context);
        assertThat(decision).as("the budget alone admits the golem").isInstanceOf(EncounterFallback.Decision.Proposed.class);

        var fallback = (Result.Admitted) EncounterAdmission.admit(decision, context, entry(ENCOUNTER, facts("blade")));
        assertThat(fallback.source()).isEqualTo(Source.AUTHORED);
        assertThat(fallback.cause()).isEqualTo(EncounterFallback.Cause.PROPOSAL_INVALID);
        assertThat(fallback.detail()).isEqualTo("unreachable-objective");
        assertThat(fallback.encounter()).as("the crypt's authored roster fits the band too, but is the same counter")
                .isEqualTo(PlanningFixtures.patrol());
        assertThat(fallback.frozen().templateId()).isEqualTo("patrol");
        assertThat(fallback.threat().cost()).isEqualTo(8);
        assertThat(EncounterFeasibility.check(FrozenEncounter.freeze(ENCOUNTER, crypt(), PROFILE, facts("blade"), null,
                LIBRARY)).findings()).extracting(finding -> finding.violation().code() + " " + finding.subject() + " "
                        + finding.reason().code())
                .containsExactly("unreachable-objective win countered", "hard-counter golem unharmable");

        var pierced = (Result.Admitted) EncounterAdmission.admit(decision, context, entry(ENCOUNTER, facts("pick")));
        assertThat(pierced.source()).as("penetration answers the armor").isEqualTo(Source.PROPOSED);
        assertThat(pierced.frozen().templateId()).isEqualTo("crypt");
    }

    /** QA-14: an authored fallback with an unreachable objective is skipped; with nothing feasible left, no fight starts. */
    @Test
    void anInfeasibleAuthoredFallbackIsSkippedOrGivesNoEncounter() {
        var context = EncounterContractTest.context(SupportedLocale.EN, 8, vault(), PlanningFixtures.patrol());
        var refused = new ContractOutcome.Refused<EncounterProposal>("context-scope", context.binding());
        var decision = (EncounterFallback.Decision.Fallback) EncounterFallback.decide(refused, context);
        assertThat(decision.authored().encounter()).as("the budget alone takes the vault").isEqualTo(vault());

        var admitted = (Result.Admitted) EncounterAdmission.admit(decision, context, entry(ENCOUNTER, facts("blade")));
        assertThat(admitted.source()).isEqualTo(Source.AUTHORED);
        assertThat(admitted.cause()).isEqualTo(EncounterFallback.Cause.PROPOSAL_INVALID);
        assertThat(admitted.detail()).as("a fallback keeps its own cause").isEqualTo("context-scope");
        assertThat(admitted.encounter()).isEqualTo(PlanningFixtures.patrol());

        assertThat(EncounterAdmission.admit(decision, context, entry(ENCOUNTER, facts())))
                .as("unarmed, the patrol's defeat objective is unreachable too")
                .isEqualTo(new Result.NoEncounter(EncounterFallback.Cause.PROPOSAL_INVALID, "context-scope"));
        var none = EncounterFallback.decide(refused, EncounterContractTest.context(SupportedLocale.EN, 8, PlanningFixtures.ambush()));
        assertThat(EncounterAdmission.admit(none, context, entry(ENCOUNTER, facts("blade"))))
                .isEqualTo(new Result.NoEncounter(EncounterFallback.Cause.PROPOSAL_INVALID, "context-scope"));

        var stranger = new FrozenEncounter.PlayerFacts(UUID.fromString("00000000-0000-4000-8000-0000000000f5"),
                facts("blade").attributes(), new FrozenEncounter.Pool(8, 8), null, List.of("blade"), Set.of());
        assertThatThrownBy(() -> EncounterAdmission.admit(decision, context, entry(ENCOUNTER, stranger)))
                .as("facts of another actor").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncounterAdmission.admit(decision, context, new EncounterAdmission.Entry(ENCOUNTER, Map.of(),
                facts("blade"), LIBRARY))).as("no profile for the pack").isInstanceOf(IllegalArgumentException.class);
    }

    /** A composition the contract accepts for one roster entry. */
    EncounterProposal.Composed compose(EncounterContext context, String template, String participant, String zone) {
        var body = EncounterContractTest.body(context.snapshot().hash(), template,
                List.of(EncounterContractTest.place(participant, zone)), null);
        var validation = contract.validate(context, output(contract, context, "proposed", body));
        assertThat(validation.accepted()).as(validation.violations().toString()).isTrue();
        return (EncounterProposal.Composed) validation.value();
    }

    static ContractOutcome.Accepted<EncounterProposal> accepted(EncounterContext context, EncounterProposal proposal) {
        return new ContractOutcome.Accepted<>(proposal, List.of(), context.binding(), List.of());
    }

    static EncounterAdmission.Entry entry(UUID id, FrozenEncounter.PlayerFacts facts) {
        return new EncounterAdmission.Entry(id, Map.of("pack", PROFILE), facts, LIBRARY);
    }

    /** The fixture player at 8 of 8 Health, every role 2, wearing the given items. */
    static FrozenEncounter.PlayerFacts facts(String... worn) {
        return new FrozenEncounter.PlayerFacts(PlanningFixtures.PLAYER,
                Map.of("force", 2L, "finesse", 2L, "insight", 2L, "resolve", 2L), new FrozenEncounter.Pool(8, 8), null,
                List.of(worn), Set.of());
    }

    static FrozenEncounter.Library library() {
        var gear = new TreeMap<String, EncounterGear>();
        gear.put("blade", new EncounterGear(0, 0, null,
                new CombatAttack("blade", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null)));
        gear.put("pick", new EncounterGear(0, 0, null,
                new CombatAttack("pick", "force", 0, 1, 6, "force", 0, 4, 0, CombatAttack.Defense.GUARD, false, null)));
        return new FrozenEncounter.Library(gear, Map.of(), Map.of(), Map.of());
    }

    /** One standard golem with armor 10 in the {@code vault} beside the player's {@code road}, to be defeated. */
    static EncounterTemplate crypt() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        zones.put("road", zone("road", 0));
        zones.put("vault", zone("vault", 0));
        return new EncounterTemplate("crypt", "pack", PlanningFixtures.LOCATION,
                new ZoneGraph(zones, List.of(edge("road", "vault"), edge("vault", "road"))), "road", Set.of(),
                List.of(new EncounterTemplate.Participant("golem", EncounterTemplate.Side.HOSTILE, "vault", null,
                        EncounterTemplate.Threat.STANDARD, stats(10))),
                List.of(new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0)),
                OpeningPolicy.INITIATIVE);
    }

    /** One standard warden filling the {@code door} (capacity 1), the only way from the player's {@code road} to the vault. */
    static EncounterTemplate vault() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        zones.put("road", zone("road", 0));
        zones.put("door", zone("door", 1));
        zones.put("vault", zone("vault", 0));
        return new EncounterTemplate("vault", "pack", PlanningFixtures.LOCATION,
                new ZoneGraph(zones, List.of(edge("road", "door"), edge("door", "road"), edge("door", "vault"),
                        edge("vault", "door"))), "road", Set.of(),
                List.of(new EncounterTemplate.Participant("warden", EncounterTemplate.Side.HOSTILE, "door", null,
                        EncounterTemplate.Threat.STANDARD, stats(1))),
                List.of(new EncounterObjective("loot", EncounterObjective.Kind.REACH_ZONE, "vault", null, 0)),
                OpeningPolicy.INITIATIVE);
    }

    /** Health 20, Guard and Resistance 10, every role 2 and the given armor. */
    static EncounterTemplate.Stats stats(int armor) {
        var roles = new HashMap<String, Integer>();
        CombatProfile.ROLES.forEach(role -> roles.put(role, 2));
        return new EncounterTemplate.Stats(20, 0, 10, 10, armor, roles);
    }

    static ZoneGraph.Zone zone(String id, int capacity) {
        return new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, false, capacity);
    }

    static ZoneGraph.Edge edge(String from, String to) {
        return new ZoneGraph.Edge(from, to, false, false);
    }
}
