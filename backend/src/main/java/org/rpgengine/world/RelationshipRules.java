package org.rpgengine.world;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prose-free relationship rules of one NPC definition (P15.1-B), compiled only from a pack that enables the
 * {@code relationships} module and keyed by the NPC's canonical definition ID in the pinned rule catalog. Every
 * campaign actor created from that definition holds one bounded value per dimension toward each other actor; a
 * value changes only through a declared cause, and an optional companion threshold makes the NPC travel with the
 * player while its value toward that player is at least the threshold.
 *
 * @param dimensions bounded dimensions by local identifier, for example {@code trust}; 1..{@value #MAX_DIMENSIONS}
 * @param causes how typed committed facts change those dimensions; 1..{@value #MAX_CAUSES}, one per trigger and
 *        dimension
 * @param companion threshold that makes the NPC a companion, or null when it never joins the party
 */
public record RelationshipRules(Map<String, Dimension> dimensions, List<Cause> causes, Companion companion) {
    /** Most dimensions one NPC declares; equal to the pack compiler's bound. */
    public static final int MAX_DIMENSIONS = 4;
    /** Most causes one NPC declares; equal to the pack compiler's bound. */
    public static final int MAX_CAUSES = 16;
    /** Largest magnitude of a dimension bound; the database checks stored values against it too. */
    public static final int VALUE_LIMIT = 100;
    /** Largest magnitude of one cause's change. */
    public static final int DELTA_LIMIT = 10;

    /**
     * Typed committed facts that can cause a change. Each names the NPC that holds the relationship and the
     * actor it is held toward; nothing here reads speech text, item names or any other prose.
     */
    public enum Trigger {
        /** The acting player addressed the NPC ({@code SPEECH_DELIVERED}); held toward the speaker. */
        SPOKEN_TO("spoken-to"),
        /** The acting player gave the NPC units of an item ({@code ITEM_TRANSFERRED}); held toward the giver. */
        GIFT("gift"),
        /** The acting player completed a trade with the NPC ({@code ITEM_TRADED}); held toward the trader. */
        TRADE("trade"),
        /** A commitment owed to the NPC was resolved FULFILLED; held toward the debtor. */
        PROMISE_KEPT("promise-kept"),
        /** A commitment owed to the NPC was resolved BROKEN; held toward the debtor. */
        PROMISE_BROKEN("promise-broken");

        private final String token;

        Trigger(String token) {
            this.token = token;
        }

        /** @return the authored spelling used in pack files */
        public String token() {
            return token;
        }

        /**
         * @param token authored spelling
         * @return the trigger it names
         * @throws IllegalArgumentException for any other spelling; the compiler refuses those first
         */
        public static Trigger of(String token) {
            for (Trigger trigger : values()) {
                if (trigger.token.equals(token)) {
                    return trigger;
                }
            }
            throw new IllegalArgumentException("unknown relationship trigger");
        }
    }

    /**
     * One bounded dimension.
     *
     * @param min lowest value, at least {@code -VALUE_LIMIT}
     * @param max highest value, above {@code min} and at most {@code VALUE_LIMIT}
     * @param initial value before any recorded change, within the bounds
     */
    public record Dimension(int min, int max, int initial) {
        /** Require ordered bounds within the stored range and an initial value between them. */
        public Dimension {
            if (min < -VALUE_LIMIT || max > VALUE_LIMIT || min >= max || initial < min || initial > max) {
                throw new IllegalArgumentException("dimension bounds must satisfy -100 <= min <= initial <= max <= 100, "
                        + "min < max");
            }
        }

        /** @return {@code value} limited to the bounds */
        public int clamp(long value) {
            return (int) Math.max(min, Math.min(max, value));
        }
    }

    /**
     * One declared cause.
     *
     * @param on the typed fact that causes the change
     * @param dimension local identifier of a declared dimension
     * @param delta signed change before clamping, non-zero and at most {@code DELTA_LIMIT} in magnitude
     */
    public record Cause(Trigger on, String dimension, int delta) {
        /** Require a trigger, a dimension and a bounded non-zero change. */
        public Cause {
            Objects.requireNonNull(on, "on");
            Objects.requireNonNull(dimension, "dimension");
            if (delta == 0 || Math.abs(delta) > DELTA_LIMIT) {
                throw new IllegalArgumentException("a cause changes a value by 1.." + DELTA_LIMIT + " in either direction");
            }
        }
    }

    /**
     * Companion threshold.
     *
     * @param dimension local identifier of a declared dimension
     * @param atLeast value toward the player from which the NPC is a companion; above the dimension's initial value,
     *        so joining is always a recorded change
     */
    public record Companion(String dimension, int atLeast) {
        /** Require the dimension. */
        public Companion {
            Objects.requireNonNull(dimension, "dimension");
        }

        /** @return true when {@code value} makes the NPC a companion */
        public boolean holds(int value) {
            return value >= atLeast;
        }
    }

    /** Freeze the rules; an unknown dimension, a repeated cause or an unreachable threshold is a construction defect. */
    public RelationshipRules {
        dimensions = Map.copyOf(dimensions);
        causes = List.copyOf(causes);
        if (dimensions.isEmpty() || dimensions.size() > MAX_DIMENSIONS || causes.isEmpty()
                || causes.size() > MAX_CAUSES) {
            throw new IllegalArgumentException("relationship rules need 1.." + MAX_DIMENSIONS + " dimensions and 1.."
                    + MAX_CAUSES + " causes");
        }
        var pairs = new HashSet<String>();
        for (Cause cause : causes) {
            if (!dimensions.containsKey(cause.dimension()) || !pairs.add(cause.on() + "|" + cause.dimension())) {
                throw new IllegalArgumentException("each cause names a declared dimension, once per trigger");
            }
        }
        if (companion != null) {
            Dimension bound = dimensions.get(companion.dimension());
            if (bound == null || companion.atLeast() <= bound.initial() || companion.atLeast() > bound.max()) {
                throw new IllegalArgumentException("a companion threshold lies above the initial value, within bounds");
            }
        }
    }

    /** @return the causes of one trigger, in authored order */
    public List<Cause> causesOf(Trigger trigger) {
        return causes.stream().filter(cause -> cause.on() == trigger).toList();
    }
}
