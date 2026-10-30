package org.rpgengine.narration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PublicPresentationScopeBuilder;
import org.rpgengine.content.PublicPresentationScopeBuilder.VisibleActor;
import org.rpgengine.inference.contract.NarrationContext;
import org.rpgengine.presentation.PresentationScopePort;
import org.rpgengine.presentation.PublicPresentationScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the audience {@link PublicPresentationScope} of one committed operation from stored data
 * (P05.7-B.1.2.3.1), in one read-only repeatable-read snapshot so the pinned pack, the journal and the
 * actors describe the same commit. Read-only: it writes nothing. Since P05.7-B.1.2.3.2 a settling
 * presentation job consumes it through {@link PresentationScopePort} to check the cues of a publication.
 *
 * <p>Visibility is narration's rule, not a second model (ADR 0007, docs/06 section 7): an actor is
 * visible when it is the campaign's creation-bound player or is referenced by one of the operation's
 * {@code PUBLIC} events, found by {@link EventStatements#referencedActors}. An actor that appears only in
 * {@code INTERNAL} events or in no event of the operation, and an unknown actor ID, never reach the
 * builder, so the scope never confirms that such an actor exists. The scenes, lines and assets come from
 * the {@code presentation} declaration of the campaign's pinned pack, and each visible actor's poses and
 * expressions from its own {@code actor.definition_id}; an actor without a definition, or whose definition
 * the pack does not declare, is absent from the scope.
 *
 * <p>Cost, not optimized: {@link PackRegistry#loadForCampaign} recompiles the whole pinned pack closure on
 * every call, and no result is cached.
 */
@Component
public class PresentationScopeResolver implements PresentationScopePort {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final PackRegistry packs;
    private final TransactionTemplate reader;

    /**
     * @param jdbc JDBC access joined to the read snapshot
     * @param packs registry that reloads the campaign's pinned pack closure
     * @param manager shared transaction manager
     */
    public PresentationScopeResolver(JdbcTemplate jdbc, PackRegistry packs, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.packs = Objects.requireNonNull(packs, "packs");
        reader = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reader.setReadOnly(true);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reader.setTimeout(60);
    }

    /**
     * Resolve the scope for one committed operation.
     *
     * @param adventureId adventure that owns the campaign
     * @param campaignId campaign the operation belongs to
     * @param operationId committed operation
     * @param committedRevision revision the operation committed at
     * @return the scope, which is empty when the pinned pack declares no presentation; or
     *         {@link Optional#empty()} when the campaign is unknown or belongs to another adventure, the
     *         operation is unknown or not committed at {@code committedRevision}, or it has more public
     *         events than narration may state ({@link NarrationContext#MAX_EVENTS}). A caller treats an
     *         absent result as "show nothing"; it is distinct from an empty scope so a stale request is
     *         never mistaken for a pack that declares nothing.
     * @throws IllegalStateException if called inside an ambient transaction, or when the pinned pack closure
     *         can no longer be loaded
     * @throws IllegalArgumentException when more than {@link PublicPresentationScope#MAX_ENTRIES} declared
     *         actors are visible; the scope is never truncated
     * @throws org.springframework.dao.DataAccessException for storage failures (a transient failure)
     */
    @Override
    public Optional<PublicPresentationScope> resolve(UUID adventureId, UUID campaignId, UUID operationId,
            long committedRevision) {
        Objects.requireNonNull(adventureId, "adventureId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(operationId, "operationId");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("presentation scope requires an independent snapshot");
        }
        return reader.execute(status -> read(adventureId, campaignId, operationId, committedRevision));
    }

    /** Read every part inside the snapshot; see the class description for what may be read. */
    private Optional<PublicPresentationScope> read(UUID adventureId, UUID campaignId, UUID operationId,
            long committedRevision) {
        var pinned = jdbc.query("select c.content_revision_id from campaign c join content_revision r "
                        + "on r.id = c.content_revision_id and r.adventure_id = c.adventure_id "
                        + "where c.id = ? and c.adventure_id = ?",
                (row, index) -> row.getObject(1, UUID.class), campaignId, adventureId);
        if (pinned.isEmpty()) {
            return Optional.empty();
        }
        var committed = jdbc.query("select committed_revision from operation_journal where id = ? and campaign_id = ?",
                (row, index) -> row.getObject(1, Long.class), operationId, campaignId);
        if (committed.isEmpty() || committed.getFirst() == null || committed.getFirst() != committedRevision) {
            return Optional.empty();
        }
        var events = jdbc.query("select payload::text from campaign_event where campaign_id = ? and operation_id = ? "
                        + "and visibility = 'PUBLIC' order by sequence limit ?",
                (row, index) -> JSON.readTree(row.getString(1)), campaignId, operationId, NarrationContext.MAX_EVENTS + 1);
        if (events.size() > NarrationContext.MAX_EVENTS) {
            return Optional.empty();
        }
        var referenced = new LinkedHashSet<UUID>();
        jdbc.query("select cc.actor_id from campaign_creation cc where cc.campaign_id = ?",
                row -> {
                    referenced.add(row.getObject(1, UUID.class));
                }, campaignId);
        events.forEach(payload -> referenced.addAll(EventStatements.referencedActors(payload)));
        List<VisibleActor> visible = jdbc.query("select id, definition_id from actor where campaign_id = ? and id = any(?)",
                (row, index) -> new VisibleActor(row.getObject(1, UUID.class), row.getString(2)),
                campaignId, referenced.toArray(new UUID[0]));
        var declared = packs.loadForCampaign(adventureId, pinned.getFirst()).root().presentation();
        return Optional.of(PublicPresentationScopeBuilder.build(visible, declared));
    }
}
