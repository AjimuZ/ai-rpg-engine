package org.rpgengine.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.rules.CombatProfile;

/**
 * Frozen definition of one timed combat effect (P11.4, docs/11 section 11), the {@code combat} block of a pack
 * condition. An encounter stores the definitions it can reach at entry and never reads the pack again. The grammar is
 * closed on purpose: an effect modifies defenses and attack scores, holds absorption, prevents named things, and ticks
 * a fixed amount of damage or healing at a declared phase. It cannot apply another effect, so effect processing cannot
 * form a cycle, and it draws no die, so ticks never touch the operation's draw budget.
 *
 * <p>Invariants, checked here and by the pack compiler: modifiers within {@link #MODIFIER_MAX}, absorption and tick
 * amounts within {@link #AMOUNT_MAX}, at most {@link #MAX_TICKS} ticks, and a cap above one only for the stacking modes
 * that hold several instances or stacks.
 *
 * @param id canonical condition ID
 * @param stackingKey instances of one key stack with each other; defaults to the condition's own ID
 * @param stacking how a new instance meets the holder's instances of its key
 * @param cap most independent instances or additive stacks of one key; 1 for every other mode
 * @param clock the clock the duration counts on
 * @param count units of {@code clock}: activations, round ends or seconds; 0 expires at once; unused by other clocks
 * @param untilSource for {@link Clock#NEXT_ACTIVATION}, whether the named actor is the source rather than the holder
 * @param priority higher resolves first among simultaneous effects
 * @param guard situational Guard modifier
 * @param resistance situational Resistance modifier
 * @param attack situational attack modifier
 * @param absorption temporary absorption one instance starts with, consumed after armor and before Health
 * @param prevents what the holder cannot do while the effect lasts
 * @param ticks fixed-amount ticks, in authored order
 * @param endsWithSource whether a defeated source ends the effect; otherwise it persists
 */
