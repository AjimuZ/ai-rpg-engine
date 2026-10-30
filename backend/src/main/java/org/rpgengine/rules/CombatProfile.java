package org.rpgengine.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One adventure's combat profile around the AI RPG Skirmish default ruleset (P11.1, docs/11 section 17,
 * ADR 0028). A profile has two parts:
 *
 * <ul>
 *   <li><b>Kernel</b> — the confirmed structure: one initiative-ordered round scheduler, the zone-graph space,
 *       initiative {@code 2d10 + finesse + insight} rolled once with stable tie-breaks, 2d10 checks and a
 *       reaction depth of one. It is fixed by this engine build and identified by {@link #KERNEL}; a pack
 *       cannot change it, because changing it changes the scheduler or the probability model.</li>
 *   <li><b>Parameters</b> — the provisional numbers of the docs/11 section 17 table (action points, round
 *       length, costs, caps, bonuses). Each has the documented default and an inclusive bound in
 *       {@link #PARAMETERS}; a pack's {@code combat} rule may override any of them inside its bound.</li>
 * </ul>
 *
 * <p>The profile also maps the four ruleset roles (force, finesse, insight, resolve) to attributes of the
 * declaring pack and names the pack resources that serve as Health and, optionally, Focus, because packs
 * define their own vocabularies. The values are resolved once per content revision and frozen into every
 * encounter at entry ({@link #canonical()}, {@link #hash()}); a later content revision or a retuned default
 * never changes an encounter that already started.
 *
 * <p>Cross-parameter invariants (checked on construction, so the pack compiler and every runtime consumer
 * share them): every action cost fits one activation's action points, a difficult edge is still traversable
 * within one activation, and the Guard-action and partial-cover bonuses fit the situational Guard cap.
 *
 * @param pack declaring pack ID; owns the unqualified attribute and resource keys below
 * @param roles pack attribute key for each of {@link #ROLES}
 * @param health pack resource key used as Health
 * @param focus pack resource key used as Focus, or null when the profile has no Focus mechanic
 * @param values every parameter of {@link #PARAMETERS}, defaults filled in
 */
public record CombatProfile(String pack, Map<String, String> roles, String health, String focus,
        Map<String, Integer> values) {
    /**
     * Identity of the fixed scheduler kernel these parameters configure. It is part of every frozen profile,
     * so an encounter records which kernel it was frozen for; a different kernel is an engine extension.
     */
    public static final String KERNEL = "skirmish-initiative-rounds/1";
    /** The only scheduler a pack may name. */
    public static final String SCHEDULER = "initiative-rounds";
    /** The only spatial model a pack may name. */
    public static final String SPACE = "zones";
    /** Ruleset roles the default formulas read, in their documented order. */
    public static final List<String> ROLES = List.of("force", "finesse", "insight", "resolve");
    /** Dice of an initiative roll: {@code 2d10} plus the finesse and insight attributes (kernel, not a parameter). */
    public static final int INITIATIVE_DICE = 2;
    public static final int INITIATIVE_SIDES = 10;
    /** Most reactions any reaction may trigger (kernel: reactions never chain). */
    public static final int REACTION_DEPTH = 1;
    /**
     * Supported range of a role value (docs/11 section 4, kernel, not a parameter). Every frozen participant stays
     * inside it, so an initiative total always fits its stored bound {@code 2..32} (V23): two d10 plus finesse and
     * insight. A value outside it could enter a fight whose initiative can never be stored.
     */
    public static final int ROLE_MIN = 0;
    public static final int ROLE_MAX = 6;
    /** Dice of an attack roll: {@code 2d10} plus attribute, skill and modifier (kernel, not a parameter). */
    public static final int ATTACK_SIDES = 10;
    /**
     * Bounds of one attack definition (P11.3, kernel). The dice bound keeps the largest roster inside one operation's
     * durable draw budget: at most nine strikes of two attack dice, four damage dice and a strong-hit die.
     */
    public static final int ATTACK_DICE_MAX = 4;
    public static final int ATTACK_SIDES_MAX = 20;
    public static final int ATTACK_FLAT_MIN = -5;
    public static final int ATTACK_FLAT_MAX = 20;
    public static final int ATTACK_PENETRATION_MAX = 10;
    public static final int ATTACK_SKILL_MAX = 10;
    /** Most attacks a template participant may declare. */
    public static final int ATTACKS_MAX = 4;
    /** Most non-player participants of one encounter. */
    public static final int PARTICIPANTS_MAX = 8;
    /** Most effects one attack can apply (P11.4, kernel). */
    public static final int ON_HIT_MAX = 2;
    /**
     * Bounds of one combat effect (P11.4, kernel): its modifiers, absorption and tick amounts, its ticks, its stacking cap,
     * the count of its declared duration and its priority. With {@link #EFFECTS_ACTIVE_MAX} they bound a round's automatic
     * work and every total a fight can reach, before the situational caps apply.
     */
    public static final int EFFECT_MODIFIER_MAX = 4;
    public static final int EFFECT_AMOUNT_MAX = 99;
    public static final int EFFECT_TICKS_MAX = 2;
    public static final int EFFECT_CAP_MAX = 8;
    public static final int EFFECT_COUNT_MAX = 10_000;
    public static final int PRIORITY_MAX = 10;
    /** Most active effects of one participant. */
    public static final int EFFECTS_ACTIVE_MAX = 8;
    /** Most reactions a participant may declare beside the default Brace. */
    public static final int REACTIONS_MAX = 4;
    /** Largest Focus cost of an ability or a reaction. */
    public static final int FOCUS_COST_MAX = 99;
    /**
     * Bounds of one combat ability (P11.4, kernel): its operations, its dice (each one durable draw, which keeps its
     * operation inside the draw budget) and its cooldown; a longer one is a once-per-encounter limit.
     */
    public static final int ABILITY_OPERATIONS_MAX = 4;
    public static final int ABILITY_DICE_MAX = 16;
    public static final int ABILITY_COOLDOWN_MAX = 30;

    /**
     * Inclusive bound and documented default of one parameter.
     *
     * @param fallback AI RPG Skirmish default (docs/11 section 17)
     * @param min smallest supported value
     * @param max largest supported value
     */
    public record Bound(int fallback, int min, int max) {
        /** Reject an inverted bound or a default outside it. */
        public Bound {
            if (min > max || fallback < min || fallback > max) {
                throw new IllegalArgumentException("inconsistent parameter bound");
            }
        }
    }

    /** Every tunable parameter, in documentation order. Keys ending in {@code Cost} are action costs in AP. */
    public static final Map<String, Bound> PARAMETERS = parameters();

    /** Validate the mapping and the parameter values; freeze the maps in key order. */
    public CombatProfile {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(health, "health");
        if (!roles.keySet().equals(new java.util.HashSet<>(ROLES))) {
            throw new IllegalArgumentException("a combat profile maps exactly the roles " + ROLES);
        }
        roles.values().forEach(attribute -> Objects.requireNonNull(attribute, "role attribute"));
        if (health.equals(focus)) {
            throw new IllegalArgumentException("Health and Focus must be different resources");
        }
        if (!values.keySet().equals(PARAMETERS.keySet())) {
            throw new IllegalArgumentException("a combat profile sets every parameter exactly once");
        }
        values.forEach((name, value) -> {
            Bound bound = PARAMETERS.get(name);
            if (value == null || value < bound.min() || value > bound.max()) {
                throw new IllegalArgumentException(name + " must be within " + bound.min() + ".." + bound.max());
            }
        });
        int points = values.get("actionPoints");
        for (var entry : values.entrySet()) {
            if (entry.getKey().endsWith("Cost") && entry.getValue() > points) {
                throw new IllegalArgumentException(entry.getKey() + " " + entry.getValue()
                        + " exceeds the " + points + " action points of one activation");
            }
        }
        if (values.get("moveCost") * values.get("difficultTerrainMultiplier") > points) {
            throw new IllegalArgumentException("a difficult edge would cost more than one activation's action points");
        }
        int cap = values.get("situationalGuardCap");
        if (values.get("guardBonus") > cap || values.get("partialCoverBonus") > cap) {
            throw new IllegalArgumentException("Guard and cover bonuses must fit the situational Guard cap of " + cap);
        }
        roles = Collections.unmodifiableMap(new TreeMap<>(roles));
        values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    /**
     * Resolve a profile from the documented defaults and a pack's overrides.
     *
     * @param pack declaring pack ID
     * @param roles attribute key per role
     * @param health Health resource key
     * @param focus Focus resource key or null
     * @param overrides parameter values the pack sets; omitted parameters keep their default
     * @return the resolved profile
     * @throws IllegalArgumentException for an unknown parameter, a value out of bound or a broken invariant
     */
    public static CombatProfile resolve(String pack, Map<String, String> roles, String health, String focus,
            Map<String, Integer> overrides) {
        for (String name : overrides.keySet()) {
            if (!PARAMETERS.containsKey(name)) {
                throw new IllegalArgumentException("unknown combat parameter " + name);
            }
        }
        var values = new LinkedHashMap<String, Integer>();
        PARAMETERS.forEach((name, bound) -> values.put(name, overrides.getOrDefault(name, bound.fallback())));
        return new CombatProfile(pack, roles, health, focus, values);
    }

    /**
     * @param value a participant's role value
     * @return whether it lies in the supported range {@link #ROLE_MIN}..{@link #ROLE_MAX}
     */
    public static boolean roleInRange(long value) {
        return value >= ROLE_MIN && value <= ROLE_MAX;
    }

    /** @return the documented default values of every parameter */
    public static Map<String, Integer> defaults() {
        var values = new TreeMap<String, Integer>();
        PARAMETERS.forEach((name, bound) -> values.put(name, bound.fallback()));
        return Collections.unmodifiableMap(values);
    }

    /**
     * Value of one parameter.
     *
     * @param name a key of {@link #PARAMETERS}
     * @return its resolved value
     * @throws IllegalArgumentException for an unknown parameter
     */
    public int value(String name) {
        Integer value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("unknown combat parameter " + name);
        }
        return value;
    }

    /** @return action points granted at the start of a valid activation */
    public int actionPoints() {
        return value("actionPoints");
    }

    /** @return reactions refreshed at each round-open */
    public int reactionsPerRound() {
        return value("reactionsPerRound");
    }

    /** @return in-world seconds of one completed round */
    public int roundSeconds() {
        return value("roundSeconds");
    }

    /** @return the round cap after which the configured stalemate policy applies */
    public int roundCap() {
        return value("roundCap");
    }

    /**
     * Frozen form: kernel, mapping and every parameter, as canonical JSON text. Equal profiles always give
     * equal text; this is what an encounter stores at entry.
     *
     * @return canonical JSON object text
     */
    public String canonical() {
        var form = new LinkedHashMap<String, Object>();
        form.put("kernel", KERNEL);
        form.put("scheduler", SCHEDULER);
        form.put("space", SPACE);
        form.put("pack", pack);
        form.put("roles", roles);
        form.put("health", health);
        form.put("focus", focus);
        form.put("values", values);
        return CanonicalJson.write(form);
    }

    /**
     * Read a frozen profile back from the canonical form {@link #canonical()} wrote (P11.2): the scheduler of an
     * active encounter uses exactly the stored values, never today's defaults or the current pack revision.
     *
     * @param form parsed canonical object
     * @return the profile, revalidated by the constructor
     * @throws IllegalArgumentException when the form names another kernel or breaks a bound or invariant
     * @throws ClassCastException for a structurally malformed form
     */
    public static CombatProfile fromCanonical(Map<?, ?> form) {
        if (!KERNEL.equals(form.get("kernel"))) {
            throw new IllegalArgumentException("the frozen profile was made for another scheduler kernel");
        }
        var roles = new TreeMap<String, String>();
        ((Map<?, ?>) form.get("roles")).forEach((role, attribute) -> roles.put((String) role, (String) attribute));
        var values = new TreeMap<String, Integer>();
        ((Map<?, ?>) form.get("values")).forEach((name, value) -> values.put((String) name, ((Number) value).intValue()));
        return new CombatProfile((String) form.get("pack"), roles, (String) form.get("health"),
                (String) form.get("focus"), values);
    }

    /** @return SHA-256 of {@link #canonical()}, the frozen profile's identity */
    public String hash() {
        return CanonicalJson.sha256(canonical());
    }

    /** Build the parameter table; values are the provisional defaults of docs/11 section 17. */
    private static Map<String, Bound> parameters() {
        var table = new LinkedHashMap<String, Bound>();
        table.put("actionPoints", new Bound(3, 1, 6));
        table.put("reactionsPerRound", new Bound(1, 0, 3));
        table.put("roundSeconds", new Bound(8, 1, 60));
        table.put("roundCap", new Bound(30, 1, 100));
        table.put("guardBase", new Bound(10, 0, 20));
        table.put("resistanceBase", new Bound(10, 0, 20));
        table.put("equipmentGuardCap", new Bound(3, 0, 6));
        table.put("situationalGuardCap", new Bound(4, 0, 8));
        table.put("armorMax", new Bound(4, 0, 10));
        table.put("guardBonus", new Bound(2, 1, 4));
        table.put("partialCoverBonus", new Bound(2, 0, 4));
        table.put("situationalModifierCap", new Bound(3, 0, 6));
        table.put("strongHitMargin", new Bound(6, 1, 20));
        table.put("strongHitDieSides", new Bound(4, 2, 12));
        table.put("maxRangeEdges", new Bound(2, 0, 4));
        table.put("speechPerActivation", new Bound(1, 0, 3));
        table.put("difficultTerrainMultiplier", new Bound(2, 1, 3));
        table.put("moveCost", new Bound(1, 1, 3));
        table.put("strikeCost", new Bound(2, 1, 6));
        table.put("guardCost", new Bound(1, 1, 6));
        table.put("useItemCost", new Bound(2, 1, 6));
        table.put("readyCost", new Bound(1, 1, 6));
        table.put("interactCost", new Bound(1, 1, 6));
        table.put("assessCost", new Bound(1, 1, 6));
        table.put("withdrawCost", new Bound(2, 1, 6));
        return Collections.unmodifiableMap(table);
    }
}
