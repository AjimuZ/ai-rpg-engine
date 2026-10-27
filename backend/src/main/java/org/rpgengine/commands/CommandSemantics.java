package org.rpgengine.commands;

import java.util.UUID;

import org.rpgengine.campaign.StartingDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Database-only common semantic gate shared by admission and canonical commit. The
 * caller holds the campaign lock in its bounded transaction. No validation stamp from
 * an earlier snapshot grants permission: mode, pending decision and player binding are
 * read again at use. {@link CommandRules} then applies the P07.3 reachability, perception,
 * ownership, prerequisite and resource rules in the same transaction; encounter commands
 * remain unavailable until their scheduler exists.
 */
final class CommandSemantics {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Static transaction participant, with no independent lifecycle or external clients. */
    private CommandSemantics() { }

    /**
     * Authorize the campaign and resolve its immutable creation player, then reject
     * unsupported scheduling/decisions or a foreign/missing speech target. Returns the
     * acting player identity for trusted resolvers. Null decisions allow exploration;
     * the exact version-1 starting marker also permits input. Unknown decisions fail
     * closed, including future versions or markers with unrecognized fields.
     */
    static UUID requireEligible(JdbcTemplate jdbc, UUID owner, UUID adventure, UUID campaign, TypedCommand command) {
        var states = jdbc.query("select c.mode, c.pending_decision::text, cc.actor_id, a.kind from campaign c "
                        + "join adventure_workspace w on w.id = c.adventure_id "
                        + "left join campaign_creation cc on cc.campaign_id = c.id "
                        + "left join actor a on a.campaign_id = c.id and a.id = cc.actor_id "
                        + "where c.id = ? and c.adventure_id = ? and w.owner_id = ? for update of c",
                (row, index) -> new State(row.getString(1), row.getString(2), row.getObject(3, UUID.class),
                        row.getString(4)), campaign, adventure, owner);
        if (states.isEmpty()) { fail(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE); }
        State state = states.getFirst();
        if (!"EXPLORATION".equals(state.mode())) { fail(OperationAdmissionFailure.Kind.MODE_ILLEGAL); }
        if (state.decision() != null && !StartingDecision.supports(JSON.readTree(state.decision()))) {
            fail(OperationAdmissionFailure.Kind.DECISION_UNAVAILABLE);
        }
        if (state.player() == null || !"CHARACTER".equals(state.kind())) {
            fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
        }
        if (command instanceof TypedCommand.Speak speech
                && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from actor "
                        + "where campaign_id = ? and id = ?)", Boolean.class, campaign, speech.recipientActorId()))) {
            fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
        }
        return state.player();
    }

    /** Throw a sanitized category; escaping the caller's transaction guarantees rollback. */
    private static void fail(OperationAdmissionFailure.Kind kind) { throw new OperationAdmissionFailure(kind); }

    /** Call-local committed eligibility fields; never exposed through a public projection. */
    private record State(String mode, String decision, UUID player, String kind) { }
}
