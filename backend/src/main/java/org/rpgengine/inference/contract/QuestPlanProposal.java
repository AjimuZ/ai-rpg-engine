package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated result of one quest-plan task (docs/28 section 6). Either a plan whose structure,
 * references, bounds, author constraints and snapshot feasibility passed the contract validator,
 * or the planner's bounded statement that it cannot plan. A plan here is still only a private
 * candidate: the plan-admission gate repeats every check that later world changes could
 * falsify, and only an ordinary canonical command can ever use an admitted branch.
 */
public sealed interface QuestPlanProposal {
    /**
     * An accepted plan.
     *
     * @param bodyJson canonical JSON of the validated body with the backend-written
     *        {@code validationMetadata}; this exact text is what the artifact stores
     * @param acceptedBranches branch ids that are feasible in the snapshot, in output order
     * @param rejectedBranches branch ids rejected by selective validation, with a fixed reason code
     *        ({@code guard-false} or {@code missing-support})
     * @param citations for each cited manifest dependency id, which plan parts rely on it; an
     *        absent id is a read the plan never references
     * @param entityRefs every typed campaign reference the plan cites
     * @param contentRefs every pinned definition the plan cites
     * @param authorConstraintRefs the author constraints the plan preserves
     */
    record Planned(String bodyJson, List<String> acceptedBranches, Map<String, String> rejectedBranches,
            Map<String, Citation> citations, List<String> entityRefs, List<String> contentRefs,
            List<String> authorConstraintRefs) implements QuestPlanProposal {
        /** Freeze every collection; at least one branch is accepted. */
        public Planned {
            Objects.requireNonNull(bodyJson, "bodyJson");
            acceptedBranches = List.copyOf(acceptedBranches);
            rejectedBranches = Map.copyOf(rejectedBranches);
            citations = Map.copyOf(citations);
            entityRefs = List.copyOf(entityRefs);
            contentRefs = List.copyOf(contentRefs);
            authorConstraintRefs = List.copyOf(authorConstraintRefs);
            if (acceptedBranches.isEmpty()) {
                throw new IllegalArgumentException("a plan needs at least one accepted branch");
            }
        }
    }

    /**
     * The planner declined with one fixed reason; the playable baseline continues unchanged.
     *
     * @param reasonCode {@code insufficient-context} or {@code constraint-conflict}
     */
    record CannotPlan(String reasonCode) implements QuestPlanProposal {
    }

    /**
     * How one recorded read is relied on.
     *
     * @param root whether a root assumption, failure rule, secret prerequisite, pending-event
     *        trigger or declared whole-plan assumption cites it, or the plan mentions it outside
     *        predicates without any branch predicate reading it; a change then invalidates the
     *        whole artifact
     * @param branches branches whose guard, objective, nodes (with their causal support) or
     *        declared assumptions cite it
     */
    record Citation(boolean root, Set<String> branches) {
        /** Freeze the branch set. */
        public Citation {
            branches = Set.copyOf(branches);
        }
    }
}
