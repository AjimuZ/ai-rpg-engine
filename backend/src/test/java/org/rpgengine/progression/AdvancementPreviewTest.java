package org.rpgengine.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rpgengine.characters.CharacterRuleCatalog;
import org.rpgengine.progression.Advancement.AttributeIncrease;
import org.rpgengine.progression.Advancement.Build;
import org.rpgengine.progression.Advancement.Profile;
import org.rpgengine.progression.Advancement.Refusal;
import org.rpgengine.progression.Advancement.Rules;
import org.rpgengine.progression.Advancement.SkillRank;
import org.rpgengine.progression.Advancement.Standing;
import org.rpgengine.progression.Advancement.Unlock;
import org.rpgengine.progression.AdvancementPreview.Change;
import org.rpgengine.progression.AdvancementPreview.Kind;
import org.rpgengine.rules.CharacterSheet;

/**
 * Advancement preview and comparison (P13.5-A, docs/12 section 9): each choice is shown with its cost, changes or refusal
 * reason, two sequences are compared side by side, and no preview changes the player's standing or build.
 */
class AdvancementPreviewTest {
    private static final Rules RULES = new Rules(Profile.DEFAULT,
            Map.of("might", new CharacterSheet.Range(1, 8), "wit", new CharacterSheet.Range(1, 4)),
            Map.of("blade", new CharacterRuleCatalog.Skill(5, "might"), "lore", new CharacterRuleCatalog.Skill(2, "wit")),
            Map.of("cleave", new CharacterRuleCatalog.Ability(Map.of("blade", 2), Set.of()),
                    "whirl", new CharacterRuleCatalog.Ability(Map.of(), Set.of("cleave"))));
    private static final Standing PENDING = new Standing(650, 4, 3, 2);

    /** Construct a stateless fixture. */
    AdvancementPreviewTest() {
    }

    /** Every defined choice is listed once in a fixed order, with its changes or refusal, and nothing is committed. */
    @Test
    void optionsShowCostsChangesAndRefusalsWithoutChangingTheBuild() {
        var build = new Build(Map.of("might", 5, "wit", 4), Map.of("blade", 1, "lore", 2), Set.of());
        var options = AdvancementPreview.options(RULES, PENDING, build);

        assertEquals(List.of(new AttributeIncrease("might"), new AttributeIncrease("wit"), new SkillRank("blade"),
                new SkillRank("lore"), new Unlock("cleave"), new Unlock("whirl")),
                options.stream().map(AdvancementPreview.Option::purchase).toList());
        assertEquals(Arrays.asList(null, Refusal.ATTRIBUTE_CAP, null, Refusal.RANK_CAP,
                Refusal.PREREQUISITE_MISSING, Refusal.PREREQUISITE_MISSING),
                options.stream().map(option -> option.outcome().refusal()).toList());

        var might = options.get(0);
        assertTrue(might.available());
        assertEquals(1, might.outcome().attributeCost());
        assertEquals(List.of(new Change(Kind.ATTRIBUTE, "might", 5, 6)), might.changes());
        var blade = options.get(2);
        assertEquals(2, blade.outcome().trainingCost());
        assertEquals(List.of(new Change(Kind.SKILL, "blade", 1, 2)), blade.changes());
        assertFalse(options.get(1).available());
        assertEquals(List.of(), options.get(1).changes());

        assertEquals(new Standing(650, 4, 3, 2), PENDING);
        assertEquals(new Build(Map.of("might", 5, "wit", 4), Map.of("blade", 1, "lore", 2), Set.of()), build);
    }

    /** Without pending points every choice is refused and the preview offers nothing to commit. */
    @Test
    void nothingIsAvailableWithoutPendingPoints() {
        var build = new Build(Map.of("might", 5, "wit", 4), Map.of("blade", 1, "lore", 2), Set.of());
        var options = AdvancementPreview.options(RULES, Standing.START, build);
        assertTrue(options.stream().noneMatch(AdvancementPreview.Option::available));
        assertTrue(options.stream().allMatch(option -> option.changes().isEmpty()));
    }

    /** Two sequences end differently; the comparison shows both end builds, the differences and the points left. */
    @Test
    void comparisonShowsTwoSequencesSideBySideAndCommitsNeither() {
        var build = new Build(Map.of("might", 5, "wit", 4), Map.of("blade", 1, "lore", 2), Set.of());
        var comparison = AdvancementPreview.compare(RULES, PENDING, build,
                List.of(new SkillRank("blade"), new Unlock("cleave")),
                List.of(new AttributeIncrease("might"), new AttributeIncrease("might")));

        var left = comparison.left();
        assertTrue(left.complete());
        assertEquals(2, left.applied());
        assertEquals(3, left.trainingSpent());
        assertEquals(0, left.attributesSpent());
        assertEquals(new Standing(650, 4, 0, 2), left.standing());
        assertEquals(Set.of("cleave"), left.build().abilities());
        assertEquals(List.of(new Change(Kind.SKILL, "blade", 1, 2), new Change(Kind.ABILITY, "cleave", 0, 1)),
                left.changes());

        var right = comparison.right();
        assertFalse(right.complete());
        assertEquals(1, right.applied());
        assertEquals(Refusal.ATTRIBUTE_CAP, right.refusal());
        assertEquals(new Standing(650, 4, 3, 1), right.standing());
        assertEquals(List.of(new Change(Kind.ATTRIBUTE, "might", 5, 6)), right.changes());

        assertEquals(List.of(new Change(Kind.ATTRIBUTE, "might", 5, 6), new Change(Kind.SKILL, "blade", 2, 1),
                new Change(Kind.ABILITY, "cleave", 1, 0)), comparison.differences());
        assertEquals(-3, comparison.trainingLeftOver());
        assertEquals(1, comparison.attributesLeftOver());

        assertEquals(new Build(Map.of("might", 5, "wit", 4), Map.of("blade", 1, "lore", 2), Set.of()), build);
        assertEquals(new Standing(650, 4, 3, 2), PENDING);
    }

    /** An empty or immediately refused sequence changes nothing and equal sequences do not differ. */
    @Test
    void emptyRefusedAndEqualSequencesLeaveTheStateAlone() {
        var build = new Build(Map.of("might", 5, "wit", 4), Map.of("blade", 1, "lore", 2), Set.of());
        var empty = AdvancementPreview.path(RULES, PENDING, build, List.of());
        assertTrue(empty.complete());
        assertSame(build, empty.build());
        assertSame(PENDING, empty.standing());
        assertEquals(List.of(), empty.changes());

        var refused = AdvancementPreview.path(RULES, PENDING, build, List.of(new Unlock("whirl"), new SkillRank("blade")));
        assertEquals(0, refused.applied());
        assertEquals(Refusal.PREREQUISITE_MISSING, refused.refusal());
        assertSame(build, refused.build());
        assertNull(AdvancementPreview.path(RULES, PENDING, build, List.of(new SkillRank("blade"))).refusal());

        var same = AdvancementPreview.compare(RULES, PENDING, build, List.of(new SkillRank("blade")),
                List.of(new SkillRank("blade")));
        assertEquals(List.of(), same.differences());
        assertEquals(0, same.trainingLeftOver());
        assertEquals(0, same.attributesLeftOver());
    }
}
