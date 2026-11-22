package org.rpgengine.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.assets.ManagedAssets.Status;
import org.rpgengine.assets.RuntimeImageJobs.Admission;
import org.rpgengine.assets.RuntimeImageJobs.Outcome;
import org.rpgengine.assets.RuntimeImageJobs.Request;
import org.rpgengine.assets.RuntimeImageJobs.Settings;
import org.rpgengine.assets.RuntimeImageJobs.Settled;
import org.rpgengine.assets.RuntimeImageJobs.Ticket;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.content.WorkspaceProjection;
import org.rpgengine.exports.CampaignExports;
import org.rpgengine.exports.CampaignRestores;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.admission.ImageAdmission;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of the bounded runtime image jobs (P17.6-A) over a scripted image port whose results the
 * test completes by hand: budget, queue and concurrency limits, deduplication against accepted and authored art and
 * between equal requests, and the stale-result checks a late image goes through before anything is stored. The
 * properties equal those of {@link ManagedAssetsTest}, so both share one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000061")
class RuntimeImageJobsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private ManagedAssets assets;
    @Autowired private CampaignExports exports;
    @Autowired private CampaignRestores restores;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private final ScriptedPort port = new ScriptedPort();
    private final MovingClock clock = new MovingClock();
    private UUID adventure;
    private UUID campaign;
    private UUID otherAdventure;
    private UUID otherCampaign;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    RuntimeImageJobsTest() { }

    /** Seal the sample twice and start one campaign in each adventure. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        adventure = workspace();
        campaign = campaign(adventure, seal(adventure));
        otherAdventure = workspace();
        otherCampaign = campaign(otherAdventure, seal(otherAdventure));
    }

    /** Remove disposable rows. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate runtime_image_job, asset_association, managed_asset, encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, relationship_change, faction_clock_change, learned_recipe, canon_correction, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * One subject and appearance costs one generation: an equal request while it runs joins the same job, the
     * accepted image is reused afterwards, and a subject the pack already illustrates is never generated. The port
     * receives the category framing, the public appearance text and a seed of the job identity, and nothing else.
     */
    @Test
    void generatesEachAppearanceOnceAndReusesSavedArt() {
        RuntimeImageJobs jobs = jobs(settings(4, 12, 1));
        Ticket first = jobs.request(portrait("mira", 1));
        assertEquals(Admission.QUEUED, first.admission());
        assertEquals(1, port.requests.size());
        ImageRequest sent = port.requests.getFirst();
        assertEquals("character portrait, head and shoulders, tall woman with a grey cloak", sent.prompt());
        assertEquals(RuntimeImageJobs.NEGATIVE_PROMPT, sent.negativePrompt());
        assertEquals(832, sent.width());
        assertEquals(1216, sent.height());
        assertEquals(RuntimeImageJobs.seed(portrait("mira", 1)), sent.seed());
        assertNull(sent.model());
        assertNull(sent.sampler());

        Ticket joined = jobs.request(portrait("mira", 1));
        assertEquals(Admission.COALESCED, joined.admission());
        assertEquals(first.job(), joined.job());
        assertEquals(1, port.requests.size(), "an equal request is not sent again");
        assertEquals(1, jobs.budget(campaign).charged(), "a coalesced request is not charged");

        byte[] image = port.complete(0, 10);
        Settled settled = settled(first);
        assertEquals(Outcome.ACCEPTED, settled.outcome());
        assertEquals(ManagedAssets.sha256(image), settled.current().sha256());
        assertEquals(RuntimeImageJobs.CREDIT + " (scripted)", settled.current().credit(),
                "the credit line names the backend that made the image");
        assertEquals(settled, settled(joined), "both tickets follow the same job");
        assertEquals(ManagedAssets.sha256(image),
                assets.current(campaign, AssetCategory.PORTRAIT, "mira").orElseThrow().sha256());

        // Asking again for the saved appearance, as a reload or a revisit does, generates nothing.
        Ticket again = jobs.request(portrait("mira", 1));
        assertEquals(Admission.REUSED, again.admission());
        assertEquals(ManagedAssets.sha256(image), again.current().sha256());
        assertNull(again.settled());
        // The sample binds authored art to item "sword": that is in force and is never replaced by a generation.
        Ticket authored = jobs.request(new Request(adventure, campaign, AssetCategory.ITEM, "sword", 1, "a plain sword"));
        assertEquals(Admission.REUSED, authored.admission());
        assertEquals(1, port.requests.size());
        assertEquals(1, count("select count(*) from asset_association"));

        // Equal names in another campaign are another subject: its own job, its own image.
        Ticket foreign = jobs.request(new Request(otherAdventure, otherCampaign, AssetCategory.PORTRAIT, "mira", 1,
                "tall woman with a grey cloak"));
        assertEquals(Admission.QUEUED, foreign.admission());
        assertEquals(2, port.requests.size());
        assertEquals(port.requests.get(0).prompt(), port.requests.get(1).prompt());
        assertTrue(port.requests.get(0).seed() != port.requests.get(1).seed(), "the seed follows the campaign identity");

        // Disabled generation, a category outside the three, a missing capability and a foreign scope never queue.
        assertEquals(Admission.DISABLED, jobs(new Settings(false, 4, 12, Duration.ofHours(1), 1, Duration.ofMinutes(5),
                Duration.ZERO)).request(portrait("oren", 1)).admission());
        assertEquals(Admission.DISABLED, jobs.request(new Request(adventure, campaign, AssetCategory.MAP, "ford", 1,
                "a river map")).admission());
        assertEquals(Admission.UNAVAILABLE, new RuntimeImageJobs(jdbc, manager, assets, null, ImageAdmission.direct(),
                settings(4, 12, 1), clock, null).request(portrait("oren", 1)).admission());
        assertThrows(IllegalArgumentException.class, () -> jobs.request(new Request(otherAdventure, campaign,
                AssetCategory.PORTRAIT, "oren", 1, "a sailor")));
        for (String appearance : new String[] {" ", "line\nbreak", "x".repeat(RuntimeImageJobs.MAX_APPEARANCE + 1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Request(adventure, campaign, AssetCategory.ITEM, "lantern", 1, appearance));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Request(adventure, campaign, AssetCategory.ITEM, "../lantern", 1, "a lantern"));
    }

    /**
     * One job per campaign and the global maximum are with the port at once; a campaign queues a bounded number more
     * and is charged a bounded number of generations per window. A failed generation keeps its charge, a job that ends
     * before it was sent gives it back, and the window rolls.
     */
    @Test
    void enforcesQueueConcurrencyAndBudgetLimits() {
        RuntimeImageJobs jobs = jobs(settings(2, 3, 1));
        Ticket running = jobs.request(portrait("a1", 1));
        Ticket second = jobs.request(portrait("a2", 1));
        Ticket third = jobs.request(portrait("a3", 1));
        assertEquals(List.of(Admission.QUEUED, Admission.QUEUED, Admission.QUEUED),
                List.of(running.admission(), second.admission(), third.admission()));
        assertEquals(1, port.requests.size(), "one job of a campaign at a time");
        assertEquals(Admission.QUEUE_FULL, jobs.request(portrait("a4", 1)).admission());
        // The global limit of one active job holds another campaign back too.
        Ticket other = jobs.request(new Request(otherAdventure, otherCampaign, AssetCategory.ITEM, "lantern", 1,
                "a brass lantern"));
        assertEquals(Admission.QUEUED, other.admission());
        assertEquals(1, port.requests.size());
        assertEquals(new RuntimeImageJobs.Budget(3, 3, 2, true), jobs.budget(campaign));

        // A failed generation reached the port, so its charge stays; the next queued job of the oldest order starts.
        port.fail(0, "image-server-busy");
        Settled failed = settled(running);
        assertEquals(Outcome.FAILED, failed.outcome());
        assertEquals("image-server-busy", failed.errorCode());
        assertEquals(2, port.requests.size());
        assertTrue(port.requests.get(1).prompt().endsWith("a2"));

        // A queued job cancelled before it was sent gives its charge back and never reaches the port.
        assertTrue(jobs.cancel(third.job()));
        assertEquals(Outcome.CANCELLED, settled(third).outcome());
        assertEquals(new RuntimeImageJobs.Budget(2, 3, 0, true), jobs.budget(campaign));
        assertEquals(Admission.QUEUED, jobs.request(portrait("a5", 1)).admission());
        assertEquals(Admission.BUDGET_EXHAUSTED, jobs.request(portrait("a6", 1)).admission());
        assertEquals(new RuntimeImageJobs.Budget(3, 3, 1, true), jobs.budget(campaign));
        assertEquals(new RuntimeImageJobs.Budget(1, 3, 1, false), jobs.budget(otherCampaign),
                "each campaign has its own budget");

        port.complete(1, 20);
        assertEquals(Outcome.ACCEPTED, settled(second).outcome());
        // The other campaign was queued before a5, so it is sent next; this campaign waits its turn.
        assertEquals(3, port.requests.size());
        assertTrue(port.requests.get(2).prompt().endsWith("a brass lantern"));
        assertEquals(Admission.BUDGET_EXHAUSTED, jobs.request(portrait("a6", 1)).admission(),
                "a completed job keeps its charge");

        // Once the window has passed, the earlier charges no longer count.
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertEquals(0, jobs.budget(campaign).charged());
        assertEquals(Admission.QUEUED, jobs.request(portrait("a6", 1)).admission());
        assertEquals(Admission.QUEUE_FULL, jobs.request(portrait("a7", 1)).admission());
        assertEquals(3, port.requests.size(), "nothing jumps the running job");
    }

    /**
     * A late image is judged against the campaign as it is on arrival: a newer accepted appearance, a newer scene
     * request, a restore and a cancel each keep it out of storage; a newer request supersedes a queued older one before
     * it costs anything; and an image that arrives after the running timeout is dropped.
     */
    @Test
    void discardsStaleAndLateResults() throws Exception {
        RuntimeImageJobs jobs = jobs(settings(4, 20, 1));
        // Appearance: another writer accepted appearance 2 while appearance 1 was generated.
        Ticket older = jobs.request(portrait("mira", 1));
        assertEquals(Status.ACCEPTED, publish(AssetCategory.PORTRAIT, "mira", 2, 0, png(30)));
        byte[] staleImage = port.complete(0, 31);
        Settled appearance = settled(older);
        assertEquals(Outcome.STALE_APPEARANCE, appearance.outcome());
        assertEquals(2, appearance.current().appearanceRevision());
        assertEquals(0, count("select count(*) from managed_asset where sha256 = '" + ManagedAssets.sha256(staleImage)
                + "'"), "stale bytes are not stored");

        // Supersession: appearance 1 of the lantern waits behind a running job and appearance 2 replaces it unsent.
        Ticket blocker = jobs.request(portrait("oren", 1));
        Ticket lantern1 = jobs.request(item("lantern", 1));
        Ticket lantern2 = jobs.request(item("lantern", 2));
        assertEquals(Outcome.SUPERSEDED, settled(lantern1).outcome());
        assertEquals(Admission.STALE_APPEARANCE, jobs.request(item("lantern", 1)).admission(),
                "an older appearance than a queued one is not queued");
        // Charged: mira (sent), oren (sent) and lantern 2 (queued); lantern 1 gave its charge back.
        assertEquals(3, jobs.budget(campaign).charged(), "the superseded job gave its charge back");
        port.complete(1, 40);
        assertEquals(Outcome.ACCEPTED, settled(blocker).outcome());
        assertEquals(3, port.requests.size(), "only appearance 2 of the lantern was sent");
        assertEquals(RuntimeImageJobs.seed(item("lantern", 2)), port.requests.get(2).seed());
        port.complete(2, 41);
        assertEquals(Outcome.ACCEPTED, settled(lantern2).outcome());
        assertEquals(2, assets.current(campaign, AssetCategory.ITEM, "lantern").orElseThrow().appearanceRevision());

        // Source: a scene requested before the campaign advanced cannot replace a scene requested after it.
        Ticket early = jobs.request(scene("ford", 2));
        advanceCampaign();
        long now = jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
        assertEquals(Status.ACCEPTED, publish(AssetCategory.SCENE, "ford", 1, now, png(50)));
        byte[] earlyScene = port.complete(3, 51);
        Settled source = settled(early);
        assertEquals(Outcome.STALE_SOURCE, source.outcome());
        assertEquals(1, source.current().appearanceRevision());

        // Generation: an installation restore advances the recovery generation while the job runs.
        Ticket beforeRestore = jobs.request(portrait("tamsin", 1));
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        byte[] restoredAway = port.complete(4, 60);
        assertEquals(Outcome.STALE_GENERATION, settled(beforeRestore).outcome());
        assertTrue(assets.current(campaign, AssetCategory.PORTRAIT, "tamsin").isEmpty());

        // Cancel: a running job is cancelled on the port and the image it still returns is dropped.
        Ticket cancelled = jobs.request(portrait("tamsin", 1));
        assertTrue(jobs.cancel(cancelled.job()));
        assertTrue(port.handles.get(5).cancelled);
        byte[] afterCancel = port.complete(5, 70);
        assertEquals(Outcome.CANCELLED, settled(cancelled).outcome());
        assertTrue(assets.current(campaign, AssetCategory.PORTRAIT, "tamsin").isEmpty());
        assertFalse(jobs.cancel(cancelled.job()), "an ended job cannot be cancelled");

        // Timeout: the job gives the port up, cancels it, and a later image changes nothing.
        RuntimeImageJobs quick = jobs(new Settings(true, 4, 20, Duration.ofHours(1), 1, Duration.ofMillis(200),
                Duration.ZERO));
        // Charges are rows, so this second instance counts the ones above too.
        int chargedBefore = quick.budget(campaign).charged();
        Ticket slow = quick.request(portrait("wren", 1));
        Settled timedOut = slow.settled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(Outcome.FAILED, timedOut.outcome());
        assertEquals(RuntimeImageJobs.TIMED_OUT, timedOut.errorCode());
        assertTrue(port.handles.get(6).cancelled);
        byte[] lateImage = port.complete(6, 80);
        assertTrue(assets.current(campaign, AssetCategory.PORTRAIT, "wren").isEmpty());
        assertEquals(chargedBefore + 1, quick.budget(campaign).charged(), "a timed-out generation keeps its charge");
        assertEquals(0, count("select count(*) from managed_asset where sha256 in ('" + ManagedAssets.sha256(restoredAway)
                + "', '" + ManagedAssets.sha256(afterCancel) + "', '" + ManagedAssets.sha256(lateImage) + "', '"
                + ManagedAssets.sha256(earlyScene) + "')"), "no refused image left bytes behind");
    }

    /**
     * Jobs and charges are rows (P17.6-D): a new process sees the same budget and open jobs, resumes the queued ones in
     * order, never sends again a job the previous process had sent, and drops an image whose row ended meanwhile; a
     * restore's new recovery generation ends every open row of the old one.
     */
    @Test
    void survivesRestartWithoutDuplicatingOrLosingResults() {
        RuntimeImageJobs before = jobs(settings(4, 3, 1));
        Ticket sent = before.request(portrait("p1", 1));
        Ticket waiting = before.request(portrait("p2", 1));
        Ticket last = before.request(portrait("p3", 1));
        assertEquals(Admission.QUEUED, last.admission());
        assertEquals(1, port.requests.size());
        // The process stops: its tickets end, its rows stay open for the next start.
        before.close();
        assertEquals(RuntimeImageJobs.SHUTDOWN, settled(waiting).errorCode());
        assertEquals(List.of("RUNNING", "QUEUED", "QUEUED"), statuses(campaign));

        RuntimeImageJobs after = jobs(settings(4, 3, 1));
        assertEquals(new RuntimeImageJobs.Budget(3, 3, 2, true), after.budget(campaign), "the budget is durable");
        Ticket equal = after.request(portrait("p2", 1));
        assertEquals(Admission.COALESCED, equal.admission());
        assertEquals(waiting.job(), equal.job(), "an equal request joins the saved job");
        assertEquals(Admission.BUDGET_EXHAUSTED, after.request(portrait("p4", 1)).admission(),
                "the window survived the restart");
        assertEquals(new RuntimeImageJobs.Recovery(2, 1, 0, 0), after.recover());
        assertEquals(List.of("FAILED", "RUNNING", "QUEUED"), statuses(campaign));
        assertEquals(RuntimeImageJobs.INTERRUPTED, column("error_code", sent.job()));
        assertNotNull(column("charged_at", sent.job()), "the interrupted generation keeps its charge");
        assertEquals(2, port.requests.size(), "the interrupted job is not sent again; the oldest queued one is");
        assertTrue(port.requests.get(1).prompt().endsWith("p2"));
        assertEquals(new RuntimeImageJobs.Recovery(0, 0, 0, 0), after.recover(), "a second pass changes nothing");

        // The interrupted job's image still arrives, after its process ended: nothing is stored.
        byte[] interrupted = port.complete(0, 90);
        assertTrue(assets.current(campaign, AssetCategory.PORTRAIT, "p1").isEmpty());
        assertEquals(0, count("select count(*) from managed_asset where sha256 = '" + ManagedAssets.sha256(interrupted)
                + "'"));
        byte[] accepted = port.complete(1, 91);
        assertEquals(ManagedAssets.sha256(accepted),
                assets.current(campaign, AssetCategory.PORTRAIT, "p2").orElseThrow().sha256());
        assertEquals(3, port.requests.size());
        assertEquals(1, port.requests.stream().filter(request -> request.prompt().endsWith("p2")).count(),
                "the resumed job was generated once");
        assertEquals(new RuntimeImageJobs.Budget(3, 3, 0, true), after.budget(campaign), "no charge was taken twice");

        // Provenance of the accepted image: what the adapter reported, the review, the credit; hashes, never prompt text.
        Map<String, Object> provenance = jdbc.queryForMap("select sha256, provenance->>'backend' as backend, "
                + "provenance->'review'->>'result' as review, provenance->'review'->>'method' as method, "
                + "provenance->>'promptSha256' as prompt, provenance->>'credit' as credit, provenance::text as text "
                + "from runtime_image_job where id = ?", waiting.job());
        assertEquals(ManagedAssets.sha256(accepted), provenance.get("sha256"));
        assertEquals("scripted", provenance.get("backend"));
        assertEquals("passed", provenance.get("review"));
        assertEquals(RuntimeImageJobs.REVIEW, provenance.get("method"));
        assertEquals(ManagedAssets.sha256(port.requests.get(1).prompt().getBytes(StandardCharsets.UTF_8)),
                provenance.get("prompt"));
        assertEquals(assets.current(campaign, AssetCategory.PORTRAIT, "p2").orElseThrow().credit(),
                provenance.get("credit"));
        assertFalse(((String) provenance.get("text")).contains("character portrait"));

        // A result of a process that still runs, after another instance recovered its row first, is dropped too.
        RuntimeImageJobs third = jobs(settings(4, 10, 1));
        assertEquals(new RuntimeImageJobs.Recovery(0, 1, 0, 0), third.recover());
        port.complete(2, 92);
        assertTrue(assets.current(campaign, AssetCategory.PORTRAIT, "p3").isEmpty(),
                "a result whose row ended elsewhere is never published");
        assertEquals(RuntimeImageJobs.INTERRUPTED, column("error_code", last.job()));

        // An installation restore advances the recovery generation: the next start ends every open row of the old one.
        Ticket running = third.request(portrait("p5", 1));
        Ticket queued = third.request(portrait("p6", 1));
        assertEquals(4, port.requests.size());
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        third.close();
        RuntimeImageJobs restored = jobs(settings(4, 10, 1));
        assertEquals(new RuntimeImageJobs.Recovery(0, 0, 2, 0), restored.recover());
        assertEquals("STALE_GENERATION", column("status", running.job()));
        assertNotNull(column("charged_at", running.job()), "the sent job keeps its charge");
        assertNull(column("charged_at", queued.job()), "the unsent job gives its charge back");
        assertEquals(4, port.requests.size(), "nothing of the old generation is sent");

        // Restarting with generation switched off cancels what waited and gives its charge back.
        restored.request(portrait("p7", 1));
        Ticket parked = restored.request(portrait("p8", 1));
        restored.close();
        assertEquals(new RuntimeImageJobs.Recovery(0, 1, 0, 1), jobs(new Settings(false, 4, 10, Duration.ofHours(1), 1,
                Duration.ofMinutes(5), Duration.ZERO)).recover());
        assertEquals("CANCELLED", column("status", parked.job()));
        assertNull(column("charged_at", parked.job()));
        assertEquals(0, count("select count(*) from runtime_image_job where status in ('QUEUED', 'RUNNING')"));
    }

    /**
     * Every completed image passes the acceptance review before anything is stored: a flat picture, provenance of
     * another prompt and pixels of another size are refused and keep their charge. The pinned pack's policy decides
     * per category whether anything is generated at all.
     */
    @Test
    void reviewsEachImageAndHonoursThePackPolicy() throws Exception {
        RuntimeImageJobs jobs = jobs(settings(4, 20, 1));
        Ticket blank = jobs.request(portrait("r1", 1));
        ImageRequest sent = port.requests.get(0);
        port.complete(0, ScriptedImages.completed(sent, ScriptedImages.uniform(sent.width(), sent.height())));
        assertEquals(new Settled(Outcome.FAILED, null, RuntimeImageJobs.REVIEW_REJECTED), settled(blank));

        Ticket other = jobs.request(portrait("r2", 1));
        sent = port.requests.get(1);
        byte[] picture = ScriptedImages.png(sent.width(), sent.height(), 5);
        port.complete(1, ImageResult.completed(picture, ScriptedImages.provenance(sent, picture,
                ManagedAssets.sha256("another prompt".getBytes(StandardCharsets.UTF_8)))));
        assertEquals(RuntimeImageJobs.REVIEW_REJECTED, settled(other).errorCode());

        Ticket small = jobs.request(portrait("r3", 1));
        sent = port.requests.get(2);
        port.complete(2, ScriptedImages.completed(sent, png(7)));
        assertEquals(RuntimeImageJobs.REVIEW_REJECTED, settled(small).errorCode());
        assertEquals(0, count("select count(*) from asset_association"));
        assertEquals(0, count("select count(*) from managed_asset"));
        assertEquals(3, jobs.budget(campaign).charged(), "a refused image was generated, so its charge stays");
        assertTrue(RuntimeImageJobs.review(sent, ScriptedImages.completed(sent,
                ScriptedImages.png(sent.width(), sent.height(), 1))).isEmpty(), "an honest picture passes");

        UUID policyAdventure = workspace();
        UUID policyCampaign = campaign(policyAdventure, seal(policyAdventure, Map.of("portrait", "catalogOnly",
                "item", "disabled")));
        assertEquals(Admission.DISABLED, jobs.request(new Request(policyAdventure, policyCampaign,
                AssetCategory.PORTRAIT, "mira", 1, "a tall woman")).admission());
        assertEquals(Admission.DISABLED, jobs.request(new Request(policyAdventure, policyCampaign, AssetCategory.ITEM,
                "lantern", 1, "a brass lantern")).admission());
        assertEquals(Admission.QUEUED, jobs.request(new Request(policyAdventure, policyCampaign, AssetCategory.SCENE,
                "ford", 1, "a shallow river ford")).admission(), "an undeclared category stays bounded");
        assertEquals(4, port.requests.size());
    }

    /**
     * QA-33 for generated images: two adventures generate a portrait for the same subject name. Each image is stored,
     * listed, served and written only in its own adventure's campaign folder, switching between them never mixes them,
     * and a full-owner export restored into a third adventure brings its own image along without its job rows.
     */
    @Test
    void keepsGeneratedImagesInTheirOwnAdventureAcrossSwitchingAndRestore(@TempDir Path root) throws Exception {
        WorkspaceProjection folders = new WorkspaceProjection(registry, jdbc, root.toString());
        RuntimeImageJobs jobs = new RuntimeImageJobs(jdbc, manager, assets, port, ImageAdmission.direct(),
                settings(4, 12, 1), clock, folders);
        Ticket here = jobs.request(portrait("mira", 1));
        Ticket there = jobs.request(new Request(otherAdventure, otherCampaign, AssetCategory.PORTRAIT, "mira", 1,
                "tall woman with a grey cloak"));
        // The first adventure's image arrives while the second one's job waits: it lands only in its own campaign.
        byte[] mine = port.complete(0, 101);
        byte[] theirs = port.complete(1, 102);
        assertEquals(Outcome.ACCEPTED, settled(here).outcome());
        assertEquals(Outcome.ACCEPTED, settled(there).outcome());
        String mineHash = ManagedAssets.sha256(mine);
        String theirHash = ManagedAssets.sha256(theirs);

        for (int pass = 0; pass < 2; pass++) {
            assertEquals(mineHash, portraitIn(adventure, campaign), "switching back shows the same image");
            assertEquals(theirHash, portraitIn(otherAdventure, otherCampaign));
        }
        assertTrue(assets.serve(OWNER, adventure, campaign, theirHash).isEmpty(), "another adventure's image is not served");
        assertTrue(assets.serve(OWNER, otherAdventure, otherCampaign, mineHash).isEmpty());
        assertTrue(assets.serve(OWNER, adventure, campaign, mineHash).isPresent());
        assertEquals(Map.of(generatedPath(adventure, campaign, mineHash), mineHash,
                generatedPath(otherAdventure, otherCampaign, theirHash), theirHash), generatedFiles(root));

        // A full-owner export restored into a third adventure carries its image and no job row.
        UUID third = workspace();
        seal(third);
        CampaignExports.Export export = exports.export(OWNER, adventure, campaign, CampaignExports.Audience.FULL_OWNER);
        assertEquals(1, JSON.readTree(export.text()).at("/tables/runtime_image_job").size());
        UUID copy = restores.restore(OWNER, third, export.document(), export.sha256(),
                CampaignRestores.Identities.FRESH).campaignId();
        assertEquals(mineHash, portraitIn(third, copy));
        assertEquals(theirHash, portraitIn(otherAdventure, otherCampaign), "the restore touched no other adventure");
        assertEquals(0, count("select count(*) from runtime_image_job where campaign_id = '" + copy + "'"),
                "a copy never resumes the source's jobs");
        // The next start writes the restored campaign's copy into its own folder; nothing else changes.
        folders.startup();
        assertEquals(Map.of(generatedPath(adventure, campaign, mineHash), mineHash,
                generatedPath(otherAdventure, otherCampaign, theirHash), theirHash,
                generatedPath(third, copy, mineHash), mineHash), generatedFiles(root));
        assertEquals(Admission.REUSED, jobs.request(new Request(third, copy, AssetCategory.PORTRAIT, "mira", 1,
                "tall woman with a grey cloak")).admission(), "the restored image is reused, not generated again");
        assertEquals(2, port.requests.size());
    }

    private String portraitIn(UUID scope, UUID playthrough) {
        return assets.artwork(OWNER, scope, playthrough).orElseThrow().stream()
                .filter(item -> item.category() == AssetCategory.PORTRAIT && item.subject().equals("mira"))
                .findFirst().orElseThrow().sha256();
    }

    private static String generatedPath(UUID scope, UUID playthrough, String hash) {
        return "adventures/" + scope + "/campaigns/" + playthrough + "/assets/generated/portraits/" + hash + ".png";
    }

    /** Every file under a campaigns folder of the root, relative path to the SHA-256 of its bytes. */
    private static Map<String, String> generatedFiles(Path root) throws Exception {
        var files = new java.util.TreeMap<String, String>();
        try (var walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isRegularFile(path) && relative.contains("/campaigns/")) {
                    files.put(relative, ManagedAssets.sha256(Files.readAllBytes(path)));
                }
            }
        }
        return files;
    }

    private List<String> statuses(UUID scope) {
        return jdbc.queryForList("select status from runtime_image_job where campaign_id = ? order by ordinal",
                String.class, scope);
    }

    private Object column(String name, UUID job) {
        return jdbc.queryForObject("select " + name + " from runtime_image_job where id = ?", Object.class, job);
    }

    private Status publish(AssetCategory category, String subject, long appearance, long source, byte[] bytes) {
        UUID pinned = jdbc.queryForObject("select content_revision_id from campaign where id = ?", UUID.class, campaign);
        return assets.publish(campaign, new ManagedAssets.Candidate(category, subject, appearance, source, pinned,
                "Original artwork, MIT", bytes)).status();
    }

    private void advanceCampaign() {
        var execution = executions.execute(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 0, new TypedCommand.Engage("ford-ambush", null).type(),
                new TypedCommand.Engage("ford-ambush", null).payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status());
    }

    private RuntimeImageJobs jobs(Settings settings) {
        return new RuntimeImageJobs(jdbc, manager, assets, port, ImageAdmission.direct(), settings, clock, null);
    }

    private static Settings settings(int queue, int budget, int active) {
        return new Settings(true, queue, budget, Duration.ofHours(1), active, Duration.ofMinutes(5), Duration.ZERO);
    }

    private Request portrait(String subject, long appearance) {
        return new Request(adventure, campaign, AssetCategory.PORTRAIT, subject, appearance,
                subject.equals("mira") ? "tall woman with a grey cloak" : subject);
    }

    private Request item(String subject, long appearance) {
        return new Request(adventure, campaign, AssetCategory.ITEM, subject, appearance, "a brass " + subject);
    }

    private Request scene(String subject, long appearance) {
        return new Request(adventure, campaign, AssetCategory.SCENE, subject, appearance, "a shallow river ford at dusk");
    }

    private static Settled settled(Ticket ticket) {
        assertNotNull(ticket.settled());
        CompletableFuture<Settled> future = ticket.settled().toCompletableFuture();
        assertTrue(future.isDone(), "the job has settled");
        return future.join();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    /** A valid PNG whose bytes, and so hash, depend on the shade. */
    private static byte[] png(int shade) {
        try {
            var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x010101 * shade);
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private UUID workspace() {
        return new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Lantern Ford", "fantasy", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
    }

    private UUID seal(UUID target) throws Exception {
        return seal(target, null);
    }

    /** Seal the sample, with the given {@code imageGeneration} policy in its presentation file when not null. */
    @SuppressWarnings("unchecked")
    private UUID seal(UUID target, Map<String, String> policy) throws Exception {
        var files = new java.util.TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(RuntimeImageJobsTest.class.getResource("/sample-adventure").toURI())).files());
        if (policy != null) {
            Map<String, Object> presentation = JSON.readValue(files.get("presentation.json"), Map.class);
            presentation.put("imageGeneration", policy);
            files.put("presentation.json", JSON.writeValueAsBytes(presentation));
        }
        return registry.register(target, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(files)))).revisionId();
    }

    private UUID campaign(UUID target, UUID sealed) {
        return creation.create(OWNER, target, new CampaignCreationService.Request(UUID.randomUUID(), sealed, "Ford watch",
                SupportedLocale.EN, new CharacterChoices("Player", "scout", Map.of(), Map.of(), null, null))).campaignId();
    }

    /** Image port whose jobs stay open until the test completes them; it records every request it receives. */
    private static final class ScriptedPort implements ImageGenerationPort {
        final List<ImageRequest> requests = new CopyOnWriteArrayList<>();
        final List<ScriptedHandle> handles = new CopyOnWriteArrayList<>();

        @Override
        public String backend() {
            return "scripted";
        }

        @Override
        public ImageHandle submit(ImageRequest request) {
            requests.add(request);
            var handle = new ScriptedHandle(request);
            handles.add(handle);
            return handle;
        }

        /** Complete one job with a picture of its requested size and honest provenance; returns the bytes. */
        byte[] complete(int index, int shade) {
            ScriptedHandle handle = handles.get(index);
            byte[] png = ScriptedImages.png(handle.request.width(), handle.request.height(), shade);
            handle.future.complete(ScriptedImages.completed(handle.request, png));
            return png;
        }

        void complete(int index, ImageResult result) {
            handles.get(index).future.complete(result);
        }

        void fail(int index, String code) {
            handles.get(index).future.complete(ImageResult.failed(code));
        }
    }

    /** Handle that records a cancel and, like a backend that finished anyway, can still be completed with an image. */
    private static final class ScriptedHandle implements ImageHandle {
        final ImageRequest request;
        final CompletableFuture<ImageResult> future = new CompletableFuture<>();
        volatile boolean cancelled;

        ScriptedHandle(ImageRequest request) {
            this.request = request;
        }

        @Override
        public CompletionStage<ImageResult> result() {
            return future;
        }

        @Override
        public boolean cancel() {
            cancelled = true;
            return !future.isDone();
        }
    }

    /** Clock the test moves forward by hand. */
    private static final class MovingClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));

        void advance(Duration step) {
            now.updateAndGet(instant -> instant.plus(step));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
