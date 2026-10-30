package org.rpgengine.combat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One frozen predeclared reaction (P11.4, docs/11 section 10): its trigger, priority, cost, target constraints and
 * single effect. Reactions fire by policy during another participant's action, inside its atomic operation; there is no
 * manual window and no way to decide after seeing a roll. A reaction's effect never triggers another reaction, which the
 * scheduler enforces (kernel depth one). {@link #BRACE} is the default reaction every participant has.
 *
 * @param id stable reaction ID: {@code brace}, the template's reaction ID or the canonical ability ID
 * @param trigger when it may fire
 * @param priority higher fires first among simultaneous reactions, then initiative order, then participant ID
 * @param focus Focus it costs beside the one reaction it always spends
 * @param protectsAllies whether it may answer an attack on a standing ally in the reactor's zone as well as on itself
 * @param attacks which attacks it answers
 * @param kind its one effect
 * @param amount the Guard bonus or the absorption; 0 means the profile's Guard bonus for {@link Kind#GUARD}
 * @param attackId frozen attack of a counter, or null for the first that reaches
 * @param effectId frozen effect a {@link Kind#APPLY} reaction applies, or null
 * @param onAttacker whether a {@link Kind#APPLY} reaction applies it to the attacker rather than to the reactor
 */
public record CombatReaction(String id, Trigger trigger, int priority, int focus, boolean protectsAllies, Attacks attacks,
        Kind kind, int amount, String attackId, String effectId, boolean onAttacker) {
    /** Identity of the default reaction, which every participant can set a policy for. */
    public static final String BRACE_ID = "brace";
    /**
     * The default reaction (docs/11 section 10): against a visible attack on oneself, spend one reaction to add the
     * profile's Guard bonus for that attack. Guard action and Brace share one stacking key.
     */
    public static final CombatReaction BRACE = new CombatReaction(BRACE_ID, Trigger.ATTACKED, 0, 0, false, Attacks.ANY,
            Kind.GUARD, 0, null, null, false);
    /** Most reactions a participant may declare beside Brace. */
    public static final int MAX_DECLARED = org.rpgengine.rules.CombatProfile.REACTIONS_MAX;
    /** Largest Focus cost of a reaction. */
    public static final int FOCUS_MAX = org.rpgengine.rules.CombatProfile.FOCUS_COST_MAX;

    /** When a reaction may fire. */
    public enum Trigger {
        /** An attack is declared against the protected participant, before its dice. */
        ATTACKED("attacked"),
        /** The attack hit. */
        HIT("hit"),
        /** The attack took Health. */
        DAMAGED("damaged"),
        /** An opponent standing in the reactor's zone is leaving it, the one way a move provokes a blow. */
        LEAVES_ZONE("leavesZone");

        private final String wire;

        /** Bind the wire name. */
        Trigger(String wire) {
            this.wire = wire;
        }

        /** @return the pack wire name */
        public String wire() {
            return wire;
        }

        /**
         * @param wire a pack wire name
         * @return the trigger
         * @throws IllegalArgumentException for an unknown name
         */
        public static Trigger parse(String wire) {
            for (Trigger trigger : values()) {
                if (trigger.wire.equals(wire)) {
                    return trigger;
                }
            }
            throw new IllegalArgumentException("unknown reaction trigger " + wire);
        }
    }

    /** Which attacks a reaction answers. */
    public enum Attacks {
        ANY, MELEE, RANGED;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * @param attack an attack
         * @return whether this constraint admits it
         */
        public boolean admits(CombatAttack attack) {
            return this == ANY || (this == RANGED) == attack.ranged();
        }
    }

    /** The one effect of a reaction. */
    public enum Kind {
        /** A Guard bonus against this attack, under the stance's stacking key: the higher applies, never the sum. */
        GUARD,
        /** Absorption against this attack only. */
        ABSORB,
        /** One attack of the reactor at no action-point cost; it provokes no reaction. */
        COUNTER,
        /** Apply one frozen effect to the reactor or to the attacker. */
        APPLY;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Check the combination; a violation here is a broken compiler guarantee. */
    public CombatReaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(attacks, "attacks");
        Objects.requireNonNull(kind, "kind");
        if (focus < 0 || focus > FOCUS_MAX || Math.abs(priority) > CombatEffect.PRIORITY_MAX || amount < 0
                || amount > CombatEffect.AMOUNT_MAX) {
            throw new IllegalArgumentException("reaction " + id + " has a value outside its bound");
        }
        if (kind == Kind.APPLY && effectId == null || kind != Kind.APPLY && effectId != null
                || kind != Kind.COUNTER && attackId != null) {
            throw new IllegalArgumentException("reaction " + id + " does not fit its effect");
        }
        // A blow already landed cannot be guarded against, a counter answers what happened rather than what is declared,
        // and a move provokes only what can answer a move.
        if (kind == Kind.GUARD && trigger != Trigger.ATTACKED || kind == Kind.COUNTER && trigger == Trigger.ATTACKED
                || kind == Kind.ABSORB && trigger != Trigger.ATTACKED && trigger != Trigger.HIT
                || trigger == Trigger.LEAVES_ZONE && (protectsAllies || attacks != Attacks.ANY)) {
            throw new IllegalArgumentException("reaction " + id + " cannot have this effect at this trigger");
        }
    }

    /** @return canonical frozen form, part of the participant's frozen profile */
    public Map<String, Object> canonicalForm() {
        var form = new LinkedHashMap<String, Object>();
        form.put("id", id);
        form.put("trigger", trigger.wire());
        form.put("priority", priority);
        form.put("focus", focus);
        form.put("protectsAllies", protectsAllies);
        form.put("attacks", attacks.wire());
        form.put("kind", kind.wire());
        form.put("amount", amount);
        form.put("attackId", attackId);
        form.put("effectId", effectId);
        form.put("onAttacker", onAttacker);
        return form;
    }

    /**
     * Read the canonical form {@link #canonicalForm()} wrote.
     *
     * @param form parsed canonical object
     * @return the reaction
     * @throws IllegalArgumentException or {@link ClassCastException} for a malformed frozen form
     */
    public static CombatReaction fromCanonical(Map<?, ?> form) {
        return new CombatReaction((String) form.get("id"), Trigger.parse((String) form.get("trigger")),
                ((Number) form.get("priority")).intValue(), ((Number) form.get("focus")).intValue(),
                Boolean.TRUE.equals(form.get("protectsAllies")),
                Attacks.valueOf(((String) form.get("attacks")).toUpperCase(Locale.ROOT)),
                Kind.valueOf(((String) form.get("kind")).toUpperCase(Locale.ROOT)), ((Number) form.get("amount")).intValue(),
                (String) form.get("attackId"), (String) form.get("effectId"), Boolean.TRUE.equals(form.get("onAttacker")));
    }

    /**
     * A participant's predeclared answer to whether a reaction fires. It is chosen before combat or during the player's
     * own activation, never after seeing a roll, and its predicate reads only the reactor's own public facts.
     *
     * @param mode when it fires
     * @param value the percent of {@link Mode#HEALTH_AT_MOST} or the points of {@link Mode#FOCUS_AT_LEAST}; otherwise 0
     */
    public record Policy(Mode mode, int value) {
        /** The default: answer the first eligible trigger of each round. */
        public static final Policy FIRST = new Policy(Mode.FIRST, 0);
        /** Never fire. */
        public static final Policy OFF = new Policy(Mode.OFF, 0);

        /** When a reaction fires. */
        public enum Mode {
            /** Never. */
            OFF("off"),
            /** At the first eligible trigger while a reaction is left. */
            FIRST("first"),
            /** Only when the reactor's Health is at most this percent of its maximum. */
            HEALTH_AT_MOST("healthAtMost"),
            /** Only when the reactor holds at least this much Focus. */
            FOCUS_AT_LEAST("focusAtLeast");

            private final String wire;

            /** Bind the wire name. */
            Mode(String wire) {
                this.wire = wire;
            }

            /** @return the wire name */
            public String wire() {
                return wire;
            }

            /**
             * @param wire a wire name
             * @return the mode, or null when no mode has this name
             */
            public static Mode find(String wire) {
                for (Mode mode : values()) {
                    if (mode.wire.equals(wire)) {
                        return mode;
                    }
                }
                return null;
            }
        }

        /** Check the value against the mode. */
        public Policy {
            Objects.requireNonNull(mode, "mode");
            boolean valid = switch (mode) {
                case OFF, FIRST -> value == 0;
                case HEALTH_AT_MOST -> value >= 1 && value <= 100;
                case FOCUS_AT_LEAST -> value >= 1 && value <= FOCUS_MAX;
            };
            if (!valid) {
                throw new IllegalArgumentException("reaction policy value does not fit its mode");
            }
        }

        /**
         * @param health the reactor's Health
         * @param healthMax its maximum
         * @param focus its Focus
         * @return whether the predicate holds now
         */
        public boolean fires(int health, int healthMax, int focus) {
            return switch (mode) {
                case OFF -> false;
                case FIRST -> true;
                case HEALTH_AT_MOST -> health * 100L <= (long) value * healthMax;
                case FOCUS_AT_LEAST -> focus >= value;
            };
        }

        /** @return canonical stored form */
        public Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("mode", mode.wire());
            form.put("value", value);
            return form;
        }

        /**
         * Read the stored form.
         *
         * @param form parsed canonical object
         * @return the policy
         * @throws IllegalArgumentException or {@link ClassCastException} for a malformed form
         */
        public static Policy fromCanonical(Map<?, ?> form) {
            Mode mode = Mode.find((String) form.get("mode"));
            if (mode == null) {
                throw new IllegalArgumentException("unknown reaction policy mode");
            }
            return new Policy(mode, ((Number) form.get("value")).intValue());
        }
    }
}
