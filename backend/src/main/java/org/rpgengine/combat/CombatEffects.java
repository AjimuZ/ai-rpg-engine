package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure stacking, modifier and clock rules of active combat effects (P11.4, docs/11 section 11). Nothing here draws,
 * stores or schedules: {@link CombatScheduler} decides when each function runs. A holder's instances are plain values,
 * so every rule can be tested exhaustively and a stored fight reads back exactly what was calculated.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Order</b> ({@link #ORDER}): higher priority first, then source event sequence, then stable instance ID. The
 *       sequence is the encounter's application counter, which orders exactly as the events announcing the effects do.</li>
 *   <li><b>Stacking</b> ({@link #apply}): the incoming definition's mode decides how it meets its key's instances. A
 *       holder keeps at most {@link #MAX_ACTIVE} instances, so one more changes nothing rather than failing.</li>
 *   <li><b>Magnitude</b>: an additive instance multiplies its modifiers and ticks by its stacks; independent instances
 *       add up; the callers then apply the profile's situational caps.</li>
 *   <li><b>Clocks</b> ({@link #afterActivation}, {@link #afterRound}): an activation counts only when the instance was
 *       there when it started; a round end counts the round clock; only seconds pass on the world-time clock, so world
 *       time never ticks a round or activation clock a second time.</li>
 * </ul>
 */
public final class CombatEffects {
    /** Most active instances of one holder; with {@link CombatEffect#MAX_TICKS} it bounds a round's automatic work. */
    public static final int MAX_ACTIVE = org.rpgengine.rules.CombatProfile.EFFECTS_ACTIVE_MAX;

    /** Static rules only. */
    private CombatEffects() {
    }

    /**
     * One active effect on one holder, stored in the holder's row.
     *
     * @param instanceId stable identity within the encounter, {@code e<sequence>}
     * @param effectId frozen definition
     * @param sourceId participant that applied it, or null when it came with the holder into the fight
     * @param sequence the encounter's application counter when it was applied
     * @param remaining units left on its clock, possibly below zero for seconds in the round that ends it; unused by a
     *        next-activation or persistent clock
     * @param stacks additive stacks, at least one
     * @param absorption absorption left in this instance
     * @param untilId the participant whose next activation ends it, or null on any other clock
     * @param fresh whether the holder's activation in progress started before it was applied, so that one does not count
     */
    public record Active(String instanceId, String effectId, String sourceId, int sequence, int remaining, int stacks,
            int absorption, String untilId, boolean fresh) {
        /** Require identities and bounds. */
        public Active {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(effectId, "effectId");
            if (stacks < 1 || stacks > CombatEffect.CAP_MAX || absorption < 0) {
                throw new IllegalArgumentException("active effect outside its bounds");
            }
        }

        /** @return canonical stored form; a null source or actor is written as null, for a fixed member set */
        public Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("instanceId", instanceId);
            form.put("effectId", effectId);
            form.put("sourceId", sourceId);
            form.put("sequence", sequence);
            form.put("remaining", remaining);
            form.put("stacks", stacks);
            form.put("absorption", absorption);
            form.put("untilId", untilId);
            form.put("fresh", fresh);
            return form;
        }

        /**
         * Read the stored form.
         *
         * @param form parsed canonical object
         * @return the instance
         * @throws IllegalArgumentException or {@link ClassCastException} for a malformed form
         */
        public static Active fromCanonical(Map<?, ?> form) {
            return new Active((String) form.get("instanceId"), (String) form.get("effectId"), (String) form.get("sourceId"),
                    ((Number) form.get("sequence")).intValue(), ((Number) form.get("remaining")).intValue(),
                    ((Number) form.get("stacks")).intValue(), ((Number) form.get("absorption")).intValue(),
                    (String) form.get("untilId"), Boolean.TRUE.equals(form.get("fresh")));
        }
    }

    /** What applying an effect did, for the public record. */
    public enum Outcome {
        /** A new instance joined the holder. */
        APPLIED,
        /** A new instance took the place of the key's instances. */
        REPLACED,
        /** An existing instance restarted its duration, with its magnitude unchanged. */
        REFRESHED,
        /** An additive instance gained a stack and restarted its duration. */
        STACKED,
        /** A cap was already reached: nothing changed except, for an additive instance, its duration. */
        CAPPED,
        /** The effect expired the moment it was applied. */
        INSTANT
    }

    /**
     * Result of {@link #apply}.
     *
     * @param held the holder's instances afterwards, in application order
     * @param outcome what happened
     * @param instance the instance added or changed; null when none was
     * @param removed instances the new one replaced, for their own record
     */
    public record Applied(List<Active> held, Outcome outcome, Active instance, List<Active> removed) {
    }

    /**
     * Order of simultaneous effects within a phase.
     *
     * @param library frozen definitions
     * @return priority descending, then sequence ascending, then instance ID ascending
     */
    public static Comparator<Active> order(Map<String, CombatEffect> library) {
        return Comparator.<Active>comparingInt(active -> -definition(library, active).priority())
                .thenComparingInt(Active::sequence).thenComparing(Active::instanceId);
    }

    /**
     * Apply one effect to a holder.
     *
     * @param held the holder's current instances
     * @param effect the incoming definition
     * @param library frozen definitions of the existing instances
     * @param sourceId participant applying it, or null
     * @param holderId the holder
     * @param sequence the encounter's next application counter, used only when a new instance results
     * @param ownActivation whether the holder's own activation is in progress, so it must not count toward the clock
     * @return the result; {@code held} is not modified
     */
    public static Applied apply(List<Active> held, CombatEffect effect, Map<String, CombatEffect> library, String sourceId,
            String holderId, int sequence, boolean ownActivation) {
        if (effect.expiresAtOnce()) {
            return new Applied(List.copyOf(held), Outcome.INSTANT, null, List.of());
        }
        var sameKey = held.stream().filter(active ->
                definition(library, active).stackingKey().equals(effect.stackingKey())).toList();
        Active fresh = fresh(effect, sourceId, holderId, sequence, ownActivation);
        boolean sameDefinition = sameKey.stream().allMatch(active -> active.effectId().equals(effect.id()));
        return switch (effect.stacking()) {
            case REPLACE -> replace(held, sameKey, fresh);
            case REFRESH -> sameKey.isEmpty() ? add(held, fresh)
                    : sameDefinition ? restart(held, sameKey.getFirst(), effect, Outcome.REFRESHED, 0, effect.absorption())
                    : replace(held, sameKey, fresh);
            case STRONGEST -> {
                if (sameKey.isEmpty()) {
                    yield add(held, fresh);
                }
                Active strongest = sameKey.stream().max(Comparator.comparingInt((Active active) ->
                        definition(library, active).magnitude())).orElseThrow();
                CombatEffect standing = definition(library, strongest);
                // The stronger magnitude stays; either way the duration restarts (docs/11 section 11). A weaker shield
                // cannot refill a stronger one that is nearly spent: it leaves the larger of what is left and what it brings.
                yield effect.magnitude() > standing.magnitude() ? replace(held, sameKey, fresh)
                        : restart(held, strongest, standing, Outcome.REFRESHED, 0,
                                Math.max(strongest.absorption(), effect.absorption()));
            }
            case INDEPENDENT -> sameKey.size() >= effect.cap() ? capped(held) : add(held, fresh);
            case ADDITIVE -> {
                if (sameKey.isEmpty()) {
                    yield add(held, fresh);
                }
                if (!sameDefinition) {
                    yield replace(held, sameKey, fresh);
                }
                Active current = sameKey.getFirst();
                int stacks = Math.min(effect.cap(), current.stacks() + 1);
                yield restart(held, current, effect, stacks == current.stacks() ? Outcome.CAPPED : Outcome.STACKED,
                        stacks - current.stacks(), effect.absorption() * stacks);
            }
        };
    }

    /** A new instance of a definition. */
    private static Active fresh(CombatEffect effect, String sourceId, String holderId, int sequence, boolean ownActivation) {
        String until = effect.clock() != CombatEffect.Clock.NEXT_ACTIVATION ? null
                : effect.untilSource() && sourceId != null ? sourceId : holderId;
        return new Active("e" + sequence, effect.id(), sourceId, sequence, start(effect), 1, effect.absorption(), until,
                ownActivation);
    }

    /** The clock's starting units. */
    private static int start(CombatEffect effect) {
        return switch (effect.clock()) {
            case ACTIVATIONS, ROUND_ENDS, WORLD_SECONDS -> effect.count();
            case INSTANT, NEXT_ACTIVATION, PERSISTENT -> 0;
        };
    }

    /** Add a new instance unless the holder is full. */
    private static Applied add(List<Active> held, Active fresh) {
        if (held.size() >= MAX_ACTIVE) {
            return capped(held);
        }
        var list = new ArrayList<>(held);
        list.add(fresh);
        return new Applied(List.copyOf(list), Outcome.APPLIED, fresh, List.of());
    }

    /** Nothing changes. */
    private static Applied capped(List<Active> held) {
        return new Applied(List.copyOf(held), Outcome.CAPPED, null, List.of());
    }

    /** Replace the key's instances with a new one; that never needs more room than they held, except for a new key. */
    private static Applied replace(List<Active> held, List<Active> sameKey, Active fresh) {
        if (sameKey.isEmpty()) {
            return add(held, fresh);
        }
        var list = new ArrayList<>(held);
        list.removeAll(sameKey);
        list.add(fresh);
        return new Applied(List.copyOf(list), Outcome.REPLACED, fresh, List.copyOf(sameKey));
    }

    /** Restart an instance's duration on its definition, add stacks and set the absorption it holds afterwards. */
    private static Applied restart(List<Active> held, Active current, CombatEffect definition, Outcome outcome, int stacks,
            int absorption) {
        var changed = new Active(current.instanceId(), current.effectId(), current.sourceId(), current.sequence(),
                start(definition), current.stacks() + stacks, absorption, current.untilId(), current.fresh());
        var list = new ArrayList<>(held);
        list.set(list.indexOf(current), changed);
        return new Applied(List.copyOf(list), outcome, changed, List.of());
    }

    /**
     * @param held a holder's instances
     * @param library frozen definitions
     * @return the sum of their Guard modifiers, before the profile's situational cap
     */
    public static int guard(List<Active> held, Map<String, CombatEffect> library) {
        return held.stream().mapToInt(active -> definition(library, active).guard() * active.stacks()).sum();
    }

    /**
     * @param held a holder's instances
     * @param library frozen definitions
     * @return the sum of their Resistance modifiers, before the profile's situational cap
     */
    public static int resistance(List<Active> held, Map<String, CombatEffect> library) {
        return held.stream().mapToInt(active -> definition(library, active).resistance() * active.stacks()).sum();
    }

    /**
     * @param held a holder's instances
     * @param library frozen definitions
     * @return the sum of their attack modifiers, before the profile's situational clamp
     */
    public static int attack(List<Active> held, Map<String, CombatEffect> library) {
        return held.stream().mapToInt(active -> definition(library, active).attack() * active.stacks()).sum();
    }

    /**
     * @param held a holder's instances
     * @return absorption left across them
     */
    public static int absorption(List<Active> held) {
        return held.stream().mapToInt(Active::absorption).sum();
    }

    /**
     * @param held a holder's instances
     * @param library frozen definitions
     * @param what a capability
     * @return whether an instance prevents it
     */
    public static boolean prevents(List<Active> held, Map<String, CombatEffect> library, CombatEffect.Prevents what) {
        return held.stream().anyMatch(active -> definition(library, active).prevents().contains(what));
    }

    /**
     * Consume absorption in effect order.
     *
     * @param held a holder's instances
     * @param library frozen definitions
     * @param amount absorption a hit used, at most {@link #absorption}
     * @return the instances afterwards; one that started with absorption and has none left is gone, a shield being spent
     */
    public static List<Active> consumeAbsorption(List<Active> held, Map<String, CombatEffect> library, int amount) {
        int left = amount;
        var byInstance = new LinkedHashMap<String, Active>();
        held.forEach(active -> byInstance.put(active.instanceId(), active));
        for (Active active : held.stream().sorted(order(library)).toList()) {
            int used = Math.min(left, active.absorption());
            if (used > 0) {
                left -= used;
                int after = active.absorption() - used;
                if (after == 0) {
                    byInstance.remove(active.instanceId());
                } else {
                    byInstance.put(active.instanceId(), new Active(active.instanceId(), active.effectId(), active.sourceId(),
                            active.sequence(), active.remaining(), active.stacks(), after, active.untilId(), active.fresh()));
                }
            }
        }
        return List.copyOf(byInstance.values());
    }

    /**
     * The holder's activation ended: its activation clocks count it, unless it started before the instance was applied.
     *
     * @param held the holder's instances
     * @param library frozen definitions
     * @return the instances that remain and those that expired
     */
    public static Elapsed afterActivation(List<Active> held, Map<String, CombatEffect> library) {
        var kept = new ArrayList<Active>();
        var expired = new ArrayList<Active>();
        for (Active active : held) {
            boolean counts = definition(library, active).clock() == CombatEffect.Clock.ACTIVATIONS && !active.fresh();
            int remaining = counts ? active.remaining() - 1 : active.remaining();
            if (counts && remaining <= 0) {
                expired.add(active);
            } else {
                kept.add(new Active(active.instanceId(), active.effectId(), active.sourceId(), active.sequence(), remaining,
                        active.stacks(), active.absorption(), active.untilId(), false));
            }
        }
        return new Elapsed(List.copyOf(kept), List.copyOf(expired));
    }

    /**
     * The round ended: round clocks tick once and the world-time clock loses the round's seconds. A clock that is not
     * counted in the unit that just passed does not change, so no effect is ticked twice for one round.
     *
     * @param held the holder's instances
     * @param library frozen definitions
     * @param seconds seconds the round took
     * @return the instances that remain and those that expired
     */
    public static Elapsed afterRound(List<Active> held, Map<String, CombatEffect> library, int seconds) {
        var kept = new ArrayList<Active>();
        var expired = new ArrayList<Active>();
        for (Active active : held) {
            int passed = switch (definition(library, active).clock()) {
                case ROUND_ENDS -> 1;
                case WORLD_SECONDS -> seconds;
                case INSTANT, NEXT_ACTIVATION, ACTIVATIONS, PERSISTENT -> 0;
            };
            int remaining = active.remaining() - passed;
            if (passed > 0 && remaining <= 0) {
                expired.add(active);
            } else {
                kept.add(new Active(active.instanceId(), active.effectId(), active.sourceId(), active.sequence(), remaining,
                        active.stacks(), active.absorption(), active.untilId(), active.fresh()));
            }
        }
        return new Elapsed(List.copyOf(kept), List.copyOf(expired));
    }

    /**
     * What a clock left.
     *
     * @param kept instances that remain
     * @param expired instances that ended
     */
    public record Elapsed(List<Active> kept, List<Active> expired) {
    }

    /** The frozen definition of an instance; its absence is a broken storage guarantee. */
    private static CombatEffect definition(Map<String, CombatEffect> library, Active active) {
        CombatEffect effect = library.get(active.effectId());
        if (effect == null) {
            throw new IllegalStateException("active effect " + active.effectId() + " has no frozen definition");
        }
        return effect;
    }
}
