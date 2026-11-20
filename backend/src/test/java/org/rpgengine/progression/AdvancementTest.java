package org.rpgengine.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rpgengine.characters.CharacterRuleCatalog;
import org.rpgengine.progression.Advancement.AttributeIncrease;
import org.rpgengine.progression.Advancement.Build;
import org.rpgengine.progression.Advancement.Gain;
import org.rpgengine.progression.Advancement.Outcome;
import org.rpgengine.progression.Advancement.Pool;
import org.rpgengine.progression.Advancement.Profile;
import org.rpgengine.progression.Advancement.Refusal;
import org.rpgengine.progression.Advancement.Rules;
import org.rpgengine.progression.Advancement.SkillRank;
import org.rpgengine.progression.Advancement.Standing;
import org.rpgengine.progression.Advancement.Unlock;
import org.rpgengine.rules.CharacterSheet;

/**
 * Level-and-training advancement (P13.4-B, docs/12 section 9, QA-15): each crossed threshold grants its exact entitlements
 * once, nothing is spent or refilled automatically, and a purchase spends pending points only when every rule holds.
 */
class AdvancementTest {
    private static final Rules RULES = new Rules(Profile.DEFAULT,
            Map.of("might", new CharacterSheet.Range(1, 8), "wit", new CharacterSheet.Range(1, 4)),
            Map.of("blade", new CharacterRuleCatalog.Skill(5, "might"), "lore", new CharacterRuleCatalog.Skill(2, "wit")),
            Map.of("cleave", new CharacterRuleCatalog.Ability(Map.of("blade", 2), Set.of()),
                    "whirl", new CharacterRuleCatalog.Ability(Map.of(), Set.of("cleave"))));
    private static final Build BUILD = new Build(Map.of("might", 5, "wit", 4), Map.of("blade", 1, "lore", 2), Set.of());

    /** Construct a stateless fixture. */
    AdvancementTest() {
    }

    /** The worked scenario of docs/20: 90 + 20 XP reaches level 2, and the maxima rise while the current values stay. */
    @Test
    void oneThresholdRaisesMaximaWithoutRefilling() {
        var award = Advancement.award(Profile.DEFAULT, new Standing(90, 1, 0, 0), 20, new Pool(24, 24), new Pool(10, 10));
        assertEquals(new Standing(110, 2, 1, 1), award.after());
        assertEquals(List.of(new Gain(2, 1, 1)), award.gains());
        assertEquals(new Pool(24, 26), award.health());
        assertEquals(new Pool(10, 11), award.focus());
        assertEquals(List.of(0L, 100L, 300L, 600L, 1000L),
                List.of(Advancement.threshold(1), Advancement.threshold(2), Advancement.threshold(3),
                        Advancement.threshold(4), Advancement.threshold(5)));
    }

    /** QA-15: one award crossing three thresholds records levels 2, 3 and 4 once each; later awards add only new levels. */
    @Test
    void crossingSeveralThresholdsGrantsExactEntitlementsOnce() {
        var award = Advancement.award(Profile.DEFAULT, Standing.START, 650, new Pool(14, 20), null);
        assertEquals(List.of(new Gain(2, 1, 1), new Gain(3, 1, 0), new Gain(4, 1, 1)), award.gains());
        assertEquals(new Standing(650, 4, 3, 2), award.after());
        assertEquals(new Pool(14, 26), award.health());
        assertNull(award.focus());

        var nothing = Advancement.award(Profile.DEFAULT, award.after(), 0, award.health(), null);
        assertEquals(List.of(), nothing.gains());
        assertEquals(award.after(), nothing.after());
        assertEquals(award.health(), nothing.health());

        var fifth = Advancement.award(Profile.DEFAULT, award.after(), 350, award.health(), new Pool(3, 9));
        assertEquals(List.of(new Gain(5, 1, 0)), fifth.gains());
        assertEquals(new Standing(1000, 5, 4, 2), fifth.after());
        assertEquals(new Pool(14, 28), fifth.health());
        assertEquals(new Pool(3, 10), fifth.focus());

        var once = Advancement.award(Profile.DEFAULT, Standing.START, 1000, new Pool(14, 20), null);
        assertEquals(fifth.after(), once.after());
        assertEquals(4, once.gains().size());

        var capped = new Profile(3, 2, 1, 1);
        var top = Advancement.award(capped, Standing.START, 5000, new Pool(10, 10), null);
        assertEquals(new Standing(5000, 3, 2, 1), top.after());
        assertEquals(new Pool(10, 14), top.health());
        assertEquals(List.of(), Advancement.award(capped, top.after(), 9000, top.health(), null).gains());
    }

