package org.rpgengine.quests;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the {@link QuestChanges} of a canonical commit inside its transaction (P10.3). It never opens
 * a transaction: the commit's campaign lock, lease and rollback cover every row written here, so a
 * refused or failing step leaves no partial quest transition.
 *
 * <p>An update is guarded by the exact storage version the resolver read, so a plan resolved from
 * state that has since moved fails with {@link IllegalStateException} and rolls the commit back; an
 * insert of an already stored quest fails on the primary key. Terminal rows cannot change at all (a
 * database trigger), so a quest that ended cannot settle again. Objective and visit rows are
 * append-only; a visit that is already stored is skipped.
 */
public final class QuestWriter {
    private static final Pattern SCOPE_SAFE = Pattern.compile("[a-z][a-z0-9_.:-]{0,120}");

    /** Static transaction participant with no lifecycle of its own. */
    private QuestWriter() { }

    /**
     * Write the changes.
     *
     * @param jdbc JDBC access joined to the commit's transaction
     * @param campaign campaign whose row the caller has locked
     * @param revision state revision this commit produces
     * @param firstSequence sequence number of the plan's first event; the event at index {@code i} has
     *        sequence {@code firstSequence + i}
     * @param changes the resolved quest effects
     * @throws IllegalStateException if a guarded row no longer matches what was resolved, or there is no
     *         ambient transaction
     * @throws org.springframework.dao.DataAccessException for constraint or trigger violations
     */
    public static void applyWithin(JdbcTemplate jdbc, UUID campaign, long revision, long firstSequence,
            QuestChanges changes) {
        Objects.requireNonNull(changes, "changes");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("quest changes require the commit's transaction");
        }
        for (QuestChanges.StateWrite write : changes.states()) {
            long sequence = Math.addExact(firstSequence, write.eventIndex());
            if (write.expectedRowVersion() == QuestPlan.State.ABSENT) {
                jdbc.update("insert into quest_state (campaign_id, quest_id, status, outcome_id, event_sequence, "
                        + "updated_revision) values (?, ?, ?, ?, ?, ?)", campaign, write.questId(),
                        write.status().wire(), write.outcomeId(), sequence, revision);
            } else if (jdbc.update("update quest_state set status = ?, outcome_id = ?, event_sequence = ?, "
                    + "updated_revision = ?, row_version = row_version + 1 where campaign_id = ? and quest_id = ? "
                    + "and row_version = ?", write.status().wire(), write.outcomeId(), sequence, revision, campaign,
                    write.questId(), write.expectedRowVersion()) != 1) {
                throw new IllegalStateException("stale or out-of-scope quest state");
            }
        }
        for (QuestChanges.ObjectiveWrite write : changes.objectives()) {
            jdbc.update("insert into quest_objective (campaign_id, quest_id, objective_id, event_sequence, "
                    + "satisfied_revision) values (?, ?, ?, ?, ?)", campaign, write.questId(), write.objectiveId(),
                    Math.addExact(firstSequence, write.eventIndex()), revision);
        }
        for (QuestChanges.VisitWrite write : changes.visits()) {
            jdbc.update("insert into location_visit (campaign_id, actor_id, location_id, first_revision) "
                    + "values (?, ?, ?, ?) on conflict do nothing", campaign, write.actorId(), write.locationId(),
                    revision);
        }
    }

    /**
     * Query scopes the changes can affect: {@code quests} for any quest change, and
     * {@code quest:<id>} for each touched quest whose identity is a legal scope key (local quests; a
     * dependency quest's {@code pack#id} is covered by {@code quests}). Plans that read quest state
     * are invalidated at commit through these scopes.
     *
     * @param changes the resolved quest effects
     * @return ordered distinct scope keys, empty when nothing changes
     */
    public static Set<String> scopes(QuestChanges changes) {
        var scopes = new LinkedHashSet<String>();
        if (!changes.states().isEmpty()) {
            scopes.add("quests");
        }
        for (QuestChanges.StateWrite write : changes.states()) {
            if (SCOPE_SAFE.matcher(write.questId()).matches()) {
                scopes.add("quest:" + write.questId());
            }
        }
        return scopes;
    }
}
