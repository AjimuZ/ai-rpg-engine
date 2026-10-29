package org.rpgengine.narration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.rpgengine.inference.contract.PublicFact;
import org.rpgengine.presentation.SupportedLocale;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic selected-locale statements of the public events written by the local resolver
 * (P07.4, event version 1). A statement is the trusted, mechanical wording of what the event
 * established; the narrator may reword it but must cite it, and every number it contains becomes a
 * number the narration may state (docs/21 section 14, grounding rule).
 *
 * <p>Only the closed vocabulary below is narratable. An unknown event type or version, a payload
 * missing a required field, an actor without a known display name, or a statement longer than
 * {@link PublicFact#MAX_TEXT} yields an empty result, and the caller falls back to the
 * deterministic receipt instead of guessing. Statements use the canonical location, ability and
 * resource identifiers, exactly as the player-safe receipt does, until packs supply public display
 * names for them. The Italian wording avoids gendered participles because character gender is not
 * modelled; it still needs the independent human review that every Italian message receives.
 */
public final class EventStatements {
    /** The only event payload version these statements understand. */
    static final int VERSION = 1;

    /** Static helpers only. */
    private EventStatements() {
    }

    /**
     * State one public event.
     *
     * @param locale selected narrative locale
     * @param type stored event type
     * @param version stored event version
     * @param payload stored event payload
     * @param names display names of the campaign actors the payload may reference
     * @return the statement, or empty when the event cannot be stated faithfully
     */
    public static Optional<String> state(SupportedLocale locale, String type, int version, JsonNode payload,
            Map<UUID, String> names) {
        if (version != VERSION || !payload.isObject()) {
            return Optional.empty();
        }
        boolean it = locale == SupportedLocale.IT;
        String text = switch (type) {
            case "ACTOR_MOVED" -> {
                String actor = name(payload, "actorId", names);
                String from = token(payload, "from");
                String to = token(payload, "to");
                Long seconds = number(payload, "seconds");
                // The duration is stated only when the link took time; older events carry none.
                String trip = seconds == null || seconds == 0 ? "" : it ? " Il viaggio dura " + span(true, seconds) + "."
                        : " The trip takes " + span(false, seconds) + ".";
                yield actor == null || from == null || to == null ? null
                        : it ? "Spostamento di " + actor + ": da " + from + " a " + to + "." + trip
                        : actor + " moved from " + from + " to " + to + "." + trip;
            }
            case "TIME_PASSED" -> {
                Long seconds = number(payload, "seconds");
                yield seconds == null ? null
                        : it ? "Attesa: trascorrono " + seconds + " secondi." : "Waiting: " + seconds + " seconds pass.";
            }
            case "REST_COMPLETED", "REST_INTERRUPTED" -> {
                String actor = name(payload, "actorId", names);
                String policy = token(payload, "restId");
                Long seconds = number(payload, "seconds");
                boolean cut = type.equals("REST_INTERRUPTED");
                yield actor == null || policy == null || seconds == null ? null
                        : it ? actor + (cut ? " interrompe il riposo " : " completa il riposo ") + policy + " dopo "
                                + span(true, seconds) + "."
                        : actor + (cut ? " is interrupted during the rest " : " completes the rest ") + policy
                                + " after " + span(false, seconds) + ".";
            }
            case "RESOURCE_RECOVERED" -> {
                String actor = name(payload, "actorId", names);
                String resource = token(payload, "resourceKey");
                Long amount = number(payload, "amount");
                Long before = number(payload, "before");
                Long after = number(payload, "after");
                yield actor == null || resource == null || amount == null || before == null || after == null ? null
                        : it ? actor + " recupera " + amount + " di " + resource + " (da " + before + " a " + after + ")."
                        : actor + " recovers " + amount + " " + resource + " (from " + before + " to " + after + ").";
            }
            case "CONDITION_REMOVED" -> {
                String actor = name(payload, "actorId", names);
                String condition = token(payload, "conditionId");
                yield actor == null || condition == null ? null
                        : it ? actor + " non è più soggetto a " + condition + "." : actor + " is no longer " + condition + ".";
            }
            case "SPEECH_DELIVERED" -> {
                String speaker = name(payload, "speakerActorId", names);
                String recipient = name(payload, "recipientActorId", names);
                String words = payload.path("text").isString() ? payload.path("text").asString() : null;
                yield speaker == null || recipient == null || words == null || words.isBlank() ? null
                        : it ? speaker + " dice a " + recipient + ": \"" + words + "\""
                        : speaker + " says to " + recipient + ": \"" + words + "\"";
            }
            case "ABILITY_USED" -> {
                String actor = name(payload, "actorId", names);
                String ability = token(payload, "abilityId");
                List<String> targets = names(payload.path("targetActorIds"), names);
                if (actor == null || ability == null || targets == null) {
                    yield null;
                }
                String on = targets.isEmpty() ? "" : (it ? " su " : " on ") + String.join(", ", targets);
                yield it ? actor + " usa l'abilità " + ability + on + "." : actor + " uses the ability " + ability + on + ".";
            }
            case "RESOURCE_SPENT" -> {
                String actor = name(payload, "actorId", names);
                String resource = token(payload, "resourceKey");
                Long amount = number(payload, "amount");
                Long before = number(payload, "before");
                Long after = number(payload, "after");
                yield actor == null || resource == null || amount == null || before == null || after == null ? null
                        : it ? "Costo per " + actor + ": " + amount + " di " + resource + " (da " + before + " a " + after + ")."
                        : actor + " spends " + amount + " " + resource + " (from " + before + " to " + after + ").";
            }
            case "EFFECT_RESOLVED" -> effect(it, payload, names);
            case "ITEM_USED" -> {
                String actor = name(payload, "actorId", names);
                String item = token(payload, "definitionId");
                List<String> targets = names(payload.path("targetActorIds"), names);
                if (actor == null || item == null || targets == null) {
                    yield null;
                }
                String on = targets.isEmpty() ? "" : (it ? " su " : " on ") + String.join(", ", targets);
                yield it ? actor + " usa " + item + on + "." : actor + " uses " + item + on + ".";
            }
            case "ITEM_CREATED" -> {
                String actor = name(payload, "recipientActorId", names);
                String item = token(payload, "definitionId");
                Long quantity = number(payload, "quantity");
                yield actor == null || item == null || quantity == null ? null
                        : it ? actor + " ottiene " + quantity + " × " + item + "."
                        : actor + " obtains " + quantity + " × " + item + ".";
            }
            case "ITEM_TRANSFERRED" -> {
                String from = name(payload, "fromActorId", names);
                String to = name(payload, "toActorId", names);
                String item = token(payload, "definitionId");
                Long quantity = number(payload, "quantity");
                yield from == null || to == null || item == null || quantity == null ? null
                        : it ? from + " dà " + quantity + " × " + item + " a " + to + "."
                        : from + " gives " + quantity + " × " + item + " to " + to + ".";
            }
            case "ITEM_TRADED" -> {
                String actor = name(payload, "actorId", names);
                String counterparty = name(payload, "counterpartyActorId", names);
                String offered = lines(payload.path("offered"));
                String requested = lines(payload.path("requested"));
                yield actor == null || counterparty == null || offered == null || requested == null ? null
                        : it ? actor + " scambia con " + counterparty + ": offre " + offered + ", riceve " + requested + "."
                        : actor + " trades with " + counterparty + ": gives " + offered + ", receives " + requested + ".";
            }
            case "ITEM_EQUIPPED", "ITEM_UNEQUIPPED" -> {
                String actor = name(payload, "actorId", names);
                String item = token(payload, "definitionId");
                String slot = token(payload, "slot");
                boolean worn = type.equals("ITEM_EQUIPPED");
                yield actor == null || item == null || slot == null ? null
                        : it ? actor + (worn ? " indossa " : " rimuove ") + item + " (" + slot + ")."
                        : actor + (worn ? " equips " : " unequips ") + item + " (" + slot + ").";
            }
            case "QUEST_AVAILABLE" -> {
                String quest = token(payload, "questId");
                yield quest == null ? null : it ? "Missione disponibile: " + quest + "." : "Quest available: " + quest + ".";
            }
            case "QUEST_ACCEPTED", "QUEST_SUSPENDED", "QUEST_RESUMED" -> {
                String actor = name(payload, "actorId", names);
                String quest = token(payload, "questId");
                yield actor == null || quest == null ? null : switch (type) {
                    case "QUEST_ACCEPTED" -> it ? actor + " accetta la missione " + quest + "."
                            : actor + " accepts the quest " + quest + ".";
                    case "QUEST_SUSPENDED" -> it ? actor + " mette da parte la missione " + quest + "."
                            : actor + " sets the quest " + quest + " aside.";
                    default -> it ? actor + " riprende la missione " + quest + "."
                            : actor + " resumes the quest " + quest + ".";
                };
            }
            case "ENCOUNTER_STARTED" -> {
                // P11.1: entry freezes the fight; an opening strike is declared here and resolved later, once.
                String actor = name(payload, "actorId", names);
                String encounter = token(payload, "templateId");
                String zone = token(payload, "zone");
                String strike = payload.has("openingStrike") ? token(payload, "openingStrike") : "";
                yield actor == null || encounter == null || zone == null || strike == null ? null
                        : it ? "Inizia il combattimento " + encounter + ": " + actor + " si trova nella zona " + zone + "."
                                + (strike.isEmpty() ? "" : " Attacco d'apertura dichiarato contro " + strike + ".")
                        : "Combat " + encounter + " begins: " + actor + " stands in zone " + zone + "."
                                + (strike.isEmpty() ? "" : " Opening strike declared against " + strike + ".");
            }
            case "QUEST_OBJECTIVE_SATISFIED" -> {
                String quest = token(payload, "questId");
                String objective = token(payload, "objectiveId");
                yield quest == null || objective == null ? null
                        : it ? "Missione " + quest + ": obiettivo raggiunto (" + objective + ")."
                        : "Quest " + quest + ": objective reached (" + objective + ").";
            }
            case "QUEST_COMPLETED", "QUEST_FAILED" -> {
                String quest = token(payload, "questId");
                String outcome = token(payload, "outcomeId");
                boolean done = type.equals("QUEST_COMPLETED");
                yield quest == null || outcome == null ? null
                        : it ? "Missione " + quest + (done ? " completata" : " fallita") + " (esito " + outcome + ")."
                        : "Quest " + quest + (done ? " is completed" : " has failed") + " (outcome " + outcome + ").";
            }
            default -> null;
        };
        return text == null || text.length() > PublicFact.MAX_TEXT ? Optional.empty() : Optional.of(text);
    }

    /**
     * State a resolved resource effect. The resolver marks this event public only when every
     * affected actor is the player, so each outcome here is the player's own value.
     */
    private static String effect(boolean it, JsonNode payload, Map<UUID, String> names) {
        String resource = token(payload, "resourceKey");
        if (resource == null || !payload.path("outcomes").isArray() || payload.path("outcomes").isEmpty()) {
            return null;
        }
        var parts = new ArrayList<String>();
        for (JsonNode outcome : payload.path("outcomes")) {
            String actor = name(outcome, "actorId", names);
            String kind = token(outcome, "outcome");
            if (actor == null || kind == null) {
                return null;
            }
            if (kind.equals("NO_RESOURCE")) {
                parts.add(it ? "nessun effetto su " + resource + " per " + actor : actor + " is unaffected (" + resource + ")");
                continue;
            }
            Long before = number(outcome, "before");
            Long after = number(outcome, "after");
            if (!(kind.equals("APPLIED") || kind.equals("CLAMPED")) || before == null || after == null) {
                return null;
            }
            parts.add(it ? resource + " di " + actor + ": da " + before + " a " + after
                    : actor + "'s " + resource + " goes from " + before + " to " + after);
        }
        String joined = String.join("; ", parts);
        return (it ? "Effetto: " : "Effect: ") + joined + ".";
    }

    /**
     * Whole-unit duration wording: minutes when the span is a whole number of minutes, otherwise seconds.
     * Every number printed here is one the narration may state.
     */
    private static String span(boolean it, long seconds) {
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            return it ? minutes + (minutes == 1 ? " minuto" : " minuti") : minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return it ? seconds + (seconds == 1 ? " secondo" : " secondi") : seconds + (seconds == 1 ? " second" : " seconds");
    }

    /** @return the known display name of the actor referenced by {@code field}, or null */
    private static String name(JsonNode node, String field, Map<UUID, String> names) {
        return known(node.path(field), names);
    }

    /** @return the display names of every referenced actor, or null when any is unknown */
    private static List<String> names(JsonNode array, Map<UUID, String> names) {
        if (!array.isArray()) {
            return null;
        }
        var resolved = new ArrayList<String>();
        for (JsonNode entry : array) {
            String name = known(entry, names);
            if (name == null) {
                return null;
            }
            resolved.add(name);
        }
        return resolved;
    }

    /** @return the display name of the actor whose UUID text is {@code reference}, or null */
    private static String known(JsonNode reference, Map<UUID, String> names) {
        if (!reference.isString()) {
            return null;
        }
        try {
            return names.get(UUID.fromString(reference.asString()));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** @return every actor identity a supported payload may reference, for one bounded name lookup */
    public static List<UUID> referencedActors(JsonNode payload) {
        var actors = new ArrayList<UUID>();
        for (String field : List.of("actorId", "speakerActorId", "recipientActorId", "fromActorId", "toActorId",
                "counterpartyActorId")) {
            add(payload.path(field), actors);
        }
        payload.path("targetActorIds").forEach(entry -> add(entry, actors));
        payload.path("outcomes").forEach(outcome -> add(outcome.path("actorId"), actors));
        return actors;
    }

    /** Collect one well-formed UUID reference; anything else is ignored and later reads as unknown. */
    private static void add(JsonNode reference, List<UUID> actors) {
        if (reference.isString()) {
            try {
                actors.add(UUID.fromString(reference.asString()));
            } catch (IllegalArgumentException malformed) {
                // An unknown reference makes the statement empty, which falls back to the receipt.
            }
        }
    }

    /** @return the comma-joined {@code quantity × definition} lines of a trade side, or null when malformed */
    private static String lines(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return null;
        }
        var parts = new ArrayList<String>();
        for (JsonNode line : array) {
            String item = token(line, "definitionId");
            Long quantity = number(line, "quantity");
            if (item == null || quantity == null) {
                return null;
            }
            parts.add(quantity + " × " + item);
        }
        return String.join(", ", parts);
    }

    /** @return a non-blank string field, or null */
    private static String token(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    /** @return an exact integral field, or null */
    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }
}
