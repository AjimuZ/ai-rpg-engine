package org.rpgengine.adventure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * First-start provisioning of a packaged installation (ADR 0046): the configured single owner, one
 * workspace and the sample adventure bundled in the image, sealed through the normal registration path.
 *
 * <p>It runs once per start, after persistence initialization and before the web server, only when
 * {@code engine.provisioning.enabled} is true (the packaged profile) and {@code engine.http.owner-id}
 * names an owner. Every step is idempotent, so a restart creates no second owner, workspace or revision:
 * <ul>
 * <li>the owner row is inserted only when missing;</li>
 * <li>the sample workspace has an identity derived from the owner, and is created only when that owner
 *     has no workspace at all, so an owner who already has adventures is never given another one;</li>
 * <li>the bundled pack is registered only into that workspace and only while it holds no revision, which
 *     also repairs a start interrupted between the two steps. A later image with another sample never
 *     adds a revision to a workspace that already has one.</li>
 * </ul>
 *
 * <p>Failures propagate and abort startup: a database that already belongs to a different owner (the
 * launcher's owner file was lost or replaced), storage errors and a bundled pack that this build cannot
 * seal. No test-only route or seeding endpoint exists; this is the only writer.
 */
@Component
public class FirstStartProvisioning implements SmartInitializingSingleton {
    /** Classpath folder of the bundled sample pack; its manifest lists every other file. */
    static final String SAMPLE_ROOT = "sample-adventure/";
    private static final String OWNER_NAME = "Owner";
    private final JdbcTemplate jdbc;
    private final PackRegistry registry;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final String ownerId;

    /**
     * Wire provisioning; nothing is read or written until startup calls it.
     *
     * @param enabled true only in the packaged profile
     * @param ownerId configured owner UUID, or empty when none is configured
     */
    @Autowired
    public FirstStartProvisioning(JdbcTemplate jdbc, PlatformTransactionManager manager, PackRegistry registry,
            @Value("${engine.provisioning.enabled:false}") boolean enabled,
            @Value("${engine.http.owner-id:}") String ownerId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = Objects.requireNonNull(registry, "registry");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
        this.enabled = enabled;
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    }

    /** Provision at startup when enabled and an owner is configured; any failure prevents readiness. */
    @Override
    public void afterSingletonsInstantiated() {
        if (enabled && !ownerId.isEmpty()) {
            provision(UUID.fromString(ownerId));
        }
    }

    /**
     * Ensure the owner, its sample workspace and the sealed sample revision exist.
     *
     * @param owner the single owner of this installation
     * @return what this call created; all false on a restart of a provisioned installation
     * @throws IllegalStateException when the database holds another owner but not this one, or inside a
     *         transaction
     * @throws org.rpgengine.content.PackException when the bundled pack does not seal on this build
     */
    public Result provision(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        UUID workspace = sampleWorkspace(owner);
        Step step = transactions.execute(status -> {
            // Serializes concurrent starts on one database; released at commit.
            jdbc.execute("select pg_advisory_xact_lock(hashtext('engine.first-start-provisioning'))");
            boolean known = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from identity_owner_profile where id = ?)", Boolean.class, owner));
            boolean ownerCreated = false;
            if (!known) {
                // A lost owner file must not hide the previous owner's adventures behind a new empty owner.
                if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from identity_owner_profile)",
                        Boolean.class))) {
                    throw new IllegalStateException("this database belongs to another owner; restore the "
                            + "installation's original owner identity instead of creating a second owner");
                }
                jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                        + "values (?, ?, 'en', now(), now())", owner, OWNER_NAME);
                ownerCreated = true;
            }
            boolean present = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from adventure_workspace where id = ?)", Boolean.class, workspace));
            boolean workspaceCreated = false;
            if (!present) {
                if (Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from adventure_workspace where owner_id = ?)", Boolean.class, owner))) {
                    return new Step(ownerCreated, false, false, false);
                }
                jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, "
                        + "updated_at) values (?, ?, ?, ?, 'en', now(), now())", workspace, owner, "Lantern Ford", "fantasy");
                workspaceCreated = true;
            }
            boolean empty = !Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from content_revision where adventure_id = ?)", Boolean.class, workspace));
            return new Step(ownerCreated, workspaceCreated, true, empty);
        });
        boolean revisionCreated = false;
        if (step.register()) {
            // Registration owns its transaction and seals bytes only after every check passes.
            var staged = new PackStaging(PackStaging.Limits.standard()).archive(new ByteArrayInputStream(zip(sampleFiles())));
            revisionCreated = registry.register(workspace, staged).created();
        }
        return new Result(step.ownerCreated(), step.workspaceCreated(), revisionCreated,
                step.sample() ? workspace : null);
    }

    /**
     * Identity of an owner's sample workspace: name-based, so every start finds the same row without a marker
     * column. It is an opaque storage key and grants nothing by itself.
     */
    static UUID sampleWorkspace(UUID owner) {
        return UUID.nameUUIDFromBytes(("engine.sample-workspace:" + owner).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read the bundled pack: the manifest, then every file it lists, from this build's classpath.
     *
     * @return logical path to bytes
     * @throws IllegalStateException when the image lacks a listed file
     */
    static Map<String, byte[]> sampleFiles() {
        Map<String, byte[]> files = new TreeMap<>();
        byte[] manifest = resource("adventure.json");
        files.put("adventure.json", manifest);
        @SuppressWarnings("unchecked")
        Map<String, Object> values = JsonMapper.builder().build().readValue(manifest, Map.class);
        for (Object entry : (List<?>) values.get("files")) {
            String path = (String) ((Map<?, ?>) entry).get("path");
            files.put(path, resource(path));
        }
        return files;
    }

    /** One bundled file; a missing resource is a build defect. */
    private static byte[] resource(String path) {
        try (InputStream input = FirstStartProvisioning.class.getClassLoader().getResourceAsStream(SAMPLE_ROOT + path)) {
            if (input == null) {
                throw new IllegalStateException("bundled sample adventure file is missing: " + path);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("bundled sample adventure is unreadable", failure);
        }
    }

    /** Archive the files in memory so they cross the same staging boundary as an owner's import. */
    private static byte[] zip(Map<String, byte[]> files) {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("bundled sample adventure could not be archived", failure);
        }
        return output.toByteArray();
    }

    /**
     * Database step outcome.
     *
     * @param sample the sample workspace exists after the step; false when the owner already had others
     * @param register the sample workspace holds no revision yet, so the bundled pack must be sealed
     */
    private record Step(boolean ownerCreated, boolean workspaceCreated, boolean sample, boolean register) {
    }

    /**
     * What one provisioning call created.
     *
     * @param ownerCreated the owner row was inserted
     * @param workspaceCreated the sample workspace was inserted
     * @param revisionCreated the bundled pack was sealed as a new revision
     * @param workspaceId the sample workspace, or null when the owner already had other workspaces and none was
     *        created
     */
    public record Result(boolean ownerCreated, boolean workspaceCreated, boolean revisionCreated, UUID workspaceId) {
    }
}
