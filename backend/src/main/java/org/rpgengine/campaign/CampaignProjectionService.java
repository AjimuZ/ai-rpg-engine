package org.rpgengine.campaign;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assemble detached player-only views from a committed owner/adventure/campaign scope.
 * All statements share a read-only repeatable-read snapshot, so section data and revision
 * tokens cannot straddle a concurrent commit. No events, jobs, RNG or timestamps are written.
 * Inaccessible scopes share one failure; SQL/content failures propagate for sanitization.
 */
@Service
public class CampaignProjectionService {
    private static final int MAX_ROWS = 1000;
    private static final String SUMMARY_SQL = "select c.id, c.adventure_id, c.content_revision_id, c.title, c.mode, "
            + "c.state_revision, c.game_turn, c.world_time_seconds, c.event_sequence, c.updated_at, "
            + "c.pending_decision is not null as decision_available from campaign c "
            + "join adventure_workspace a on a.id=c.adventure_id ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Require persistence infrastructure; configure independent consistent snapshots or fail construction. */
    public CampaignProjectionService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(15);
    }

    /**
     * List only this trusted owner's campaigns, optionally in one owned adventure, ordered
     * by UUID with an exclusive cursor. Returns at most limit rows and a continuation cursor;
     * invalid limits fail before SQL. Empty/foreign adventures disclose no campaign identities.
     */
    public Listing list(UUID owner, UUID adventure, UUID after, int limit) {
        Objects.requireNonNull(owner, "owner");
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("invalid page size"); }
        independent();
        return transaction.execute(status -> {
            var rows = jdbc.query(SUMMARY_SQL + "where a.owner_id=? "
                    + "and (?::uuid is null or c.adventure_id=?) and (?::uuid is null or c.id>?) "
                    + "order by c.id limit ?", CampaignProjectionService::summary,
                    owner, adventure, adventure, after, after, limit + 1);
            boolean more = rows.size() > limit;
            var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new Listing(page, more ? page.getLast().campaignId() : null);
        });
    }

    /**
     * Continue/inspect one campaign at its committed revision. Authorize the exact triple
     * before any child lookup, then select only the creation-bound player, owned inventory,
     * learned abilities and explicitly audience-bound knowledge. Missing legacy characters
     * are represented by null, never an invented actor or empty replacement character.
     */
    public State read(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        independent();
        return transaction.execute(status -> {
            var rows = jdbc.query(SUMMARY_SQL + "where a.owner_id=? and c.adventure_id=? and c.id=?",
                    CampaignProjectionService::summary, owner, adventure, campaign);
            if (rows.isEmpty()) { throw new ScopeUnavailable(); }
            Summary summary = rows.getFirst();
            Scope scope = new Scope(adventure, campaign, summary.contentRevisionId(), summary.stateRevision());
            var players = jdbc.query("select a.id, a.display_name, a.location_id, s.attributes::text, s.skills::text, "
                    + "s.background, s.role from campaign_creation cc "
                    + "join actor a on a.campaign_id=cc.campaign_id and a.id=cc.actor_id "
                    + "join character_state s on s.campaign_id=a.campaign_id and s.actor_id=a.id "
                    + "where cc.adventure_id=? and cc.campaign_id=?",
                    (rs, n) -> new Player(rs.getObject("id", UUID.class), rs.getString("display_name"),
                            rs.getString("location_id"), numbers(rs.getString("attributes")),
                            numbers(rs.getString("skills")), rs.getString("background"), rs.getString("role")),
                    adventure, campaign);
            if (players.isEmpty()) {
                return new State(scope, summary, null, List.of(), List.of(), List.of(), List.of(), List.of());
            }
            Player player = players.getFirst();
            UUID actor = player.actorId();
            var resources = bounded(jdbc.query("select resource_key, minimum_value, current_value, maximum_value "
                    + "from actor_resource where campaign_id=? and actor_id=? order by resource_key limit 1001",
                    (rs, n) -> new Resource(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), campaign, actor));
            var items = bounded(jdbc.query("select id, definition_id, quantity from item_instance "
                    + "where campaign_id=? and actor_id=? order by id limit 1001",
                    (rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)), campaign, actor));
            var abilities = bounded(jdbc.query("select definition_id, rank from learned_ability "
                    + "where campaign_id=? and actor_id=? order by definition_id limit 1001",
                    (rs, n) -> new Ability(rs.getString(1), rs.getInt(2)), campaign, actor));
            // Authoring definitions, hidden NPCs and raw event payloads are never knowledge sources.
            var facts = bounded(jdbc.query("select id, fact_text, confidence from campaign_known_fact "
                    + "where campaign_id=? and actor_id=? and learned_revision<=? order by id limit 1001",
                    (rs, n) -> new KnownFact(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                    campaign, actor, scope.stateRevision()));
            var quests = bounded(jdbc.query("select id, title, objective, status from campaign_known_quest "
                    + "where campaign_id=? and actor_id=? and updated_revision<=? order by id limit 1001",
                    (rs, n) -> new Quest(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
                    campaign, actor, scope.stateRevision()));
            return new State(scope, summary, player, resources, items, abilities, facts, quests);
        });
    }

    /** Reject ambient transactions: callers cannot downgrade snapshot isolation or mix reads with writes. */
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("projection requires an independent transaction");
        }
    }

    /** Decode bounded canonical integer allocations into a detached map; malformed stored content fails closed. */
    private Map<String, Integer> numbers(String value) {
        return Collections.unmodifiableMap(json.readValue(value, new TypeReference<TreeMap<String, Integer>>() { }));
    }

    /** Bound memory and fail rather than present a silently incomplete collection as an authoritative view. */
    private static <T> List<T> bounded(List<T> rows) {
        if (rows.size() > MAX_ROWS) { throw new IllegalStateException("projection limit exceeded"); }
        return List.copyOf(rows);
    }

    /** Map only the public campaign allowlist; SQL conversion failures propagate without returning partial data. */
    private static Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getObject("adventure_id", UUID.class),
                rs.getObject("content_revision_id", UUID.class), rs.getString("title"), rs.getString("mode"),
                rs.getLong("state_revision"), rs.getLong("game_turn"), rs.getLong("world_time_seconds"),
                rs.getLong("event_sequence"), rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("decision_available"));
    }

    /** Public identity/revision tokens; clients must discard replies for a previously selected scope. */
    public record Scope(UUID adventureId, UUID campaignId, UUID contentRevisionId, long stateRevision) { }
    /** Public save metadata only; no seed identity, raw decision, job, lease or recovery internals. */
    public record Summary(UUID campaignId, UUID adventureId, UUID contentRevisionId, String title, String mode,
            long stateRevision, long gameTurn, long worldTimeSeconds, long eventSequence, Instant updatedAt,
            boolean pendingDecisionAvailable) { }
    /** Bounded owner page; null nextAfter denotes the end of this snapshot's listing. */
    public record Listing(List<Summary> campaigns, UUID nextAfter) { }
    /** The creation-bound player; location is that player's current location, never an NPC's hidden position. */
    public record Player(UUID actorId, String displayName, String locationId, Map<String, Integer> attributes,
            Map<String, Integer> skills, String background, String role) { }
    /** Exact owned resource values and declared inclusive bounds, with no derived estimate. */
    public record Resource(String key, long minimum, long current, long maximum) { }
    /** Owned stack identity, pinned definition reference and exact quantity; no secret definition body. */
    public record Item(UUID itemId, String definitionId, int quantity) { }
    /** Learned ability/rank only; this is not a claim of current action legality or affordability. */
    public record Ability(String definitionId, int rank) { }
    /** Explicit audience knowledge, retaining rumor/belief classification rather than asserting world truth. */
    public record KnownFact(UUID factId, String text, String confidence) { }
    /** Known quest objective/status only; hidden branches, conditions and reward definitions are excluded. */
    public record Quest(UUID questId, String title, String objective, String status) { }
    /** Complete detached snapshot at one revision; empty knowledge lists mean no revealed records. */
    public record State(Scope scope, Summary campaign, Player player, List<Resource> resources,
            List<Item> inventory, List<Ability> abilities, List<KnownFact> knownFacts, List<Quest> quests) { }
    /** Section response retains scope tokens for independent panel/reconnect requests. */
    public record Section<T>(Scope scope, T data) { }
    /** Uniform missing/foreign/mismatched scope signal; never retains private identities or diagnostics. */
    public static final class ScopeUnavailable extends RuntimeException {
        /** Construct a fixed failure without scope input or external effects. */
        public ScopeUnavailable() { super("campaign scope unavailable"); }
    }
}
