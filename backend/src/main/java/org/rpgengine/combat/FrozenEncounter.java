package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.rules.CanonicalJson;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Everything an encounter fixes at entry (P11.1, docs/11 sections 2-4): the resolved combat profile, the zone
 * graph, starting zones, objectives, the opening-action policy and the declared opening strike, and one frozen
 * profile per participant. Freezing is pure: {@link #freeze} reads only its arguments, so the same template,
 * profile and player facts always give byte-identical {@link #definition()} text and hash.
 *
 * <p>The player's frozen profile is derived from the same actor that explores (docs/11 section 4): its role
 * attributes through the profile's role mapping, Guard {@code guardBase + finesse}, Resistance
 * {@code resistanceBase + resolve}, initiative modifier {@code finesse + insight}, and its actual Health and
 * Focus pools. Entry equips nothing, refills nothing and removes nothing; the worn items and active conditions
 * are recorded as they are. Since P11.3 the worn items' {@link EncounterGear} is read here, once: their Guard modifiers
 * (capped together by {@code equipmentGuardCap}) join the Guard above, their armor (capped by {@code armorMax}) is the
 * player's armor, and each weapon becomes a frozen {@link CombatAttack} with the player's rank in its skill. Template
 * participants use their explicit statistics and declared attacks. Since P11.4 the fight also freezes the player's learned
 * abilities that have a combat form, every participant's reactions and initial policies, and the definition of every
 * effect it can reach, so an effect never reads the pack during the fight.
 *
 * @param id encounter identity, derived from the entering operation
 * @param templateId canonical template ID
 * @param location canonical location where the encounter takes place
 * @param profile the frozen combat profile
 * @param zones frozen encounter space
 * @param escapeZones frozen escape zones
 * @param objectives frozen objectives
 * @param opening opening-action policy
 * @param openingTarget hostile participant the player opens against, or null when entry carries no attack
 * @param participants the player first, then template participants in authored order
 * @param actions declared social and scene actions (P11.2), possibly none
 * @param effects frozen definitions of every effect the encounter can reach (P11.4), by canonical ID
 * @param disengagement what an undecided fight becomes at the round cap (P11.5)
 */
public record FrozenEncounter(UUID id, String templateId, String location, CombatProfile profile, ZoneGraph zones,
        Set<String> escapeZones, List<EncounterObjective> objectives, OpeningPolicy opening, String openingTarget,
        List<Participant> participants, List<EncounterAction> actions, Map<String, CombatEffect> effects,
        Disengagement disengagement) {
    /** The only defeat policy of the default ruleset: zero Health incapacitates, it never kills (docs/11 section 13). */
    public static final String DEFEAT_POLICY = "incapacitated";

    /**
     * One frozen participant. Pools of an actor-backed participant (the player) stay in the actor's resource
     * rows; the frozen values here are the readings at entry.
     *
     * @param id roster ID: {@link EncounterTemplate#PLAYER} or the template participant ID
     * @param actorId campaign actor behind the participant, or null for a template-only participant
     * @param side fighting side
     * @param zone starting zone
     * @param npc canonical NPC definition portrayed, or null
     * @param threat catalog threat tier, or null for the player
     * @param health Health at entry
     * @param healthMax Health maximum
     * @param focus Focus at entry, 0 without a Focus mechanic
     * @param focusMax Focus maximum, 0 without a Focus mechanic
     * @param guard Guard defense
     * @param resistance Resistance defense
     * @param armor flat armor
     * @param roles role attribute values
     * @param initiativeModifier {@code finesse + insight}, added to the initiative roll
     * @param equipment canonical item definitions worn at entry, sorted
     * @param conditions canonical conditions active at entry, sorted
     * @param attacks frozen attacks (P11.3), in stable order; omitted from the canonical form when empty
     * @param abilities frozen abilities (P11.4), in stable order; omitted from the canonical form when empty
     * @param reactions frozen reactions beside Brace (P11.4), in stable order; omitted from the canonical form when empty
     * @param policies initial reaction policies (P11.4); these are mutable facts of the row, not part of the frozen form
     */
    public record Participant(String id, UUID actorId, EncounterTemplate.Side side, String zone, String npc,
            EncounterTemplate.Threat threat, long health, long healthMax, long focus, long focusMax, long guard,
            long resistance, long armor, Map<String, Long> roles, long initiativeModifier, List<String> equipment,
            List<String> conditions, List<CombatAttack> attacks, List<CombatAbility> abilities,
            List<CombatReaction> reactions, Map<String, CombatReaction.Policy> policies) {
        /** Require identities; freeze the collections in order. */
        public Participant {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(zone, "zone");
            if (health < 0 || health > healthMax || focus < 0 || focus > focusMax) {
                throw new IllegalArgumentException("participant pools outside their bounds");
            }
            roles = java.util.Collections.unmodifiableMap(new TreeMap<>(roles));
            equipment = equipment.stream().sorted().toList();
            conditions = conditions.stream().sorted().toList();
            attacks = List.copyOf(attacks);
            abilities = List.copyOf(abilities);
            reactions = List.copyOf(reactions);
            policies = java.util.Collections.unmodifiableMap(new TreeMap<>(policies));
        }

        /** @return canonical frozen form, as stored in the participant row */
        public Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("id", id);
            form.put("actorId", actorId == null ? null : actorId.toString());
            form.put("side", side.wire());
            form.put("zone", zone);
            form.put("npc", npc);
            form.put("threat", threat == null ? null : threat.wire());
            form.put("health", health);
            form.put("healthMax", healthMax);
            form.put("focus", focus);
            form.put("focusMax", focusMax);
            form.put("guard", guard);
            form.put("resistance", resistance);
            form.put("armor", armor);
            form.put("roles", roles);
            form.put("initiativeModifier", initiativeModifier);
            form.put("equipment", equipment);
            form.put("conditions", conditions);
            // Omitted when empty, so a participant that cannot attack keeps the form it had before P11.3.
            if (!attacks.isEmpty()) {
                form.put("attacks", attacks.stream().map(attack -> (Object) attack.canonicalForm()).toList());
            }
            // Likewise, so a participant without them keeps the form it had before P11.4.
            if (!abilities.isEmpty()) {
                form.put("abilities", abilities.stream().map(ability -> (Object) ability.canonicalForm()).toList());
            }
            if (!reactions.isEmpty()) {
                form.put("reactions", reactions.stream().map(reaction -> (Object) reaction.canonicalForm()).toList());
            }
            return form;
        }

        /** @return the fighting values the tactical rules read, as {@link EncounterState.Sheet#fromCanonical} reads them */
        public EncounterState.Sheet sheet() {
            var values = new TreeMap<String, Integer>();
            roles.forEach((role, value) -> values.put(role, Math.toIntExact(value)));
            return new EncounterState.Sheet(Math.toIntExact(guard), Math.toIntExact(resistance), Math.toIntExact(armor),
                    values, attacks, abilities, reactions, conditions);
        }
    }

    /**
     * A resource pool reading.
     *
     * @param current current value
     * @param maximum inclusive maximum
     */
    public record Pool(long current, long maximum) {
        /** Reject an inconsistent reading. */
        public Pool {
            if (current < 0 || current > maximum) {
                throw new IllegalArgumentException("pool reading outside its bounds");
            }
        }
    }

    /**
     * The player's committed facts at entry, read under the campaign lock.
     *
     * @param actorId player actor
     * @param attributes unqualified attribute values of the player's vocabulary
     * @param health reading of the profile's Health resource
     * @param focus reading of the profile's Focus resource, or null when the profile or the player has none
     * @param worn canonical item definitions the player wears
     * @param conditions canonical conditions active on the player
     * @param skills trained skill ranks by canonical skill ID, read for the player's weapons
     * @param learned canonical abilities the player has learned, read for their combat forms (P11.4)
     */
    public record PlayerFacts(UUID actorId, Map<String, Long> attributes, Pool health, Pool focus, List<String> worn,
            Set<String> conditions, Map<String, Long> skills, Set<String> learned) {
        /** Facts without skill ranks, for a player whose weapons train none. */
        public PlayerFacts(UUID actorId, Map<String, Long> attributes, Pool health, Pool focus, List<String> worn,
                Set<String> conditions) {
            this(actorId, attributes, health, focus, worn, conditions, Map.of());
        }

        /** Facts without learned abilities, the shape used before P11.4. */
        public PlayerFacts(UUID actorId, Map<String, Long> attributes, Pool health, Pool focus, List<String> worn,
                Set<String> conditions, Map<String, Long> skills) {
            this(actorId, attributes, health, focus, worn, conditions, skills, Set.of());
        }

        /** Require the actor and Health; copy the collections. */
        public PlayerFacts {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(health, "health");
            attributes = Map.copyOf(attributes);
            worn = List.copyOf(worn);
            conditions = Set.copyOf(conditions);
            skills = Map.copyOf(skills);
            learned = Set.copyOf(learned);
        }
    }

    /**
     * The fight forms of pack data the freeze step reads for the player and the effects any participant can reach.
     *
     * @param gear combat data of every item that has any, by canonical item ID
     * @param effects effect definitions by canonical condition ID
     * @param abilities combat forms of abilities by canonical ability ID
     * @param reactions reaction forms of abilities by canonical ability ID
     */
    public record Library(Map<String, EncounterGear> gear, Map<String, CombatEffect> effects,
            Map<String, CombatAbility> abilities, Map<String, CombatReaction> reactions) {
        /** Copy the indexes. */
        public Library {
            gear = Map.copyOf(gear);
            effects = Map.copyOf(effects);
            abilities = Map.copyOf(abilities);
            reactions = Map.copyOf(reactions);
        }
    }

    /** Encounter without frozen effects, the shape used before P11.4. */
    public FrozenEncounter(UUID id, String templateId, String location, CombatProfile profile, ZoneGraph zones,
            Set<String> escapeZones, List<EncounterObjective> objectives, OpeningPolicy opening, String openingTarget,
            List<Participant> participants, List<EncounterAction> actions) {
        this(id, templateId, location, profile, zones, escapeZones, objectives, opening, openingTarget, participants,
                actions, Map.of());
    }

    /** Encounter with the default disengagement policy, the shape used before P11.5. */
    public FrozenEncounter(UUID id, String templateId, String location, CombatProfile profile, ZoneGraph zones,
            Set<String> escapeZones, List<EncounterObjective> objectives, OpeningPolicy opening, String openingTarget,
            List<Participant> participants, List<EncounterAction> actions, Map<String, CombatEffect> effects) {
        this(id, templateId, location, profile, zones, escapeZones, objectives, opening, openingTarget, participants,
                actions, effects, Disengagement.STALEMATE);
    }

    /** Copy the collections; the freeze step established every other invariant. */
    public FrozenEncounter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(zones, "zones");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(disengagement, "disengagement");
        escapeZones = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(escapeZones));
        objectives = List.copyOf(objectives);
        participants = List.copyOf(participants);
        actions = List.copyOf(actions);
        effects = java.util.Collections.unmodifiableMap(new TreeMap<>(effects));
        if (openingTarget != null && opening == OpeningPolicy.FORBIDDEN) {
            throw new IllegalArgumentException("this encounter cannot be opened by an attack");
        }
    }

    /**
     * Freeze an encounter. The caller's rule gate has proved the template applies here, the profile belongs to
     * the player's vocabulary and the opening target is a hostile participant; a contradiction is a programming
     * error, never a silent default.
     *
     * @param id encounter identity
     * @param template validated template
     * @param profile the template pack's resolved combat profile
     * @param player the player's committed facts
     * @param openingTarget hostile participant attacked by the entering action, or null
     * @return the frozen encounter
     * @throws IllegalStateException when a role attribute is missing from the player's sheet, the opening is not
     *         allowed or the target is not a hostile participant
     */
    public static FrozenEncounter freeze(UUID id, EncounterTemplate template, CombatProfile profile, PlayerFacts player,
            String openingTarget) {
        return freeze(id, template, profile, player, openingTarget, Map.of());
    }

    /**
     * Freeze an encounter with the combat data of the items the player wears.
     *
     * @param id encounter identity
     * @param template validated template
     * @param profile the template pack's resolved combat profile
     * @param player the player's committed facts
     * @param openingTarget hostile participant attacked by the entering action, or null
     * @param gear combat data of every item that has any, by canonical item ID; a worn item without an entry adds nothing
     * @return the frozen encounter
     * @throws IllegalStateException when a role attribute is missing from the player's sheet, the opening is not
     *         allowed or the target is not a hostile participant
     */
    public static FrozenEncounter freeze(UUID id, EncounterTemplate template, CombatProfile profile, PlayerFacts player,
            String openingTarget, Map<String, EncounterGear> gear) {
        return freeze(id, template, profile, player, openingTarget, new Library(gear, Map.of(), Map.of(), Map.of()));
    }

    /**
     * Freeze an encounter with everything the content's combat forms supply.
     *
     * @param id encounter identity
     * @param template validated template
     * @param profile the template pack's resolved combat profile
     * @param player the player's committed facts
     * @param openingTarget hostile participant attacked by the entering action, or null
     * @param library the content's combat forms; only what this fight can reach is frozen
     * @return the frozen encounter
     * @throws IllegalStateException when a role attribute is missing from the player's sheet, the opening is not
     *         allowed or the target is not a hostile participant
     */
    public static FrozenEncounter freeze(UUID id, EncounterTemplate template, CombatProfile profile, PlayerFacts player,
            String openingTarget, Library library) {
        return freeze(id, template, profile, player, openingTarget, library, Set.of());
    }

    /**
     * Freeze an encounter that must also hold effects no participant names (P15.4-B): those the template pack's creative
     * profile lets a draft apply or remove, because a fight never reads the pack once it started. With an empty set this is
     * exactly {@link #freeze(UUID, EncounterTemplate, CombatProfile, PlayerFacts, String, Library)}, so a fight without a
     * creative profile keeps its definition text and hash.
     *
     * @param id encounter identity
     * @param template validated template
     * @param profile the template pack's resolved combat profile
     * @param player the player's committed facts
     * @param openingTarget hostile participant attacked by the entering action, or null
     * @param library the content's combat forms; only what this fight can reach is frozen
     * @param available canonical condition IDs the fight must also reach; an ID without a combat form adds nothing
     * @return the frozen encounter
     * @throws IllegalStateException when a role attribute is missing from the player's sheet, the opening is not
     *         allowed or the target is not a hostile participant
     */
    public static FrozenEncounter freeze(UUID id, EncounterTemplate template, CombatProfile profile, PlayerFacts player,
            String openingTarget, Library library, Set<String> available) {
        return freeze(id, template, profile, player, openingTarget, library, available, List.of());
    }

    /**
     * Freeze an encounter whose player also holds learned creative recipes (P15.4-B): abilities the commands layer previewed
     * again against the template pack's creative profile, under IDs no canonical definition can have. They follow the learned
     * abilities in the player's frozen form, and what they apply or remove is reached like any ability's effect. With no
     * recipes this is exactly {@link #freeze(UUID, EncounterTemplate, CombatProfile, PlayerFacts, String, Library, Set)}.
     *
     * @param id encounter identity
     * @param template validated template
     * @param profile the template pack's resolved combat profile
     * @param player the player's committed facts
     * @param openingTarget hostile participant attacked by the entering action, or null
     * @param library the content's combat forms; only what this fight can reach is frozen
     * @param available canonical condition IDs the fight must also reach; an ID without a combat form adds nothing
     * @param recipes the player's recipe abilities, with IDs distinct from each other and from every learned ability
     * @return the frozen encounter
     * @throws IllegalStateException when a role attribute is missing from the player's sheet, the opening is not
     *         allowed, the target is not a hostile participant or two abilities share an ID
     */
    public static FrozenEncounter freeze(UUID id, EncounterTemplate template, CombatProfile profile, PlayerFacts player,
            String openingTarget, Library library, Set<String> available, List<CombatAbility> recipes) {
        var gear = library.gear();
        if (!template.pack().equals(profile.pack())) {
            throw new IllegalStateException("the profile does not belong to the template's pack");
        }
        if (openingTarget != null) {
            var target = template.participant(openingTarget);
            if (template.opening() == OpeningPolicy.FORBIDDEN || target == null
                    || target.side() != EncounterTemplate.Side.HOSTILE) {
                throw new IllegalStateException("validated opening strike has no lawful target");
            }
        }
        var roles = new TreeMap<String, Long>();
        for (String role : CombatProfile.ROLES) {
            Long value = player.attributes().get(profile.roles().get(role));
            if (value == null) {
                // Never a silent zero: a save without the mapped attribute cannot enter this profile's combat.
                throw new IllegalStateException("the player has no value for the " + role + " attribute");
            }
            if (!CombatProfile.roleInRange(value)) {
                // The rule gate refuses this first; a frozen value outside the range could never store its initiative.
                throw new IllegalStateException("the player's " + role + " attribute is outside the supported range");
            }
            roles.put(role, value);
        }
        var focus = profile.focus() == null || player.focus() == null ? new Pool(0, 0) : player.focus();
        // The same combination a Ready/equip action recomputes later (P11.5), so both agree on one set's worth.
        var wornGear = EncounterGear.worn(player.worn(), gear, player.skills(), profile);
        long guard = wornGear.guard();
        long armor = wornGear.armor();
        var attacks = wornGear.attacks();
        // Sorted, so the frozen form follows from what is learned, not from storage order. Brace needs no learning.
        var abilities = new ArrayList<CombatAbility>();
        var reactions = new ArrayList<CombatReaction>();
        var policies = new TreeMap<String, CombatReaction.Policy>();
        policies.put(CombatReaction.BRACE_ID, CombatReaction.Policy.FIRST);
        for (String learned : new java.util.TreeSet<>(player.learned())) {
            if (library.abilities().containsKey(learned)) {
                abilities.add(library.abilities().get(learned));
            }
            if (library.reactions().containsKey(learned)) {
                reactions.add(library.reactions().get(learned));
                policies.put(learned, CombatReaction.Policy.FIRST);
            }
        }
        // Learned recipes follow in the order given (P15.4-B); a shared ID would make one of them unreachable.
        for (CombatAbility recipe : recipes) {
            if (abilities.stream().anyMatch(ability -> ability.id().equals(recipe.id()))) {
                throw new IllegalStateException("two frozen abilities share the ID " + recipe.id());
            }
            abilities.add(recipe);
        }
        var participants = new ArrayList<Participant>();
        participants.add(new Participant(EncounterTemplate.PLAYER, player.actorId(), EncounterTemplate.Side.PLAYER,
                template.playerZone(), null, null, player.health().current(), player.health().maximum(), focus.current(),
                focus.maximum(), profile.value("guardBase") + roles.get("finesse") + guard,
                profile.value("resistanceBase") + roles.get("resolve"), armor, roles,
                roles.get("finesse") + roles.get("insight"), player.worn(), List.copyOf(player.conditions()), attacks,
                abilities, reactions, policies));
        for (EncounterTemplate.Participant participant : template.participants()) {
            var stats = participant.stats();
            var values = new TreeMap<String, Long>();
            stats.roles().forEach((role, value) -> values.put(role, (long) value));
            participants.add(new Participant(participant.id(), null, participant.side(), participant.zone(),
                    participant.npc(), participant.threat(), stats.health(), stats.health(), stats.focus(),
                    stats.focus(), stats.guard(), stats.resistance(), stats.armor(), values,
                    values.get("finesse") + values.get("insight"), List.of(), participant.conditions(),
                    participant.attacks(), List.of(), participant.reactions(), participant.policies()));
        }
        return new FrozenEncounter(id, template.id(), template.location(), profile, template.zones(),
                template.escapeZones(), template.objectives(), template.opening(), openingTarget, participants,
                template.actions().stream().map(action -> action.forPlayer(player.skills())).toList(),
                reachable(participants, library.effects(), available), template.disengagement());
    }

    /**
     * The definitions this fight can reach: what its attacks, reactions and abilities apply or remove, the conditions
     * its participants came with and the {@code available} ones a draft may use. An effect cannot apply another, so one pass
     * finds them all. A condition without a combat block keeps its exploration meaning and has no entry.
     */
    private static Map<String, CombatEffect> reachable(List<Participant> participants, Map<String, CombatEffect> effects,
            Set<String> available) {
        var named = new java.util.TreeSet<String>(available);
        for (Participant participant : participants) {
            named.addAll(participant.conditions());
            participant.attacks().forEach(attack -> attack.onHit().forEach(effect -> named.add(effect.effectId())));
            participant.reactions().forEach(reaction -> {
                if (reaction.effectId() != null) {
                    named.add(reaction.effectId());
                }
            });
            participant.abilities().forEach(ability -> ability.operations().forEach(operation -> {
                if (operation.effectId() != null) {
                    named.add(operation.effectId());
                }
            }));
        }
        var frozen = new TreeMap<String, CombatEffect>();
        for (String id : named) {
            if (effects.containsKey(id)) {
                frozen.put(id, effects.get(id));
            }
        }
        return frozen;
    }

    /** @return the player's frozen participant */
    public Participant player() {
        return participants.getFirst();
    }

    /**
     * Frozen definition as canonical JSON: template, location, kernel, profile hash, zones, escape zones,
     * objectives, opening, defeat policy, roster and, only when the template declares any, its social and scene
     * actions (P11.2; omitting an empty list keeps definitions of action-free templates byte-identical to P11.1). Since
     * P11.5 a disengagement policy other than the default stalemate is recorded as {@code disengagement}; the default is
     * omitted, so a definition without the member reads as a stalemate, as every one frozen before P11.5 does.
     * This text and its hash are stored at entry and never change; the tactical scheduler reads its zones, escape
     * zones and actions back from it.
     *
     * @return canonical JSON object text
     */
    public String definition() {
        var form = new LinkedHashMap<String, Object>();
        form.put("encounterId", id.toString());
        form.put("templateId", templateId);
        form.put("location", location);
        form.put("kernel", CombatProfile.KERNEL);
        form.put("profileHash", profile.hash());
        form.put("space", zones.canonicalForm());
        form.put("playerZone", player().zone());
        form.put("escapeZones", List.copyOf(escapeZones));
        form.put("objectives", objectives.stream().map(objective -> (Object) objective.canonicalForm()).toList());
        var opening = new LinkedHashMap<String, Object>();
        opening.put("policy", this.opening.wire());
        opening.put("strike", openingTarget);
        form.put("opening", opening);
        form.put("defeatPolicy", DEFEAT_POLICY);
        form.put("participants", participants.stream().map(p -> (Object) p.canonicalForm()).toList());
        if (!actions.isEmpty()) {
            form.put("actions", actions.stream().map(action -> (Object) action.canonicalForm()).toList());
        }
        // Likewise for effects (P11.4), so a fight that can reach none keeps its earlier definition text.
        if (!effects.isEmpty()) {
            form.put("effects", effects.values().stream().map(effect -> (Object) effect.canonicalForm()).toList());
        }
        if (disengagement != Disengagement.STALEMATE) {
            form.put("disengagement", disengagement.wire());
        }
        return CanonicalJson.write(form);
    }

    /** @return SHA-256 of {@link #definition()} */
    public String definitionHash() {
        return CanonicalJson.sha256(definition());
    }
}
