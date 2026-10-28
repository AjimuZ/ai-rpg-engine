package org.rpgengine.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audience-scoped readers of typed knowledge (P09.1). Each call reads one read-only
 * repeatable-read snapshot, so every list and the revision it reports describe the same commit.
 * Callers are internal services that already authorized the campaign (context assembly, P09.2;
 * panels, P10.5); nothing here is an HTTP projection.
 *
 * <p>{@link #forActor} is what one actor may know: its current beliefs with their content but
 * <em>without</em> truth, current common-knowledge claims, its own last known locations of other
 * actors (never their real current locations), its own current location, and the open commitments
 * it is party to or that are common knowledge. {@link #privileged} is the game master's view for
 * privileged roles only: truth, validity intervals, every holder's beliefs, provenance sequences,
 * real current locations beside last known ones, and resolved commitments.
 *
 * <p>Each list is bounded by {@link #MAX_ROWS}; exceeding it fails instead of returning a silently
 * incomplete view. Ranking and budget-driven selection belong to context assembly (P09.2).
 */
@Service
public class KnowledgeView {
    /** Rows per list; one more row than this fails the read. */
    public static final int MAX_ROWS = 1000;
    private static final String CLAIM_COLUMNS = "c.id as claim_id, c.subject_ref, c.predicate, c.object_ref, "
            + "c.qualifier, c.property_value::text as property_value, c.statement, c.statement_locale";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;

    /** Wire storage; missing infrastructure fails application startup. */
    public KnowledgeView(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(15);
    }

    /**
     * Read what one actor may know at the latest committed revision.
     *
     * @param campaign campaign the caller authorized
     * @param holder audience actor of that campaign
     * @return detached audience view
     * @throws IllegalArgumentException when the holder is not an actor of the campaign
     * @throws IllegalStateException inside an ambient transaction or when a list exceeds {@link #MAX_ROWS}
     */
    public Audience forActor(UUID campaign, UUID holder) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(holder, "holder");
        independent();
        return snapshot.execute(status -> {
            var self = jdbc.query("select a.location_id, c.state_revision, c.world_time_seconds from actor a "
                    + "join campaign c on c.id = a.campaign_id where a.campaign_id = ? and a.id = ?",
                    (row, index) -> new Object[] {row.getString(1), row.getLong(2), row.getLong(3)}, campaign, holder);
            if (self.isEmpty()) {
                throw new IllegalArgumentException("unknown audience");
            }
            long revision = (Long) self.getFirst()[1];
            long worldTime = (Long) self.getFirst()[2];
            var beliefs = bounded(jdbc.query("select b.id, b.stance, b.acquisition, b.informant_actor_id, b.source_ref, "
                    + "b.learned_revision, b.learned_world_time, " + CLAIM_COLUMNS + " from knowledge_belief b "
                    + "join knowledge_claim c on c.campaign_id = b.campaign_id and c.id = b.claim_id "
                    + "where b.campaign_id = ? and b.holder_actor_id = ? and b.superseded_revision is null "
                    + "order by b.learned_revision, b.id limit ?",
                    (row, index) -> new HeldBelief(row.getObject("id", UUID.class), content(row),
                            KnowledgeChange.Stance.valueOf(row.getString("stance")),
                            KnowledgeChange.Acquisition.valueOf(row.getString("acquisition")),
                            row.getObject("informant_actor_id", UUID.class), row.getString("source_ref"),
                            row.getLong("learned_revision"), row.getLong("learned_world_time")),
                    campaign, holder, MAX_ROWS + 1));
            // A held belief is the more specific record, so a common claim the holder already has a
            // stance on is listed once, as that belief.
            var common = bounded(jdbc.query("select c.established_revision, " + CLAIM_COLUMNS + " from knowledge_claim c "
                    + "where c.campaign_id = ? and c.disclosure = 'COMMON' and c.superseded_revision is null "
                    + "and not exists (select 1 from knowledge_belief b where b.campaign_id = c.campaign_id "
                    + "and b.claim_id = c.id and b.holder_actor_id = ? and b.superseded_revision is null) "
                    + "order by c.established_revision, c.id limit ?",
                    (row, index) -> new CommonClaim(content(row), row.getLong("established_revision")),
                    campaign, holder, MAX_ROWS + 1));
            var lastKnown = bounded(jdbc.query("select subject_actor_id, location_id, acquisition, informant_actor_id, "
                    + "observed_revision, observed_world_time, absence_revision, absence_world_time from actor_sighting "
                    + "where campaign_id = ? and observer_actor_id = ? order by subject_actor_id limit ?",
                    (row, index) -> new LastKnown(row.getObject(1, UUID.class), row.getString(2),
                            KnowledgeChange.Acquisition.valueOf(row.getString(3)), row.getObject(4, UUID.class),
                            row.getLong(5), row.getLong(6), nullableLong(row, 7), nullableLong(row, 8)),
                    campaign, holder, MAX_ROWS + 1));
            var commitments = bounded(jdbc.query("select id, kind, debtor_actor_id, creditor_actor_id, subject_ref, "
                    + "terms, terms_locale, quoted, due_world_time, made_revision from actor_commitment "
                    + "where campaign_id = ? and status = 'OPEN' and (debtor_actor_id = ? or creditor_actor_id = ? "
                    + "or disclosure = 'COMMON') order by made_revision, id limit ?",
                    (row, index) -> {
                        Long due = nullableLong(row, 9);
                        return new OpenCommitment(row.getObject(1, UUID.class),
                                KnowledgeChange.CommitmentKind.valueOf(row.getString(2)), row.getObject(3, UUID.class),
                                row.getObject(4, UUID.class), row.getString(5), row.getString(6), row.getString(7),
                                row.getBoolean(8), due, due != null && worldTime > due, row.getLong(10));
                    }, campaign, holder, holder, MAX_ROWS + 1));
            return new Audience(campaign, holder, revision, worldTime, (String) self.getFirst()[0], beliefs, common,
                    lastKnown, commitments);
        });
    }

    /**
     * Read the complete privileged knowledge record of a campaign. Only privileged roles (planner,
     * game master tooling) may receive this value; it contains truth and real locations.
     *
     * @param campaign campaign the caller authorized
     * @return detached privileged view
     * @throws IllegalArgumentException for an unknown campaign
     * @throws IllegalStateException inside an ambient transaction or when a list exceeds {@link #MAX_ROWS}
     */
    public Privileged privileged(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        independent();
        return snapshot.execute(status -> {
            var counters = jdbc.query("select state_revision, world_time_seconds from campaign where id = ?",
                    (row, index) -> new long[] {row.getLong(1), row.getLong(2)}, campaign);
            if (counters.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            long worldTime = counters.getFirst()[1];
            var claims = bounded(jdbc.query("select c.disclosure, c.truth, c.established_revision, "
                    + "c.established_event_sequence, c.truth_revision, c.truth_event_sequence, c.supersedes, "
                    + "c.superseded_revision, " + CLAIM_COLUMNS + " from knowledge_claim c where c.campaign_id = ? "
                    + "order by c.established_revision, c.id limit ?",
                    (row, index) -> new TruthClaim(content(row),
                            KnowledgeChange.Disclosure.valueOf(row.getString("disclosure")),
                            KnowledgeChange.Truth.valueOf(row.getString("truth")), row.getLong("established_revision"),
                            row.getLong("established_event_sequence"), row.getLong("truth_revision"),
                            row.getLong("truth_event_sequence"), row.getObject("supersedes", UUID.class),
                            nullableLong(row, "superseded_revision")),
                    campaign, MAX_ROWS + 1));
            var beliefs = bounded(jdbc.query("select id, holder_actor_id, claim_id, stance, acquisition, "
                    + "informant_actor_id, source_ref, learned_revision, learned_event_sequence, supersedes, "
                    + "superseded_revision from knowledge_belief where campaign_id = ? "
                    + "order by learned_revision, id limit ?",
                    (row, index) -> new RecordedBelief(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class), KnowledgeChange.Stance.valueOf(row.getString(4)),
                            KnowledgeChange.Acquisition.valueOf(row.getString(5)), row.getObject(6, UUID.class),
                            row.getString(7), row.getLong(8), row.getLong(9), row.getObject(10, UUID.class),
                            nullableLong(row, 11)),
                    campaign, MAX_ROWS + 1));
            var whereabouts = bounded(jdbc.query("select s.observer_actor_id, s.subject_actor_id, s.location_id, "
                    + "s.acquisition, s.informant_actor_id, s.observed_revision, s.observed_event_sequence, "
                    + "s.absence_revision, a.location_id from actor_sighting s join actor a "
                    + "on a.campaign_id = s.campaign_id and a.id = s.subject_actor_id where s.campaign_id = ? "
                    + "order by s.observer_actor_id, s.subject_actor_id limit ?",
                    (row, index) -> new Whereabouts(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getString(3), KnowledgeChange.Acquisition.valueOf(row.getString(4)),
                            row.getObject(5, UUID.class), row.getLong(6), row.getLong(7), nullableLong(row, 8),
                            row.getString(9)),
                    campaign, MAX_ROWS + 1));
            var commitments = bounded(jdbc.query("select id, kind, debtor_actor_id, creditor_actor_id, subject_ref, "
                    + "terms, terms_locale, quoted, disclosure, due_world_time, status, made_revision, "
                    + "made_event_sequence, resolved_revision, resolved_event_sequence from actor_commitment "
                    + "where campaign_id = ? order by made_revision, id limit ?",
                    (row, index) -> {
                        Long due = nullableLong(row, 10);
                        var state = KnowledgeChange.CommitmentStatus.valueOf(row.getString(11));
                        return new CommitmentRecord(row.getObject(1, UUID.class),
                                KnowledgeChange.CommitmentKind.valueOf(row.getString(2)), row.getObject(3, UUID.class),
                                row.getObject(4, UUID.class), row.getString(5), row.getString(6), row.getString(7),
                                row.getBoolean(8), KnowledgeChange.CommitmentDisclosure.valueOf(row.getString(9)), due,
                                state, state == KnowledgeChange.CommitmentStatus.OPEN && due != null && worldTime > due,
                                row.getLong(12), row.getLong(13), nullableLong(row, 14), nullableLong(row, 15));
                    }, campaign, MAX_ROWS + 1));
            return new Privileged(campaign, counters.getFirst()[0], worldTime, claims, beliefs, whereabouts, commitments);
        });
    }

    /** Map the shared claim columns; truth and disclosure are deliberately not part of the content. */
    private static ClaimContent content(ResultSet row) throws SQLException {
        return new ClaimContent(row.getObject("claim_id", UUID.class), row.getString("subject_ref"),
                KnowledgeChange.Predicate.valueOf(row.getString("predicate")), row.getString("object_ref"),
                row.getString("qualifier"), row.getString("property_value"), row.getString("statement"),
                row.getString("statement_locale"));
    }

    /** Read a nullable bigint column by position. */
    private static Long nullableLong(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    /** Read a nullable bigint column by label. */
    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    /** Fail rather than present a truncated list as complete knowledge. */
    private static <T> List<T> bounded(List<T> rows) {
        if (rows.size() > MAX_ROWS) {
            throw new IllegalStateException("knowledge view limit exceeded");
        }
        return List.copyOf(rows);
    }

    /** Reject ambient transactions so the snapshot cannot be downgraded or mixed with writes. */
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge view requires an independent transaction");
        }
    }

    /**
     * Proposition of a claim without its truth or disclosure.
     *
     * @param claimId claim identity
     * @param subjectRef typed subject
     * @param predicate proposition shape
     * @param objectRef typed object of a relation, otherwise null
     * @param qualifier relation or property key, otherwise null
     * @param valueJson scalar property value as JSON text, otherwise null
     * @param statement proposition or gloss text, otherwise null
     * @param statementLocale language of the statement, otherwise null
     */
    public record ClaimContent(UUID claimId, String subjectRef, KnowledgeChange.Predicate predicate, String objectRef,
            String qualifier, String valueJson, String statement, String statementLocale) { }

    /** A current belief of the audience actor: what it holds and how it learned it, never whether it is true. */
    public record HeldBelief(UUID beliefId, ClaimContent claim, KnowledgeChange.Stance stance,
            KnowledgeChange.Acquisition acquisition, UUID informantId, String sourceRef, long learnedRevision,
            long learnedWorldTime) { }

    /** A current common-knowledge claim the audience holds no separate belief about. */
    public record CommonClaim(ClaimContent claim, long establishedRevision) { }

    /**
     * The audience's last known location of another actor; absence fields record a later look at
     * the same place that did not find the subject. This is never the subject's real location.
     */
    public record LastKnown(UUID subjectId, String locationId, KnowledgeChange.Acquisition acquisition,
            UUID informantId, long observedRevision, long observedWorldTime, Long absenceRevision,
            Long absenceWorldTime) { }

    /** An unresolved commitment visible to the audience; overdue is derived from current world time. */
    public record OpenCommitment(UUID commitmentId, KnowledgeChange.CommitmentKind kind, UUID debtorId,
            UUID creditorId, String subjectRef, String terms, String termsLocale, boolean quoted, Long dueWorldTime,
            boolean overdue, long madeRevision) { }

    /**
     * Complete audience view at one committed revision.
     *
     * @param campaignId campaign scope
     * @param holderId audience actor
     * @param stateRevision revision every list describes
     * @param worldTime campaign world time at that revision
     * @param ownLocation the audience actor's own current location, or null
     * @param beliefs current beliefs
     * @param common current common knowledge not already held as a belief
     * @param lastKnown last known locations of other actors
     * @param commitments open commitments the audience is party to or that are common knowledge
     */
    public record Audience(UUID campaignId, UUID holderId, long stateRevision, long worldTime, String ownLocation,
            List<HeldBelief> beliefs, List<CommonClaim> common, List<LastKnown> lastKnown,
            List<OpenCommitment> commitments) { }

    /** Privileged claim record with truth, disclosure, provenance and validity interval. */
    public record TruthClaim(ClaimContent claim, KnowledgeChange.Disclosure disclosure, KnowledgeChange.Truth truth,
            long establishedRevision, long establishedEventSequence, long truthRevision, long truthEventSequence,
            UUID supersedes, Long supersededRevision) { }

    /** Privileged belief record of any holder, current or superseded. */
    public record RecordedBelief(UUID beliefId, UUID holderId, UUID claimId, KnowledgeChange.Stance stance,
            KnowledgeChange.Acquisition acquisition, UUID informantId, String sourceRef, long learnedRevision,
            long learnedEventSequence, UUID supersedes, Long supersededRevision) { }

    /** Privileged sighting with the subject's real current location beside the observer's last known one. */
    public record Whereabouts(UUID observerId, UUID subjectId, String lastKnownLocation,
            KnowledgeChange.Acquisition acquisition, UUID informantId, long observedRevision,
            long observedEventSequence, Long absenceRevision, String currentLocation) { }

    /** Privileged commitment record in any status. */
    public record CommitmentRecord(UUID commitmentId, KnowledgeChange.CommitmentKind kind, UUID debtorId,
            UUID creditorId, String subjectRef, String terms, String termsLocale, boolean quoted,
            KnowledgeChange.CommitmentDisclosure disclosure, Long dueWorldTime, KnowledgeChange.CommitmentStatus status,
            boolean overdue, long madeRevision, long madeEventSequence, Long resolvedRevision,
            Long resolvedEventSequence) { }

    /** Complete privileged view at one committed revision. */
    public record Privileged(UUID campaignId, long stateRevision, long worldTime, List<TruthClaim> claims,
            List<RecordedBelief> beliefs, List<Whereabouts> whereabouts, List<CommitmentRecord> commitments) { }
}
