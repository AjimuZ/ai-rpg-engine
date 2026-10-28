package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.rules.DeterministicDraw;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic local calculation of one registered exploration intention (P07.4). Given
 * a detached snapshot read under the campaign lock, the pinned rule catalog and a source of
 * durable draws, it returns the complete trusted effect set for {@link ResolvedCommit}:
 * world counters, the resulting pending decision, resource deltas, actor placements,
 * ordered events with calculation inputs and draw ordinals, receipt details and the query
 * scopes the effects change. It performs no I/O of its own; only the supplied
 * {@link Draws} reaches storage, and only to reserve or replay draws.
 *
 * <p>Determinism: the same intention, snapshot and draw values always give byte-identical
 * output. Draw ordinals are assigned in evaluation order starting at zero, one per die, so
 * a retry of the same operation replays the same reserved values. The calculation never
 * invents a value: an attribute or resource that an expression reads but the save lacks is
 * a resolver failure ({@link IllegalStateException}), never a silent zero.
 *
 * <p>Provisional exploration timing until the world clock (P10.4): every action advances
 * the game turn once; {@code WAIT} advances world time by exactly the requested seconds and
 * movement, speech and ability use take no world time, because pinned links and abilities
 * declare no duration yet. Every action resolves to "no pending decision" (null), which also
 * clears the creation starting marker: the player may submit any registered intention next.
 */
final class LocalActionResolver {
    /** Receipt marker of this resolver; the player-safe projection trusts only receipts carrying it. */
    static final String RESOLVER = "local-v1";
    /** Version of every event type and payload schema written by this resolver. */
    static final int EVENT_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Static calculation entry point only. */
    private LocalActionResolver() { }

    /**
     * Source of durable random draws for this operation.
     */
    @FunctionalInterface
    interface Draws {
        /**
         * Reserve or replay one draw.
         *
         * @param ordinal 0-based ordinal within the operation, below {@link DeterministicDraw#MAX_DRAWS}
         * @param sides number of faces, at least two
         * @return value in 1..sides; the same ordinal of the same operation always returns the same value
         */
        int value(int ordinal, int sides);
    }

    /**
     * One resource pool as read under the campaign lock.
     *
     * @param id pool identity
     * @param rowVersion storage version the commit must still observe
     * @param minimum inclusive lower bound
     * @param current current value
     * @param maximum inclusive upper bound
     */
    record Pool(UUID id, long rowVersion, long minimum, long current, long maximum) {
        /** Require an identity; bounds were enforced by the database. */
        Pool { Objects.requireNonNull(id, "id"); }
    }

    /**
     * Detached facts the calculation reads.
     *
     * @param player acting player
     * @param location player's current location, or null
     * @param playerRowVersion storage version of the player's actor row, for placements
     * @param attributes player's attribute values by unqualified key
     * @param skills player's skill ranks by canonical skill ID
     * @param pools resource pools of every participant, by actor then unqualified key
     * @param vocabularies declaring pack of each participant's origin, or no entry when unknown
     */
    record Snapshot(UUID player, String location, long playerRowVersion, Map<String, Long> attributes,
            Map<String, Long> skills, Map<UUID, Map<String, Pool>> pools, Map<UUID, String> vocabularies) {
        /** Freeze the maps; nested pool maps are copied too. */
        Snapshot {
            Objects.requireNonNull(player, "player");
            attributes = Map.copyOf(attributes);
            skills = Map.copyOf(skills);
            var frozen = new LinkedHashMap<UUID, Map<String, Pool>>();
            pools.forEach((actor, byKey) -> frozen.put(actor, Map.copyOf(byKey)));
            pools = Map.copyOf(frozen);
            vocabularies = Map.copyOf(vocabularies);
        }
    }

    /**
     * Complete trusted effects of one intention.
     *
     * @param world turn/time change and the resulting mode and pending decision
     * @param resources net delta per changed pool, ordered by pool identity
     * @param placements actor location changes
     * @param events ordered events (at least one)
     * @param receiptDetails owner-safe receipt details JSON object
     * @param queryScopes sorted distinct query scopes whose truth the effects change
     */
    record Resolution(ResolvedCommit.WorldChange world, List<ResolvedCommit.ResourceChange> resources,
            List<ResolvedCommit.Placement> placements, List<ResolvedCommit.Event> events, String receiptDetails,
            List<String> queryScopes) { }

