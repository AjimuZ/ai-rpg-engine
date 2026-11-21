package org.rpgengine.creative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.combat.CombatAbility;
import org.rpgengine.combat.CombatAbility.Kind;
import org.rpgengine.combat.CombatEffect;
import org.rpgengine.rules.CombatProfile;

/**
 * Read-only validation preview of one creative ability draft (P15.4-A, docs/10 section 9, docs/20 scenario 7): whether the
 * draft fits its profile, what it would cost, whom it would take, how long its effects would last, and what keeps the user from
 * using it now. Pure and deterministic.
 *
 * <p><b>A preview never executes a draft.</b> It takes only immutable values and returns a value: it spends nothing, moves
 * nobody, records no event, grants no learned ability and holds no command. The {@link Ready#candidate()} is the frozen
 * ability the draft would become, under the reserved id {@link #CANDIDATE_ID}, so it is never one of the user's learned
 * abilities. Using or learning a draft needs a separate explicit player operation, which must run this preview again against
 * the state current at that moment; a preview shown earlier proves nothing about a later state.
 *
 * <p>The check is bounded: a draft with more steps than its profile allows is rejected before any step is read, so a proposal
 * cannot make the preview do unbounded work.
 */
public final class DraftPreview {
    /** Reserved id of every candidate; a learning operation assigns the real id. */
    public static final String CANDIDATE_ID = "draft";
    /** Longest draft label. */
    public static final int MAX_LABEL = 80;

    /** Static entry point only. */
    private DraftPreview() {
    }

    /**
     * What the user has now, as far as using a draft is concerned.
     *
     * @param focus Focus the user has; 0 when the combat profile has no Focus
     * @param abilities IDs of abilities the user has learned
     */
    public record Caster(int focus, Set<String> abilities) {
        /** Freeze the abilities. */
        public Caster {
            abilities = Collections.unmodifiableSet(new TreeSet<>(abilities));
        }
    }

