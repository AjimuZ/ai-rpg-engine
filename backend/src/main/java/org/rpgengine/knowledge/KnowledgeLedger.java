package org.rpgengine.knowledge;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.persistence.CanonicalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional writer and rule reader of typed knowledge (P09.1). Every method joins the caller's
 * transaction, which must already hold the campaign row lock that all canonical writers take
 * first, so the rows read here cannot change before the caller commits. Writes happen only beside
 * the events they cite; a failure throws and the caller's rollback removes every write together
 * with the world change that caused it.
 *
 * <p>Query scopes. Each write reports the scopes it changed so the commit bumps their epochs in
 * the same transaction and admitted plans that read them become observably stale:
 * <ul>
 *   <li>{@value #CLAIMS_SCOPE} — any claim registered, superseded or determined (claims are shared
 *       by every holder and COMMON claims by every audience);</li>
 *   <li>{@code knowledge:<actor>} — that holder's beliefs;</li>
 *   <li>{@code sightings:<actor>} — that observer's last known locations;</li>
 *   <li>{@code commitments:<actor>} — commitments where that actor is debtor or creditor.</li>
 * </ul>
 * Every changed row also steps its {@code row_version}, so a versioned plan dependency on a claim,
 * belief or commitment ({@code claim:}, {@code belief:}, {@code commitment:} references) is stale too.
 */
public final class KnowledgeLedger {
    /** Query scope of the campaign's claim registry. */
    public static final String CLAIMS_SCOPE = "knowledge:claims";
    /** Upper bound of actors one automatic observation records; larger crowds need encounter rules (P11). */
    public static final int MAX_OBSERVED = 64;

    /** Static transaction participant only. */
    private KnowledgeLedger() { }

    /** @return query scope of one holder's beliefs */
    public static String knowledgeScope(UUID holder) {
        return "knowledge:" + Objects.requireNonNull(holder, "holder");
    }

    /** @return query scope of one observer's last known locations */
    public static String sightingScope(UUID observer) {
        return "sightings:" + Objects.requireNonNull(observer, "observer");
    }

    /** @return query scope of the commitments one actor is party to */
    public static String commitmentScope(UUID actor) {
        return "commitments:" + Objects.requireNonNull(actor, "actor");
    }

    /**
     * Commit-supplied provenance of one operation: its committing revision and world time and the
     * campaign sequence range of its events.
     *
     * @param revision state revision the operation commits
     * @param worldTime campaign world time after the operation
     * @param firstSequence campaign sequence of the operation's first event
     * @param eventCount number of events the operation appended (at least one)
     */
    public record Provenance(long revision, long worldTime, long firstSequence, int eventCount) {
        /** Reject counters that cannot belong to a committed operation. */
        public Provenance {
            CanonicalValues.requirePositive(revision, "revision");
            CanonicalValues.requireNonNegative(worldTime, "worldTime");
            CanonicalValues.requirePositive(firstSequence, "firstSequence");
            if (eventCount < 1) {
                throw new IllegalArgumentException("an operation has at least one event");
            }
        }

        /** @return campaign sequence of the operation event at {@code index}; out-of-range indexes fail */
        long sequence(int index) {
            if (index < 0 || index >= eventCount) {
                throw new IllegalArgumentException("source event outside this operation");
            }
            return firstSequence + index;
        }
    }

