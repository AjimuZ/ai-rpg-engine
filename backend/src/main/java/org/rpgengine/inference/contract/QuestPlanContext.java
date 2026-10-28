package org.rpgengine.inference.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Trusted, privileged input of one {@code quest-plan} or {@code quest-plan-refine} task (docs/28
 * section 6, docs/21 section 13): the backend-owned plan identity, the quest's known terms, the
 * authorized world slice the snapshot assembler read, the engine-recorded dependency manifest,
 * pinned content and author constraints. It runs only in the privileged planner audience, so it
 * may hold hidden canon; its output is never shown to the player and never narrated directly.
 *
 * <p>Every entity in {@link #entities} is covered by at least one entry of {@link #dependencies}
 * with the same reference, and every lore id in the typed entity grammar is such an entity, which
 * is what lets the validator map plan citations onto recorded reads.
 *
 * @param binding identity and generation binding; {@code taskId} is the job attempt's task id
 * @param planId stable backend-owned plan identity across versions
 * @param planVersion version this output must become, 1..10000
 * @param predecessorPlanId plan identity of the refined predecessor, or null for version 1
 * @param questId bounded logical quest target (the preparation job's target)
 * @param questTitle known quest title
 * @param questObjective known objective text; the plan may enrich but never replace it
 * @param worldTimeSeconds world time of the snapshot
 * @param maxHorizonSeconds longest planning horizon accepted, 1..2,592,000 (30 days)
 * @param entities authorized campaign rows the plan may cite, at most {@value #MAX_ENTITIES}
 * @param lore source-linked facts the plan may cite, at most {@value #MAX_LORE}
 * @param dependencies the snapshot's dependency manifest in order, at most {@value #MAX_DEPENDENCIES}
 * @param contentRefs pinned definition identities the plan may cite, at most {@value #MAX_CONTENT}
 * @param authorConstraints protected author constraints; every one must be cited by the plan
 * @param refinement for {@code quest-plan-refine}: branches of the predecessor that are stale and
 *        those that are still usable; null for a first plan
 * @param glossary selected-locale terminology the plan's prose should use
 */
public record QuestPlanContext(TaskBinding binding, UUID planId, int planVersion, UUID predecessorPlanId,
        String questId, String questTitle, String questObjective, long worldTimeSeconds, long maxHorizonSeconds,
        List<PlanEntity> entities, List<Lore> lore, List<Dependency> dependencies, List<String> contentRefs,
        List<String> authorConstraints, Refinement refinement, List<GlossaryTerm> glossary)
        implements ContractContext {
    /** Most entities. */
    public static final int MAX_ENTITIES = 64;
    /** Most lore entries. */
    public static final int MAX_LORE = 64;
    /** Most manifest entries, the snapshot bound. */
    public static final int MAX_DEPENDENCIES = 512;
    /** Most pinned content references. */
    public static final int MAX_CONTENT = 128;
    /** Most author constraints. */
    public static final int MAX_CONSTRAINTS = 32;
    /** Longest accepted planning horizon (30 days of world time). */
    public static final long MAX_HORIZON = 2_592_000L;
    private static final Pattern DEPENDENCY_ID = Pattern.compile("dep-[0-9]{1,3}");

    /**
     * Freeze and bound every list.
     *
     * @throws IllegalArgumentException for out-of-range values, duplicates, an entity not covered
     *         by a dependency, a refinement on version 1 or a missing one on a later version
     */
    public QuestPlanContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(planId, "planId");
        if (planVersion < 1 || planVersion > 10_000) {
            throw new IllegalArgumentException("planVersion must be 1..10000");
        }
        if ((planVersion == 1) != (predecessorPlanId == null)) {
            throw new IllegalArgumentException("only a later version names its predecessor");
        }
        if ((planVersion == 1) != (refinement == null)) {
            throw new IllegalArgumentException("only a later version carries a refinement");
        }
        ContextValues.text(questId, "quest id", 256);
        ContextValues.text(questTitle, "quest title", 200);
        ContextValues.text(questObjective, "quest objective", 4000);
        if (worldTimeSeconds < 0) {
            throw new IllegalArgumentException("world time must not be negative");
        }
        if (maxHorizonSeconds < 1 || maxHorizonSeconds > MAX_HORIZON) {
            throw new IllegalArgumentException("horizon must be 1..2592000 seconds");
        }
        entities = ContextValues.distinct(entities, "entities", 0, MAX_ENTITIES, PlanEntity::ref);
        lore = ContextValues.distinct(lore, "lore", 0, MAX_LORE, Lore::id);
        dependencies = ContextValues.distinct(dependencies, "dependencies", 0, MAX_DEPENDENCIES, Dependency::id);
        contentRefs = ContextValues.distinct(contentRefs, "content", 0, MAX_CONTENT, value -> value);
        contentRefs.forEach(value -> ContextValues.text(value, "content reference", 256));
        authorConstraints = ContextValues.distinct(authorConstraints, "constraints", 0, MAX_CONSTRAINTS, value -> value);
        if (!new HashSet<>(contentRefs).containsAll(authorConstraints)) {
            throw new IllegalArgumentException("every author constraint must be a pinned definition");
        }
        glossary = ContextValues.distinct(glossary, "glossary", 0, IntentContext.MAX_GLOSSARY, GlossaryTerm::termId);
        var recorded = new HashSet<String>();
        for (int index = 0; index < dependencies.size(); index++) {
            if (!dependencies.get(index).id().equals("dep-" + index)) {
                throw new IllegalArgumentException("dependency ids must follow manifest order");
            }
            recorded.add(dependencies.get(index).reference());
        }
        for (PlanEntity entity : entities) {
            if (!recorded.contains(entity.ref())) {
                throw new IllegalArgumentException("every entity needs a recorded dependency");
            }
        }
        var refs = new HashSet<String>();
        entities.forEach(entity -> refs.add(entity.ref()));
        for (Lore entry : lore) {
            // Entity-shaped lore is a campaign row; it must be an entity so its read is recorded.
            if (PlanEntity.REFERENCE.matcher(entry.id()).matches() && !refs.contains(entry.id())) {
                throw new IllegalArgumentException("entity-shaped lore must be a context entity");
            }
        }
    }

    /** Planner context holds privileged canon by design; nothing is guarded against its own audience. */
    @Override
    public DisclosureGuard guard() {
        return DisclosureGuard.none();
    }

    /** @return the entity with this reference, or null */
    public PlanEntity entity(String ref) {
        return entities.stream().filter(entity -> entity.ref().equals(ref)).findFirst().orElse(null);
    }

    /**
     * One source-linked fact.
     *
     * @param id stable source reference (a {@code known-fact:} entity or a pinned definition id)
     * @param text fact text in its authored language
     * @param secret whether the fact is hidden from the player
     */
    public record Lore(String id, String text, boolean secret) {
        /** @throws IllegalArgumentException for a malformed reference or unbounded text */
        public Lore {
            ContextValues.reference(id, "lore id");
            ContextValues.text(text, "lore text", 1000);
        }
    }

    /**
     * One engine-recorded read, as the planner sees it.
     *
     * @param id {@code dep-<index>} of the manifest position
     * @param reference entity reference or query scope the read covers
     * @param query whether the read is a query-epoch observation (catches phantoms)
     */
    public record Dependency(String id, String reference, boolean query) {
        /** @throws IllegalArgumentException for a malformed id or reference */
        public Dependency {
            Objects.requireNonNull(id, "id");
            if (!DEPENDENCY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("dependency id must be dep-<index>");
            }
            ContextValues.text(reference, "dependency reference", 256);
        }
    }

    /**
     * What a refinement replaces.
     *
     * @param staleBranches predecessor branches whose dependencies changed; the refined plan should
     *        replace them with branches that fit the current snapshot
     * @param usableBranches predecessor branches that are still usable and may be kept by id
     */
    public record Refinement(List<String> staleBranches, List<String> usableBranches) {
        /** @throws IllegalArgumentException for malformed or overlapping branch ids */
        public Refinement {
            staleBranches = ContextValues.distinct(staleBranches, "stale branches", 0, 8, value -> value);
            usableBranches = ContextValues.distinct(usableBranches, "usable branches", 0, 8, value -> value);
            for (String branch : staleBranches) {
                if (!IntentContract.LOCAL_ID.matcher(branch).matches() || usableBranches.contains(branch)) {
                    throw new IllegalArgumentException("malformed or overlapping refinement branch");
                }
            }
            usableBranches.forEach(branch -> {
                if (!IntentContract.LOCAL_ID.matcher(branch).matches()) {
                    throw new IllegalArgumentException("malformed refinement branch");
                }
            });
        }
    }
}
