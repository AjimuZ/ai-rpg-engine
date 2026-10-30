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
        assertEquals(Set.of("trail-ambush", "toll-parley", "grove-watch", "yard-duel", "close-brawl", "close-rush"), rules.templates().keySet());
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
        // Distinct role values tell the formulas apart: each role reads only its own mapped attribute.
        var mapped = CombatProfile.resolve("pack", Map.of("force", "f", "finesse", "a", "insight", "i", "resolve", "r"),
                "vigor", null, Map.of());
        var distinct = FrozenEncounter.freeze(ID, relabel(ambush, "pack"), mapped, new FrozenEncounter.PlayerFacts(PLAYER,
                Map.of("f", 1L, "a", 2L, "i", 3L, "r", 4L), new FrozenEncounter.Pool(8, 8), null, List.of(), Set.of()),
                null).player();
        assertEquals(12, distinct.guard(), "10 + finesse 2");
        assertEquals(14, distinct.resistance(), "10 + resolve 4");
        assertEquals(5, distinct.initiativeModifier(), "finesse 2 + insight 3");
        assertEquals(Map.of("force", 1L, "finesse", 2L, "insight", 3L, "resolve", 4L), distinct.roles());
        assertEquals(Map.of("force", "might", "finesse", "awareness", "insight", "awareness", "resolve", "might"),
                profile.roles());
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

    /**
     * A missing role attribute, a role value outside the supported range or an unlawful opening is a programming
     * error, never a silent default.
     */
    @Test
    void freezingRefusesContradictions() throws Exception {
        var rules = rules(EncounterPack.files());
        var profile = rules.profiles().get("sample:armory");
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L), new FrozenEncounter.Pool(6, 8),
                null, List.of(), Set.of());
        assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID, rules.templates().get("trail-ambush"),
                profile, facts, null));
        for (long outside : List.of(-1L, 7L)) {
            var unsupported = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", outside, "might", 2L),
                    new FrozenEncounter.Pool(6, 8), null, List.of(), Set.of());
            assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID,
                    rules.templates().get("trail-ambush"), profile, unsupported, null));
        }
        var strongest = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 6L, "might", 0L),
                new FrozenEncounter.Pool(6, 8), null, List.of(), Set.of());
        assertEquals(12, FrozenEncounter.freeze(ID, rules.templates().get("trail-ambush"), profile, strongest, null)
                .player().initiativeModifier());
        var full = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(6, 8), null, List.of(), Set.of());
        assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID, rules.templates().get("toll-parley"),
                profile, full, "smuggler"));
        assertThrows(IllegalStateException.class, () -> FrozenEncounter.freeze(ID, rules.templates().get("trail-ambush"),
                profile, full, "nobody"));
        var keeperless = FrozenEncounter.freeze(ID, rules.templates().get("toll-parley"), profile, full, null);
        assertEquals(EncounterTemplate.Side.ALLY, keeperless.participants().get(1).side());
        var smuggler = keeperless.participants().get(2);
        assertEquals(2, smuggler.initiativeModifier(), "finesse 2 + insight 0");
        assertEquals(8, smuggler.health());
        assertEquals(10, smuggler.guard());
        assertEquals(9, smuggler.resistance());
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

    private static EncounterTemplate relabel(EncounterTemplate template, String pack) {
        return new EncounterTemplate(template.id(), pack, template.location(), template.zones(), template.playerZone(),
                template.escapeZones(), template.participants(), template.objectives(), template.opening());
    }

    private static EncounterTemplate.Participant rename(EncounterTemplate.Participant participant, String id) {
        return new EncounterTemplate.Participant(id, participant.side(), participant.zone(), participant.npc(),
                participant.threat(), participant.stats());
    }

    /** Compile a fixture variant and project its combat rules. */
    private static CombatRules rules(Map<String, byte[]> files) {
        return EncounterPack.rules(files);
    }

    /**
     * Every attack field survives the projection, explicit or defaulted, for template attacks and for weapons, whose
     * skill and ammunition resolve to canonical IDs; armor and Guard project from the item's combat object (P11.3).
     */
    @Test
    void projectsEffectsReactionsAndAbilities() throws Exception {
        var rules = EncounterPack.rules(EncounterPack.tactics());
        var poison = rules.effects().get("poisoned");
        assertEquals(CombatEffect.Stacking.ADDITIVE, poison.stacking());
        assertEquals(3, poison.cap());
        assertEquals(CombatEffect.Clock.ROUND_ENDS, poison.clock());
        assertEquals(2, poison.count());
        assertEquals(java.util.List.of(new CombatEffect.Tick(CombatEffect.Phase.ROUND_END, 1, false)), poison.ticks());
        assertEquals("poisoned", poison.stackingKey(), "a key defaults to the condition");
        assertEquals(3, rules.effects().get("warded").absorption());
        assertEquals(CombatEffect.Stacking.STRONGEST, rules.effects().get("warded").stacking(), "the documented default");
        assertEquals(-2, rules.effects().get("winded").attack());
        assertEquals(java.util.Set.of(CombatEffect.Prevents.ACTIONS), rules.effects().get("stunned").prevents());
        assertEquals(null, rules.effects().get("dazed"), "a condition without a combat block has no combat form");

        var steady = rules.abilities().get("steady");
        assertEquals(1, steady.cost());
        assertEquals(1, steady.focus());
        assertEquals(2, steady.cooldownRounds());
        assertEquals(CombatAbility.Targets.SELF, steady.targets());
        assertEquals(CombatAbility.Kind.HEAL, steady.operations().getFirst().kind());
        assertEquals(CombatAbility.Limit.ENCOUNTER, rules.abilities().get("chain-riposte").limit());
        assertEquals(CombatReaction.Kind.COUNTER, rules.reactions().get("riposte").kind());
        assertEquals(null, rules.abilities().get("riposte"), "a reaction is not an action");
        assertEquals(null, rules.abilities().get("master-riposte"), "no combat form: exploration only");

        var adder = rules.templates().get("venom-pit").participants().getFirst();
        assertEquals(CombatReaction.Policy.OFF, adder.policies().get("brace"));
        assertEquals(CombatReaction.Policy.FIRST, adder.policies().get("snap"));
        assertEquals("fang", adder.reactions().getFirst().attackId());
        assertEquals(new CombatAttack.OnHit("poisoned", CombatEffect.Phase.POST_DAMAGE, false), adder.attacks().getFirst().onHit().getFirst());

        // Freezing keeps only what the fight can reach and what the player has learned; the forms read back unchanged.
        var profile = rules.profiles().get("sample:armory-tactics");
        var facts = new FrozenEncounter.PlayerFacts(java.util.UUID.randomUUID(), java.util.Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(8, 8), new FrozenEncounter.Pool(6, 6), java.util.List.of(), java.util.Set.of("dazed"),
                java.util.Map.of(), java.util.Set.of("steady", "riposte", "unrelated"));
        var frozen = FrozenEncounter.freeze(java.util.UUID.randomUUID(), rules.templates().get("venom-pit"), profile, facts, null,
                rules.library());
        assertEquals(java.util.Set.of("poisoned"), frozen.effects().keySet(), "steady heals and the fang poisons: nothing else is reachable");
        var player = frozen.player();
        assertEquals(java.util.List.of("steady"), player.abilities().stream().map(CombatAbility::id).toList());
        assertEquals(java.util.List.of("riposte"), player.reactions().stream().map(CombatReaction::id).toList());
        assertEquals(CombatReaction.Policy.FIRST, player.policies().get("brace"));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var sheet = EncounterState.Sheet.fromCanonical(json.readValue(org.rpgengine.rules.CanonicalJson.write(player.canonicalForm()),
                java.util.Map.class));
        assertEquals(player.sheet(), sheet);
        var definition = json.readValue(frozen.definition(), java.util.Map.class);
        assertEquals(1, ((java.util.List<?>) definition.get("effects")).size());
        var plain = FrozenEncounter.freeze(frozen.id(), EncounterPack.rules(EncounterPack.files()).templates().get("trail-ambush"),
                EncounterPack.rules(EncounterPack.files()).profiles().get("sample:armory"), new FrozenEncounter.PlayerFacts(facts.actorId(),
                        facts.attributes(), facts.health(), null, java.util.List.of(), java.util.Set.of()), null);
        assertTrue(!plain.definition().contains("\"effects\"") && !plain.definition().contains("\"reactions\""),
                "a fight without them keeps the frozen text it had");
    }

    /** Attacks and worn gear freeze into the participants that use them, and read back identically. */
    @Test
    void projectsAttacksAndGear() throws Exception {
        var files = new java.util.TreeMap<>(EncounterPack.files());
        String encounters = new String(files.get("encounters.json"), java.nio.charset.StandardCharsets.UTF_8);
        String cudgel = "{\"id\":\"cudgel\",\"attribute\":\"force\",\"skill\":1,\"strongHit\":false,"
                + "\"damage\":{\"count\":1,\"sides\":6}}";
        assertTrue(encounters.contains(cudgel));
        String hex = "{\"id\":\"hex\",\"attribute\":\"resolve\",\"skill\":3,\"range\":2,\"penetration\":4,"
                + "\"defense\":\"resistance\",\"damage\":{\"count\":2,\"sides\":8,\"attribute\":\"none\",\"flat\":-1}}";
        String talon = "{\"id\":\"talon\",\"attribute\":\"finesse\",\"damage\":{\"sides\":4,\"attribute\":\"force\"}}";
        files.put("encounters.json", encounters.replace(cudgel, cudgel + "," + hex + "," + talon)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var rules = EncounterPack.rules(files);
        assertEquals(List.of(
                new CombatAttack("cudgel", "force", 1, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null),
                new CombatAttack("hex", "resolve", 3, 2, 8, null, -1, 4, 2, CombatAttack.Defense.RESISTANCE, true, null),
                new CombatAttack("talon", "finesse", 0, 1, 4, "force", 0, 0, 0, CombatAttack.Defense.GUARD, true, null)),
                rules.templates().get("yard-duel").participant("bandit").attacks());
        assertEquals(new EncounterGear(0, 0, "bladework",
                new CombatAttack("sword", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, true, null)),
                rules.gear().get("sword"));
        assertEquals(new EncounterGear(0, 1, null, null), rules.gear().get("cloak"));
        assertEquals(new CombatAttack("bolt-caster", "finesse", 0, 1, 6, "finesse", 0, 0, 1, CombatAttack.Defense.GUARD, true,
                "arrows"), rules.gear().get("bolt-caster").attack());
        assertNull(rules.gear().get("potion"), "an item without combat data has no entry");
    }
}