    /**
     * Apply trusted knowledge changes in list order, so a belief may cite a claim registered
     * earlier in the same list.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked
     * @param at provenance of the committing operation
     * @param changes typed changes; empty writes nothing
     * @return the query scopes the changes affect, in first-touch order
     * @throws IllegalStateException when a premise no longer holds: a stale row version, a closed or
     *         unrelated predecessor, an already determined claim or resolved commitment, an unknown
     *         campaign reference, a second current belief of one holder about one claim, or an
     *         observed sighting that contradicts the subject's real location
     */
    public static Set<String> applyWithin(JdbcTemplate jdbc, UUID campaign, Provenance at,
            List<KnowledgeChange> changes) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(at, "at");
        requireTransaction();
        var scopes = new LinkedHashSet<String>();
        for (KnowledgeChange change : changes) {
            long sequence = at.sequence(change.sourceEvent());
            switch (change) {
                case KnowledgeChange.Claim claim -> claim(jdbc, campaign, at, sequence, claim);
                case KnowledgeChange.Determination determination -> {
                    if (jdbc.update("update knowledge_claim set truth = ?, truth_revision = ?, truth_event_sequence = ?, "
                            + "row_version = row_version + 1 where campaign_id = ? and id = ? and row_version = ? "
                            + "and truth = 'UNDETERMINED' and superseded_revision is null",
                            determination.truth().name(), at.revision(), sequence, campaign, determination.claimId(),
                            determination.expectedRowVersion()) != 1) {
                        throw new IllegalStateException("stale, determined or superseded claim");
                    }
                }
                case KnowledgeChange.Belief belief -> {
                    belief(jdbc, campaign, at, sequence, belief);
                    scopes.add(knowledgeScope(belief.holderId()));
                }
                case KnowledgeChange.Sighting sighting -> {
                    if (sighting.informantId() == null && !sighting.locationId().equals(locationOf(jdbc, campaign,
                            sighting.subjectId()))) {
                        throw new IllegalStateException("an observed sighting must match the subject's real location");
                    }
                    requireActor(jdbc, campaign, sighting.subjectId());
                    sight(jdbc, campaign, sighting.observerId(), sighting.subjectId(), sighting.locationId(),
                            sighting.informantId(), at, sequence);
                    scopes.add(sightingScope(sighting.observerId()));
                }
                case KnowledgeChange.Commitment commitment -> {
                    if (commitment.subjectRef() != null) {
                        requireRow(jdbc, campaign, commitment.subjectRef());
                    }
                    jdbc.update("insert into actor_commitment (id, campaign_id, kind, debtor_actor_id, creditor_actor_id, "
                            + "subject_ref, terms, terms_locale, quoted, disclosure, due_world_time, status, "
                            + "made_revision, made_world_time, made_event_sequence) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)",
                            commitment.commitmentId(), campaign, commitment.kind().name(), commitment.debtorId(),
                            commitment.creditorId(), commitment.subjectRef(), commitment.terms(),
                            commitment.termsLocale(), commitment.quoted(), commitment.disclosure().name(),
                            commitment.dueWorldTime(), at.revision(), at.worldTime(), sequence);
                    scopes.add(commitmentScope(commitment.debtorId()));
                    scopes.add(commitmentScope(commitment.creditorId()));
                }
                case KnowledgeChange.Resolution resolution -> {
                    var parties = jdbc.query("update actor_commitment set status = ?, resolved_revision = ?, "
                            + "resolved_world_time = ?, resolved_event_sequence = ?, row_version = row_version + 1 "
                            + "where campaign_id = ? and id = ? and row_version = ? and status = 'OPEN' "
                            + "returning debtor_actor_id, creditor_actor_id",
                            (row, index) -> List.of(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
                            resolution.status().name(), at.revision(), at.worldTime(), sequence, campaign,
                            resolution.commitmentId(), resolution.expectedRowVersion());
                    if (parties.size() != 1) {
                        throw new IllegalStateException("stale or already resolved commitment");
                    }
                    parties.getFirst().forEach(party -> scopes.add(commitmentScope(party)));
                }
            }
            if (change instanceof KnowledgeChange.Claim || change instanceof KnowledgeChange.Determination) {
                scopes.add(CLAIMS_SCOPE);
            }
        }
        return scopes;
    }

