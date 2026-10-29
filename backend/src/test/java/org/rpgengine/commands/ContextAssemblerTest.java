package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.contract.GlossaryTerm;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TaskLedger;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.knowledge.KnowledgeChange.Acquisition;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentDisclosure;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentKind;
import org.rpgengine.knowledge.KnowledgeChange.Disclosure;
import org.rpgengine.knowledge.KnowledgeChange.Predicate;
import org.rpgengine.knowledge.KnowledgeChange.Stance;
import org.rpgengine.knowledge.KnowledgeChange.Truth;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.rpgengine.memory.ContextAssembler;
import org.rpgengine.memory.ContextBudget;
import org.rpgengine.memory.ContextCosts;
import org.rpgengine.memory.ContextEnvelope;
import org.rpgengine.memory.ContextItem;
import org.rpgengine.memory.ContextItem.Section;
import org.rpgengine.memory.ContextItem.Standing;
import org.rpgengine.memory.ContextItem.Tier;
import org.rpgengine.planning.PlanningSettings;
import org.rpgengine.planning.QuestPlanAssembler;
import org.rpgengine.planning.QuestPlanning;
import org.rpgengine.planning.QuestPlanning.Processed;
import org.rpgengine.preparation.PlanAdmissionService;
import org.rpgengine.preparation.PreparationJobService;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of P09.2 context assembly (docs/08 sections 5 and 6, ADR 0005, ADR 0007,
 * docs/29 sections 4 and 5) over a campaign created by production code from sealed registered
 * packs. Knowledge is written by trusted internal operations through the real canonical commit,
 * history by ordinary player commands, and the private plan by the real planning pipeline with
 * only the planner runtime scripted ({@link PlannerTaskPort}).
 *
 * <p>The suite proves that a limited audience receives beliefs and common knowledge without truth,
 * never another holder's secrets, assumptions or real locations; that a privileged context labels
 * truth, beliefs and private planning assumptions as distinct standings; that mandatory material
 * overflows as a controlled result at the exact token boundary while optional history is dropped
 * and counted; that explicit IDs and per-language full-text search reach history beyond the recent
 * window; that glossary terms bind to the output locale; and that the stamp detects later changes.
 */