    /**
     * Calculate the effects of an intention that already passed {@link CommandRules}.
     *
     * @param command validated intention
     * @param snapshot facts read under the lock that also validated the intention
     * @param catalog pinned rule facts; required for ability use, may be null otherwise
     * @param draws durable draw source bound to this operation
     * @return immutable effect set
     * @throws IllegalStateException if the snapshot contradicts the validated premises or an
     *         expression needs a value the save does not hold
     */
    static Resolution resolve(TypedCommand command, Snapshot snapshot, CommandRuleCatalog catalog, Draws draws) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(draws, "draws");
        return new Run(snapshot, draws).resolve(command, catalog);
    }

    /** Single-use mutable calculation state; never shared between threads. */
    private static final class Run {
        private final Snapshot snapshot;
        private final Draws draws;
        private final Map<UUID, Map<String, Long>> values = new LinkedHashMap<>();
        private final List<ResolvedCommit.Event> events = new ArrayList<>();
        private final List<ResolvedCommit.Placement> placements = new ArrayList<>();
        private final ObjectNode details = JSON.createObjectNode();
        private final ArrayNode calculations = JSON.createArrayNode();
        private int nextOrdinal;

        /** Copy current pool values into the running state. */
        Run(Snapshot snapshot, Draws draws) {
            this.snapshot = snapshot;
            this.draws = draws;
            snapshot.pools().forEach((actor, byKey) -> {
                var current = new TreeMap<String, Long>();
                byKey.forEach((key, pool) -> current.put(key, pool.current()));
                values.put(actor, current);
            });
        }

        /** Dispatch on the closed intention vocabulary, then assemble the effect set. */
        Resolution resolve(TypedCommand command, CommandRuleCatalog catalog) {
            long elapsed = 0;
            details.put("resolver", RESOLVER);
            details.put("commandType", command.type());
            details.put("actorId", snapshot.player().toString());
            switch (command) {
                case TypedCommand.Move move -> {
                    if (snapshot.location() == null) {
                        throw new IllegalStateException("validated movement has no origin");
                    }
                    placements.add(new ResolvedCommit.Placement(snapshot.player(), snapshot.playerRowVersion(),
                            move.destinationId()));
                    var location = details.putObject("location");
                    location.put("from", snapshot.location());
                    location.put("to", move.destinationId());
                    event("ACTOR_MOVED", EventVisibility.PUBLIC, payload()
                            .put("actorId", snapshot.player().toString())
                            .put("from", snapshot.location()).put("to", move.destinationId()));
                }
                case TypedCommand.Wait wait -> {
                    elapsed = wait.seconds();
                    event("TIME_PASSED", EventVisibility.PUBLIC, payload().put("seconds", wait.seconds()));
                }
                case TypedCommand.Speak speech -> {
                    details.put("recipientActorId", speech.recipientActorId().toString());
                    event("SPEECH_DELIVERED", EventVisibility.PUBLIC, payload()
                            .put("speakerActorId", snapshot.player().toString())
                            .put("recipientActorId", speech.recipientActorId().toString())
                            .put("text", speech.text()));
                }
                case TypedCommand.UseAbility use -> ability(use, Objects.requireNonNull(catalog, "catalog"));
            }
            details.put("elapsedSeconds", elapsed);
            details.put("turnAdvanced", true);
            details.putNull("pendingDecision");
            var resources = changes();
            var scopes = new TreeSet<String>();
            resources.forEach(change -> scopes.add(scope(owner(change.resourceId()))));
            placements.forEach(placement -> scopes.add(scope(placement.actorId())));
            return new Resolution(new ResolvedCommit.WorldChange(true, elapsed, CampaignMode.EXPLORATION, null),
                    resources, List.copyOf(placements), List.copyOf(events), JSON.writeValueAsString(details),
                    List.copyOf(scopes));
        }

        /**
         * Ability use: announce, pay every nonzero cost exactly, then run top-level {@code pre}
         * nodes before {@code post} nodes, each group in authored order.
         */
        private void ability(TypedCommand.UseAbility use, CommandRuleCatalog catalog) {
            var ability = catalog.abilities().get(use.abilityId());
            if (ability == null) {
                throw new IllegalStateException("validated ability is missing from the pinned catalog");
            }
            details.put("abilityId", use.abilityId());
            var targets = details.putArray("targetActorIds");
            use.targetActorIds().forEach(target -> targets.add(target.toString()));
            var announced = payload().put("actorId", snapshot.player().toString()).put("abilityId", use.abilityId());
            announced.set("targetActorIds", targets.deepCopy());
            event("ABILITY_USED", EventVisibility.PUBLIC, announced);
            for (var cost : new TreeMap<>(ability.costs()).entrySet()) {
                if (cost.getValue() == 0) {
                    continue;
                }
                // CommandRules proved affordability under the same lock; a mismatch is a defect.
                Pool pool = pool(snapshot.player(), cost.getKey());
                if (pool == null) {
                    throw new IllegalStateException("validated cost has no pool");
                }
                long before = value(snapshot.player(), cost.getKey());
                long after = before - cost.getValue();
                if (after < pool.minimum()) {
                    throw new IllegalStateException("validated cost is not payable");
                }
                values.get(snapshot.player()).put(cost.getKey(), after);
                event("RESOURCE_SPENT", EventVisibility.PUBLIC, payload()
                        .put("actorId", snapshot.player().toString()).put("resourceKey", cost.getKey())
                        .put("amount", cost.getValue()).put("before", before).put("after", after));
            }
            var ordered = new ArrayList<Integer>();
            for (String timing : List.of("pre", "post")) {
                for (int index = 0; index < ability.effects().size(); index++) {
                    if (ability.effects().get(index).timing().equals(timing)) {
                        ordered.add(index);
                    }
                }
            }
            for (int index : ordered) {
                effect(ability.effects().get(index), "effects/" + index, ability, use);
            }
            details.set("calculations", calculations);
        }

        /** Execute one supported node; the rule gate already refused unsupported programs. */
        private void effect(EffectProgram.Effect effect, String path, CommandRuleCatalog.Ability ability,
                TypedCommand.UseAbility use) {
            switch (effect) {
                case EffectProgram.Conditional branch -> {
                    var ordinals = new ArrayList<Integer>();
                    boolean holds = bool(branch.when(), ordinals);
                    calculation(path + "/when", "condition", JSON.getNodeFactory().booleanNode(holds), ordinals);
                    var chosen = holds ? branch.then() : branch.otherwise();
                    String label = holds ? "/then/" : "/else/";
                    for (int index = 0; index < chosen.size(); index++) {
                        effect(chosen.get(index), path + label + index, ability, use);
                    }
                }
                case EffectProgram.ModifyResource change -> modify(change, path, ability, use);
                case EffectProgram.Unsupported unsupported ->
                        throw new IllegalStateException("unsupported effect reached local resolution");
            }
        }

        /**
         * Evaluate the amount once, then apply it to every affected actor in order. A value
         * outside the pool bounds is clamped (healing never exceeds the maximum, loss never
         * passes the minimum); an actor without a pool of that key in the ability's vocabulary
         * is recorded as explicitly unaffected instead of receiving an invented pool.
         */
        private void modify(EffectProgram.ModifyResource change, String path, CommandRuleCatalog.Ability ability,
                TypedCommand.UseAbility use) {
            var ordinals = new ArrayList<Integer>();
            long amount = number(change.amount(), ordinals);
            calculation(path + "/amount", "amount", JSON.getNodeFactory().numberNode(amount), ordinals);
            // "self" is the actor; "target"/"targets" are the explicit targets, or the actor itself
            // for a self-policy ability that accepts none.
            List<UUID> affected = change.target().equals("self") || use.targetActorIds().isEmpty()
                    ? List.of(snapshot.player()) : use.targetActorIds();
            var payload = payload().put("actorId", snapshot.player().toString()).put("path", path)
                    .put("resourceKey", change.resource()).put("amount", amount);
            payload.set("drawOrdinals", ordinals(ordinals));
            var outcomes = payload.putArray("outcomes");
            boolean onlyPlayer = true;
            for (UUID actor : affected) {
                onlyPlayer &= actor.equals(snapshot.player());
                var outcome = outcomes.addObject().put("actorId", actor.toString());
                Pool pool = ability.pack().equals(snapshot.vocabularies().get(actor))
                        ? pool(actor, change.resource()) : null;
                if (pool == null) {
                    outcome.put("outcome", "NO_RESOURCE");
                    continue;
                }
                long before = value(actor, change.resource());
                long after = Math.max(pool.minimum(), Math.min(pool.maximum(), Math.addExact(before, amount)));
                values.get(actor).put(change.resource(), after);
                outcome.put("outcome", after - before == amount ? "APPLIED" : "CLAMPED");
                outcome.put("before", before).put("after", after).put("applied", after - before);
            }
            // Values of other actors' pools are hidden information; keep them engine-internal.
            event("EFFECT_RESOLVED", onlyPlayer ? EventVisibility.PUBLIC : EventVisibility.INTERNAL, payload);
        }

        /** Evaluate a numeric expression with exact long arithmetic, recording consumed draw ordinals. */
        private long number(EffectProgram.Formula formula, List<Integer> ordinals) {
            return switch (formula) {
                case EffectProgram.Constant constant -> constant.value();
                case EffectProgram.Attribute attribute -> {
                    Long value = snapshot.attributes().get(attribute.id());
                    if (value == null) {
                        throw new IllegalStateException("expression reads an attribute the save does not hold");
                    }
                    yield value;
                }
                case EffectProgram.Resource resource -> {
                    if (pool(snapshot.player(), resource.id()) == null) {
                        throw new IllegalStateException("expression reads a resource the save does not hold");
                    }
                    yield value(snapshot.player(), resource.id());
                }
                case EffectProgram.Skill skill -> snapshot.skills().getOrDefault(skill.id(), 0L);
                case EffectProgram.Dice dice -> {
                    long sum = 0;
                    for (int die = 0; die < dice.count(); die++) {
                        int ordinal = nextOrdinal++;
                        if (ordinal >= DeterministicDraw.MAX_DRAWS) {
                            throw new IllegalStateException("draw budget exceeded");
                        }
                        int value = draws.value(ordinal, dice.sides());
                        if (value < 1 || value > dice.sides()) {
                            throw new IllegalStateException("draw outside its bound");
                        }
                        ordinals.add(ordinal);
                        sum += value;
                    }
                    yield sum;
                }
                case EffectProgram.Operation operation -> operate(operation, ordinals);
                case EffectProgram.Flag ignored -> throw new IllegalStateException("boolean used as a number");
            };
        }

        /** Apply a numeric operator; the compiler proved bounds and nonzero divisors, exact math rechecks overflow. */
        private long operate(EffectProgram.Operation operation, List<Integer> ordinals) {
            var operands = operation.operands();
            switch (operation.operator()) {
                case "add", "mul", "min", "max" -> {
                    long result = number(operands.getFirst(), ordinals);
                    for (int index = 1; index < operands.size(); index++) {
                        long next = number(operands.get(index), ordinals);
                        result = switch (operation.operator()) {
                            case "add" -> Math.addExact(result, next);
                            case "mul" -> Math.multiplyExact(result, next);
                            case "min" -> Math.min(result, next);
                            default -> Math.max(result, next);
                        };
                    }
                    return result;
                }
                case "sub" -> {
                    return Math.subtractExact(number(operands.get(0), ordinals), number(operands.get(1), ordinals));
                }
                case "div" -> {
                    long dividend = number(operands.get(0), ordinals);
                    long divisor = number(operands.get(1), ordinals);
                    if (divisor == 0) {
                        throw new IllegalStateException("division by zero");
                    }
                    // Java long division truncates toward zero, matching the compiler's interval analysis.
                    return dividend / divisor;
                }
                case "clamp" -> {
                    long subject = number(operands.get(0), ordinals);
                    long low = number(operands.get(1), ordinals);
                    long high = number(operands.get(2), ordinals);
                    return Math.min(Math.max(subject, low), high);
                }
                default -> throw new IllegalStateException("boolean operator used as a number");
            }
        }

        /**
         * Evaluate a boolean expression. {@code all}/{@code any} stop at the first deciding
         * operand, so dice in skipped operands reserve no draws; the order is fixed, so this
         * remains deterministic.
         */
        private boolean bool(EffectProgram.Formula formula, List<Integer> ordinals) {
            if (formula instanceof EffectProgram.Flag flag) {
                return flag.value();
            }
            if (!(formula instanceof EffectProgram.Operation operation)) {
                throw new IllegalStateException("number used as a boolean");
            }
            var operands = operation.operands();
            return switch (operation.operator()) {
                case "eq", "ne", "lt", "le", "gt", "ge" -> {
                    long left = number(operands.get(0), ordinals);
                    long right = number(operands.get(1), ordinals);
                    yield switch (operation.operator()) {
                        case "eq" -> left == right;
                        case "ne" -> left != right;
                        case "lt" -> left < right;
                        case "le" -> left <= right;
                        case "gt" -> left > right;
                        default -> left >= right;
                    };
                }
                case "all" -> {
                    for (var operand : operands) {
                        if (!bool(operand, ordinals)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case "any" -> {
                    for (var operand : operands) {
                        if (bool(operand, ordinals)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case "not" -> !bool(operands.getFirst(), ordinals);
                default -> throw new IllegalStateException("numeric operator used as a boolean");
            };
        }

        /** Append one calculation record: its path, kind, result and consumed draw ordinals. */
        private void calculation(String path, String kind, JsonNode value, List<Integer> ordinals) {
            var record = calculations.addObject().put("path", path).put("kind", kind);
            record.set("value", value);
            record.set("drawOrdinals", ordinals(ordinals));
        }

        /** Net delta per changed pool in pool-identity order; unchanged pools are not written. */
        private List<ResolvedCommit.ResourceChange> changes() {
            var changed = new ArrayList<ResolvedCommit.ResourceChange>();
            var summary = JSON.createArrayNode();
            var pools = new ArrayList<Map.Entry<UUID, Pool>>();
            snapshot.pools().forEach((actor, byKey) -> byKey.values().forEach(pool -> pools.add(Map.entry(actor, pool))));
            pools.sort(Comparator.comparing(entry -> entry.getValue().id().toString()));
            for (var entry : pools) {
                Pool pool = entry.getValue();
                String key = keyOf(entry.getKey(), pool);
                long after = values.get(entry.getKey()).get(key);
                if (after != pool.current()) {
                    changed.add(new ResolvedCommit.ResourceChange(pool.id(), pool.rowVersion(), after - pool.current()));
                    summary.addObject().put("actorId", entry.getKey().toString()).put("resourceKey", key)
                            .put("before", pool.current()).put("after", after);
                }
            }
            details.set("changes", summary);
            return List.copyOf(changed);
        }

        /** Unqualified key of a pool within its actor's map. */
        private String keyOf(UUID actor, Pool pool) {
            for (var entry : snapshot.pools().get(actor).entrySet()) {
                if (entry.getValue().id().equals(pool.id())) {
                    return entry.getKey();
                }
            }
            throw new IllegalStateException("pool is not indexed by its actor");
        }

        /** Owner of a pool identity in the snapshot. */
        private UUID owner(UUID pool) {
            for (var entry : snapshot.pools().entrySet()) {
                for (Pool candidate : entry.getValue().values()) {
                    if (candidate.id().equals(pool)) {
                        return entry.getKey();
                    }
                }
            }
            throw new IllegalStateException("changed pool has no owner");
        }

        /** Snapshot pool of an actor by key, or null. */
        private Pool pool(UUID actor, String key) {
            var byKey = snapshot.pools().get(actor);
            return byKey == null ? null : byKey.get(key);
        }

        /** Running value of an existing pool. */
        private long value(UUID actor, String key) {
            return values.get(actor).get(key);
        }

        /** Append one event attributed to the acting player; commit assigns sequence and provenance. */
        private void event(String type, EventVisibility visibility, ObjectNode payload) {
            events.add(new ResolvedCommit.Event(snapshot.player(), null, visibility,
                    new CampaignEvent.Body(type, EVENT_VERSION, EVENT_VERSION, JSON.writeValueAsString(payload))));
        }

        /** Fresh event payload object. */
        private static ObjectNode payload() {
            return JSON.createObjectNode();
        }

        /** JSON array of draw ordinals. */
        private static ArrayNode ordinals(List<Integer> ordinals) {
            var array = JSON.createArrayNode();
            ordinals.forEach(array::add);
            return array;
        }

        /**
         * Query scope of one actor's state (location and resources). Plans that read an actor's
         * placement or pools record this scope, so a change invalidates them at commit
         * (docs/28 section 7).
         */
        private static String scope(UUID actor) {
            return "actor:" + actor;
        }
    }
}
