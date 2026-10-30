package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.rpgengine.rules.CombatProfile;

/**
 * One frozen ability usable in a fight (P11.4, docs/11 section 7), the {@code combat} block of an ability the player has
 * learned when the fight starts. It declares its action-point and Focus costs, cooldown, target rule and a short list of
 * operations, and the scheduler resolves it from these frozen values and recorded draws, never from the pack.
 *
 * <p>Invariants, checked here and by the pack compiler: a normal ability costs at least one action point; a zero-point
 * ability needs an explicit once-per-activation or once-per-encounter limit, and no operation can restore availability,
 * so it cannot repeat for free. Its operations draw at most {@link #MAX_DICE} dice, which keeps an operation inside the
 * durable draw budget. Damage here is not an attack: there is no roll, and no reaction answers it.
 *
 * @param id canonical ability ID
 * @param cost action points, 0 only with a {@code limit}
 * @param limit how often it can be used regardless of its cooldown
 * @param cooldownRounds rounds that pass before it can be used again; 0 for none
 * @param focus Focus it costs
 * @param targets who it takes
 * @param maxTargets most explicit targets
 * @param range greatest zone distance to a target; line of sight and full cover apply as they do to attacks
 * @param operations what it does, in order, for each target
 */
public record CombatAbility(String id, int cost, Limit limit, int cooldownRounds, int focus, Targets targets, int maxTargets,
        int range, List<Operation> operations) {
    /** Most operations of one ability. */
    public static final int MAX_OPERATIONS = CombatProfile.ABILITY_OPERATIONS_MAX;
    /** Most dice of one ability, each one durable draw. */
    public static final int MAX_DICE = CombatProfile.ABILITY_DICE_MAX;
    /** Largest cooldown; a longer one is a once-per-encounter limit. */
    public static final int COOLDOWN_MAX = CombatProfile.ABILITY_COOLDOWN_MAX;
    /** Largest Focus cost. */
    public static final int FOCUS_MAX = CombatProfile.FOCUS_COST_MAX;

    /** How often an ability can be used, apart from its cooldown. */
    public enum Limit {
        NONE, ACTIVATION, ENCOUNTER;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Who an ability takes. */
    public enum Targets {
        /** The user alone; the intention names nobody. */
        SELF,
        /** Standing participants of the user's side, the user included. */
        ALLY,
        /** Standing participants of the opposing side. */
        HOSTILE;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What one operation does. */
    public enum Kind {
        /** Dice plus attribute and flat, reduced by armor and absorption. */
        DAMAGE,
        /** Dice plus attribute and flat as healing: at most the missing Health, and it never revives. */
        HEAL,
        /** Apply one frozen effect. */
        APPLY,
        /** End every instance of one frozen effect, the removal a persistent clock waits for. */
        REMOVE;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One operation.
     *
     * @param kind what it does
     * @param diceCount dice of a damage or healing amount, possibly none
     * @param diceSides faces of each die
     * @param attribute ruleset role added to the amount, or null
     * @param flat flat part of the amount
     * @param penetration armor a damage operation ignores
     * @param effectId the frozen effect of {@link Kind#APPLY} or {@link Kind#REMOVE}, or null
     * @param toSelf whether it affects the user rather than the targets
     */
    public record Operation(Kind kind, int diceCount, int diceSides, String attribute, int flat, int penetration,
            String effectId, boolean toSelf) {
        /** Check the combination; a violation here is a broken compiler guarantee. */
        public Operation {
            Objects.requireNonNull(kind, "kind");
            boolean amount = kind == Kind.DAMAGE || kind == Kind.HEAL;
            if (amount == (effectId != null) || diceCount < 0 || diceCount > CombatProfile.ATTACK_DICE_MAX
                    || diceCount > 0 && (diceSides < 2 || diceSides > CombatProfile.ATTACK_SIDES_MAX)
                    || flat < CombatProfile.ATTACK_FLAT_MIN || flat > CombatProfile.ATTACK_FLAT_MAX || penetration < 0
                    || penetration > CombatProfile.ATTACK_PENETRATION_MAX || attribute != null
                    && !CombatProfile.ROLES.contains(attribute)) {
                throw new IllegalArgumentException("ability operation has a value outside its bound");
            }
        }

        /** @return canonical frozen form */
        Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("kind", kind.wire());
            form.put("diceCount", diceCount);
            form.put("diceSides", diceSides);
            form.put("attribute", attribute);
            form.put("flat", flat);
            form.put("penetration", penetration);
            form.put("effectId", effectId);
            form.put("toSelf", toSelf);
            return form;
        }
    }

    /** Check every bound; a violation here is a broken compiler guarantee. */
    public CombatAbility {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(targets, "targets");
        operations = List.copyOf(operations);
        if (cost < 0 || cost > CombatProfile.PARAMETERS.get("actionPoints").max() || cost == 0 && limit == Limit.NONE
                || cooldownRounds < 0 || cooldownRounds > COOLDOWN_MAX || focus < 0 || focus > FOCUS_MAX
                || maxTargets < 1 || maxTargets > EncounterTemplate.MAX_PARTICIPANTS + 1 || range < 0
                || range > CombatProfile.PARAMETERS.get("maxRangeEdges").max() || operations.isEmpty()
                || operations.size() > MAX_OPERATIONS
                || operations.stream().mapToInt(Operation::diceCount).sum() > MAX_DICE) {
            throw new IllegalArgumentException("ability " + id + " has a value outside its bound");
        }
    }

    /** @return canonical frozen form, part of the participant's frozen profile */
    public Map<String, Object> canonicalForm() {
        var form = new LinkedHashMap<String, Object>();
        form.put("id", id);
        form.put("cost", cost);
        form.put("limit", limit.wire());
        form.put("cooldownRounds", cooldownRounds);
        form.put("focus", focus);
        form.put("targets", targets.wire());
        form.put("maxTargets", maxTargets);
        form.put("range", range);
        form.put("operations", operations.stream().map(operation -> (Object) operation.canonicalForm()).toList());
        return form;
    }

    /**
     * Read the canonical form {@link #canonicalForm()} wrote.
     *
     * @param form parsed canonical object
     * @return the ability
     * @throws IllegalArgumentException or {@link ClassCastException} for a malformed frozen form
     */
    public static CombatAbility fromCanonical(Map<?, ?> form) {
        var operations = new ArrayList<Operation>();
        ((List<?>) form.get("operations")).forEach(stored -> {
            var operation = (Map<?, ?>) stored;
            operations.add(new Operation(Kind.valueOf(((String) operation.get("kind")).toUpperCase(Locale.ROOT)),
                    ((Number) operation.get("diceCount")).intValue(), ((Number) operation.get("diceSides")).intValue(),
                    (String) operation.get("attribute"), ((Number) operation.get("flat")).intValue(),
                    ((Number) operation.get("penetration")).intValue(), (String) operation.get("effectId"),
                    Boolean.TRUE.equals(operation.get("toSelf"))));
        });
        return new CombatAbility((String) form.get("id"), ((Number) form.get("cost")).intValue(),
                Limit.valueOf(((String) form.get("limit")).toUpperCase(Locale.ROOT)),
                ((Number) form.get("cooldownRounds")).intValue(), ((Number) form.get("focus")).intValue(),
                Targets.valueOf(((String) form.get("targets")).toUpperCase(Locale.ROOT)),
                ((Number) form.get("maxTargets")).intValue(), ((Number) form.get("range")).intValue(), operations);
    }

    /**
     * When an ability was last used by one participant, stored in its row.
     *
     * @param lastRound round of the last use, which is also the activation's: a participant activates once per round
     * @param uses uses so far in this encounter
     */
    public record Use(int lastRound, int uses) {
        /** Require a real use. */
        public Use {
            if (lastRound < 1 || uses < 1) {
                throw new IllegalArgumentException("an ability use has a round and a count");
            }
        }

        /** @return canonical stored form */
        public Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("lastRound", lastRound);
            form.put("uses", uses);
            return form;
        }
    }

    /**
     * Why this ability cannot be used now, ignoring the action points and targets. The limit and cooldown only count
     * rounds and uses: world time and refreshes cannot change them.
     *
     * @param use its stored last use, or null when never used
     * @param round the current round
     * @return {@code LIMIT}, {@code COOLDOWN} or null when neither applies
     */
    public String waiting(Use use, int round) {
        if (use == null) {
            return null;
        }
        if (limit == Limit.ENCOUNTER || limit == Limit.ACTIVATION && use.lastRound() >= round) {
            return "LIMIT";
        }
        return round < use.lastRound() + cooldownRounds ? "COOLDOWN" : null;
    }
}
