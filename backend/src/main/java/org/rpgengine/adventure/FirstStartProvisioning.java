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
 * First-start provisioning of a packaged installation (ADR 0046, ADR 0055): the configured single owner and
 * one workspace per sample adventure bundled in the image, each sealed through the normal registration path.
 *
 * <p>It runs once per start, after persistence initialization and before the web server, only when
 * {@code engine.provisioning.enabled} is true (the packaged profile) and {@code engine.http.owner-id}
 * names an owner. Every step is idempotent, so a restart creates no second owner, workspace or revision:
 * <ul>
 * <li>the owner row is inserted only when missing;</li>
 * <li>each sample workspace has an identity derived from the owner and the sample, and the sample
 *     workspaces are created together, in one transaction, only when that owner has no workspace at all.
 *     So an owner who already has adventures, including an installation provisioned by an earlier image
 *     with one sample, is never given another one: only a fresh installation receives every sample;</li>
 * <li>each bundled pack is registered only into its own workspace and only while that workspace holds no
 *     revision, which also repairs a start interrupted between the steps. A later image with other sample
 *     bytes never adds a revision to a workspace that already has one.</li>
 * </ul>
 *
 * <p>Failures propagate and abort startup: a database that already belongs to a different owner (the
 * launcher's owner file was lost or replaced), storage errors and a bundled pack that this build cannot
 * seal. No test-only route or seeding endpoint exists; this is the only writer.
 */
@Component
public class FirstStartProvisioning implements SmartInitializingSingleton {
    /**
     * Classpath folders of the bundled sample packs, in provisioning order; each manifest lists its other files. The
     * first is the original sample, whose workspace identity predates the second (ADR 0055). The folders are numbered,
     * not named after their adventures, so the engine still names no adventure (P15.5-A).
     */
    static final List<String> SAMPLE_ROOTS = List.of("sample-adventure/", "sample-adventure-2/");
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
     * Ensure the owner, its sample workspaces and their sealed sample revisions exist.
     *
     * @param owner the single owner of this installation
     * @return what this call created; nothing created on a restart of a provisioned installation
     * @throws IllegalStateException when the database holds another owner but not this one, or inside a
     *         transaction
     * @throws org.rpgengine.content.PackException when a bundled pack does not seal on this build
     */
    public Result provision(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        var bundles = new java.util.ArrayList<Bundle>();
        for (int index = 0; index < SAMPLE_ROOTS.size(); index++) {
            Map<String, byte[]> files = sampleFiles(SAMPLE_ROOTS.get(index));
            bundles.add(new Bundle(sampleWorkspace(owner, index), files, labels(files.get("adventure.json"))));
        }
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
            // Decided once, before any insert: only an owner without any workspace receives the samples, all together.
            boolean fresh = !Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from adventure_workspace where owner_id = ?)", Boolean.class, owner));
            var samples = new java.util.ArrayList<SampleStep>();
            for (Bundle bundle : bundles) {
                boolean present = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from adventure_workspace where id = ?)", Boolean.class, bundle.workspace()));
                boolean workspaceCreated = false;
                if (!present) {
                    if (!fresh) {
                        continue;
                    }
                    jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, "
                            + "updated_at) values (?, ?, ?, ?, 'en', now(), now())", bundle.workspace(), owner,
                            bundle.labels().title(), bundle.labels().genre());
                    workspaceCreated = true;
                }
                boolean empty = !Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from content_revision where adventure_id = ?)", Boolean.class,
                        bundle.workspace()));
                samples.add(new SampleStep(bundle, workspaceCreated, empty));
            }
            return new Step(ownerCreated, samples);
        });
        var outcomes = new java.util.ArrayList<Sample>();
        for (SampleStep sample : step.samples()) {
            boolean revisionCreated = false;
            if (sample.register()) {
                // Registration owns its transaction and seals bytes only after every check passes.
                var staged = new PackStaging(PackStaging.Limits.standard())
                        .archive(new ByteArrayInputStream(zip(sample.bundle().files())));
                revisionCreated = registry.register(sample.bundle().workspace(), staged).created();
            }
            outcomes.add(new Sample(sample.bundle().workspace(), sample.workspaceCreated(), revisionCreated));
        }
        return new Result(step.ownerCreated(), List.copyOf(outcomes), sampleWorkspace(owner));
    }

    /**
     * Identity of an owner's first (fantasy) sample workspace, unchanged since ADR 0046.
     *
     * @see #sampleWorkspace(UUID, int)
     */
    static UUID sampleWorkspace(UUID owner) {
        return sampleWorkspace(owner, 0);
    }

    /**
     * Identity of the workspace of the bundled sample at {@code index} of {@link #SAMPLE_ROOTS}: name-based, so
     * every start finds the same row without a marker column. The first keeps the derivation of ADR 0046, so an
     * installation provisioned before the second sample existed still finds its workspace. It is an opaque storage
     * key and grants nothing by itself.
     */
    static UUID sampleWorkspace(UUID owner, int index) {
        String name = index == 0 ? "engine.sample-workspace:" + owner
                : "engine.sample-workspace:" + owner + ":" + SAMPLE_ROOTS.get(index);
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read one bundled pack: the manifest, then every file it lists, from this build's classpath.
     *
     * @param root one of {@link #SAMPLE_ROOTS}
     * @return logical path to bytes
     * @throws IllegalStateException when the image lacks a listed file
     */
    static Map<String, byte[]> sampleFiles(String root) {
        Map<String, byte[]> files = new TreeMap<>();
        byte[] manifest = resource(root, "adventure.json");
        files.put("adventure.json", manifest);
        @SuppressWarnings("unchecked")
        Map<String, Object> values = JsonMapper.builder().build().readValue(manifest, Map.class);
        for (Object entry : (List<?>) values.get("files")) {
            String path = (String) ((Map<?, ?>) entry).get("path");
            files.put(path, resource(root, path));
        }
        return files;
    }

    /**
     * Library labels of the sample workspace, read from the bundled manifest so the engine names no adventure of its own
     * (P15.5-A): the manifest's {@code title}, and as genre the first {@code contentGuidance.themes} entry, since the pack
     * contract has no genre field. A missing, blank or over-80-character theme gives the neutral label {@code adventure}.
     *
     * @param manifest bytes of {@code adventure.json}
     * @return the stripped title and genre
     * @throws IllegalStateException when the manifest has no usable title, which is a build defect
     */
    static Labels labels(byte[] manifest) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = JsonMapper.builder().build().readValue(manifest, Map.class);
        if (!(values.get("title") instanceof String title) || title.isBlank()) {
            throw new IllegalStateException("bundled sample adventure has no title");
        }
        String genre = "adventure";
        if (values.get("contentGuidance") instanceof Map<?, ?> guidance && guidance.get("themes") instanceof List<?> themes
                && !themes.isEmpty() && themes.getFirst() instanceof String theme && !theme.isBlank()
                && theme.strip().length() <= 80) {
            genre = theme.strip();
        }
        return new Labels(title.strip(), genre);
    }

    /** One bundled file; a missing resource is a build defect. */
    private static byte[] resource(String root, String path) {
        try (InputStream input = FirstStartProvisioning.class.getClassLoader().getResourceAsStream(root + path)) {
            if (input == null) {
                throw new IllegalStateException("bundled sample adventure file is missing: " + root + path);
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

    /** One bundled sample read from the classpath, with the workspace it belongs in. */
    private record Bundle(UUID workspace, Map<String, byte[]> files, Labels labels) {
    }

    /**
     * Database step outcome.
     *
     * @param samples the sample workspaces that exist after the step, in {@link #SAMPLE_ROOTS} order; empty when
     *        the owner already had other workspaces
     */
    private record Step(boolean ownerCreated, List<SampleStep> samples) {
    }

    /**
     * Database step outcome of one sample.
     *
     * @param register the sample workspace holds no revision yet, so the bundled pack must be sealed
     */
    private record SampleStep(Bundle bundle, boolean workspaceCreated, boolean register) {
    }

    /**
     * Library labels of the sample workspace.
     *
     * @param title the bundled manifest's title
     * @param genre its first content theme, or {@code adventure}
     */
    record Labels(String title, String genre) {
    }

    /**
     * What one provisioning call found and created for one bundled sample.
     *
     * @param workspaceId the sample's workspace
     * @param workspaceCreated the workspace was inserted by this call
     * @param revisionCreated the bundled pack was sealed as a new revision by this call
     */
    public record Sample(UUID workspaceId, boolean workspaceCreated, boolean revisionCreated) {
    }

    /**
     * What one provisioning call created.
     *
     * @param ownerCreated the owner row was inserted
     * @param samples every sample workspace that exists after the call, in {@link #SAMPLE_ROOTS} order; empty when
     *        the owner already had other workspaces and none was created
     * @param first identity of the first (fantasy) sample's workspace, used by {@link #workspaceId()}
     */
    public record Result(boolean ownerCreated, List<Sample> samples, UUID first) {
        /** Copy the list so the result stays immutable. */
        public Result {
            samples = List.copyOf(samples);
        }

        /** The first (fantasy) sample's workspace, or null when it does not exist after the call. */
        public UUID workspaceId() {
            return samples.stream().map(Sample::workspaceId).filter(first::equals).findFirst().orElse(null);
        }
    }
}
