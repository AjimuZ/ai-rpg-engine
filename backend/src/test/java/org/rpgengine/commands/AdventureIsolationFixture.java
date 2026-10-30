package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TaskLedger;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;
import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.knowledge.KnowledgeChange.Acquisition;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentDisclosure;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentKind;
import org.rpgengine.knowledge.KnowledgeChange.Disclosure;
import org.rpgengine.knowledge.KnowledgeChange.Predicate;
import org.rpgengine.knowledge.KnowledgeChange.Stance;
import org.rpgengine.knowledge.KnowledgeChange.Truth;
import org.rpgengine.knowledge.KnowledgeView;
import org.rpgengine.memory.ContextAssembler;
import org.rpgengine.memory.ContextBudget;
import org.rpgengine.memory.ContinuitySummarizer;
import org.rpgengine.memory.MemorySettings;
import org.rpgengine.memory.MemorySummaries;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two-genre world of the QA-33 proofs at MVP scope: one owner, two adventures with equal titles,
 * and pack variants of the fantasy and science-fiction fixtures that declare the same NPC and item
 * identities and names with genre-specific meaning. Every row comes from production paths: real
 * registration and campaign creation, P10.2 player commands, P09 knowledge through the canonical commit
 * and summaries through the real summarizer behind a real scheduler. Only the runtime answering the
 * summary task is scripted. A failed setup step or a mixed read fails the calling test.
 *
 * <p>Generated images are out of scope (P17.6), and no browser takes part.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000033")
abstract class AdventureIsolationFixture {
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000033");
    static final String TITLE = "Same world";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "c".repeat(64);
    private static final String PACK = "a".repeat(64);
    private static final String POLICY = "d".repeat(64);
    private static final String SUSPENSION_CODE = "usage-limit";

    /** Genre-specific meaning behind the shared identities; each text exists in exactly one genre. */
    record Genre(String directory, String label, String location, String resource, String rarity, String role,
            String lantern, String secret, String rumor, String promise, String summary) {
        /** @return every text that must stay inside this genre's scope */
        List<String> texts() {
            return List.of(role, lantern, secret, rumor, promise, summary);
        }
    }

    static final Genre FANTASY = new Genre("fantasy-exploration", "fantasy", "trailhead", "vigor", "ordinary",
            "Trail keeper", "An oil lantern with a horn shutter.",
            "The warden hides the ford map under the waystone.",
            "The warden buried a spare key beneath the cairn.",
            "I will guide you across the ford before dusk.",
            "The traveller bought a lantern from the warden at the trailhead.");
    static final Genre SALVAGE = new Genre("science-fiction-salvage", "science-fiction", "dock", "charge", "standard",
            "Dock supervisor", "A sealed fusion lantern with a status diode.",
            "The warden spoofed the relay manifest at shift change.",
            "The warden keeps a spare override card in the airlock.",
            "I will unlock the cold reactor before the next shift.",
            "The technician handed a lantern to the warden at the service dock.");

    /**
     * Identities of one seeded scope.
     *
     * @param directory fixture directory naming the {@link Genre}
     * @param summary recorded public summary
     * @param pendingJob summary job left queued
     * @param queuedTask inference task left queued by a process that no longer exists
     * @param lantern the unique same-name item instance
     */
    record Scope(String directory, UUID adventure, UUID revision, UUID campaign, UUID player, UUID warden,
            UUID summary, UUID pendingJob, UUID queuedTask, UUID lantern) {
        /** @return this scope's genre */
        Genre genre() {
            return directory.equals(FANTASY.directory()) ? FANTASY : SALVAGE;
        }

        /** @return every identity that must stay inside this scope */
        List<UUID> identities() {
            return java.util.stream.Stream.of(adventure, revision, campaign, player, warden, lantern)
                    .filter(java.util.Objects::nonNull).toList();
        }
    }

    /** The two scopes. */
    record World(Scope fantasy, Scope salvage) {
        /** @return both scopes */
        List<Scope> scopes() {
            return List.of(fantasy, salvage);
        }

        /** @return the scope other than {@code scope} */
        Scope other(Scope scope) {
            return scope.equals(fantasy) ? salvage : fantasy;
        }
    }

    /** One process's admitted HTTP session; proofs never survive that process. */
    record Client(int port, String headers) {
    }

    /** Detached HTTP status and body. */
    record Reply(int status, String headers, String body) {
    }

    @LocalServerPort protected int serverPort;
    @Value("${engine.workspaces.root}") protected String workspacesRoot;
    @Autowired protected CommandExecutionService executions;
    @Autowired protected CanonicalCommitService commits;
    @Autowired protected MutationLeaseService leases;
    @Autowired protected CampaignCreationService creation;
    @Autowired protected PackRegistry registry;
    @Autowired protected TaskLedger ledger;
    @Autowired protected ContextAssembler assembler;
    @Autowired protected KnowledgeView knowledge;
    @Autowired protected EntityManager entities;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager manager;

