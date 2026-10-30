package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatEffect.Clock;
import org.rpgengine.combat.CombatEffect.Stacking;
import org.rpgengine.combat.CombatEffects.Active;
import org.rpgengine.combat.CombatEffects.Outcome;
import org.rpgengine.rules.CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure acceptance of the P11.4 stacking, magnitude, ordering and clock rules of docs/11 section 11, including a seeded
 * walk over every stacking mode whose invariants must hold after each application.
 */
class CombatEffectsTest {
    /** Construct a stateless fixture. */
    CombatEffectsTest() {
    }

    /** A definition with only what a case varies. */
    private static CombatEffect effect(String id, String key, Stacking stacking, int cap, Clock clock, int count, int guard,
            int absorption) {
        return new CombatEffect(id, key, stacking, cap, clock, count, false, 0, guard, 0, 0, absorption, Set.of(), List.of(),
                false);
    }

    /** The definitions as a library. */
    private static Map<String, CombatEffect> library(CombatEffect... effects) {
        var map = new TreeMap<String, CombatEffect>();
        for (CombatEffect effect : effects) {
            map.put(effect.id(), effect);
        }
        return map;
    }

    /** Apply as a source named {@code s} to a holder named {@code h}, outside the holder's activation. */
    private static CombatEffects.Applied apply(List<Active> held, CombatEffect effect, Map<String, CombatEffect> library,
            int sequence) {
        return CombatEffects.apply(held, effect, library, "s", "h", sequence, false);
    }

    /** The default mode keeps the strongest magnitude and restarts the duration; a stronger one takes over. */
    @Test
    void strongestKeepsTheStrongerMagnitudeAndRestartsTheDuration() {
        var weak = effect("weak", "k", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, 5, 1, 0);
        var strong = effect("strong", "k", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, 2, 3, 0);
        var library = library(weak, strong);

        var first = apply(List.of(), strong, library, 1);
        assertEquals(Outcome.APPLIED, first.outcome());
        var aged = List.of(new Active("e1", "strong", "s", 1, 1, 1, 0, null, false));
        var kept = apply(aged, weak, library, 2);
        assertEquals(Outcome.REFRESHED, kept.outcome());
        assertEquals(1, kept.held().size());
        assertEquals("strong", kept.held().getFirst().effectId(), "the stronger magnitude stays");
        assertEquals(2, kept.held().getFirst().remaining(), "on its own definition's full duration");
        assertEquals(3, CombatEffects.guard(kept.held(), library));

        var taken = apply(first.held().stream().map(a -> new Active(a.instanceId(), "weak", a.sourceId(), a.sequence(), 1, 1, 0,
                null, false)).toList(), strong, library, 2);
        assertEquals(Outcome.REPLACED, taken.outcome());
        assertEquals("strong", taken.held().getFirst().effectId());
        assertEquals(List.of("e1"), taken.removed().stream().map(Active::instanceId).toList());
    }

    /** An equal magnitude is no stronger: the instance standing stays with its identity and only its duration restarts. */
    @Test
    void strongestKeepsTheStandingInstanceOnATie() {
        var first = effect("first", "k", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, 5, 2, 0);
        var second = effect("second", "k", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, 2, 2, 0);
        var kept = apply(List.of(new Active("e1", "first", "s", 1, 1, 1, 0, null, false)), second, library(first, second), 2);
        assertEquals(Outcome.REFRESHED, kept.outcome());
        assertEquals(new Active("e1", "first", "s", 1, 5, 1, 0, null, false), kept.held().getFirst());
        assertEquals(List.of(), kept.removed());
    }

    /** Every modifier of an additive instance is multiplied by its stacks, and independent instances add up. */
    @Test
    void modifiersMultiplyByStacks() {
        var both = new CombatEffect("both", "k", Stacking.ADDITIVE, 3, Clock.PERSISTENT, 0, false, 2, 1, 3, 2, 0, Set.of(), List.of(), false);
        var library = library(both);
        var stacked = List.of(new Active("e1", "both", null, 1, 0, 3, 0, null, false));
        assertEquals(6, CombatEffects.attack(stacked, library));
        assertEquals(3, CombatEffects.guard(stacked, library));
        assertEquals(9, CombatEffects.resistance(stacked, library));
        var two = List.of(stacked.getFirst(), new Active("e2", "both", null, 2, 0, 1, 0, null, false));
        assertEquals(8, CombatEffects.attack(two, library));
    }

