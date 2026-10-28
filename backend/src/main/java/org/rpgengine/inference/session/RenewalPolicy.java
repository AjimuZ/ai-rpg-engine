package org.rpgengine.inference.session;

import org.springframework.core.env.Environment;

/**
 * Validated renewal thresholds of logical AI sessions (P09.4, docs/08 section 7), read from
 * {@code engine.inference.sessions.*}. All figures are application defaults in estimated units of
 * 4 UTF-8 bytes (the scheduler's usage estimate), not claims about any model's real limits.
 *
 * <p>A session is renewed at its next task, which is the earliest safe boundary of its line (the
 * scheduler never runs two tasks of one line at once), when either:
 * <ul>
 *   <li><b>turn ceiling</b>: it has completed {@code turnCeiling} tasks (repairs and read-only
 *       questions count; local consultations never reach a session); or</li>
 *   <li><b>context pressure</b>: the estimated size of its accumulated conversation plus the next
 *       task (its input and its slot's output allowance) would exceed {@code renewPercent} of
 *       {@code contextWindow}. Crossing {@code warnPercent} is counted and logged once per
 *       session.</li>
 * </ul>
 * A session that has run no turn is never renewed for pressure, so one oversized task cannot make
 * the registry renew in a loop; that task simply runs in its fresh session.
 *
 * @param turnCeiling completed tasks per session before renewal, 1..100 (default 10)
 * @param contextWindow estimated conversation size the pressure percentages refer to,
 *        8,000..2,000,000 units (default 128,000)
 * @param warnPercent pressure warning threshold, 1..99 and below {@code renewPercent} (default 75)
 * @param renewPercent pressure renewal threshold, 2..99 (default 85)
 */
public record RenewalPolicy(int turnCeiling, long contextWindow, int warnPercent, int renewPercent) {
    private static final String PREFIX = "engine.inference.sessions.";

    /** @throws IllegalArgumentException naming the first out-of-range setting */
    public RenewalPolicy {
        require(turnCeiling >= 1 && turnCeiling <= 100, "turn-ceiling");
        require(contextWindow >= 8_000 && contextWindow <= 2_000_000, "context-window");
        require(renewPercent >= 2 && renewPercent <= 99, "context-renew-percent");
        require(warnPercent >= 1 && warnPercent < renewPercent, "context-warn-percent");
    }

    /**
     * Defaults of docs/08 section 7: ten completed tasks, warning at 75% and renewal at 85% of a
     * conservative 128,000-unit conversation window.
     *
     * @return the default policy
     */
    public static RenewalPolicy defaults() {
        return new RenewalPolicy(10, 128_000, 75, 85);
    }

    /**
     * Read and validate {@code engine.inference.sessions.*} over {@link #defaults()}.
     *
     * @param environment Spring environment
     * @return validated policy
     * @throws IllegalArgumentException for a malformed or out-of-range value, naming the property only
     */
    public static RenewalPolicy from(Environment environment) {
        RenewalPolicy base = defaults();
        try {
            return new RenewalPolicy(
                    environment.getProperty(PREFIX + "turn-ceiling", Integer.class, base.turnCeiling()),
                    environment.getProperty(PREFIX + "context-window", Long.class, base.contextWindow()),
                    environment.getProperty(PREFIX + "context-warn-percent", Integer.class, base.warnPercent()),
                    environment.getProperty(PREFIX + "context-renew-percent", Integer.class, base.renewPercent()));
        } catch (RuntimeException malformed) {
            // Conversion failures can quote the value; report only which settings are invalid.
            if (malformed.getMessage() != null && malformed.getMessage().startsWith(PREFIX)) {
                throw malformed;
            }
            throw new IllegalArgumentException(PREFIX + "* contains a malformed value");
        }
    }

    /** @return estimated units above which a session is renewed before its next task */
    public long renewUnits() {
        return contextWindow * renewPercent / 100;
    }

    /** @return estimated units above which a session's pressure warning is counted */
    public long warnUnits() {
        return contextWindow * warnPercent / 100;
    }

    /** Fail with the property name only. */
    private static void require(boolean valid, String name) {
        if (!valid) {
            throw new IllegalArgumentException(PREFIX + name + " is out of range");
        }
    }
}
