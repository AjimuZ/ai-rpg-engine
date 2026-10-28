package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic quest-plan bodies shared by the pure contract suite and the real-PostgreSQL
 * planning suite (whose scripted planner fills the references from the context it receives).
 *
 * <p>The canonical plan is the docs/28 witness example: a root assumption that the quest is
 * active; the witness as a participant; an {@code interview} branch guarded by the witness being
 * at its location and able to talk (vigor at least 1); an independent {@code document} branch
 * guarded by the kit still existing; a mandatory no-progress fallback. Neither branch orders the
 * player to do anything. Negative tests change exactly one aspect of this body.
 */
public final class QuestPlanFixtures {
    private QuestPlanFixtures() {
    }

    /**
     * References the canonical plan cites.
     *
     * @param planId backend plan identity to echo
     * @param version plan version to echo
     * @param predecessor predecessor plan id to echo, or null
     * @param questId quest target to echo
     * @param questRef {@code known-quest:} entity
     * @param witnessRef {@code actor:} entity of the witness
     * @param witnessLocation location the interview branch requires
     * @param vigorRef {@code resource:} pool of the witness
     * @param kitRef {@code item:} entity of the document route
     * @param loreRef lore id cited as established, or null
     * @param constraints author constraints to cite
     * @param locale output locale tag
     * @param branchPrefix prefix of both branch ids (empty for {@code interview}/{@code document})
     */
    public record Refs(String planId, int version, String predecessor, String questId, String questRef,
            String witnessRef, String witnessLocation, String vigorRef, String kitRef, String loreRef,
            List<String> constraints, String locale, String branchPrefix) {
    }

    /** @return one predicate node in wire shape */
    public static Map<String, Object> predicate(String op, List<Map<String, Object>> operands, String ref, String value,
            Long amount, Long from, Long to) {
        var map = new LinkedHashMap<String, Object>();
        map.put("op", op);
        map.put("operands", new ArrayList<>(operands));
        map.put("ref", ref);
        map.put("value", value);
        map.put("amount", amount);
        map.put("fromSeconds", from);
        map.put("toSeconds", to);
        return map;
    }

    /** @return {@code actor-at} */
    public static Map<String, Object> at(String actor, String location) {
        return predicate("actor-at", List.of(), actor, location, null, null, null);
    }

    /** @return {@code resource-at-least} */
    public static Map<String, Object> atLeast(String resource, long amount) {
        return predicate("resource-at-least", List.of(), resource, null, amount, null, null);
    }

    /** @return {@code item-available} */
    public static Map<String, Object> item(String item) {
        return predicate("item-available", List.of(), item, null, null, null, null);
    }

    /** @return {@code quest-status} */
    public static Map<String, Object> quest(String quest, String status) {
        return predicate("quest-status", List.of(), quest, status, null, null, null);
    }

    /** @return {@code all} over the operands */
    @SafeVarargs
    public static Map<String, Object> all(Map<String, Object>... operands) {
        return predicate("all", Arrays.asList(operands), null, null, null, null, null);
    }

