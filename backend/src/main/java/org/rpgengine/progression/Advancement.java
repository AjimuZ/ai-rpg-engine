package org.rpgengine.progression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.characters.CharacterRuleCatalog;
import org.rpgengine.rules.CharacterSheet;

/**
 * Level-and-training advancement (P13.4-B, docs/12 section 9): cumulative XP thresholds, the entitlements each gained level
 * grants, and the validated purchases that spend them. Pure and deterministic; nothing calls it yet.
 *
 * <p>Three rules always hold. A level's entitlements are granted once, by the award that first crosses its threshold: a
 * {@link Standing} records the level already reached, so an award of zero XP, a repeated total or a replay grants nothing.
 * Entitlements are never spent automatically: they stay pending in the standing until a {@link Purchase} spends them, and a
 * purchase changes nothing unless it is accepted whole. A gained level raises pool maxima but never a current value, so
 * advancement is not a refill.
 */
public final class Advancement {
    /** Most cumulative XP a standing holds; keeps every level and point count within {@code int}. */
    public static final long MAX_XP = 1_000_000_000L;
    /** Highest attribute value advancement can buy, under the attribute's own range. */
    public static final int ATTRIBUTE_CAP = 6;
    /** Highest skill rank advancement can buy, under {@code level + 1} and the skill's own maximum. */
    public static final int SKILL_RANK_CAP = 5;

    /** Static entry points only. */
    private Advancement() { }

    /**
     * A pack's advancement profile.
     *
     * @param maxLevel highest level, or null for none (the default; docs/11 section 17)
     * @param healthPerLevel Health maximum gained per level, 0 or more
     * @param focusPerLevel Focus maximum gained per level, 0 or more
     * @param abilityCost training points an ability unlock spends, 0 or more
     */
    public record Profile(Integer maxLevel, int healthPerLevel, int focusPerLevel, int abilityCost) {
        /**
         * The default profile: no maximum level, +2 Health and +1 Focus maximum per level, and an unlock costing one training
         * point. The unlock cost is a provisional default of this engine (ADR 0045), calibrated in P18.3.
         */
        public static final Profile DEFAULT = new Profile(null, 2, 1, 1);

        /** Require a positive maximum level and non-negative gains and cost. */
        public Profile {
            if (maxLevel != null && maxLevel < 1) {
                throw new IllegalArgumentException("a maximum level is at least 1");
            }
            if (healthPerLevel < 0 || focusPerLevel < 0 || abilityCost < 0) {
                throw new IllegalArgumentException("per-level gains and the unlock cost are not negative");
            }
        }

        /**
         * @param xp cumulative XP, 0..{@link #MAX_XP}
         * @return the highest level whose threshold {@code xp} reaches, capped at the maximum level
         */
        public int levelFor(long xp) {
            requireXp(xp);
            int level = 1;
            while ((maxLevel == null || level < maxLevel) && threshold(level + 1) <= xp) {
                level++;
            }
            return level;
        }
    }

