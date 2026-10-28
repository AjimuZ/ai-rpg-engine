package org.rpgengine.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

import org.rpgengine.memory.ContextItem.Section;

/**
 * Deterministic budgeted selection of context items (P09.2, docs/08 section 6). Pure: no I/O, no
 * clock, no randomness, so the same candidates and budget always give the same selection.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Deduplicate by {@link ContextItem#ref()}, keeping the best-ranked copy (mandatory first,
 *       then the best {@link ContextItem.Tier}, then the higher score).</li>
 *   <li>Charge the fixed cost (instructions, envelope stamp and framing) and every mandatory item.
 *       An item costs its own rendering plus the caller's extra charge for it (the glossary terms
 *       it names), so terminology is paid for only by the items that bring it in.
 *       If they exceed the input budget the result is {@link TooLarge}: mandatory material is never
 *       truncated and nothing is selected.</li>
 *   <li>Give each section its optional allowance minus its mandatory cost (not below zero). When
 *       the remaining room cannot cover every allowance, later sections lose allowance first. The
 *       rest is shared headroom.</li>
 *   <li>Pass 1: in section order, take each section's optional items in rank order while they fit
 *       its allowance. Pass 2: in section order, continue from where pass 1 stopped while items fit
 *       the headroom. A section stops at its first item that does not fit, so within a section a
 *       lower-ranked item is never kept while a higher-ranked one is dropped.</li>
 * </ol>
 * Everything not selected is reported per section in {@link Selected#omitted()}, so a partial
 * context is labelled as partial and never presented as complete.
 */
public final class ContextSelection {
    /** Rank order inside a section: mandatory, best tier, highest score, then reference. */
    static final Comparator<ContextItem> RANK = Comparator.comparing((ContextItem item) -> !item.mandatory())
            .thenComparing(ContextItem::tier)
            .thenComparing(Comparator.comparingLong(ContextItem::score).reversed())
            .thenComparing(ContextItem::ref);

    /** Static helpers only. */
    private ContextSelection() {
    }

    /**
     * Select items under a budget.
     *
     * @param candidates audience-filtered candidates, possibly with duplicate references
     * @param budget input budget and section allowances
     * @param fixedTokens estimated tokens of everything outside the items and their extras:
     *        instructions, envelope stamp and section framing
     * @param extra additional estimated tokens an item brings with it (for example the glossary
     *        entries of the definitions it names); must not be negative. Charging shared extras
     *        once per item over-estimates, which is safe
     * @param privileged whether the audience may receive privileged-only standings
     * @return the selection or the controlled overflow
     * @throws IllegalArgumentException when a non-privileged audience is offered a fact with truth
     *         or a planning assumption (an assembler defect, never silently filtered), or for a
     *         negative fixed cost or extra charge
     */
    public static Result select(List<ContextItem> candidates, ContextBudget budget, int fixedTokens,
            ToLongFunction<ContextItem> extra, boolean privileged) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(extra, "extra");
        if (fixedTokens < 0) {
            throw new IllegalArgumentException("fixed cost must not be negative");
        }
        var best = new LinkedHashMap<String, ContextItem>();
        for (ContextItem item : candidates) {
            if (!privileged && item.standing().privilegedOnly()) {
                throw new IllegalArgumentException("privileged-only material offered to a limited audience");
            }
            best.merge(item.ref(), item, (kept, offered) -> RANK.compare(offered, kept) < 0 ? offered : kept);
        }
        var bySection = new EnumMap<Section, List<ContextItem>>(Section.class);
        for (Section section : Section.values()) {
            bySection.put(section, new ArrayList<>());
        }
        best.values().forEach(item -> bySection.get(item.section()).add(item));
        bySection.values().forEach(list -> list.sort(RANK));

