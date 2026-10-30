package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Player-safe projection of a stored canonical receipt (P07.4). The stored receipt is an
 * engine record: it can hold other actors' resource values, internal calculation context or
 * details written by trusted internal operations. This allowlist copies only facts the
 * acting player may see once the operation has committed:
 * <ul>
 *   <li>always: committed revision and the event sequence range;</li>
 *   <li>committed draw ordinals, sides and values (never IDs, seeds or algorithms);</li>
 *   <li>only for receipts written by {@link LocalActionResolver}: command type, elapsed
 *       world seconds, turn advancement, destination of a move, the acting player's own
 *       resource changes and the ability calculations, which read only the player's own
 *       state and draws, plus (since P10.2) the item lines of the player's own inventory: units
 *       created, consumed, given, received, equipped or taken off, and (since P10.3) the public quest
 *       transitions the operation caused: discovered, accepted, suspended, resumed, objective reached,
 *       completed or failed, and (since P10.4) the rest summary: policy, whether it was interrupted, the
 *       seconds it took and the conditions it ended, and (since P11.1) the encounter an entry started: its
 *       identity, template, phase, the player's starting zone, the opening policy and the declared opening
 *       strike target (the frozen roster and profile stay in the engine), and (since P11.2) the {@code combat}
 *       summary of a tactical intention: encounter, phase, round, the player's zone, the acting participant and its
 *       remaining action points, the public initiative order and the player's legal options at a decision. Every
 *       participant of an encounter is observable, so none of these values is another actor's private state.</li>
 * </ul>
 * Other actors' values, causal participants, request identities and unknown detail fields
 * are never copied, so a new internal field cannot leak by default. Public since P09.5, because
 * the resume card shows the receipts of committed actions still awaiting narration.
 */
public final class CommandReceipts {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Static projection only. */
    private CommandReceipts() { }