@SpringBootTest
class ContextAssemblerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "c".repeat(64);
    private static final String POLICY = "d".repeat(64);
    private static final String SECRET = "A surveyor moved the boundary stones at night.";
    private static final String HELD = "The warden hides a key under the waystone.";
    private static final String FOREIGN_TERMS = "Report every traveller who crosses the ford.";
    private static final String PROMISE = "I will show you the hidden ford before dusk.";
    private static final String ASSUMPTION = "The warden probably watched the surveyor from the shelter.";
    @Autowired private CommandExecutionService executions;
    @Autowired private CanonicalCommitService commits;
    @Autowired private MutationLeaseService leases;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private PreparationJobService jobs;
    @Autowired private PlanAdmissionService admissions;
    @Autowired private QuestPlanAssembler planAssembler;
    @Autowired private TaskLedger ledger;
    @Autowired private ContextAssembler assembler;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID revision;
    private UUID campaign;
    private UUID player;
    private UUID warden;
    private UUID hermit;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    ContextAssemblerTest() { }

    /**
     * Register the dependency and root packs (with a glossary for the trailhead and the grove),
     * then create an English campaign through the production creation service.
     */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Context", "context", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        var dependency = register(CommandExecutionTest.dependencyPack(fixture), "test:dep", List.of());
        revision = register(rootPack(fixture), "test:context", List.of(CommandExecutionTest.pin(dependency)))
                .revisionId();
        campaign(SupportedLocale.EN);
    }

    /** Remove disposable rows, preserving production constraints and triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
                + "narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, "
                + "campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, "
                + "quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, "
                + "campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, "
                + "campaign_checkpoint, reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * A public context carries the player's belief with its stance but no truth, common knowledge,
     * the mandatory promise and present warden, and every item's standing label; secrets the
     * player never learned and other parties' commitments are absent and become the disclosure
     * guard's withheld phrases instead. The warden's own context holds what the warden knows and
     * no player history.
     */
    @Test
    void limitedAudiencesReceiveLabelledBeliefsWithoutTruthOrOtherHoldersSecrets() {
        seedKnowledge();
        execute(new TypedCommand.Wait(60));
        var envelope = assembled(request(AiRole.NARRATOR, Audience.PUBLIC, List.of(), "keys waystone",
                ContextBudget.standard(false)));
        String json = envelope.json();
        assertFalse(json.contains(SECRET), "a secret the player never learned is filtered before retrieval");
        assertFalse(json.contains(FOREIGN_TERMS), "another pair's commitment is not the player's");
        assertFalse(json.contains("\"truth\""));
        assertFalse(json.contains("undetermined"));
        assertFalse(json.contains("realLocation"));
        assertFalse(json.contains("assumption"));

        var belief = only(envelope, Standing.BELIEF);
        assertEquals(Section.KNOWLEDGE, belief.section());
        assertEquals(Tier.TEXT, belief.tier(), "the English statement matched the stemmed query");
        assertEquals(HELD, belief.fields().get("statement"));
        assertEquals("rumored", belief.fields().get("stance"));
        assertEquals("actor:" + warden, belief.fields().get("informant"));
        assertEquals("en", belief.language());
        assertEquals("wary", ((tools.jackson.databind.JsonNode) only(envelope, Standing.COMMON_KNOWLEDGE).fields()
                .get("value")).asString());
        var promise = only(envelope, Standing.OBLIGATION);
        assertTrue(promise.mandatory(), "an open commitment the audience is party to is mandatory");
        assertEquals(PROMISE, promise.fields().get("terms"));
        var present = envelope.items().get(Section.STATE).stream()
                .filter(item -> item.ref().equals("sighting:" + warden)).findFirst().orElseThrow();
        assertTrue(present.mandatory(), "an actor the audience sees here is mandatory state");
        assertEquals(Standing.LAST_KNOWN, present.standing());
        assertTrue(envelope.items().get(Section.STATE).stream().anyMatch(item -> item.ref().equals("actor:" + player)
                && item.mandatory()));
        assertTrue(json.contains("\"belief\":\"What this audience holds with the given stance; it may be wrong.\""));
        assertEquals(List.of(), envelope.items().get(Section.PLANNING));

        var stamp = envelope.stamp();
        assertEquals(player, stamp.holderId());
        assertEquals(revision(), stamp.stateRevision());
        assertEquals(SupportedLocale.EN, stamp.locale());
        assertEquals(POLICY, stamp.policyHash());
        assertTrue(stamp.epochs().containsKey(KnowledgeLedger.knowledgeScope(player)));
        assertTrue(stamp.epochs().containsKey(KnowledgeLedger.commitmentScope(player)));
        assertTrue(stamp.epochs().get(KnowledgeLedger.CLAIMS_SCOPE) > 0);

        var withheld = assembler.withheldPhrases(campaign, player);
        assertTrue(withheld.contains(SECRET));
        assertTrue(withheld.contains(FOREIGN_TERMS));
        assertFalse(withheld.contains(HELD), "a claim the player holds a belief about is not withheld from it");
        withheld.forEach(phrase -> assertFalse(json.contains(phrase)));

        var wardenView = assembled(request(AiRole.GUIDE, Audience.character("actor:" + warden), List.of(), null,
                ContextBudget.standard(false)));
        var known = only(wardenView, Standing.BELIEF);
        assertEquals(SECRET, known.fields().get("statement"));
        assertEquals("knows", known.fields().get("stance"));
        assertFalse(wardenView.json().contains(HELD), "the player's belief is not the warden's");
        assertEquals(List.of(), wardenView.items().get(Section.HISTORY), "no character-side history exists yet");
        assertTrue(wardenView.json().contains(FOREIGN_TERMS), "the warden is party to that obligation");
        assertThrows(IllegalArgumentException.class, () -> request(AiRole.NARRATOR, Audience.PRIVILEGED, List.of(),
                null, ContextBudget.standard(true)));
    }

    /**
     * A privileged planner context labels admitted facts with truth, every holder's beliefs and
     * the real location beside the last known one, and the plan's private assumption as a
     * distinct, fresh {@code assumption}; a public context of the same state holds none of them,
     * and a plan of a retired recovery generation contributes no assumption.
     */
    @Test
    void privilegedContextsKeepFactsBeliefsAndPrivateAssumptionsApart() {
        seedKnowledge();
        var planning = planning();
        assertEquals(QuestPlanning.Request.Outcome.REQUESTED, planning.request(adventure, campaign, quest()).outcome());
        port.then(ContextAssemblerTest::plannedWithAssumption);
        Processed processed = planning.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals(Processed.Outcome.ADMITTED, processed.outcome(), String.valueOf(processed.code()));

        var envelope = assembled(request(AiRole.QUEST_PLANNER, Audience.PRIVILEGED, List.of(), null,
                ContextBudget.standard(true)));
        var assumption = only(envelope, Standing.ASSUMPTION);
        assertEquals(Section.PLANNING, assumption.section());
        assertEquals(ASSUMPTION, assumption.fields().get("note"));
        assertEquals(true, assumption.fields().get("fresh"));
        assertEquals("validated", assumption.fields().get("planStatus"));
        assertEquals(List.of("interview"), assumption.fields().get("branchRefs"));
        assertFalse(Standing.ASSUMPTION.admitted());
        var facts = envelope.all().stream().filter(item -> item.standing() == Standing.FACT).toList();
        assertTrue(facts.stream().anyMatch(fact -> SECRET.equals(fact.fields().get("statement"))
                && "true".equals(fact.fields().get("truth"))));
        assertTrue(facts.stream().anyMatch(fact -> HELD.equals(fact.fields().get("statement"))
                && "undetermined".equals(fact.fields().get("truth"))));
        assertTrue(envelope.all().stream().anyMatch(item -> item.standing() == Standing.BELIEF
                && ("actor:" + warden).equals(item.fields().get("holder"))));
        assertTrue(envelope.json().contains("\"standing\":\"fact\""));
        assertTrue(envelope.json().contains("A private planning assumption; unverified, never admitted, never public."));
        assertNull(envelope.stamp().holderId());

        String publicJson = assembled(request(AiRole.NARRATOR, Audience.PUBLIC, List.of(), null,
                ContextBudget.standard(false))).json();
        assertFalse(publicJson.contains(ASSUMPTION));
        assertFalse(publicJson.contains("\"standing\":\"fact\""));
        assertFalse(publicJson.contains(SECRET));

        // A restore-style generation change retires the plan: its assumptions leave the context.
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        var retired = assembled(request(AiRole.QUEST_PLANNER, Audience.PRIVILEGED, List.of(), null,
                ContextBudget.standard(true)));
        assertEquals(List.of(), retired.items().get(Section.PLANNING));
        assertEquals(envelope.stamp().campaignGeneration() + 1, retired.stamp().campaignGeneration());
    }

    /**
     * Mandatory material never truncates: below the exact required size the result is the
     * controlled {@code CONTEXT_TOO_LARGE} overflow; at that size every mandatory item is present,
     * every optional record is dropped and counted, and the context says it is partial.
     */
    @Test
    void mandatoryOverflowIsControlledAtTheExactBoundaryAndOptionalHistoryIsCounted() {
        seedKnowledge();
        for (int turn = 0; turn < 6; turn++) {
            execute(new TypedCommand.Wait(60));
        }
        int instructions = 5_000;
        var probe = assembler.assemble(sized(instructions, 1_000));
        var overflow = assertInstanceOf(ContextAssembler.TooLarge.class, probe);
        assertEquals("CONTEXT_TOO_LARGE", overflow.code());
        long required = overflow.overflow().requiredTokens();
        assertTrue(required > 1_000 && required < 9_999, "same digit count keeps the overhead identical");
        assertTrue(overflow.overflow().mandatoryBySection().get(Section.OBLIGATIONS) > 0);

        var exact = assertInstanceOf(ContextAssembler.Assembled.class, assembler.assemble(sized(instructions,
                (int) required))).envelope();
        assertTrue(exact.estimatedTokens() <= required);
        assertTrue(exact.all().stream().allMatch(ContextItem::mandatory));
        assertTrue(exact.all().stream().anyMatch(item -> item.ref().equals("actor:" + player)));
        assertTrue(exact.partial());
        assertTrue(exact.omitted().get(Section.HISTORY) >= 6);
        assertTrue(exact.json().contains("\"omitted\":{"));
        // Every rendered part was charged: overhead, each item and each glossary entry it brought.
        assertFalse(exact.glossary().isEmpty(), "the player's location binds the trailhead name");
        long charged = instructions + ContextCosts.overhead(exact)
                + exact.all().stream().mapToLong(ContextCosts::item).sum()
                + exact.glossary().stream().mapToLong(ContextCosts::term).sum();
        assertTrue(exact.estimatedTokens() >= charged, exact.estimatedTokens() + " < " + charged);

        assertInstanceOf(ContextAssembler.TooLarge.class, assembler.assemble(sized(instructions, (int) required - 1)));
    }

    /**
     * ID and full-text retrieval reach beyond the 64-event recent window: an explicit
     * {@code event:} focus reference fetches the old move event in the ID tier and its locations
     * bind the English glossary; the English stemmer finds the old speech for "key".
     */
    @Test
    void explicitIdsAndEnglishFullTextReachOlderHistoryAndBindTheGlossary() {
        execute(new TypedCommand.Speak(warden, "Where did you hide the keys?"));
        long speech = lastPublic("SPEECH_DELIVERED");
        execute(new TypedCommand.Move("grove"));
        long move = lastPublic("ACTOR_MOVED");
        execute(new TypedCommand.Move("trailhead"));
        for (int turn = 0; turn < 66; turn++) {
            execute(new TypedCommand.Wait(10));
        }
        var plain = assembled(request(AiRole.NARRATOR, Audience.PUBLIC, List.of(), null, ContextBudget.standard(false)));
        assertTrue(plain.all().stream().noneMatch(item -> item.ref().equals("event:" + speech)
                || item.ref().equals("event:" + move)), "both are older than the recent window");

        var envelope = assembled(request(AiRole.NARRATOR, Audience.PUBLIC, List.of("event:" + move), "key",
                ContextBudget.standard(false)));
        var moved = item(envelope, "event:" + move);
        assertEquals(Tier.ID, moved.tier());
        assertEquals("Player moved from trailhead to grove.", moved.fields().get("statement"));
        var spoken = item(envelope, "event:" + speech);
        assertEquals(Tier.TEXT, spoken.tier());
        assertEquals(Standing.EVENT, spoken.standing());
        assertTrue(((String) spoken.fields().get("statement")).contains("Where did you hide the keys?"));

        var glossary = envelope.glossary();
        assertTrue(glossary.contains(new GlossaryTerm("trailhead.name", GlossaryTerm.Kind.PROPER_NAME,
                "Mosslight Trailhead", List.of())));
        assertTrue(glossary.contains(new GlossaryTerm("grove.name", GlossaryTerm.Kind.TERM, "Lantern Grove",
                List.of("Boschetto delle lanterne"))));
        assertTrue(envelope.json().contains("\"glossaryStatus\":\"bound\""));
        assertEquals(0, envelope.glossaryOmitted());
        // Deterministic: the same state and request give the same digest.
        assertEquals(envelope.digest(), assembled(request(AiRole.NARRATOR, Audience.PUBLIC, List.of("event:" + move),
                "key", ContextBudget.standard(false))).digest());
    }

    /**
     * An Italian campaign binds Italian renderings that avoid the English names, states events in
     * Italian, and searches Italian text with the Italian stemmer ("pietre" finds "pietra", which
     * the English configuration would not).
     */
    @Test
    void italianContextsBindItalianTermsAndSearchWithTheItalianStemmer() {
        // A second campaign of the same adventure and pinned revision, narrated in Italian.
        campaign(SupportedLocale.IT);
        UUID claim = UUID.randomUUID();
        internal(1, List.of(new KnowledgeChange.Claim(claim, "actor:" + warden, Predicate.STATEMENT, null, null, null,
                "Il guardiano nasconde le chiavi sotto la pietra.", "it", Disclosure.COMMON, Truth.TRUE, null, 0)));
        execute(new TypedCommand.Move("grove"));
        long move = lastPublic("ACTOR_MOVED");

        var envelope = assembled(request(AiRole.NARRATOR, Audience.PUBLIC, List.of(), "pietre",
                ContextBudget.standard(false)));
        assertEquals(SupportedLocale.IT, envelope.stamp().locale());
        var common = item(envelope, "claim:" + claim);
        assertEquals(Tier.TEXT, common.tier());
        assertEquals("it", common.language());
        var moved = item(envelope, "event:" + move);
        assertEquals("it", moved.language());
        assertEquals("Spostamento di Player: da trailhead a grove.", moved.fields().get("statement"));
        assertTrue(envelope.glossary().contains(new GlossaryTerm("grove.name", GlossaryTerm.Kind.TERM,
                "Boschetto delle lanterne", List.of("Lantern Grove"))));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("select to_tsvector('english', ?) @@ "
                + "websearch_to_tsquery('english', ?)", Boolean.class,
                "Il guardiano nasconde le chiavi sotto la pietra.", "pietre"));
    }

    /**
     * The stamp names what the context read: it is current until a commit moves the revision and
     * the player's observation epochs, and a missing focus operation is refused.
     */
    @Test
    void theStampDetectsLaterChangesAndUncommittedFocusIsRefused() {
        execute(new TypedCommand.Wait(60));
        UUID operation = jdbc.queryForObject("select operation_id from campaign_event where campaign_id = ? "
                + "and visibility = 'PUBLIC' order by sequence desc limit 1", UUID.class, campaign);
        var envelope = assembled(new ContextAssembler.Request(adventure, campaign, AiRole.NARRATOR, Audience.PUBLIC,
                operation, List.of(), null, 1_000, ContextBudget.standard(false), POLICY));
        assertEquals(operation, envelope.stamp().operationId());
        var focus = envelope.items().get(Section.HISTORY).stream().filter(ContextItem::mandatory).toList();
        assertFalse(focus.isEmpty(), "the focus operation's public events are mandatory");
        assertTrue(focus.stream().allMatch(item -> item.tier() == Tier.REQUIRED));
        assertEquals(List.of(), assembler.staleness(envelope.stamp()));

        execute(new TypedCommand.Wait(60));
        var changed = assembler.staleness(envelope.stamp());
        assertEquals("revision", changed.getFirst());
        assertTrue(changed.contains("epoch:" + KnowledgeLedger.sightingScope(player)), changed.toString());
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(new ContextAssembler.Request(adventure,
                campaign, AiRole.NARRATOR, Audience.PUBLIC, UUID.randomUUID(), List.of(), null, 0,
                ContextBudget.standard(false), POLICY)));
        assertThrows(IllegalArgumentException.class, () -> request(AiRole.NARRATOR, Audience.PUBLIC,
                List.of("not a reference"), null, ContextBudget.standard(false)));
    }

    // ----------------------------------------------------------------------------------------------
    // Fixture helpers

    private PlannerTaskPort port;

    /**
     * Claims (a held statement, a secret, common mood), beliefs (the player's rumor told by the
     * warden, the warden's knowledge of the secret) and two commitments (the warden's promise to
     * the player, the hermit's obligation to the warden).
     */
    private void seedKnowledge() {
        UUID held = UUID.randomUUID();
        UUID secret = UUID.randomUUID();
        internal(2, List.of(
                new KnowledgeChange.Claim(held, "actor:" + warden, Predicate.STATEMENT, null, null, null, HELD, "en",
                        Disclosure.HOLDERS, Truth.UNDETERMINED, null, 0),
                new KnowledgeChange.Claim(secret, "actor:" + warden, Predicate.STATEMENT, null, null, null, SECRET,
                        "en", Disclosure.HOLDERS, Truth.TRUE, null, 0),
                new KnowledgeChange.Claim(UUID.randomUUID(), "actor:" + warden, Predicate.PROPERTY, null, "mood",
                        "\"wary\"", null, null, Disclosure.COMMON, Truth.TRUE, null, 0),
                new KnowledgeChange.Belief(UUID.randomUUID(), player, held, Stance.RUMORED, Acquisition.TOLD, warden,
                        null, null, 1),
                new KnowledgeChange.Belief(UUID.randomUUID(), warden, secret, Stance.KNOWS, Acquisition.OBSERVED, null,
                        null, null, 1),
                new KnowledgeChange.Commitment(UUID.randomUUID(), CommitmentKind.PROMISE, warden, player,
                        "location:grove", PROMISE, "en", true, CommitmentDisclosure.PARTIES, null, 1),
                new KnowledgeChange.Commitment(UUID.randomUUID(), CommitmentKind.OBLIGATION, hermit, warden, null,
                        FOREIGN_TERMS, "en", false, CommitmentDisclosure.PARTIES, null, 1)));
    }

    /** Create a campaign in a locale with the warden (vigor pool) at the trailhead and the hermit in the grove. */
    private void campaign(SupportedLocale locale) {
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revision, "Campaign", locale, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null,
                        null)));
        campaign = created.campaignId();
        player = created.actorId();
        warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "warden", "Warden", "trailhead", 0, Instant.now());
            entities.persist(actor);
            entities.persist(ActorResource.create(actor, "vigor", 0, 2, 6, 0, Instant.now()));
            return actor.getId();
        });
        hermit = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "hermit", "Hermit", "grove", 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
    }

    /** @return the assembled envelope, failing on overflow */
    private ContextEnvelope assembled(ContextAssembler.Request request) {
        return assertInstanceOf(ContextAssembler.Assembled.class, assembler.assemble(request)).envelope();
    }

    /** @return a request for the current campaign with 1,000 instruction tokens */
    private ContextAssembler.Request request(AiRole role, Audience audience, List<String> focus, String query,
            ContextBudget budget) {
        return new ContextAssembler.Request(adventure, campaign, role, audience, null, focus, query, 1_000, budget, POLICY);
    }

    /** @return a public narrator request with only shared headroom */
    private ContextAssembler.Request sized(int instructions, int input) {
        return new ContextAssembler.Request(adventure, campaign, AiRole.NARRATOR, Audience.PUBLIC, null, List.of(), null,
                instructions, new ContextBudget(input, 0, 0, Map.of()), POLICY);
    }

    /** @return the single selected item of a standing */
    private static ContextItem only(ContextEnvelope envelope, Standing standing) {
        var items = envelope.all().stream().filter(item -> item.standing() == standing).toList();
        assertEquals(1, items.size(), standing + " in " + envelope.json());
        return items.getFirst();
    }

    /** @return the selected item with this reference */
    private static ContextItem item(ContextEnvelope envelope, String ref) {
        return envelope.all().stream().filter(item -> item.ref().equals(ref)).findFirst()
                .orElseThrow(() -> new AssertionError(ref + " missing from " + envelope.json()));
    }

    /** @return sequence of the newest public event of a type */
    private long lastPublic(String type) {
        return jdbc.queryForObject("select max(sequence) from campaign_event where campaign_id = ? and event_type = ? "
                + "and visibility = 'PUBLIC'", Long.class, campaign, type);
    }

    /** Give the player one known quest; returns its plan target. */
    private String quest() {
        UUID quest = UUID.randomUUID();
        jdbc.update("insert into campaign_known_quest (id, campaign_id, actor_id, title, objective, status, "
                + "updated_revision) values (?, ?, ?, 'Boundary stones', 'Find who moved the boundary stones.', "
                + "'ACTIVE', ?)", quest, campaign, player, revision());
        return "known-quest:" + quest;
    }

    /** Planning over the real ledger and gate, with the scripted planner behind a real scheduler. */
    private QuestPlanning planning() {
        port = new PlannerTaskPort();
        var scheduler = new InferenceScheduler(new ScopedSessions(port, 16), port, ledger, SchedulerSettings.defaults(),
                Clock.systemUTC(), () -> 0.5);
        scheduler.recover();
        return new QuestPlanning(jdbc, jobs, admissions, planAssembler, new ContractRunner(scheduler, Clock.systemUTC()),
                PlanningSettings.defaults().withEnabled(true));
    }

    /** The canonical plan plus one private assumption citing the first supplied read. */
    private static String plannedWithAssumption(PlannerTaskPort.Seen seen) {
        var body = new LinkedHashMap<>(PlannerTaskPort.body(seen));
        String read = seen.context().path("dependencies").get(0).path("id").asString();
        var assumption = new LinkedHashMap<String, Object>();
        assumption.put("assumptionId", "witness-saw");
        assumption.put("branchRefs", List.of("interview"));
        assumption.put("dependencyRefs", List.of(read));
        assumption.put("note", ASSUMPTION);
        body.put("assumptions", List.of(assumption));
        return PlannerTaskPort.output(seen, "planned", body);
    }

    /** Commit a trusted internal operation with knowledge changes; returns its revision. */
    private long internal(int events, List<KnowledgeChange> knowledge) {
        long base = revision();
        var operation = new TransactionTemplate(manager).execute(status -> {
            var created = CampaignOperation.accept(campaign, UUID.randomUUID().toString(), HASH, "context-test", base,
                    Instant.now());
            created.transitionTo(OperationStatus.PLANNING, Instant.now());
            created.transitionTo(OperationStatus.VALIDATING, Instant.now());
            entities.persist(created);
            return created;
        });
        long version = jdbc.queryForObject("select operation_version from operation_journal where id = ?", Long.class,
                operation.getId());
        var grant = leases.acquire(adventure, campaign, operation.getId(), operation.getId(), Duration.ofSeconds(60));
        try {
            var list = new ArrayList<ResolvedCommit.Event>();
            for (int index = 0; index < events; index++) {
                list.add(new ResolvedCommit.Event(null, null, EventVisibility.INTERNAL,
                        new CampaignEvent.Body("test-knowledge", 1, 1, "{}")));
            }
            return commits.commit(new ResolvedCommit(new ResolvedCommit.Context(adventure, campaign, operation.getId(),
                    revision, HASH, base, version, grant.token()),
                    new ResolvedCommit.WorldChange(false, 0, CampaignMode.EXPLORATION, null), List.of(), list,
                    List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), knowledge)).stateRevision();
        } finally {
            leases.release(adventure, campaign, operation.getId(), grant.token());
        }
    }

    /** Execute one ordinary player intention at the current revision; it must commit. */
    private void execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision(), command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status());
    }

    /** Current committed state revision. */
    private long revision() {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
    }

    /**
     * Root pack of the command suites plus a hermit in the grove and glossary entries: the
     * trailhead name is a proper name (its Italian overlay is removed, as proper names keep one
     * spelling) and the grove name is a term with an Italian rendering.
     */
    private static Map<String, byte[]> rootPack(Map<String, byte[]> fixture) {
        var files = CommandExecutionTest.rootPack(fixture);
        files.put("npcs.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", List.of(
                Map.of("id", "warden", "kind", "npc", "data", Map.of("name", "Warden", "role", "Trail keeper",
                        "location", "trailhead")),
                Map.of("id", "hermit", "kind", "npc", "data", Map.of("name", "Hermit", "role", "Recluse",
                        "location", "grove"))))));
        var texts = CommandExecutionTest.document(files, "text-resources.json");
        @SuppressWarnings("unchecked")
        var glossary = new ArrayList<>((List<Object>) texts.get("glossary"));
        glossary.add(Map.of("id", "trailhead", "field", "/name", "kind", "properName"));
        glossary.add(Map.of("id", "grove", "field", "/name", "kind", "term"));
        texts.put("glossary", glossary);
        files.put("text-resources.json", JSON.writeValueAsBytes(texts));
        var italian = CommandExecutionTest.document(files, "locales/it.json");
        @SuppressWarnings("unchecked")
        var entries = new ArrayList<>((List<Map<String, Object>>) italian.get("entries"));
        entries.removeIf(entry -> "trailhead".equals(entry.get("id")) && "/name".equals(entry.get("field")));
        italian.put("entries", entries);
        files.put("locales/it.json", JSON.writeValueAsBytes(italian));
        return files;
    }

    /** Register a fixture variant through real ZIP staging and sealing. */
    private PackRegistry.Registration register(Map<String, byte[]> original, String id,
            List<Map<String, Object>> dependencies) throws Exception {
        var files = new TreeMap<>(original);
        var manifest = CommandExecutionTest.document(files, "adventure.json");
        manifest.put("id", id);
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", dependencies);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.setLevel(0);
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(output.toByteArray())));
    }
}