        var costs = new java.util.HashMap<String, Long>();
        for (ContextItem item : best.values()) {
            long charge = extra.applyAsLong(item);
            if (charge < 0) {
                throw new IllegalArgumentException("extra charge must not be negative");
            }
            costs.put(item.ref(), cost(item) + charge);
        }
        ToLongFunction<ContextItem> price = item -> costs.get(item.ref());
        var mandatoryCost = new EnumMap<Section, Long>(Section.class);
        long mandatory = 0;
        for (Section section : Section.values()) {
            long cost = bySection.get(section).stream().filter(ContextItem::mandatory).mapToLong(price).sum();
            mandatoryCost.put(section, cost);
            mandatory += cost;
        }
        long required = fixedTokens + mandatory;
        if (required > budget.inputTokens()) {
            return new TooLarge(required, budget.inputTokens(), fixedTokens, Map.copyOf(mandatoryCost));
        }
        long room = budget.inputTokens() - required;
        var allowance = new EnumMap<Section, Long>(Section.class);
        long allowed = 0;
        for (Section section : Section.values()) {
            long value = Math.max(0, budget.allowance(section) - mandatoryCost.get(section));
            allowance.put(section, value);
            allowed += value;
        }
        Section[] reverse = Section.values();
        for (int index = reverse.length - 1; index >= 0 && allowed > room; index--) {
            long cut = Math.min(allowance.get(reverse[index]), allowed - room);
            allowance.put(reverse[index], allowance.get(reverse[index]) - cut);
            allowed -= cut;
        }
        long headroom = room - allowed;

        var chosen = new EnumMap<Section, List<ContextItem>>(Section.class);
        var next = new EnumMap<Section, Integer>(Section.class);
        long used = 0;
        for (Section section : Section.values()) {
            var list = bySection.get(section);
            var keep = new ArrayList<ContextItem>();
            int index = 0;
            while (index < list.size() && list.get(index).mandatory()) {
                keep.add(list.get(index++));
            }
            long left = allowance.get(section);
            while (index < list.size() && price.applyAsLong(list.get(index)) <= left) {
                left -= price.applyAsLong(list.get(index));
                used += price.applyAsLong(list.get(index));
                keep.add(list.get(index++));
            }
            chosen.put(section, keep);
            next.put(section, index);
        }
        for (Section section : Section.values()) {
            var list = bySection.get(section);
            int index = next.get(section);
            while (index < list.size() && price.applyAsLong(list.get(index)) <= headroom) {
                headroom -= price.applyAsLong(list.get(index));
                used += price.applyAsLong(list.get(index));
                chosen.get(section).add(list.get(index++));
            }
            next.put(section, index);
        }
        var omitted = new EnumMap<Section, Integer>(Section.class);
        var selected = new EnumMap<Section, List<ContextItem>>(Section.class);
        for (Section section : Section.values()) {
            omitted.put(section, bySection.get(section).size() - next.get(section));
            selected.put(section, List.copyOf(chosen.get(section)));
        }
        return new Selected(selected, omitted, candidates.size() - best.size(), required + used);
    }

    /**
     * Select items without extra charges.
     *
     * @see #select(List, ContextBudget, int, ToLongFunction, boolean)
     */
    public static Result select(List<ContextItem> candidates, ContextBudget budget, int fixedTokens, boolean privileged) {
        return select(candidates, budget, fixedTokens, item -> 0, privileged);
    }

    /** @return estimated tokens of one item including its list separator */
    static long cost(ContextItem item) {
        return TokenEstimator.tokens(item.renderedBytes() + 1L);
    }

    /** Outcome of {@link #select}. */
    public sealed interface Result permits Selected, TooLarge {
    }

    /**
     * A selection within budget.
     *
     * @param items selected items per section, in rank order (mandatory first)
     * @param omitted number of distinct candidates of each section that were not selected
     * @param duplicates candidates dropped because a better-ranked copy of the same record existed
     * @param tokens estimated tokens of the fixed cost plus the selected items; at most the input budget
     */
    public record Selected(Map<Section, List<ContextItem>> items, Map<Section, Integer> omitted, int duplicates,
            long tokens) implements Result {
    }

    /**
     * Mandatory material does not fit; nothing was selected.
     *
     * @param requiredTokens fixed cost plus every mandatory item
     * @param inputTokens the input budget it exceeds
     * @param fixedTokens the fixed cost alone
     * @param mandatoryBySection mandatory cost of each section
     */
    public record TooLarge(long requiredTokens, int inputTokens, int fixedTokens, Map<Section, Long> mandatoryBySection)
            implements Result {
    }
}