    /** Construct the injected fixture; missing database infrastructure fails context startup. */
    AdventureIsolationFixture() { }

    // ---------------------------------------------------------------- seeding

    /**
     * Build both scopes and prove the isolation of commands, jobs and limits on the way. The salvage
     * scope acts first, so the fantasy purchase and equip can be compared against its stored rows.
     *
     * @return the identities of the seeded world, with one summary job and one inference task left queued
     */
    protected World seed() throws Exception {
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        UUID salvageAdventure = adventure(SALVAGE);
        UUID fantasyAdventure = adventure(FANTASY);
        Scope salvage = campaign(SALVAGE, salvageAdventure);
        Scope fantasy = campaign(FANTASY, fantasyAdventure);

        command(salvage, new TypedCommand.UseItem(item(salvage, salvage.player(), "purse"), List.of()));
        UUID salvageLantern = item(salvage, salvage.player(), "lantern");
        command(salvage, new TypedCommand.TransferItem(salvageLantern, salvage.warden(), 1));
        var untouched = scopedRows(salvage);

        // A purchase and an equip in one world: same definition identities, separate instances and ledgers.
        command(fantasy, new TypedCommand.UseItem(item(fantasy, fantasy.player(), "purse"), List.of()));
        UUID fantasyLantern = item(fantasy, fantasy.player(), "lantern");
        command(fantasy, new TypedCommand.TransferItem(fantasyLantern, fantasy.warden(), 1));
        command(fantasy, new TypedCommand.TradeItems(fantasy.warden(),
                List.of(new TypedCommand.TradeItems.Line(item(fantasy, fantasy.player(), "coin"), 8)),
                List.of(new TypedCommand.TradeItems.Line(fantasyLantern, 1))));
        command(fantasy, new TypedCommand.EquipItem(fantasyLantern));
        command(fantasy, new TypedCommand.UseItem(item(fantasy, fantasy.player(), "tonic"), List.of()));
        assertEquals(untouched, scopedRows(salvage), "commands of one adventure never touch another's rows");
        assertEquals(1, count("equipment_assignment"), "only the world that equipped the lantern wears it");
        assertEquals(12, held(fantasy, fantasy.player(), "coin"));
        assertEquals(20, held(salvage, salvage.player(), "coin"));
        assertEquals(1, held(salvage, salvage.warden(), "lantern"));

        // Another world's identities resolve to nothing, even for an item with an equal definition.
        refused(fantasy, new TypedCommand.EquipItem(salvageLantern), OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE);
        refused(fantasy, new TypedCommand.TransferItem(salvageLantern, fantasy.warden(), 1),
                OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE);
        refused(fantasy, new TypedCommand.TransferItem(item(fantasy, fantasy.player(), "kit"), salvage.warden(), 1),
                OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
        var foreign = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(OWNER, fantasy.adventure(),
                salvage.campaign(), envelope(salvage, new TypedCommand.Wait(60))));
        assertEquals(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE, foreign.kind());
        assertEquals(untouched, scopedRows(salvage), "refusals write nothing");

        Scope fantasyStocked = withLantern(fantasy, fantasyLantern);
        Scope salvageStocked = withLantern(salvage, salvageLantern);
        seedKnowledge(fantasyStocked);
        seedKnowledge(salvageStocked);
        Scope fantasySummarized = withSummary(fantasyStocked);
        Scope salvageSummarized = withSummary(salvageStocked);
        World world = new World(withPendingJob(fantasySummarized), withPendingJob(salvageSummarized));
        for (Scope scope : world.scopes()) {
            assertEquals(MemorySummaries.Request.Outcome.THROTTLED, boundedSummaries(2).request(scope.campaign(),
                    Audience.PRIVILEGED, true).outcome(), "both worlds' pending jobs fill the installation-wide bound");
        }
        assertEquals(0, count("presentation_event"), "no real turn produces cues yet, so this world proves nothing about them");
        return assertLimitsAreOwnerWide(world);
    }

    /** Commit one equal-title adventure of the owner; storage failure aborts setup. */
    private UUID adventure(Genre genre) {
        return new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, TITLE, genre.label(), SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
    }