    /**
     * @param level a level, 1 or more
     * @return the cumulative XP that reaches it, {@code 50 × level × (level - 1)}
     */
    public static long threshold(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("a level is at least 1");
        }
        return 50L * level * (level - 1);
    }

    /**
     * An actor's advancement record.
     *
     * @param xp cumulative XP, 0..{@link #MAX_XP}
     * @param level the level reached; every level up to it has granted its entitlements
     * @param trainingPoints unspent training points
     * @param attributePoints unspent attribute points
     */
    public record Standing(long xp, int level, int trainingPoints, int attributePoints) {
        /** A new character: no XP, level 1, nothing pending. */
        public static final Standing START = new Standing(0, 1, 0, 0);

        /** Require bounded XP, a positive level and non-negative pending points. */
        public Standing {
            requireXp(xp);
            if (level < 1 || trainingPoints < 0 || attributePoints < 0) {
                throw new IllegalArgumentException("a standing has a positive level and no negative points");
            }
        }
    }

    /**
     * A resource pool.
     *
     * @param current current value, 0..maximum
     * @param maximum maximum, 0 or more
     */
    public record Pool(int current, int maximum) {
        /** Require a current value within the maximum. */
        public Pool {
            if (maximum < 0 || current < 0 || current > maximum) {
                throw new IllegalArgumentException("a pool holds 0..maximum");
            }
        }
    }

    /**
     * The entitlements of one gained level.
     *
     * @param level the level gained
     * @param trainingPoints training points it grants, always 1
     * @param attributePoints attribute points it grants, 1 on an even level and 0 otherwise
     */
    public record Gain(int level, int trainingPoints, int attributePoints) { }

    /**
     * The effect of one XP award.
     *
     * @param before the standing awarded
     * @param after the standing with the XP added and every gained level's points pending
     * @param gains each gained level once, in ascending order; empty when no threshold is crossed
     * @param health Health with its maximum raised and its current value unchanged
     * @param focus Focus likewise, or null for an actor without Focus
     */
    public record Award(Standing before, Standing after, List<Gain> gains, Pool health, Pool focus) {
        /** Freeze the gains. */
        public Award {
            gains = List.copyOf(gains);
        }
    }

    /**
     * Add settled XP, for example a {@link ChallengeRewards.Plan#xp()}, after the settlement that owes it. Each level crossed
     * is recorded once with its entitlements; points stay pending and pools are not refilled.
     *
     * @param profile the advancement profile
     * @param standing the actor's current standing; its level must be the one its XP reaches under {@code profile}
     * @param xp XP to add, 0 or more
     * @param health the actor's Health
     * @param focus the actor's Focus, or null
     * @return the new standing, the gained levels and the raised pools
     * @throws IllegalArgumentException for an inconsistent standing, negative XP or a total over {@link #MAX_XP}
     */
    public static Award award(Profile profile, Standing standing, long xp, Pool health, Pool focus) {
        Objects.requireNonNull(health, "health");
        if (profile.levelFor(standing.xp()) != standing.level()) {
            throw new IllegalArgumentException("the standing's level does not match its XP under this profile");
        }
        if (xp < 0 || xp > MAX_XP - standing.xp()) {
            throw new IllegalArgumentException("an award adds 0 or more XP and keeps the total within " + MAX_XP);
        }
        long total = standing.xp() + xp;
        int level = profile.levelFor(total);
        var gains = new ArrayList<Gain>();
        int training = 0;
        int attributes = 0;
        for (int gained = standing.level() + 1; gained <= level; gained++) {
            var gain = new Gain(gained, 1, gained % 2 == 0 ? 1 : 0);
            gains.add(gain);
            training += gain.trainingPoints();
            attributes += gain.attributePoints();
        }
        int levels = gains.size();
        var after = new Standing(total, level, standing.trainingPoints() + training, standing.attributePoints() + attributes);
        return new Award(standing, after, gains, raise(health, profile.healthPerLevel(), levels),
                focus == null ? null : raise(focus, profile.focusPerLevel(), levels));
    }

    /**
     * The purchasable parts of a character, as stored: base attributes before conditions, skill ranks and learned abilities.
     *
     * @param attributes base attribute values by key
     * @param skills ranks by canonical skill ID; a missing skill has rank 0
     * @param abilities canonical IDs of learned abilities
     */
    public record Build(Map<String, Integer> attributes, Map<String, Integer> skills, Set<String> abilities) {
        /** Freeze the parts. */
        public Build {
            attributes = Map.copyOf(attributes);
            skills = Map.copyOf(skills);
            abilities = Set.copyOf(abilities);
        }
    }

    /**
     * The pinned rules purchases are checked against.
     *
     * @param profile the advancement profile
     * @param attributes attribute ranges of the actor's vocabulary by key
     * @param skills skill rules by canonical ID
     * @param abilities ability prerequisites by canonical ID
     */
    public record Rules(Profile profile, Map<String, CharacterSheet.Range> attributes,
            Map<String, CharacterRuleCatalog.Skill> skills, Map<String, CharacterRuleCatalog.Ability> abilities) {
        /** Require a profile and freeze the indexes. */
        public Rules {
            Objects.requireNonNull(profile, "profile");
            attributes = Map.copyOf(attributes);
            skills = Map.copyOf(skills);
            abilities = Map.copyOf(abilities);
        }

        /**
         * @param profile the advancement profile
         * @param catalog the campaign's pinned rules
         * @param vocabulary the vocabulary of the actor's creation profile
         * @return the rules for that actor
         */
        public static Rules of(Profile profile, CharacterRuleCatalog catalog, CharacterRuleCatalog.Vocabulary vocabulary) {
            return new Rules(profile, vocabulary.attributes(), catalog.skills(), catalog.abilities());
        }
    }

    /** One advancement choice the player commits. */
    public sealed interface Purchase permits SkillRank, AttributeIncrease, Unlock { }

    /**
     * Buy the next rank of a skill; it costs that new rank in training points.
     *
     * @param skillId canonical skill ID
     */
    public record SkillRank(String skillId) implements Purchase {
        /** Require the ID. */
        public SkillRank {
            Objects.requireNonNull(skillId, "skillId");
        }
    }

    /**
     * Raise a base attribute by one; it costs one attribute point.
     *
     * @param attribute attribute key of the actor's vocabulary
     */
    public record AttributeIncrease(String attribute) implements Purchase {
        /** Require the key. */
        public AttributeIncrease {
            Objects.requireNonNull(attribute, "attribute");
        }
    }

    /**
     * Learn an ability at rank one; it costs the profile's unlock cost in training points.
     *
     * @param abilityId canonical ability ID
     */
    public record Unlock(String abilityId) implements Purchase {
        /** Require the ID. */
        public Unlock {
            Objects.requireNonNull(abilityId, "abilityId");
        }
    }

    /** Why a purchase is refused. */
    public enum Refusal {
        /** The skill, attribute or ability is not in the pinned rules. */
        UNKNOWN_DEFINITION,
        /** The next rank exceeds {@code min(5, level + 1)} or the skill's own maximum. */
        RANK_CAP,
        /** The next value exceeds {@link Advancement#ATTRIBUTE_CAP} or the attribute's own maximum. */
        ATTRIBUTE_CAP,
        /** The ability is already learned. */
        ALREADY_LEARNED,
        /** A required skill rank or ability is missing. */
        PREREQUISITE_MISSING,
        /** Fewer training points are pending than the purchase costs. */
        NOT_ENOUGH_TRAINING,
        /** No attribute point is pending. */
        NO_ATTRIBUTE_POINT
    }

    /**
     * A purchase decision.
     *
     * @param refusal why it is refused, or null when accepted
     * @param trainingCost training points spent, 0 when refused
     * @param attributeCost attribute points spent, 0 when refused
     * @param standing the standing after the purchase; the given one when refused
     * @param build the build after the purchase; the given one when refused
     */
    public record Outcome(Refusal refusal, int trainingCost, int attributeCost, Standing standing, Build build) {
        /** @return whether the purchase was accepted */
        public boolean accepted() {
            return refusal == null;
        }
    }

    /**
     * Check and apply one purchase. Checks run in a fixed order: the definition, then its cap or learned state, then
     * prerequisites, then the pending points; the first failing one is reported.
     *
     * @param rules the pinned rules
     * @param standing the actor's standing with its pending points
     * @param build the actor's stored build
     * @param purchase the choice
     * @return the accepted result or the refusal; inputs are never modified
     */
    public static Outcome purchase(Rules rules, Standing standing, Build build, Purchase purchase) {
        return switch (Objects.requireNonNull(purchase, "purchase")) {
            case SkillRank rank -> {
                var skill = rules.skills().get(rank.skillId());
                if (skill == null) {
                    yield refuse(Refusal.UNKNOWN_DEFINITION, standing, build);
                }
                int next = build.skills().getOrDefault(rank.skillId(), 0) + 1;
                if (next > Math.min(Math.min(SKILL_RANK_CAP, standing.level() + 1), skill.maxRank())) {
                    yield refuse(Refusal.RANK_CAP, standing, build);
                }
                if (standing.trainingPoints() < next) {
                    yield refuse(Refusal.NOT_ENOUGH_TRAINING, standing, build);
                }
                var skills = new HashMap<>(build.skills());
                skills.put(rank.skillId(), next);
                yield new Outcome(null, next, 0, spend(standing, next, 0),
                        new Build(build.attributes(), skills, build.abilities()));
            }
            case AttributeIncrease increase -> {
                var range = rules.attributes().get(increase.attribute());
                Integer value = build.attributes().get(increase.attribute());
                if (range == null || value == null) {
                    yield refuse(Refusal.UNKNOWN_DEFINITION, standing, build);
                }
                if (value + 1 > Math.min(ATTRIBUTE_CAP, range.max())) {
                    yield refuse(Refusal.ATTRIBUTE_CAP, standing, build);
                }
                if (standing.attributePoints() < 1) {
                    yield refuse(Refusal.NO_ATTRIBUTE_POINT, standing, build);
                }
                var attributes = new HashMap<>(build.attributes());
                attributes.put(increase.attribute(), value + 1);
                yield new Outcome(null, 0, 1, spend(standing, 0, 1),
                        new Build(attributes, build.skills(), build.abilities()));
            }
            case Unlock unlock -> {
                var ability = rules.abilities().get(unlock.abilityId());
                if (ability == null) {
                    yield refuse(Refusal.UNKNOWN_DEFINITION, standing, build);
                }
                if (build.abilities().contains(unlock.abilityId())) {
                    yield refuse(Refusal.ALREADY_LEARNED, standing, build);
                }
                boolean ranks = ability.skills().entrySet().stream()
                        .allMatch(need -> build.skills().getOrDefault(need.getKey(), 0) >= need.getValue());
                if (!ranks || !build.abilities().containsAll(ability.abilities())) {
                    yield refuse(Refusal.PREREQUISITE_MISSING, standing, build);
                }
                int cost = rules.profile().abilityCost();
                if (standing.trainingPoints() < cost) {
                    yield refuse(Refusal.NOT_ENOUGH_TRAINING, standing, build);
                }
                var abilities = new TreeSet<>(build.abilities());
                abilities.add(unlock.abilityId());
                yield new Outcome(null, cost, 0, spend(standing, cost, 0),
                        new Build(build.attributes(), build.skills(), abilities));
            }
        };
    }

    /** Raise a pool's maximum without touching its current value. */
    private static Pool raise(Pool pool, int perLevel, int levels) {
        return new Pool(pool.current(), Math.addExact(pool.maximum(), Math.multiplyExact(perLevel, levels)));
    }

    /** Spend pending points. */
    private static Standing spend(Standing standing, int training, int attributes) {
        return new Standing(standing.xp(), standing.level(), standing.trainingPoints() - training,
                standing.attributePoints() - attributes);
    }

    /** A refusal that leaves both inputs as they were. */
    private static Outcome refuse(Refusal refusal, Standing standing, Build build) {
        return new Outcome(refusal, 0, 0, standing, build);
    }

    /** Fail unless XP is within 0..{@link #MAX_XP}. */
    private static void requireXp(long xp) {
        if (xp < 0 || xp > MAX_XP) {
            throw new IllegalArgumentException("XP is 0.." + MAX_XP);
        }
    }
}
