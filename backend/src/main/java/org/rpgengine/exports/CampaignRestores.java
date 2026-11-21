package org.rpgengine.exports;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.rpgengine.content.PackMedia;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Restore one full-owner {@link CampaignExports} document into an explicitly named, owned adventure as a new campaign
 * (P16.4-A, QA-23; docs/14 section 6).
 *
 * <p><b>Destination.</b> The caller names the destination adventure; there is no default and no lookup by title. The
 * restore always creates a new campaign and never writes into, replaces or merges with a stored one, whatever titles
 * the destination holds. The pinned content is matched only by its coordinates and hashes: the destination must hold
 * a sealed revision of the export's pack and version with the same content hash and ruleset, which is then the restored
 * campaign's revision. Its sealed files, original artwork included, are therefore byte-identical to the source's.
 * Exports embed no pack files, so a missing revision is refused ({@link Code#CONTENT_UNAVAILABLE}); an adventure bundle
 * ({@link AdventureBundles}) brings it first.
 *
 * <p><b>Identities.</b> With {@link Identities#FRESH}, the default every caller states, every UUID found in a UUID
 * column of the exported rows gets a new random identity, except the source adventure and content revision, which
 * become the destination's. The new identities keep the source identities' order, so every serialization ordered by
 * identity (checkpoint snapshots, ordered lists) keeps its order. Each occurrence of a mapped identity is replaced in
 * every text and JSON value as well, which keeps typed references such as {@code actor:<uuid>}, plan scopes and
 * summary sources consistent. {@link Identities#ORIGINAL} keeps the source identities, for a clean installation; any
 * collision with a stored row refuses with {@link Code#IDENTITY_IN_USE}. Summary-invalidation numbers are an
 * installation-wide counter, so they are always renumbered in order, and each summary's high-water mark follows.
 *
 * <p><b>What is restored.</b> Every exported row of every campaign-scoped table: the world, the active encounter
 * with its participants, the journal with operations still owing narration and their presentation jobs, draws and
 * the random seed, knowledge, memory, plans, sessions and the mutation lease exactly as exported. A lease or job held
 * by the source process names a holder no running process has, so it is recovered by the usual expiry paths.
 * {@code inference_task} rows are not restored: they are the source scheduler's work and quota charges, not campaign
 * state. A checkpoint whose snapshot matched its hash in the export gets the hash of its re-keyed snapshot; a
 * checkpoint that did not match keeps its stored hash and stays detectably corrupt. Other recorded digests (payload,
 * context, fingerprint and result hashes) are evidence and keep their source values. The document's {@code assets}
 * section (P17.1-C) puts the registered image and audio bytes back into the content-addressed store under their own
 * SHA-256, which no identity mapping changes, so a restored association shows the same bytes; a document without the
 * section restores its associations and no bytes.
 *
 * <p><b>How it fails.</b> Every refusal is checked before or inside one transaction that writes nothing on failure:
 * size, digest, format, schema version and table set, destination scope, content, references and collisions. Rows
 * are inserted with ordinary triggers suspended ({@code session_replication_role = replica}, transaction-local),
 * because the triggers enforce live transitions and append stream messages that the export already holds; check and
 * unique constraints still apply, and every foreign key of the restored rows is verified before commit. This needs a
 * database role allowed to set that parameter, which the packaged role (the database owner) is.
 */
@Service
public class CampaignRestores {
    /** Largest document accepted; above every document {@link CampaignExports} can produce. */
    public static final int MAX_DOCUMENT_BYTES = 96 * 1024 * 1024;
    /** Exported tables deliberately not restored: scheduler work and charges of the source process. */
    public static final Set<String> NOT_RESTORED = Set.of("inference_task");
    /** Most distinct asset byte entries a document may carry. */
    private static final int MAX_ASSETS = 5_000;
    private static final String INVALIDATIONS = "memory_invalidation";
    private static final String SUMMARIES = "memory_summary";
    private static final String CHECKPOINTS = "campaign_checkpoint";
    /** The only identity column a restored table may have; any other fails closed as an unknown schema. */
    private static final String KNOWN_IDENTITY = INVALIDATIONS + ".sequence";
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String SQL_HASH = "encode(sha256(convert_to(cast(? as jsonb)::text, 'UTF8')), 'hex')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    // Exact decimals: a restored numeric value must not pass through a binary floating-point number.
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /**
     * @param jdbc storage
     * @param manager transaction manager of the single restore transaction
     */
    public CampaignRestores(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(180);
    }

    /**
     * Restore a full-owner export as a new campaign of the destination adventure.
     *
     * @param owner locally admitted owner, who must own the destination
     * @param destination adventure that receives the campaign; never inferred
     * @param document the export's exact bytes
     * @param sha256 the lowercase SHA-256 the export reported beside the document
     * @param identities whether the campaign gets new identities; there is no default
     * @return the restored campaign, its revision and the identity map
     * @throws Refused with a fixed code; nothing was written
     * @throws IllegalStateException inside an ambient transaction, or when restored rows would break a reference
     * @throws org.springframework.dao.DataAccessException for storage failures, including a role that may not
     *     suspend triggers; nothing was written
     */
    public Restored restore(UUID owner, UUID destination, byte[] document, String sha256, Identities identities) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(identities, "identities");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a restore requires its own transaction");
        }
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw new Refused(Code.LIMIT);
        }
        if (!DIGEST.matcher(sha256).matches() || !sha256(document).equals(sha256)) {
            throw new Refused(Code.INTEGRITY);
        }
        Source source = parse(document);
        try {
            return Objects.requireNonNull(transaction.execute(status -> write(owner, destination, source, identities)));
        } catch (DuplicateKeyException collision) {
            throw new Refused(Code.IDENTITY_IN_USE);
        }
    }

    /** Check the document's own consistency; the database is not read yet. */
    private Source parse(byte[] document) {
        JsonNode root;
        try {
            root = json.readTree(document);
        } catch (JacksonException malformed) {
            throw new Refused(Code.FORMAT);
        }
        if (root == null || !root.isObject() || !CampaignExports.FORMAT.equals(root.path("format").asString(null))
                || root.path("formatVersion").asInt(-1) != CampaignExports.FORMAT_VERSION
                || !CampaignExports.Audience.FULL_OWNER.wireName().equals(root.path("audience").asString(null))
                || !root.path("tables").isObject() || !root.path("content").isObject()
                || !root.path("schemaVersion").isString()) {
            throw new Refused(Code.FORMAT);
        }
        JsonNode campaigns = root.path("tables").path("campaign");
        if (!campaigns.isArray() || campaigns.size() != 1 || !campaigns.get(0).isObject()) {
            throw new Refused(Code.FORMAT);
        }
        JsonNode campaign = campaigns.get(0);
        String campaignId = campaign.path("id").asString("");
        String adventureId = campaign.path("adventure_id").asString("");
        String revisionId = campaign.path("content_revision_id").asString("");
        JsonNode content = root.get("content");
        if (!campaignId.equals(root.at("/campaign/campaignId").asString(null))
                || !adventureId.equals(root.at("/campaign/adventureId").asString(null))
                || !revisionId.equals(content.path("revisionId").asString(null))
                || campaign.path("state_revision").asLong(-1) != root.at("/campaign/stateRevision").asLong(-2)
                || !isUuid(campaignId) || !isUuid(adventureId) || !isUuid(revisionId)) {
            throw new Refused(Code.FORMAT);
        }
        for (var table : root.get("tables").properties()) {
            if (!NAME.matcher(table.getKey()).matches() || !table.getValue().isArray()) {
                throw new Refused(Code.FORMAT);
            }
            if (table.getKey().equals("campaign")) {
                continue;
            }
            for (JsonNode row : table.getValue()) {
                if (!row.isObject() || !campaignId.equals(row.path("campaign_id").asString(null))) {
                    throw new Refused(Code.FORMAT);
                }
            }
        }
        return new Source(root, campaignId, adventureId, revisionId, assets(root));
    }

    /**
     * Decode and judge the optional {@code assets} section. A document without it (an export older than P17.1-C)
     * restores its associations without bytes. Nothing the document states is trusted: the media facts come from
     * the decoded bytes, which must hash to the stated digest, be admitted media, and be named by an association of
     * the campaign, so the section cannot carry bytes the campaign does not show.
     */
    private List<Asset> assets(JsonNode root) {
        JsonNode section = root.get(CampaignExports.ASSETS);
        if (section == null) {
            return List.of();
        }
        if (!section.isArray() || section.size() > MAX_ASSETS) {
            throw new Refused(Code.FORMAT);
        }
        var named = new HashSet<String>();
        for (JsonNode row : root.at("/tables/asset_association")) {
            named.add(row.path("sha256").asString(""));
        }
        var assets = new ArrayList<Asset>();
        var seen = new HashSet<String>();
        for (JsonNode entry : section) {
            String digest = entry.path("sha256").asString("");
            if (!entry.isObject() || !DIGEST.matcher(digest).matches() || !entry.path("content").isString()
                    || !named.contains(digest) || !seen.add(digest)) {
                throw new Refused(Code.FORMAT);
            }
            byte[] bytes;
            PackMedia.Media media;
            try {
                bytes = Base64.getDecoder().decode(entry.path("content").asString(""));
                media = PackMedia.inspectBytes(bytes);
            } catch (IllegalArgumentException malformed) {
                // Includes PackException, which refuses unsupported or oversized media without echoing it.
                throw new Refused(Code.FORMAT);
            }
            if (!sha256(bytes).equals(digest)) {
                throw new Refused(Code.INTEGRITY);
            }
            assets.add(new Asset(digest, media.mediaType(), media.width(), media.height(), bytes));
        }
        return List.copyOf(assets);
    }

    /** The whole restore inside one transaction; any refusal rolls it back. */
    private Restored write(UUID owner, UUID destination, Source source, Identities identities) {
        jdbc.execute("set local statement_timeout = '120s'");
        if (jdbc.queryForList("select id from adventure_workspace where id = ? and owner_id = ? for share", UUID.class,
                destination, owner).isEmpty()) {
            throw new Refused(Code.SCOPE_UNAVAILABLE);
        }
        String schema = jdbc.query("select version from flyway_schema_history where success and version is not null "
                + "order by installed_rank desc limit 1", (rs, n) -> rs.getString(1)).stream().findFirst().orElse(null);
        JsonNode tables = source.root().get("tables");
        var expected = new TreeSet<>(jdbc.queryForList("select c.table_name::text from information_schema.columns c "
                + "join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name "
                + "where c.table_schema = current_schema() and c.column_name = 'campaign_id' and t.table_type = "
                + "'BASE TABLE'", String.class));
        expected.add("campaign");
        if (!source.root().get("schemaVersion").asString().equals(schema)
                || !expected.equals(new TreeSet<>(tables.propertyNames()))) {
            throw new Refused(Code.SCHEMA_MISMATCH);
        }
        for (String identity : jdbc.queryForList("select table_name || '.' || column_name from "
                + "information_schema.columns where table_schema = current_schema() and is_identity = 'YES' and "
                + "table_name = any(cast(? as text[]))", String.class, (Object) expected.toArray(new String[0]))) {
            if (!identity.equals(KNOWN_IDENTITY)) {
                throw new Refused(Code.SCHEMA_MISMATCH);
            }
        }
        UUID revision = destinationRevision(destination, source.root().get("content"));
        requirePinnedContent(tables, source.revisionId());

        var uuidColumns = new LinkedHashMap<String, Set<String>>();
        jdbc.query("select table_name::text, column_name::text from information_schema.columns where table_schema = "
                + "current_schema() and udt_name in ('uuid', '_uuid')", (RowCallbackHandler) rs -> uuidColumns
                        .computeIfAbsent(rs.getString(1), key -> new HashSet<>()).add(rs.getString(2)));
        Map<String, String> map = identityMap(tables, uuidColumns, source, destination, revision, identities);

        var inserts = new ArrayList<Map.Entry<String, String>>();
        int rows = 0;
        TreeMap<Long, Long> invalidations = renumberInvalidations(tables.get(INVALIDATIONS));
        for (String table : expected) {
            ArrayNode values = (ArrayNode) tables.get(table);
            if (NOT_RESTORED.contains(table) || values.isEmpty()) {
                continue;
            }
            ArrayNode copy = values.deepCopy();
            switch (table) {
                case INVALIDATIONS -> copy.forEach(row -> ((ObjectNode) row).put("sequence",
                        invalidations.get(row.path("sequence").asLong())));
                case SUMMARIES -> copy.forEach(row -> ((ObjectNode) row).put("invalidation_high_water",
                        highWater(invalidations, row.path("invalidation_high_water").asLong())));
                case CHECKPOINTS -> copy.forEach(row -> rekeyCheckpoint((ObjectNode) row, map));
                default -> { }
            }
            inserts.add(Map.entry(table, substitute(json.writeValueAsString(copy), map)));
            rows += copy.size();
        }

        UUID campaign = UUID.fromString(map.getOrDefault(source.campaignId(), source.campaignId()));
        // Bytes first, so every restored association finds its image; equal bytes are stored once and never replaced.
        for (Asset asset : source.assets()) {
            jdbc.update("insert into managed_asset (sha256, media_type, byte_size, width, height, content, created_at) "
                    + "values (?, ?, ?, ?, ?, ?, clock_timestamp()) on conflict (sha256) do nothing", asset.sha256(),
                    asset.mediaType(), (long) asset.bytes().length, asset.width(), asset.height(), asset.bytes());
        }
        jdbc.execute("set local session_replication_role = replica");
        for (var insert : inserts) {
            // Names were checked against the catalog and the identifier pattern, so quoting them is safe.
            String override = insert.getKey().equals(INVALIDATIONS) ? " overriding system value" : "";
            jdbc.update("insert into \"" + insert.getKey() + "\"" + override + " select * from jsonb_populate_recordset("
                    + "null::\"" + insert.getKey() + "\", cast(? as jsonb))", insert.getValue());
        }
        jdbc.execute("set local session_replication_role = origin");
        verifyReferences(expected, campaign);
        long stateRevision = jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class,
                campaign);
        var changed = new LinkedHashMap<UUID, UUID>();
        map.forEach((from, to) -> changed.put(UUID.fromString(from), UUID.fromString(to)));
        return new Restored(destination, campaign, revision, stateRevision, rows, changed);
    }

    /**
     * Find the destination's sealed revision with the export's exact pack coordinates and hashes; titles are never
     * consulted.
     */
    private UUID destinationRevision(UUID destination, JsonNode content) {
        var found = jdbc.query("select r.id, r.content_hash, r.ruleset_id, r.ruleset_version, r.ruleset_hash, exists ("
                + "select 1 from content_revision_report p where p.content_revision_id = r.id) from content_revision r "
                + "where r.adventure_id = ? and r.pack_id = ? and r.pack_version = ? for share of r", (rs, n) -> new Object[] {
                        rs.getObject(1, UUID.class), List.of(rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5)), rs.getBoolean(6)},
                destination, content.path("packId").asString(""), content.path("packVersion").asString(""));
        if (found.isEmpty() || !(boolean) found.getFirst()[2]) {
            throw new Refused(Code.CONTENT_UNAVAILABLE);
        }
        var expected = List.of(content.path("contentHash").asString(""), content.path("rulesetId").asString(""),
                content.path("rulesetVersion").asString(""), content.path("rulesetHash").asString(""));
        if (!expected.equals(found.getFirst()[1])) {
            throw new Refused(Code.CONTENT_CONFLICT);
        }
        return (UUID) found.getFirst()[0];
    }

    /** Every row naming a content revision must name the pinned one; this restore cannot remap another pack. */
    private static void requirePinnedContent(JsonNode tables, String revision) {
        for (var table : tables.properties()) {
            for (JsonNode row : table.getValue()) {
                JsonNode cited = row.get("content_revision_id");
                if (cited != null && !cited.isNull() && !revision.equals(cited.asString(null))) {
                    throw new Refused(Code.CONTENT_UNAVAILABLE);
                }
            }
        }
    }

    /**
     * Map each identity of the exported rows. The source adventure and revision become the destination's; with fresh
     * identities every other one gets a new random UUID, assigned in the same order, so ordering by identity is kept.
     */
    private static Map<String, String> identityMap(JsonNode tables, Map<String, Set<String>> columns, Source source,
            UUID destination, UUID revision, Identities mode) {
        var found = new TreeSet<String>();
        found.add(source.campaignId());
        for (var table : tables.properties()) {
            Set<String> names = columns.getOrDefault(table.getKey(), Set.of());
            for (JsonNode row : table.getValue()) {
                for (String name : names) {
                    collect(row.get(name), found);
                }
            }
        }
        found.remove(source.adventureId());
        found.remove(source.revisionId());
        var map = new TreeMap<String, String>();
        map.put(source.adventureId(), destination.toString());
        map.put(source.revisionId(), revision.toString());
        if (mode == Identities.FRESH) {
            var fresh = new TreeSet<String>();
            while (fresh.size() < found.size()) {
                String candidate = UUID.randomUUID().toString();
                if (!found.contains(candidate)) {
                    fresh.add(candidate);
                }
            }
            // Lowercase hexadecimal text order equals PostgreSQL's byte order of uuid values.
            var next = fresh.iterator();
            for (String identity : found) {
                map.put(identity, next.next());
            }
        }
        map.entrySet().removeIf(entry -> entry.getKey().equals(entry.getValue()));
        return map;
    }

    private static void collect(JsonNode value, Set<String> found) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(element -> collect(element, found));
        } else if (value.isString() && isUuid(value.asString())) {
            found.add(value.asString());
        }
    }

    /** New installation-wide invalidation numbers for the exported ones, in the same order. */
    private TreeMap<Long, Long> renumberInvalidations(JsonNode rows) {
        var old = new TreeSet<Long>();
        if (rows != null) {
            rows.forEach(row -> old.add(row.path("sequence").asLong()));
        }
        var renumbered = new TreeMap<Long, Long>();
        if (old.isEmpty()) {
            return renumbered;
        }
        var allocated = new TreeSet<>(jdbc.queryForList("select nextval(pg_get_serial_sequence('memory_invalidation', "
                + "'sequence')) from generate_series(1, ?)", Long.class, old.size()));
        var next = allocated.iterator();
        old.forEach(sequence -> renumbered.put(sequence, next.next()));
        return renumbered;
    }

    /**
     * A summary is stale through invalidations numbered above its mark; the restored mark is the new number of the
     * newest exported invalidation at or below the old mark, or 0 when none was, so the same invalidations count.
     */
    private static long highWater(TreeMap<Long, Long> invalidations, long mark) {
        var floor = invalidations.floorEntry(mark);
        return floor == null ? 0 : floor.getValue();
    }

    /** Give an intact checkpoint the hash of its re-keyed snapshot; a corrupt one keeps its evidence. */
    private void rekeyCheckpoint(ObjectNode row, Map<String, String> map) {
        String snapshot = json.writeValueAsString(row.get("snapshot"));
        String stored = row.path("snapshot_hash").asString("");
        String intact = jdbc.queryForObject("select " + SQL_HASH, String.class, snapshot);
        if (stored.equals(intact)) {
            row.put("snapshot_hash", jdbc.queryForObject("select " + SQL_HASH, String.class, substitute(snapshot, map)));
        }
    }

    /**
     * Verify every foreign key of the restored rows, because triggers (and with them foreign-key enforcement) were
     * suspended while they were inserted. A key with a null column is not checked, as PostgreSQL's simple match does.
     */
    private void verifyReferences(Set<String> tables, UUID campaign) {
        var keys = jdbc.query("select c.conrelid::regclass::text, c.confrelid::regclass::text, (select array_agg("
                + "a.attname::text order by k.i) from unnest(c.conkey) with ordinality k(n, i) join pg_attribute a on "
                + "a.attrelid = c.conrelid and a.attnum = k.n), (select array_agg(a.attname::text order by k.i) from "
                + "unnest(c.confkey) with ordinality k(n, i) join pg_attribute a on a.attrelid = c.confrelid and "
                + "a.attnum = k.n) from pg_constraint c where c.contype = 'f' and c.conrelid::regclass::text = "
                + "any(cast(? as text[])) order by 1, 2", (rs, n) -> new Object[] {rs.getString(1), rs.getString(2),
                        (String[]) rs.getArray(3).getArray(), (String[]) rs.getArray(4).getArray()},
                (Object) tables.toArray(new String[0]));
        for (Object[] key : keys) {
            String child = (String) key[0];
            String parent = (String) key[1];
            String[] from = (String[]) key[2];
            String[] to = (String[]) key[3];
            if (!NAME.matcher(child).matches() || !NAME.matcher(parent).matches()) {
                throw new IllegalStateException("unexpected table name");
            }
            var present = new StringBuilder();
            var join = new StringBuilder();
            for (int i = 0; i < from.length; i++) {
                if (!NAME.matcher(from[i]).matches() || !NAME.matcher(to[i]).matches()) {
                    throw new IllegalStateException("unexpected column name");
                }
                present.append(" and x.\"").append(from[i]).append("\" is not null");
                join.append(i == 0 ? "" : " and ").append("y.\"").append(to[i]).append("\" = x.\"").append(from[i])
                        .append('"');
            }
            String scope = child.equals("campaign") ? "id" : "campaign_id";
            Integer broken = jdbc.queryForObject("select count(*) from \"" + child + "\" x where x.\"" + scope
                    + "\" = ?" + present + " and not exists (select 1 from \"" + parent + "\" y where " + join + ")",
                    Integer.class, campaign);
            if (broken != null && broken > 0) {
                throw new IllegalStateException("restored rows break a reference");
            }
        }
    }

    /** Replace every mapped identity in a text; identities are fixed-length hexadecimal, so JSON stays valid. */
    private static String substitute(String text, Map<String, String> map) {
        if (map.isEmpty()) {
            return text;
        }
        Matcher matcher = UUID_TEXT.matcher(text);
        var out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(map.getOrDefault(matcher.group(), matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isUuid(String text) {
        return text != null && UUID_TEXT.matcher(text).matches();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Whether the restored campaign gets new identities; every caller names one. */
    public enum Identities {
        /** New random identities in the source order; the restore can sit beside its source. */
        FRESH,
        /** The source identities, for a clean installation; a collision refuses the restore. */
        ORIGINAL
    }

    /** Why a restore was refused; none of them wrote a row. */
    public enum Code {
        /** The document is larger than {@value CampaignRestores#MAX_DOCUMENT_BYTES} bytes. */
        LIMIT,
        /** The document's bytes do not have the stated SHA-256. */
        INTEGRITY,
        /** Not a consistent full-owner campaign export of this format version. */
        FORMAT,
        /** The export was made by another schema version or names another set of campaign tables. */
        SCHEMA_MISMATCH,
        /** The destination adventure is missing or belongs to someone else. */
        SCOPE_UNAVAILABLE,
        /** The destination has no sealed revision of the pinned pack version, or a row names another revision. */
        CONTENT_UNAVAILABLE,
        /** The destination's revision of that pack version has other content or ruleset hashes. */
        CONTENT_CONFLICT,
        /** A restored row collides with a stored one, which only kept identities can cause. */
        IDENTITY_IN_USE
    }

    /** A refused restore with its fixed code; it retains no identities or diagnostics. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Code code;

        /** @param code why the restore was refused */
        public Refused(Code code) {
            super("campaign restore refused: " + code);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** @return why the restore was refused */
        public Code code() {
            return code;
        }
    }

    /**
     * The restored campaign.
     *
     * @param adventureId the destination adventure
     * @param campaignId the new campaign, never one that existed before
     * @param contentRevisionId the destination revision it is pinned to
     * @param stateRevision the committed state revision it continues from, equal to the export's
     * @param rows rows written
     * @param identities each source identity that changed and its restored value
     */
    public record Restored(UUID adventureId, UUID campaignId, UUID contentRevisionId, long stateRevision, int rows,
            Map<UUID, UUID> identities) {
        /** Copy the map so the result cannot change after it is returned. */
        public Restored {
            identities = Map.copyOf(identities);
        }
    }

    /** A parsed, self-consistent export. */
    private record Source(JsonNode root, String campaignId, String adventureId, String revisionId, List<Asset> assets) {
    }

    /** One decoded and verified entry of the document's {@code assets} section. */
    private record Asset(String sha256, String mediaType, int width, int height, byte[] bytes) {
    }
}
