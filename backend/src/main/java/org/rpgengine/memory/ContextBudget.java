package org.rpgengine.memory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.rpgengine.memory.ContextItem.Section;

/**
 * Input-token budget of one AI task (docs/08 section 6). All figures are application defaults in
 * estimated tokens ({@link TokenEstimator}), not claims about any model's maximum.
 *
 * <p>The input budget covers the rendered instructions and the context; the output reserve and
 * the safety reserve are kept outside it. {@link #forModel} derives the input budget from a
 * model's effective context allowance: {@code min(applicationCap, effective - outputReserve -
 * safetyReserve - protocolOverhead)}.
 *
 * <p>Section allowances bound <em>optional</em> material per section. Mandatory items are charged
 * to their section first and may exceed it; the excess reduces the shared headroom. Mandatory
 * material is never dropped: when it cannot fit at all, assembly fails with a controlled
 * context-too-large result instead of truncating (see {@link ContextSelection}).
 *
 * @param inputTokens tokens available to instructions plus context, 1..1,000,000
 * @param outputReserve tokens reserved for the answer, outside the input budget
 * @param safetyReserve additional reserve for estimation error, outside the input budget
 * @param allowances optional-material allowance of every section; missing sections get 0
 */
public record ContextBudget(int inputTokens, int outputReserve, int safetyReserve, Map<Section, Integer> allowances) {
    /** Application input cap of the standard profile. */
    public static final int STANDARD_INPUT = 12_000;
    /** Output reserve of the standard profile. */
    public static final int STANDARD_OUTPUT = 2_000;
    /** Safety reserve of the standard profile. */
    public static final int STANDARD_SAFETY = 1_000;
    /** Runtime protocol framing assumed outside the rendered instructions. */
    public static final int PROTOCOL_OVERHEAD = 500;
    /** Largest accepted input budget. */
    public static final int MAX_INPUT = 1_000_000;

    /**
     * @throws IllegalArgumentException for a budget outside 1..{@link #MAX_INPUT}, negative reserves
     *         or allowances, or allowances that together exceed the input budget
     */
    public ContextBudget {
        if (inputTokens < 1 || inputTokens > MAX_INPUT || outputReserve < 0 || safetyReserve < 0) {
            throw new IllegalArgumentException("input budget must be 1.." + MAX_INPUT + " with non-negative reserves");
        }
        var copy = new EnumMap<Section, Integer>(Section.class);
        for (Section section : Section.values()) {
            int value = Objects.requireNonNull(allowances, "allowances").getOrDefault(section, 0);
            if (value < 0) {
                throw new IllegalArgumentException("section allowances must not be negative");
            }
            copy.put(section, value);
        }
        if (copy.values().stream().mapToLong(Integer::longValue).sum() > inputTokens) {
            throw new IllegalArgumentException("section allowances exceed the input budget");
        }
        allowances = Collections.unmodifiableMap(copy);
    }

    /**
     * Standard profile from the illustrative docs/08 allocation of 12,000 input tokens: about 2,000
     * for task instructions (not an allowance: the caller's instruction estimate is charged first),
     * 3,000 for current state, 2,000 for objectives and commitments, 3,000 for selected memory
     * (knowledge and history) and the remainder as shared headroom. A privileged audience moves
     * 1,000 tokens of the headroom to planning assumptions; other audiences have no planning room.
     *
     * @param privileged whether the audience may receive planning assumptions
     * @return the standard budget
     */
    public static ContextBudget standard(boolean privileged) {
        var allowances = new EnumMap<Section, Integer>(Section.class);
        allowances.put(Section.STATE, 3_000);
        allowances.put(Section.OBLIGATIONS, 2_000);
        allowances.put(Section.KNOWLEDGE, 1_500);
        allowances.put(Section.HISTORY, 1_500);
        allowances.put(Section.PLANNING, privileged ? 1_000 : 0);
        return new ContextBudget(STANDARD_INPUT, STANDARD_OUTPUT, STANDARD_SAFETY, allowances);
    }

    /**
     * Fit the standard profile to a model's effective context allowance.
     *
     * @param effectiveContext model context allowance in estimated tokens
     * @param privileged whether the audience may receive planning assumptions
     * @return a budget whose input is the smaller of the application cap and what the model leaves
     *         after the reserves; allowances shrink proportionally when the input shrinks
     * @throws IllegalArgumentException when the model leaves no input room at all
     */
    public static ContextBudget forModel(int effectiveContext, boolean privileged) {
        ContextBudget standard = standard(privileged);
        long room = (long) effectiveContext - STANDARD_OUTPUT - STANDARD_SAFETY - PROTOCOL_OVERHEAD;
        if (room < 1) {
            throw new IllegalArgumentException("the model context leaves no input room");
        }
        int input = (int) Math.min(STANDARD_INPUT, room);
        if (input == STANDARD_INPUT) {
            return standard;
        }
        var scaled = new EnumMap<Section, Integer>(Section.class);
        standard.allowances().forEach((section, value) -> scaled.put(section, (int) ((long) value * input / STANDARD_INPUT)));
        return new ContextBudget(input, STANDARD_OUTPUT, STANDARD_SAFETY, scaled);
    }

    /** @return allowance of one section */
    public int allowance(Section section) {
        return allowances.get(section);
    }
}
