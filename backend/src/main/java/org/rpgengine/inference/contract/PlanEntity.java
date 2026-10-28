package org.rpgengine.inference.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One campaign row a quest planner may cite, as the snapshot assembler read it (docs/28 section 7).
 * The reference uses the closed typed grammar of the plan-admission gate, so every entity a plan
 * cites can be rechecked against its exact row version; the attributes are the few values the
 * {@link PlanPredicate} evaluator needs, never raw rows.
 *
 * @param ref typed reference {@code <kind>:<lowercase uuid>} with kind {@code actor},
 *        {@code resource}, {@code item}, {@code character}, {@code known-fact} or {@code known-quest}
 * @param label short display label (actor name, pool key, item definition, quest title)
 * @param location current location reference of an actor; null otherwise or when unplaced
 * @param amount current value of a resource pool; null for other kinds
 * @param status status of a known quest; null for other kinds
 * @param holderRef {@code actor:} reference owning a resource or item; null otherwise
 */
public record PlanEntity(String ref, String label, String location, Long amount, String status, String holderRef) {
    /** Closed reference grammar, mirroring {@code plan_reference_version} (V12). */
    public static final Pattern REFERENCE = Pattern.compile(
            "(actor|resource|item|character|known-fact|known-quest):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Longest label. */
    public static final int MAX_LABEL = 200;

    /** @throws IllegalArgumentException for a reference outside the grammar or malformed values */
    public PlanEntity {
        Objects.requireNonNull(ref, "ref");
        if (!REFERENCE.matcher(ref).matches()) {
            throw new IllegalArgumentException("entity reference outside the typed grammar");
        }
        ContextValues.text(label, "entity label", MAX_LABEL);
        if (location != null) {
            ContextValues.reference(location, "location");
        }
        if (amount != null && amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (status != null && !PlanPredicate.QUEST_STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported quest status");
        }
        if (holderRef != null && !holderRef.startsWith("actor:")) {
            throw new IllegalArgumentException("holder must be an actor reference");
        }
    }

    /** @return the kind prefix of the reference */
    public String kind() {
        return ref.substring(0, ref.indexOf(':'));
    }
}
