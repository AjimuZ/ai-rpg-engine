package org.rpgengine.commands;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Pure rejection corpus for the closed intention vocabulary and immutable hash evidence. */
class TypedCommandTest {
    /** Construct the stateless contract fixture. */
    TypedCommandTest() { }

    /** All supported intentions round-trip without changing the existing canonical identity. */
    @Test
    void supportedIntentionsRoundTripAndPreserveExactText() {
        for (TypedCommand command : List.of(new TypedCommand.Move("harbor:door"), new TypedCommand.Wait(86_400),
                new TypedCommand.Speak(UUID.randomUUID(), "  È qui? 😀\n"), new TypedCommand.UseAbility("steady", List.of()),
                new TypedCommand.UseAbility("dep#mend", List.of(UUID.randomUUID(), UUID.randomUUID())),
                new TypedCommand.UseItem(UUID.randomUUID(), List.of()),
                new TypedCommand.UseItem(UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())),
                new TypedCommand.EquipItem(UUID.randomUUID()), new TypedCommand.UnequipItem(UUID.randomUUID()),
                new TypedCommand.TransferItem(UUID.randomUUID(), UUID.randomUUID(), 9999),
                new TypedCommand.TradeItems(UUID.randomUUID(),
                        List.of(new TypedCommand.TradeItems.Line(UUID.randomUUID(), 2),
                                new TypedCommand.TradeItems.Line(UUID.randomUUID(), 1)),
                        List.of(new TypedCommand.TradeItems.Line(UUID.randomUUID(), 5))),
                new TypedCommand.AcceptQuest("survey"), new TypedCommand.SuspendQuest("dep#survey"),
                new TypedCommand.ResumeQuest("quest.main:1"), new TypedCommand.Rest("night"),
                new TypedCommand.Engage("trail-ambush", null), new TypedCommand.Engage("dep#ambush", "raider"))) {
            byte[] canonical = CommandCanonicalForm.encode(command.type(), 3, command.payload(), 32_768);
            assertEquals(command, TypedCommand.decode(canonical));
            assertThrows(UnsupportedOperationException.class, () -> command.payload().put("patch", "bad"));
            var intent = CommandIntent.from(UUID.randomUUID(),
                    new OperationAdmissionService.Command("request", 3, command.type(), command.payload()));
            assertEquals(CommandCanonicalForm.sha256(canonical), intent.payloadHash());
        }
    }

    /**
     * The equivalent-attempt payload ignores target order, which cannot change resolution, while the
     * hashed payload keeps it; other intentions use their exact payload (P07.5 reroll policy).
     */
    @Test
    void attemptPayloadNormalizesOnlyOrderInsensitiveFields() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID second = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        var forward = new TypedCommand.UseAbility("mend", List.of(first, second));
        var reversed = new TypedCommand.UseAbility("mend", List.of(second, first));
        assertNotEquals(forward.payload(), reversed.payload());
        assertEquals(forward.attemptPayload(), reversed.attemptPayload());
        assertEquals(List.of(first.toString(), second.toString()), reversed.attemptPayload().get("targetActorIds"));
        assertNotEquals(forward.attemptPayload(), new TypedCommand.UseAbility("mend", List.of(first)).attemptPayload());
        for (TypedCommand command : List.of(new TypedCommand.Move("door"), new TypedCommand.Wait(5),
                new TypedCommand.Speak(first, " Exact  text "))) {
            assertEquals(command.payload(), command.attemptPayload());
        }
    }

    /** Effect, batch, administrative and not-yet-supported action types cannot masquerade as player intentions. */
    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "SET_FIELD", "UPDATE", "BATCH", "ADMIN_CORRECTION", "DROP_ITEM", "BUY_ITEM",
            "CRAFT_ITEM", "ATTACK", "FREE_TEXT", "UNKNOWN"})
    void rejectsUnregisteredCommands(String type) {
        assertInvalid(type, Map.of());
    }

    /** Extra keys at any level, effect deltas and type coercion all fail instead of applying a valid subset. */
    @Test
    void rejectsPatchesAndMalformedPayloads() {
        for (Map<String, Object> payload : List.<Map<String, Object>>of(Map.of(), Map.of("destinationId", 1),
                Map.of("destinationId", "door", "patch", Map.of("resources", Map.of("hp", 100))),
                Map.of("destinationId", Map.of("set", "door")), Map.of("destinationId", "door", "delta", -1),
                Map.of("destinationId", "door", "actorId", UUID.randomUUID().toString()),
                Map.of("destinationId", " "), Map.of("destinationId", "x".repeat(257)))) {
            assertInvalid("MOVE", payload);
        }
        for (Object seconds : List.of(-1, 0, 86_401, "1", new BigDecimal("1.0"), Long.MAX_VALUE,
                new java.math.BigInteger("9223372036854775808"))) {
            assertInvalid("WAIT", Map.of("seconds", seconds));
        }
        assertInvalid("WAIT", Map.of("seconds", 1, "cost", -5));
        for (Object recipient : List.of("no-uuid", UUID.randomUUID().toString().toUpperCase(), 1)) {
            assertInvalid("SPEAK", Map.of("recipientActorId", recipient, "text", "hello"));
        }
        assertInvalid("SPEAK", Map.of("recipientActorId", UUID.randomUUID().toString(), "text", " "));
        assertInvalid("SPEAK", Map.of("recipientActorId", UUID.randomUUID().toString(), "text", "x".repeat(4001)));
        // Ability use names a pinned ability and targets only: no costs, magnitudes, rolls or actor overrides.
        String target = UUID.randomUUID().toString();
        for (Map<String, Object> payload : List.<Map<String, Object>>of(Map.of("abilityId", "steady"),
                Map.of("targetActorIds", List.of()), Map.of("abilityId", " ", "targetActorIds", List.of()),
                Map.of("abilityId", "x".repeat(257), "targetActorIds", List.of()),
                Map.of("abilityId", 1, "targetActorIds", List.of()),
                Map.of("abilityId", "steady", "targetActorIds", target),
                Map.of("abilityId", "steady", "targetActorIds", List.of(target, target)),
                Map.of("abilityId", "steady", "targetActorIds", List.of(target.toUpperCase())),
                Map.of("abilityId", "steady", "targetActorIds", List.of(1)),
                Map.of("abilityId", "steady", "targetActorIds", java.util.Collections.nCopies(9, target)),
                Map.of("abilityId", "steady", "targetActorIds", java.util.stream.Stream.generate(
                        () -> UUID.randomUUID().toString()).limit(9).toList()),
                Map.of("abilityId", "steady", "targetActorIds", List.of(), "costs", Map.of("vigor", -1)),
                Map.of("abilityId", "steady", "targetActorIds", List.of(), "actorId", target),
                Map.of("abilityId", "steady", "targetActorIds", List.of(), "roll", 20),
                Map.of("abilityId", "steady", "targetActorIds", List.of(Map.of("id", target))))) {
            assertInvalid("USE_ABILITY", payload);
        }
    }

    /**
     * Item intentions name instances, recipients and unit counts only: no prices, values, slots, targets
     * of a patch, nested overrides, fractional or textual numbers, duplicated instances or empty trades.
     */
    @Test
    void rejectsMalformedItemPayloads() {
        String item = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        String actor = UUID.randomUUID().toString();
        for (Map<String, Object> payload : List.<Map<String, Object>>of(Map.of(), Map.of("itemInstanceId", item),
                Map.of("itemInstanceId", item, "targetActorIds", List.of(), "cost", 1),
                Map.of("itemInstanceId", "not-a-uuid", "targetActorIds", List.of()),
                Map.of("itemInstanceId", item.toUpperCase(), "targetActorIds", List.of()),
                Map.of("itemInstanceId", item, "targetActorIds", List.of(actor, actor)),
                Map.of("itemInstanceId", item, "targetActorIds", actor))) {
            assertInvalid("USE_ITEM", payload);
        }
        for (String type : List.of("EQUIP_ITEM", "UNEQUIP_ITEM")) {
            assertInvalid(type, Map.of());
            assertInvalid(type, Map.of("itemInstanceId", 7));
            assertInvalid(type, Map.of("itemInstanceId", item, "slot", "main-hand"));
        }
        for (Object quantity : List.of(0, -1, 10_000, "2", new BigDecimal("2.0"), true)) {
            assertInvalid("TRANSFER_ITEM", Map.of("itemInstanceId", item, "recipientActorId", actor, "quantity", quantity));
        }
        assertInvalid("TRANSFER_ITEM", Map.of("itemInstanceId", item, "recipientActorId", actor));
        assertInvalid("TRANSFER_ITEM", Map.of("itemInstanceId", item, "recipientActorId", actor, "quantity", 1, "price", 3));
        Map<String, Object> line = Map.of("itemInstanceId", item, "quantity", 1);
        Map<String, Object> otherLine = Map.of("itemInstanceId", other, "quantity", 1);
        for (Map<String, Object> payload : List.<Map<String, Object>>of(
                Map.of("counterpartyActorId", actor, "offered", List.of(line)),
                Map.of("counterpartyActorId", actor, "offered", List.of(), "requested", List.of(otherLine)),
                Map.of("counterpartyActorId", actor, "offered", List.of(line), "requested", List.of()),
                Map.of("counterpartyActorId", actor, "offered", List.of(line), "requested", List.of(line)),
                Map.of("counterpartyActorId", actor, "offered", List.of(line, line), "requested", List.of(otherLine)),
                Map.of("counterpartyActorId", actor, "offered", List.of(line), "requested", List.of(otherLine), "currency", 5),
                Map.of("counterpartyActorId", actor, "offered", List.of(Map.of("itemInstanceId", item, "quantity", 1, "price", 2)),
                        "requested", List.of(otherLine)),
                Map.of("counterpartyActorId", actor, "offered", List.of(Map.of("itemInstanceId", item)), "requested", List.of(otherLine)),
                Map.of("counterpartyActorId", actor, "offered", "all", "requested", List.of(otherLine)),
                Map.of("counterpartyActorId", actor, "offered", java.util.Collections.nCopies(9, line), "requested", List.of(otherLine)),
                Map.of("counterpartyActorId", actor, "offered", java.util.stream.Stream.generate(
                        () -> Map.<String, Object>of("itemInstanceId", UUID.randomUUID().toString(), "quantity", 1)).limit(9).toList(),
                        "requested", List.of(otherLine)))) {
            assertInvalid("TRADE_ITEMS", payload);
        }
        // Order of lines is part of the hashed intention and is kept as submitted.
        var trade = new TypedCommand.TradeItems(UUID.randomUUID(), List.of(new TypedCommand.TradeItems.Line(UUID.randomUUID(), 1),
                new TypedCommand.TradeItems.Line(UUID.randomUUID(), 2)), List.of(new TypedCommand.TradeItems.Line(UUID.randomUUID(), 1)));
        assertEquals(trade, TypedCommand.decode(CommandCanonicalForm.encode(trade.type(), 0, trade.payload(), 32_768)));
        assertThrows(UnsupportedOperationException.class, () -> trade.offered().add(trade.requested().getFirst()));
        // The equivalent-attempt identity of an item use ignores target order, as for abilities.
        UUID instance = UUID.randomUUID();
        UUID first = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID second = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        assertEquals(new TypedCommand.UseItem(instance, List.of(first, second)).attemptPayload(),
                new TypedCommand.UseItem(instance, List.of(second, first)).attemptPayload());
    }

    /**
     * Quest intentions name one stable quest identity and nothing else: no status, outcome, objective or
     * reward can be supplied, and blank, oversized or non-text identities fail like any malformed payload.
     */
    @Test
    void rejectsMalformedQuestPayloads() {
        for (String type : List.of("ACCEPT_QUEST", "SUSPEND_QUEST", "RESUME_QUEST")) {
            assertInvalid(type, Map.of());
            assertInvalid(type, Map.of("questId", 7));
            assertInvalid(type, Map.of("questId", ""));
            assertInvalid(type, Map.of("questId", "   "));
            assertInvalid(type, Map.of("questId", "x".repeat(257)));
            assertInvalid(type, Map.of("questId", "survey", "status", "completed"));
            assertInvalid(type, Map.of("questId", "survey", "outcomeId", "won"));
            assertInvalid(type, Map.of("quest", "survey"));
            assertTrue(TypedCommand.registered(type));
        }
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.AcceptQuest(null));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.SuspendQuest("\ud800"));
        assertEquals("ACCEPT_QUEST", new TypedCommand.AcceptQuest("survey").type());
        assertEquals(Map.of("questId", "survey"), new TypedCommand.ResumeQuest("survey").payload());
    }

    /**
     * A rest names one stable policy and nothing else: duration, recovery, interruption and condition removal
     * are pack rules, so none of them can be supplied, and blank, oversized or non-text identities fail.
     */
    @Test
    void rejectsMalformedRestPayloads() {
        assertTrue(TypedCommand.registered("REST"));
        assertInvalid("REST", Map.of());
        assertInvalid("REST", Map.of("restId", 7));
        assertInvalid("REST", Map.of("restId", ""));
        assertInvalid("REST", Map.of("restId", "x".repeat(257)));
        assertInvalid("REST", Map.of("restId", "night", "seconds", 3600));
        assertInvalid("REST", Map.of("restId", "night", "restores", Map.of("vigor", 99)));
        assertInvalid("REST", Map.of("rest", "night"));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Rest(null));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Rest("\ud800"));
        assertEquals("REST", new TypedCommand.Rest("night").type());
        assertEquals(Map.of("restId", "night"), new TypedCommand.Rest("night").payload());
    }

    /** Direct record callers cannot bypass the wire validation of bounds or Unicode scalar text. */
    @Test
    void recordConstructorsEnforceContracts() {
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Wait(0));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Move("\ud800"));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Speak(null, "hello"));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Speak(UUID.randomUUID(), "\udc00"));
        UUID target = UUID.randomUUID();
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.UseAbility("steady", null));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.UseAbility("steady", List.of(target, target)));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.UseAbility("\ud800", List.of()));
        var targets = new java.util.ArrayList<UUID>(List.of(target));
        var use = new TypedCommand.UseAbility("steady", targets);
        targets.add(UUID.randomUUID());
        // A caller mutating its list after construction cannot change the frozen intention.
        assertEquals(List.of(target), use.targetActorIds());
        assertThrows(UnsupportedOperationException.class, () -> use.targetActorIds().add(target));
    }

    /** Decode bounded canonical input and require only the sanitized semantic category. */
    /** Encounter entry names a template and optionally the opening strike's target, nothing else (P11.1). */
    @Test
    void rejectsMalformedEngagePayloads() {
        assertTrue(TypedCommand.registered("ENGAGE"));
        assertInvalid("ENGAGE", Map.of());
        assertInvalid("ENGAGE", Map.of("encounterId", 7));
        assertInvalid("ENGAGE", Map.of("encounterId", ""));
        assertInvalid("ENGAGE", Map.of("encounterId", "x".repeat(257)));
        assertInvalid("ENGAGE", Map.of("encounterId", "ambush", "openingStrike", ""));
        assertInvalid("ENGAGE", Map.of("encounterId", "ambush", "openingStrike", "x".repeat(129)));
        assertInvalid("ENGAGE", Map.of("encounterId", "ambush", "openingStrike", List.of("raider")));
        assertInvalid("ENGAGE", Map.of("encounterId", "ambush", "zone", "yard"));
        assertInvalid("ENGAGE", Map.of("encounterId", "ambush", "openingStrike", "raider", "damage", 8));
        assertInvalid("ENGAGE", Map.of("encounterId", "ambush", "profile", Map.of("actionPoints", 9)));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Engage(null, null));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Engage("ambush", " "));
        assertEquals("ENGAGE", new TypedCommand.Engage("ambush", null).type());
        assertEquals(Map.of("encounterId", "ambush"), new TypedCommand.Engage("ambush", null).payload());
        assertEquals(Map.of("encounterId", "ambush", "openingStrike", "raider"),
                new TypedCommand.Engage("ambush", "raider").payload());
    }

    /**
     * Tactical intentions (P11.2) carry only the player's choice: nothing for the initiative roll and the end of an
     * activation, a zone, a recipient with at most 500 characters of speech, a declared action with an optional target.
     */
    @Test
    void decodesTacticalIntentionsExactly() {
        for (String type : List.of("ROLL_INITIATIVE", "MOVE_ZONE", "END_ACTIVATION", "COMBAT_SPEAK", "INTERACT", "STRIKE",
                "GUARD", "COMBAT_USE_ITEM")) {
            assertTrue(TypedCommand.registered(type), type);
        }
        assertEquals(new TypedCommand.RollInitiative(), decode("ROLL_INITIATIVE", Map.of()));
        assertEquals(new TypedCommand.EndActivation(), decode("END_ACTIVATION", Map.of()));
        assertEquals(new TypedCommand.MoveZone("yard"), decode("MOVE_ZONE", Map.of("zoneId", "yard")));
        assertEquals(new TypedCommand.CombatSpeak("raider", "Stop."), decode("COMBAT_SPEAK",
                Map.of("recipientId", "raider", "text", "Stop.")));
        assertEquals(new TypedCommand.Interact("taunt", "raider"), decode("INTERACT",
                Map.of("actionId", "taunt", "targetId", "raider")));
        assertEquals(new TypedCommand.Interact("seal-route", null), decode("INTERACT", Map.of("actionId", "seal-route")));
        assertEquals(new org.rpgengine.combat.TacticalIntent.Interact("taunt", "raider"),
                new TypedCommand.Interact("taunt", "raider").intent());
        assertInvalid("ROLL_INITIATIVE", Map.of("dice", List.of(10, 10)));
        assertInvalid("END_ACTIVATION", Map.of("actionPoints", 3));
        assertInvalid("MOVE_ZONE", Map.of());
        assertInvalid("MOVE_ZONE", Map.of("zoneId", ""));
        assertInvalid("MOVE_ZONE", Map.of("zoneId", "x".repeat(129)));
        assertInvalid("MOVE_ZONE", Map.of("zoneId", "yard", "cost", 0));
        assertInvalid("COMBAT_SPEAK", Map.of("recipientId", "raider"));
        assertInvalid("COMBAT_SPEAK", Map.of("recipientId", "raider", "text", " "));
        assertInvalid("COMBAT_SPEAK", Map.of("recipientId", "raider", "text", "x".repeat(501)));
        assertInvalid("COMBAT_SPEAK", Map.of("recipientId", "raider", "text", "Yield.", "effect", "surrender"));
        // PostgreSQL cannot store U+0000: refused here, for every text field, instead of failing at the commit.
        assertInvalid("COMBAT_SPEAK", Map.of("recipientId", "raider", "text", "Stop\u0000now"));
        assertInvalid("COMBAT_SPEAK", Map.of("recipientId", "rai\u0000der", "text", "Stop."));
        assertInvalid("MOVE_ZONE", Map.of("zoneId", "ya\u0000rd"));
        assertInvalid("INTERACT", Map.of("actionId", "\u0000"));
        assertInvalid("INTERACT", Map.of("targetId", "raider"));
        assertInvalid("INTERACT", Map.of("actionId", "taunt", "targetId", ""));
        assertInvalid("INTERACT", Map.of("actionId", "taunt", "outcome", "accepted"));
        // P11.3: a strike names only an attack and a target, Guard nothing, an item use its instance and maybe a target.
        assertEquals(new TypedCommand.Strike("sword", "raider"), decode("STRIKE", Map.of("attackId", "sword", "targetId", "raider")));
        assertEquals(new org.rpgengine.combat.TacticalIntent.Strike("sword", "raider"),
                new TypedCommand.Strike("sword", "raider").intent());
        assertEquals(new TypedCommand.Guard(), decode("GUARD", Map.of()));
        assertEquals(new org.rpgengine.combat.TacticalIntent.Guard(), new TypedCommand.Guard().intent());
        UUID instance = UUID.randomUUID();
        assertEquals(new TypedCommand.CombatUseItem(instance, null),
                decode("COMBAT_USE_ITEM", Map.of("itemInstanceId", instance.toString())));
        assertEquals(new TypedCommand.CombatUseItem(instance, "porter"),
                decode("COMBAT_USE_ITEM", Map.of("itemInstanceId", instance.toString(), "targetId", "porter")));
        assertEquals(new org.rpgengine.combat.TacticalIntent.UseItem("porter"),
                new TypedCommand.CombatUseItem(instance, "porter").intent());
        assertEquals(Map.of("itemInstanceId", instance.toString()), new TypedCommand.CombatUseItem(instance, null).payload());
        assertInvalid("STRIKE", Map.of("attackId", "sword"));
        assertInvalid("STRIKE", Map.of("targetId", "raider"));
        assertInvalid("STRIKE", Map.of("attackId", "sword", "targetId", ""));
        assertInvalid("STRIKE", Map.of("attackId", "sword", "targetId", "x".repeat(129)));
        assertInvalid("STRIKE", Map.of("attackId", "sword", "targetId", "raider", "damage", 99));
        assertInvalid("GUARD", Map.of("bonus", 2));
        assertInvalid("COMBAT_USE_ITEM", Map.of());
        assertInvalid("COMBAT_USE_ITEM", Map.of("itemInstanceId", "not-a-uuid"));
        assertInvalid("COMBAT_USE_ITEM", Map.of("itemInstanceId", instance.toString(), "targetId", ""));
        assertInvalid("COMBAT_USE_ITEM", Map.of("itemInstanceId", instance.toString(), "healed", 8));
        assertEquals(500, decodeSpeech("x".repeat(500)).text().length());
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.MoveZone(null));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Interact("taunt", " "));
        assertEquals(Map.of(), new TypedCommand.EndActivation().payload());
        assertEquals(Map.of("actionId", "seal-route"), new TypedCommand.Interact("seal-route", null).payload());
    }

    private static TypedCommand decode(String type, Map<String, Object> payload) {
        return TypedCommand.decode(CommandCanonicalForm.encode(type, 0, payload, 32_768));
    }

    private static TypedCommand.CombatSpeak decodeSpeech(String text) {
        return (TypedCommand.CombatSpeak) decode("COMBAT_SPEAK", Map.of("recipientId", "raider", "text", text));
    }

    private static void assertInvalid(String type, Map<String, Object> payload) {
        var failure = assertThrows(OperationAdmissionFailure.class,
                () -> TypedCommand.decode(CommandCanonicalForm.encode(type, 0, payload, 32_768)));
        assertEquals(OperationAdmissionFailure.Kind.INVALID_COMMAND, failure.kind());
        assertFalse(failure.getMessage().contains(payload.toString()));
    }
}
