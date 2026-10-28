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
                new TypedCommand.UseAbility("dep#mend", List.of(UUID.randomUUID(), UUID.randomUUID())))) {
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
    @ValueSource(strings = {"PATCH", "SET_FIELD", "UPDATE", "BATCH", "ADMIN_CORRECTION", "USE_ITEM",
            "EQUIP_ITEM", "TRANSFER_ITEM", "ATTACK", "FREE_TEXT", "UNKNOWN"})
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