    /**
     * Project one stored receipt.
     *
     * @param stored receipt JSON object written by {@link CanonicalCommitService}
     * @return detached ordered map of allowlisted values
     * @throws IllegalArgumentException if the stored text is not JSON
     */
    public static Map<String, Object> project(String stored) {
        JsonNode root = JSON.readTree(stored);
        var view = new LinkedHashMap<String, Object>();
        view.put("stateRevision", root.path("stateRevision").asLong());
        view.put("firstEventSequence", root.path("firstEventSequence").asLong());
        view.put("lastEventSequence", root.path("lastEventSequence").asLong());
        JsonNode details = root.path("details");
        if (LocalActionResolver.RESOLVER.equals(details.path("resolver").asString(null))) {
            view.put("commandType", details.path("commandType").asString());
            view.put("elapsedSeconds", details.path("elapsedSeconds").asLong());
            view.put("turnAdvanced", details.path("turnAdvanced").asBoolean());
            if (details.path("location").isObject()) {
                view.put("destinationId", details.path("location").path("to").asString());
            }
            String player = details.path("actorId").asString();
            var resources = new ArrayList<Map<String, Object>>();
            for (JsonNode change : details.path("changes")) {
                if (player.equals(change.path("actorId").asString())) {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("resourceKey", change.path("resourceKey").asString());
                    entry.put("before", change.path("before").asLong());
                    entry.put("after", change.path("after").asLong());
                    resources.add(entry);
                }
            }
            view.put("resources", List.copyOf(resources));
            var calculations = new ArrayList<Map<String, Object>>();
            for (JsonNode calculation : details.path("calculations")) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("path", calculation.path("path").asString());
                entry.put("kind", calculation.path("kind").asString());
                JsonNode value = calculation.path("value");
                entry.put("value", value.isBoolean() ? (Object) value.asBoolean() : (Object) value.asLong());
                entry.put("drawOrdinals", ordinals(calculation.path("drawOrdinals")));
                calculations.add(entry);
            }
            view.put("calculations", List.copyOf(calculations));
            if (details.path("items").isArray() && !details.path("items").isEmpty()) {
                // Present only for item-bearing receipts, so earlier receipt shapes stay byte-identical.
                var items = new ArrayList<Map<String, Object>>();
                for (JsonNode line : details.path("items")) {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("kind", line.path("kind").asString());
                    entry.put("definitionId", line.path("definitionId").asString());
                    if (line.path("itemInstanceId").isString()) {
                        entry.put("itemInstanceId", line.path("itemInstanceId").asString());
                    }
                    entry.put("quantity", line.path("quantity").asLong());
                    items.add(entry);
                }
                view.put("items", List.copyOf(items));
            }
            if (details.path("rest").isObject()) {
                // Present only for a rest, so earlier receipt shapes stay byte-identical. The interruption draw
                // itself is listed with the committed draws; nothing here is another actor's state.
                var rest = details.path("rest");
                var entry = new LinkedHashMap<String, Object>();
                entry.put("restId", rest.path("restId").asString());
                entry.put("interrupted", rest.path("interrupted").asBoolean());
                entry.put("seconds", rest.path("seconds").asLong());
                var ended = new ArrayList<String>();
                rest.path("conditionsEnded").forEach(condition -> ended.add(condition.asString()));
                entry.put("conditionsEnded", List.copyOf(ended));
                view.put("rest", java.util.Collections.unmodifiableMap(entry));
            }
            if (details.path("encounter").isObject()) {
                // Present only for an encounter entry, so earlier receipt shapes stay byte-identical.
                var encounter = details.path("encounter");
                var entry = new LinkedHashMap<String, Object>();
                entry.put("encounterId", encounter.path("encounterId").asString());
                entry.put("templateId", encounter.path("templateId").asString());
                entry.put("phase", encounter.path("phase").asString());
                entry.put("zone", encounter.path("zone").asString());
                entry.put("openingPolicy", encounter.path("openingPolicy").asString());
                entry.put("openingStrike", encounter.path("openingStrike").isString()
                        ? encounter.path("openingStrike").asString() : null);
                view.put("encounter", java.util.Collections.unmodifiableMap(entry));
            }
            if (details.path("combat").isObject()) {
                // Present only for a tactical intention (P11.2), so earlier receipt shapes stay byte-identical.
                view.put("combat", combat(details.path("combat")));
            }
            if (details.path("quests").isArray() && !details.path("quests").isEmpty()) {
                // Present only when a public quest transition happened, so earlier receipt shapes stay
                // byte-identical. Steps of hidden background quests are never written to the receipt.
                var quests = new ArrayList<Map<String, Object>>();
                for (JsonNode line : details.path("quests")) {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("kind", line.path("kind").asString());
                    entry.put("questId", line.path("questId").asString());
                    if (line.path("objectiveId").isString()) {
                        entry.put("objectiveId", line.path("objectiveId").asString());
                    }
                    if (line.path("outcomeId").isString()) {
                        entry.put("outcomeId", line.path("outcomeId").asString());
                    }
                    quests.add(entry);
                }
                view.put("quests", List.copyOf(quests));
            }
        }
        var draws = new ArrayList<Map<String, Object>>();
        for (JsonNode draw : root.path("randomDraws")) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("ordinal", draw.path("ordinal").asInt());
            entry.put("sides", draw.path("sides").asInt());
            entry.put("value", draw.path("value").asInt());
            draws.add(entry);
        }
        view.put("randomDraws", List.copyOf(draws));
        return view;
    }

    /**
     * Copy the allowlisted members of a tactical summary: scalar cursor facts, the initiative order, each participant's
     * public standing with any effects it holds, the operation's strikes (never a hidden defense, armor or Health value) and the legal options
     * (moves, speech allowance and recipients, declared actions, attacks with costs, targets and disabled reasons, Guard
     * and item use).
     */
    private static Map<String, Object> combat(JsonNode combat) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("encounterId", combat.path("encounterId").asString());
        entry.put("phase", combat.path("phase").asString());
        entry.put("round", combat.path("round").asInt());
        entry.put("zone", combat.path("zone").asString());
        entry.put("acting", combat.path("acting").isString() ? combat.path("acting").asString() : null);
        entry.put("actionPoints", combat.path("actionPoints").isInt() ? combat.path("actionPoints").asInt() : null);
        var order = new ArrayList<Map<String, Object>>();
        for (JsonNode line : combat.path("initiative")) {
            var position = new LinkedHashMap<String, Object>();
            position.put("participantId", line.path("participantId").asString());
            position.put("initiative", line.path("initiative").asInt());
            order.add(position);
        }
        entry.put("initiative", List.copyOf(order));
        // Each member below is present only when the stored summary has it, so a replayed earlier receipt stays
        // byte-identical (standings and option lines since P11.3, strikes only for an operation with any).
        if (combat.path("participants").isArray()) {
            var standing = new ArrayList<Map<String, Object>>();
            for (JsonNode line : combat.path("participants")) {
                var participant = new LinkedHashMap<String, Object>();
                participant.put("participantId", line.path("participantId").asString());
                participant.put("side", line.path("side").asString());
                participant.put("zone", line.path("zone").asString());
                participant.put("status", line.path("status").asString());
                // Since P11.4, only for a participant holding any: which effects it holds is public, their numbers are not.
                if (line.path("effects").isArray()) {
                    var held = new ArrayList<Map<String, Object>>();
                    for (JsonNode effect : line.path("effects")) {
                        var active = new LinkedHashMap<String, Object>();
                        active.put("effectId", effect.path("effectId").asString());
                        active.put("stacks", effect.path("stacks").asInt());
                        held.add(active);
                    }
                    participant.put("effects", List.copyOf(held));
                }
                standing.add(participant);
            }
            entry.put("participants", List.copyOf(standing));
        }
        var outcomes = new ArrayList<Map<String, Object>>();
        for (JsonNode line : combat.path("strikes")) {
            var strike = new LinkedHashMap<String, Object>();
            strike.put("attackerId", line.path("attackerId").asString());
            strike.put("targetId", line.path("targetId").asString());
            strike.put("attackId", line.path("attackId").asString());
            strike.put("attackTotal", line.path("attackTotal").asInt());
            strike.put("hit", line.path("hit").asBoolean());
            strike.put("strongHit", line.path("strongHit").asBoolean());
            strike.put("damage", line.path("damage").asInt());
            outcomes.add(strike);
        }
        if (!outcomes.isEmpty()) {
            entry.put("strikes", List.copyOf(outcomes));
        }
        JsonNode options = combat.path("options");
        if (options.isObject()) {
            var view = new LinkedHashMap<String, Object>();
            var moves = new ArrayList<Map<String, Object>>();
            for (JsonNode move : options.path("moves")) {
                var line = new LinkedHashMap<String, Object>();
                line.put("zone", move.path("zone").asString());
                line.put("cost", move.path("cost").asInt());
                moves.add(line);
            }
            view.put("moves", List.copyOf(moves));
            var speech = new LinkedHashMap<String, Object>();
            speech.put("remaining", options.path("speech").path("remaining").asInt());
            speech.put("recipients", strings(options.path("speech").path("recipients")));
            view.put("speech", java.util.Collections.unmodifiableMap(speech));
            var actions = new ArrayList<Map<String, Object>>();
            for (JsonNode action : options.path("actions")) {
                var line = new LinkedHashMap<String, Object>();
                line.put("actionId", action.path("actionId").asString());
                line.put("kind", action.path("kind").asString());
                line.put("cost", action.path("cost").asInt());
                line.put("targets", strings(action.path("targets")));
                actions.add(line);
            }
            view.put("actions", List.copyOf(actions));
            if (options.path("strikes").isArray()) {
                var strikes = new ArrayList<Map<String, Object>>();
                for (JsonNode strike : options.path("strikes")) {
                    var line = new LinkedHashMap<String, Object>();
                    line.put("attackId", strike.path("attackId").asString());
                    line.put("cost", strike.path("cost").asInt());
                    line.put("targets", strings(strike.path("targets")));
                    line.put("disabled", strike.path("disabled").isString() ? strike.path("disabled").asString() : null);
                    strikes.add(line);
                }
                view.put("strikes", List.copyOf(strikes));
                for (String choice : List.of("guard", "useItem")) {
                    var line = new LinkedHashMap<String, Object>();
                    line.put("cost", options.path(choice).path("cost").asInt());
                    line.put("available", options.path(choice).path("available").asBoolean());
                    view.put(choice, java.util.Collections.unmodifiableMap(line));
                }
            }
            // Since P11.4, and only in a receipt that has them, so older receipts replay byte-identical.
            if (options.path("abilities").isArray()) {
                var abilities = new ArrayList<Map<String, Object>>();
                for (JsonNode ability : options.path("abilities")) {
                    var line = new LinkedHashMap<String, Object>();
                    line.put("abilityId", ability.path("abilityId").asString());
                    line.put("cost", ability.path("cost").asInt());
                    line.put("focus", ability.path("focus").asInt());
                    line.put("targets", strings(ability.path("targets")));
                    line.put("maxTargets", ability.path("maxTargets").asInt());
                    line.put("disabled", ability.path("disabled").isString() ? ability.path("disabled").asString() : null);
                    abilities.add(line);
                }
                view.put("abilities", List.copyOf(abilities));
                var reactions = new ArrayList<Map<String, Object>>();
                for (JsonNode reaction : options.path("reactions")) {
                    var policy = new LinkedHashMap<String, Object>();
                    policy.put("mode", reaction.path("policy").path("mode").asString());
                    policy.put("value", reaction.path("policy").path("value").asInt());
                    var line = new LinkedHashMap<String, Object>();
                    line.put("reactionId", reaction.path("reactionId").asString());
                    line.put("policy", java.util.Collections.unmodifiableMap(policy));
                    reactions.add(line);
                }
                view.put("reactions", List.copyOf(reactions));
                view.put("reactionsLeft", options.path("reactionsLeft").asInt());
            }
            view.put("endActivation", options.path("endActivation").asBoolean());
            entry.put("options", java.util.Collections.unmodifiableMap(view));
        }
        return java.util.Collections.unmodifiableMap(entry);
    }

    /** Copy a string array. */
    private static List<String> strings(JsonNode array) {
        var values = new ArrayList<String>();
        array.forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }

    /** Copy an integer array. */
    private static List<Integer> ordinals(JsonNode array) {
        var values = new ArrayList<Integer>();
        array.forEach(value -> values.add(value.asInt()));
        return List.copyOf(values);
    }
}
