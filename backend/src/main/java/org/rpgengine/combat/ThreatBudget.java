package org.rpgengine.combat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.rpgengine.rules.CombatProfile;

/**
 * Deterministic threat and roster limits of one encounter plan (P13.1, docs/12 section 4): the party's capacity, the
 * threat band of the selected difficulty and the hostile-body limit, all derived from a {@link CapabilitySnapshot}, and
 * the check that admits or refuses a proposed hostile roster against them.
 *
 * <p>Everything is integer or exact rational arithmetic, so the same snapshot and roster always give the same budget and
 * verdict on every host. The heuristic is the provisional calibration model of the docs/11 section 17 register,
 * identified by {@link #HEURISTIC}; it does not estimate a win probability, and passing it proves no balance.
 *
 * <ul>
 *   <li>Capacity: each contributing actor adds {@code (8 + 2 × (level - 1)) × preparedness}, with preparedness
 *       {@code 0.55 + 0.30 × healthRatio + 0.15 × resourceRatio}; the sum is rounded down, at least 1 for a party with a
 *       contributing actor. The resource ratio is the lowest ratio among the resources the actor relies on, 1 when it
 *       relies on none. Both ratios lie in 0..1, so the documented clamp to 0.55..1.00 never binds.</li>
 *   <li>Band: a roster's threat must lie inside {@code capacity × lowest% .. capacity × highest%} of the difficulty,
 *       inclusive, compared exactly without rounding the bounds first.</li>
 *   <li>Cost: minor 4, standard 8, elite 12, anchor 20 at level 1, scaled by {@code ceil(base × (1 + 0.20 × (level - 1)))}.</li>
 *   <li>Hostile bodies: at most the party size plus {@link EncounterTemplate#EXTRA_HOSTILES}, and never more than one fight's
 *       {@link CombatProfile#PARTICIPANTS_MAX} non-player participants.</li>
 * </ul>
 *
 * @param difficulty selected difficulty policy
 * @param partySize contributing actors; 0 means no roster can be admitted
 * @param capacity party capacity, 0 only for an empty party
 * @param minimum smallest admitted roster threat, {@code ceil(capacity × lowest% / 100)}
 * @param maximum largest admitted roster threat, {@code floor(capacity × highest% / 100)}; may be below {@code minimum}
 *        when no integer threat fits the band
 * @param hostileLimit most hostile bodies an admitted roster may hold
 */