    /** A weaker shield cannot refill a stronger one that is nearly spent. */
    @Test
    void aWeakerShieldDoesNotRefillAStrongerOne() {
        var big = effect("big", "ward", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 0, 10);
        var small = effect("small", "ward", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 0, 2);
        var library = library(big, small);
        var worn = List.of(new Active("e1", "big", "s", 1, 0, 1, 1, null, false));
        assertEquals(2, apply(worn, small, library, 2).held().getFirst().absorption(), "the larger of what is left and what it brings");
        var kept = List.of(new Active("e1", "big", "s", 1, 0, 1, 7, null, false));
        assertEquals(7, apply(kept, small, library, 2).held().getFirst().absorption());
        assertEquals(10, apply(kept, big, library, 2).held().getFirst().absorption(), "the same shield applied again is full");
    }

    /** Replace always takes over; refresh restarts the same definition and replaces another. */
    @Test
    void replaceAndRefresh() {
        var one = effect("one", "k", Stacking.REPLACE, 1, Clock.ROUND_ENDS, 3, 2, 0);
        var two = effect("two", "k", Stacking.REPLACE, 1, Clock.ROUND_ENDS, 1, 1, 0);
        var library = library(one, two);
        var replaced = apply(apply(List.of(), one, library, 1).held(), two, library, 2);
        assertEquals(Outcome.REPLACED, replaced.outcome());
        assertEquals("two", replaced.held().getFirst().effectId(), "replace ignores magnitude");
        assertEquals("e2", replaced.held().getFirst().instanceId());

        var fresh = effect("fresh", "r", Stacking.REFRESH, 1, Clock.ROUND_ENDS, 3, 1, 0);
        var other = effect("other", "r", Stacking.REFRESH, 1, Clock.ROUND_ENDS, 4, 2, 0);
        var refreshing = library(fresh, other);
        var aged = List.of(new Active("e1", "fresh", "s", 1, 1, 1, 0, null, false));
        var restarted = apply(aged, fresh, refreshing, 2);
        assertEquals(Outcome.REFRESHED, restarted.outcome());
        assertEquals("e1", restarted.instance().instanceId(), "the same instance keeps its place in the order");
        assertEquals(3, restarted.held().getFirst().remaining());
        assertEquals(Outcome.REPLACED, apply(aged, other, refreshing, 2).outcome());
    }

    /** Independent instances coexist up to the cap, each with its own clock; beyond it nothing changes. */
    @Test
    void independentInstancesStopAtTheirCap() {
        var bleed = effect("bleed", "bleed", Stacking.INDEPENDENT, 2, Clock.ROUND_ENDS, 2, -1, 0);
        var library = library(bleed);
        var one = apply(List.of(), bleed, library, 1);
        var two = apply(one.held(), bleed, library, 2);
        assertEquals(Outcome.APPLIED, two.outcome());
        assertEquals(-2, CombatEffects.guard(two.held(), library), "independent instances add up");
        var three = apply(two.held(), bleed, library, 3);
        assertEquals(Outcome.CAPPED, three.outcome());
        assertNull(three.instance());
        assertEquals(two.held(), three.held());
    }

    /** Additive stacks multiply magnitude up to the cap; every application restarts the duration. */
    @Test
    void additiveStacksMultiplyUpToTheirCap() {
        var poison = effect("poison", "poison", Stacking.ADDITIVE, 3, Clock.ROUND_ENDS, 2, -1, 1);
        var library = library(poison);
        var held = apply(List.of(), poison, library, 1).held();
        held = List.of(new Active("e1", "poison", "s", 1, 1, 1, 0, null, false));
        var two = apply(held, poison, library, 2);
        assertEquals(Outcome.STACKED, two.outcome());
        assertEquals(2, two.held().getFirst().stacks());
        assertEquals(2, two.held().getFirst().remaining());
        assertEquals(2, two.held().getFirst().absorption());
        assertEquals(-2, CombatEffects.guard(two.held(), library));
        var three = apply(two.held(), poison, library, 3);
        var capped = apply(three.held(), poison, library, 4);
        assertEquals(Outcome.CAPPED, capped.outcome());
        assertEquals(3, capped.held().getFirst().stacks());
        assertEquals("e1", capped.held().getFirst().instanceId());
    }

