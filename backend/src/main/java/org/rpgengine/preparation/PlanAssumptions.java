package org.rpgengine.preparation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Private planning assumptions of usable quest plans, for privileged context assembly (P09.2,
 * docs/08 section 6, docs/28). Plan storage stays owned by this package (ADR 0014, enforced by
 * {@code PlanningBoundaryTest}); the memory layer receives only these typed, detached records.
 *
 * <p>An assumption is a planner's unverified hypothesis. It is never an admitted fact, never
 * public and never a reason to change the world. Callers must hand it only to a privileged
 * audience and label it as an assumption.
 *
 * <p>Only the newest version of each plan is read, and only when it passed the P07.6 gate, its
 * admission belongs to the campaign's current recovery generation and its latest disposition is
 * {@code VALIDATED} or {@code PARTIALLY_INVALIDATED}. {@link Assumption#fresh()} reports whether
 * every whole-plan dependency is still current; a stale plan's assumptions stay visible to the
 * planner, labelled stale, because they explain what is being replanned.
 */
public final class PlanAssumptions {
    /** Most plan artifacts whose assumptions one read returns. */
    public static final int MAX_ARTIFACTS = 32;
    /** Assumption ids accepted by the quest-plan contract. */
    private static final Pattern LOCAL_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Static helpers only. */
    private PlanAssumptions() {
    }

    /**
     * Read the assumptions of usable plans inside the caller's transaction.
     *
     * @param jdbc transaction-bound template
     * @param campaign campaign the caller authorized for a privileged audience
     * @return assumptions, newest artifact first, each artifact's in body order; entries that are
     *         not contract-shaped are skipped
     * @throws IllegalStateException without an active transaction
     */
    public static List<Assumption> usableWithin(JdbcTemplate jdbc, UUID campaign) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(campaign, "campaign");
        PreparationSupport.requireTransaction();
        var out = new ArrayList<Assumption>();
        jdbc.query("select a.id, a.plan_id, a.plan_version, a.quest_target, a.body::text, d.status, "
                + "not exists (select 1 from plan_dependency p where p.artifact_id = a.id and p.severity = 'ARTIFACT' "
                + "and not plan_dependency_fresh(a.campaign_id, p)) as fresh, extract(epoch from a.created_at)::bigint "
                + "from quest_plan_artifact a join campaign c on c.id = a.campaign_id "
                + "join plan_admission ad on ad.artifact_id = a.id and ad.gate_version is not null "
                + "and ad.campaign_generation = c.recovery_generation "
                + "cross join lateral (select status from plan_disposition where artifact_id = a.id "
                + "order by sequence desc limit 1) d where a.campaign_id = ? "
                + "and d.status in ('VALIDATED', 'PARTIALLY_INVALIDATED') and a.plan_version = (select max(b.plan_version) "
                + "from quest_plan_artifact b where b.campaign_id = a.campaign_id and b.plan_id = a.plan_id) "
                + "order by a.created_at desc, a.id limit ?", row -> {
                    JsonNode body = JSON.readTree(row.getString(5));
                    String locale = body.path("locale").isString()
                            && List.of("en", "it").contains(body.path("locale").asString())
                            ? body.path("locale").asString() : null;
                    for (JsonNode entry : body.path("assumptions")) {
                        String id = entry.path("assumptionId").isString() ? entry.path("assumptionId").asString() : "";
                        if (!LOCAL_ID.matcher(id).matches() || !entry.path("note").isString()) {
                            continue;
                        }
                        out.add(new Assumption(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                                row.getInt(3), row.getString(4), row.getString(6), row.getBoolean(7), row.getLong(8),
                                locale, id, entry.path("note").asString(), strings(entry.path("branchRefs")),
                                strings(entry.path("dependencyRefs"))));
                    }
                }, campaign, MAX_ARTIFACTS);
        return List.copyOf(out);
    }

    /** @return string elements of a JSON array; other elements are skipped */
    private static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(node -> {
            if (node.isString()) {
                out.add(node.asString());
            }
        });
        return List.copyOf(out);
    }

    /**
     * One private planning assumption.
     *
     * @param artifactId plan artifact holding it
     * @param planId stable plan identity
     * @param planVersion plan version of the artifact
     * @param questTarget quest the plan serves, for example {@code known-quest:<uuid>}
     * @param planStatus latest disposition, {@code VALIDATED} or {@code PARTIALLY_INVALIDATED}
     * @param fresh whether every whole-plan dependency is still current
     * @param createdEpochSeconds artifact creation time, for recency ranking
     * @param locale language of the plan prose ({@code en} or {@code it}), or null when absent
     * @param assumptionId local id inside the plan
     * @param note the assumption text; private and unverified
     * @param branchRefs plan branches the assumption scopes
     * @param dependencyRefs recorded reads the assumption cites
     */
    public record Assumption(UUID artifactId, UUID planId, int planVersion, String questTarget, String planStatus,
            boolean fresh, long createdEpochSeconds, String locale, String assumptionId, String note,
            List<String> branchRefs, List<String> dependencyRefs) {
    }
}
