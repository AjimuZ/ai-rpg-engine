package org.rpgengine.commands;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.content.PackTextParser;

/**
 * Closed vocabulary of player intentions, never effects or generic field writes.
 * Exploration currently admits movement, waiting, addressed speech and use of a learned
 * ability. Item, trade and encounter intentions must acquire explicit contracts and
 * validators before registration; unknown types fail closed. Structural decoding here
 * is followed by current-state rule validation in {@link CommandRules}; resolution
 * remains backend-owned (P07.4). Every value is immutable and can reproduce its exact
 * version-1 hash input.
 */
public sealed interface TypedCommand
        permits TypedCommand.Move, TypedCommand.Wait, TypedCommand.Speak, TypedCommand.UseAbility {
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
                case "USE_ABILITY" -> {
                    fields(payload, "abilityId", "targetActorIds");
                    if (!(payload.get("targetActorIds") instanceof List<?> targets)
                            || targets.size() > UseAbility.MAX_TARGETS) {
                        throw invalid();
                    }
                    yield new UseAbility(text(payload.get("abilityId"), 256),
                            targets.stream().map(CampaignCommandJson::uuid).toList());
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
        return Set.of("MOVE", "WAIT", "SPEAK", "USE_ABILITY").contains(type);
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

    /**
     * Request to use one learned ability on explicit targets. It names the pinned ability
     * and the actors the player intends to affect; costs, magnitudes, draws and outcomes are
     * never part of the intention. Ownership, prerequisites, target policy, perception and
     * affordable costs are checked against current state by {@link CommandRules}.
     */
    record UseAbility(String abilityId, List<UUID> targetActorIds) implements TypedCommand {
        /** Upper bound of explicit targets, matching the pack contract's per-action target cap. */
        static final int MAX_TARGETS = 8;

        /**
         * Require a bounded stable content reference and at most eight distinct target identities
         * in submitted order. Order is part of the hashed intention; duplicates fail because one
         * action cannot address the same actor twice. An empty list means "no explicit target".
         */
        public UseAbility {
            abilityId = text(abilityId, 256);
            if (targetActorIds == null || targetActorIds.size() > MAX_TARGETS) { throw invalid(); }
            var distinct = new HashSet<UUID>();
            for (UUID target : targetActorIds) {
                if (target == null || !distinct.add(target)) { throw invalid(); }
            }
            targetActorIds = List.copyOf(targetActorIds);
        }
        /** @return the fixed ability-use identifier */
        public String type() { return "USE_ABILITY"; }
        /** @return the ability reference and canonical target UUID texts in submitted order */
        public Map<String, Object> payload() {
            return Map.of("abilityId", abilityId,
                    "targetActorIds", targetActorIds.stream().map(UUID::toString).toList());
        }
    }
}
