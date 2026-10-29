package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
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
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.knowledge.KnowledgeChange.Acquisition;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentDisclosure;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentKind;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentStatus;
import org.rpgengine.knowledge.KnowledgeChange.Disclosure;
import org.rpgengine.knowledge.KnowledgeChange.Predicate;
import org.rpgengine.knowledge.KnowledgeChange.Stance;
import org.rpgengine.knowledge.KnowledgeChange.Supersession;
import org.rpgengine.knowledge.KnowledgeChange.Truth;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.rpgengine.knowledge.KnowledgeView;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of P09.1 typed knowledge over a sealed, registered pack and a campaign
 * from the production creation service. Trusted internal operations write claims, beliefs,
 * sightings and commitments through the real canonical commit; registered player intentions run
 * through the real admission, rules, resolver and commit. The suite proves that truth and belief
 * stay separate in every audience view, that provenance is the committing operation's own event,
 * that last known locations never reveal real ones, that commitments stay open until resolved
 * and are scoped to their parties, that only typed TRUE reveals disclose concealed content to the
 * rules, and that every failure rolls the whole operation back.
 */
@SpringBootTest
class KnowledgeLedgerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "c".repeat(64);
    private static final List<String> TABLES = List.of("campaign", "actor", "campaign_event", "operation_journal",
            "knowledge_claim", "knowledge_belief", "actor_sighting", "actor_commitment", "query_epoch");
    @Autowired private CommandExecutionService execution;
    @Autowired private OperationAdmissionService admissions;
    @Autowired private CanonicalCommitService commits;
    @Autowired private MutationLeaseService leases;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private KnowledgeView view;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID revision;
    private UUID campaign;
    private UUID player;
    private UUID warden;
    private UUID lurker;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    KnowledgeLedgerTest() { }

    /**
     * Register the pack through real staging and sealing, create one campaign through the production
     * creation service, then place a public NPC and a secret NPC beside the player at the trailhead.
     */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Knowledge", "knowledge", SupportedLocale.EN,
                    Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        revision = register(pack(fixture)).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revision, "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(),
                Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        warden = npc("warden", "Warden", "trailhead");
        lurker = npc("lurker", "Lurker", "trailhead");
    }

    /** Remove disposable test rows, preserving production constraints and triggers. */
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
     * A claim's truth is privileged and a belief is only a stance: the holder's view carries the
     * proposition, stance and acquisition but never truth; another holder's belief never leaks; a
     * determination and a belief revision step row versions and query scopes with exact provenance.
     */
    @Test
    void claimsAndBeliefsStaySeparateWithExactProvenance() {
        UUID cellar = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID mood = UUID.randomUUID();
        UUID rumor = UUID.randomUUID();
        UUID witness = UUID.randomUUID();
        long first = internal(2, List.of(
                exists(cellar, "location:cellar", Disclosure.HOLDERS, Truth.TRUE, null, 0),
                new KnowledgeChange.Claim(key, "actor:" + warden, Predicate.STATEMENT, null, null, null,
                        "The warden hides a key under the waystone.", "en", Disclosure.HOLDERS, Truth.UNDETERMINED,
                        null, 1),
                new KnowledgeChange.Claim(mood, "actor:" + warden, Predicate.PROPERTY, null, "mood", "\"wary\"",
                        null, null, Disclosure.COMMON, Truth.TRUE, null, 0),
                new KnowledgeChange.Belief(rumor, player, key, Stance.RUMORED, Acquisition.TOLD, warden, null, null, 1),
                new KnowledgeChange.Belief(witness, warden, cellar, Stance.KNOWS, Acquisition.OBSERVED, null, null,
                        null, 0)));
        long firstSequence = sequenceOf(first, 0);

        var seen = view.forActor(campaign, player);
        assertEquals(first, seen.stateRevision());
        assertEquals("trailhead", seen.ownLocation());
        assertEquals(1, seen.beliefs().size());
        var held = seen.beliefs().getFirst();
        assertEquals(new KnowledgeView.ClaimContent(key, "actor:" + warden, Predicate.STATEMENT, null, null, null,
                "The warden hides a key under the waystone.", "en"), held.claim());
        assertEquals(Stance.RUMORED, held.stance());
        assertEquals(Acquisition.TOLD, held.acquisition());
        assertEquals(warden, held.informantId());
        // The audience records carry no truth or disclosure at all, and the warden's knowledge of
        // the secret cellar is not the player's.
        assertFalse(seen.toString().contains("UNDETERMINED"));
        assertFalse(seen.toString().contains("cellar"));
        assertEquals(List.of(mood), seen.common().stream().map(c -> c.claim().claimId()).toList());
        assertEquals("\"wary\"", seen.common().getFirst().claim().valueJson());
        assertEquals(List.of(cellar), view.forActor(campaign, warden).beliefs().stream()
                .map(b -> b.claim().claimId()).toList());

        var privileged = view.privileged(campaign);
        var keyClaim = privileged.claims().stream().filter(c -> c.claim().claimId().equals(key)).findFirst().orElseThrow();
        assertEquals(Truth.UNDETERMINED, keyClaim.truth());
        assertEquals(firstSequence + 1, keyClaim.establishedEventSequence());
        assertEquals(firstSequence + 1, keyClaim.truthEventSequence());
        assertEquals(1L, epoch(KnowledgeLedger.CLAIMS_SCOPE));
        assertEquals(1L, epoch(KnowledgeLedger.knowledgeScope(player)));
        assertEquals(1L, epoch(KnowledgeLedger.knowledgeScope(warden)));
        assertEquals(0L, reference("claim:" + key));
        assertEquals(0L, reference("belief:" + rumor));

        // Truth is resolved once and the player revises its own stance; the earlier belief stays as history.
        UUID doubt = UUID.randomUUID();
        long second = internal(1, List.of(new KnowledgeChange.Determination(key, 0, Truth.FALSE, 0),
                new KnowledgeChange.Belief(doubt, player, key, Stance.DOUBTS, Acquisition.INFERRED, null, null,
                        new Supersession(rumor, 0), 0)));
        assertEquals(List.of(Stance.DOUBTS), view.forActor(campaign, player).beliefs().stream()
                .map(KnowledgeView.HeldBelief::stance).toList());
        privileged = view.privileged(campaign);
        keyClaim = privileged.claims().stream().filter(c -> c.claim().claimId().equals(key)).findFirst().orElseThrow();
        assertEquals(Truth.FALSE, keyClaim.truth());
        assertEquals(second, keyClaim.truthRevision());
        assertEquals(sequenceOf(second, 0), keyClaim.truthEventSequence());
        var old = privileged.beliefs().stream().filter(b -> b.beliefId().equals(rumor)).findFirst().orElseThrow();
        assertEquals(second, old.supersededRevision());
        assertEquals(rumor, privileged.beliefs().stream().filter(b -> b.beliefId().equals(doubt)).findFirst()
                .orElseThrow().supersedes());
        assertEquals(2L, epoch(KnowledgeLedger.CLAIMS_SCOPE));
        assertEquals(2L, epoch(KnowledgeLedger.knowledgeScope(player)));
        assertEquals(1L, epoch(KnowledgeLedger.knowledgeScope(warden)));
        assertEquals(1L, reference("claim:" + key));
        assertEquals(1L, reference("belief:" + rumor));
        assertNull(reference("claim:" + UUID.randomUUID()));

        // A world change supersedes a claim about the same subject; the old one keeps its interval.
        UUID calm = UUID.randomUUID();
        long third = internal(1, List.of(new KnowledgeChange.Claim(calm, "actor:" + warden, Predicate.PROPERTY, null,
                "mood", "\"calm\"", null, null, Disclosure.COMMON, Truth.TRUE, new Supersession(mood, 0), 0)));
        assertEquals(List.of(calm), view.forActor(campaign, player).common().stream()
                .map(c -> c.claim().claimId()).toList());
        var closed = view.privileged(campaign).claims().stream().filter(c -> c.claim().claimId().equals(mood))
                .findFirst().orElseThrow();
        assertEquals(third, closed.supersededRevision());
        assertEquals(Truth.TRUE, closed.truth());
    }

    /**
     * Every refused premise rolls back the whole operation: KNOWS on a claim that is not TRUE, a
     * second current belief, a repeated determination, an unrelated or stale predecessor, foreign
     * campaign references, an observed sighting that contradicts the real location and a
     * resolution of a closed commitment.
     */
    @Test
    void refusedKnowledgePremisesRollBackTheWholeOperation() {
        UUID open = UUID.randomUUID();
        UUID belief = UUID.randomUUID();
        UUID promise = UUID.randomUUID();
        internal(1, List.of(new KnowledgeChange.Claim(open, "location:grove", Predicate.PROPERTY, null, "gate",
                "\"open\"", null, null, Disclosure.HOLDERS, Truth.UNDETERMINED, null, 0),
                new KnowledgeChange.Belief(belief, player, open, Stance.BELIEVES, Acquisition.INFERRED, null, null,
                        null, 0),
                new KnowledgeChange.Commitment(promise, CommitmentKind.PROMISE, warden, player, null, "Wait here.",
                        "en", true, CommitmentDisclosure.PARTIES, null, 0)));
        internal(1, List.of(new KnowledgeChange.Resolution(promise, 0, CommitmentStatus.RELEASED, 0)));
        UUID foreign = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revision, "Other", SupportedLocale.EN, new CharacterChoices("Other", "surveyor", Map.of(), Map.of(),
                null, null))).actorId();
        var refused = new LinkedHashMap<String, List<KnowledgeChange>>();
        refused.put("knows-undetermined", List.of(new KnowledgeChange.Belief(UUID.randomUUID(), warden, open,
                Stance.KNOWS, Acquisition.OBSERVED, null, null, null, 0)));
        refused.put("second-current-belief", List.of(new KnowledgeChange.Belief(UUID.randomUUID(), player, open,
                Stance.SUSPECTS, Acquisition.INFERRED, null, null, null, 0)));
        refused.put("stale-determination", List.of(new KnowledgeChange.Determination(open, 7, Truth.TRUE, 0)));
        refused.put("unrelated-predecessor", List.of(new KnowledgeChange.Claim(UUID.randomUUID(), "location:ridge",
                Predicate.EXISTS, null, null, null, null, null, Disclosure.COMMON, Truth.TRUE,
                new Supersession(open, 0), 0)));
        refused.put("foreign-holder-predecessor", List.of(new KnowledgeChange.Belief(UUID.randomUUID(), warden, open,
                Stance.BELIEVES, Acquisition.INFERRED, null, null, new Supersession(belief, 0), 0)));
        refused.put("foreign-subject", List.of(exists(UUID.randomUUID(), "actor:" + foreign, Disclosure.COMMON,
                Truth.TRUE, null, 0)));
        refused.put("unknown-item", List.of(new KnowledgeChange.Belief(UUID.randomUUID(), warden, open,
                Stance.RUMORED, Acquisition.READ, null, "item:" + UUID.randomUUID(), null, 0)));
        refused.put("foreign-holder", List.of(new KnowledgeChange.Belief(UUID.randomUUID(), foreign, open,
                Stance.RUMORED, Acquisition.INFERRED, null, null, null, 0)));
        refused.put("observed-elsewhere", List.of(new KnowledgeChange.Sighting(player, warden, "grove", null, 0)));
        refused.put("resolved-twice", List.of(new KnowledgeChange.Resolution(promise, 1, CommitmentStatus.FULFILLED, 0)));
        // A valid first change shows that the later refusal also removes earlier writes of the operation.
        UUID valid = UUID.randomUUID();
        refused.forEach((name, changes) -> {
            var all = new ArrayList<KnowledgeChange>();
            all.add(exists(valid, "location:tower", Disclosure.COMMON, Truth.TRUE, null, 0));
            all.addAll(changes);
            var before = world();
            // Database-enforced premises surface as storage errors; the ledger's own checks as
            // IllegalStateException, so a removed ledger check cannot hide behind a later guard.
            var expected = name.equals("knows-undetermined") || name.equals("foreign-holder")
                    ? DataAccessException.class : IllegalStateException.class;
            assertThrows(expected, () -> internal(1, all), name);
            assertEquals(before, world(), name);
        });
        // Construction refuses shapes that could never be valid, before any transaction.
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeChange.Claim(UUID.randomUUID(),
                "location:grove", Predicate.PROPERTY, null, "gate", "{\"open\":true}", null, null, Disclosure.COMMON,
                Truth.TRUE, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeChange.Claim(UUID.randomUUID(),
                "region:north", Predicate.EXISTS, null, null, null, null, null, Disclosure.COMMON, Truth.TRUE, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeChange.Claim(UUID.randomUUID(),
                "location:grove", Predicate.STATEMENT, null, null, null, "Text without language", null,
                Disclosure.COMMON, Truth.TRUE, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeChange.Belief(UUID.randomUUID(), player, open,
                Stance.BELIEVES, Acquisition.TOLD, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeChange.Resolution(promise, 0,
                CommitmentStatus.OPEN, 0));
        // A change may only cite an event of its own operation.
        var citing = List.<KnowledgeChange>of(exists(UUID.randomUUID(), "location:tower", Disclosure.COMMON,
                Truth.TRUE, null, 1));
        assertThrows(IllegalArgumentException.class, () -> internal(1, citing));
    }

    /**
     * Concealed content stays undiscoverable to the rules until a typed reveal: a current TRUE
     * EXISTS claim the player KNOWS or BELIEVES, or that is common knowledge. Rumors, false or
     * undetermined claims, another actor's knowledge and free-text known facts never disclose it.
     */
    @Test
    void onlyTypedTrueRevealsDiscloseConcealedLocationsAndActors() {
        assertRefused(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE, new TypedCommand.Move("cellar"));
        assertRefused(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, new TypedCommand.Speak(lurker, "Hello"));
        UUID cellar = UUID.randomUUID();
        UUID rumored = UUID.randomUUID();
        UUID hypothesis = UUID.randomUUID();
        internal(1, List.of(exists(cellar, "location:cellar", Disclosure.HOLDERS, Truth.TRUE, null, 0),
                new KnowledgeChange.Belief(rumored, player, cellar, Stance.RUMORED, Acquisition.TOLD, warden, null,
                        null, 0),
                new KnowledgeChange.Belief(UUID.randomUUID(), warden, cellar, Stance.KNOWS, Acquisition.OBSERVED,
                        null, null, null, 0),
                exists(hypothesis, "actor:" + lurker, Disclosure.COMMON, Truth.UNDETERMINED, null, 0),
                // Common knowledge that is false never reveals anything.
                exists(UUID.randomUUID(), "location:cellar", Disclosure.COMMON, Truth.FALSE, null, 0)));
        jdbc.update("insert into campaign_known_fact (id, campaign_id, actor_id, fact_text, confidence, "
                + "learned_revision) values (?, ?, ?, 'A cellar lies beneath the trailhead.', 'KNOWN', 1)",
                UUID.randomUUID(), campaign, player);
        assertRefused(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE, new TypedCommand.Move("cellar"));
        assertRefused(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, new TypedCommand.Speak(lurker, "Hello"));

        // The player now believes the cellar exists and the lurker's existence is resolved as common knowledge.
        UUID believed = UUID.randomUUID();
        internal(1, List.of(new KnowledgeChange.Belief(believed, player, cellar, Stance.BELIEVES,
                Acquisition.TOLD, warden, null, new Supersession(rumored, 0), 0),
                new KnowledgeChange.Determination(hypothesis, 0, Truth.TRUE, 0)));
        assertEquals(OperationStatus.COMMITTED, run(new TypedCommand.Speak(lurker, "I see you")).operation().status());
        assertEquals(OperationStatus.COMMITTED, run(new TypedCommand.Move("cellar")).operation().status());
        assertEquals("cellar", jdbc.queryForObject("select location_id from actor where id = ?", String.class, player));

        // A superseded belief reveals nothing: once the player doubts the cellar, the route is closed again.
        assertEquals(OperationStatus.COMMITTED, run(new TypedCommand.Move("trailhead")).operation().status());
        internal(1, List.of(new KnowledgeChange.Belief(UUID.randomUUID(), player, cellar, Stance.DOUBTS,
                Acquisition.INFERRED, null, null, new Supersession(believed, 0), 0)));
        assertRefused(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE, new TypedCommand.Move("cellar"));

    }

    /**
     * After each committed player intention the player records what it perceives where it stands:
     * public co-present actors are sighted, concealed ones are not, the last known location never
     * follows the subject's real movement, a later look at the place records the absence and a new
     * sighting replaces it. A reported sighting records what was told, even when it is wrong.
     */
    @Test
    void perceptionKeepsLastKnownLocationsApartFromRealOnes() {
        run(new TypedCommand.Wait(10));
        long observed = revision();
        var seen = view.forActor(campaign, player);
        assertEquals(1, seen.lastKnown().size());
        var sighting = seen.lastKnown().getFirst();
        assertEquals(warden, sighting.subjectId());
        assertEquals("trailhead", sighting.locationId());
        assertEquals(Acquisition.OBSERVED, sighting.acquisition());
        assertEquals(observed, sighting.observedRevision());
        assertNull(sighting.absenceRevision());
        assertEquals(1L, epoch(KnowledgeLedger.sightingScope(player)));

        // The warden leaves through another canonical writer: the player's knowledge does not follow.
        jdbc.update("update actor set location_id = 'grove', row_version = row_version + 1 where id = ?", warden);
        var stale = view.forActor(campaign, player).lastKnown().getFirst();
        assertEquals("trailhead", stale.locationId());
        assertFalse(view.forActor(campaign, player).toString().contains("grove"));
        var whereabouts = view.privileged(campaign).whereabouts().getFirst();
        assertEquals("trailhead", whereabouts.lastKnownLocation());
        assertEquals("grove", whereabouts.currentLocation());

        run(new TypedCommand.Wait(10));
        long absent = revision();
        var checked = view.forActor(campaign, player).lastKnown().getFirst();
        assertEquals("trailhead", checked.locationId());
        assertEquals(observed, checked.observedRevision());
        assertEquals(absent, checked.absenceRevision());

        run(new TypedCommand.Move("grove"));
        var found = view.forActor(campaign, player).lastKnown().getFirst();
        assertEquals("grove", found.locationId());
        assertEquals(revision(), found.observedRevision());
        assertNull(found.absenceRevision());
        assertEquals(sequenceOf(revision(), 0), view.privileged(campaign).whereabouts().getFirst()
                .observedEventSequence());

        // A reported sighting of the concealed lurker records the informant's (wrong) account.
        internal(1, List.of(new KnowledgeChange.Sighting(player, lurker, "cellar", warden, 0)));
        var reported = view.forActor(campaign, player).lastKnown().stream().filter(s -> s.subjectId().equals(lurker))
                .findFirst().orElseThrow();
        assertEquals("cellar", reported.locationId());
        assertEquals(Acquisition.TOLD, reported.acquisition());
        assertEquals(warden, reported.informantId());
        assertEquals("trailhead", jdbc.queryForObject("select location_id from actor where id = ?", String.class,
                lurker));
    }

    /**
     * Commitments stay OPEN until one resolution with its own provenance; lateness is derived from
     * world time without any write; each audience sees only the commitments it is party to or that
     * are common knowledge, and resolved ones leave the audience view.
     */
    @Test
    void commitmentsStayOpenUntilResolvedAndReachOnlyTheirParties() {
        UUID promise = UUID.randomUUID();
        UUID secret = UUID.randomUUID();
        UUID pact = UUID.randomUUID();
        long made = internal(1, List.of(
                new KnowledgeChange.Commitment(promise, CommitmentKind.PROMISE, warden, player, "location:grove",
                        "I will guard the grove until dusk.", "en", true, CommitmentDisclosure.PARTIES, 600L, 0),
                new KnowledgeChange.Commitment(secret, CommitmentKind.OBLIGATION, warden, lurker, null,
                        "Report every traveller.", "en", false, CommitmentDisclosure.PARTIES, null, 0),
                new KnowledgeChange.Commitment(pact, CommitmentKind.AGREEMENT, lurker, warden, null,
                        "Patto di non belligeranza.", "it", false, CommitmentDisclosure.COMMON, null, 0)));
        var open = view.forActor(campaign, player).commitments();
        assertEquals(java.util.Set.of(promise, pact), open.stream().map(KnowledgeView.OpenCommitment::commitmentId)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of(promise, secret, pact), view.forActor(campaign, warden).commitments().stream()
                .map(KnowledgeView.OpenCommitment::commitmentId).collect(java.util.stream.Collectors.toSet()));
        var kept = open.stream().filter(c -> c.commitmentId().equals(promise)).findFirst().orElseThrow();
        assertEquals("I will guard the grove until dusk.", kept.terms());
        assertTrue(kept.quoted());
        assertFalse(kept.overdue());
        assertEquals(made, kept.madeRevision());
        assertEquals("it", open.stream().filter(c -> c.commitmentId().equals(pact)).findFirst().orElseThrow()
                .termsLocale());
        assertEquals(1L, epoch(KnowledgeLedger.commitmentScope(player)));
        assertEquals(1L, epoch(KnowledgeLedger.commitmentScope(warden)));

        // Time passes past the deadline: the promise is overdue but still OPEN, and reading changes nothing.
        run(new TypedCommand.Wait(900));
        var before = snapshot();
        var late = view.forActor(campaign, player).commitments().stream()
                .filter(c -> c.commitmentId().equals(promise)).findFirst().orElseThrow();
        assertTrue(late.overdue());
        view.privileged(campaign);
        assertEquals(before, snapshot());

        long kept2 = internal(1, List.of(new KnowledgeChange.Resolution(promise, 0, CommitmentStatus.BROKEN, 0)));
        assertEquals(List.of(pact), view.forActor(campaign, player).commitments().stream()
                .map(KnowledgeView.OpenCommitment::commitmentId).toList());
        var record = view.privileged(campaign).commitments().stream().filter(c -> c.commitmentId().equals(promise))
                .findFirst().orElseThrow();
        assertEquals(CommitmentStatus.BROKEN, record.status());
        assertEquals(kept2, record.resolvedRevision());
        assertFalse(record.overdue());
        assertEquals(2L, epoch(KnowledgeLedger.commitmentScope(player)));
        assertEquals(1L, reference("commitment:" + promise));
        // The database refuses to reopen, rewrite or delete it.
        assertThrows(DataAccessException.class, () -> jdbc.update("update actor_commitment set status = 'OPEN', "
                + "resolved_revision = null, resolved_world_time = null, resolved_event_sequence = null, "
                + "row_version = row_version + 1 where id = ?", promise));
        assertThrows(DataAccessException.class, () -> jdbc.update("update actor_commitment set terms = 'Changed', "
                + "row_version = row_version + 1 where id = ?", pact));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from actor_commitment where id = ?", pact));
    }

    /**
     * A registered intention may only write knowledge records of its participants: the speaker may
     * learn from its recipient, but it cannot plant a belief in an uninvolved actor.
     */
    @Test
    void registeredIntentionsWriteOnlyTheirParticipantsKnowledge() {
        UUID claim = UUID.randomUUID();
        internal(1, List.of(exists(claim, "location:grove", Disclosure.HOLDERS, Truth.TRUE, null, 0)));
        var input = envelope(new TypedCommand.Speak(warden, "What do you know?"));
        var admitted = admissions.submit(owner, adventure, campaign, input);
        validating(admitted);
        var planted = speech(admitted, input, List.of(new KnowledgeChange.Belief(UUID.randomUUID(), lurker, claim,
                Stance.BELIEVES, Acquisition.TOLD, warden, null, null, 0)));
        var before = snapshot();
        assertThrows(IllegalStateException.class, () -> commits.commit(planted));
        assertEquals(before, snapshot());
        UUID learned = UUID.randomUUID();
        commits.commit(speech(admitted, input, List.of(new KnowledgeChange.Belief(learned, player, claim,
                Stance.BELIEVES, Acquisition.TOLD, warden, null, null, 0))));
        assertEquals(List.of(learned), view.forActor(campaign, player).beliefs().stream()
                .map(KnowledgeView.HeldBelief::beliefId).toList());
        // The same commit observed the co-present public warden, citing the operation's first event.
        assertEquals(List.of(warden), view.forActor(campaign, player).lastKnown().stream()
                .map(KnowledgeView.LastKnown::subjectId).toList());
    }

    /** The database repeats the structural rules for any client that bypasses the ledger. */
    @Test
    void databaseGuardsKnowledgeInvariants() {
        UUID claim = UUID.randomUUID();
        UUID belief = UUID.randomUUID();
        long first = internal(1, List.of(exists(claim, "location:grove", Disclosure.COMMON, Truth.TRUE, null, 0),
                new KnowledgeChange.Belief(belief, player, claim, Stance.KNOWS, Acquisition.OBSERVED, null, null,
                        null, 0)));
        long sequence = sequenceOf(first, 0);
        long second = internal(1, List.of(new KnowledgeChange.Sighting(player, lurker, "cellar", warden, 0)));
        long later = sequenceOf(second, 0);
        // Provenance must be an event of the recorded revision.
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into knowledge_claim (id, campaign_id, "
                + "subject_ref, predicate, disclosure, truth, established_revision, established_world_time, "
                + "established_event_sequence, truth_revision, truth_event_sequence) values (?, ?, 'location:ridge', "
                + "'EXISTS', 'COMMON', 'TRUE', ?, 0, ?, ?, ?)", UUID.randomUUID(), campaign, first, later, first, later));
        // Shape, reference grammar and the truth-at-establishment rule.
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into knowledge_claim (id, campaign_id, "
                + "subject_ref, predicate, qualifier, disclosure, truth, established_revision, established_world_time, "
                + "established_event_sequence, truth_revision, truth_event_sequence) values (?, ?, 'location:ridge', "
                + "'EXISTS', 'extra', 'COMMON', 'TRUE', ?, 0, ?, ?, ?)", UUID.randomUUID(), campaign, first, sequence,
                first, sequence));
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into knowledge_claim (id, campaign_id, "
                + "subject_ref, predicate, disclosure, truth, established_revision, established_world_time, "
                + "established_event_sequence, truth_revision, truth_event_sequence) values (?, ?, 'region:north', "
                + "'EXISTS', 'COMMON', 'TRUE', ?, 0, ?, ?, ?)", UUID.randomUUID(), campaign, first, sequence, first,
                sequence));
        // A determined truth never changes and the proposition is frozen.
        assertThrows(DataAccessException.class, () -> jdbc.update("update knowledge_claim set truth = 'FALSE', "
                + "truth_revision = ?, truth_event_sequence = ?, row_version = row_version + 1 where id = ?",
                second, later, claim));
        assertThrows(DataAccessException.class, () -> jdbc.update("update knowledge_claim set subject_ref = "
                + "'location:ridge', row_version = row_version + 1 where id = ?", claim));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from knowledge_claim where id = ?", claim));
        // KNOWS needs a current TRUE claim; a belief's stance is frozen; supersession stays with its holder.
        UUID undetermined = UUID.randomUUID();
        internal(1, List.of(exists(undetermined, "location:ridge", Disclosure.HOLDERS, Truth.UNDETERMINED, null, 0)));
        long third = revision();
        long thirdSequence = sequenceOf(third, 0);
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into knowledge_belief (id, campaign_id, "
                + "holder_actor_id, claim_id, stance, acquisition, learned_revision, learned_world_time, "
                + "learned_event_sequence) values (?, ?, ?, ?, 'KNOWS', 'INFERRED', ?, 0, ?)", UUID.randomUUID(),
                campaign, warden, undetermined, third, thirdSequence));
        assertThrows(DataAccessException.class, () -> jdbc.update("update knowledge_belief set stance = 'DOUBTS', "
                + "row_version = row_version + 1 where id = ?", belief));
        // Sightings never move backwards and are never deleted.
        assertThrows(DataAccessException.class, () -> jdbc.update("update actor_sighting set observed_revision = ?, "
                + "observed_event_sequence = ?, row_version = row_version + 1 where subject_actor_id = ?", first,
                sequence, lurker));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from actor_sighting where subject_actor_id = ?",
                lurker));
    }

    /** Audience reads are scoped to one actor of the campaign and need their own snapshot transaction. */
    @Test
    void viewsRefuseForeignAudiencesAndAmbientTransactions() {
        assertThrows(IllegalArgumentException.class, () -> view.forActor(campaign, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> view.privileged(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status ->
                view.forActor(campaign, player)));
        var empty = view.forActor(campaign, player);
        assertEquals(List.of(), empty.beliefs());
        assertEquals(List.of(), empty.lastKnown());
        assertEquals(List.of(), empty.commitments());
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** EXISTS claim with no statement. */
    private static KnowledgeChange.Claim exists(UUID id, String subject, Disclosure disclosure, Truth truth,
            Supersession supersedes, int event) {
        return new KnowledgeChange.Claim(id, subject, Predicate.EXISTS, null, null, null, null, null, disclosure, truth,
                supersedes, event);
    }

    /**
     * Commit one trusted internal operation with {@code events} internal events and the knowledge
     * changes, through the real canonical commit and a real lease; return the committed revision.
     */
    private long internal(int events, List<KnowledgeChange> knowledge) {
        long base = revision();
        var operation = new TransactionTemplate(manager).execute(status -> {
            var created = CampaignOperation.accept(campaign, UUID.randomUUID().toString(), HASH, "knowledge-test", base,
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
            // A refused operation keeps no permit; a committed one already released it.
            leases.release(adventure, campaign, operation.getId(), grant.token());
        }
    }

    /** Run a registered player intention end to end at the current revision. */
    private CommandExecutionService.Execution run(TypedCommand command) {
        return execution.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision(), command.type(), command.payload()));
    }

    /** Assert that a fresh admission is refused with exactly this category and writes nothing. */
    private void assertRefused(OperationAdmissionFailure.Kind kind, TypedCommand command) {
        var before = snapshot();
        assertEquals(kind, assertThrows(OperationAdmissionFailure.class, () -> admissions.submit(owner, adventure,
                campaign, envelope(command))).kind());
        assertEquals(before, snapshot());
    }

    /** Fresh envelope at the current revision. */
    private OperationAdmissionService.Command envelope(TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision(), command.type(),
                command.payload());
    }

    /** Advance only journal progress so the trusted commit path accepts a resolved plan. */
    private void validating(OperationAdmissionService.Admission admitted) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var operation = entities.find(CampaignOperation.class, admitted.operation().operationId());
            operation.transitionTo(OperationStatus.PLANNING, Instant.now());
            operation.transitionTo(OperationStatus.VALIDATING, Instant.now());
        });
    }

    /** Trusted speech effects bound to the admitted typed evidence, with knowledge changes. */
    private ResolvedCommit speech(OperationAdmissionService.Admission admitted, OperationAdmissionService.Command input,
            List<KnowledgeChange> knowledge) {
        var intent = CommandIntent.from(owner, input);
        return new ResolvedCommit(new ResolvedCommit.Context(adventure, campaign, admitted.operation().operationId(),
                revision, intent.payloadHash(), input.expectedRevision(), 2, admitted.lease().token()),
                new ResolvedCommit.WorldChange(true, 0, CampaignMode.EXPLORATION, null), List.of(),
                List.of(new ResolvedCommit.Event(player, null, EventVisibility.PUBLIC,
                        new CampaignEvent.Body("test-speech", 1, 1, "{}"))),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, intent, List.of(), knowledge);
    }

    /** Current committed state revision. */
    private long revision() {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
    }

    /** Campaign sequence of the index-th event committed at a revision. */
    private long sequenceOf(long committed, int index) {
        return jdbc.queryForList("select sequence from campaign_event where campaign_id = ? and state_revision = ? "
                + "order by sequence", Long.class, campaign, committed).get(index);
    }

    /** Current epoch of a query scope. */
    private long epoch(String scope) {
        return QueryEpochs.current(jdbc, campaign, scope);
    }

    /** Row version a plan dependency on the reference would observe, or null when it cannot. */
    private Long reference(String reference) {
        return jdbc.queryForObject("select plan_reference_version(?, ?)", Long.class, campaign, reference);
    }

    /** Persist one campaign NPC with an authored origin at a location. */
    private UUID npc(String definition, String name, String location) {
        return new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, definition, name, location, 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
    }

    /**
     * Exact rows of every world and knowledge table except the journal, whose fresh operation row an
     * internal attempt writes before its commit is refused.
     */
    private Map<String, String> world() {
        var rows = snapshot();
        rows.remove("operation_journal");
        return rows;
    }

    /** Exact committed rows of every table a knowledge write or its rollback could touch. */
    private Map<String, String> snapshot() {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return snapshot;
    }

    /**
     * Fixture variant: open links from the trailhead to the grove and the secret cellar, a public
     * warden and a secret lurker origin.
     */
    private static Map<String, byte[]> pack(Map<String, byte[]> fixture) {
        var files = new TreeMap<>(fixture);
        put(files, "locations.json", List.of(
                location("trailhead", null, link("grove", true), link("ridge", false), link("cellar", true)),
                location("grove", null, link("trailhead", true)),
                location("ridge", null, link("trailhead", true)),
                location("cellar", "secret", link("trailhead", true)),
                location("tower", null)));
        put(files, "npcs.json", List.of(npcDefinition("warden", "Trail keeper", null),
                npcDefinition("lurker", "Hidden watcher", "secret")));
        @SuppressWarnings("unchecked")
        var manifest = (Map<String, Object>) JSON.readValue(files.get("adventure.json"), Map.class);
        @SuppressWarnings("unchecked")
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        manifest.put("id", "test:knowledge");
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", List.of());
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        return files;
    }

    /** One authored location with an optional knowledge visibility and ordered links. */
    @SafeVarargs
    private static Map<String, Object> location(String id, String knowledge, Map<String, Object>... links) {
        var data = new LinkedHashMap<String, Object>(Map.of("name", "Place " + id, "description", "Fixture " + id,
                "links", List.of(links)));
        if (knowledge != null) {
            data.put("knowledge", knowledge);
        }
        return Map.of("id", id, "kind", "location", "data", data);
    }

    /** One authored link with explicit accessibility. */
    private static Map<String, Object> link(String to, boolean accessible) {
        return Map.of("to", to, "accessible", accessible);
    }

    /** One authored NPC at the trailhead, optionally secret. */
    private static Map<String, Object> npcDefinition(String id, String role, String knowledge) {
        var data = new LinkedHashMap<String, Object>(Map.of("name", "NPC " + id, "role", role, "location", "trailhead"));
        if (knowledge != null) {
            data.put("knowledge", knowledge);
        }
        return Map.of("id", id, "kind", "npc", "data", data);
    }

    /** Replace one definition file with exactly these entries. */
    private static void put(Map<String, byte[]> files, String path, List<Map<String, Object>> definitions) {
        files.put(path, JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", definitions)));
    }

    /** Register the variant through real ZIP staging and sealing. */
    private PackRegistry.Registration register(Map<String, byte[]> files) throws Exception {
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
