package org.rpgengine.inventory;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the instance mutations and creation ledger of an {@link ItemPlan} inside a canonical
 * commit (P10.2). It never opens a transaction of its own: the commit's campaign lock, lease and
 * rollback cover every row written here, so a refused or failing step leaves no partial transfer.
 *
 * <p>Every update and delete is guarded by the exact owner, quantity, definition and storage version
 * the resolver read. A mismatch means the plan was resolved from state that has since moved, which a
 * correct commit prevents with its lock and lease; it is therefore reported as an
 * {@link IllegalStateException} that rolls the commit back, never silently merged. Ownership of a
 * worn instance cannot change at all: the equipment foreign key rejects the update, and an item
 * cannot be removed while worn.
 *
 * <p>Creation rows reference the committing operation and the plan event that announced the units,
 * and are unique per source, recipient and definition, so a replayed source can never create
 * a second time even if a defect bypassed the idempotent journal.
 */
public final class InventoryWriter {
    /** Static transaction participant with no lifecycle of its own. */
    private InventoryWriter() { }

    /**
     * Write the plan's instance mutations and ledger rows.
     *
     * @param jdbc JDBC access joined to the commit's transaction
     * @param campaign campaign whose row the caller has locked
     * @param operation committing operation
     * @param revision state revision this commit produces, recorded on inserted and created rows
     * @param firstSequence sequence number of the plan's first event; the plan's event at index
     *        {@code i} has sequence {@code firstSequence + i}
     * @param plan the resolved item effects (conservation was proved when it was built)
     * @param now commit timestamp
     * @throws IllegalStateException if a guarded row no longer matches what was resolved, or there is no ambient transaction
     * @throws org.springframework.dao.DataAccessException for constraint violations, for example the
     *         equipment foreign key or the stack bound
     */
    public static void applyWithin(JdbcTemplate jdbc, UUID campaign, UUID operation, long revision, long firstSequence,
            ItemPlan plan, Instant now) {
        Objects.requireNonNull(plan, "plan");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("item changes require the commit's transaction");
        }
        for (ItemChange change : plan.changes()) {
            switch (change) {
                case ItemChange.Update update -> {
                    if (jdbc.update("update item_instance set actor_id = ?, quantity = ?, row_version = row_version + 1 "
                            + "where campaign_id = ? and id = ? and definition_id = ? and row_version = ? "
                            + "and actor_id = ? and quantity = ?", update.newOwner(), update.newQuantity(), campaign,
                            update.instanceId(), update.definitionId(), update.expectedRowVersion(),
                            update.expectedOwner(), update.expectedQuantity()) != 1) {
                        throw stale();
                    }
                }
                case ItemChange.Delete delete -> {
                    if (jdbc.update("delete from item_instance where campaign_id = ? and id = ? and definition_id = ? "
                            + "and row_version = ? and actor_id = ? and quantity = ?", campaign, delete.instanceId(),
                            delete.definitionId(), delete.expectedRowVersion(), delete.expectedOwner(),
                            delete.expectedQuantity()) != 1) {
                        throw stale();
                    }
                }
                case ItemChange.Insert insert -> jdbc.update("insert into item_instance (id, campaign_id, actor_id, "
                        + "definition_id, quantity, maximum_stack, acquired_revision) values (?, ?, ?, ?, ?, ?, ?)",
                        insert.instanceId(), campaign, insert.ownerId(), insert.definitionId(), insert.quantity(),
                        insert.maxStack(), revision);
            }
        }
        for (ItemPlan.Creation creation : plan.creations()) {
            String identity = operation + "|" + creation.kind() + "|" + creation.sourceKey() + "|"
                    + creation.recipientId() + "|" + creation.definitionId();
            jdbc.update("insert into item_creation (id, campaign_id, operation_id, event_sequence, source_kind, "
                    + "source_key, recipient_actor_id, definition_id, quantity, applied_revision, created_at) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), campaign, operation,
                    Math.addExact(firstSequence, creation.eventIndex()), creation.kind().name(), creation.sourceKey(),
                    creation.recipientId(), creation.definitionId(), creation.quantity(), revision, Timestamp.from(now));
        }
    }

    /**
     * Query scopes the plan can change: one {@code actor:<id>} scope for every actor that gains,
     * loses or wears an item, so plans that read their inventory are invalidated at commit.
     *
     * @return ordered distinct scope keys
     */
    public static Set<String> scopes(ItemPlan plan) {
        var actors = new LinkedHashSet<UUID>();
        for (ItemChange change : plan.changes()) {
            switch (change) {
                case ItemChange.Update update -> {
                    actors.add(update.expectedOwner());
                    actors.add(update.newOwner());
                }
                case ItemChange.Delete delete -> actors.add(delete.expectedOwner());
                case ItemChange.Insert insert -> actors.add(insert.ownerId());
            }
        }
        plan.creations().forEach(creation -> actors.add(creation.recipientId()));
        plan.equipment().forEach(change -> actors.add(change.actorId()));
        var scopes = new LinkedHashSet<String>();
        actors.forEach(actor -> scopes.add("actor:" + actor));
        return scopes;
    }

    /** Sanitized stale-plan failure; the commit rolls everything back. */
    private static IllegalStateException stale() {
        return new IllegalStateException("stale or out-of-scope item instance");
    }
}