    /**
     * One problem, as a fixed code and the JSON-pointer-like path of the draft part it concerns.
     *
     * @param code fixed problem code (see {@link DraftPreview#preview})
     * @param path where it is, for example {@code /steps/1/effectId}
     */
    public record Issue(String code, String path) {
        /** Require both parts. */
        public Issue {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * How long one applied effect would last.
     *
     * @param effectId the frozen effect
     * @param clock the clock its duration counts on
     * @param count units of that clock; meaningless for the instant, next-activation and persistent clocks
     */
    public record Duration(String effectId, CombatEffect.Clock clock, int count) {
    }

    /** The outcome of one preview. */
    public sealed interface Result permits Rejected, Ready {
    }

    /**
     * The draft breaks its profile's bounds; nothing about cost or targets is shown.
     *
     * @param issues every problem found, in draft order; never empty
     */
    public record Rejected(List<Issue> issues) implements Result {
        /** Freeze the issues. */
        public Rejected {
            issues = List.copyOf(issues);
        }
    }

    /**
     * The draft fits its profile.
     *
     * @param label the draft's label
     * @param candidate the frozen ability it would become, with the derived costs, under {@link DraftPreview#CANDIDATE_ID}
     * @param complexity its complexity, within the profile's budget
     * @param durations each applied effect's duration, in step order
     * @param zeroCost whether the profile deliberately charges nothing
     * @param blockers what keeps the user from using it now, in a fixed order: missing prerequisites by ID, then Focus
     */
    public record Ready(String label, CombatAbility candidate, int complexity, List<Duration> durations, boolean zeroCost,
            List<Issue> blockers) implements Result {
        /** Freeze the lists. */
        public Ready {
            Objects.requireNonNull(candidate, "candidate");
            durations = List.copyOf(durations);
            blockers = List.copyOf(blockers);
        }

        /**
         * @return whether nothing blocks the draft at the previewed state; an execution must still check again at its own
         *         state, so this is never a permission
         */
        public boolean unblocked() {
            return blockers.isEmpty();
        }
    }

    /**
     * Validate a draft and preview its cost, targets and durations.
     *
     * <p>Rejection codes: {@code label} (blank or longer than {@link #MAX_LABEL}); {@code operation-count} (no step, or more
     * than the profile allows); {@code target-count} (outside 1..the profile's most, or more than one for {@code SELF});
     * {@code range}; {@code kind-not-allowed}; {@code operand} (an amount kind naming an effect, or an effect kind naming
     * none); {@code dice} (a negative count, more than an attack's dice in one step, sides outside an attack's, or more dice in
     * total than the profile allows); {@code amount} (flat or penetration outside the bounds); {@code attribute} (not a
     * ruleset role); {@code unknown-effect} (not permitted by the profile, or not in the catalog); {@code duration} (a
     * persistent effect the profile does not permit, or a count above its most); {@code complexity} (above the budget; checked
     * only when nothing else is wrong).
     *
     * <p>Complexity, a provisional measure: each damage or healing step counts 1 plus its dice, its positive flat part, its
     * penetration and 1 for an attribute; each applied effect counts 2 plus the effect's magnitude; each removal counts 2. The
     * sum is multiplied by the most targets and the range is added. The candidate costs the profile's action points, Focus of
     * complexity times the Focus rate and a cooldown of complexity times the cooldown rate, with no use limit.
     *
     * <p>Blocker codes, which leave the draft valid: {@code prerequisite-missing} at {@code /prerequisites/<id>} and
     * {@code insufficient-focus} at {@code /focus}.
     *
     * @param profile the bounds and cost rules
     * @param draft the proposal
     * @param effects frozen effect definitions by ID that the campaign's content declares
     * @param caster what the user has now
     * @return {@link Rejected} with every problem, or {@link Ready}; no input is modified and nothing is stored
     */
    public static Result preview(CreativeProfile profile, AbilityDraft draft, Map<String, CombatEffect> effects,
            Caster caster) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(caster, "caster");
        var issues = new ArrayList<Issue>();
        if (draft.label().isBlank() || draft.label().length() > MAX_LABEL) {
            issues.add(new Issue("label", "/label"));
        }
        if (draft.maxTargets() < 1 || draft.maxTargets() > profile.maxTargets()
                || draft.targets() == CombatAbility.Targets.SELF && draft.maxTargets() != 1) {
            issues.add(new Issue("target-count", "/maxTargets"));
        }
        if (draft.range() < 0 || draft.range() > profile.maxRange()) {
            issues.add(new Issue("range", "/range"));
        }
        if (draft.steps().isEmpty() || draft.steps().size() > profile.maxOperations()) {
            issues.add(new Issue("operation-count", "/steps"));
            return new Rejected(issues);
        }
        int dice = 0;
        var durations = new ArrayList<Duration>();
        for (int index = 0; index < draft.steps().size(); index++) {
            AbilityDraft.Step step = draft.steps().get(index);
            String at = "/steps/" + index;
            if (!profile.kinds().contains(step.kind())) {
                issues.add(new Issue("kind-not-allowed", at + "/kind"));
            }
            if (amount(step.kind())) {
                if (step.effectId() != null) {
                    issues.add(new Issue("operand", at + "/effectId"));
                }
                if (step.diceCount() < 0 || step.diceCount() > CombatProfile.ATTACK_DICE_MAX || step.diceCount() > 0
                        && (step.diceSides() < 2 || step.diceSides() > CombatProfile.ATTACK_SIDES_MAX)) {
                    issues.add(new Issue("dice", at + "/diceCount"));
                } else {
                    dice += step.diceCount();
                }
                if (step.flat() < CombatProfile.ATTACK_FLAT_MIN || step.flat() > profile.maxFlat() || step.penetration() < 0
                        || step.penetration() > CombatProfile.ATTACK_PENETRATION_MAX) {
                    issues.add(new Issue("amount", at + "/flat"));
                }
                if (step.attribute() != null && !CombatProfile.ROLES.contains(step.attribute())) {
                    issues.add(new Issue("attribute", at + "/attribute"));
                }
            } else if (step.effectId() == null || step.diceCount() != 0 || step.flat() != 0 || step.penetration() != 0
                    || step.attribute() != null) {
                issues.add(new Issue("operand", at + "/effectId"));
            } else if (!profile.effects().contains(step.effectId()) || !effects.containsKey(step.effectId())) {
                issues.add(new Issue("unknown-effect", at + "/effectId"));
            } else if (step.kind() == Kind.APPLY) {
                CombatEffect effect = effects.get(step.effectId());
                if (!lasts(profile, effect)) {
                    issues.add(new Issue("duration", at + "/effectId"));
                }
                durations.add(new Duration(effect.id(), effect.clock(), effect.count()));
            }
        }
        if (dice > profile.maxDice()) {
            issues.add(new Issue("dice", "/steps"));
        }
        if (!issues.isEmpty()) {
            return new Rejected(issues);
        }
        int complexity = complexity(draft, effects);
        if (complexity > profile.complexityBudget()) {
            return new Rejected(List.of(new Issue("complexity", "/steps")));
        }
        var operations = draft.steps().stream().map(step -> new CombatAbility.Operation(step.kind(), step.diceCount(),
                step.diceSides(), step.attribute(), step.flat(), step.penetration(), step.effectId(), step.toSelf())).toList();
        var candidate = new CombatAbility(CANDIDATE_ID, profile.actionPoints(), CombatAbility.Limit.NONE,
                complexity * profile.cooldownPerComplexity(), complexity * profile.focusPerComplexity(), draft.targets(),
                draft.maxTargets(), draft.range(), operations);
        var blockers = new ArrayList<Issue>();
        for (String required : profile.prerequisites()) {
            if (!caster.abilities().contains(required)) {
                blockers.add(new Issue("prerequisite-missing", "/prerequisites/" + required));
            }
        }
        if (candidate.focus() > caster.focus()) {
            blockers.add(new Issue("insufficient-focus", "/focus"));
        }
        return new Ready(draft.label(), candidate, complexity, durations, profile.zeroCost(), blockers);
    }

    /** @return whether a kind carries a rolled amount rather than an effect */
    private static boolean amount(Kind kind) {
        return kind == Kind.DAMAGE || kind == Kind.HEAL;
    }

    /** @return whether an effect's duration fits the profile */
    private static boolean lasts(CreativeProfile profile, CombatEffect effect) {
        return switch (effect.clock()) {
            case PERSISTENT -> profile.persistentEffects();
            case ACTIVATIONS, ROUND_ENDS, WORLD_SECONDS -> effect.count() <= profile.maxEffectCount();
            case INSTANT, NEXT_ACTIVATION -> true;
        };
    }

    /** Complexity of a draft whose every step is already within its bounds; see {@link #preview}. */
    private static int complexity(AbilityDraft draft, Map<String, CombatEffect> effects) {
        int sum = 0;
        for (AbilityDraft.Step step : draft.steps()) {
            sum += switch (step.kind()) {
                case DAMAGE, HEAL -> 1 + step.diceCount() + Math.max(0, step.flat()) + step.penetration()
                        + (step.attribute() == null ? 0 : 1);
                case APPLY -> 2 + effects.get(step.effectId()).magnitude();
                case REMOVE -> 2;
            };
        }
        return sum * draft.maxTargets() + draft.range();
    }
}
