package org.rpgengine.rules;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure lifecycle rules of actor conditions: how applying a condition again behaves under each
 * stacking policy, and how elapsed rounds shorten it (docs/09 section 7, docs/10 section 1).
 *
 * <p>A condition is a temporary, data-defined effect with an explicit duration; it is never
 * inferred from prose. This class holds no state: callers pass the current {@link Active} state
 * and persist the returned one, so the rules stay replayable and the database remains the only
 * owner of what is active.
 */
public final class ConditionRules {
    private ConditionRules() {
    }

    /** Stacking policy of a definition. */
    public enum Stacking {
        /** A second application changes nothing while the condition is active. */
        NONE,
        /** A second application restores the full duration; stacks stay at one. */
        REFRESH,
        /** A second application adds a stack up to the cap and restores the full duration. */
        STACK
    }

    /**
     * Compiled condition definition.
     *
     * @param id canonical condition ID
     * @param stacking policy on repeated application
     * @param durationRounds rounds an application lasts, at least one
     * @param maxStacks highest stack count; one unless the policy is {@link Stacking#STACK}
     * @param modifiers per-stack attribute delta keyed by unqualified attribute key
     */
    public record Definition(String id, Stacking stacking, int durationRounds, int maxStacks,
            Map<String, Integer> modifiers) {
        /** Validate invariants the pack compiler guarantees and freeze the modifiers. */
        public Definition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(stacking, "stacking");
            if (durationRounds < 1) {
                throw new IllegalArgumentException("durationRounds must be at least 1");
            }
            if (maxStacks < 1 || (stacking != Stacking.STACK && maxStacks != 1)) {
                throw new IllegalArgumentException("maxStacks must be 1 unless the condition stacks");
            }
            modifiers = Map.copyOf(modifiers);
        }
    }

    /**
     * One active condition on an actor.
     *
     * @param id canonical condition ID
     * @param stacks current stack count, at least one
     * @param remainingRounds rounds left, at least one while the condition is active
     */
    public record Active(String id, int stacks, int remainingRounds) {
        /** Reject states that would represent an expired or empty condition. */
        public Active {
            Objects.requireNonNull(id, "id");
            if (stacks < 1 || remainingRounds < 1) {
                throw new IllegalArgumentException("an active condition has at least one stack and one round");
            }
        }
    }

    /** What an application did to the actor's state. */
    public enum Change {
        /** The condition was not active and now is. */
        ADDED,
        /** The condition was active and only its duration was restored. */
        REFRESHED,
        /** A further stack was added. */
        STACKED,
        /** Nothing changed (no-stacking policy while active). */
        UNCHANGED
    }

    /**
     * Result of {@link #apply}.
     *
     * @param state the condition state to persist
     * @param change how it differs from the input
     */
    public record Application(Active state, Change change) {
    }

    /**
     * Apply a condition to an actor.
     *
     * @param definition the condition being applied
     * @param current the actor's active state for that same condition, or empty
     * @return the state to persist and the kind of change
     * @throws IllegalArgumentException if {@code current} belongs to another condition
     */
    public static Application apply(Definition definition, Optional<Active> current) {
        Objects.requireNonNull(definition, "definition");
        if (current.isEmpty()) {
            return new Application(new Active(definition.id(), 1, definition.durationRounds()), Change.ADDED);
        }
        Active active = current.get();
        if (!active.id().equals(definition.id())) {
            throw new IllegalArgumentException("active condition " + active.id() + " is not " + definition.id());
        }
        return switch (definition.stacking()) {
            case NONE -> new Application(active, Change.UNCHANGED);
            case REFRESH -> new Application(new Active(active.id(), 1, definition.durationRounds()), Change.REFRESHED);
            case STACK -> {
                // Stacks never exceed the cap; at the cap the application only restores the duration.
                boolean room = active.stacks() < definition.maxStacks();
                int stacks = room ? active.stacks() + 1 : active.stacks();
                yield new Application(new Active(active.id(), stacks, definition.durationRounds()),
                        room ? Change.STACKED : Change.REFRESHED);
            }
        };
    }

    /**
     * Advance a condition by elapsed rounds.
     *
     * @param active current state
     * @param rounds elapsed rounds, not negative
     * @return the shortened state, or empty when the duration is used up and the condition must be removed
     * @throws IllegalArgumentException if {@code rounds} is negative
     */
    public static Optional<Active> elapse(Active active, long rounds) {
        if (rounds < 0) {
            throw new IllegalArgumentException("elapsed rounds must not be negative");
        }
        if (rounds >= active.remainingRounds()) {
            return Optional.empty();
        }
        return Optional.of(new Active(active.id(), active.stacks(), active.remainingRounds() - (int) rounds));
    }
}
