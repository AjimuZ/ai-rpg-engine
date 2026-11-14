package org.rpgengine.combat;

import java.util.Locale;

/**
 * Selected difficulty policy of an encounter plan (P13.1, docs/12 section 4): the inclusive band of enemy threat an
 * admitted roster must cost, in percent of the party's capacity. The bands are the provisional values of the docs/11
 * section 17 register; they change only with {@link ThreatBudget#HEURISTIC}.
 */
public enum EncounterDifficulty {
    /** Teaching and low-stakes scenes: 60-80% of capacity. */
    GENTLE(60, 80),
    /** The default meaningful challenge: 90-110% of capacity. */
    STANDARD(90, 110),
    /** Communicated risk and stronger tactics: 120-140% of capacity. */
    HARD(120, 140),
    /** Explicitly selected dangerous content with known stakes: 150-170% of capacity. */
    SEVERE(150, 170);

    private final int lowestPercent;
    private final int highestPercent;

    EncounterDifficulty(int lowestPercent, int highestPercent) {
        this.lowestPercent = lowestPercent;
        this.highestPercent = highestPercent;
    }

    /** @return the smallest admitted threat, in percent of capacity, inclusive */
    public int lowestPercent() {
        return lowestPercent;
    }

    /** @return the largest admitted threat, in percent of capacity, inclusive */
    public int highestPercent() {
        return highestPercent;
    }

    /** @return the lowercase wire name */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
