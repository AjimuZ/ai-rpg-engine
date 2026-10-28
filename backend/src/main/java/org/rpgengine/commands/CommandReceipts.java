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
 *       state and draws.</li>
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

    /** Copy an integer array. */
    private static List<Integer> ordinals(JsonNode array) {
        var values = new ArrayList<Integer>();
        array.forEach(value -> values.add(value.asInt()));
        return List.copyOf(values);
    }
}
