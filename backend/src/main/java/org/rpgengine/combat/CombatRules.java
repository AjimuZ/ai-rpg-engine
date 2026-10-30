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
 * {@link CombatProfile}, every {@link EncounterTemplate}, since P11.3 every item's {@link EncounterGear} and since P11.4
 * the combat forms of conditions and abilities, keyed by canonical ID. It is derived from a sealed,
 * recompiled {@link CampaignPackSnapshot}; the pack compiler has validated every field, so a failure here is a
 * broken guarantee and propagates as {@link IllegalArgumentException} (the caller treats the content as
 * unavailable rather than guessing a rule).
 *
 * @param profiles resolved combat profile of each pack that declares a {@code combat} rule, by pack ID
 * @param templates encounter templates by canonical ID (local ID in the root pack, {@code pack#id} otherwise)
 * @param gear combat data of equippable items that declare any, by canonical item ID
 * @param effects combat forms of conditions that declare one, by canonical condition ID (P11.4)
 * @param abilities active combat forms of abilities that declare one, by canonical ability ID (P11.4)
 * @param reactions reaction forms of abilities that declare one, by canonical ability ID (P11.4)
 */
public record CombatRules(Map<String, CombatProfile> profiles, Map<String, EncounterTemplate> templates,
        Map<String, EncounterGear> gear, Map<String, CombatEffect> effects, Map<String, CombatAbility> abilities,
        Map<String, CombatReaction> reactions) {
    /** Content without combat rules or encounters. */
    public static final CombatRules NONE = new CombatRules(Map.of(), Map.of());

    /** Rules without combat forms of conditions and abilities, the shape used before P11.4. */
    public CombatRules(Map<String, CombatProfile> profiles, Map<String, EncounterTemplate> templates,
            Map<String, EncounterGear> gear) {
        this(profiles, templates, gear, Map.of(), Map.of(), Map.of());
    }

    /** Rules without item combat data, the shape used before P11.3. */
    public CombatRules(Map<String, CombatProfile> profiles, Map<String, EncounterTemplate> templates) {
        this(profiles, templates, Map.of());
    }

    /** Freeze the indexes and require every template's pack to have a profile whose bounds it respects. */
    public CombatRules {
        profiles = Map.copyOf(profiles);
        templates = Map.copyOf(templates);
        gear = Map.copyOf(gear);
        effects = Map.copyOf(effects);
        abilities = Map.copyOf(abilities);
        reactions = Map.copyOf(reactions);
        for (EncounterTemplate template : templates.values()) {
            CombatProfile profile = profiles.get(template.pack());
            if (profile == null) {
                throw new IllegalArgumentException("encounter " + template.id() + " has no combat profile");
            }
            for (EncounterTemplate.Participant participant : template.participants()) {
                if (participant.stats().armor() > profile.value("armorMax")) {
                    throw new IllegalArgumentException("participant armor exceeds the profile's armorMax");
                }
                for (CombatAttack attack : participant.attacks()) {
                    if (attack.range() > profile.value("maxRangeEdges")) {
                        throw new IllegalArgumentException("participant attack range exceeds the profile's maxRangeEdges");
                    }
                }
            }
            for (EncounterAction action : template.actions()) {
                if (action.cost() > profile.actionPoints()) {
                    throw new IllegalArgumentException("declared action cost exceeds one activation's action points");
                }
            }
        }
    }

    /** @return the combat forms the freeze step reads */
    public FrozenEncounter.Library library() {
        return new FrozenEncounter.Library(gear, effects, abilities, reactions);
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
                    templates.put(id, template(snapshot, pack, id, packId, definition.data(), profiles.get(packId)));
                }
            }
        }
        var gear = new HashMap<String, EncounterGear>();
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            for (Definition definition : pack.definitions()) {
                if (definition.kind().equals("item") && definition.data().get("combat") instanceof Map<?, ?> combat) {
                    String id = pack == snapshot.root() ? definition.id() : packId + "#" + definition.id();
                    gear.put(id, gear(snapshot, pack, id, combat));
                }
            }
        }
        var effects = new HashMap<String, CombatEffect>();
        var abilities = new HashMap<String, CombatAbility>();
        var reactions = new HashMap<String, CombatReaction>();
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            for (Definition definition : pack.definitions()) {
                String id = pack == snapshot.root() ? definition.id() : packId + "#" + definition.id();
                if (definition.kind().equals("condition") && definition.data().get("combat") instanceof Map<?, ?> combat) {
                    effects.put(id, effect(id, combat));
                }
                if (definition.kind().equals("ability") && definition.data().get("combat") instanceof Map<?, ?> combat) {
                    if (combat.get("reaction") instanceof Map<?, ?> reaction) {
                        reactions.put(id, reaction(snapshot, pack, id, reaction));
                    } else {
                        abilities.put(id, ability(snapshot, pack, id, combat));
                    }
                }
            }
        }
        return new CombatRules(profiles, templates, gear, effects, abilities, reactions);
    }

    /** Project one validated condition {@code combat} object. */
    private static CombatEffect effect(String id, Map<?, ?> combat) {
        var duration = combat.get("duration") instanceof Map<?, ?> declared ? declared : Map.of();
        var prevented = new TreeSet<CombatEffect.Prevents>();
        strings(combat.get("prevents")).forEach(name ->
                prevented.add(CombatEffect.Prevents.valueOf(name.toUpperCase(java.util.Locale.ROOT))));
        var ticks = objects(combat.get("ticks")).stream().map(tick -> tick.get("heal") instanceof Number heal
                ? new CombatEffect.Tick(CombatEffect.Phase.parse((String) tick.get("phase")), heal.intValue(), true)
                : new CombatEffect.Tick(CombatEffect.Phase.parse((String) tick.get("phase")),
                        number(tick.get("damage"), 0), false)).toList();
        var stacking = CombatEffect.Stacking.valueOf(text(combat.get("stacking"), "strongest").toUpperCase(java.util.Locale.ROOT));
        boolean several = stacking == CombatEffect.Stacking.INDEPENDENT || stacking == CombatEffect.Stacking.ADDITIVE;
        return new CombatEffect(id, text(combat.get("stackingKey"), id), stacking, several ? number(combat.get("cap"), 3) : 1,
                CombatEffect.Clock.parse(text(duration.get("clock"), "roundEnds")), number(duration.get("count"), 1),
                "source".equals(duration.get("actor")), number(combat.get("priority"), 0), number(combat.get("guard"), 0),
                number(combat.get("resistance"), 0), number(combat.get("attack"), 0), number(combat.get("absorption"), 0),
                prevented, ticks, "end".equals(combat.get("sourceDefeated")));
    }

    /** Project one validated reaction object; the player's own form freezes under its ability's ID. */
    private static CombatReaction reaction(CampaignPackSnapshot snapshot, CompiledPack pack, String id, Map<?, ?> reaction) {
        var effect = (Map<?, ?>) reaction.get("effect");
        var kind = CombatReaction.Kind.valueOf(((String) effect.get("type")).toUpperCase(java.util.Locale.ROOT));
        String condition = effect.get("condition") instanceof String reference
                ? snapshot.resolve(pack, reference, "condition").id() : null;
        return new CombatReaction(id, CombatReaction.Trigger.parse((String) reaction.get("trigger")),
                number(reaction.get("priority"), 0), number(reaction.get("focus"), 0),
                "ally".equals(reaction.get("protects")),
                CombatReaction.Attacks.valueOf(text(reaction.get("attacks"), "any").toUpperCase(java.util.Locale.ROOT)),
                kind, number(effect.get("amount"), 0), (String) effect.get("attack"), condition,
                "attacker".equals(effect.get("to")));
    }

    /** Project one validated active ability {@code combat} object. */
    private static CombatAbility ability(CampaignPackSnapshot snapshot, CompiledPack pack, String id, Map<?, ?> combat) {
        var operations = new ArrayList<CombatAbility.Operation>();
        for (Map<?, ?> operation : objects(combat.get("operations"))) {
            var kind = CombatAbility.Kind.valueOf(((String) operation.get("type")).toUpperCase(java.util.Locale.ROOT));
            var dice = operation.get("dice") instanceof Map<?, ?> declared ? declared : Map.of();
            String condition = operation.get("condition") instanceof String reference
                    ? snapshot.resolve(pack, reference, "condition").id() : null;
            operations.add(new CombatAbility.Operation(kind, number(dice.get("count"), 0), number(dice.get("sides"), 0),
                    (String) operation.get("attribute"), number(operation.get("flat"), 0),
                    number(operation.get("penetration"), 0), condition, "self".equals(operation.get("to"))));
        }
        return new CombatAbility(id, number(combat.get("cost"), 1),
                CombatAbility.Limit.valueOf(text(combat.get("limit"), "none").toUpperCase(java.util.Locale.ROOT)),
                number(combat.get("cooldownRounds"), 0), number(combat.get("focus"), 0),
                CombatAbility.Targets.valueOf(text(combat.get("target"), "self").toUpperCase(java.util.Locale.ROOT)),
                number(combat.get("maxTargets"), 1), number(combat.get("range"), 0), operations);
    }

    /** Project one validated policy object; an absent one is the default first eligible trigger. */
    private static CombatReaction.Policy policy(Object declared) {
        if (!(declared instanceof Map<?, ?> policy)) {
            return CombatReaction.Policy.FIRST;
        }
        return new CombatReaction.Policy(CombatReaction.Policy.Mode.find((String) policy.get("mode")),
                number(policy.get("value"), 0));
    }

    /** Project one validated item {@code combat} object; the attack's skill rank waits for its wearer. */
    private static EncounterGear gear(CampaignPackSnapshot snapshot, CompiledPack pack, String id, Map<?, ?> combat) {
        CombatAttack attack = null;
        String skill = null;
        if (combat.get("attack") instanceof Map<?, ?> weapon) {
            skill = weapon.get("skill") instanceof String reference ? snapshot.resolve(pack, reference, "skill").id() : null;
            String ammunition = weapon.get("ammunition") instanceof String reference
                    ? snapshot.resolve(pack, reference, "item").id() : null;
            attack = attack(snapshot, pack, id, 0, ammunition, weapon);
        }
        return new EncounterGear(number(combat.get("guard"), 0), number(combat.get("armor"), 0), skill, attack);
    }

    /** Project one validated attack object: a damage attribute of {@code none} adds nothing, an omitted one follows the attack. */
    private static CombatAttack attack(CampaignPackSnapshot snapshot, CompiledPack pack, String id, int skill,
            String ammunition, Map<?, ?> attack) {
        var damage = (Map<?, ?>) attack.get("damage");
        String attribute = (String) attack.get("attribute");
        String contribution = text(damage.get("attribute"), attribute);
        var onHit = objects(attack.get("onHit")).stream().map(effect -> new CombatAttack.OnHit(
                snapshot.resolve(pack, (String) effect.get("condition"), "condition").id(),
                CombatEffect.Phase.parse(text(effect.get("phase"), "postHit")), "self".equals(effect.get("to")))).toList();
        return new CombatAttack(id, attribute, skill, number(damage.get("count"), 1), number(damage.get("sides"), 0),
                contribution.equals("none") ? null : contribution, number(damage.get("flat"), 0),
                number(attack.get("penetration"), 0), number(attack.get("range"), 0),
                CombatAttack.Defense.valueOf(text(attack.get("defense"), "guard").toUpperCase(java.util.Locale.ROOT)),
                !Boolean.FALSE.equals(attack.get("strongHit")), ammunition, onHit);
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
            String packId, Map<String, Object> data, CombatProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("encounter " + id + " has no combat profile");
        }
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
            // Reactions and their policies (P11.4): Brace is every participant's, first by default.
            var declared = new ArrayList<CombatReaction>();
            var policies = new TreeMap<String, CombatReaction.Policy>();
            policies.put(CombatReaction.BRACE_ID, policy(participant.get("brace")));
            for (Map<?, ?> reaction : objects(participant.get("reactions"))) {
                String reactionId = (String) reaction.get("id");
                declared.add(reaction(snapshot, pack, reactionId, reaction));
                policies.put(reactionId, policy(reaction.get("policy")));
            }
            var conditions = strings(participant.get("conditions")).stream()
                    .map(reference -> snapshot.resolve(pack, reference, "condition").id()).toList();
            participants.add(new EncounterTemplate.Participant((String) participant.get("id"),
                    EncounterTemplate.Side.valueOf(((String) participant.get("side")).toUpperCase(java.util.Locale.ROOT)),
                    (String) participant.get("zone"), npc,
                    EncounterTemplate.Threat.valueOf(text(participant.get("threat"), "standard")
                            .toUpperCase(java.util.Locale.ROOT)),
                    new EncounterTemplate.Stats(number(stats.get("health"), 0), number(stats.get("focus"), 0),
                            number(stats.get("guard"), 0), number(stats.get("resistance"), 0),
                            number(stats.get("armor"), 0), roles),
                    objects(participant.get("attacks")).stream().map(attack -> attack(snapshot, pack,
                            (String) attack.get("id"), number(attack.get("skill"), 0), null, attack)).toList(),
                    declared, policies, conditions));
        }
        var objectives = new ArrayList<EncounterObjective>();
        for (Map<?, ?> objective : objects(data.get("objectives"))) {
            objectives.add(new EncounterObjective((String) objective.get("id"),
                    EncounterObjective.Kind.parse((String) objective.get("kind")), (String) objective.get("zone"),
                    (String) objective.get("participant"), number(objective.get("rounds"), 0)));
        }
        // Declared social and scene actions (P11.2); an omitted cost is the profile's Interact cost.
        int interact = profile.value("interactCost");
        var actions = new ArrayList<EncounterAction>();
        for (Map<?, ?> action : objects(data.get("actions"))) {
            int cost = number(action.get("cost"), interact);
            String actionId = (String) action.get("id");
            if ("social".equals(action.get("kind"))) {
                actions.add(new EncounterAction(actionId, EncounterAction.Kind.SOCIAL,
                        EncounterAction.Intent.valueOf(((String) action.get("intent")).toUpperCase(java.util.Locale.ROOT)),
                        EncounterAction.Recipients.valueOf(text(action.get("recipients"), "hostile")
                                .toUpperCase(java.util.Locale.ROOT)), cost, null, null));
            } else {
                var passage = (Map<?, ?>) action.get("passage");
                actions.add(new EncounterAction(actionId, EncounterAction.Kind.SCENE, null, null, cost,
                        action.get("zone") instanceof String zone ? zone : null,
                        new EncounterAction.Passage((String) passage.get("from"), (String) passage.get("to"),
                                Boolean.TRUE.equals(passage.get("blocked")))));
            }
        }
        String location = snapshot.resolve(pack, (String) data.get("location"), "location").id();
        return new EncounterTemplate(id, packId, location, new ZoneGraph(zones, edges), (String) data.get("playerZone"),
                new TreeSet<>(strings(data.get("escapeZones"))), participants, objectives,
                OpeningPolicy.parse(text(data.get("opening"), "initiative")), actions);
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