    /** Register the genre's variant, create a campaign through production code and place the same-name warden. */
    private Scope campaign(Genre genre, UUID adventure) throws Exception {
        UUID revision = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(pack(genre))))).revisionId();
        var created = creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), revision,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        UUID warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(created.campaignId(), ActorKind.NPC, "warden", "Warden", genre.location(), 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
        return new Scope(genre.directory(), adventure, revision, created.campaignId(), created.actorId(), warden,
                null, null, null, null);
    }

    /** The bundled genre fixture with shared NPC and item identities and names; texts stay genre-specific. */
    private static Map<String, byte[]> pack(Genre genre) throws Exception {
        var files = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(AdventureIsolationFixture.class.getResource("/packs/" + genre.directory()).toURI())).files());
        // Drop localization: the shared vocabulary is not in the bundled translations.
        files.keySet().removeIf(name -> name.startsWith("locales/") || name.equals("text-resources.json"));
        var manifest = CommandExecutionTest.document(files, "adventure.json");
        var listed = new ArrayList<Map<String, Object>>();
        for (Object entry : (List<?>) manifest.get("files")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> file = (Map<String, Object>) entry;
            if (!List.of("localization", "textResources").contains(file.get("category"))) {
                listed.add(file);
            }
        }
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        manifest.remove("localizations");
        manifest.remove("textResources");
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));

        var rules = CommandExecutionTest.document(files, "rules.json");
        @SuppressWarnings("unchecked")
        var definitions = new ArrayList<>((List<Object>) rules.get("definitions"));
        definitions.add(Map.of("id", "rules.equipment", "kind", "rule", "data", Map.of("ruleType", "equipment", "hands", 2,
                "slots", List.of(Map.of("id", "off-hand", "name", "Off hand", "capacity", 1, "accepts", List.of("light"))))));
        rules.put("definitions", definitions);
        files.put("rules.json", JSON.writeValueAsBytes(rules));

        files.put("npcs.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", List.of(
                Map.of("id", "warden", "kind", "npc", "data", Map.of("name", "Warden", "role", genre.role(),
                        "location", genre.location()))))));
        files.put("items.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", List.of(
                item("kit", "Field Kit", "tool", genre, Map.of("maxStack", 1)),
                item("lantern", "Lantern", "tool", genre, Map.of("value", 8, "slot", "off-hand", "hands", 1,
                        "tags", List.of("light"))),
                item("coin", "Coin", "treasure", genre, Map.of("value", 1, "maxStack", 999)),
                item("tonic", "Tonic", "consumable", genre, Map.of("value", 10, "maxStack", 5, "use", Map.of(
                        "target", Map.of("policy", "self"), "effects", List.of(Map.of("type", "modifyResource",
                                "resource", genre.resource(), "amount", 1))))),
                item("purse", "Purse", "consumable", genre, Map.of("value", 6, "maxStack", 4, "use", Map.of(
                        "target", Map.of("policy", "self"), "effects", List.of(
                                Map.of("type", "createItem", "item", "coin", "quantity", 20),
                                Map.of("type", "createItem", "item", "lantern", "quantity", 1)))))))));

        var creationFile = CommandExecutionTest.document(files, "creation.json");
        @SuppressWarnings("unchecked")
        var profile = (Map<String, Object>) ((List<Map<String, Object>>) creationFile.get("definitions")).getFirst().get("data");
        profile.put("startingItems", List.of(Map.of("item", "kit", "quantity", 1), Map.of("item", "tonic", "quantity", 3),
                Map.of("item", "purse", "quantity", 1)));
        files.put("creation.json", JSON.writeValueAsBytes(creationFile));
        return files;
    }

    /** One shared item definition; only its description, appearance and rarity token differ by genre. */
    private static Map<String, Object> item(String id, String name, String type, Genre genre, Map<String, Object> extra) {
        var data = new LinkedHashMap<String, Object>(extra);
        data.put("name", name);
        data.put("description", id.equals("lantern") ? genre.lantern() : genre.label() + " " + name);
        data.put("itemType", type);
        data.put("rarity", genre.rarity());
        data.put("appearance", Map.of("id", id + ".look", "summary", genre.label() + " " + name + " look."));
        return Map.of("id", id, "kind", "item", "data", data);
    }

    /** Claims, a rumor the player heard, a secret only the warden knows and the warden's open promise. */
    private void seedKnowledge(Scope scope) {
        Genre genre = scope.genre();
        UUID rumor = UUID.randomUUID();
        UUID secret = UUID.randomUUID();
        internal(scope, 2, List.of(
                new KnowledgeChange.Claim(rumor, "actor:" + scope.warden(), Predicate.STATEMENT, null, null, null, genre.rumor(),
                        "en", Disclosure.HOLDERS, Truth.UNDETERMINED, null, 0),
                new KnowledgeChange.Claim(secret, "actor:" + scope.warden(), Predicate.STATEMENT, null, null, null, genre.secret(),
                        "en", Disclosure.HOLDERS, Truth.TRUE, null, 0),
                new KnowledgeChange.Belief(UUID.randomUUID(), scope.player(), rumor, Stance.RUMORED, Acquisition.TOLD,
                        scope.warden(), null, null, 1),
                new KnowledgeChange.Belief(UUID.randomUUID(), scope.warden(), secret, Stance.KNOWS, Acquisition.OBSERVED,
                        null, null, null, 1),
                new KnowledgeChange.Commitment(UUID.randomUUID(), CommitmentKind.PROMISE, scope.warden(), scope.player(),
                        "location:" + genre.location(), genre.promise(), "en", true, CommitmentDisclosure.PARTIES, null, 1)));
    }

    /** Commit a trusted internal operation carrying knowledge changes through the canonical commit. */
    private void internal(Scope scope, int events, List<KnowledgeChange> changes) {
        long base = revision(scope);
        UUID operation = new TransactionTemplate(manager).execute(status -> {
            var created = CampaignOperation.accept(scope.campaign(), UUID.randomUUID().toString(), HASH, "isolation-test",
                    base, Instant.now());
            created.transitionTo(OperationStatus.PLANNING, Instant.now());
            created.transitionTo(OperationStatus.VALIDATING, Instant.now());
            entities.persist(created);
            return created.getId();
        });
        long version = jdbc.queryForObject("select operation_version from operation_journal where id = ?", Long.class, operation);
        var grant = leases.acquire(scope.adventure(), scope.campaign(), operation, operation, Duration.ofSeconds(60));
        try {
            var list = new ArrayList<ResolvedCommit.Event>();
            for (int index = 0; index < events; index++) {
                list.add(new ResolvedCommit.Event(null, null, EventVisibility.INTERNAL,
                        new CampaignEvent.Body("test-knowledge", 1, 1, "{}")));
            }
            commits.commit(new ResolvedCommit(new ResolvedCommit.Context(scope.adventure(), scope.campaign(), operation,
                    scope.revision(), HASH, base, version, grant.token()),
                    new ResolvedCommit.WorldChange(false, 0, CampaignMode.EXPLORATION, null), List.of(), list,
                    List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), changes));
        } finally {
            leases.release(scope.adventure(), scope.campaign(), operation, grant.token());
        }
    }

    /** Record a summary through the real queue, scheduler and summarizer; the runtime sees this scope only. */
    private Scope withSummary(Scope scope) {
        var summaries = summaries();
        var port = new PlannerTaskPort();
        var scheduler = new InferenceScheduler(new ScopedSessions(port, 16), port, ledger, SchedulerSettings.defaults(),
                Clock.systemUTC(), () -> 0.5);
        scheduler.recover();
        var summarizer = new ContinuitySummarizer(jdbc, summaries, assembler, new ContractRunner(scheduler, Clock.systemUTC()));
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, summaries.request(scope.campaign(), Audience.PUBLIC, true).outcome());
        port.then(seen -> MemoryContinuityTest.summarized(seen, body -> body.put("summary", scope.genre().summary())));
        var processed = summarizer.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals(ContinuitySummarizer.Processed.Outcome.RECORDED, processed.outcome(), String.valueOf(processed.code()));
        Genre foreign = scope.genre().equals(FANTASY) ? SALVAGE : FANTASY;
        String supplied = port.seen.getFirst().context().toString();
        foreign.texts().forEach(text -> assertFalse(supplied.contains(text), "foreign text in a summary context: " + text));
        assertFalse(supplied.contains(scope.genre().secret()), "the public summarizer never receives a secret");
        return new Scope(scope.directory(), scope.adventure(), scope.revision(), scope.campaign(), scope.player(),
                scope.warden(), processed.summaryId(), null, null, scope.lantern());
    }

    /** Leave one public summary job queued over a newer public event. */
    private Scope withPendingJob(Scope scope) {
        command(scope, new TypedCommand.Wait(60));
        var requested = summaries().request(scope.campaign(), Audience.PUBLIC, true);
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, requested.outcome());
        return new Scope(scope.directory(), scope.adventure(), scope.revision(), scope.campaign(), scope.player(),
                scope.warden(), scope.summary(), requested.jobId(), null, scope.lantern());
    }

    /** @return the scope carrying its unique lantern instance */
    private static Scope withLantern(Scope scope, UUID lantern) {
        return new Scope(scope.directory(), scope.adventure(), scope.revision(), scope.campaign(), scope.player(),
                scope.warden(), null, null, null, lantern);
    }

    /**
     * Charged usage of both worlds fills one rolling limit, and one backoff suspends both. Only then
     * leave a queued task per scope, because every scheduler recovery abandons earlier queued rows.
     */
    private World assertLimitsAreOwnerWide(World world) {
        long background = usage();
        for (Scope scope : world.scopes()) {
            long own = jdbc.queryForObject("select coalesce(sum(charged_usage), 0) from inference_task where campaign_id = ?",
                    Long.class, scope.campaign());
            assertTrue(own > 0, "each world charged the shared ledger");
            // A task this small fits one world's usage, so a per-world ledger would have admitted it.
            assertTrue(backgroundTask(scope).estimatedUsage() <= own, "the limit below must depend on the other world");
        }
        assertLimit(world, background, InferenceScheduler.QUOTA_EXHAUSTED);
        Instant now = Instant.now();
        ledger.saveSuspension(new TaskLedger.Suspension(now.plus(Duration.ofHours(6)), SUSPENSION_CODE, 1), now);
        assertLimit(world, background, InferenceScheduler.SUSPENDED);
        var conflict = backgroundTask(world.fantasy().adventure(), world.salvage().campaign());
        assertEquals(TaskLedger.Admission.SCOPE_CONFLICT, ledger.admit(entry(conflict)));
        return new World(withQueuedTask(world.fantasy()), withQueuedTask(world.salvage()));
    }

    /** Admit a QUEUED row as a scheduler process that no longer exists would have left it. */
    protected Scope withQueuedTask(Scope scope) {
        var task = backgroundTask(scope);
        assertEquals(TaskLedger.Admission.ADMITTED, ledger.admit(entry(task)));
        return new Scope(scope.directory(), scope.adventure(), scope.revision(), scope.campaign(), scope.player(),
                scope.warden(), scope.summary(), scope.pendingJob(), task.taskId(), scope.lantern());
    }

    private static TaskLedger.Entry entry(ScheduledTask task) {
        Instant now = Instant.now();
        return new TaskLedger.Entry(task, UUID.randomUUID(), task.estimatedUsage(), now, now.plus(Duration.ofHours(1)));
    }

    /** Execute one player command at the current revision; it must commit. */
    private void command(Scope scope, TypedCommand command) {
        var execution = executions.execute(OWNER, scope.adventure(), scope.campaign(), envelope(scope, command));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
    }

    private void refused(Scope scope, TypedCommand command, OperationAdmissionFailure.Kind kind) {
        var failure = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(OWNER, scope.adventure(),
                scope.campaign(), envelope(scope, command)), command.toString());
        assertEquals(kind, failure.kind(), command.toString());
    }

    private OperationAdmissionService.Command envelope(Scope scope, TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision(scope), command.type(),
                command.payload());
    }

    // ---------------------------------------------------------------- isolation assertions

    /**
     * Read every public section of both scopes over real HTTP, assert that no read mixes them and that
     * crossing the scopes finds nothing.
     *
     * @return section name to exact response body, for byte comparison after a restart or restore
     */
    protected Map<String, String> assertReadsAreIsolated(World world, Client client) throws Exception {
        var reads = new TreeMap<String, String>();
        for (Scope scope : world.scopes()) {
            Scope other = world.other(scope);
            String query = "?adventureId=" + scope.adventure();
            String root = "/api/v1/campaigns/" + scope.campaign() + "/";
            for (String section : List.of("state", "inventory", "known-state", "quests", "abilities", "panels", "resume")) {
                var reply = get(client, root + section + query);
                assertEquals(200, reply.status(), section + ": " + reply.body());
                assertBelongsTo(scope, other, reply.body());
                assertFalse(reply.body().contains(scope.genre().secret()), "a public read exposes no secret");
                reads.put(scope.directory() + "/" + section, section.equals("resume") ? withoutContinuity(reply.body()) : reply.body());
                var crossed = get(client, "/api/v1/campaigns/" + scope.campaign() + "/" + section + "?adventureId="
                        + other.adventure());
                assertEquals(404, crossed.status(), "campaign requested through the other adventure: " + section);
            }
            var listing = get(client, "/api/v1/campaigns" + query);
            assertEquals(200, listing.status(), listing.body());
            assertEquals(1, JSON.readTree(listing.body()).get("campaigns").size());
            assertBelongsTo(scope, other, listing.body());
            reads.put(scope.directory() + "/listing", listing.body());
            var detail = get(client, "/api/v1/adventures/" + scope.adventure());
            assertEquals(200, detail.status(), detail.body());
            assertBelongsTo(scope, other, detail.body());
            reads.put(scope.directory() + "/adventure", detail.body());

            JsonNode inventory = JSON.readTree(reads.get(scope.directory() + "/inventory")).get("data");
            for (JsonNode item : inventory) {
                assertEquals(1, jdbc.queryForObject("select count(*) from item_instance where id = ? and campaign_id = ?",
                        Integer.class, UUID.fromString(item.get("itemId").asString()), scope.campaign()));
            }
            assertTrue(reads.get(scope.directory() + "/panels").contains(scope.genre().rumor()),
                    "the player's own rumor resolves in its own world");
        }
        var library = get(client, "/api/v1/adventures");
        assertEquals(200, library.status(), library.body());
        JsonNode adventures = JSON.readTree(library.body()).get("adventures");
        assertEquals(2, adventures.size());
        adventures.forEach(card -> assertEquals(TITLE, card.get("title").asString()));
        reads.put("library", library.body());

        JsonNode fantasyPanels = JSON.readTree(reads.get(FANTASY.directory() + "/panels"));
        JsonNode salvagePanels = JSON.readTree(reads.get(SALVAGE.directory() + "/panels"));
        assertEquals(List.of("coin", "kit", "lantern", "tonic"), definitions(fantasyPanels));
        assertEquals(List.of("coin", "kit", "tonic"), definitions(salvagePanels));
        assertTrue(fantasyPanels.toString().contains(FANTASY.rarity()) && salvagePanels.toString().contains(SALVAGE.rarity()));
        assertEquals(java.util.Set.of("Lantern"), lanternNames(fantasyPanels));
        return reads;
    }

    /**
     * The resume card without its continuity codes. Those are diagnostics of derived data that startup
     * work legitimately changes, so {@link #assertContinuity} states them for each stage instead.
     */
    private static String withoutContinuity(String resume) {
        var card = (tools.jackson.databind.node.ObjectNode) JSON.readTree(resume);
        assertNotNull(card.remove("continuity"), "the resume card reports continuity");
        return card.toString();
    }

    /** Both worlds' resume cards report exactly these continuity codes. */
    protected void assertContinuity(World world, Client client, String checkpoint, String publicSummary) throws Exception {
        for (Scope scope : world.scopes()) {
            var reply = get(client, "/api/v1/campaigns/" + scope.campaign() + "/resume?adventureId=" + scope.adventure());
            assertEquals(200, reply.status(), reply.body());
            JsonNode continuity = JSON.readTree(reply.body()).get("continuity");
            assertEquals(checkpoint, continuity.get("checkpoint").asString(), scope.directory());
            assertEquals(publicSummary, continuity.get("publicSummary").asString(), scope.directory());
        }
    }

    /** Startup captures one checkpoint per campaign, holding that campaign's state and nothing of the other's. */
    protected void assertCheckpointsAreIsolated(World world) {
        for (Scope scope : world.scopes()) {
            var snapshots = jdbc.queryForList("select snapshot::text from campaign_checkpoint where campaign_id = ?",
                    String.class, scope.campaign());
            assertEquals(1, snapshots.size(), scope.directory());
            assertTrue(snapshots.getFirst().contains(scope.player().toString()));
            assertBelongsTo(scope, world.other(scope), snapshots.getFirst());
        }
    }

    /** Compare recorded reads one section at a time, so a failure names the section that changed. */
    protected static void assertSameReads(Map<String, String> expected, Map<String, String> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((section, body) -> assertEquals(body, actual.get(section), section));
    }

    /** @return sorted item definitions a panels reply lists for the player */
    private static List<String> definitions(JsonNode panels) {
        var found = new java.util.TreeSet<String>();
        panels.findValues("definitionId").forEach(value -> found.add(value.asString()));
        return List.copyOf(found);
    }

    private static java.util.Set<String> lanternNames(JsonNode panels) {
        var names = new java.util.TreeSet<String>();
        panels.findParents("definitionId").stream().filter(item -> item.get("definitionId").asString().equals("lantern"))
                .forEach(item -> names.add(item.get("name").get("text").asString()));
        return names;
    }

    /** Assemble and read memory through the real services; each world sees its own records and none of the other's. */
    protected void assertMemoryIsIsolated(World world) {
        for (Scope scope : world.scopes()) {
            Genre genre = scope.genre();
            Scope other = world.other(scope);
            String open = envelope(scope, AiRole.NARRATOR, Audience.PUBLIC);
            assertTrue(open.contains(genre.rumor()) || open.contains(genre.promise()), "public memory of its own world");
            assertFalse(open.contains(genre.secret()), "the public envelope excludes the secret");
            assertBelongsTo(scope, other, open);
            String privileged = envelope(scope, AiRole.QUEST_PLANNER, Audience.PRIVILEGED);
            assertTrue(privileged.contains(genre.secret()), "the privileged envelope holds its own secret");
            assertBelongsTo(scope, other, privileged);
            String truth = knowledge.privileged(scope.campaign()).toString();
            assertTrue(truth.contains(genre.secret()) && truth.contains(genre.rumor()) && truth.contains(genre.promise()));
            assertBelongsTo(scope, other, truth);
            String held = knowledge.forActor(scope.campaign(), scope.player()).toString();
            assertTrue(held.contains(genre.rumor()) && !held.contains(genre.secret()));
            assertBelongsTo(scope, other, held);
            String stored = jdbc.queryForObject("select string_agg(body::text, ' ') from memory_summary where campaign_id = ?",
                    String.class, scope.campaign());
            assertTrue(stored.contains(genre.summary()));
            assertBelongsTo(scope, other, stored);
            assertEquals(1, jdbc.queryForObject("select count(*) from memory_summary where campaign_id = ?", Integer.class,
                    scope.campaign()));
        }
    }

    private String envelope(Scope scope, AiRole role, Audience audience) {
        return assertInstanceOf(ContextAssembler.Assembled.class, assembler.assemble(new ContextAssembler.Request(
                scope.adventure(), scope.campaign(), role, audience, null, List.of(), null, 1_000,
                ContextBudget.standard(audience.kind() == Audience.Kind.PRIVILEGED), POLICY))).envelope().json();
    }

    /** A text of {@code scope} names none of the other scope's identities and none of its genre's texts. */
    protected static void assertBelongsTo(Scope scope, Scope other, String text) {
        other.identities().forEach(identity ->
                assertFalse(text.contains(identity.toString()), "foreign identity " + identity + " in: " + text));
        other.genre().texts().forEach(foreign ->
                assertFalse(text.contains(foreign), "foreign text in " + scope.directory() + ": " + foreign));
    }

    /**
     * Assert the owner-wide limits a restored or restarted world keeps: the usage total, the backoff,
     * and with the backoff lifted the rolling quota, each for both scopes.
     *
     * @param background limit set to the recorded background usage
     */
    protected void assertLimitsSurvive(World world, long background) {
        assertEquals(background, usage(), "rolling usage survives");
        var suspension = ledger.suspension();
        assertEquals(SUSPENSION_CODE, suspension.code());
        assertTrue(suspension.until().isAfter(Instant.now()), "the backoff is still in force");
        assertLimit(world, background, InferenceScheduler.SUSPENDED);
        ledger.saveSuspension(TaskLedger.Suspension.NONE, Instant.now());
        assertLimit(world, background, InferenceScheduler.QUOTA_EXHAUSTED);
    }

    /** A fresh scheduler process over the shared ledger refuses background work of both worlds with one code. */
    private void assertLimit(World world, long background, String code) {
        var port = new PlannerTaskPort();
        var defaults = SchedulerSettings.defaults();
        var scheduler = new InferenceScheduler(new ScopedSessions(port, 16), port, ledger, new SchedulerSettings(1,
                defaults.interactiveCapacity(), defaults.interactivePerCampaign(), defaults.backgroundCapacity(),
                defaults.backgroundPerCampaign(), defaults.dailyUsageLimit(), background, defaults.agingStep(),
                defaults.backoffInitial(), defaults.backoffMax(), defaults.tick()), Clock.systemUTC(), () -> 0.5);
        scheduler.recover();
        for (Scope scope : world.scopes()) {
            var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> scheduler.submit(backgroundTask(scope))
                    .result().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(code, result.errorCode(), scope.directory());
        }
        assertTrue(port.seen.isEmpty(), "a refused task reaches no runtime");
    }

    /** @return charged background usage in the rolling window, across every adventure of the installation */
    protected long usage() {
        return ledger.usageSince(Instant.now().minus(Duration.ofHours(23)), true);
    }

    private ScheduledTask backgroundTask(Scope scope) {
        return backgroundTask(scope.adventure(), scope.campaign());
    }

    /** A minimal background task: a light slot and short texts keep its estimate below one recorded summary. */
    private ScheduledTask backgroundTask(UUID adventure, UUID campaign) {
        long generation = jdbc.queryForObject("select recovery_generation from campaign where id = ?", Long.class, campaign);
        return new ScheduledTask(UUID.randomUUID(), new SessionScope(adventure, campaign, generation, AiRole.QUEST_PLANNER,
                Audience.PRIVILEGED, PACK, POLICY), WorkClass.PREPARATION, InferenceSlot.LIGHT, "Task.", "{\"type\":\"object\"}",
                Duration.ofSeconds(60));
    }

    /** @return summaries over the real storage, switched on for this suite only */
    protected MemorySummaries summaries() {
        return boundedSummaries(MemorySettings.defaults().maxPending());
    }

    /** @return summaries with an installation-wide bound on unfinished jobs */
    protected MemorySummaries boundedSummaries(int bound) {
        return new MemorySummaries(jdbc, manager, MemorySettings.defaults().withSummaries(true).withMaxPending(bound),
                Clock.systemUTC());
    }

    // ---------------------------------------------------------------- folders

    /**
     * Inspect the managed folders of a root. Each adventure holds exactly its own revision; authored bytes
     * equal its own genre's fixture and differ from the other's, under equal relative paths.
     *
     * @return relative path to SHA-256 for every file under the root, proving nothing else was written
     */
    protected Map<String, String> assertFoldersAreIsolated(World world, Path root) throws Exception {
        for (Scope scope : world.scopes()) {
            Scope other = world.other(scope);
            Path adventure = root.resolve("adventures/" + scope.adventure());
            try (var revisions = Files.list(adventure.resolve("content-revisions"))) {
                assertEquals(List.of(scope.revision().toString()), revisions.map(path -> path.getFileName().toString()).toList());
            }
            Path revision = adventure.resolve("content-revisions/" + scope.revision());
            Path fixture = Path.of(getClass().getResource("/packs/" + scope.directory()).toURI());
            Path otherFixture = Path.of(getClass().getResource("/packs/" + other.directory()).toURI());
            for (var original : Map.of("specification/lore.md", "lore.md", "assets/authored/assets/map.png", "assets/map.png").entrySet()) {
                byte[] stored = Files.readAllBytes(revision.resolve(original.getKey()));
                assertArrayEquals(Files.readAllBytes(fixture.resolve(original.getValue())), stored, original.getKey());
                assertFalse(java.util.Arrays.equals(Files.readAllBytes(otherFixture.resolve(original.getValue())), stored),
                        "the genres must differ for this comparison to prove anything: " + original.getKey());
            }
            String specification = Files.readString(revision.resolve("specification/items.json"));
            assertTrue(specification.contains(scope.genre().lantern()) && specification.contains("\"Lantern\""));
            assertBelongsTo(scope, other, specification);
            assertTrue(Files.isRegularFile(revision.resolve("integrity.json")));
        }
        try (var adventures = Files.list(root.resolve("adventures"))) {
            assertEquals(2, adventures.count());
        }
        var files = new TreeMap<String, String>();
        try (var walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    files.put(root.relativize(path).toString().replace('\\', '/'), HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                }
            }
        }
        assertFalse(files.isEmpty());
        return files;
    }

    // ---------------------------------------------------------------- storage and rows

    /** @return every row of this scope, in every table holding a campaign identity, as canonical text */
    protected Map<String, List<String>> scopedRows(Scope scope) {
        var rows = new TreeMap<String, List<String>>();
        rows.put("campaign", jdbc.queryForList("select to_jsonb(t)::text from campaign t where id = ?", String.class, scope.campaign()));
        for (String table : jdbc.queryForList("select table_name from information_schema.columns where table_schema = 'public' "
                + "and column_name = 'campaign_id' order by 1", String.class)) {
            rows.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t where campaign_id = ? order by 1",
                    String.class, scope.campaign()));
        }
        return rows;
    }

    /** @return every row of every public table except the named ones, as canonical text */
    protected Map<String, List<String>> allRows(java.util.Set<String> except) {
        var rows = new TreeMap<String, List<String>>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname = 'public' order by 1", String.class)) {
            if (!except.contains(table)) {
                rows.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
            }
        }
        return rows;
    }

    protected long revision(Scope scope) {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, scope.campaign());
    }

    protected int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private UUID item(Scope scope, UUID actor, String definition) {
        return jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? and definition_id = ? "
                + "order by id limit 1", UUID.class, scope.campaign(), actor, definition);
    }

    private int held(Scope scope, UUID actor, String definition) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where campaign_id = ? and actor_id = ? "
                + "and definition_id = ?", Integer.class, scope.campaign(), actor, definition);
    }

    // ---------------------------------------------------------------- processes and HTTP

    /**
     * Start the real application entry point in a separate JVM, with startup memory reconciliation on as
     * in production. Runs {@code action} with an admitted client of that process, then stops it.
     *
     * @param root empty managed-folder root the new process must rebuild
     * @param action what to do while the process serves requests
     */
    protected void withRestartedProcess(Path root, ProcessAction action) throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Path log = Path.of("target", "p065-isolation-restart.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "org.rpgengine.EngineApplication",
                "--server.port=" + port, "--engine.http.owner-id=" + OWNER, "--engine.workspaces.root=" + root,
                "--engine.memory.reconciliation.enabled=true", "--spring.main.banner-mode=off", "--logging.level.root=WARN")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            boolean ready = false;
            while (!ready && process.isAlive() && System.nanoTime() < deadline) {
                try {
                    ready = exchange(port, "GET", "/actuator/health/readiness", "").status() == 200;
                } catch (java.io.IOException unavailable) {
                    // Startup has not yet bound its loopback listener; never treat this as readiness.
                }
                if (!ready) {
                    Thread.sleep(100);
                }
            }
            assertTrue(ready, () -> "restart readiness failed; inspect " + log);
            action.run(client(port));
        } finally {
            // Only this fixture's process may be stopped; never leave a test server running.
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                    fail("restarted process did not stop gracefully");
                }
            }
        }
    }

    /** Work against one running process. */
    protected interface ProcessAction {
        /** @param client admitted client of that process */
        void run(Client client) throws Exception;
    }

    /** Bootstrap a browser-shaped session at the given port. */
    protected Client client(int port) throws Exception {
        var session = exchange(port, "GET", "/api/v1/session", "");
        assertEquals(200, session.status(), session.body());
        String csrf = JSON.readTree(session.body()).get("csrfToken").asString();
        String cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:")).findFirst()
                .orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
        return new Client(port, "Origin: http://127.0.0.1:8080\r\nCookie: " + cookie + "\r\nX-CSRF-Token: " + csrf + "\r\n");
    }

    private static Reply get(Client client, String path) throws Exception {
        return exchange(client.port(), "GET", path, client.headers());
    }

    /** A bounded loopback exchange with an explicit Host, as the local admission filter requires. */
    private static Reply exchange(int port, String method, String path, String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: close\r\n"
                    + headers + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            // Chunk sizes count octets, so keep a one-to-one byte mapping until framing is removed.
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String[] parts = response.split("\r\n\r\n", 2);
            String body = parts.length > 1 ? parts[1] : "";
            if (parts[0].toLowerCase().contains("transfer-encoding: chunked")) {
                var decoded = new StringBuilder();
                int offset = 0;
                while (true) {
                    int end = body.indexOf("\r\n", offset);
                    int length = Integer.parseInt(body.substring(offset, end), 16);
                    if (length == 0) {
                        break;
                    }
                    decoded.append(body, end + 2, end + 2 + length);
                    offset = end + 2 + length + 2;
                }
                body = decoded.toString();
            }
            return new Reply(Integer.parseInt(parts[0].split(" ", 3)[1]), parts[0],
                    new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
        }
    }
}
