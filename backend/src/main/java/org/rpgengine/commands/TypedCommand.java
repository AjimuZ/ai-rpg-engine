package org.rpgengine.commands;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.content.PackTextParser;

/**
 * Closed vocabulary of player intentions, never effects or generic field writes.
 * Exploration currently admits movement, waiting and addressed speech. Item, ability,
 * trade and encounter intentions must acquire explicit contracts and validators before
 * registration; unknown types fail closed. Resolution remains backend-owned (P07.4).
 * Every value is immutable and can reproduce its exact version-1 hash input.
 */
public sealed interface TypedCommand permits TypedCommand.Move, TypedCommand.Wait, TypedCommand.Speak {
    /** @return registered, case-sensitive wire identifier */
    String type();

    /** @return immutable closed payload containing intentions only, never calculated effects */
    Map<String, Object> payload();

    /**
     * Decode the already bounded canonical snapshot, not the caller's mutable map.
     * Called for fresh admissions only: historical matching retries must replay before
     * today's registry or eligibility policy is consulted. All malformed payload shapes,
     * unknown fields (including nested patches) and unsupported types share a safe error.
     */
    static TypedCommand decode(byte[] canonical) {
        try {
            var root = (Map<?, ?>) new PackTextParser().parse("canonical-command.json", canonical).value();
            var payload = (Map<?, ?>) root.get("payload");
            return switch ((String) root.get("commandType")) {
                case "MOVE" -> {
                    fields(payload, "destinationId");
                    yield new Move(text(payload.get("destinationId"), 256));
                }
                case "WAIT" -> {
                    fields(payload, "seconds");
                    if (!(payload.get("seconds") instanceof BigInteger seconds)) {
                        throw invalid();
                    }
                    yield new Wait(seconds.longValueExact());
                }
                case "SPEAK" -> {
                    fields(payload, "recipientActorId", "text");
                    yield new Speak(CampaignCommandJson.uuid(payload.get("recipientActorId")),
                            text(payload.get("text"), 4000));
                }
                default -> throw invalid();
            };
        } catch (IllegalArgumentException | ArithmeticException failure) {
            // Never retain submitted text, parser locations or UUID diagnostics.
            throw invalid();
        }
    }

    /** Whether the journal type requires typed evidence at canonical commit. */
    static boolean registered(String type) {
        return Set.of("MOVE", "WAIT", "SPEAK").contains(type);
    }

    /** Require the exact payload field set; missing, extra and effect-bearing keys all fail. */
    private static void fields(Map<?, ?> payload, String... names) {
        if (!Set.of(names).equals(payload.keySet())) {
            throw invalid();
        }
    }

    /** Require meaningful exact text without coercion, trimming or Unicode normalization. */
    private static String text(Object value, int limit) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > limit) {
            throw invalid();
        }
        // Record constructors also serve direct Java callers, independently of JSON decoding.
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isSurrogate(ch) && (!Character.isHighSurrogate(ch)
                    || ++index >= text.length() || !Character.isLowSurrogate(text.charAt(index)))) {
                throw invalid();
            }
        }
        return text;
    }

    /** Build the single sanitized payload/registration failure category. */
    private static OperationAdmissionFailure invalid() {
        return new OperationAdmissionFailure(OperationAdmissionFailure.Kind.INVALID_COMMAND);
    }

    /** Destination intent; reachability, content existence and duration belong to rule validation. */
    record Move(String destinationId) implements TypedCommand {
        /** Require a bounded stable content reference; no location change occurs here. */
        public Move { destinationId = text(destinationId, 256); }
        /** @return the fixed movement identifier */
        public String type() { return "MOVE"; }
        /** @return the single destination field */
        public Map<String, Object> payload() { return Map.of("destinationId", destinationId); }
    }

    /** Bounded requested wait, not an authoritative elapsed-time delta. */
    record Wait(long seconds) implements TypedCommand {
        /** Accept one second through one day; rules may impose a tighter world-clock bound. */
        public Wait {
            if (seconds < 1 || seconds > 86_400) { throw invalid(); }
        }
        /** @return the fixed waiting identifier */
        public String type() { return "WAIT"; }
        /** @return an exact integer request, never a decimal or string coercion */
        public Map<String, Object> payload() { return Map.of("seconds", seconds); }
    }

    /** Player-authored words to an existing actor; no promise or social outcome is implied. */
    record Speak(UUID recipientActorId, String text) implements TypedCommand {
        /** Require a recipient and 1..4000 UTF-16 units of meaningful, scalar Unicode text. */
        public Speak {
            if (recipientActorId == null) { throw invalid(); }
            text = TypedCommand.text(text, 4000);
        }
        /** @return the fixed speech identifier */
        public String type() { return "SPEAK"; }
        /** @return exact submitted wording and canonical recipient identity */
        public Map<String, Object> payload() {
            return Map.of("recipientActorId", recipientActorId.toString(), "text", text);
        }
    }
}
