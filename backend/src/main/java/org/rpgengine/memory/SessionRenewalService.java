package org.rpgengine.memory;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.inference.session.ScopedSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manual idle renewal (P09.4, docs/08 section 7): the owner asks to start every AI session of one
 * campaign afresh, for example after noticing drift. Renewal only retires sessions
 * ({@link ScopedSessions#renewCampaign}); each line's next task opens a new session that carries a
 * continuity block ({@link SessionRehydration}). It never writes campaign state, events, narration
 * or a decision, so the campaign and its public transcript are preserved exactly.
 *
 * <p>The campaign must be idle, meaning that nothing of it is running, checked in one read-only
 * snapshot; otherwise nothing is retired:
 * <ul>
 *   <li>no live mutation lease;</li>
 *   <li>no operation being resolved or narrated ({@code ACCEPTED}, {@code PLANNING},
 *       {@code VALIDATING}, {@code COMMITTING} or {@code NARRATING});</li>
 *   <li>no running presentation job or summary job, and no dispatched or reconciling inference
 *       task.</li>
 * </ul>
 * Waiting work does not make a campaign busy: a committed action whose narration is queued, an
 * operation waiting for the player ({@code NEEDS_CLARIFICATION}) or for a narration retry
 * ({@code COMMITTED_NARRATION_PENDING}), and queued jobs or tasks simply start in the renewed
 * sessions, with a continuity block. Counting them would let a stalled queue block renewal forever.
 * A task dispatched between the check and the retirement still finishes on its old conversation
 * (registry contract), so the race costs continuity only.
 */
@Service
public class SessionRenewalService {
    private final JdbcTemplate jdbc;
    private final ScopedSessions sessions;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    /** Spring constructor with the system clock. */
    @Autowired
    public SessionRenewalService(JdbcTemplate jdbc, PlatformTransactionManager manager, ScopedSessions sessions) {
        this(jdbc, manager, sessions, Clock.systemUTC());
    }

    /**
     * @param jdbc storage
     * @param manager transactions for the idle snapshot
     * @param sessions the installation's session registry
     * @param clock time source of lease expiry
     */
    public SessionRenewalService(JdbcTemplate jdbc, PlatformTransactionManager manager, ScopedSessions sessions,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(5);
    }

    /**
     * Renew every AI session of an owned, idle campaign.
     *
     * @param owner trusted owner identity from admission
     * @param adventure adventure that must own the campaign
     * @param campaign campaign to renew
     * @return {@link Outcome#RENEWED} with the number of retired sessions (0 when none was active),
     *         {@link Outcome#BUSY} when the campaign is not idle (nothing retired), or
     *         {@link Outcome#SCOPE_UNAVAILABLE} when the owner does not own that adventure/campaign
     * @throws IllegalStateException inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures (nothing retired)
     */
    public Result renewIdle(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("renewal requires an independent snapshot");
        }
        Timestamp now = Timestamp.from(clock.instant());
        Object[] state = snapshot.execute(status -> {
            List<Long> revision = jdbc.queryForList("select c.state_revision from campaign c join adventure_workspace a "
                    + "on a.id = c.adventure_id where c.id = ? and c.adventure_id = ? and a.owner_id = ?", Long.class,
                    campaign, adventure, owner);
            if (revision.isEmpty()) {
                return null;
            }
            Integer busy = jdbc.queryForObject("select (select count(*) from campaign_mutation_lease where campaign_id = ? "
                    + "and expires_at > ?) + (select count(*) from operation_journal where campaign_id = ? and status in "
                    + "('ACCEPTED', 'PLANNING', 'VALIDATING', 'COMMITTING', 'NARRATING')) + (select count(*) from "
                    + "presentation_job where campaign_id = ? and status = 'RUNNING') + (select count(*) from "
                    + "memory_summary_job where campaign_id = ? and status = 'RUNNING') + (select count(*) from "
                    + "inference_task where campaign_id = ? and status in ('DISPATCHED', 'RECONCILING'))", Integer.class,
                    campaign, now, campaign, campaign, campaign, campaign);
            return new Object[] {revision.getFirst(), busy != null && busy > 0};
        });
        if (state == null) {
            return new Result(Outcome.SCOPE_UNAVAILABLE, 0, -1);
        }
        long revision = (long) state[0];
        if ((boolean) state[1]) {
            return new Result(Outcome.BUSY, 0, revision);
        }
        return new Result(Outcome.RENEWED, sessions.renewCampaign(campaign), revision);
    }

    /** Outcomes of {@link #renewIdle}. */
    public enum Outcome {
        /** Every active session of the campaign was retired. */
        RENEWED,
        /** The campaign was not idle; nothing was retired. */
        BUSY,
        /** The owner does not own that adventure and campaign. */
        SCOPE_UNAVAILABLE
    }

    /**
     * Result of {@link #renewIdle}.
     *
     * @param outcome what happened
     * @param renewedSessions sessions retired, 0 unless renewed
     * @param stateRevision campaign revision observed by the idle check (unchanged by renewal), -1
     *        when the scope is unavailable
     */
    public record Result(Outcome outcome, int renewedSessions, long stateRevision) {
    }
}