    /** Zero duration and an instant clock expire at once; a next-activation clock names its actor. */
    @Test
    void durationZeroExpiresAtOnce() {
        for (var clock : List.of(Clock.ACTIVATIONS, Clock.ROUND_ENDS, Clock.WORLD_SECONDS)) {
            var zero = effect("zero", "z", Stacking.STRONGEST, 1, clock, 0, 1, 0);
            var applied = apply(List.of(), zero, library(zero), 1);
            assertEquals(Outcome.INSTANT, applied.outcome(), clock.wire());
            assertTrue(applied.held().isEmpty());
        }
        var instant = effect("flash", "f", Stacking.STRONGEST, 1, Clock.INSTANT, 5, 1, 0);
        assertEquals(Outcome.INSTANT, apply(List.of(), instant, library(instant), 1).outcome());
        assertFalse(effect("held", "f", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 1, 0).expiresAtOnce(),
                "a clock that counts nothing does not read its count");

        var own = new CombatEffect("own", "o", Stacking.STRONGEST, 1, Clock.NEXT_ACTIVATION, 0, false, 0, 1, 0, 0, 0, Set.of(),
                List.of(), false);
        var theirs = new CombatEffect("theirs", "t", Stacking.STRONGEST, 1, Clock.NEXT_ACTIVATION, 0, true, 0, 1, 0, 0, 0,
                Set.of(), List.of(), false);
        var library = library(own, theirs);
        assertEquals("h", apply(List.of(), own, library, 1).instance().untilId());
        assertEquals("s", apply(List.of(), theirs, library, 1).instance().untilId());
        assertEquals("h", CombatEffects.apply(List.of(), theirs, library, null, "h", 1, false).instance().untilId(),
                "a sourceless effect waits for its holder");
    }

    /** A holder at its limit gains no new key, but a replacement of a held key still fits. */
    @Test
    void aFullHolderKeepsWhatItHas() {
        var effects = new ArrayList<CombatEffect>();
        var held = new ArrayList<Active>();
        for (int index = 0; index < CombatEffects.MAX_ACTIVE; index++) {
            effects.add(effect("key" + index, "key" + index, Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 1, 0));
            held.add(new Active("e" + index, "key" + index, null, index, 0, 1, 0, null, false));
        }
        var extra = effect("extra", "extra", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 1, 0);
        var stronger = effect("stronger", "key0", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 3, 0);
        effects.add(extra);
        effects.add(stronger);
        var library = library(effects.toArray(new CombatEffect[0]));
        assertEquals(Outcome.CAPPED, apply(held, extra, library, 99).outcome());
        var replaced = apply(held, stronger, library, 99);
        assertEquals(Outcome.REPLACED, replaced.outcome());
        assertEquals(CombatEffects.MAX_ACTIVE, replaced.held().size());
    }

    /** Priority first (higher wins), then application order, then instance ID. */
    @Test
    void simultaneousEffectsHaveOneOrder() {
        var low = new CombatEffect("low", "l", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, false, -3, 0, 0, 0, 0, Set.of(),
                List.of(), false);
        var high = new CombatEffect("high", "h", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, false, 7, 0, 0, 0, 0, Set.of(),
                List.of(), false);
        var library = library(low, high);
        var sorted = new ArrayList<>(List.of(new Active("e1", "low", null, 1, 0, 1, 0, null, false),
                new Active("e4", "low", null, 4, 0, 1, 0, null, false), new Active("e9", "high", null, 9, 0, 1, 0, null, false),
                new Active("e3", "low", null, 3, 0, 1, 0, null, false)));
        sorted.sort(CombatEffects.order(library));
        assertEquals(List.of("e9", "e1", "e3", "e4"), sorted.stream().map(Active::instanceId).toList());
        // The sequence decides before the identity: read as text, e10 would come before e2.
        var numeric = new ArrayList<>(List.of(new Active("e10", "low", null, 10, 0, 1, 0, null, false),
                new Active("e2", "low", null, 2, 0, 1, 0, null, false)));
        numeric.sort(CombatEffects.order(library));
        assertEquals(List.of("e2", "e10"), numeric.stream().map(Active::instanceId).toList());
    }

    /** Absorption is consumed in effect order; a shield that runs out is gone, and one not touched stays. */
    @Test
    void absorptionWearsShieldsInOrder() {
        var ward = effect("ward", "ward", Stacking.INDEPENDENT, 3, Clock.PERSISTENT, 0, 0, 3);
        var library = library(ward);
        var held = List.of(new Active("e1", "ward", null, 1, 0, 1, 3, null, false),
                new Active("e2", "ward", null, 2, 0, 1, 3, null, false));
        assertEquals(6, CombatEffects.absorption(held));
        var after = CombatEffects.consumeAbsorption(held, library, 4);
        assertEquals(List.of("e2"), after.stream().map(Active::instanceId).toList(), "the first ran out");
        assertEquals(2, after.getFirst().absorption());
        assertEquals(held, CombatEffects.consumeAbsorption(held, library, 0));
    }

