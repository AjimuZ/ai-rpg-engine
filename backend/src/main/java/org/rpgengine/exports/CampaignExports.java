package org.rpgengine.exports;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.panels.PlayPanels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Export one owned campaign as a JSON document for an explicitly chosen {@link Audience} (P16.2-A; docs/13, the
 * {@code /exports} row of the API table; docs/14 section 6).
 *
 * <p><b>Full owner.</b> Every row of every base table that has a {@code campaign_id} column, plus the campaign row
 * itself, read in one read-only repeatable-read snapshot and keyed by table name. The table list comes from the
 * database catalog at export time, so a table added by a later migration is exported without a code change. This
 * export deliberately contains the campaign's secrets: hidden quests and events, the private random seed, other
 * actors' knowledge, planning artifacts, job and runtime-session records. It is for the owner's own keeping and a
 * later restore (P16.4), not for sharing.
 *
 * <p><b>Player.</b> Built only from {@link PlayPanels}, page by page through the whole public journal, and accepted
 * only when every page reports the same committed state revision. It holds exactly what the player panels show:
 * no internal event, hidden or secret definition text, other holder's belief, seed, idempotency key, job, plan or
 * session record.
 *
 * <p><b>Both.</b> Account data (the owner profile, the owner's identity, other campaigns and adventures) and
 * installation-wide tables (model slots, scheduler state, resource leases) are never exported. Both documents name
 * the pinned content revision (pack, version, content hash, ruleset) so a recipient can check compatibility, and
 * neither embeds pack files. Reading writes no row, draws no random number and starts no job; exporting the same
 * revision twice yields identical bytes and digest. The SHA-256 digest is returned beside the document rather
 * than inside it, because a document cannot contain its own hash.
 */
@Service
public class CampaignExports {
    /** Identifies the document type; a reader refuses any other value. */
    public static final String FORMAT = "engine.campaign-export";
    /** Version of the document layout; a change of layout increments it. */
    public static final int FORMAT_VERSION = 1;
    /** Most rows a full-owner export copies; one more fails the export instead of truncating it. */
    static final int MAX_OWNER_ROWS = 200_000;
    /** Most characters of row JSON a full-owner export holds before it fails. */
    static final long MAX_OWNER_CHARS = 64L * 1024 * 1024;
    /** Most journal entries a player export holds before it fails. */
    static final int MAX_JOURNAL_ENTRIES = 100_000;
    /** Most journal pages a player export reads, which bounds pages that hold no statable event. */
    static final int MAX_JOURNAL_PAGES = 5_000;
    /** Whole player reads attempted before a campaign that keeps changing fails the export. */
    static final int PLAYER_ATTEMPTS = 3;
    private static final Pattern TABLE = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final String CONTENT_SQL = "select jsonb_build_object('revisionId', r.id, 'packId', r.pack_id, "
            + "'packVersion', r.pack_version, 'contentHash', r.content_hash, 'rulesetId', r.ruleset_id, "
            + "'rulesetVersion', r.ruleset_version, 'rulesetHash', r.ruleset_hash, 'sourceLocale', r.source_locale)::text "
            + "from campaign c join adventure_workspace w on w.id = c.adventure_id join content_revision r on r.id = "
            + "c.content_revision_id and r.adventure_id = c.adventure_id where c.id = ? and c.adventure_id = ? and "
            + "w.owner_id = ?";

    private final JdbcTemplate jdbc;
    private final PlayPanels panels;
    private final TransactionTemplate snapshot;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param jdbc storage
     * @param manager transaction manager for the full-owner snapshot
     * @param panels the player projection, the only source of a player export
     */
    public CampaignExports(JdbcTemplate jdbc, PlatformTransactionManager manager, PlayPanels panels) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.panels = Objects.requireNonNull(panels, "panels");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(120);
    }

    /**
     * Export one owned campaign for one audience.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign identity
     * @param audience who the document is for; there is no default
     * @return the document, its digest and the state revision it describes
     * @throws ScopeUnavailable for a missing, foreign or mismatched scope, before any campaign row is read
     * @throws IllegalStateException inside an ambient transaction, beyond a size bound, or when a player export keeps
     *     meeting new commits between its journal pages
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public Export export(UUID owner, UUID adventure, UUID campaign, Audience audience) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(audience, "audience");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("exports require an independent snapshot");
        }
        ObjectNode document = audience == Audience.FULL_OWNER ? owner(owner, adventure, campaign)
                : player(owner, adventure, campaign);
        byte[] bytes = json.writeValueAsBytes(document);
        return new Export(audience, campaign, document.at("/campaign/stateRevision").asLong(), sha256(bytes), bytes);
    }

    /** Copy every campaign-scoped row inside one snapshot; authorization runs first, inside the same snapshot. */
    private ObjectNode owner(UUID owner, UUID adventure, UUID campaign) {
        return Objects.requireNonNull(snapshot.execute(status -> {
            jdbc.execute("set local statement_timeout = '60s'");
            JsonNode content = content(owner, adventure, campaign);
            JsonNode row = json.readTree(jdbc.queryForObject("select to_jsonb(c)::text from campaign c where c.id = ?",
                    String.class, campaign));
            ObjectNode document = header(Audience.FULL_OWNER, adventure, campaign, content,
                    row.get("state_revision").asLong());
            document.put("schemaVersion", jdbc.query("select version from flyway_schema_history where success and "
                    + "version is not null order by installed_rank desc limit 1", (rs, n) -> rs.getString(1))
                    .stream().findFirst().orElse(null));
            ObjectNode tables = document.putObject("tables");
            tables.putArray("campaign").add(row);
            var budget = new long[] {1, 0};
            for (String table : jdbc.queryForList("select c.table_name::text from information_schema.columns c join "
                    + "information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name "
                    + "where c.table_schema = current_schema() and c.column_name = 'campaign_id' and t.table_type = "
                    + "'BASE TABLE' order by 1", String.class)) {
                if (!TABLE.matcher(table).matches()) {
                    throw new IllegalStateException("unexpected table name");
                }
                ArrayNode rows = tables.putArray(table);
                // The name comes from the catalog and matched a plain identifier, so quoting it is safe.
                jdbc.query("select to_jsonb(t)::text from \"" + table + "\" t where t.campaign_id = ? order by 1",
                        (RowCallbackHandler) rs -> {
                            String text = rs.getString(1);
                            budget[0]++;
                            budget[1] += text.length();
                            if (budget[0] > MAX_OWNER_ROWS || budget[1] > MAX_OWNER_CHARS) {
                                throw new IllegalStateException("export limit exceeded");
                            }
                            rows.add(json.readTree(text));
                        }, campaign);
            }
            return document;
        }));
    }

    /**
     * Read the player panels and every journal page. A commit between two pages changes the reported revision, and
     * the whole read starts again, so the entries always belong to the revision of the panels they accompany.
     */
    private ObjectNode player(UUID owner, UUID adventure, UUID campaign) {
        JsonNode content = content(owner, adventure, campaign);
        try {
            for (int attempt = 0; attempt < PLAYER_ATTEMPTS; attempt++) {
                PlayPanels.Panels first = panels.read(owner, adventure, campaign, null, PlayPanels.MAX_EVENT_PAGE);
                var entries = new ArrayList<PlayPanels.Entry>(first.journal().entries());
                Long next = first.journal().nextBefore();
                boolean stable = true;
                for (int pages = 1; next != null && stable; pages++) {
                    if (pages >= MAX_JOURNAL_PAGES) {
                        throw new IllegalStateException("export limit exceeded");
                    }
                    PlayPanels.Panels page = panels.read(owner, adventure, campaign, next, PlayPanels.MAX_EVENT_PAGE);
                    stable = page.scope().equals(first.scope());
                    entries.addAll(page.journal().entries());
                    if (entries.size() > MAX_JOURNAL_ENTRIES) {
                        throw new IllegalStateException("export limit exceeded");
                    }
                    next = page.journal().nextBefore();
                }
                if (stable) {
                    return playerDocument(adventure, campaign, content, first, entries);
                }
            }
        } catch (PlayPanels.ScopeUnavailable unavailable) {
            throw new ScopeUnavailable();
        }
        throw new IllegalStateException("campaign changed during export");
    }

    /** Lay out the player document: the panels without their single journal page, then the whole journal. */
    private ObjectNode playerDocument(UUID adventure, UUID campaign, JsonNode content, PlayPanels.Panels panels,
            List<PlayPanels.Entry> entries) {
        ObjectNode document = header(Audience.PLAYER, adventure, campaign, content, panels.scope().stateRevision());
        ObjectNode view = json.valueToTree(panels);
        view.remove("scope");
        view.remove("journal");
        document.set("panels", view);
        document.set("journal", json.valueToTree(List.copyOf(entries)));
        return document;
    }

    /** Fields every export starts with; the format, version and audience let a reader refuse a document early. */
    private ObjectNode header(Audience audience, UUID adventure, UUID campaign, JsonNode content, long revision) {
        ObjectNode document = json.createObjectNode();
        document.put("format", FORMAT);
        document.put("formatVersion", FORMAT_VERSION);
        document.put("audience", audience.wireName());
        ObjectNode scope = document.putObject("campaign");
        scope.put("adventureId", adventure.toString());
        scope.put("campaignId", campaign.toString());
        scope.put("stateRevision", revision);
        document.set("content", content);
        return document;
    }

    /** Authorize the owner, adventure and campaign together and return the pinned content identity. */
    private JsonNode content(UUID owner, UUID adventure, UUID campaign) {
        List<String> rows = jdbc.queryForList(CONTENT_SQL, String.class, campaign, adventure, owner);
        if (rows.isEmpty()) {
            throw new ScopeUnavailable();
        }
        return json.readTree(rows.getFirst());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Who an export is for; every caller names one, and the two never share a data source. */
    public enum Audience {
        /** The owner's complete copy of the campaign, secrets included. */
        FULL_OWNER("full-owner"),
        /** What the player panels and public journal show, and nothing else. */
        PLAYER("player");

        private final String wireName;

        Audience(String wireName) {
            this.wireName = wireName;
        }

        /** @return the name written in the document's {@code audience} field */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * One finished export. The document is UTF-8 JSON; {@link #sha256()} is the lowercase hexadecimal SHA-256 of
     * exactly those bytes. The byte array is copied in and out, so an export cannot be changed after it is made.
     */
    public static final class Export {
        private final Audience audience;
        private final UUID campaignId;
        private final long stateRevision;
        private final String sha256;
        private final byte[] document;

        Export(Audience audience, UUID campaignId, long stateRevision, String sha256, byte[] document) {
            this.audience = audience;
            this.campaignId = campaignId;
            this.stateRevision = stateRevision;
            this.sha256 = sha256;
            this.document = document.clone();
        }

        /** @return the audience the document was made for */
        public Audience audience() {
            return audience;
        }

        /** @return the exported campaign */
        public UUID campaignId() {
            return campaignId;
        }

        /** @return the committed state revision the whole document describes */
        public long stateRevision() {
            return stateRevision;
        }

        /** @return lowercase hexadecimal SHA-256 of {@link #document()} */
        public String sha256() {
            return sha256;
        }

        /** @return a copy of the UTF-8 JSON document */
        public byte[] document() {
            return document.clone();
        }

        /** @return the document decoded as UTF-8 */
        public String text() {
            return new String(document, StandardCharsets.UTF_8);
        }
    }

    /** Uniform missing, foreign or mismatched scope signal; retains no identities or diagnostics. */
    public static final class ScopeUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Construct a fixed failure without scope input. */
        public ScopeUnavailable() {
            super("campaign scope unavailable");
        }
    }
}