public record ThreatBudget(EncounterDifficulty difficulty, int partySize, int capacity, int minimum, int maximum,
        int hostileLimit) {
    /** Identity of the heuristic below; a retuned value is a new identity, recorded in every snapshot's canonical text. */
    public static final String HEURISTIC = "threat-heuristic/1";
    /** Capacity of a level-1 actor at full preparedness. */
    public static final int BASE_CAPACITY = 8;
    /** Capacity added by each level above 1. */
    public static final int CAPACITY_PER_LEVEL = 2;
    /** Preparedness terms, in percent: the floor, the Health weight and the resource weight; they sum to 100. */
    public static final int PREPAREDNESS_FLOOR = 55;
    public static final int HEALTH_WEIGHT = 30;
    public static final int RESOURCE_WEIGHT = 15;
    /** Threat cost added by each level above 1, in percent of the level-1 cost. */
    public static final int COST_PER_LEVEL_PERCENT = 20;
    /** Stable identifier rule of a roster entry, the same rule pack identifiers follow. */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._:-]{0,127}");

    /** Why a roster is refused. The enum name is the stable code a repair prompt or a log can carry. */
    public enum Violation {
        /** The snapshot has no contributing actor, so there is no band to fit. */
        NO_ACTIVE_PARTY,
        /** The roster names no hostile body. */
        EMPTY_ROSTER,
        /** An entry's ID is not a lowercase stable identifier. */
        INVALID_ID,
        /** An entry uses the ID reserved for the player. */
        RESERVED_ID,
        /** Two entries share an ID. */
        DUPLICATE_ID,
        /** An entry's level is outside {@link CapabilitySnapshot#LEVEL_MIN}..{@link CapabilitySnapshot#LEVEL_MAX}. */
        LEVEL_OUT_OF_RANGE,
        /** The roster holds more hostile bodies than {@link ThreatBudget#hostileLimit()}. */
        TOO_MANY_HOSTILES,
        /** The roster's threat exceeds the band. */
        OVER_BUDGET,
        /** The roster's threat falls short of the band, so it would misstate the selected difficulty. */
        UNDER_BUDGET
    }

    /**
     * One proposed hostile body: a catalog tier at a level.
     *
     * @param id roster ID the body would freeze under
     * @param threat catalog threat tier
     * @param level template level; checked by {@link #check}, not here, so an out-of-range proposal is reported
     */
    public record Entry(String id, EncounterTemplate.Threat threat, int level) {
        /** Require an ID and a tier. */
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(threat, "threat");
        }
    }

    /**
     * One reason for refusal.
     *
     * @param violation what is wrong
     * @param subject the entry ID it concerns, or null for the roster as a whole
     */
    public record Finding(Violation violation, String subject) {
        /** Require the violation. */
        public Finding {
            Objects.requireNonNull(violation, "violation");
        }
    }

    /**
     * Result of {@link #check}.
     *
     * @param cost total threat of the entries whose level is in range
     * @param hostiles hostile bodies in the roster
     * @param findings every reason for refusal, in a fixed order: the party, then each entry in roster order, then the
     *        roster-wide limits; empty when admitted
     */
    public record Verdict(int cost, int hostiles, List<Finding> findings) {
        /** Freeze the findings. */
        public Verdict {
            findings = List.copyOf(findings);
        }

        /** @return whether the roster is admitted */
        public boolean admitted() {
            return findings.isEmpty();
        }
    }

    /** Check the derived values are consistent. */
    public ThreatBudget {
        Objects.requireNonNull(difficulty, "difficulty");
        if (partySize < 0 || capacity < 0 || minimum < 0 || maximum < 0 || hostileLimit < 0
                || (partySize == 0) != (capacity == 0)) {
            throw new IllegalArgumentException("inconsistent threat budget");
        }
    }

    /**
     * Derive the budget of a snapshot.
     *
     * @param snapshot the party's capabilities and the selected difficulty
     * @return capacity, band and hostile limit
     */
    public static ThreatBudget of(CapabilitySnapshot snapshot) {
        int partySize = snapshot.partySize();
        int capacity = capacity(snapshot.actors());
        var difficulty = snapshot.difficulty();
        int minimum = (capacity * difficulty.lowestPercent() + 99) / 100;
        int maximum = capacity * difficulty.highestPercent() / 100;
        int hostileLimit = Math.min(partySize + EncounterTemplate.EXTRA_HOSTILES, CombatProfile.PARTICIPANTS_MAX);
        return new ThreatBudget(difficulty, partySize, capacity, minimum, maximum, hostileLimit);
    }

    /**
     * Party capacity of docs/12 section 4.
     *
     * @param actors party members; defeated, absent, excluded and zero-Health actors contribute nothing
     * @return the rounded-down sum, at least 1 when any actor contributes, 0 otherwise
     */
    public static int capacity(List<CapabilitySnapshot.Actor> actors) {
        // Exact rationals: a binary floating sum could round 9.999... below an integer the exact sum reaches.
        var numerator = BigInteger.ZERO;
        var denominator = BigInteger.ONE;
        boolean any = false;
        for (CapabilitySnapshot.Actor actor : actors) {
            if (!actor.participates()) {
                continue;
            }
            any = true;
            var healthCurrent = BigInteger.valueOf(actor.health().current());
            var healthMaximum = BigInteger.valueOf(actor.health().maximum());
            var resourceCurrent = BigInteger.ONE;
            var resourceMaximum = BigInteger.ONE;
            for (FrozenEncounter.Pool pool : actor.resources().values()) {
                var current = BigInteger.valueOf(pool.current());
                var maximum = BigInteger.valueOf(pool.maximum());
                if (current.multiply(resourceMaximum).compareTo(resourceCurrent.multiply(maximum)) < 0) {
                    resourceCurrent = current;
                    resourceMaximum = maximum;
                }
            }
            long base = BASE_CAPACITY + (long) CAPACITY_PER_LEVEL * (actor.level() - 1);
            // base × (55 + 30 × hc/hm + 15 × rc/rm) / 100, over the common denominator 100 × hm × rm.
            var share = BigInteger.valueOf(PREPAREDNESS_FLOOR).multiply(healthMaximum).multiply(resourceMaximum)
                    .add(BigInteger.valueOf(HEALTH_WEIGHT).multiply(healthCurrent).multiply(resourceMaximum))
                    .add(BigInteger.valueOf(RESOURCE_WEIGHT).multiply(resourceCurrent).multiply(healthMaximum))
                    .multiply(BigInteger.valueOf(base));
            var scale = BigInteger.valueOf(100).multiply(healthMaximum).multiply(resourceMaximum);
            numerator = numerator.multiply(scale).add(share.multiply(denominator));
            denominator = denominator.multiply(scale);
            var common = numerator.gcd(denominator);
            if (common.signum() > 0) {
                numerator = numerator.divide(common);
                denominator = denominator.divide(common);
            }
        }
        if (!any) {
            return 0;
        }
        return Math.max(1, numerator.divide(denominator).intValueExact());
    }

    /**
     * Threat cost of one catalog body.
     *
     * @param threat catalog tier
     * @param level template level, {@link CapabilitySnapshot#LEVEL_MIN}..{@link CapabilitySnapshot#LEVEL_MAX}
     * @return {@code ceil(base × (1 + 0.20 × (level - 1)))}
     * @throws IllegalArgumentException for a level out of range
     */
    public static int cost(EncounterTemplate.Threat threat, int level) {
        if (!levelInRange(level)) {
            throw new IllegalArgumentException("level must be within " + CapabilitySnapshot.LEVEL_MIN + ".."
                    + CapabilitySnapshot.LEVEL_MAX);
        }
        int percent = 100 + COST_PER_LEVEL_PERCENT * (level - 1);
        return (baseCost(threat) * percent + 99) / 100;
    }

    /**
     * @param threat catalog tier
     * @return its level-1 cost: minor 4, standard 8, elite 12, anchor 20
     */
    public static int baseCost(EncounterTemplate.Threat threat) {
        return switch (threat) {
            case MINOR -> 4;
            case STANDARD -> 8;
            case ELITE -> 12;
            case ANCHOR -> 20;
        };
    }

    /**
     * Admit or refuse a proposed hostile roster. Nothing is thrown for a bad proposal: every problem is a finding, so a
     * single repair request can name all of them.
     *
     * @param roster proposed hostile bodies in proposal order
     * @return the verdict; admitted only with no finding
     */
    public Verdict check(List<Entry> roster) {
        var findings = new ArrayList<Finding>();
        if (partySize == 0) {
            findings.add(new Finding(Violation.NO_ACTIVE_PARTY, null));
        }
        var ids = new HashSet<String>();
        int cost = 0;
        for (Entry entry : roster) {
            if (!ID.matcher(entry.id()).matches()) {
                findings.add(new Finding(Violation.INVALID_ID, entry.id()));
            } else if (EncounterTemplate.PLAYER.equals(entry.id())) {
                findings.add(new Finding(Violation.RESERVED_ID, entry.id()));
            } else if (!ids.add(entry.id())) {
                findings.add(new Finding(Violation.DUPLICATE_ID, entry.id()));
            }
            if (levelInRange(entry.level())) {
                cost += cost(entry.threat(), entry.level());
            } else {
                findings.add(new Finding(Violation.LEVEL_OUT_OF_RANGE, entry.id()));
            }
        }
        if (roster.isEmpty()) {
            findings.add(new Finding(Violation.EMPTY_ROSTER, null));
        }
        // Without a contributing actor there is no band or party size to compare against; an empty roster has one finding.
        if (partySize > 0 && !roster.isEmpty()) {
            if (roster.size() > hostileLimit) {
                findings.add(new Finding(Violation.TOO_MANY_HOSTILES, null));
            }
            // Exact comparison against capacity × percent, so no rounded bound admits a roster the band excludes.
            if (100L * cost > (long) capacity * difficulty.highestPercent()) {
                findings.add(new Finding(Violation.OVER_BUDGET, null));
            } else if (100L * cost < (long) capacity * difficulty.lowestPercent()) {
                findings.add(new Finding(Violation.UNDER_BUDGET, null));
            }
        }
        return new Verdict(cost, roster.size(), findings);
    }

    private static boolean levelInRange(int level) {
        return level >= CapabilitySnapshot.LEVEL_MIN && level <= CapabilitySnapshot.LEVEL_MAX;
    }
}
