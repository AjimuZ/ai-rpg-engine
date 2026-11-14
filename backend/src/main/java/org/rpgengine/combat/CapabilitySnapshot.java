package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.rpgengine.rules.CanonicalJson;
import org.rpgengine.rules.CombatProfile;

/**
 * The party's mechanical capabilities that encounter planning reads before entry (P13.1, docs/12 section 2): the content
 * and combat-profile hashes, the campaign revision the facts were read at, the location and scene tags, the selected
 * difficulty policy and, for every participating actor, its level, Health, the spendable combat resources it relies on,
 * worn equipment, active conditions, learned abilities, skill ranks and accessible ammunition.
 *
 * <p>The snapshot is a pure value. Its {@link #canonical()} text is independent of the order and map implementation the
 * caller used, so equal capabilities always give the same {@link #hash()}; a plan made from one snapshot is stale as soon
 * as the hash of a fresh reading differs, and must then be validated again or replaced (docs/12 section 2).
 *
 * <p>What the snapshot deliberately does not hold: items in storage (only worn items are equipment), abilities the actor
 * has not learned, absent companions' capabilities (an absent actor is recorded but contributes nothing) and rewards not
 * yet accepted. Known threats, mobility, damage and defense coverage, escape options, the objective and the recent
 * challenge history are not captured yet.
 *
 * @param packHash hash of the pinned content the plan is made against
 * @param profileHash {@link CombatProfile#hash()} of the combat profile the encounter would freeze
 * @param sourceRevision campaign revision the actors' facts were read at, 0 or more
 * @param location canonical location where the encounter would take place
 * @param sceneTags tags of the scene, sorted
 * @param difficulty selected difficulty policy
 * @param actors the player first, then companions ordered by actor ID
 */