    /** Pending points pay for ranks, unlocks and attributes, and every refusal leaves the standing and build unchanged. */
    @Test
    void purchasesSpendPendingEntitlementsOnlyWhenValid() {
        var standing = new Standing(650, 4, 3, 2);
        var rank = Advancement.purchase(RULES, standing, BUILD, new SkillRank("blade"));
        assertTrue(rank.accepted());
        assertEquals(2, rank.trainingCost());
        assertEquals(new Standing(650, 4, 1, 2), rank.standing());
        assertEquals(2, rank.build().skills().get("blade"));

        refused(Refusal.NOT_ENOUGH_TRAINING, rank, new SkillRank("blade"));
        refused(Refusal.PREREQUISITE_MISSING, rank, new Unlock("whirl"));
        refused(Refusal.PREREQUISITE_MISSING, Advancement.purchase(RULES, standing, BUILD, new AttributeIncrease("might")),
                new Unlock("cleave"));
        var cleave = Advancement.purchase(RULES, rank.standing(), rank.build(), new Unlock("cleave"));
        assertTrue(cleave.accepted());
        assertEquals(new Standing(650, 4, 0, 2), cleave.standing());
        assertEquals(Set.of("cleave"), cleave.build().abilities());
        refused(Refusal.ALREADY_LEARNED, cleave, new Unlock("cleave"));
        refused(Refusal.NOT_ENOUGH_TRAINING, cleave, new Unlock("whirl"));

        var might = Advancement.purchase(RULES, cleave.standing(), cleave.build(), new AttributeIncrease("might"));
        assertTrue(might.accepted());
        assertEquals(1, might.attributeCost());
        assertEquals(new Standing(650, 4, 0, 1), might.standing());
        assertEquals(6, might.build().attributes().get("might"));
        refused(Refusal.ATTRIBUTE_CAP, might, new AttributeIncrease("might"));
        refused(Refusal.ATTRIBUTE_CAP, might, new AttributeIncrease("wit"));
        refused(Refusal.UNKNOWN_DEFINITION, might, new AttributeIncrease("grace"));
        refused(Refusal.UNKNOWN_DEFINITION, might, new SkillRank("stealth"));
        refused(Refusal.UNKNOWN_DEFINITION, might, new Unlock("fireball"));

        var rich = new Standing(0, 1, 10, 0);
        var second = Advancement.purchase(RULES, rich, BUILD, new SkillRank("blade"));
        assertTrue(second.accepted());
        refused(Refusal.RANK_CAP, second, new SkillRank("blade"));
        refused(Refusal.RANK_CAP, second, new SkillRank("lore"));
        refused(Refusal.NO_ATTRIBUTE_POINT, second, new AttributeIncrease("might"));
        var master = new Build(BUILD.attributes(), Map.of("blade", 5), Set.of());
        assertEquals(Refusal.RANK_CAP, Advancement.purchase(RULES, new Standing(1000, 5, 10, 0), master,
                new SkillRank("blade")).refusal());
    }

    /** Inconsistent standings, negative or overflowing XP and malformed pools and profiles are refused. */
    @Test
    void malformedInputsAreRefused() {
        var pool = new Pool(5, 5);
        assertThrows(IllegalArgumentException.class,
                () -> Advancement.award(Profile.DEFAULT, new Standing(100, 1, 0, 0), 0, pool, null));
        assertThrows(IllegalArgumentException.class,
                () -> Advancement.award(Profile.DEFAULT, Standing.START, -1, pool, null));
        assertThrows(IllegalArgumentException.class,
                () -> Advancement.award(Profile.DEFAULT, Standing.START, Advancement.MAX_XP + 1, pool, null));
        assertThrows(IllegalArgumentException.class, () -> new Pool(6, 5));
        assertThrows(IllegalArgumentException.class, () -> new Standing(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Standing(0, 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Profile(0, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Profile(null, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Advancement.threshold(0));
    }

    /** Assert a purchase from an accepted outcome is refused for this reason and changes nothing. */
    private static void refused(Refusal reason, Outcome from, Advancement.Purchase purchase) {
        var outcome = Advancement.purchase(RULES, from.standing(), from.build(), purchase);
        assertEquals(reason, outcome.refusal());
        assertEquals(0, outcome.trainingCost());
        assertEquals(0, outcome.attributeCost());
        assertSame(from.standing(), outcome.standing());
        assertSame(from.build(), outcome.build());
    }
}
