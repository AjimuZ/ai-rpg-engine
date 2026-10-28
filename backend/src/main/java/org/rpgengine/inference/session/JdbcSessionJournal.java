package org.rpgengine.inference.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL {@link SessionJournal} over {@code ai_session_record} (V17). Each call is one
 * auto-committed statement, so a record never joins a caller's transaction, and every storage
 * failure is logged by exception class and swallowed (the journal contract).
 *
 * <p>Line numbering is computed in the insert itself from the line's newest record, so the
 * predecessor link and {@code line_generation} stay consistent across processes and restarts; the
 * unique line index refuses a concurrent duplicate, which is then only logged.
 */
@Component
public class JdbcSessionJournal implements SessionJournal {
    private static final System.Logger LOG = System.getLogger(JdbcSessionJournal.class.getName());
    private final JdbcTemplate jdbc;

    /** @param jdbc storage; a missing bean fails startup */
    public JdbcSessionJournal(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean hasHistory(UUID campaignId, AiRole role, Audience audience) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from ai_session_record where "
                    + "campaign_id = ? and role = ? and audience = ?)", Boolean.class, campaignId, role.wire(),
                    audience.wire()));
        } catch (RuntimeException failure) {
            warn("history", failure);
            return false;
        }
    }

    @Override
    public void opened(Opened record) {
        SessionScope scope = record.scope();
        Rehydrator.Ready ready = record.rehydration();
        boolean carried = ready != null && !record.basis().equals("UNAVAILABLE") && !record.basis().equals("NONE");
        Rehydrator.Checkpoint checkpoint = ready == null ? null : ready.checkpoint();
        try {
            // Without an earlier record the session is the line's first whatever the caller saw
            // in memory (only possible after an earlier journal failure): cause FIRST, no basis.
            jdbc.update("insert into ai_session_record (id, adventure_id, campaign_id, campaign_generation, role, "
                    + "audience, pack_hash, policy_hash, line_generation, predecessor_id, opened_cause, status, "
                    + "rehydration_basis, rehydration_code, rehydration_revision, rehydration_digest, checkpoint_id, "
                    + "checkpoint_revision, checkpoint_hash, checkpoint_status, summary_id, opened_at) "
                    + "select ?, ?, ?, ?, ?, ?, ?, ?, coalesce(p.line_generation, 0) + 1, p.id, "
                    + "case when p.id is null then 'FIRST' else ? end, 'ACTIVE', "
                    + "case when p.id is null then 'NONE' else ? end, case when p.id is null then null else ? end, "
                    + "case when p.id is null then null else ?::bigint end, case when p.id is null then null else ? end, "
                    + "case when p.id is null then null else ?::uuid end, case when p.id is null then null else ?::bigint end, "
                    + "case when p.id is null then null else ? end, case when p.id is null then null else ? end, "
                    + "case when p.id is null then null else ?::uuid end, ? "
                    + "from (select 1) one left join lateral (select id, line_generation from ai_session_record where "
                    + "campaign_id = ? and role = ? and audience = ? order by line_generation desc limit 1) p on true",
                    record.sessionId(), scope.adventureId(), scope.campaignId(), scope.campaignGeneration(),
                    scope.role().wire(), scope.audience().wire(), scope.packHash(), scope.policyHash(), record.cause(),
                    record.basis(), record.code(), carried ? ready.revision() : null, carried ? ready.digest() : null,
                    checkpoint == null ? null : checkpoint.id(), checkpoint == null ? null : checkpoint.revision(),
                    checkpoint == null ? null : checkpoint.hash(), ready == null ? null : ready.checkpointStatus(),
                    record.basis().equals("SUMMARY") ? ready.summaryId() : null, Timestamp.from(record.at()),
                    scope.campaignId(), scope.role().wire(), scope.audience().wire());
        } catch (RuntimeException failure) {
            warn("open", failure);
        }
    }

    @Override
    public void used(UUID sessionId, Usage usage, Instant at) {
        try {
            // greatest() keeps the monotonic trigger satisfied if a stale report arrives late.
            jdbc.update("update ai_session_record set turns = greatest(turns, ?), completed_tasks = greatest("
                    + "completed_tasks, ?), context_units = greatest(context_units, ?), context_warned = context_warned "
                    + "or ?, last_used_at = ?, row_version = row_version + 1 where id = ?", usage.turns(),
                    usage.completed(), usage.contextUnits(), usage.warned(), Timestamp.from(at), sessionId);
        } catch (RuntimeException failure) {
            warn("use", failure);
        }
    }

    @Override
    public void retired(UUID sessionId, ScopedSessions.Reason reason, Instant at) {
        try {
            jdbc.update("update ai_session_record set status = 'RETIRED', retired_reason = ?, retired_at = ?, "
                    + "row_version = row_version + 1 where id = ? and status = 'ACTIVE'", reason.name(),
                    Timestamp.from(at), sessionId);
        } catch (RuntimeException failure) {
            warn("retire", failure);
        }
    }

    @Override
    public int recover(Instant at) {
        try {
            return jdbc.update("update ai_session_record set status = 'RETIRED', retired_reason = 'RESTART', "
                    + "retired_at = ?, row_version = row_version + 1 where status = 'ACTIVE'", Timestamp.from(at));
        } catch (RuntimeException failure) {
            warn("recover", failure);
            return 0;
        }
    }

    /** Log a failed write without its message, which may quote values. */
    private static void warn(String step, RuntimeException failure) {
        LOG.log(System.Logger.Level.WARNING, "AI session journal " + step + " failed: " + failure.getClass().getName());
    }
}
