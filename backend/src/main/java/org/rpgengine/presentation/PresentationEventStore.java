package org.rpgengine.presentation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write side of durable presentation events (P05.7-B.1.1, V26 {@code presentation_event}). Its one
 * entry point takes untrusted drafts and stores only what {@link PresentationEvents#acceptAll}
 * returns, so a rejected draft can reach neither storage nor the stream. The V26 trigger appends
 * the {@code presentation.event} stream message in the same transaction; nothing here writes the
 * stream, and {@code PublicStreamService} remains the only delivery path. Since P05.7-B.1.2.3.2 the
 * settling transaction of {@link PresentationJobService} calls it for the cues of a publication.
 *
 * <p>Always holds: a campaign, operation and ordinal is stored and announced at most once, whatever
 * the retries, and the first stored content wins. Nothing here touches world rows or advances a
 * canonical counter.
 */
@Service
public class PresentationEventStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * Wire persistence; missing dependencies fail startup.
     *
     * @param jdbc JDBC access joined to the transaction
     * @param manager shared transaction manager
     */
    public PresentationEventStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Admit and store the events of one committed operation, all or nothing. Joins a caller's
     * transaction when there is one; otherwise runs in its own.
     *
     * @param campaign campaign that owns the operation
     * @param drafts untrusted events of one operation
     * @param scope allowlist of the audience the events will be delivered to
     * @return events newly stored, which equals the stream messages added; 0 for an empty list or
     *         a complete repeat
     * @throws PresentationEvents.Rejection when any draft is refused; no SQL has run
     * @throws org.springframework.dao.DataAccessException when the campaign is missing, the
     *         operation did not commit at the drafts' revision in this campaign, or storage fails;
     *         nothing is stored
     */
    public int record(UUID campaign, List<PresentationEvents.Draft> drafts, PublicPresentationScope scope) {
        Objects.requireNonNull(campaign, "campaign");
        List<PresentationEvent> admitted = PresentationEvents.acceptAll(drafts, scope);
        if (admitted.isEmpty()) {
            return 0;
        }
        return transaction.execute(status -> {
            // Same lock order as the journal's stream writers: campaign first, then the stream head.
            jdbc.queryForObject("select id from campaign where id = ? for no key update", UUID.class, campaign);
            int stored = 0;
            for (PresentationEvent event : admitted) {
                stored += jdbc.update("insert into presentation_event (campaign_id, operation_id, ordinal, "
                                + "committed_revision, kind, scene_id, actor_id, pose_id, expression_id, line_id, asset_id, "
                                + "created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp()) "
                                + "on conflict (campaign_id, operation_id, ordinal) do nothing",
                        campaign, event.operationId(), event.ordinal(), event.committedRevision(), event.kind().name(),
                        event.sceneId(), event.actorId(), event.poseId(), event.expressionId(), event.lineId(),
                        event.assetId());
            }
            return stored;
        });
    }
}
