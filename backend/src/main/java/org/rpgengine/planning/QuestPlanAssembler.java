package org.rpgengine.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.inference.contract.GlossaryTerm;
import org.rpgengine.inference.contract.PlanEntity;
import org.rpgengine.inference.contract.QuestPlanContext;
import org.rpgengine.inference.contract.TaskBinding;
import org.rpgengine.preparation.Audience;
import org.rpgengine.preparation.PlanAdmissionService.Dependency;
import org.rpgengine.preparation.PlanAdmissionService.Kind;
import org.rpgengine.preparation.PlanAdmissionService.Severity;
import org.rpgengine.preparation.PlanContentCatalog;
import org.rpgengine.preparation.PlanContentCatalogs;
import org.rpgengine.preparation.PlanningSnapshotService;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Snapshot assembler of the quest planner (docs/28 section 7, "capture complete dependencies"):
 * reads the authorized privileged world slice of one quest from canonical rows, records every
 * read in the dependency manifest, captures the immutable planning snapshot and returns the
 * matching {@link QuestPlanContext}. The engine, never the model, decides what was read.
 *
 * <p>Slice of a {@code known-quest:<uuid>} target, read in one repeatable-read transaction:
 * the known quest; its holder (the player character) and the actors at the holder's location;
 * their resource pools; the holder's items and the 32 most recent knowledge records (how many
 * exist is recorded in the selected state, so the selection is explicit, never silent); the
 * pinned definitions those rows name plus every protected author constraint. Each entity gets
 * an exact row-version dependency; each actor also gets its {@code actor:<uuid>} query epoch,
 * which P07.4 bumps on every placement or pool change. Every entry starts at the conservative
 * whole-artifact severity; only the plan validator may narrow it to branches.
 *
 * <p>Failures: an unsupported target or unknown quest is {@link IllegalArgumentException}; a
 * slice exceeding a context bound is {@link IllegalStateException} with message
 * {@value #TOO_LARGE} (explicit refusal, never truncation); a concurrent world change between the
 * read and the capture is the snapshot service's {@link IllegalStateException}.
 *
 * <p>Known limits: location membership has no query epoch yet (no writer tags it), so an actor
 * arriving at the holder's location after capture is not a phantom this slice detects; secret
 * authored definitions are offered as content references only, without text.
 */
@Component
public class QuestPlanAssembler {
    /** Failure message of a slice above a context bound. */
    public static final String TOO_LARGE = "CONTEXT_TOO_LARGE";
    /** Most knowledge records supplied. */
    static final int FACTS = 32;
    /** SHA-256 identifying the planner's tool-less privileged policy; bound to job and snapshot. */
    public static final String POLICY_HASH = sha256("quest-planner|privileged|tool-less|"
            + org.rpgengine.inference.contract.QuestPlanContract.JOB_CONTRACT);
    private static final Pattern TARGET = Pattern.compile(
            "known-quest:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;
    private final PlanContentCatalogs catalogs;
    private final PlanningSnapshotService snapshots;

    /** Wire local persistence, pinned content and the snapshot service. */
    public QuestPlanAssembler(JdbcTemplate jdbc, PlatformTransactionManager manager, PlanContentCatalogs catalogs,
            PlanningSnapshotService snapshots) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    /** @return whether {@code target} names a known quest this assembler can slice */
    public static boolean supports(String target) {
        return target != null && TARGET.matcher(target).matches();
    }

    /**
     * Read, record and capture one planning slice. Must be called outside a transaction.
     *
     * @param plan backend-owned plan identity, version, predecessor and refinement
     * @param taskId contract task identity of this attempt
     * @param maxHorizonSeconds horizon bound passed to the planner
     * @return the snapshot and the planner context built from exactly its bytes
     */
    public Assembly assemble(UUID adventure, UUID campaign, String target, PlanIdentity plan, UUID taskId,
            long maxHorizonSeconds) {
        Objects.requireNonNull(plan, "plan");
        var matcher = TARGET.matcher(Objects.requireNonNull(target, "target"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unsupported plan target");
        }
        UUID quest = UUID.fromString(matcher.group(1));
        PlanContentCatalog catalog = catalogs.preload(campaign).catalog();
        if (catalog == null) {
            throw new IllegalStateException("snapshot content unavailable");
        }
        Slice slice = Objects.requireNonNull(reads.execute(status -> read(adventure, campaign, quest, catalog)));
        var snapshot = snapshots.capture(adventure, campaign, new PlanningSnapshotService.SnapshotRequest(
                Audience.PLANNER_PRIVATE, slice.selectedState(), slice.sourceRefs(), slice.manifest(),
                catalog.authorConstraintHash(), POLICY_HASH, slice.revision(), slice.generation(),
                slice.localeRevision(), slice.glossaryRevision()));
        var binding = new TaskBinding(taskId, adventure, campaign, snapshot.generation(), snapshot.sourceRevision(),
                slice.locale(), snapshot.localeRevision(), snapshot.glossaryRevision(), slice.packHash(), POLICY_HASH);
        var context = new QuestPlanContext(binding, plan.planId(), plan.version(), plan.predecessor(), target,
                slice.title(), slice.objective(), snapshot.worldTime(), maxHorizonSeconds, slice.entities(),
                slice.lore(), slice.contextDependencies(), slice.contentRefs(), slice.constraints(), plan.refinement(),
                List.<GlossaryTerm>of());
        return new Assembly(snapshot, slice.manifest(), context);
    }

    /** Read every row of the slice inside the caller's repeatable-read transaction. */
    private Slice read(UUID adventure, UUID campaign, UUID quest, PlanContentCatalog catalog) {
        var tokens = jdbc.queryForList("select c.state_revision, c.recovery_generation, r.content_hash, "
                + "coalesce(p.narrative_locale, w.source_locale) as locale, coalesce(p.locale_revision, 0) as lr, "
                + "coalesce(p.glossary_revision, 0) as gr from campaign c join content_revision r on r.id = "
                + "c.content_revision_id join adventure_workspace w on w.id = c.adventure_id left join "
                + "presentation_campaign_settings p on p.campaign_id = c.id where c.id = ? and c.adventure_id = ?",
                campaign, adventure);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("unknown campaign");
        }
        var token = tokens.getFirst();
        var quests = jdbc.queryForList("select actor_id, title, objective, status, row_version from campaign_known_quest "
                + "where campaign_id = ? and id = ?", campaign, quest);
        if (quests.isEmpty()) {
            throw new IllegalArgumentException("unknown quest");
        }
        var questRow = quests.getFirst();
        UUID holder = (UUID) questRow.get("actor_id");
        var entities = new ArrayList<PlanEntity>();
        var versions = new LinkedHashMap<String, Long>();
        var definitions = new LinkedHashSet<String>();
        String questRef = "known-quest:" + quest;
        entities.add(new PlanEntity(questRef, (String) questRow.get("title"), null, null, (String) questRow.get("status"),
                "actor:" + holder));
        versions.put(questRef, number(questRow.get("row_version")));
        var holderRow = jdbc.queryForMap("select location_id from actor where campaign_id = ? and id = ?", campaign, holder);
        String location = (String) holderRow.get("location_id");
        // The holder first, then the actors sharing its location, in identity order.
        var actors = jdbc.queryForList("select id, display_name, location_id, definition_id, row_version from actor "
                + "where campaign_id = ? and (id = ? or location_id = cast(? as text)) "
                + "order by (id = ?) desc, id", campaign, holder, location, holder);
        var actorIds = new ArrayList<UUID>();
        for (var actor : actors) {
            UUID id = (UUID) actor.get("id");
            actorIds.add(id);
            String ref = "actor:" + id;
            entities.add(new PlanEntity(ref, (String) actor.get("display_name"), (String) actor.get("location_id"),
                    null, null, null));
            versions.put(ref, number(actor.get("row_version")));
            if (actor.get("definition_id") != null) {
                definitions.add((String) actor.get("definition_id"));
            }
        }
        for (UUID actor : actorIds) {
            for (var pool : jdbc.queryForList("select id, resource_key, current_value, row_version from actor_resource "
                    + "where campaign_id = ? and actor_id = ? order by id", campaign, actor)) {
                String ref = "resource:" + pool.get("id");
                entities.add(new PlanEntity(ref, (String) pool.get("resource_key"), null,
                        Math.max(0, number(pool.get("current_value"))), null, "actor:" + actor));
                versions.put(ref, number(pool.get("row_version")));
            }
        }
        for (var item : jdbc.queryForList("select id, definition_id, row_version from item_instance "
                + "where campaign_id = ? and actor_id = ? order by id", campaign, holder)) {
            String ref = "item:" + item.get("id");
            entities.add(new PlanEntity(ref, label((String) item.get("definition_id")), null, null, null,
                    "actor:" + holder));
            versions.put(ref, number(item.get("row_version")));
            definitions.add((String) item.get("definition_id"));
        }
        long available = Objects.requireNonNull(jdbc.queryForObject("select count(*) from campaign_known_fact "
                + "where campaign_id = ? and actor_id = ?", Long.class, campaign, holder));
        var lore = new ArrayList<QuestPlanContext.Lore>();
        for (var fact : jdbc.queryForList("select id, fact_text, row_version from campaign_known_fact "
                + "where campaign_id = ? and actor_id = ? order by learned_revision desc, id limit ?", campaign, holder,
                FACTS)) {
            String ref = "known-fact:" + fact.get("id");
            entities.add(new PlanEntity(ref, label((String) fact.get("fact_text")), null, null, null, null));
            versions.put(ref, number(fact.get("row_version")));
            lore.add(new QuestPlanContext.Lore(ref, clip((String) fact.get("fact_text")), false));
        }
        if (entities.size() > QuestPlanContext.MAX_ENTITIES) {
            throw new IllegalStateException(TOO_LARGE);
        }
        var constraints = new ArrayList<>(new TreeSet<>(catalog.constrainedDefinitions()));
        var content = new TreeSet<>(constraints);
        definitions.stream().filter(catalog.definitions()::contains).forEach(content::add);
        if (constraints.size() > QuestPlanContext.MAX_CONSTRAINTS || content.size() > QuestPlanContext.MAX_CONTENT) {
            throw new IllegalStateException(TOO_LARGE);
        }
        // Manifest: one exact version per entity, plus the epoch of every actor's query scope.
        var manifest = new ArrayList<Dependency>();
        var contextDependencies = new ArrayList<QuestPlanContext.Dependency>();
        for (PlanEntity entity : entities) {
            Kind kind = entity.kind().startsWith("known-") ? Kind.FACT : Kind.ENTITY_FIELD;
            add(manifest, contextDependencies, Dependency.version(kind, entity.ref(), versions.get(entity.ref()),
                    Severity.ARTIFACT, "[]"), entity.ref(), false);
            if (entity.kind().equals("actor")) {
                add(manifest, contextDependencies, Dependency.query("actor-scope", null, Audience.PLANNER_PRIVATE,
                        entity.ref(), QueryEpochs.current(jdbc, campaign, entity.ref()),
                        sha256(JSON.writeValueAsString(entity)), Severity.ARTIFACT, "[]"), entity.ref(), true);
            }
        }
        var selected = new LinkedHashMap<String, Object>();
        selected.put("questRef", questRef);
        selected.put("entities", entities);
        selected.put("factsAvailable", available);
        selected.put("factsSelected", lore.size());
        selected.put("contentRefs", content);
        return new Slice(number(token.get("state_revision")), number(token.get("recovery_generation")),
                SupportedLocale.fromTag((String) token.get("locale")), number(token.get("lr")), number(token.get("gr")),
                (String) token.get("content_hash"), (String) questRow.get("title"), (String) questRow.get("objective"),
                List.copyOf(entities), List.copyOf(lore), List.copyOf(manifest), List.copyOf(contextDependencies),
                List.copyOf(content), List.copyOf(constraints), JSON.writeValueAsString(selected),
                JSON.writeValueAsString(lore.stream().map(QuestPlanContext.Lore::id).toList()));
    }

    /** Append one manifest entry and its planner-visible id. */
    private static void add(List<Dependency> manifest, List<QuestPlanContext.Dependency> visible, Dependency dependency,
            String reference, boolean query) {
        visible.add(new QuestPlanContext.Dependency("dep-" + manifest.size(), reference, query));
        manifest.add(dependency);
    }

    /** @return a bounded single-line label */
    private static String label(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= PlanEntity.MAX_LABEL ? line : line.substring(0, PlanEntity.MAX_LABEL);
    }

    /** @return text within the lore bound */
    private static String clip(String text) {
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    /** @return lowercase SHA-256 hex of UTF-8 text */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }

    /**
     * Backend-owned identity of the plan version an attempt produces.
     *
     * @param planId stable plan identity
     * @param version version to produce
     * @param predecessor plan identity of the predecessor, null for version 1
     * @param refinement stale and usable predecessor branches, null for version 1
     */
    public record PlanIdentity(UUID planId, int version, UUID predecessor, QuestPlanContext.Refinement refinement) {
    }

    /**
     * One captured slice.
     *
     * @param snapshot stored snapshot header
     * @param manifest the exact dependency manifest recorded with the snapshot
     * @param context planner context rendered from the same reads
     */
    public record Assembly(PlanningSnapshotService.Snapshot snapshot, List<Dependency> manifest,
            QuestPlanContext context) {
    }

    /** Everything read inside the transaction. */
    private record Slice(long revision, long generation, SupportedLocale locale, long localeRevision,
            long glossaryRevision, String packHash, String title, String objective, List<PlanEntity> entities,
            List<QuestPlanContext.Lore> lore, List<Dependency> manifest,
            List<QuestPlanContext.Dependency> contextDependencies, List<String> contentRefs, List<String> constraints,
            String selectedState, String sourceRefs) {
    }
}
