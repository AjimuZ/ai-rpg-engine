package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.EncounterOption.Placement;
import org.rpgengine.combat.EncounterTemplate.Threat;

/**
 * Template-bounded composition and the deterministic safe fallback of encounter planning (P13.2-A, docs/12 section 3 steps 4
 * and 7): a composition only chooses which authored hostile participants take part and where each starts, and the fallback is
 * the first authored template, unchanged, that fits the threat budget, or none.
 */
class EncounterOptionTest {
    /** Construct a stateless fixture. */
    EncounterOptionTest() {
    }

    /** A variant keeps the authored order, the allies, statistics and everything else; only selection and zones change. */
    @Test
    void composesOnlyWhatTheAuthorDeclared() {
        var ambush = PlanningFixtures.ambush();
        var option = new EncounterOption(ambush, 1);
        var budget = ThreatBudget.of(PlanningFixtures.snapshot(EncounterDifficulty.STANDARD, 8));
        assertEquals(List.of("raider", "wolf", "hound", "brute"), option.hostiles().stream()
                .map(EncounterTemplate.Participant::id).toList());
        assertEquals(List.of("brush", "ridge", "road"), List.copyOf(option.startZones()), "the escape zone stays open");
        assertEquals(3, option.hostileLimit(budget), "party size 1 + 2, below the template's own limit of 4");
        assertEquals(List.of(new Placement("raider", "brush"), new Placement("wolf", "brush"), new Placement("hound", "ridge"),
                new Placement("brute", "brush")), option.authored());

        var placements = List.of(new Placement("hound", "brush"), new Placement("raider", "ridge"));
        assertEquals(List.of(new ThreatBudget.Entry("hound", Threat.MINOR, 1), new ThreatBudget.Entry("raider", Threat.STANDARD, 1)),
                option.entries(placements));
        var variant = option.compose(placements);
        assertEquals(List.of("guide", "raider", "hound"), variant.participants().stream()
                .map(EncounterTemplate.Participant::id).toList());
        assertEquals("ridge", variant.participant("raider").zone());
        assertEquals("brush", variant.participant("hound").zone());
        assertEquals(ambush.participant("raider").stats(), variant.participant("raider").stats());
        assertSame(ambush.participant("guide"), variant.participant("guide"));
        assertEquals(ambush.zones(), variant.zones());
        assertEquals(ambush.objectives(), variant.objectives());
        assertEquals(ambush.escapeZones(), variant.escapeZones());
        assertEquals(ambush.id(), variant.id());
        assertEquals(variant, option.compose(List.of(new Placement("raider", "ridge"), new Placement("hound", "brush"))),
                "the authored order, not the proposal order, decides the roster");

        assertEquals(Set.of("ridge"), option.overfilled(List.of(new Placement("wolf", "ridge"), new Placement("hound", "ridge"))));
        assertEquals(Set.of(), option.overfilled(option.authored()));
        for (var refused : List.of(List.of(new Placement("raider", "gate")), List.of(new Placement("guide", "brush")),
                List.of(new Placement("player", "brush")), List.of(new Placement("raider", "moon")),
                List.of(new Placement("wolf", "brush"), new Placement("wolf", "ridge")),
                List.of(new Placement("wolf", "ridge"), new Placement("hound", "ridge")), List.<Placement>of())) {
            assertThrows(IllegalArgumentException.class, () -> option.compose(refused), refused.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> new EncounterOption(ambush, 0));
        assertThrows(IllegalArgumentException.class, () -> new EncounterOption(ambush, CapabilitySnapshot.LEVEL_MAX + 1));
    }

    /** The fallback tries the options in order, uses an authored roster unchanged, and reports none when nothing fits. */
    @Test
    void theFallbackIsTheFirstAuthoredTemplateThatFits() {
        var ambush = new EncounterOption(PlanningFixtures.ambush(), 1);
        var patrol = new EncounterOption(PlanningFixtures.patrol(), 1);
        var standard = ThreatBudget.of(PlanningFixtures.snapshot(EncounterDifficulty.STANDARD, 8));

        var chosen = EncounterOption.fallback(List.of(ambush, patrol), standard);
        assertEquals(patrol, chosen.option(), "the ambush's authored roster costs 28, over the band of 8");
        assertSame(patrol.template(), chosen.encounter());
        assertEquals(8, chosen.verdict().cost());
        assertNull(EncounterOption.fallback(List.of(ambush), standard));

        // Hard asks for a threat of 10 or 11: the level-1 sentry (8) is under it, the level-2 sentry (10) fits.
        var hard = ThreatBudget.of(PlanningFixtures.snapshot(EncounterDifficulty.HARD, 8));
        assertNull(EncounterOption.fallback(List.of(patrol), hard));
        var veteran = new EncounterOption(PlanningFixtures.patrol(), 2);
        assertEquals(veteran, EncounterOption.fallback(List.of(patrol, veteran), hard).option());

        var fallen = ThreatBudget.of(PlanningFixtures.snapshot(EncounterDifficulty.STANDARD, 0));
        assertNull(EncounterOption.fallback(List.of(patrol), fallen), "a party without a contributing actor gets no fight");
    }
}