public record CombatEffect(String id, String stackingKey, Stacking stacking, int cap, Clock clock, int count,
        boolean untilSource, int priority, int guard, int resistance, int attack, int absorption, Set<Prevents> prevents,
        List<Tick> ticks, boolean endsWithSource) {
    /** Largest absolute Guard, Resistance or attack modifier of one instance. */
    public static final int MODIFIER_MAX = CombatProfile.EFFECT_MODIFIER_MAX;
    /** Largest absorption or tick amount of one instance. */
    public static final int AMOUNT_MAX = CombatProfile.EFFECT_AMOUNT_MAX;
    /** Most ticks of one definition, which bounds the automatic work of a round. */
    public static final int MAX_TICKS = CombatProfile.EFFECT_TICKS_MAX;
    /** Largest cap of independent instances or additive stacks. */
    public static final int CAP_MAX = CombatProfile.EFFECT_CAP_MAX;
    /** Largest count of a declared duration: more seconds than any fight's round cap can pass. */
    public static final int COUNT_MAX = CombatProfile.EFFECT_COUNT_MAX;
    /** Largest absolute declared priority. */
    public static final int PRIORITY_MAX = CombatProfile.PRIORITY_MAX;

    /** How a new instance meets the holder's instances of the same stacking key (docs/11 section 11). */
    public enum Stacking {
        /** The new instance replaces every instance of the key. */
        REPLACE,
        /** The duration of the existing instance restarts; a different definition replaces it. */
        REFRESH,
        /** The default: the strongest magnitude stays and the duration restarts. */
        STRONGEST,
        /** Instances coexist up to the cap, each with its own duration; one beyond the cap changes nothing. */
        INDEPENDENT,
        /** One instance gains a stack up to the cap, multiplying its magnitude, and its duration restarts. */
        ADDITIVE;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The clock a duration counts on (docs/11 section 11). */
    public enum Clock {
        /** Over at once: the effect is announced and leaves no instance. */
        INSTANT("instant"),
        /** Until the named actor's next activation starts. */
        NEXT_ACTIVATION("nextActivation"),
        /** A number of the holder's activations that start after the effect was applied. */
        ACTIVATIONS("activations"),
        /** A number of round-end ticks. */
        ROUND_ENDS("roundEnds"),
        /** A number of in-world seconds, the only clock that world time counts down. */
        WORLD_SECONDS("worldSeconds"),
        /** Until a removal, the holder's defeat or the end of the encounter. */
        PERSISTENT("persistent");

        private final String wire;

        /** Bind the wire name. */
        Clock(String wire) {
            this.wire = wire;
        }

        /** @return the pack wire name */
        public String wire() {
            return wire;
        }

        /**
         * @param wire a pack wire name
         * @return the clock
         * @throws IllegalArgumentException for an unknown name
         */
        public static Clock parse(String wire) {
            for (Clock clock : values()) {
                if (clock.wire.equals(wire)) {
                    return clock;
                }
            }
            throw new IllegalArgumentException("unknown effect clock " + wire);
        }
    }

    /** What an effect can prevent; a condition must say so, because a status cannot silently remove turns. */
    public enum Prevents {
        /** Zone movement of any kind. */
        MOVEMENT,
        /** Everything that costs action points: the holder's activations grant no usable points. */
        ACTIONS,
        /** Every reaction. */
        REACTIONS,
        /** Spending Focus. */
        RESOURCES;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The eight phases of docs/11 section 11, in the order they sort. */
    public enum Phase {
        ROUND_OPEN("roundOpen"),
        ACTIVATION_START("activationStart"),
        ACTION_DECLARED("actionDeclared"),
        PRE_ROLL("preRoll"),
        POST_HIT("postHit"),
        POST_DAMAGE("postDamage"),
        ACTIVATION_END("activationEnd"),
        ROUND_END("roundEnd");

        private final String wire;

        /** Bind the wire name. */
        Phase(String wire) {
            this.wire = wire;
        }

        /** @return the pack wire name */
        public String wire() {
            return wire;
        }

        /**
         * @param wire a pack wire name
         * @return the phase
         * @throws IllegalArgumentException for an unknown name
         */
        public static Phase parse(String wire) {
            for (Phase phase : values()) {
                if (phase.wire.equals(wire)) {
                    return phase;
                }
            }
            throw new IllegalArgumentException("unknown effect phase " + wire);
        }
    }

    /**
     * One automatic change at a declared phase. The boundary phases fire for the holder's own activations and for every
     * round; {@code actionDeclared} fires when the holder declares an action that costs points; {@code preRoll},
     * {@code postHit} and {@code postDamage} fire when the holder is the target of an attack.
     *
     * @param phase when it fires
     * @param amount fixed amount per stack, 1..{@link #AMOUNT_MAX}
     * @param heal whether it restores Health; otherwise it deals damage that armor does not reduce and absorption does
     */
    public record Tick(Phase phase, int amount, boolean heal) {
        /** Require a phase and a bounded amount. */
        public Tick {
            Objects.requireNonNull(phase, "phase");
            if (amount < 1 || amount > AMOUNT_MAX) {
                throw new IllegalArgumentException("a tick amount lies within 1.." + AMOUNT_MAX);
            }
        }

        /** @return canonical frozen form */
        Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("phase", phase.wire());
            form.put("amount", amount);
            form.put("heal", heal);
            return form;
        }
    }

    /** Check every bound; a violation here is a broken compiler guarantee. */
    public CombatEffect {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stackingKey, "stackingKey");
        Objects.requireNonNull(stacking, "stacking");
        Objects.requireNonNull(clock, "clock");
        prevents = java.util.Collections.unmodifiableSet(new TreeSet<>(prevents));
        ticks = List.copyOf(ticks);
        boolean several = stacking == Stacking.INDEPENDENT || stacking == Stacking.ADDITIVE;
        if (cap < 1 || cap > CAP_MAX || !several && cap != 1) {
            throw new IllegalArgumentException("effect " + id + " has a stacking cap its mode does not allow");
        }
        if (Math.abs(guard) > MODIFIER_MAX || Math.abs(resistance) > MODIFIER_MAX || Math.abs(attack) > MODIFIER_MAX
                || absorption < 0 || absorption > AMOUNT_MAX || ticks.size() > MAX_TICKS || count < 0 || count > COUNT_MAX
                || Math.abs(priority) > PRIORITY_MAX) {
            throw new IllegalArgumentException("effect " + id + " has a value outside its bound");
        }
        if (untilSource && clock != Clock.NEXT_ACTIVATION) {
            throw new IllegalArgumentException("only an effect that lasts until a next activation names a source's");
        }
    }

    /**
     * Whether the effect expires the moment it is applied: an instant clock, or a counting clock at zero (docs/11
     * section 11: duration zero expires immediately).
     *
     * @return true when no instance is ever stored
     */
    public boolean expiresAtOnce() {
        return clock == Clock.INSTANT || count == 0
                && (clock == Clock.ACTIVATIONS || clock == Clock.ROUND_ENDS || clock == Clock.WORLD_SECONDS);
    }

    /**
     * The magnitude {@link Stacking#STRONGEST} compares: every modifier's size plus absorption and tick amounts. It
     * orders definitions of one key, whatever they modify.
     *
     * @return a non-negative size, for one stack
     */
    public int magnitude() {
        return Math.abs(guard) + Math.abs(resistance) + Math.abs(attack) + absorption
                + ticks.stream().mapToInt(Tick::amount).sum();
    }

    /** @return canonical frozen form, part of the encounter's frozen definition */
    public Map<String, Object> canonicalForm() {
        var form = new LinkedHashMap<String, Object>();
        form.put("id", id);
        form.put("stackingKey", stackingKey);
        form.put("stacking", stacking.wire());
        form.put("cap", cap);
        form.put("clock", clock.wire());
        form.put("count", count);
        form.put("untilSource", untilSource);
        form.put("priority", priority);
        form.put("guard", guard);
        form.put("resistance", resistance);
        form.put("attack", attack);
        form.put("absorption", absorption);
        form.put("prevents", prevents.stream().map(Prevents::wire).toList());
        form.put("ticks", ticks.stream().map(tick -> (Object) tick.canonicalForm()).toList());
        form.put("endsWithSource", endsWithSource);
        return form;
    }

    /**
     * Read the canonical form {@link #canonicalForm()} wrote.
     *
     * @param form parsed canonical object
     * @return the definition
     * @throws IllegalArgumentException or {@link ClassCastException} for a malformed frozen form
     */
    public static CombatEffect fromCanonical(Map<?, ?> form) {
        var prevented = new TreeSet<Prevents>();
        ((List<?>) form.get("prevents")).forEach(name ->
                prevented.add(Prevents.valueOf(((String) name).toUpperCase(Locale.ROOT))));
        var ticks = ((List<?>) form.get("ticks")).stream().map(tick -> (Map<?, ?>) tick)
                .map(tick -> new Tick(Phase.parse((String) tick.get("phase")), ((Number) tick.get("amount")).intValue(),
                        Boolean.TRUE.equals(tick.get("heal")))).toList();
        return new CombatEffect((String) form.get("id"), (String) form.get("stackingKey"),
                Stacking.valueOf(((String) form.get("stacking")).toUpperCase(Locale.ROOT)),
                ((Number) form.get("cap")).intValue(), Clock.parse((String) form.get("clock")),
                ((Number) form.get("count")).intValue(), Boolean.TRUE.equals(form.get("untilSource")),
                ((Number) form.get("priority")).intValue(), ((Number) form.get("guard")).intValue(),
                ((Number) form.get("resistance")).intValue(), ((Number) form.get("attack")).intValue(),
                ((Number) form.get("absorption")).intValue(), prevented, ticks,
                Boolean.TRUE.equals(form.get("endsWithSource")));
    }
}
