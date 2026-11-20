package org.rpgengine.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.progression.Advancement.Build;
import org.rpgengine.progression.Advancement.Outcome;
import org.rpgengine.progression.Advancement.Purchase;
import org.rpgengine.progression.Advancement.Refusal;
import org.rpgengine.progression.Advancement.Rules;
import org.rpgengine.progression.Advancement.Standing;

/**
 * Read-only preview and comparison of advancement choices (P13.5-A, docs/12 section 9): what each purchase would cost and
 * change, and how two proposed sequences of purchases end up side by side. Pure and deterministic; nothing calls it yet.
 *
 * <p>One rule always holds: a preview never changes a player's build. It runs {@link Advancement#purchase} on the inputs and
 * returns hypothetical standings and builds as values; the inputs are immutable, nothing is stored, and only a later
 * validated purchase operation of the caller can commit a choice.
 */
public final class AdvancementPreview {
    /** Static entry points only. */
    private AdvancementPreview() { }

    /** The part of a build a change touches. */
    public enum Kind {
        /** A base attribute value. */
        ATTRIBUTE,
        /** A skill rank. */
        SKILL,
        /** A learned ability: 0 when unknown, 1 when learned. */
        ABILITY
    }

    /**
     * One difference between two builds.
     *
     * @param kind the part of the build
     * @param key the attribute key, canonical skill ID or canonical ability ID
     * @param from its value in the first build
     * @param to its value in the second build
     */
    public record Change(Kind kind, String key, int from, int to) { }

    /**
     * What one purchase would do if the player committed it now.
     *
     * @param purchase the choice
     * @param outcome the decision: accepted with its costs and resulting standing and build, or refused with the reason
     * @param changes the build differences it would make; empty when refused
     */
    public record Option(Purchase purchase, Outcome outcome, List<Change> changes) {
        /** Freeze the changes. */
        public Option {
            changes = List.copyOf(changes);
        }

        /** @return whether the player could commit this choice now */
        public boolean available() {
            return outcome.accepted();
        }
    }

    /**
     * List every purchase the rules define for this actor, each checked as a single next choice from the same starting point.
     * Options are independent: committing one changes what the others would cost or allow.
     *
     * @param rules the pinned rules
     * @param standing the actor's standing with its pending points
     * @param build the actor's stored build
     * @return one option per attribute of the build, per skill and per ability of the rules, ordered attributes, skills,
     *     abilities and by key inside each group; refused choices are kept with their reason
     */
    public static List<Option> options(Rules rules, Standing standing, Build build) {
        Objects.requireNonNull(rules, "rules");
        var candidates = new ArrayList<Purchase>();
        for (String attribute : new TreeSet<>(build.attributes().keySet())) {
            candidates.add(new Advancement.AttributeIncrease(attribute));
        }
        for (String skill : new TreeSet<>(rules.skills().keySet())) {
            candidates.add(new Advancement.SkillRank(skill));
        }
        for (String ability : new TreeSet<>(rules.abilities().keySet())) {
            candidates.add(new Advancement.Unlock(ability));
        }
        var options = new ArrayList<Option>();
        for (Purchase candidate : candidates) {
            options.add(option(rules, standing, build, candidate));
        }
        return options;
    }

    /**
     * Preview one purchase.
     *
     * @param rules the pinned rules
     * @param standing the actor's standing
     * @param build the actor's stored build
     * @param purchase the choice
     * @return its decision and build changes; inputs are never modified
     */
    public static Option option(Rules rules, Standing standing, Build build, Purchase purchase) {
        var outcome = Advancement.purchase(rules, standing, build, purchase);
        return new Option(purchase, outcome, outcome.accepted() ? differences(build, outcome.build()) : List.of());
    }

    /**
     * A sequence of purchases applied in order to a copy of the actor's state.
     *
     * @param purchases the proposed choices
     * @param applied how many were accepted before the sequence stopped; equals the size when none was refused
     * @param refusal why the next choice was refused, or null when every choice was accepted
     * @param standing the standing after the accepted choices
     * @param build the build after the accepted choices
     * @param trainingSpent training points the accepted choices spend
     * @param attributesSpent attribute points the accepted choices spend
     * @param changes the differences between the actor's build and {@code build}
     */
    public record Path(List<Purchase> purchases, int applied, Refusal refusal, Standing standing, Build build,
            int trainingSpent, int attributesSpent, List<Change> changes) {
        /** Freeze the lists. */
        public Path {
            purchases = List.copyOf(purchases);
            changes = List.copyOf(changes);
        }

        /** @return whether every proposed choice was accepted */
        public boolean complete() {
            return refusal == null;
        }
    }

