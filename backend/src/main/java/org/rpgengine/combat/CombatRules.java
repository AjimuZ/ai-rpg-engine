package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.Definition;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Prose-free combat projection of a pinned content closure (P11.1): each pack's resolved
 * {@link CombatProfile} and every {@link EncounterTemplate}, keyed by canonical ID. It is derived from a sealed,
 * recompiled {@link CampaignPackSnapshot}; the pack compiler has validated every field, so a failure here is a
 * broken guarantee and propagates as {@link IllegalArgumentException} (the caller treats the content as
 * unavailable rather than guessing a rule).
 *
 * @param profiles resolved combat profile of each pack that declares a {@code combat} rule, by pack ID
 * @param templates encounter templates by canonical ID (local ID in the root pack, {@code pack#id} otherwise)
 */
public record CombatRules(Map<String, CombatProfile> profiles, Map<String, EncounterTemplate> templates) {
    /** Content without combat rules or encounters. */
    public static final CombatRules NONE = new CombatRules(Map.of(), Map.of());

    /** Freeze both indexes and require every template's pack to have a profile whose armor bound it respects. */
    public CombatRules {
        profiles = Map.copyOf(profiles);
        templates = Map.copyOf(templates);
        for (EncounterTemplate template : templates.values()) {
            CombatProfile profile = profiles.get(template.pack());
            if (profile == null) {
                throw new IllegalArgumentException("encounter " + template.id() + " has no combat profile");
            }
            for (EncounterTemplate.Participant participant : template.participants()) {
                if (participant.stats().armor() > profile.value("armorMax")) {
                    throw new IllegalArgumentException("participant armor exceeds the profile's armorMax");
                }
            }
        }
    }

    /**
     * Project a compiled closure.
     *
     * @param snapshot sealed compiled closure; it is not retained
     * @return the detached rules
     */
    public static CombatRules from(CampaignPackSnapshot snapshot) {
        var profiles = new HashMap<String, CombatProfile>();
        var templates = new LinkedHashMap<String, EncounterTemplate>();
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            for (Definition definition : pack.definitions()) {
                if (definition.kind().equals("rule") && "combat".equals(definition.data().get("ruleType"))) {
                    profiles.put(packId, profile(packId, definition.data()));
                }
            }
        }
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            for (Definition definition : pack.definitions()) {
                if (definition.kind().equals("encounter")) {
                    String id = pack == snapshot.root() ? definition.id() : packId + "#" + definition.id();
                    templates.put(id, template(snapshot, pack, id, packId, definition.data()));
                }
            }
        }
        return new CombatRules(profiles, templates);
    }

    /**
     * Resolve one validated {@code combat} rule's data into a profile.
     *
     * @param pack declaring pack ID
     * @param data the rule's validated data object
     * @return resolved profile, defaults filled in
     */
    public static CombatProfile profile(String pack, Map<String, Object> data) {
        var roles = new TreeMap<String, String>();
        ((Map<?, ?>) data.get("roles")).forEach((role, attribute) -> roles.put((String) role, (String) attribute));
        var overrides = new TreeMap<String, Integer>();
        if (data.get("values") instanceof Map<?, ?> values) {
            values.forEach((name, value) -> overrides.put((String) name, ((Number) value).intValue()));
        }
        return CombatProfile.resolve(pack, roles, (String) data.get("health"), (String) data.get("focus"), overrides);
    }

    /** Project one validated encounter definition. */
    private static EncounterTemplate template(CampaignPackSnapshot snapshot, CompiledPack pack, String id,
            String packId, Map<String, Object> data) {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        for (Map<?, ?> zone : objects(data.get("zones"))) {
            String zoneId = (String) zone.get("id");
            zones.put(zoneId, new ZoneGraph.Zone(zoneId, new TreeSet<>(strings(zone.get("tags"))),
                    ZoneGraph.Cover.valueOf(text(zone.get("cover"), "none").toUpperCase(java.util.Locale.ROOT)),
                    "obscured".equals(zone.get("visibility")), number(zone.get("capacity"), 0)));
        }
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (Map<?, ?> edge : objects(data.get("edges"))) {
            boolean difficult = Boolean.TRUE.equals(edge.get("difficult"));
            boolean blocked = Boolean.TRUE.equals(edge.get("blocked"));
            String from = (String) edge.get("from");
            String to = (String) edge.get("to");
            edges.add(new ZoneGraph.Edge(from, to, difficult, blocked));
            if (!Boolean.FALSE.equals(edge.get("bidirectional"))) {
                edges.add(new ZoneGraph.Edge(to, from, difficult, blocked));
            }
        }
        var participants = new ArrayList<EncounterTemplate.Participant>();
        for (Map<?, ?> participant : objects(data.get("participants"))) {
            var stats = (Map<?, ?>) participant.get("stats");
            var roles = new TreeMap<String, Integer>();
            for (String role : CombatProfile.ROLES) {
                roles.put(role, number(stats.get(role), 0));
            }
            String npc = participant.get("npc") instanceof String reference
                    ? snapshot.resolve(pack, reference, "npc").id() : null;
            participants.add(new EncounterTemplate.Participant((String) participant.get("id"),
                    EncounterTemplate.Side.valueOf(((String) participant.get("side")).toUpperCase(java.util.Locale.ROOT)),
                    (String) participant.get("zone"), npc,
                    EncounterTemplate.Threat.valueOf(text(participant.get("threat"), "standard")
                            .toUpperCase(java.util.Locale.ROOT)),
                    new EncounterTemplate.Stats(number(stats.get("health"), 0), number(stats.get("focus"), 0),
                            number(stats.get("guard"), 0), number(stats.get("resistance"), 0),
                            number(stats.get("armor"), 0), roles)));
        }
        var objectives = new ArrayList<EncounterObjective>();
        for (Map<?, ?> objective : objects(data.get("objectives"))) {
            objectives.add(new EncounterObjective((String) objective.get("id"),
                    EncounterObjective.Kind.parse((String) objective.get("kind")), (String) objective.get("zone"),
                    (String) objective.get("participant"), number(objective.get("rounds"), 0)));
        }
        String location = snapshot.resolve(pack, (String) data.get("location"), "location").id();
        return new EncounterTemplate(id, packId, location, new ZoneGraph(zones, edges), (String) data.get("playerZone"),
                new TreeSet<>(strings(data.get("escapeZones"))), participants, objectives,
                OpeningPolicy.parse(text(data.get("opening"), "initiative")));
    }

    /** Object elements of an optional validated list; absent lists are empty. */
    private static List<Map<?, ?>> objects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().<Map<?, ?>>map(element -> (Map<?, ?>) element).toList();
    }

    /** String elements of an optional validated list. */
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(element -> (String) element).toList();
    }

    /** Integer value of an optional validated number. */
    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** Text of an optional validated string. */
    private static String text(Object value, String fallback) {
        return value instanceof String text ? text : Objects.requireNonNull(fallback);
    }
}
