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
                new TypedCommand.Speak(UUID.randomUUID(), "  È qui? 😀\n"))) {
            byte[] canonical = CommandCanonicalForm.encode(command.type(), 3, command.payload(), 32_768);
            assertEquals(command, TypedCommand.decode(canonical));
            assertThrows(UnsupportedOperationException.class, () -> command.payload().put("patch", "bad"));
            var intent = CommandIntent.from(UUID.randomUUID(),
                    new OperationAdmissionService.Command("request", 3, command.type(), command.payload()));
            assertEquals(CommandCanonicalForm.sha256(canonical), intent.payloadHash());
        }
    }

    /** Effect, batch, administrative and not-yet-supported action types cannot masquerade as player intentions. */
    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "SET_FIELD", "UPDATE", "BATCH", "ADMIN_CORRECTION", "USE_ITEM",
            "USE_ABILITY", "TRANSFER_ITEM", "ATTACK", "FREE_TEXT", "UNKNOWN"})
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
    }

    /** Direct record callers cannot bypass the wire validation of bounds or Unicode scalar text. */
    @Test
    void recordConstructorsEnforceContracts() {
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Wait(0));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Move("\ud800"));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Speak(null, "hello"));
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.Speak(UUID.randomUUID(), "\udc00"));
    }

    /** Decode bounded canonical input and require only the sanitized semantic category. */
    private static void assertInvalid(String type, Map<String, Object> payload) {
        var failure = assertThrows(OperationAdmissionFailure.class,
                () -> TypedCommand.decode(CommandCanonicalForm.encode(type, 0, payload, 32_768)));
        assertEquals(OperationAdmissionFailure.Kind.INVALID_COMMAND, failure.kind());
        assertFalse(failure.getMessage().contains(payload.toString()));
    }
}
