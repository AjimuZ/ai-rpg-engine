package org.rpgengine.inference;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The owner's assignment of one text slot (ADR 0021 decision 2): the backend that serves it,
 * the preferred model and effort, and an optional ordered list of owner-approved degradation
 * alternatives.
 *
 * <p>Alternatives are deliberately not a failover mechanism: they live on the same backend as the
 * primary choice, so they share its billing and privacy class (ADR 0021 decision 8), and they are
 * considered only while resolving a task, when the primary pair is absent from the backend's
 * catalog or approved allowlist. A turn that fails after it started is never retried on another
 * model (ADR 0020 decision 3).
 *
 * @param backend protocol adapter name from {@link #TEXT_BACKENDS}
 * @param primary preferred model and effort
 * @param alternatives approved lower-preference pairs of the same backend, at most
 *        {@link #MAX_ALTERNATIVES}, distinct from the primary pair and from each other
 */
public record SlotAssignment(String backend, EffectiveSettings primary, List<EffectiveSettings> alternatives) {
    /**
     * Closed set of text protocol adapters of ADR 0021 decision 1. Membership here does not mean
     * an adapter exists yet: the task port wires only implemented adapters, and a slot assigned
     * to a backend the active adapter does not serve is refused at resolution time.
     */
    public static final Set<String> TEXT_BACKENDS = Set.of("codex", "claude", "local", "openai-compatible",
            "anthropic-api");
    /** Upper bound of the approved degradation list, so the owner's intent stays reviewable. */
    public static final int MAX_ALTERNATIVES = 4;

    private static final Pattern BACKEND = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /**
     * Validate and freeze the assignment.
     *
     * @throws NullPointerException for a missing component
     * @throws IllegalArgumentException for an unknown backend, too many alternatives or a duplicate pair
     */
    public SlotAssignment {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(alternatives, "alternatives");
        if (!BACKEND.matcher(backend).matches() || !TEXT_BACKENDS.contains(backend)) {
            throw new IllegalArgumentException("unknown text backend");
        }
        alternatives = List.copyOf(alternatives);
        if (alternatives.size() > MAX_ALTERNATIVES) {
            throw new IllegalArgumentException("too many alternatives");
        }
        Set<EffectiveSettings> seen = new HashSet<>(Set.of(primary));
        for (EffectiveSettings alternative : alternatives) {
            if (!seen.add(alternative)) {
                throw new IllegalArgumentException("duplicate model and effort pair");
            }
        }
    }

    /**
     * @param backend protocol adapter name
     * @param primary preferred model and effort
     * @return an assignment without degradation alternatives: an unsupported pair fails clearly
     */
    public static SlotAssignment of(String backend, EffectiveSettings primary) {
        return new SlotAssignment(backend, primary, List.of());
    }

    /** @return the primary pair followed by the alternatives, in the owner's order of preference */
    public List<EffectiveSettings> candidates() {
        List<EffectiveSettings> all = new java.util.ArrayList<>(alternatives.size() + 1);
        all.add(primary);
        all.addAll(alternatives);
        return List.copyOf(all);
    }
}