    /** Absorption follows effect order, not the order instances were stored in: higher priority wears first. */
    @Test
    void absorptionFollowsPriorityOverStoredOrder() {
        var low = new CombatEffect("low", "low", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, false, 0, 0, 0, 0, 3, Set.of(), List.of(), false);
        var high = new CombatEffect("high", "high", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, false, 5, 0, 0, 0, 3, Set.of(), List.of(), false);
        var held = List.of(new Active("e1", "low", null, 1, 0, 1, 3, null, false), new Active("e2", "high", null, 2, 0, 1, 3, null, false));
        var after = CombatEffects.consumeAbsorption(held, library(low, high), 2);
        assertEquals(List.of(3, 1), after.stream().map(Active::absorption).toList());
    }

    /** A refresh restores a shield's full absorption along with its duration. */
    @Test
    void aRefreshRestoresItsAbsorption() {
        var shield = effect("shield", "k", Stacking.REFRESH, 1, Clock.ROUND_ENDS, 3, 0, 5);
        var restarted = apply(List.of(new Active("e1", "shield", "s", 1, 1, 1, 2, null, false)), shield, library(shield), 2);
        assertEquals(Outcome.REFRESHED, restarted.outcome());
        assertEquals(new Active("e1", "shield", "s", 1, 3, 1, 5, null, false), restarted.held().getFirst());
    }

    /** Among several instances of a key, an incoming effect is measured against the strongest of them. */
    @Test
    void strongestMeetsTheStrongestOfSeveral() {
        var small = effect("small", "k", Stacking.INDEPENDENT, 3, Clock.ROUND_ENDS, 5, 1, 0);
        var big = effect("big", "k", Stacking.INDEPENDENT, 3, Clock.ROUND_ENDS, 5, 3, 0);
        var middle = effect("middle", "k", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, 2, 2, 0);
        var held = List.of(new Active("e1", "small", "s", 1, 1, 1, 0, null, false), new Active("e2", "big", "s", 2, 1, 1, 0, null, false));
        var kept = apply(held, middle, library(small, big, middle), 3);
        assertEquals(Outcome.REFRESHED, kept.outcome());
        assertEquals(List.of(1, 5), kept.held().stream().map(Active::remaining).toList(), "only the strongest restarted");
    }

    /** An activation counts only when the instance was there when it started; expiry reports the instance. */
    @Test
    void activationClocksCountOnlyWholeActivations() {
        var stun = effect("stun", "stun", Stacking.STRONGEST, 1, Clock.ACTIVATIONS, 1, 0, 0);
        var library = library(stun);
        var applied = CombatEffects.apply(List.of(), stun, library, "s", "h", 1, true);
        assertTrue(applied.instance().fresh(), "applied during the holder's own activation");
        var first = CombatEffects.afterActivation(applied.held(), library);
        assertTrue(first.expired().isEmpty(), "the activation in progress does not count");
        assertFalse(first.kept().getFirst().fresh());
        var second = CombatEffects.afterActivation(first.kept(), library);
        assertEquals(1, second.expired().size());
        assertTrue(second.kept().isEmpty());
    }

    /** Round clocks tick once per round, and only the world-time clock counts seconds. */
    @Test
    void eachClockCountsOnlyItsOwnUnit() {
        var rounds = effect("rounds", "a", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, 2, 0, 0);
        var seconds = effect("seconds", "b", Stacking.STRONGEST, 1, Clock.WORLD_SECONDS, 12, 0, 0);
        var activations = effect("activations", "c", Stacking.STRONGEST, 1, Clock.ACTIVATIONS, 2, 0, 0);
        var lasting = effect("lasting", "d", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 0, 0);
        var library = library(rounds, seconds, activations, lasting);
        var held = new ArrayList<Active>();
        int sequence = 1;
        for (var effect : List.of(rounds, seconds, activations, lasting)) {
            held = new ArrayList<>(apply(held, effect, library, sequence++).held());
        }
        var first = CombatEffects.afterRound(held, library, 8);
        assertTrue(first.expired().isEmpty());
        assertEquals(1, remaining(first.kept(), "rounds"));
        assertEquals(4, remaining(first.kept(), "seconds"));
        assertEquals(2, remaining(first.kept(), "activations"), "a round does not count an activation clock");
        var second = CombatEffects.afterRound(first.kept(), library, 8);
        assertEquals(List.of("rounds", "seconds"), second.expired().stream().map(Active::effectId).toList());
        assertEquals(List.of("activations", "lasting"), second.kept().stream().map(Active::effectId).toList());
        var activation = CombatEffects.afterActivation(second.kept(), library);
        assertEquals(1, remaining(activation.kept(), "activations"));
        assertEquals(0, remaining(activation.kept(), "lasting"), "persistent ignores every clock");
        assertEquals(1, CombatEffects.afterRound(activation.kept(), library, 8).kept().stream()
                .filter(active -> active.effectId().equals("activations")).count());
    }

