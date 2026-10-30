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
            case "INITIATIVE_ROLLED" -> {
                // P11.2: the stored order, highest first; participant IDs stand for template participants.
                var parts = new ArrayList<String>();
                for (JsonNode line : payload.path("order")) {
                    String participant = token(line, "participantId");
                    Long total = number(line, "total");
                    if (participant == null || total == null) {
                        parts = null;
                        break;
                    }
                    parts.add(participant + " " + total);
                }
                yield parts == null || parts.isEmpty() ? null
                        : (it ? "Iniziativa tirata: " : "Initiative is rolled: ") + String.join(", ", parts) + ".";
            }
            case "ROUND_STARTED" -> {
                Long round = number(payload, "round");
                yield round == null ? null : it ? "Inizia il round " + round + "." : "Round " + round + " begins.";
            }
            case "ROUND_ENDED" -> {
                Long round = number(payload, "round");
                Long seconds = number(payload, "seconds");
                yield round == null || seconds == null ? null
                        : it ? "Il round " + round + " termina: trascorrono " + seconds + " secondi."
                        : "Round " + round + " ends: " + seconds + " seconds pass.";
            }
            case "ACTIVATION_STARTED", "ACTIVATION_ENDED" -> {
                String participant = participant(payload, names);
                boolean start = type.equals("ACTIVATION_STARTED");
                Long points = number(payload, start ? "actionPoints" : "unusedActionPoints");
                yield participant == null || points == null ? null
                        : start ? it ? "Turno di " + participant + ": " + points(true, points) + "."
                                : participant + " acts with " + points(false, points) + "."
                        : it ? "Fine del turno di " + participant + ": " + (points == 1 ? "scade " : "scadono ")
                                + points(true, points) + "."
                                : participant + " ends the turn; " + points(false, points)
                                + (points == 1 ? " expires." : " expire.");
            }
            case "PARTICIPANT_MOVED" -> {
                String participant = participant(payload, names);
                String from = token(payload, "from");
                String to = token(payload, "to");
                Long points = number(payload, "actionPoints");
                yield participant == null || from == null || to == null || points == null ? null
                        : it ? "Spostamento di " + participant + ": dalla zona " + from + " alla zona " + to + " ("
                                + points(true, points) + ")."
                        : participant + " moves from zone " + from + " to zone " + to + " for "
                                + points(false, points) + ".";
            }
            case "COMBAT_SPEECH" -> {
                String speaker = participant(payload, names);
                String recipient = token(payload, "recipientId");
                String words = payload.path("text").isString() ? payload.path("text").asString() : null;
                yield speaker == null || recipient == null || words == null || words.isBlank() ? null
                        : it ? speaker + " dice a " + recipient + ": \"" + words + "\""
                        : speaker + " says to " + recipient + ": \"" + words + "\"";
            }
            case "INTERACTION_ATTEMPTED" -> {
                String participant = participant(payload, names);
                String action = token(payload, "actionId");
                Long points = number(payload, "actionPoints");
                if (participant == null || action == null || points == null) {
                    yield null;
                }
                if ("social".equals(token(payload, "kind"))) {
                    String intent = token(payload, "intent");
                    String target = token(payload, "targetId");
                    yield intent == null || target == null ? null
                            : it ? participant + " tenta " + action + " (" + intent + ") con " + target + " ("
                                    + points(true, points) + ")."
                            : participant + " attempts " + action + " (" + intent + ") with " + target + " for "
                                    + points(false, points) + ".";
                }
                JsonNode passage = payload.path("passage");
                String from = token(passage, "from");
                String to = token(passage, "to");
                if (from == null || to == null || !passage.path("blocked").isBoolean()) {
                    yield null;
                }
                boolean blocked = passage.path("blocked").asBoolean();
                yield it ? participant + " usa " + action + ": il passaggio tra " + from + " e " + to + " ora è "
                        + (blocked ? "chiuso" : "aperto") + " (" + points(true, points) + ")."
                        : participant + " uses " + action + ": the passage between " + from + " and " + to + " is now "
                                + (blocked ? "closed" : "open") + " for " + points(false, points) + ".";
            }
            case "STRIKE_RESOLVED" -> {
                // P11.3: the public outcome of one attack; another participant's defense and Health are never stated.
                String attacker = participant(payload, names);
                String target = token(payload, "targetId");
                String attack = token(payload, "attackId");
                Long total = number(payload, "attackTotal");
                Long damage = number(payload, "damage");
                if (attacker == null || target == null || attack == null || total == null || damage == null
                        || !payload.path("hit").isBoolean()) {
                    yield null;
                }
                if (!payload.path("hit").asBoolean()) {
                    yield it ? attacker + " attacca " + target + " con " + attack + " (totale " + total + ") e manca il bersaglio."
                            : attacker + " attacks " + target + " with " + attack + " (total " + total + ") and misses.";
                }
                boolean strong = payload.path("strongHit").asBoolean();
                yield it ? attacker + " colpisce " + target + " con " + attack + " (totale " + total + ")"
                                + (strong ? ", un colpo forte" : "") + ": " + damage + " danni."
                        : attacker + " hits " + target + " with " + attack + " (total " + total + ")"
                                + (strong ? ", a strong hit" : "") + ": " + damage + " damage.";
            }
            case "OPENING_STRIKE_LAPSED" -> {
                String attacker = participant(payload, names);
                String target = token(payload, "targetId");
                yield attacker == null || target == null ? null
                        : it ? "L'attacco d'apertura di " + attacker + " contro " + target + " non può essere portato."
                        : attacker + "'s opening strike against " + target + " cannot be made.";
            }
            case "GUARD_RAISED" -> {
                String participant = participant(payload, names);
                Long bonus = number(payload, "bonus");
                Long points = number(payload, "actionPoints");
                yield participant == null || bonus == null || points == null ? null
                        : it ? participant + " si mette in guardia: +" + bonus + " alla Guardia ("
                                + points(true, points) + ")."
                        : participant + " takes a guarded stance: +" + bonus + " Guard for " + points(false, points) + ".";
            }
            case "PARTICIPANT_DEFEATED" -> {
                String participant = participant(payload, names);
                yield participant == null ? null
                        : it ? participant + " è fuori combattimento." : participant + " is defeated.";
            }
            case "COMBAT_ITEM_USED" -> {
                String participant = participant(payload, names);
                String item = token(payload, "definitionId");
                Long points = number(payload, "actionPoints");
                if (participant == null || item == null || points == null) {
                    yield null;
                }
                String target = payload.has("targetId") ? token(payload, "targetId") : "";
                Long healed = payload.has("healed") ? number(payload, "healed") : Long.valueOf(0);
                if (target == null || healed == null) {
                    yield null;
                }
                String on = target.isEmpty() ? "" : (it ? " su " : " on ") + target;
                String restored = healed <= 0 ? "" : it ? ": recupera " + healed + " Salute" : ": " + healed + " Health restored";
                yield it ? participant + " usa " + item + on + " (" + points(true, points) + ")" + restored + "."
                        : participant + " uses " + item + on + " for " + points(false, points) + restored + ".";
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
            if (kind.equals("NOT_REVIVED") && before != null) {
                // P11.3: ordinary healing leaves a defeated actor where it is.
                parts.add(it ? actor + " resta fuori combattimento (" + resource + " " + before + ")"
                        : actor + " stays defeated (" + resource + " " + before + ")");
                continue;
            }
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

    /**
     * Name of an encounter participant (P11.2): the display name of the campaign actor behind it when the payload
     * names one, otherwise its participant ID, as template participants have no campaign actor.
     *
     * @return the name, or null when the payload names an unknown actor or no participant
     */
    private static String participant(JsonNode payload, Map<UUID, String> names) {
        if (payload.has("actorId")) {
            return name(payload, "actorId", names);
        }
        return token(payload, "participantId");
    }

    /** @return a count of action points with the singular or plural noun of the locale (P11.2) */
    private static String points(boolean it, long count) {
        return count + (it ? count == 1 ? " punto azione" : " punti azione" : count == 1 ? " action point" : " action points");
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