    /**
     * Apply a proposed sequence step by step, each against the result of the one before, and stop at the first refusal.
     *
     * @param rules the pinned rules
     * @param standing the actor's standing
     * @param build the actor's stored build
     * @param purchases the choices in the order the player would commit them
     * @return the hypothetical end state; the accepted prefix is applied and the rest is not; inputs are never modified
     */
    public static Path path(Rules rules, Standing standing, Build build, List<Purchase> purchases) {
        var proposed = List.copyOf(purchases);
        Standing currentStanding = standing;
        Build currentBuild = build;
        int training = 0;
        int attributes = 0;
        int applied = 0;
        Refusal refusal = null;
        for (Purchase purchase : proposed) {
            var outcome = Advancement.purchase(rules, currentStanding, currentBuild, purchase);
            if (!outcome.accepted()) {
                refusal = outcome.refusal();
                break;
            }
            currentStanding = outcome.standing();
            currentBuild = outcome.build();
            training += outcome.trainingCost();
            attributes += outcome.attributeCost();
            applied++;
        }
        return new Path(proposed, applied, refusal, currentStanding, currentBuild, training, attributes,
                differences(build, currentBuild));
    }

    /**
     * Two sequences previewed from the same starting point.
     *
     * @param left the first sequence
     * @param right the second sequence
     * @param differences how the two end builds differ, {@code from} being the left value and {@code to} the right one
     * @param trainingLeftOver pending training points left after the left sequence minus those after the right one
     * @param attributesLeftOver pending attribute points left after the left sequence minus those after the right one
     */
    public record Comparison(Path left, Path right, List<Change> differences, int trainingLeftOver, int attributesLeftOver) {
        /** Freeze the differences. */
        public Comparison {
            differences = List.copyOf(differences);
        }
    }

    /**
     * Compare two proposed sequences from the same standing and build, so a player can weigh them before committing either.
     *
     * @param rules the pinned rules
     * @param standing the actor's standing
     * @param build the actor's stored build
     * @param left the first sequence
     * @param right the second sequence
     * @return both previewed paths and how they differ; neither is committed and inputs are never modified
     */
    public static Comparison compare(Rules rules, Standing standing, Build build, List<Purchase> left, List<Purchase> right) {
        var first = path(rules, standing, build, left);
        var second = path(rules, standing, build, right);
        return new Comparison(first, second, differences(first.build(), second.build()),
                first.standing().trainingPoints() - second.standing().trainingPoints(),
                first.standing().attributePoints() - second.standing().attributePoints());
    }

    /** Every attribute, skill and ability whose value differs between two builds, ordered by kind and key. */
    private static List<Change> differences(Build from, Build to) {
        var changes = new ArrayList<Change>();
        for (String key : new TreeSet<>(union(from.attributes().keySet(), to.attributes().keySet()))) {
            int before = from.attributes().getOrDefault(key, 0);
            int after = to.attributes().getOrDefault(key, 0);
            if (before != after) {
                changes.add(new Change(Kind.ATTRIBUTE, key, before, after));
            }
        }
        for (String key : new TreeSet<>(union(from.skills().keySet(), to.skills().keySet()))) {
            int before = from.skills().getOrDefault(key, 0);
            int after = to.skills().getOrDefault(key, 0);
            if (before != after) {
                changes.add(new Change(Kind.SKILL, key, before, after));
            }
        }
        for (String key : new TreeSet<>(union(from.abilities(), to.abilities()))) {
            int before = from.abilities().contains(key) ? 1 : 0;
            int after = to.abilities().contains(key) ? 1 : 0;
            if (before != after) {
                changes.add(new Change(Kind.ABILITY, key, before, after));
            }
        }
        return changes;
    }

    /** The union of two key sets. */
    private static Set<String> union(Set<String> first, Set<String> second) {
        var all = new TreeSet<>(first);
        all.addAll(second);
        return all;
    }
}
