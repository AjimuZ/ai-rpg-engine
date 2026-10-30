package org.rpgengine.commands;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.combat.TacticalIntent;
import org.rpgengine.content.PackTextParser;

/**
 * Closed vocabulary of player intentions, never effects or generic field writes.
 * Exploration currently admits movement, waiting, addressed speech, use of a learned
 * ability and, since P10.2, using, equipping, unequipping, giving and trading item instances the
 * player holds. Since P10.3 the player can also accept, suspend and resume a quest by its stable
 * identity; every other quest transition is derived by the engine from committed facts. Since P10.4 the
 * player can also rest under a pack-declared rest policy; its duration, interruption and recovery are
 * rule data, never part of the intention. Since P11.1 the player can enter an authored encounter, optionally
 * declaring the attack that opens it. Since P11.2 the {@link Tactical} intentions are registered for encounter mode:
 * roll initiative, move to an adjacent zone, end the activation, one bounded speech-only utterance and a declared
 * social or scene action; attacks and reactions acquire their contracts with P11.3/P11.4. Unknown types fail closed.
 * Structural decoding here
 * is followed by current-state rule validation in {@link CommandRules}; resolution
 * remains backend-owned (P07.4). Every value is immutable and can reproduce its exact
 * version-1 hash input.
 */
public sealed interface TypedCommand
        permits TypedCommand.Move, TypedCommand.Wait, TypedCommand.Speak, TypedCommand.UseAbility,
        TypedCommand.UseItem, TypedCommand.EquipItem, TypedCommand.UnequipItem, TypedCommand.TransferItem,
        TypedCommand.TradeItems, TypedCommand.AcceptQuest, TypedCommand.SuspendQuest, TypedCommand.ResumeQuest,
        TypedCommand.Rest, TypedCommand.Engage, TypedCommand.RollInitiative, TypedCommand.MoveZone,
        TypedCommand.EndActivation, TypedCommand.CombatSpeak, TypedCommand.Interact {
    /** @return registered, case-sensitive wire identifier */
    String type();

    /** @return immutable closed payload containing intentions only, never calculated effects */
    Map<String, Object> payload();

    /**
     * Normalized payload of the equivalent-attempt identity (P07.5): two intentions with equal
     * attempt payloads at the same base revision are the same deliberate attempt, so a new
     * request for one after the other was cancelled or failed reuses its draw stream instead of
     * re-rolling. Fields whose order or spelling cannot change the mechanical outcome must be
     * normalized here; by default the exact hashed payload is used.
     *
     * @return immutable payload used only to derive the attempt key, never for execution
     */
    default Map<String, Object> attemptPayload() {
        return payload();
    }

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
                case "USE_ITEM" -> {
                    fields(payload, "itemInstanceId", "targetActorIds");
                    if (!(payload.get("targetActorIds") instanceof List<?> targets)
                            || targets.size() > UseAbility.MAX_TARGETS) {
                        throw invalid();
                    }
                    yield new UseItem(CampaignCommandJson.uuid(payload.get("itemInstanceId")),
                            targets.stream().map(CampaignCommandJson::uuid).toList());
                }
                case "EQUIP_ITEM" -> {
                    fields(payload, "itemInstanceId");
                    yield new EquipItem(CampaignCommandJson.uuid(payload.get("itemInstanceId")));
                }
                case "UNEQUIP_ITEM" -> {
                    fields(payload, "itemInstanceId");
                    yield new UnequipItem(CampaignCommandJson.uuid(payload.get("itemInstanceId")));
                }
                case "TRANSFER_ITEM" -> {
                    fields(payload, "itemInstanceId", "recipientActorId", "quantity");
                    yield new TransferItem(CampaignCommandJson.uuid(payload.get("itemInstanceId")),
                            CampaignCommandJson.uuid(payload.get("recipientActorId")), quantity(payload.get("quantity")));
                }
                case "TRADE_ITEMS" -> {
                    fields(payload, "counterpartyActorId", "offered", "requested");
                    yield new TradeItems(CampaignCommandJson.uuid(payload.get("counterpartyActorId")),
                            lines(payload.get("offered")), lines(payload.get("requested")));
                }
                case "ACCEPT_QUEST" -> {
                    fields(payload, "questId");
                    yield new AcceptQuest(text(payload.get("questId"), 256));
                }
                case "SUSPEND_QUEST" -> {
                    fields(payload, "questId");
                    yield new SuspendQuest(text(payload.get("questId"), 256));
                }
                case "RESUME_QUEST" -> {
                    fields(payload, "questId");
                    yield new ResumeQuest(text(payload.get("questId"), 256));
                }
                case "REST" -> {
                    fields(payload, "restId");
                    yield new Rest(text(payload.get("restId"), 256));
                }
                case "ENGAGE" -> {
                    // The opening strike is optional: its key is either absent or names a participant.
                    if (payload.containsKey("openingStrike")) {
                        fields(payload, "encounterId", "openingStrike");
                        yield new Engage(text(payload.get("encounterId"), 256), text(payload.get("openingStrike"), 128));
                    }
                    fields(payload, "encounterId");
                    yield new Engage(text(payload.get("encounterId"), 256), null);
                }
                case "ROLL_INITIATIVE" -> {
                    fields(payload);
                    yield new RollInitiative();
                }
                case "MOVE_ZONE" -> {
                    fields(payload, "zoneId");
                    yield new MoveZone(text(payload.get("zoneId"), 128));
                }
                case "END_ACTIVATION" -> {
                    fields(payload);
                    yield new EndActivation();
                }
                case "COMBAT_SPEAK" -> {
                    fields(payload, "recipientId", "text");
                    yield new CombatSpeak(text(payload.get("recipientId"), 128),
                            text(payload.get("text"), CombatSpeak.MAX_TEXT));
                }
                case "INTERACT" -> {
                    // The target is optional: a social action names one, a scene action none.
                    if (payload.containsKey("targetId")) {
                        fields(payload, "actionId", "targetId");
                        yield new Interact(text(payload.get("actionId"), 128), text(payload.get("targetId"), 128));
                    }
                    fields(payload, "actionId");
                    yield new Interact(text(payload.get("actionId"), 128), null);
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
        return Set.of("MOVE", "WAIT", "SPEAK", "USE_ABILITY", "USE_ITEM", "EQUIP_ITEM", "UNEQUIP_ITEM",
                "TRANSFER_ITEM", "TRADE_ITEMS", "ACCEPT_QUEST", "SUSPEND_QUEST", "RESUME_QUEST", "REST", "ENGAGE",
                "ROLL_INITIATIVE", "MOVE_ZONE", "END_ACTIVATION", "COMBAT_SPEAK", "INTERACT").contains(type);
    }

    /**
     * Tactical intentions (P11.2): legal only in encounter mode, at the decision boundary {@code TacticalRules}
     * checks, and resolved by the combat scheduler from the frozen encounter rather than from pinned definitions.
     */
    sealed interface Tactical permits RollInitiative, MoveZone, EndActivation, CombatSpeak, Interact {
        /** @return the combat-layer value of this intention */
        TacticalIntent intent();
    }

    /** Require an exact integer quantity of 1..9999 without coercion from decimals or text. */
    private static int quantity(Object value) {
        if (!(value instanceof BigInteger number) || number.signum() < 1 || number.compareTo(BigInteger.valueOf(9999)) > 0) {
            throw invalid();
        }
        return number.intValueExact();
    }

    /** Decode one side of a trade: a list of exact {@code {itemInstanceId, quantity}} objects. */
    private static List<TradeItems.Line> lines(Object value) {
        if (!(value instanceof List<?> list) || list.size() > TradeItems.MAX_LINES) {
            throw invalid();
        }
        return list.stream().map(entry -> {
            if (!(entry instanceof Map<?, ?> line)) {
                throw invalid();
            }
            fields(line, "itemInstanceId", "quantity");
            return new TradeItems.Line(CampaignCommandJson.uuid(line.get("itemInstanceId")), quantity(line.get("quantity")));
        }).toList();
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
            // U+0000 cannot be stored in a PostgreSQL text or jsonb value: refuse it here, not as a failed commit.
            if (ch == 0 || Character.isSurrogate(ch) && (!Character.isHighSurrogate(ch)
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
        /** Accept one second through one day as a structural bound; {@link CommandRules} enforces the tighter legal wait. */
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
        /**
         * Target order is hashed but does not change resolution: every effect amount is
         * evaluated once and applied to each target, so reordering targets must not open a
         * fresh draw stream. The attempt payload therefore lists targets sorted.
         *
         * @return the ability reference and target UUID texts in ascending text order
         */
        public Map<String, Object> attemptPayload() {
            return Map.of("abilityId", abilityId,
                    "targetActorIds", targetActorIds.stream().map(UUID::toString).sorted().toList());
        }
    }
    /**
     * Request to use one held item instance on explicit targets. Whether the item can be used, what
     * it does and whether a unit is consumed come from its pinned use contract, never from the
     * intention. Ownership, targets and the effect program are checked by {@link CommandRules}.
     */
    record UseItem(UUID itemInstanceId, List<UUID> targetActorIds) implements TypedCommand {
        /**
         * Require an instance and at most eight distinct target identities in submitted order; an
         * empty list means "no explicit target", as for abilities.
         */
        public UseItem {
            if (itemInstanceId == null || targetActorIds == null || targetActorIds.size() > UseAbility.MAX_TARGETS) {
                throw invalid();
            }
            var distinct = new HashSet<UUID>();
            for (UUID target : targetActorIds) {
                if (target == null || !distinct.add(target)) { throw invalid(); }
            }
            targetActorIds = List.copyOf(targetActorIds);
        }
        /** @return the fixed item-use identifier */
        public String type() { return "USE_ITEM"; }
        /** @return the instance identity and canonical target UUID texts in submitted order */
        public Map<String, Object> payload() {
            return Map.of("itemInstanceId", itemInstanceId.toString(),
                    "targetActorIds", targetActorIds.stream().map(UUID::toString).toList());
        }
        /** @return the instance and its targets in ascending text order, because target order cannot change the outcome */
        public Map<String, Object> attemptPayload() {
            return Map.of("itemInstanceId", itemInstanceId.toString(),
                    "targetActorIds", targetActorIds.stream().map(UUID::toString).sorted().toList());
        }
    }

    /** Request to wear one held item instance; slot, hands and tags come from its definition. */
    record EquipItem(UUID itemInstanceId) implements TypedCommand {
        /** Require an instance identity. */
        public EquipItem { if (itemInstanceId == null) { throw invalid(); } }
        /** @return the fixed equip identifier */
        public String type() { return "EQUIP_ITEM"; }
        /** @return the single instance field */
        public Map<String, Object> payload() { return Map.of("itemInstanceId", itemInstanceId.toString()); }
    }

    /** Request to take off one worn item instance; it stays in the inventory. */
    record UnequipItem(UUID itemInstanceId) implements TypedCommand {
        /** Require an instance identity. */
        public UnequipItem { if (itemInstanceId == null) { throw invalid(); } }
        /** @return the fixed unequip identifier */
        public String type() { return "UNEQUIP_ITEM"; }
        /** @return the single instance field */
        public Map<String, Object> payload() { return Map.of("itemInstanceId", itemInstanceId.toString()); }
    }

    /**
     * Gift of units of one held item instance to a co-present actor. The recipient does not pay and
     * is not asked; an exchange needs {@link TradeItems}.
     */
    record TransferItem(UUID itemInstanceId, UUID recipientActorId, int quantity) implements TypedCommand {
        /** Require both identities and 1..9999 units; the held quantity is a current-state rule. */
        public TransferItem {
            if (itemInstanceId == null || recipientActorId == null || quantity < 1 || quantity > 9999) {
                throw invalid();
            }
        }
        /** @return the fixed transfer identifier */
        public String type() { return "TRANSFER_ITEM"; }
        /** @return instance, recipient and exact unit count */
        public Map<String, Object> payload() {
            return Map.of("itemInstanceId", itemInstanceId.toString(), "recipientActorId", recipientActorId.toString(),
                    "quantity", quantity);
        }
    }

    /**
     * One atomic exchange between the player and a co-present actor: the units the player offers
     * against the units the player requests. The counterparty's acceptance is decided by rule
     * (the valuation balance of {@link CommandRules}), never by wording; a price rumor is not an offer.
     */
    record TradeItems(UUID counterpartyActorId, List<Line> offered, List<Line> requested) implements TypedCommand {
        /** Upper bound of lines per side. */
        static final int MAX_LINES = 8;

        /**
         * Units of one instance.
         *
         * @param itemInstanceId the instance
         * @param quantity units, 1..9999
         */
        public record Line(UUID itemInstanceId, int quantity) {
            /** Require an identity and a bounded unit count. */
            public Line {
                if (itemInstanceId == null || quantity < 1 || quantity > 9999) { throw invalid(); }
            }
            /** @return the canonical object form */
            Map<String, Object> payload() {
                return Map.of("itemInstanceId", itemInstanceId.toString(), "quantity", quantity);
            }
        }

        /**
         * Require a counterparty, one to eight lines per side and no instance named twice across
         * both sides, so one trade can neither pay with what it receives nor request the same stack twice.
         */
        public TradeItems {
            if (counterpartyActorId == null || offered == null || requested == null || offered.isEmpty()
                    || requested.isEmpty() || offered.size() > MAX_LINES || requested.size() > MAX_LINES) {
                throw invalid();
            }
            var distinct = new HashSet<UUID>();
            for (Line line : offered) {
                if (line == null || !distinct.add(line.itemInstanceId())) { throw invalid(); }
            }
            for (Line line : requested) {
                if (line == null || !distinct.add(line.itemInstanceId())) { throw invalid(); }
            }
            offered = List.copyOf(offered);
            requested = List.copyOf(requested);
        }
        /** @return the fixed trade identifier */
        public String type() { return "TRADE_ITEMS"; }
        /** @return counterparty and both ordered line lists */
        public Map<String, Object> payload() {
            return Map.of("counterpartyActorId", counterpartyActorId.toString(),
                    "offered", offered.stream().map(Line::payload).toList(),
                    "requested", requested.stream().map(Line::payload).toList());
        }
    }
    /**
     * Request to take on an available quest. Which quests exist and are available is a current-state
     * rule; a quest the player has not discovered answers exactly like one that does not exist.
     */
    record AcceptQuest(String questId) implements TypedCommand {
        /** Require a bounded stable quest identity. */
        public AcceptQuest { questId = text(questId, 256); }
        /** @return the fixed accept identifier */
        public String type() { return "ACCEPT_QUEST"; }
        /** @return the single quest field */
        public Map<String, Object> payload() { return Map.of("questId", questId); }
    }

    /** Request to set an active quest aside; nothing is tracked for it until it is resumed. */
    record SuspendQuest(String questId) implements TypedCommand {
        /** Require a bounded stable quest identity. */
        public SuspendQuest { questId = text(questId, 256); }
        /** @return the fixed suspend identifier */
        public String type() { return "SUSPEND_QUEST"; }
        /** @return the single quest field */
        public Map<String, Object> payload() { return Map.of("questId", questId); }
    }

    /** Request to return a suspended quest to active; its predicates are evaluated at once. */
    record ResumeQuest(String questId) implements TypedCommand {
        /** Require a bounded stable quest identity. */
        public ResumeQuest { questId = text(questId, 256); }
        /** @return the fixed resume identifier */
        public String type() { return "RESUME_QUEST"; }
        /** @return the single quest field */
        public Map<String, Object> payload() { return Map.of("questId", questId); }
    }

    /**
     * Request to rest under one pack-declared rest policy (P10.4). The intention names only the policy;
     * duration, interruption, restored resources and removed conditions come from the pinned rule.
     */
    record Rest(String restId) implements TypedCommand {
        /** Require a bounded stable policy identity. */
        public Rest { restId = text(restId, 256); }
        /** @return the fixed rest identifier */
        public String type() { return "REST"; }
        /** @return the single policy field */
        public Map<String, Object> payload() { return Map.of("restId", restId); }
    }

    /**
     * Request to enter one authored encounter at the player's location (P11.1). The intention names only the
     * template and, when the entering action is itself an attack, the hostile participant it strikes. Roster,
     * zones, profile, objectives and the slot at which the opening strike resolves come from pinned rules;
     * entry itself resolves no attack.
     *
     * @param encounterId canonical encounter template ID
     * @param openingStrike hostile participant ID of the opening attack, or null when entry carries none
     */
    record Engage(String encounterId, String openingStrike) implements TypedCommand {
        /** Require a bounded template identity and, when present, a bounded participant identity. */
        public Engage {
            encounterId = text(encounterId, 256);
            openingStrike = openingStrike == null ? null : text(openingStrike, 128);
        }
        /** @return the fixed entry identifier */
        public String type() { return "ENGAGE"; }
        /** @return the template field and, only when declared, the opening strike target */
        public Map<String, Object> payload() {
            return openingStrike == null ? Map.of("encounterId", encounterId)
                    : Map.of("encounterId", encounterId, "openingStrike", openingStrike);
        }
    }

    /** Roll initiative once and open round one of the encounter just entered (P11.2); no field to choose. */
    record RollInitiative() implements TypedCommand, Tactical {
        /** @return the fixed initiative identifier */
        public String type() { return "ROLL_INITIATIVE"; }
        /** @return the empty payload */
        public Map<String, Object> payload() { return Map.of(); }
        /** @return the combat-layer intention */
        public TacticalIntent intent() { return new TacticalIntent.RollInitiative(); }
    }

    /**
     * Move the acting player across one edge to an adjacent encounter zone (P11.2). The cost, the edge and the
     * capacity are rule data; the intention names only the destination.
     *
     * @param zoneId destination zone of the frozen encounter
     */
    record MoveZone(String zoneId) implements TypedCommand, Tactical {
        /** Require a bounded zone identity. */
        public MoveZone { zoneId = text(zoneId, 128); }
        /** @return the fixed zone-move identifier */
        public String type() { return "MOVE_ZONE"; }
        /** @return the single destination field */
        public Map<String, Object> payload() { return Map.of("zoneId", zoneId); }
        /** @return the combat-layer intention */
        public TacticalIntent intent() { return new TacticalIntent.MoveZone(zoneId); }
    }

    /** End the player's activation; unused action points expire (P11.2). */
    record EndActivation() implements TypedCommand, Tactical {
        /** @return the fixed end-of-activation identifier */
        public String type() { return "END_ACTIVATION"; }
        /** @return the empty payload */
        public Map<String, Object> payload() { return Map.of(); }
        /** @return the combat-layer intention */
        public TacticalIntent intent() { return new TacticalIntent.EndActivation(); }
    }

    /**
     * One bounded speech-only utterance to an encounter participant (P11.2, docs/11 section 7.1): no action points,
     * no mechanical benefit; the per-activation allowance is rule data.
     *
     * @param recipientId addressed participant ID
     * @param text the player's exact words, 1..{@link #MAX_TEXT} UTF-16 units
     */
    record CombatSpeak(String recipientId, String text) implements TypedCommand, Tactical {
        /** Longest utterance: brief speech, far shorter than exploration speech. */
        static final int MAX_TEXT = 500;

        /** Require a bounded recipient and bounded meaningful text. */
        public CombatSpeak {
            recipientId = TypedCommand.text(recipientId, 128);
            text = TypedCommand.text(text, MAX_TEXT);
        }
        /** @return the fixed combat-speech identifier */
        public String type() { return "COMBAT_SPEAK"; }
        /** @return recipient and exact words */
        public Map<String, Object> payload() { return Map.of("recipientId", recipientId, "text", text); }
        /** @return the combat-layer intention */
        public TacticalIntent intent() { return new TacticalIntent.Speech(recipientId, text); }
    }

    /**
     * Attempt one declared social or scene action of the frozen encounter (P11.2). Its cost and effect are rule
     * data; the intention names the action and, for a social action, the participant addressed.
     *
     * @param actionId declared action ID
     * @param targetId addressed participant, or null for a scene action
     */
    record Interact(String actionId, String targetId) implements TypedCommand, Tactical {
        /** Require a bounded action identity and, when present, a bounded participant identity. */
        public Interact {
            actionId = text(actionId, 128);
            targetId = targetId == null ? null : text(targetId, 128);
        }
        /** @return the fixed interaction identifier */
        public String type() { return "INTERACT"; }
        /** @return the action and, only when declared, the target */
        public Map<String, Object> payload() {
            return targetId == null ? Map.of("actionId", actionId) : Map.of("actionId", actionId, "targetId", targetId);
        }
        /** @return the combat-layer intention */
        public TacticalIntent intent() { return new TacticalIntent.Interact(actionId, targetId); }
    }
}