public record CapabilitySnapshot(String packHash, String profileHash, long sourceRevision, String location,
        Set<String> sceneTags, EncounterDifficulty difficulty, List<Actor> actors) {
    /** Lowest level or power tier the threat heuristic accepts. */
    public static final int LEVEL_MIN = 1;
    /**
     * Highest level or power tier the threat heuristic accepts (an engine bound that keeps every capacity and cost small,
     * not a calibrated progression cap).
     */
    public static final int LEVEL_MAX = 20;
    /** Most companions one snapshot can hold: a fight holds at most this many non-player participants. */
    public static final int COMPANIONS_MAX = CombatProfile.PARTICIPANTS_MAX;

    /** How an actor belongs to the party. */
    public enum Membership {
        /** The protagonist; every snapshot has exactly one. */
        PLAYER,
        /** A companion actor travelling with the player. */
        COMPANION;

        /** @return the lowercase wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether an actor can take part; only {@link #ACTIVE} actors with Health above zero contribute. */
    public enum Status {
        /** Present and able to fight. */
        ACTIVE,
        /** Present but incapacitated. */
        DEFEATED,
        /** Not at the scene. */
        ABSENT,
        /** Present but kept out of this encounter by a rule or the story. */
        EXCLUDED;

        /** @return the lowercase wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One party member's mechanical capabilities, as read from committed facts.
     *
     * @param actorId campaign actor
     * @param membership player or companion
     * @param status whether it can take part
     * @param level level or power tier, {@link CapabilitySnapshot#LEVEL_MIN}..{@link CapabilitySnapshot#LEVEL_MAX}; never
     *        an undefined default (docs/12 section 10)
     * @param health Health reading; its maximum is at least 1
     * @param resources the spendable combat resources the actor relies on, by pack resource key, each with a maximum of at
     *        least 1; empty when it relies on none
     * @param equipment canonical item definitions the actor wears, sorted; stored items are not equipment
     * @param conditions canonical conditions active on the actor, sorted
     * @param abilities canonical abilities the actor has learned, sorted
     * @param skills trained skill ranks by canonical skill ID, each 0 or more
     * @param ammunition accessible units by canonical ammunition item ID, each 0 or more
     */
    public record Actor(UUID actorId, Membership membership, Status status, int level, FrozenEncounter.Pool health,
            Map<String, FrozenEncounter.Pool> resources, List<String> equipment, Set<String> conditions,
            Set<String> abilities, Map<String, Long> skills, Map<String, Long> ammunition) {
        /** Check the bounds and freeze every collection in a stable order. */
        public Actor {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(membership, "membership");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(health, "health");
            if (level < LEVEL_MIN || level > LEVEL_MAX) {
                throw new IllegalArgumentException("level must be within " + LEVEL_MIN + ".." + LEVEL_MAX);
            }
            if (health.maximum() < 1) {
                throw new IllegalArgumentException("an actor has a Health maximum of at least 1");
            }
            resources.forEach((key, pool) -> {
                Objects.requireNonNull(key, "resource key");
                // A resource with no maximum cannot be relied on; counting it would read a ratio of 0/0.
                if (pool == null || pool.maximum() < 1) {
                    throw new IllegalArgumentException("relied resource " + key + " has a maximum of at least 1");
                }
            });
            skills.values().forEach(rank -> nonNegative(rank, "skill rank"));
            ammunition.values().forEach(units -> nonNegative(units, "ammunition units"));
            resources = Collections.unmodifiableMap(new TreeMap<>(resources));
            equipment = equipment.stream().map(item -> Objects.requireNonNull(item, "equipment")).sorted().toList();
            conditions = Collections.unmodifiableSet(new TreeSet<>(conditions));
            abilities = Collections.unmodifiableSet(new TreeSet<>(abilities));
            skills = Collections.unmodifiableMap(new TreeMap<>(skills));
            ammunition = Collections.unmodifiableMap(new TreeMap<>(ammunition));
        }

        /**
         * The protagonist's capabilities from the same committed facts encounter entry freezes, so planning and entry read
         * one source. Only worn items count as equipment. The profile's Focus pool is treated as a relied resource whenever
         * the player has one with a positive maximum; whether any learned ability actually spends it is not examined, so a
         * drained Focus lowers capacity and the plan errs towards an easier fight.
         *
         * @param facts the player's committed facts
         * @param profile the combat profile the encounter would freeze; names the Focus resource
         * @param level the player's level or power tier
         * @param ammunition accessible units by canonical ammunition item ID
         * @return an active player actor
         * @throws IllegalArgumentException when the level is out of bounds or the Health maximum is 0
         */
        public static Actor player(FrozenEncounter.PlayerFacts facts, CombatProfile profile, int level,
                Map<String, Long> ammunition) {
            var resources = new TreeMap<String, FrozenEncounter.Pool>();
            if (profile.focus() != null && facts.focus() != null && facts.focus().maximum() > 0) {
                resources.put(profile.focus(), facts.focus());
            }
            return new Actor(facts.actorId(), Membership.PLAYER, Status.ACTIVE, level, facts.health(), resources,
                    facts.worn(), facts.conditions(), facts.learned(), facts.skills(), ammunition);
        }

        /** @return whether the actor contributes to the party: active and above zero Health */
        public boolean participates() {
            return status == Status.ACTIVE && health.current() > 0;
        }

        /** @return canonical form, as hashed by the snapshot */
        public Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("actorId", actorId.toString());
            form.put("membership", membership.wire());
            form.put("status", status.wire());
            form.put("level", level);
            form.put("health", pool(health));
            var pools = new TreeMap<String, Object>();
            resources.forEach((key, pool) -> pools.put(key, pool(pool)));
            form.put("resources", pools);
            form.put("equipment", equipment);
            form.put("conditions", List.copyOf(conditions));
            form.put("abilities", List.copyOf(abilities));
            form.put("skills", skills);
            form.put("ammunition", ammunition);
            return form;
        }

        private static Map<String, Object> pool(FrozenEncounter.Pool pool) {
            var form = new LinkedHashMap<String, Object>();
            form.put("current", pool.current());
            form.put("maximum", pool.maximum());
            return form;
        }

        private static void nonNegative(Long value, String name) {
            if (value == null || value < 0) {
                throw new IllegalArgumentException(name + " must be 0 or more");
            }
        }
    }

    /** Require the identities, exactly one player and distinct actors; order the party canonically. */
    public CapabilitySnapshot {
        Objects.requireNonNull(packHash, "packHash");
        Objects.requireNonNull(profileHash, "profileHash");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(difficulty, "difficulty");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("source revision must be 0 or more");
        }
        sceneTags = Collections.unmodifiableSet(new TreeSet<>(sceneTags));
        var seen = new HashSet<UUID>();
        Actor player = null;
        var companions = new ArrayList<Actor>();
        for (Actor actor : actors) {
            Objects.requireNonNull(actor, "actor");
            if (!seen.add(actor.actorId())) {
                throw new IllegalArgumentException("actor " + actor.actorId() + " appears twice");
            }
            if (actor.membership() == Membership.PLAYER) {
                if (player != null) {
                    throw new IllegalArgumentException("a snapshot has exactly one player");
                }
                player = actor;
            } else {
                companions.add(actor);
            }
        }
        if (player == null) {
            throw new IllegalArgumentException("a snapshot has exactly one player");
        }
        if (companions.size() > COMPANIONS_MAX) {
            throw new IllegalArgumentException("a snapshot holds at most " + COMPANIONS_MAX + " companions");
        }
        companions.sort(Comparator.comparing(actor -> actor.actorId().toString()));
        var ordered = new ArrayList<Actor>();
        ordered.add(player);
        ordered.addAll(companions);
        actors = List.copyOf(ordered);
    }

    /** @return the protagonist's entry */
    public Actor player() {
        return actors.getFirst();
    }

    /** @return how many actors contribute: active and above zero Health; 0 means no encounter can be planned */
    public int partySize() {
        return (int) actors.stream().filter(Actor::participates).count();
    }

    /**
     * Canonical JSON of every captured fact. Equal snapshots give byte-identical text whatever order the caller supplied
     * the actors, tags, items or maps in.
     *
     * @return canonical JSON object text
     */
    public String canonical() {
        var form = new LinkedHashMap<String, Object>();
        form.put("heuristic", ThreatBudget.HEURISTIC);
        form.put("packHash", packHash);
        form.put("profileHash", profileHash);
        form.put("sourceRevision", sourceRevision);
        form.put("location", location);
        form.put("sceneTags", List.copyOf(sceneTags));
        form.put("difficulty", difficulty.wire());
        form.put("actors", actors.stream().map(actor -> (Object) actor.canonicalForm()).toList());
        return CanonicalJson.write(form);
    }

    /** @return SHA-256 of {@link #canonical()}, the snapshot's identity */
    public String hash() {
        return CanonicalJson.sha256(canonical());
    }
}
