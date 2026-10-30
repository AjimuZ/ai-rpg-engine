package org.rpgengine.narration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.presentation.SupportedLocale;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixed bilingual statements of the item events (P10.2): each states only what its payload says, names
 * actors from the supplied display names, and falls back to no statement when anything it needs is missing.
 */
class EventStatementsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WARDEN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Map<UUID, String> NAMES = Map.of(PLAYER, "Mira", WARDEN, "Warden");

    /** Construct a stateless fixture. */
    EventStatementsTest() {
    }

    private static String state(SupportedLocale locale, String type, String payload) {
        return EventStatements.state(locale, type, 1, JSON.readTree(payload), NAMES).orElse(null);
    }

    /** Using, creating, giving, trading and wearing an item are stated in English and Italian. */
    @Test
    void statesItemEventsInBothLocales() {
        String used = "{\"actorId\":\"" + PLAYER + "\",\"definitionId\":\"potion\",\"targetActorIds\":[\"" + WARDEN + "\"]}";
        assertEquals("Mira uses potion on Warden.", state(SupportedLocale.EN, "ITEM_USED", used));
        assertEquals("Mira usa potion su Warden.", state(SupportedLocale.IT, "ITEM_USED", used));
        assertEquals("Mira uses potion.", state(SupportedLocale.EN, "ITEM_USED",
                "{\"actorId\":\"" + PLAYER + "\",\"definitionId\":\"potion\",\"targetActorIds\":[]}"));

        String created = "{\"recipientActorId\":\"" + PLAYER + "\",\"definitionId\":\"coin\",\"quantity\":50}";
        assertEquals("Mira obtains 50 × coin.", state(SupportedLocale.EN, "ITEM_CREATED", created));
        assertEquals("Mira ottiene 50 × coin.", state(SupportedLocale.IT, "ITEM_CREATED", created));

        String given = "{\"fromActorId\":\"" + PLAYER + "\",\"toActorId\":\"" + WARDEN + "\",\"definitionId\":\"kit\",\"quantity\":1}";
        assertEquals("Mira gives 1 × kit to Warden.", state(SupportedLocale.EN, "ITEM_TRANSFERRED", given));
        assertEquals("Mira dà 1 × kit a Warden.", state(SupportedLocale.IT, "ITEM_TRANSFERRED", given));

        String trade = "{\"actorId\":\"" + PLAYER + "\",\"counterpartyActorId\":\"" + WARDEN + "\","
                + "\"offered\":[{\"definitionId\":\"arrows\",\"quantity\":10}],"
                + "\"requested\":[{\"definitionId\":\"potion\",\"quantity\":1},{\"definitionId\":\"ring\",\"quantity\":2}]}";
        assertEquals("Mira trades with Warden: gives 10 × arrows, receives 1 × potion, 2 × ring.",
                state(SupportedLocale.EN, "ITEM_TRADED", trade));
        assertEquals("Mira scambia con Warden: offre 10 × arrows, riceve 1 × potion, 2 × ring.",
                state(SupportedLocale.IT, "ITEM_TRADED", trade));

        String worn = "{\"actorId\":\"" + PLAYER + "\",\"definitionId\":\"sword\",\"slot\":\"main-hand\",\"hands\":1}";
        assertEquals("Mira equips sword (main-hand).", state(SupportedLocale.EN, "ITEM_EQUIPPED", worn));
        assertEquals("Mira unequips sword (main-hand).", state(SupportedLocale.EN, "ITEM_UNEQUIPPED", worn));
        assertEquals("Mira indossa sword (main-hand).", state(SupportedLocale.IT, "ITEM_EQUIPPED", worn));
        assertEquals("Mira rimuove sword (main-hand).", state(SupportedLocale.IT, "ITEM_UNEQUIPPED", worn));
    }

    /** Travel states its duration only when the link took time; rest and recovery state their numbers in both locales. */
    @Test
    void statesTimeAndRestEvents() {
        String instant = "{\"actorId\":\"" + PLAYER + "\",\"from\":\"trailhead\",\"to\":\"grove\",\"seconds\":0}";
        assertEquals("Mira moved from trailhead to grove.", state(SupportedLocale.EN, "ACTOR_MOVED", instant));
        String legacy = "{\"actorId\":\"" + PLAYER + "\",\"from\":\"trailhead\",\"to\":\"grove\"}";
        assertEquals("Mira moved from trailhead to grove.", state(SupportedLocale.EN, "ACTOR_MOVED", legacy));
        String trip = "{\"actorId\":\"" + PLAYER + "\",\"from\":\"trailhead\",\"to\":\"grove\",\"seconds\":2700}";
        assertEquals("Mira moved from trailhead to grove. The trip takes 45 minutes.", state(SupportedLocale.EN, "ACTOR_MOVED", trip));
        assertEquals("Spostamento di Mira: da trailhead a grove. Il viaggio dura 45 minuti.",
                state(SupportedLocale.IT, "ACTOR_MOVED", trip));
        String brief = "{\"actorId\":\"" + PLAYER + "\",\"from\":\"a\",\"to\":\"b\",\"seconds\":60}";
        assertEquals("Mira moved from a to b. The trip takes 1 minute.", state(SupportedLocale.EN, "ACTOR_MOVED", brief));
        assertEquals("Spostamento di Mira: da a a b. Il viaggio dura 1 minuto.", state(SupportedLocale.IT, "ACTOR_MOVED", brief));
        String odd = "{\"actorId\":\"" + PLAYER + "\",\"from\":\"a\",\"to\":\"b\",\"seconds\":90}";
        assertEquals("Mira moved from a to b. The trip takes 90 seconds.", state(SupportedLocale.EN, "ACTOR_MOVED", odd));

        String done = "{\"actorId\":\"" + PLAYER + "\",\"restId\":\"night\",\"interrupted\":false,\"seconds\":28800}";
        assertEquals("Mira completes the rest night after 480 minutes.", state(SupportedLocale.EN, "REST_COMPLETED", done));
        assertEquals("Mira completa il riposo night dopo 480 minuti.", state(SupportedLocale.IT, "REST_COMPLETED", done));
        String cut = "{\"actorId\":\"" + PLAYER + "\",\"restId\":\"night\",\"interrupted\":true,\"seconds\":14400}";
        assertEquals("Mira is interrupted during the rest night after 240 minutes.", state(SupportedLocale.EN, "REST_INTERRUPTED", cut));
        assertEquals("Mira interrompe il riposo night dopo 240 minuti.", state(SupportedLocale.IT, "REST_INTERRUPTED", cut));
        String recovered = "{\"actorId\":\"" + PLAYER + "\",\"resourceKey\":\"vigor\",\"amount\":5,\"before\":3,\"after\":8}";
        assertEquals("Mira recovers 5 vigor (from 3 to 8).", state(SupportedLocale.EN, "RESOURCE_RECOVERED", recovered));
        assertEquals("Mira recupera 5 di vigor (da 3 a 8).", state(SupportedLocale.IT, "RESOURCE_RECOVERED", recovered));
        String ended = "{\"actorId\":\"" + PLAYER + "\",\"conditionId\":\"dazed\"}";
        assertEquals("Mira is no longer dazed.", state(SupportedLocale.EN, "CONDITION_REMOVED", ended));
        assertEquals("Mira non è più soggetto a dazed.", state(SupportedLocale.IT, "CONDITION_REMOVED", ended));
        // Incomplete payloads fall back to the receipt instead of guessing a number.
        assertEquals(null, state(SupportedLocale.EN, "REST_COMPLETED", "{\"actorId\":\"" + PLAYER + "\",\"restId\":\"night\"}"));
        assertEquals(null, state(SupportedLocale.EN, "RESOURCE_RECOVERED", "{\"actorId\":\"" + PLAYER + "\",\"resourceKey\":\"vigor\"}"));
    }

    /** Encounter entry states the template and the player's zone, and a declared opening strike (P11.1). */
    @Test
    void statesEncounterEntry() {
        String plain = "{\"actorId\":\"" + PLAYER + "\",\"templateId\":\"trail-ambush\",\"zone\":\"entry\"}";
        assertEquals("Combat trail-ambush begins: Mira stands in zone entry.",
                state(SupportedLocale.EN, "ENCOUNTER_STARTED", plain));
        assertEquals("Inizia il combattimento trail-ambush: Mira si trova nella zona entry.",
                state(SupportedLocale.IT, "ENCOUNTER_STARTED", plain));
        String strike = "{\"actorId\":\"" + PLAYER + "\",\"templateId\":\"trail-ambush\",\"zone\":\"entry\","
                + "\"openingStrike\":\"raider\"}";
        assertEquals("Combat trail-ambush begins: Mira stands in zone entry. Opening strike declared against raider.",
                state(SupportedLocale.EN, "ENCOUNTER_STARTED", strike));
        assertEquals("Inizia il combattimento trail-ambush: Mira si trova nella zona entry. Attacco d'apertura "
                + "dichiarato contro raider.", state(SupportedLocale.IT, "ENCOUNTER_STARTED", strike));
        // A malformed strike or a missing zone falls back to the receipt.
        assertEquals(null, state(SupportedLocale.EN, "ENCOUNTER_STARTED", strike.replace("\"raider\"", "7")));
        assertEquals(null, state(SupportedLocale.EN, "ENCOUNTER_STARTED", plain.replace("\"zone\"", "\"area\"")));
    }

    /**
     * Tactical events (P11.2) state the initiative order, round boundaries, activations, zone moves, combat speech and
     * declared actions in both locales; the player is named through the actor, other participants by roster ID.
     */
    @Test
    void statesTacticalEvents() {
        String order = "{\"order\":[{\"participantId\":\"player\",\"total\":17},{\"participantId\":\"raider\",\"total\":13}]}";
        assertEquals("Initiative is rolled: player 17, raider 13.", state(SupportedLocale.EN, "INITIATIVE_ROLLED", order));
        assertEquals("Iniziativa tirata: player 17, raider 13.", state(SupportedLocale.IT, "INITIATIVE_ROLLED", order));
        assertEquals(null, state(SupportedLocale.EN, "INITIATIVE_ROLLED", "{\"order\":[{\"participantId\":\"player\"}]}"));
        assertEquals("Round 2 begins.", state(SupportedLocale.EN, "ROUND_STARTED", "{\"round\":2}"));
        assertEquals("Inizia il round 2.", state(SupportedLocale.IT, "ROUND_STARTED", "{\"round\":2}"));
        assertEquals("Round 1 ends: 8 seconds pass.", state(SupportedLocale.EN, "ROUND_ENDED", "{\"round\":1,\"seconds\":8}"));
        assertEquals("Il round 1 termina: trascorrono 8 secondi.",
                state(SupportedLocale.IT, "ROUND_ENDED", "{\"round\":1,\"seconds\":8}"));
        String mine = "{\"actorId\":\"" + PLAYER + "\",\"participantId\":\"player\",\"actionPoints\":3,\"unusedActionPoints\":2}";
        assertEquals("Mira acts with 3 action points.", state(SupportedLocale.EN, "ACTIVATION_STARTED", mine));
        assertEquals("Turno di Mira: 3 punti azione.", state(SupportedLocale.IT, "ACTIVATION_STARTED", mine));
        String theirs = "{\"participantId\":\"raider\",\"unusedActionPoints\":3}";
        assertEquals("raider ends the turn; 3 action points expire.", state(SupportedLocale.EN, "ACTIVATION_ENDED", theirs));
        assertEquals("Fine del turno di raider: scadono 3 punti azione.",
                state(SupportedLocale.IT, "ACTIVATION_ENDED", theirs));
        String moved = "{\"actorId\":\"" + PLAYER + "\",\"participantId\":\"player\",\"from\":\"entry\",\"to\":\"yard\",\"actionPoints\":1}";
        assertEquals("Mira moves from zone entry to zone yard for 1 action point.",
                state(SupportedLocale.EN, "PARTICIPANT_MOVED", moved));
        assertEquals("Spostamento di Mira: dalla zona entry alla zona yard (1 punto azione).",
                state(SupportedLocale.IT, "PARTICIPANT_MOVED", moved));
        String words = "{\"actorId\":\"" + PLAYER + "\",\"participantId\":\"player\",\"recipientId\":\"raider\",\"text\":\"Why?\"}";
        assertEquals("Mira says to raider: \"Why?\"", state(SupportedLocale.EN, "COMBAT_SPEECH", words));
        assertEquals("Mira dice a raider: \"Why?\"", state(SupportedLocale.IT, "COMBAT_SPEECH", words));
        String social = "{\"actorId\":\"" + PLAYER + "\",\"participantId\":\"player\",\"actionId\":\"demand-passage\","
                + "\"kind\":\"social\",\"intent\":\"negotiate\",\"targetId\":\"smuggler\",\"actionPoints\":1}";
        assertEquals("Mira attempts demand-passage (negotiate) with smuggler for 1 action point.",
                state(SupportedLocale.EN, "INTERACTION_ATTEMPTED", social));
        assertEquals("Mira tenta demand-passage (negotiate) con smuggler (1 punto azione).",
                state(SupportedLocale.IT, "INTERACTION_ATTEMPTED", social));
        String scene = "{\"actorId\":\"" + PLAYER + "\",\"participantId\":\"player\",\"actionId\":\"unbar-gate\","
                + "\"kind\":\"scene\",\"passage\":{\"from\":\"booth\",\"to\":\"gate\",\"blocked\":false},\"actionPoints\":1}";
        assertEquals("Mira uses unbar-gate: the passage between booth and gate is now open for 1 action point.",
                state(SupportedLocale.EN, "INTERACTION_ATTEMPTED", scene));
        assertEquals("Mira usa unbar-gate: il passaggio tra booth e gate ora è aperto (1 punto azione).",
                state(SupportedLocale.IT, "INTERACTION_ATTEMPTED", scene));
        // An unknown actor or a malformed passage falls back to the receipt.
        assertEquals(null, state(SupportedLocale.EN, "COMBAT_SPEECH", words.replace(PLAYER.toString(), UUID.randomUUID().toString())));
        assertEquals(null, state(SupportedLocale.EN, "INTERACTION_ATTEMPTED", scene.replace("false", "\"no\"")));
    }

    /**
     * Strikes state their public outcome and never another participant's defense or Health; Guard, defeat, a lapsed
     * opening strike and an item used in a fight have their own statements (P11.3).
     */
    @Test
    void statesAttacksAndHealing() {
        String common = "{\"actorId\":\"" + PLAYER + "\",\"participantId\":\"player\",\"targetId\":\"raider\","
                + "\"attackId\":\"sword\",\"attackTotal\":";
        String hit = common + "22,\"hit\":true,\"strongHit\":true,\"damage\":7}";
        assertEquals("Mira hits raider with sword (total 22), a strong hit: 7 damage.", state(SupportedLocale.EN, "STRIKE_RESOLVED", hit));
        assertEquals("Mira colpisce raider con sword (totale 22), un colpo forte: 7 danni.", state(SupportedLocale.IT, "STRIKE_RESOLVED", hit));
        String normal = common + "15,\"hit\":true,\"strongHit\":false,\"damage\":0}";
        assertEquals("Mira hits raider with sword (total 15): 0 damage.", state(SupportedLocale.EN, "STRIKE_RESOLVED", normal));
        String miss = common + "9,\"hit\":false,\"strongHit\":false,\"damage\":0}";
        assertEquals("Mira attacks raider with sword (total 9) and misses.", state(SupportedLocale.EN, "STRIKE_RESOLVED", miss));
        assertEquals("Mira attacca raider con sword (totale 9) e manca il bersaglio.", state(SupportedLocale.IT, "STRIKE_RESOLVED", miss));
        String npc = "{\"participantId\":\"bandit\",\"targetId\":\"player\",\"attackId\":\"cudgel\",\"attackTotal\":13,"
                + "\"hit\":false,\"strongHit\":false,\"damage\":0}";
        assertEquals("bandit attacks player with cudgel (total 13) and misses.", state(SupportedLocale.EN, "STRIKE_RESOLVED", npc));
        assertEquals(null, state(SupportedLocale.EN, "STRIKE_RESOLVED", miss.replace("\"hit\":false", "\"hit\":\"no\"")));
        assertEquals(null, state(SupportedLocale.EN, "STRIKE_RESOLVED", hit.replace("\"damage\":7", "\"damage\":\"7\"")));
        String guard = "{\"participantId\":\"bandit\",\"bonus\":2,\"actionPoints\":1}";
        assertEquals("bandit takes a guarded stance: +2 Guard for 1 action point.", state(SupportedLocale.EN, "GUARD_RAISED", guard));
        assertEquals("bandit si mette in guardia: +2 alla Guardia (1 punto azione).", state(SupportedLocale.IT, "GUARD_RAISED", guard));
        assertEquals("raider is defeated.", state(SupportedLocale.EN, "PARTICIPANT_DEFEATED", "{\"participantId\":\"raider\"}"));
        assertEquals("raider è fuori combattimento.", state(SupportedLocale.IT, "PARTICIPANT_DEFEATED", "{\"participantId\":\"raider\"}"));
        String lapse = "{\"actorId\":\"" + PLAYER + "\",\"targetId\":\"raider\",\"reason\":\"OUT_OF_RANGE\"}";
        assertEquals("Mira's opening strike against raider cannot be made.", state(SupportedLocale.EN, "OPENING_STRIKE_LAPSED", lapse));
        assertEquals("L'attacco d'apertura di Mira contro raider non può essere portato.", state(SupportedLocale.IT, "OPENING_STRIKE_LAPSED", lapse));
        String own = "{\"actorId\":\"" + PLAYER + "\",\"definitionId\":\"salve\",\"actionPoints\":2}";
        assertEquals("Mira uses salve for 2 action points.", state(SupportedLocale.EN, "COMBAT_ITEM_USED", own));
        String ally = "{\"actorId\":\"" + PLAYER + "\",\"definitionId\":\"bandage\",\"actionPoints\":2,\"targetId\":\"porter\",\"healed\":4}";
        assertEquals("Mira uses bandage on porter for 2 action points: 4 Health restored.", state(SupportedLocale.EN, "COMBAT_ITEM_USED", ally));
        assertEquals("Mira usa bandage su porter (2 punti azione): recupera 4 Salute.", state(SupportedLocale.IT, "COMBAT_ITEM_USED", ally));
        String kept = "{\"resourceKey\":\"vigor\",\"outcomes\":[{\"actorId\":\"" + PLAYER + "\",\"outcome\":\"NOT_REVIVED\",\"before\":0,\"after\":0}]}";
        assertEquals("Effect: Mira stays defeated (vigor 0).", state(SupportedLocale.EN, "EFFECT_RESOLVED", kept));
        assertEquals("Effetto: Mira resta fuori combattimento (vigor 0).", state(SupportedLocale.IT, "EFFECT_RESOLVED", kept));
    }

    /** Quest events state their transition in both locales, using the canonical quest and outcome identities. */
    @Test
    void statesQuestEvents() {
        String quest = "{\"actorId\":\"" + PLAYER + "\",\"questId\":\"survey\"}";
        assertEquals("Quest available: survey.", state(SupportedLocale.EN, "QUEST_AVAILABLE", quest));
        assertEquals("Missione disponibile: survey.", state(SupportedLocale.IT, "QUEST_AVAILABLE", quest));
        assertEquals("Mira accepts the quest survey.", state(SupportedLocale.EN, "QUEST_ACCEPTED", quest));
        assertEquals("Mira accetta la missione survey.", state(SupportedLocale.IT, "QUEST_ACCEPTED", quest));
        assertEquals("Mira sets the quest survey aside.", state(SupportedLocale.EN, "QUEST_SUSPENDED", quest));
        assertEquals("Mira mette da parte la missione survey.", state(SupportedLocale.IT, "QUEST_SUSPENDED", quest));
        assertEquals("Mira resumes the quest survey.", state(SupportedLocale.EN, "QUEST_RESUMED", quest));
        assertEquals("Mira riprende la missione survey.", state(SupportedLocale.IT, "QUEST_RESUMED", quest));
        String objective = "{\"questId\":\"survey\",\"objectiveId\":\"reach\"}";
        assertEquals("Quest survey: objective reached (reach).", state(SupportedLocale.EN, "QUEST_OBJECTIVE_SATISFIED", objective));
        assertEquals("Missione survey: obiettivo raggiunto (reach).", state(SupportedLocale.IT, "QUEST_OBJECTIVE_SATISFIED", objective));
        String ended = "{\"questId\":\"survey\",\"outcomeId\":\"surveyed\"}";
        assertEquals("Quest survey is completed (outcome surveyed).", state(SupportedLocale.EN, "QUEST_COMPLETED", ended));
        assertEquals("Quest survey has failed (outcome surveyed).", state(SupportedLocale.EN, "QUEST_FAILED", ended));
        assertEquals("Missione survey completata (esito surveyed).", state(SupportedLocale.IT, "QUEST_COMPLETED", ended));
        assertEquals("Missione survey fallita (esito surveyed).", state(SupportedLocale.IT, "QUEST_FAILED", ended));
        // Incomplete payloads and unknown actors state nothing.
        assertEquals(null, state(SupportedLocale.EN, "QUEST_AVAILABLE", "{}"));
        assertEquals(null, state(SupportedLocale.EN, "QUEST_ACCEPTED", "{\"questId\":\"survey\"}"));
        assertEquals(null, state(SupportedLocale.EN, "QUEST_OBJECTIVE_SATISFIED", "{\"questId\":\"survey\"}"));
        assertEquals(null, state(SupportedLocale.EN, "QUEST_COMPLETED", "{\"questId\":\"survey\"}"));
        assertEquals(null, state(SupportedLocale.EN, "QUEST_RESUMED", "{\"actorId\":\"" + UUID.randomUUID() + "\",\"questId\":\"q\"}"));
    }

    /** An unknown actor, a missing field or an empty trade side yields no statement, never an invented one. */
    @Test
    void makesNoStatementFromIncompletePayloads() {
        String stranger = "{\"fromActorId\":\"" + UUID.randomUUID() + "\",\"toActorId\":\"" + WARDEN
                + "\",\"definitionId\":\"kit\",\"quantity\":1}";
        assertEquals(null, state(SupportedLocale.EN, "ITEM_TRANSFERRED", stranger));
        assertEquals(null, state(SupportedLocale.EN, "ITEM_CREATED", "{\"recipientActorId\":\"" + PLAYER + "\",\"definitionId\":\"coin\"}"));
        assertEquals(null, state(SupportedLocale.EN, "ITEM_TRADED", "{\"actorId\":\"" + PLAYER + "\",\"counterpartyActorId\":\""
                + WARDEN + "\",\"offered\":[],\"requested\":[{\"definitionId\":\"potion\",\"quantity\":1}]}"));
        assertEquals(null, state(SupportedLocale.EN, "ITEM_EQUIPPED", "{\"actorId\":\"" + PLAYER + "\",\"definitionId\":\"sword\"}"));
    }

    /** Every actor reference an item payload can hold is collected for the single bounded name lookup. */
    @Test
    void collectsItemActorReferences() {
        var payload = JSON.readTree("{\"fromActorId\":\"" + PLAYER + "\",\"toActorId\":\"" + WARDEN
                + "\",\"counterpartyActorId\":\"" + WARDEN + "\",\"recipientActorId\":\"" + PLAYER + "\"}");
        var actors = EventStatements.referencedActors(payload);
        assertTrue(actors.containsAll(List.of(PLAYER, WARDEN)));
        assertEquals(4, actors.size());
    }
}