    /** @return the canonical two-branch plan body for the references */
    public static Map<String, Object> plan(Refs refs) {
        String interview = refs.branchPrefix() + "interview";
        String document = refs.branchPrefix() + "document";
        var body = new LinkedHashMap<String, Object>();
        body.put("planId", refs.planId());
        body.put("planVersion", refs.version());
        body.put("questId", refs.questId());
        body.put("predecessorPlanId", refs.predecessor());
        body.put("premise", refs.locale().equals("it") ? "Il guardiano sa chi ha spostato i cippi di confine."
                : "The warden knows who moved the boundary stones.");
        body.put("tensions", new ArrayList<>(List.of(refs.locale().equals("it") ? "Il guardiano teme ritorsioni."
                : "The warden fears reprisals.")));
        body.put("planningHorizonSeconds", 86_400);
        body.put("establishedLoreRefs", refs.loreRef() == null ? new ArrayList<>() : new ArrayList<>(List.of(refs.loreRef())));
        body.put("requiredWorldFacts", new ArrayList<>(List.of(quest(refs.questRef(), "ACTIVE"))));
        var participant = new LinkedHashMap<String, Object>();
        participant.put("participantRef", refs.witnessRef());
        participant.put("motive", "Protect the trail.");
        participant.put("goal", "Stay out of the feud.");
        participant.put("sourceRefs", new ArrayList<>());
        participant.put("knownSecretRefs", new ArrayList<>(List.of("culprit")));
        body.put("participants", new ArrayList<>(List.of(participant)));
        var secret = new LinkedHashMap<String, Object>();
        secret.put("secretId", "culprit");
        secret.put("establishedFactRef", null);
        secret.put("proposal", "A surveyor moved the stones at night.");
        secret.put("knowerRefs", new ArrayList<>(List.of(refs.witnessRef())));
        secret.put("disclosurePrerequisite", null);
        body.put("secrets", new ArrayList<>(List.of(secret)));
        body.put("nodes", new ArrayList<>(List.of(
                node("talk", "opportunity", List.of(), all(at(refs.witnessRef(), refs.witnessLocation()),
                        atLeast(refs.vigorRef(), 1))),
                node("ledger", "clue", List.of(), item(refs.kitRef())))));
        body.put("branches", new ArrayList<>(List.of(
                branch(interview, at(refs.witnessRef(), refs.witnessLocation()), List.of("talk")),
                branch(document, item(refs.kitRef()), List.of("ledger")))));
        var failure = new LinkedHashMap<String, Object>();
        failure.put("expiry", null);
        failure.put("failure", null);
        failure.put("noProgressFallback", "baseline-objective");
        failure.put("consequenceProposals", new ArrayList<>());
        body.put("failureAndAbandonment", failure);
        body.put("pendingEventProposals", new ArrayList<>());
        body.put("authorConstraintRefs", new ArrayList<>(refs.constraints()));
        body.put("readDependencies", new ArrayList<>());
        body.put("assumptions", new ArrayList<>());
        body.put("locale", refs.locale());
        body.put("terminologyRefs", new ArrayList<>());
        body.put("validationMetadata", null);
        body.put("reasonCode", null);
        return body;
    }

    /** @return one node in wire shape */
    public static Map<String, Object> node(String id, String kind, List<String> support, Map<String, Object> eligibility) {
        var node = new LinkedHashMap<String, Object>();
        node.put("nodeId", id);
        node.put("kind", kind);
        node.put("summary", "Optional lead " + id + ".");
        node.put("supportRefs", new ArrayList<>(support));
        node.put("visibility", "discoverable");
        node.put("eligibility", eligibility);
        node.put("expiresAfterSeconds", null);
        node.put("contentRefs", new ArrayList<>());
        return node;
    }

    /** @return one branch in wire shape */
    public static Map<String, Object> branch(String id, Map<String, Object> guard, List<String> nodes) {
        var branch = new LinkedHashMap<String, Object>();
        branch.put("branchId", id);
        branch.put("guard", guard);
        branch.put("nodeRefs", new ArrayList<>(nodes));
        branch.put("objective", null);
        branch.put("outcomeCandidates", new ArrayList<>(List.of("The quest objective can be met another way.")));
        return branch;
    }

    /** @return a cannot-plan body with echoed bindings */
    public static Map<String, Object> cannotPlan(Refs refs, String reason) {
        var body = plan(refs);
        for (String field : List.copyOf(body.keySet())) {
            Object value = body.get(field);
            if (!List.of("planId", "planVersion", "questId", "predecessorPlanId", "locale").contains(field)) {
                body.put(field, value instanceof List<?> ? new ArrayList<>() : null);
            }
        }
        body.put("reasonCode", reason);
        return body;
    }
}
