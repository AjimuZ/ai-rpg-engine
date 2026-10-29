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
                        List.of(new TypedCommand.TradeItems.Line(UUID.randomUUID(), 5))))) {
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
    private static void assertInvalid(String type, Map<String, Object> payload) {
        var failure = assertThrows(OperationAdmissionFailure.class,
                () -> TypedCommand.decode(CommandCanonicalForm.encode(type, 0, payload, 32_768)));
        assertEquals(OperationAdmissionFailure.Kind.INVALID_COMMAND, failure.kind());
        assertFalse(failure.getMessage().contains(payload.toString()));
    }
}
