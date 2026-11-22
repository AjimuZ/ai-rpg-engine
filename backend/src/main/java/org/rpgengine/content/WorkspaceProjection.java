package org.rpgengine.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Project sealed PostgreSQL originals into private, identity-scoped folders when explicitly enabled.
 * Inputs are internal committed revision identities, never browser paths. Outputs are rebuildable
 * specifications, authored media and an integrity manifest, and since P17.6-D each campaign's accepted generated
 * scene, portrait and item images under its own campaign folder; these files are never a read authority.
 * Invalid scope/integrity fails before filesystem work. I/O failure leaves committed rows intact
 * and prevents successful startup/import acknowledgement until the operator repairs the data root.
 */
@Component
@ConditionalOnProperty(name = "engine.workspaces.root")
public class WorkspaceProjection {
    private final PackRegistry registry;
    private final JdbcTemplate jdbc;
    private final RevisionFolders folders;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Bind an explicit absolute owner-controlled root and registry; invalid root configuration fails startup. */
    public WorkspaceProjection(PackRegistry registry, JdbcTemplate jdbc,
            @Value("${engine.workspaces.root}") String root) {
        this.registry = registry;
        this.jdbc = jdbc;
        folders = new RevisionFolders(Path.of(root));
    }

    /** Rebuild missing revisions after restart/restore in bounded pages; any corrupt projection aborts startup. */
    @EventListener(ContextRefreshedEvent.class)
    public void startup() {
        UUID after = null;
        while (true) {
            var page = jdbc.query("select c.adventure_id,c.id from content_revision c "
                    + "join content_revision_report r on r.content_revision_id=c.id "
                    + "where (?::uuid is null or c.id>?) order by c.id limit 50",
                    (row, index) -> new PackRegistry.RegisteredRevision(
                            row.getObject(1, UUID.class), row.getObject(2, UUID.class)), after, after);
            for (var entry : page) { registered(entry); }
            if (page.size() < 50) { break; }
            after = page.getLast().revisionId();
        }
        rebuildGenerated();
    }

    /**
     * Write every accepted scene, portrait and item association of every campaign into its own campaign folder
     * (P17.6-D), in bounded pages. A missing file is written; an existing one must hold the same bytes. An association
     * whose bytes are not stored (restored from an export older than P17.1-C) is a missing image and is skipped.
     */
    private void rebuildGenerated() {
        String[] after = null;
        while (true) {
            Object[] cursor = after == null ? new Object[] {null, "", ""} : after;
            var page = jdbc.query("select distinct c.adventure_id, a.campaign_id, a.category, a.sha256 from asset_association a "
                    + "join campaign c on c.id = a.campaign_id join managed_asset m on m.sha256 = a.sha256 "
                    + "where a.category in ('scene', 'portrait', 'item') and "
                    + "(?::uuid is null or (a.campaign_id, a.category, a.sha256) > (?::uuid, ?, ?)) "
                    + "order by a.campaign_id, a.category, a.sha256 limit 50",
                    (row, index) -> new Object[] {row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getString(3), row.getString(4)},
                    cursor[0], cursor[0], cursor[1], cursor[2]);
            for (Object[] entry : page) {
                generated((UUID) entry[0], (UUID) entry[1], (String) entry[2], (String) entry[3]);
            }
            if (page.size() < 50) {
                return;
            }
            Object[] last = page.getLast();
            after = new String[] {last[1].toString(), (String) last[2], (String) last[3]};
        }
    }

    /**
     * Project one accepted generated image into its campaign's folder (P17.6-D): {@code adventures/<adventure>/
     * campaigns/<campaign>/assets/generated/<scenes|portraits|items>/<sha256>.<extension>}. Only an association the
     * campaign holds in that adventure resolves, and the stored bytes must still match their hash, so equal subject
     * names or hashes of another adventure never write here. The database stays the authority; the file is a
     * rebuildable copy.
     *
     * @throws IllegalArgumentException when the campaign holds no such association in the adventure
     * @throws UncheckedIOException naming no host path when the folder cannot be written or holds other bytes
     */
    public synchronized void generated(UUID adventure, UUID campaign, String category, String sha256) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("workspace projection requires committed data");
        }
        var found = jdbc.query("select m.media_type, m.content from managed_asset m where m.sha256 = ? and exists ("
                + "select 1 from asset_association a join campaign c on c.id = a.campaign_id where a.campaign_id = ? "
                + "and c.adventure_id = ? and a.category = ? and a.sha256 = m.sha256) and m.sha256 = "
                + "encode(sha256(m.content), 'hex')", (row, index) -> new Object[] {row.getString(1), row.getBytes(2)},
                sha256, campaign, adventure, category);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("generated asset unavailable");
        }
        String extension = switch ((String) found.getFirst()[0]) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("generated asset is not an image");
        };
        try {
            folders.publishGenerated(adventure, campaign, category, sha256 + "." + extension, (byte[]) found.getFirst()[1]);
        } catch (IOException failure) {
            throw new UncheckedIOException("workspace projection unavailable; accepted artwork retained",
                    new IOException("private folder integrity or storage failure"));
        }
    }

    /** Reconcile a successful import or identical retry, serialized locally with startup and other imports. */
    @EventListener
    synchronized void registered(PackRegistry.RegisteredRevision entry) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("workspace projection requires committed data");
        }
        // Registration and database triggers freeze both scope and sealed files. Check the complete
        // pair before reading any private bytes, including for internal calls with a guessed revision.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from content_revision c "
                + "join content_revision_report r on r.content_revision_id=c.id "
                + "where c.adventure_id=? and c.id=?)", Boolean.class, entry.adventureId(), entry.revisionId()))) {
            throw new IllegalArgumentException("sealed workspace revision unavailable");
        }
        Map<String, byte[]> source = registry.files(entry.revisionId()).orElseThrow();
        var hashes = PackHashes.calculate(source);
        Map<String, String> categories = new LinkedHashMap<>();
        jdbc.query("select path,category from content_revision_file where content_revision_id=? order by path",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> categories.put(row.getString(1), row.getString(2)),
                entry.revisionId());
        if (!categories.keySet().equals(source.keySet()) || source.keySet().stream().anyMatch(p -> !PackPaths.portable(p))
                || PackPaths.collision(source.keySet(), java.util.List.of()) != null) {
            throw new IllegalStateException("invalid stored file inventory");
        }
        Map<String, byte[]> output = new LinkedHashMap<>();
        var inventory = new java.util.ArrayList<Map<String, Object>>();
        source.forEach((path, bytes) -> {
            String destination = (categories.get(path).equals("asset") ? "assets/authored/" : "specification/") + path;
            output.put(destination, bytes);
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("source", path);
            file.put("destination", destination);
            file.put("bytes", bytes.length);
            file.put("sha256", hashes.fileHashes().get(path));
            inventory.add(file);
        });
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 1);
        manifest.put("adventureId", entry.adventureId().toString());
        manifest.put("contentRevisionId", entry.revisionId().toString());
        manifest.put("contentHash", hashes.contentHash());
        manifest.put("files", inventory);
        output.put("integrity.json", json.writeValueAsBytes(manifest));
        try {
            folders.publish(entry.adventureId(), entry.revisionId(), output);
        } catch (IOException failure) {
            // Keep host paths out of exceptions that a future import transport might expose.
            throw new UncheckedIOException("workspace projection unavailable; registered content retained",
                    new IOException("private folder integrity or storage failure"));
        }
    }
}