    /**
     * Record what an observer perceives at its current location after a committed operation: an
     * OBSERVED sighting of every perceived actor there, and the absence of every actor the observer
     * last placed here but no longer perceives. Perception itself (co-presence, concealment and
     * reveals) is decided by the caller's rules; this method trusts the list and only writes.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked
     * @param observer actor who perceives
     * @param location observer's current location, already updated by this commit
     * @param perceived distinct other actors the observer perceives there, at most {@link #MAX_OBSERVED}
     * @param at provenance; the operation's first event is the observation's source
     * @return the observer's sighting scope when anything was written, otherwise empty
     */
    public static Set<String> observeWithin(JdbcTemplate jdbc, UUID campaign, UUID observer, String location,
            List<UUID> perceived, Provenance at) {
        Objects.requireNonNull(observer, "observer");
        KnowledgeRefs.location(location);
        requireTransaction();
        if (perceived.size() > MAX_OBSERVED || new HashSet<>(perceived).size() != perceived.size()
                || perceived.contains(observer)) {
            throw new IllegalArgumentException("perceived actors must be distinct others within the bound");
        }
        long sequence = at.sequence(0);
        boolean written = false;
        for (UUID subject : perceived) {
            sight(jdbc, campaign, observer, subject, location, null, at, sequence);
            written = true;
        }
        // Earlier sightings at this place whose subject is not perceived now: the observer has
        // looked and the subject is not (visibly) here. The sighting itself is kept.
        var seen = new HashSet<>(perceived);
        for (UUID subject : jdbc.queryForList("select subject_actor_id from actor_sighting where campaign_id = ? "
                + "and observer_actor_id = ? and location_id = ? and absence_revision is null and observed_revision < ? "
                + "order by subject_actor_id", UUID.class, campaign, observer, location, at.revision())) {
            if (!seen.contains(subject)) {
                jdbc.update("update actor_sighting set absence_revision = ?, absence_world_time = ?, "
                        + "absence_event_sequence = ?, row_version = row_version + 1 where campaign_id = ? "
                        + "and observer_actor_id = ? and subject_actor_id = ?", at.revision(), at.worldTime(), sequence,
                        campaign, observer, subject);
                written = true;
            }
        }
        return written ? Set.of(sightingScope(observer)) : Set.of();
    }

