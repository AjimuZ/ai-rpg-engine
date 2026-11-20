package org.rpgengine.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Feasibility of one frozen encounter for the player who would enter it (P13.3-A, docs/12 sections 4-5, QA-14): whether every
 * declared objective can still be met, and whether a hostile participant is a hidden hard counter. Pure: it reads only the
 * {@link FrozenEncounter}, the exact definition entry would store, so the encounter that is checked is the encounter that is
 * entered.
 *
 * <p><b>Hidden.</b> Entry reveals participants, sides, zones, escape zones, stakes and the opening policy, never a statistic,
 * attack, reaction or effect (ADR 0042). Every counter below therefore lies in what the player cannot see before the first
 * irreversible action, so every one is refused; no encounter can disclose one yet.
 *
 * <p><b>The player's walk.</b> The zones the player can stand in: the player's zone, then every zone along a traversable edge.
 * An edge is traversable when it is unblocked or when a declared scene action that opens its passage can be used from a zone
 * already walked (the passage's own zone, or anywhere when it names none). A zone other than the player's is closed to the
 * player when the other participants starting in it already fill its capacity: the player cannot make them leave, and a
 * defeated participant keeps its place, so defeating them does not open it.
 *
 * <p><b>Findings</b>, in a fixed order: first each objective in declared order, then each hostile participant in roster order.
 * <ul>
 *   <li>{@link Violation#UNREACHABLE_OBJECTIVE}: a reach-zone objective whose zone, or an escape objective none of whose escape
 *       zones, lies in the player's walk ({@link Reason#NO_PATH}); a survive objective of more rounds than the profile's round
 *       cap, which ends the fight first ({@link Reason#ROUND_CAP}); a defeat-hostiles or protect objective while a hostile is
 *       countered as below ({@link Reason#COUNTERED}).</li>
 *   <li>{@link Violation#HARD_COUNTER}, only while a defeat-hostiles or protect objective asks for the hostile to leave the
 *       fight: no means of the player can ever take its Health or make it surrender ({@link Reason#UNHARMABLE}), or none of
 *       those means can ever reach it from the player's walk, wherever it walks itself ({@link Reason#OUT_OF_REACH}).</li>
 *   <li>{@link Violation#HARD_COUNTER} under any objective: one of its attacks, or a reaction it applies to its attacker, can
 *       put an effect on the player that prevents actions for the rest of the fight ({@link Reason#ACTION_LOCK}): a persistent
 *       clock, even one that ends with its source, because the player cannot act to defeat the source; or a count of
 *       activations or round ends of at least the round cap, or of world seconds of at least the cap's rounds.</li>
 * </ul>
 *
 * <p><b>The player's means</b>, each judged on its best case so that a refusal is never a matter of dice:
 * <ul>
 *   <li>an attack harms when its best score (two tens, the attribute, the skill rank and the full situational modifier) meets
 *       the target's lowest defense (its frozen Guard or Resistance less the full situational cap), and either its largest
 *       damage (every die at its highest, the damage attribute, the flat modifier and a strong-hit die when allowed) exceeds
 *       the target's armor after penetration, or a hit puts a damaging tick on the target. It reaches from a zone within its
 *       range; across zones the target's zone must be neither obscured nor in full cover. Ammunition is assumed available;</li>
 *   <li>an ability that targets hostiles and whose Focus cost the player's Focus maximum can pay harms through a damage
 *       operation exceeding the armor after penetration or an applied damaging tick; it reaches like an attack;</li>
 *   <li>a declared negotiation whose terms or counteroffer make the recipient surrender takes any hostile out within voice
 *       range ({@code maxRangeEdges}, which ignores sight).</li>
 * </ul>
 * Allies, reactions and the opponents' choices are not counted as the player's means: a counter the player can escape only if
 * someone else acts is still a counter. These checks bound obvious failures; they do not prove an encounter balanced or fair.
 */
public final class EncounterFeasibility {
    /** Best total of the two attack dice. */
    static final int BEST_ROLL = 2 * CombatProfile.ATTACK_SIDES;

    /** Static checks only. */
    private EncounterFeasibility() {
    }

    /** What a finding refuses. */
    public enum Violation {
        /** A declared objective can never be met. */
        UNREACHABLE_OBJECTIVE,
        /** A hostile participant counters the player in a way the player cannot see or overcome. */
        HARD_COUNTER;

        /** @return the lowercase hyphenated code, as diagnostics and decisions report it */
        public String code() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Why a finding was made. */
    public enum Reason {
        /** No walk of the player reaches the zone the objective needs. */
        NO_PATH,
        /** The objective needs more rounds than the round cap allows. */
        ROUND_CAP,
        /** The objective needs a countered hostile out of the fight. */
        COUNTERED,
        /** Nothing the player has can take the hostile's Health or make it surrender. */
        UNHARMABLE,
        /** The player's harmful means can never reach the hostile. */
        OUT_OF_REACH,
        /** The hostile can deny the player every remaining action. */
        ACTION_LOCK;

        /** @return the lowercase hyphenated code */
        public String code() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * One refusal.
     *
     * @param violation what it refuses
     * @param subject the objective ID for {@link Violation#UNREACHABLE_OBJECTIVE}, the hostile participant ID otherwise
     * @param reason why
     */
    public record Finding(Violation violation, String subject, Reason reason) {
        /** Require every value. */
        public Finding {
            Objects.requireNonNull(violation, "violation");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The verdict on one frozen encounter.
     *
     * @param findings every finding in the fixed order; empty when the encounter is feasible
     */
    public record Verdict(List<Finding> findings) {
        /** Freeze the findings. */
        public Verdict {
            findings = List.copyOf(findings);
        }

        /** @return whether the encounter has no finding */
        public boolean admitted() {
            return findings.isEmpty();
        }
    }

    /**
     * Check one frozen encounter.
     *
     * @param encounter the encounter exactly as entry would store it, the player first
     * @return every finding in the fixed order
     */
    public static Verdict check(FrozenEncounter encounter) {
        if (encounter.player().side() != EncounterTemplate.Side.PLAYER) {
            throw new IllegalStateException("a frozen encounter lists the player first");
        }
        var space = encounter.zones();
        var traversable = new HashSet<ZoneGraph.Edge>();
        var walk = playerWalk(encounter, traversable);
        boolean elimination = encounter.objectives().stream().anyMatch(objective ->
                objective.kind() == EncounterObjective.Kind.DEFEAT_HOSTILES
                        || objective.kind() == EncounterObjective.Kind.PROTECT);
        var counters = new ArrayList<Finding>();
        boolean anyCountered = false;
        for (FrozenEncounter.Participant participant : encounter.participants()) {
            if (participant.side() != EncounterTemplate.Side.HOSTILE) {
                continue;
            }
            if (elimination) {
                Reason reason = countered(encounter, walk, walk(space, traversable, participant.zone(), Set.of()), participant);
                if (reason != null) {
                    counters.add(new Finding(Violation.HARD_COUNTER, participant.id(), reason));
                    anyCountered = true;
                }
            }
            if (locks(encounter, participant)) {
                counters.add(new Finding(Violation.HARD_COUNTER, participant.id(), Reason.ACTION_LOCK));
            }
        }
        var findings = new ArrayList<Finding>();
        for (EncounterObjective objective : encounter.objectives()) {
            Reason reason = switch (objective.kind()) {
                case REACH_ZONE -> walk.contains(objective.zone()) ? null : Reason.NO_PATH;
                case ESCAPE -> encounter.escapeZones().stream().anyMatch(walk::contains) ? null : Reason.NO_PATH;
                case SURVIVE -> objective.rounds() > encounter.profile().roundCap() ? Reason.ROUND_CAP : null;
                case DEFEAT_HOSTILES, PROTECT -> anyCountered ? Reason.COUNTERED : null;
            };
            if (reason != null) {
                findings.add(new Finding(Violation.UNREACHABLE_OBJECTIVE, objective.id(), reason));
            }
        }
        findings.addAll(counters);
        return new Verdict(findings);
    }

    /**
     * The player's walk, opening every passage a declared scene action can open from a walked zone until nothing changes.
     *
     * @param traversable receives every edge movement can use, opened passages included
     * @return the zones the player can stand in, sorted
     */
    private static Set<String> playerWalk(FrozenEncounter encounter, Set<ZoneGraph.Edge> traversable) {
        var space = encounter.zones();
        space.edges().stream().filter(edge -> !edge.blocked()).forEach(traversable::add);
        var occupants = new HashMap<String, Integer>();
        encounter.participants().stream().skip(1).forEach(other -> occupants.merge(other.zone(), 1, Integer::sum));
        var closed = new TreeSet<String>();
        occupants.forEach((zone, count) -> {
            int capacity = space.zones().get(zone).capacity();
            if (capacity > 0 && count >= capacity && !zone.equals(encounter.player().zone())) {
                closed.add(zone);
            }
        });
        while (true) {
            var walk = walk(space, traversable, encounter.player().zone(), closed);
            boolean opened = false;
            for (EncounterAction action : encounter.actions()) {
                if (action.kind() != EncounterAction.Kind.SCENE || action.passage().blocked()
                        || action.zone() != null && !walk.contains(action.zone())) {
                    continue;
                }
                for (ZoneGraph.Edge edge : space.edges()) {
                    if (action.passage().covers(edge.from(), edge.to()) && traversable.add(edge)) {
                        opened = true;
                    }
                }
            }
            if (!opened) {
                return walk;
            }
        }
    }

    /** Zones reachable from a start along traversable edges, never entering a closed zone; the start always counts. */
    private static Set<String> walk(ZoneGraph space, Set<ZoneGraph.Edge> traversable, String start, Set<String> closed) {
        var reached = new TreeSet<String>(Set.of(start));
        var queue = new ArrayDeque<String>(List.of(start));
        while (!queue.isEmpty()) {
            String zone = queue.poll();
            for (ZoneGraph.Edge edge : space.edges()) {
                if (edge.from().equals(zone) && traversable.contains(edge) && !closed.contains(edge.to())
                        && reached.add(edge.to())) {
                    queue.add(edge.to());
                }
            }
        }
        return reached;
    }

    /**
     * @param walk the player's walk
     * @param roam every zone the hostile can walk to, capacity ignored, so it is never assumed to stay put
     * @return why the hostile counters the player, or null when some means of the player can take it out
     */
    private static Reason countered(FrozenEncounter encounter, Set<String> walk, Set<String> roam,
            FrozenEncounter.Participant hostile) {
        var space = encounter.zones();
        boolean harmed = false;
        for (CombatAttack attack : encounter.player().attacks()) {
            if (harms(encounter, attack, hostile)) {
                harmed = true;
                if (reaches(space, walk, roam, attack.range(), true)) {
                    return null;
                }
            }
        }
        for (CombatAbility ability : encounter.player().abilities()) {
            if (harms(encounter, ability, hostile)) {
                harmed = true;
                if (reaches(space, walk, roam, ability.range(), true)) {
                    return null;
                }
            }
        }
        if (yields(encounter)) {
            harmed = true;
            if (reaches(space, walk, roam, encounter.profile().value("maxRangeEdges"), false)) {
                return null;
            }
        }
        return harmed ? Reason.OUT_OF_REACH : Reason.UNHARMABLE;
    }

    /** Whether an attack of the player can ever take the hostile's Health, on its best case. */
    private static boolean harms(FrozenEncounter encounter, CombatAttack attack, FrozenEncounter.Participant target) {
        var profile = encounter.profile();
        var player = encounter.player();
        long best = BEST_ROLL + role(player, attack.attribute()) + attack.skill() + profile.value("situationalModifierCap");
        long defense = attack.defense() == CombatAttack.Defense.GUARD ? target.guard() : target.resistance();
        if (best < Math.max(0, defense - profile.value("situationalGuardCap"))) {
            return false;
        }
        long damage = (long) attack.diceCount() * attack.diceSides() + attack.flat()
                + (attack.damageAttribute() == null ? 0 : role(player, attack.damageAttribute()))
                + (attack.strongHit() ? profile.value("strongHitDieSides") : 0);
        if (damage > Math.max(0, target.armor() - attack.penetration())) {
            return true;
        }
        // Only an effect of any hit counts here: one applied after damage needs Health taken, which armor already prevents.
        return attack.onHit().stream().anyMatch(hit -> !hit.toSelf() && hit.phase() == CombatEffect.Phase.POST_HIT
                && wounds(encounter.effects().get(hit.effectId())));
    }

    /** Whether an ability of the player can ever take the hostile's Health; it needs no roll. */
    private static boolean harms(FrozenEncounter encounter, CombatAbility ability, FrozenEncounter.Participant target) {
        var player = encounter.player();
        if (ability.targets() != CombatAbility.Targets.HOSTILE || ability.focus() > player.focusMax()) {
            return false;
        }
        for (CombatAbility.Operation operation : ability.operations()) {
            if (operation.toSelf()) {
                continue;
            }
            if (operation.kind() == CombatAbility.Kind.DAMAGE) {
                long amount = (long) operation.diceCount() * operation.diceSides() + operation.flat()
                        + (operation.attribute() == null ? 0 : role(player, operation.attribute()));
                if (amount > Math.max(0, target.armor() - operation.penetration())) {
                    return true;
                }
            }
            if (operation.kind() == CombatAbility.Kind.APPLY && wounds(encounter.effects().get(operation.effectId()))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a declared negotiation can make a hostile recipient surrender. */
    private static boolean yields(FrozenEncounter encounter) {
        return encounter.actions().stream().anyMatch(action -> action.kind() == EncounterAction.Kind.SOCIAL
                && action.recipients().admits(EncounterTemplate.Side.HOSTILE)
                && (surrenders(action.terms()) || surrenders(action.counteroffer())));
    }

    /** @return whether terms make their recipient surrender */
    private static boolean surrenders(Negotiation.Terms terms) {
        return terms != null && terms.kind() == Negotiation.Terms.Kind.RECIPIENT_SURRENDERS;
    }

    /**
     * Whether a means of the given range can ever take a target in one zone from another: some zone the player walks to and
     * some zone the target walks to lie within range, and across zones the target's zone must be visible and not in full cover
     * when the means needs sight. Range ignores blocking, as {@link ZoneGraph#distance} does.
     */
    private static boolean reaches(ZoneGraph space, Set<String> walk, Set<String> roam, int range, boolean sight) {
        for (String from : walk) {
            for (String to : roam) {
                var zone = space.zones().get(to);
                boolean seen = from.equals(to) || !sight || !zone.obscured() && zone.cover() != ZoneGraph.Cover.FULL;
                if (seen && space.distance(from, to) <= range) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a hostile participant can put an action-preventing effect on the player for the rest of the fight. */
    private static boolean locks(FrozenEncounter encounter, FrozenEncounter.Participant hostile) {
        for (CombatAttack attack : hostile.attacks()) {
            for (CombatAttack.OnHit hit : attack.onHit()) {
                if (!hit.toSelf() && indefinite(encounter.profile(), encounter.effects().get(hit.effectId()))) {
                    return true;
                }
            }
        }
        for (CombatReaction reaction : hostile.reactions()) {
            if (reaction.kind() == CombatReaction.Kind.APPLY && reaction.onAttacker()
                    && indefinite(encounter.profile(), encounter.effects().get(reaction.effectId()))) {
                return true;
            }
        }
        return false;
    }

    /** Whether an effect prevents actions for at least as long as any fight can last; a condition without one has no effect. */
    private static boolean indefinite(CombatProfile profile, CombatEffect effect) {
        if (effect == null || !effect.prevents().contains(CombatEffect.Prevents.ACTIONS) || effect.expiresAtOnce()) {
            return false;
        }
        int cap = profile.roundCap();
        return switch (effect.clock()) {
            case PERSISTENT -> true;
            case ACTIVATIONS, ROUND_ENDS -> effect.count() >= cap;
            case WORLD_SECONDS -> (long) effect.count() >= (long) cap * profile.roundSeconds();
            case INSTANT, NEXT_ACTIVATION -> false;
        };
    }

    /** Whether an effect deals damage through a tick, which armor does not reduce. */
    private static boolean wounds(CombatEffect effect) {
        return effect != null && effect.ticks().stream().anyMatch(tick -> !tick.heal());
    }

    /** The player's frozen value of a ruleset role; the freeze step requires all four. */
    private static long role(FrozenEncounter.Participant player, String role) {
        Long value = player.roles().get(role);
        if (value == null) {
            throw new IllegalStateException("the frozen player has no " + role + " value");
        }
        return value;
    }
}
