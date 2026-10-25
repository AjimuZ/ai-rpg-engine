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
 * specifications, authored media and an integrity manifest; these files are never a read authority.
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
            if (page.size() < 50) { return; }
            after = page.getLast().revisionId();
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
