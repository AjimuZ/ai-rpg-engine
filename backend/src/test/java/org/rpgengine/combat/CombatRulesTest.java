package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * P11.1 projection and freezing: the compiled fixture yields the resolved profiles and templates, freezing is pure
 * and reproduces the worked example's derived values, and a customized profile runs through the same kernel and
 * freeze code with only its data changed.
 */
class CombatRulesTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    /** Construct a stateless fixture. */
    CombatRulesTest() {
    }

    /** The fixture's profile, templates, zones, roster, objectives and policies reach the rules unchanged. */
    @Test
    void projectsProfilesAndTemplates() throws Exception {
        var rules = rules(EncounterPack.files());
        var profile = rules.profiles().get("sample:armory");
        assertEquals(CombatProfile.defaults(), profile.values());
        assertEquals("vigor", profile.health());
        assertNull(profile.focus());
        assertEquals(Set.of("trail-ambush", "toll-parley", "grove-watch"), rules.templates().keySet());
        var ambush = rules.templates().get("trail-ambush");
        assertEquals("trailhead", ambush.location());
        assertEquals("entry", ambush.playerZone());
        assertEquals(Set.of("escape-route"), ambush.escapeZones());
        assertEquals(OpeningPolicy.INITIATIVE, ambush.opening());
        assertEquals(ZoneGraph.Cover.PARTIAL, ambush.zones().zones().get("yard").cover());
        assertEquals(2, ambush.zones().stepCost("entry", "escape-route", profile));
        assertEquals(1, ambush.zones().distance("entry", "escape-route"));
        var raider = ambush.participant("raider");
        assertEquals(EncounterTemplate.Side.HOSTILE, raider.side());
        assertEquals(14, raider.stats().health());
        assertEquals(1, raider.stats().armor());
        var parley = rules.templates().get("toll-parley");
        assertEquals(OpeningPolicy.FORBIDDEN, parley.opening());
        assertEquals("warden", parley.participant("keeper").npc());
        assertEquals(-1, parley.zones().stepCost("gate", "booth", profile));
        assertTrue(parley.zones().zones().get("booth").obscured());
        assertEquals(List.of(EncounterObjective.Kind.SURVIVE, EncounterObjective.Kind.PROTECT),
                parley.objectives().stream().map(EncounterObjective::kind).toList());
        assertEquals(OpeningPolicy.FIRST, rules.templates().get("grove-watch").opening());
    }

    /** Freezing derives the player's defenses from the role mapping, like the worked fight, and is reproducible. */
    @Test
    void freezingIsPureAndFollowsTheDefaultFormulas() throws Exception {
        var rules = rules(EncounterPack.files());
        var ambush = rules.templates().get("trail-ambush");
        var profile = rules.profiles().get("sample:armory");
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(6, 8), null, List.of("sword", "cloak"), Set.of("dazed"));
        var frozen = FrozenEncounter.freeze(ID, ambush, profile, facts, "raider");
        var player = frozen.player();
        assertEquals(EncounterTemplate.PLAYER, player.id());
        assertEquals(PLAYER, player.actorId());
        assertEquals(12, player.guard(), "10 + finesse 2");
        assertEquals(12, player.resistance(), "10 + resolve 2");
        assertEquals(4, player.initiativeModifier(), "finesse 2 + insight 2");
        assertEquals(0, player.armor());
        assertEquals(6, player.health(), "entry refills nothing");
        assertEquals(8, player.healthMax());
        assertEquals(List.of("cloak", "sword"), player.equipment());
        assertEquals(List.of("dazed"), player.conditions());
        var raider = frozen.participants().get(1);
        assertEquals(2, raider.initiativeModifier());
        assertEquals(12, raider.guard());
        assertNull(raider.actorId());
        assertEquals("raider", frozen.openingTarget());
        // Same inputs, same bytes; any input change moves the hash.
        var again = FrozenEncounter.freeze(ID, ambush, profile, facts, "raider");
        assertEquals(frozen.definition(), again.definition());
        assertEquals(frozen.definitionHash(), again.definitionHash());
        assertNotEquals(frozen.definitionHash(), FrozenEncounter.freeze(ID, ambush, profile, facts, null).definitionHash());
        assertTrue(frozen.definition().contains("\"opening\":{\"policy\":\"initiative\",\"strike\":\"raider\"}"),
                frozen.definition());
        assertTrue(frozen.definition().contains("\"profileHash\":\"" + profile.hash() + "\""));
    }

    /** A missing role attribute or an unlawful opening is a programming error, never a silent default. */
    @Test
    void freezingRefusesContradictions() throws Exception {
        var rules = rules(EncounterPack.files());
        var profile = rules.profiles().get("sample:armory");
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L), new FrozenEncounter.Pool(6, 8),
                null, List.of(), Set.of());
        assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID, rules.templates().get("trail-ambush"),
                profile, facts, null));
        var full = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(6, 8), null, List.of(), Set.of());
        assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID, rules.templates().get("toll-parley"),
                profile, full, "smuggler"));
        assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID, rules.templates().get("trail-ambush"),
                profile, full, "nobody"));
        var keeperless = FrozenEncounter.freeze(ID, rules.templates().get("toll-parley"), profile, full, null);
        assertEquals(EncounterTemplate.Side.ALLY, keeperless.participants().get(1).side());
        assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID, rules.templates().get("toll-parley"),
                profile, full, "keeper"));
    }

    /**
     * Supported customization changes data only: the tuned pack resolves different parameter values, the same kernel
     * identity, the same zone-graph and freeze code paths and a different frozen identity.
     */
    @Test
    void customizationChangesDataNotTheKernel() throws Exception {
        var standard = rules(EncounterPack.files());
        var tuned = rules(EncounterPack.tuned());
        var profile = tuned.profiles().get("sample:armory-tuned");
        assertEquals(4, profile.actionPoints());
        assertEquals(6, profile.roundSeconds());
        assertEquals(3, profile.value("strikeCost"));
        assertEquals(12, profile.roundCap());
        assertEquals(1, profile.reactionsPerRound(), "an omitted parameter keeps its default");
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(8, 8), null, List.of(), Set.of());
        var frozen = FrozenEncounter.freeze(ID, tuned.templates().get("trail-ambush"), profile, facts, null);
        assertEquals(13, frozen.player().guard(), "guardBase 11 + finesse 2");
        assertTrue(profile.canonical().contains("\"kernel\":\"" + CombatProfile.KERNEL + "\""));
        var base = standard.profiles().get("sample:armory");
        assertEquals(base.canonical().replaceAll("\"values\":\\{.*}", ""),
                profile.canonical().replaceAll("\"values\":\\{.*}", "").replace("sample:armory-tuned", "sample:armory"),
                "only the parameter values differ");
        assertNotEquals(base.hash(), profile.hash());
    }

    /** The template value refuses structural contradictions even when built outside the compiler. */
    @Test
    void templatesRepeatTheStructuralInvariants() throws Exception {
        var ambush = rules(EncounterPack.files()).templates().get("trail-ambush");
        var hostile = ambush.participants().getFirst();
        assertThrows(IllegalArgumentException.class, () -> copy(ambush, "escape-route", ambush.escapeZones(),
                ambush.participants()));
        assertThrows(IllegalArgumentException.class, () -> copy(ambush, "entry", Set.of("nowhere"), ambush.participants()));
        assertThrows(IllegalArgumentException.class, () -> copy(ambush, "entry", ambush.escapeZones(), List.of()));
        var crowd = List.of(hostile, rename(hostile, "b"), rename(hostile, "c"), rename(hostile, "d"));
        var limit = assertThrows(IllegalArgumentException.class, () -> copy(ambush, "entry", ambush.escapeZones(), crowd));
        assertTrue(limit.getMessage().contains("party size"), limit.getMessage());
        copy(ambush, "entry", ambush.escapeZones(), crowd.subList(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new EncounterTemplate.Participant("player",
                EncounterTemplate.Side.ALLY, "yard", null, EncounterTemplate.Threat.MINOR, hostile.stats()));
        assertThrows(IllegalArgumentException.class, () -> new EncounterTemplate.Stats(0, 0, 10, 10, 0,
                hostile.stats().roles()));
        assertThrows(IllegalArgumentException.class, () -> new EncounterTemplate.Stats(5, 0, 10, 10, 0,
                Map.of("force", 7, "finesse", 0, "insight", 0, "resolve", 0)));
    }

    private static EncounterTemplate copy(EncounterTemplate template, String playerZone, Set<String> escapes,
            List<EncounterTemplate.Participant> participants) {
        return new EncounterTemplate(template.id(), template.pack(), template.location(), template.zones(), playerZone,
                escapes, participants, template.objectives(), template.opening());
    }

    private static EncounterTemplate.Participant rename(EncounterTemplate.Participant participant, String id) {
        return new EncounterTemplate.Participant(id, participant.side(), participant.zone(), participant.npc(),
                participant.threat(), participant.stats());
    }

    /** Compile a fixture variant and project its combat rules. */
    private static CombatRules rules(Map<String, byte[]> files) {
        return EncounterPack.rules(files);
    }
}