    /**
     * Whether a concealed subject has been revealed to a holder: a current, TRUE {@code EXISTS}
     * claim about it is common knowledge, or the holder currently KNOWS or BELIEVES it. Rumors,
     * suspicions, doubts, false or undetermined claims and superseded records never reveal anything,
     * so a reveal is always grounded in canonical truth.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign scope
     * @param holder audience actor
     * @param subjectRef typed reference of the concealed subject
     * @return true when the holder may treat the subject as disclosed
     */
    public static boolean revealedTo(JdbcTemplate jdbc, UUID campaign, UUID holder, String subjectRef) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(holder, "holder");
        KnowledgeRefs.require(subjectRef, "subjectRef");
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from knowledge_claim c "
                + "where c.campaign_id = ? and c.subject_ref = ? and c.predicate = 'EXISTS' and c.truth = 'TRUE' "
                + "and c.superseded_revision is null and (c.disclosure = 'COMMON' or exists(select 1 "
                + "from knowledge_belief b where b.campaign_id = c.campaign_id and b.claim_id = c.id "
                + "and b.holder_actor_id = ? and b.superseded_revision is null and b.stance in ('KNOWS', 'BELIEVES'))))",
                Boolean.class, campaign, subjectRef, holder));
    }

    /** Close an optional predecessor about the same subject, then insert the claim. */
    private static void claim(JdbcTemplate jdbc, UUID campaign, Provenance at, long sequence,
            KnowledgeChange.Claim claim) {
        requireRow(jdbc, campaign, claim.subjectRef());
        if (claim.objectRef() != null) {
            requireRow(jdbc, campaign, claim.objectRef());
        }
        UUID predecessor = null;
        if (claim.supersedes() != null) {
            predecessor = claim.supersedes().predecessorId();
            if (jdbc.update("update knowledge_claim set superseded_revision = ?, row_version = row_version + 1 "
                    + "where campaign_id = ? and id = ? and row_version = ? and superseded_revision is null "
                    + "and subject_ref = ?", at.revision(), campaign, predecessor,
                    claim.supersedes().expectedRowVersion(), claim.subjectRef()) != 1) {
                throw new IllegalStateException("stale, superseded or unrelated predecessor claim");
            }
        }
        jdbc.update("insert into knowledge_claim (id, campaign_id, subject_ref, predicate, object_ref, qualifier, "
                + "property_value, statement, statement_locale, disclosure, truth, established_revision, "
                + "established_world_time, established_event_sequence, truth_revision, truth_event_sequence, supersedes) "
                + "values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", claim.claimId(), campaign,
                claim.subjectRef(), claim.predicate().name(), claim.objectRef(), claim.qualifier(), claim.valueJson(),
                claim.statement(), claim.statementLocale(), claim.disclosure().name(), claim.truth().name(),
                at.revision(), at.worldTime(), sequence, at.revision(), sequence, predecessor);
    }

    /** Close an optional predecessor of the same holder, refuse a second current belief, then insert. */
    private static void belief(JdbcTemplate jdbc, UUID campaign, Provenance at, long sequence,
            KnowledgeChange.Belief belief) {
        if (belief.sourceRef() != null) {
            requireRow(jdbc, campaign, belief.sourceRef());
        }
        UUID predecessor = null;
        if (belief.supersedes() != null) {
            predecessor = belief.supersedes().predecessorId();
            if (jdbc.update("update knowledge_belief set superseded_revision = ?, row_version = row_version + 1 "
                    + "where campaign_id = ? and id = ? and row_version = ? and holder_actor_id = ? "
                    + "and superseded_revision is null", at.revision(), campaign, predecessor,
                    belief.supersedes().expectedRowVersion(), belief.holderId()) != 1) {
                throw new IllegalStateException("stale, superseded or foreign predecessor belief");
            }
        }
        // The unique index would refuse it too; failing here keeps the cause readable.
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from knowledge_belief where campaign_id = ? "
                + "and holder_actor_id = ? and claim_id = ? and superseded_revision is null)", Boolean.class, campaign,
                belief.holderId(), belief.claimId()))) {
            throw new IllegalStateException("holder already has a current belief about this claim");
        }
        jdbc.update("insert into knowledge_belief (id, campaign_id, holder_actor_id, claim_id, stance, acquisition, "
                + "informant_actor_id, source_ref, learned_revision, learned_world_time, learned_event_sequence, "
                + "supersedes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", belief.beliefId(), campaign,
                belief.holderId(), belief.claimId(), belief.stance().name(), belief.acquisition().name(),
                belief.informantId(), belief.sourceRef(), at.revision(), at.worldTime(), sequence, predecessor);
    }

    /** Insert or refresh one sighting; a newer sighting replaces the place and clears any absence. */
    private static void sight(JdbcTemplate jdbc, UUID campaign, UUID observer, UUID subject, String location,
            UUID informant, Provenance at, long sequence) {
        jdbc.update("insert into actor_sighting (campaign_id, observer_actor_id, subject_actor_id, location_id, "
                + "acquisition, informant_actor_id, observed_revision, observed_world_time, observed_event_sequence) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (campaign_id, observer_actor_id, subject_actor_id) "
                + "do update set location_id = excluded.location_id, acquisition = excluded.acquisition, "
                + "informant_actor_id = excluded.informant_actor_id, observed_revision = excluded.observed_revision, "
                + "observed_world_time = excluded.observed_world_time, "
                + "observed_event_sequence = excluded.observed_event_sequence, absence_revision = null, "
                + "absence_world_time = null, absence_event_sequence = null, "
                + "row_version = actor_sighting.row_version + 1", campaign, observer, subject, location,
                informant == null ? "OBSERVED" : "TOLD", informant, at.revision(), at.worldTime(), sequence);
    }

    /** Current canonical location of a campaign actor; null when it has none. */
    private static String locationOf(JdbcTemplate jdbc, UUID campaign, UUID actor) {
        var rows = jdbc.query("select location_id from actor where campaign_id = ? and id = ?",
                (row, index) -> java.util.Optional.ofNullable(row.getString(1)), campaign, actor);
        if (rows.isEmpty()) {
            throw new IllegalStateException("unknown campaign reference");
        }
        return rows.getFirst().orElse(null);
    }

    /** Require an actor of this campaign. */
    private static void requireActor(JdbcTemplate jdbc, UUID campaign, UUID actor) {
        requireRow(jdbc, campaign, KnowledgeRefs.actor(actor));
    }

    /**
     * Require that a campaign-row reference names a row of this campaign; content references are
     * accepted by form (see {@link KnowledgeRefs}). A foreign or missing row reveals nothing.
     */
    private static void requireRow(JdbcTemplate jdbc, UUID campaign, String reference) {
        UUID row = KnowledgeRefs.row(reference);
        if (row == null) {
            return;
        }
        String table = reference.startsWith("actor:") ? "actor" : "item_instance";
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from " + table
                + " where campaign_id = ? and id = ?)", Boolean.class, campaign, row))) {
            throw new IllegalStateException("unknown campaign reference");
        }
    }

    /** Refuse to run outside a transaction: the writes must share the caller's commit or rollback. */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge writes require the caller's transaction");
        }
    }
}
