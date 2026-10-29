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