    /** Units left on one definition's instance. */
    private static int remaining(List<Active> held, String effect) {
        return held.stream().filter(active -> active.effectId().equals(effect)).findFirst().orElseThrow().remaining();
    }

    /** Definitions out of bound are refused, and every form reads back identically. */
    @Test
    void definitionsAreBoundedAndReadBack() {
        assertThrows(IllegalArgumentException.class, () -> effect("x", "x", Stacking.STRONGEST, 2, Clock.PERSISTENT, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> effect("x", "x", Stacking.ADDITIVE, 9, Clock.PERSISTENT, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> effect("x", "x", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> effect("x", "x", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> effect("x", "x", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CombatEffect.Tick(CombatEffect.Phase.ROUND_END, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new CombatEffect("x", "x", Stacking.STRONGEST, 1, Clock.ROUND_ENDS, 1,
                true, 0, 0, 0, 0, 0, Set.of(), List.of(), false));
        var tick = new CombatEffect.Tick(CombatEffect.Phase.ROUND_END, 1, false);
        assertThrows(IllegalArgumentException.class, () -> new CombatEffect("x", "x", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0,
                false, 0, 0, 0, 0, 0, Set.of(), List.of(tick, tick, tick), false));
        var full = new CombatEffect("x", "k", Stacking.ADDITIVE, 3, Clock.WORLD_SECONDS, 16, false, 4, -2, 1, -1, 5,
                Set.of(CombatEffect.Prevents.MOVEMENT, CombatEffect.Prevents.RESOURCES),
                List.of(new CombatEffect.Tick(CombatEffect.Phase.ACTIVATION_START, 2, true), tick), true);
        var json = JsonMapper.builder().build();
        assertEquals(full, CombatEffect.fromCanonical(json.readValue(CanonicalJson.write(full.canonicalForm()), Map.class)));
        var active = new Active("e4", "x", null, 4, -3, 2, 1, "h", true);
        assertEquals(active, Active.fromCanonical(json.readValue(CanonicalJson.write(active.canonicalForm()), Map.class)));
        for (CombatEffect.Phase phase : CombatEffect.Phase.values()) {
            assertEquals(phase, CombatEffect.Phase.parse(phase.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> CombatEffect.Phase.parse("midRound"));
        assertThrows(IllegalArgumentException.class, () -> Clock.parse("forever"));
    }

    /** A seeded walk over every mode: caps, stacks, the holder's limit and identities hold after every application. */
    @Test
    void seededApplicationsKeepEveryBound() {
        var defined = new ArrayList<CombatEffect>();
        for (Stacking stacking : Stacking.values()) {
            boolean several = stacking == Stacking.INDEPENDENT || stacking == Stacking.ADDITIVE;
            for (int magnitude = 0; magnitude < 3; magnitude++) {
                defined.add(effect(stacking.wire() + magnitude, stacking.wire(), stacking, several ? 3 : 1, Clock.ROUND_ENDS,
                        magnitude + 1, magnitude, magnitude));
            }
        }
        var library = library(defined.toArray(new CombatEffect[0]));
        var random = new Random(20261030L);
        for (int walk = 0; walk < 200; walk++) {
            var held = new ArrayList<Active>();
            int sequence = 0;
            for (int step = 0; step < 40; step++) {
                var chosen = defined.get(random.nextInt(defined.size()));
                var applied = apply(held, chosen, library, ++sequence);
                held = new ArrayList<>(applied.held());
                assertTrue(held.size() <= CombatEffects.MAX_ACTIVE);
                assertEquals(held.size(), held.stream().map(Active::instanceId).distinct().count(), "identities stay distinct");
                long sameKey = held.stream().filter(a -> library.get(a.effectId()).stackingKey().equals(chosen.stackingKey())).count();
                assertTrue(sameKey <= chosen.cap(), chosen.id());
                for (Active active : held) {
                    var definition = library.get(active.effectId());
                    assertTrue(active.stacks() >= 1 && active.stacks() <= definition.cap());
                    assertTrue(active.absorption() <= definition.absorption() * active.stacks() || active.absorption() <= 2,
                            active.toString());
                }
                if (random.nextInt(4) == 0) {
                    held = new ArrayList<>(CombatEffects.afterRound(held, library, 8).kept());
                    held.forEach(active -> assertTrue(active.remaining() > 0, active.toString()));
                }
            }
        }
    }
}
