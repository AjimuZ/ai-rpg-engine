package org.rpgengine.commands;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.inventory.InventoryModel;
import org.rpgengine.inventory.InventoryWriter;
import org.rpgengine.inventory.ItemPlan;
import org.rpgengine.quests.QuestChanges;
import org.rpgengine.quests.QuestPlan;
import org.rpgengine.quests.QuestRules;
import org.rpgengine.quests.QuestStore;
import org.rpgengine.rules.DeterministicDraw;
import org.rpgengine.rules.RestRules;
import org.rpgengine.rules.WorldClock;
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
 * <p>Items (P10.2): item commands edit an {@link InventoryModel} of the participants' stacks and
 * return the resulting {@link ItemPlan}; ability and item-use effects may create items, each with a
 * ledger source key {@code <operation>#<effect path>}. New instance identities derive from the
 * operation, so the same operation always proposes the same rows. Every item command takes no world
 * time and, like the other intentions, advances the game turn once.
 *
 * <p>Quests (P10.3): after the command's own effects, the pure {@link QuestPlan} runs over the facts the
 * effects produce (visited locations, the player's possessions in the working {@link InventoryModel}, quest
 * statuses and satisfied objectives) and its ordered steps become events, quest writes, reward settlements
 * and reward-sourced item creations in this same resolution. A quest ending grants its rewards through the
 * inventory model, so later predicates in the same commit already see them. The three quest commands are
 * only the player's request that starts the plan.
 *
 * <p>Determinism: the same intention, snapshot and draw values always give byte-identical
 * output. Draw ordinals are assigned in evaluation order starting at zero, one per die, so
 * a retry of the same operation replays the same reserved values. The calculation never
 * invents a value: an attribute or resource that an expression reads but the save lacks is
 * a resolver failure ({@link IllegalStateException}), never a silent zero.
 *
 * <p>Time (P10.4): every action advances the game turn once. World time advances only by the
 * duration the rules resolve, never by a query: {@code WAIT} adds exactly the requested seconds,
 * {@code MOVE} adds the authored duration of the link it follows, {@code REST} adds its policy's
 * duration (half of it when the recorded interruption draw interrupts it), and speech, abilities
 * and item commands take no world time because they declare no duration. The rule gate has proved
 * the step fits the bounded {@link WorldClock}; the step is at most one in-world day. Every action
 * resolves to "no pending decision" (null), which also clears the creation starting marker: the
 * player may submit any registered intention next.
 *
 * <p>Rest (P10.4): the pinned policy restores its resources up to each pool's maximum and, when not
 * interrupted, ends the listed conditions the player has. An interruption is one recorded percentile
 * draw; it halves the time and the restoration and removes no condition ({@link RestRules}).
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
     * @param operationId committing operation; seeds new item identities and ledger source keys
     * @param stacks every item stack held by a participant
     * @param stored stored quest rows with satisfied objectives, and the player's recorded visits (P10.3)
     * @param worldTimeSeconds the committed world clock before this command (P10.4)
     * @param conditions canonical IDs of the conditions currently active on the player (P10.4)
     */
    record Snapshot(UUID player, String location, long playerRowVersion, Map<String, Long> attributes,
            Map<String, Long> skills, Map<UUID, Map<String, Pool>> pools, Map<UUID, String> vocabularies,
            UUID operationId, List<InventoryModel.Stack> stacks, QuestStore.Stored stored, long worldTimeSeconds,
            Set<String> conditions) {
        /** Snapshot without clock and condition facts, the shape used before P10.4. */
        Snapshot(UUID player, String location, long playerRowVersion, Map<String, Long> attributes,
                Map<String, Long> skills, Map<UUID, Map<String, Pool>> pools, Map<UUID, String> vocabularies,
                UUID operationId, List<InventoryModel.Stack> stacks, QuestStore.Stored stored) {
            this(player, location, playerRowVersion, attributes, skills, pools, vocabularies, operationId, stacks,
                    stored, 0, Set.of());
        }

        /** Snapshot without quest facts, the shape used before P10.3. */
        Snapshot(UUID player, String location, long playerRowVersion, Map<String, Long> attributes,
                Map<String, Long> skills, Map<UUID, Map<String, Pool>> pools, Map<UUID, String> vocabularies,
                UUID operationId, List<InventoryModel.Stack> stacks) {
            this(player, location, playerRowVersion, attributes, skills, pools, vocabularies, operationId, stacks,
                    QuestStore.Stored.NONE);
        }

        /** Snapshot without item facts, the shape used before P10.2. */
        Snapshot(UUID player, String location, long playerRowVersion, Map<String, Long> attributes,
                Map<String, Long> skills, Map<UUID, Map<String, Pool>> pools, Map<UUID, String> vocabularies) {
            this(player, location, playerRowVersion, attributes, skills, pools, vocabularies, new UUID(0, 0), List.of());
        }

        /** Freeze the maps; nested pool maps are copied too. */
        Snapshot {
            Objects.requireNonNull(player, "player");
            attributes = Map.copyOf(attributes);
            skills = Map.copyOf(skills);
            var frozen = new LinkedHashMap<UUID, Map<String, Pool>>();
            pools.forEach((actor, byKey) -> frozen.put(actor, Map.copyOf(byKey)));
            pools = Map.copyOf(frozen);
            vocabularies = Map.copyOf(vocabularies);
            Objects.requireNonNull(operationId, "operationId");
            stacks = List.copyOf(stacks);
            Objects.requireNonNull(stored, "stored");
            if (worldTimeSeconds < 0 || worldTimeSeconds > WorldClock.END_SECONDS) {
                throw new IllegalArgumentException("world time outside the bounded clock");
            }
            conditions = Set.copyOf(conditions);
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
     * @param items item mutations, created and consumed units and equipment changes
     * @param rewards one-time reward settlements of quests that ended in this resolution (P10.3)
     * @param quests quest status, objective and visit writes (P10.3)
     * @param conditions conditions a rest ends (P10.4)
     */
    record Resolution(ResolvedCommit.WorldChange world, List<ResolvedCommit.ResourceChange> resources,
            List<ResolvedCommit.Placement> placements, List<ResolvedCommit.Event> events, String receiptDetails,
            List<String> queryScopes, ItemPlan items, List<ResolvedCommit.Reward> rewards, QuestChanges quests,
            List<ResolvedCommit.ConditionRemoval> conditions) {
        /** Resolution without condition removals, the shape used before P10.4. */
        Resolution(ResolvedCommit.WorldChange world, List<ResolvedCommit.ResourceChange> resources,
                List<ResolvedCommit.Placement> placements, List<ResolvedCommit.Event> events, String receiptDetails,
                List<String> queryScopes, ItemPlan items, List<ResolvedCommit.Reward> rewards, QuestChanges quests) {
            this(world, resources, placements, events, receiptDetails, queryScopes, items, rewards, quests, List.of());
        }

        /** Resolution without quest effects, the shape used before P10.3. */
        Resolution(ResolvedCommit.WorldChange world, List<ResolvedCommit.ResourceChange> resources,
                List<ResolvedCommit.Placement> placements, List<ResolvedCommit.Event> events, String receiptDetails,
                List<String> queryScopes, ItemPlan items) {
            this(world, resources, placements, events, receiptDetails, queryScopes, items, List.of(),
                    QuestChanges.NONE);
        }
    }

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
        private final ArrayNode itemLines = JSON.createArrayNode();
        private final List<ItemPlan.Creation> creations = new ArrayList<>();
        private final List<ItemPlan.Consumption> consumptions = new ArrayList<>();
        private final List<ItemPlan.EquipmentChange> equipment = new ArrayList<>();
        private final List<ResolvedCommit.Reward> rewards = new ArrayList<>();
        private final ArrayNode questLines = JSON.createArrayNode();
        private QuestChanges questChanges = QuestChanges.NONE;
        private final List<ResolvedCommit.ConditionRemoval> removals = new ArrayList<>();
        private long elapsed;
        private final InventoryModel inventory;
        private int nextOrdinal;
        private int nextInstance;

        /** Copy current pool values and item stacks into the running state. */
        Run(Snapshot snapshot, Draws draws) {
            this.snapshot = snapshot;
            this.draws = draws;
            // Deterministic identities: a retried resolution of this operation proposes the same rows.
            this.inventory = new InventoryModel(snapshot.stacks(), () -> UUID.nameUUIDFromBytes(
                    (snapshot.operationId() + "/instance/" + nextInstance++).getBytes(StandardCharsets.UTF_8)));
            snapshot.pools().forEach((actor, byKey) -> {
                var current = new TreeMap<String, Long>();
                byKey.forEach((key, pool) -> current.put(key, pool.current()));
                values.put(actor, current);
            });
        }

        /** Dispatch on the closed intention vocabulary, then assemble the effect set. */
        Resolution resolve(TypedCommand command, CommandRuleCatalog catalog) {
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
                    // The authored duration of the link followed; the rule gate proved the link exists.
                    var origin = Objects.requireNonNull(catalog, "catalog").locations().get(snapshot.location());
                    if (origin == null) {
                        throw new IllegalStateException("validated movement origin is missing from the pinned catalog");
                    }
                    elapsed = origin.travelSeconds(move.destinationId());
                    var location = details.putObject("location");
                    location.put("from", snapshot.location());
                    location.put("to", move.destinationId());
                    location.put("seconds", elapsed);
                    event("ACTOR_MOVED", EventVisibility.PUBLIC, payload()
                            .put("actorId", snapshot.player().toString())
                            .put("from", snapshot.location()).put("to", move.destinationId())
                            .put("seconds", elapsed));
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
                case TypedCommand.Rest rest -> rest(rest, Objects.requireNonNull(catalog, "catalog"));
                case TypedCommand.UseAbility use -> ability(use, Objects.requireNonNull(catalog, "catalog"));
                case TypedCommand.UseItem use -> useItem(use, Objects.requireNonNull(catalog, "catalog"));
                case TypedCommand.EquipItem equip -> wear(equip.itemInstanceId(), true,
                        Objects.requireNonNull(catalog, "catalog"));
                case TypedCommand.UnequipItem unequip -> wear(unequip.itemInstanceId(), false,
                        Objects.requireNonNull(catalog, "catalog"));
                case TypedCommand.TransferItem gift -> give(gift);
                case TypedCommand.TradeItems trade -> trade(trade, Objects.requireNonNull(catalog, "catalog"));
                // The quest commands only start the quest engine below; its step is the command's event.
                case TypedCommand.AcceptQuest accept -> details.put("questId", accept.questId());
                case TypedCommand.SuspendQuest suspend -> details.put("questId", suspend.questId());
                case TypedCommand.ResumeQuest resume -> details.put("questId", resume.questId());
            }
            quests(command, catalog);
            // Defence in depth: the rule gate bounded the step; the clock itself is bounded again at commit.
            elapsed = WorldClock.advance(snapshot.worldTimeSeconds(), elapsed) - snapshot.worldTimeSeconds();
            details.put("elapsedSeconds", elapsed);
            details.put("turnAdvanced", true);
            details.putNull("pendingDecision");
            var resources = changes();
            var items = new ItemPlan(inventory.changes(), creations, consumptions, equipment);
            if (!itemLines.isEmpty()) {
                details.set("items", itemLines);
            }
            if (!questLines.isEmpty()) {
                details.set("quests", questLines);
            }
            var scopes = new TreeSet<String>();
            resources.forEach(change -> scopes.add(scope(owner(change.resourceId()))));
            placements.forEach(placement -> scopes.add(scope(placement.actorId())));
            removals.forEach(removal -> scopes.add(scope(removal.actorId())));
            scopes.addAll(InventoryWriter.scopes(items));
            return new Resolution(new ResolvedCommit.WorldChange(true, elapsed, CampaignMode.EXPLORATION, null),
                    resources, List.copyOf(placements), List.copyOf(events), JSON.writeValueAsString(details),
                    List.copyOf(scopes), items, List.copyOf(rewards), questChanges, List.copyOf(removals));
        }

        /**
         * Run the quest engine over the facts this command's effects produce, then record its steps.
         * Skipped when the command carries no pinned catalog (waiting and speech change no quest fact) or
         * the content has no quests. The player's current location, the destination of a move and the
         * recorded visits all count as visited; a move also records its origin, so a place the player
         * started in is not forgotten once they leave it.
         */
        private void quests(TypedCommand command, CommandRuleCatalog catalog) {
            if (catalog == null || catalog.quests().quests().isEmpty()) {
                return;
            }
            var stored = snapshot.stored();
            var visited = new LinkedHashSet<String>(stored.visited());
            var newVisits = new ArrayList<QuestChanges.VisitWrite>();
            if (snapshot.location() != null) {
                visited.add(snapshot.location());
            }
            if (command instanceof TypedCommand.Move move) {
                for (String place : new String[] {snapshot.location(), move.destinationId()}) {
                    if (place != null && !stored.visited().contains(place)) {
                        newVisits.add(new QuestChanges.VisitWrite(snapshot.player(), place));
                    }
                }
                visited.add(move.destinationId());
            }
            var world = new QuestPlan.World() {
                @Override public boolean visited(String location) { return visited.contains(location); }

                @Override public long possessed(String item) { return inventory.total(snapshot.player(), item); }

                // The clock as it reads once this command's own duration has passed.
                @Override public long worldTime() { return snapshot.worldTimeSeconds() + elapsed; }

                @Override
                public void grant(String item, int quantity) {
                    var rule = catalog.characters().items().get(item);
                    if (rule == null) {
                        throw new IllegalStateException("rewarded item is missing from the pinned catalog");
                    }
                    inventory.deposit(snapshot.player(), item, rule.maxStack(), quantity);
                }
            };
            QuestPlan.Request request = switch (command) {
                case TypedCommand.AcceptQuest accept -> new QuestPlan.Request(QuestPlan.Action.ACCEPT, accept.questId());
                case TypedCommand.SuspendQuest suspend -> new QuestPlan.Request(QuestPlan.Action.SUSPEND, suspend.questId());
                case TypedCommand.ResumeQuest resume -> new QuestPlan.Request(QuestPlan.Action.RESUME, resume.questId());
                default -> null;
            };
            var result = QuestPlan.evaluate(catalog.quests(), stored.states(), world, request,
                    ResolvedCommit.MAX_EVENTS - events.size());
            var indexes = new ArrayList<Integer>();
            for (QuestPlan.Step step : result.steps()) {
                indexes.add(events.size());
                announce(step);
            }
            questChanges = QuestChanges.of(result, indexes, newVisits);
        }

        /**
         * Emit the event, receipt line and (for an ending) the reward settlement and reward-sourced item
         * creations of one quest step. Steps of a secret quest stay engine-internal until it ends.
         */
        private void announce(QuestPlan.Step step) {
            String player = snapshot.player().toString();
            var visibility = step.secret() && !(step instanceof QuestPlan.Ended) ? EventVisibility.INTERNAL
                    : EventVisibility.PUBLIC;
            var payload = payload().put("actorId", player).put("questId", step.questId());
            String kind;
            switch (step) {
                case QuestPlan.Discovered ignored -> {
                    kind = "DISCOVERED";
                    event("QUEST_AVAILABLE", visibility, payload);
                }
                case QuestPlan.Accepted accepted -> {
                    kind = "ACCEPTED";
                    payload.put("automatic", accepted.automatic());
                    event("QUEST_ACCEPTED", visibility, payload);
                }
                case QuestPlan.Suspended ignored -> {
                    kind = "SUSPENDED";
                    event("QUEST_SUSPENDED", visibility, payload);
                }
                case QuestPlan.Resumed ignored -> {
                    kind = "RESUMED";
                    event("QUEST_RESUMED", visibility, payload);
                }
                case QuestPlan.ObjectiveSatisfied satisfied -> {
                    kind = "OBJECTIVE";
                    payload.put("objectiveId", satisfied.objectiveId());
                    event("QUEST_OBJECTIVE_SATISFIED", visibility, payload);
                }
                case QuestPlan.Ended ended -> {
                    var outcome = ended.outcome();
                    boolean completed = outcome.kind().ends() == org.rpgengine.quests.QuestStatus.COMPLETED;
                    kind = completed ? "COMPLETED" : "FAILED";
                    String source = "quest:" + step.questId() + ":outcome";
                    payload.put("outcomeId", outcome.id()).put("outcomeKind", outcome.kind().wire());
                    if (!outcome.rewards().isEmpty()) {
                        payload.put("sourceKey", source);
                    }
                    event(completed ? "QUEST_COMPLETED" : "QUEST_FAILED", visibility, payload);
                    settle(source, ended);
                }
            }
            if (visibility == EventVisibility.PUBLIC) {
                var line = questLines.addObject().put("kind", kind).put("questId", step.questId());
                if (step instanceof QuestPlan.ObjectiveSatisfied satisfied) {
                    line.put("objectiveId", satisfied.objectiveId());
                }
                if (step instanceof QuestPlan.Ended ended) {
                    line.put("outcomeId", ended.outcome().id());
                }
            }
        }

        /**
         * Settle an ending's rewards once: one reward settlement for the player under the quest's source
         * key, and for each rewarded definition one ledger creation citing that settlement and one
         * announcing event. The units themselves were already deposited in the inventory model.
         */
        private void settle(String source, QuestPlan.Ended ended) {
            var granted = ended.outcome().rewards();
            if (granted.isEmpty()) {
                return;
            }
            var items = JSON.createArrayNode();
            for (QuestRules.Reward reward : granted) {
                items.addObject().put("definitionId", reward.item()).put("quantity", reward.quantity());
            }
            var body = payload().put("questId", ended.questId()).put("outcomeId", ended.outcome().id());
            body.set("items", items);
            rewards.add(new ResolvedCommit.Reward(source, snapshot.player().toString(),
                    JSON.writeValueAsString(body)));
            for (QuestRules.Reward reward : granted) {
                creations.add(new ItemPlan.Creation(snapshot.player(), reward.item(), reward.quantity(),
                        ItemPlan.SourceKind.REWARD, source, events.size()));
                event("ITEM_CREATED", EventVisibility.PUBLIC, payload().put("actorId", snapshot.player().toString())
                        .put("recipientActorId", snapshot.player().toString()).put("definitionId", reward.item())
                        .put("quantity", reward.quantity()).put("sourceKey", source));
                line("CREATED", reward.item(), null, reward.quantity());
            }
        }

        /**
         * Rest under a pinned policy. One recorded percentile draw decides interruption when the policy can
         * be interrupted; then each restored pool rises by the policy amount (halved when interrupted) up to
         * its maximum, and an uninterrupted rest ends every listed condition the player has. One event
         * announces the rest, then one event per changed pool and per ended condition. The time the rest takes
         * becomes the command's elapsed world time.
         */
        private void rest(TypedCommand.Rest command, CommandRuleCatalog catalog) {
            var policy = catalog.rests().get(command.restId());
            if (policy == null) {
                throw new IllegalStateException("validated rest policy is missing from the pinned catalog");
            }
            boolean interrupted = false;
            var rest = details.putObject("rest");
            rest.put("restId", command.restId());
            var announced = payload().put("actorId", snapshot.player().toString()).put("restId", command.restId());
            if (policy.interruptible()) {
                int ordinal = nextOrdinal++;
                int draw = draws.value(ordinal, RestRules.INTERRUPTION_SIDES);
                if (draw < 1 || draw > RestRules.INTERRUPTION_SIDES) {
                    throw new IllegalStateException("draw outside its bound");
                }
                interrupted = RestRules.interrupted(policy, draw);
                rest.put("drawOrdinal", ordinal);
                announced.put("drawOrdinal", ordinal);
            }
            elapsed = RestRules.elapsedSeconds(policy, interrupted);
            rest.put("interrupted", interrupted);
            rest.put("seconds", elapsed);
            announced.put("interrupted", interrupted).put("seconds", elapsed);
            event(interrupted ? "REST_INTERRUPTED" : "REST_COMPLETED", EventVisibility.PUBLIC, announced);
            for (var restores : policy.restores().entrySet()) {
                Pool pool = pool(snapshot.player(), restores.getKey());
                if (pool == null) {
                    continue;
                }
                long before = value(snapshot.player(), restores.getKey());
                long added = RestRules.restored(restores.getValue(), interrupted, pool.maximum() - before);
                if (added == 0) {
                    continue;
                }
                values.get(snapshot.player()).put(restores.getKey(), before + added);
                event("RESOURCE_RECOVERED", EventVisibility.PUBLIC, payload()
                        .put("actorId", snapshot.player().toString()).put("resourceKey", restores.getKey())
                        .put("amount", added).put("before", before).put("after", before + added));
            }
            if (!interrupted) {
                var ended = rest.putArray("conditionsEnded");
                for (String condition : policy.removes()) {
                    if (snapshot.conditions().contains(condition)) {
                        removals.add(new ResolvedCommit.ConditionRemoval(snapshot.player(), condition));
                        ended.add(condition);
                        event("CONDITION_REMOVED", EventVisibility.PUBLIC, payload()
                                .put("actorId", snapshot.player().toString()).put("conditionId", condition));
                    }
                }
            }
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
            runEffects(ability, use.targetActorIds(), catalog);
        }

        /**
         * Item use: announce, use up one unit when the contract consumes, then run the contract's
         * effect program exactly like an ability's. The rule gate proved the instance is held, unworn
         * when consumed, and that the item declares a runnable use contract.
         */
        private void useItem(TypedCommand.UseItem use, CommandRuleCatalog catalog) {
            var stack = inventory.find(use.itemInstanceId())
                    .orElseThrow(() -> new IllegalStateException("validated item instance is missing"));
            var rule = catalog.items().get(stack.definition());
            if (rule == null || rule.use() == null) {
                throw new IllegalStateException("validated item has no use contract");
            }
            details.put("itemInstanceId", use.itemInstanceId().toString());
            details.put("definitionId", stack.definition());
            var targets = details.putArray("targetActorIds");
            use.targetActorIds().forEach(target -> targets.add(target.toString()));
            var announced = payload().put("actorId", snapshot.player().toString())
                    .put("itemInstanceId", use.itemInstanceId().toString()).put("definitionId", stack.definition())
                    .put("consumed", rule.use().consumes() ? 1 : 0);
            announced.set("targetActorIds", targets.deepCopy());
            event("ITEM_USED", EventVisibility.PUBLIC, announced);
            if (rule.use().consumes()) {
                inventory.consume(use.itemInstanceId(), 1);
                consumptions.add(new ItemPlan.Consumption(stack.definition(), 1));
                line("CONSUMED", stack.definition(), use.itemInstanceId(), 1);
            }
            runEffects(rule.use().contract(), use.targetActorIds(), catalog);
        }

        /** Run top-level {@code pre} nodes before {@code post} nodes, each group in authored order. */
        private void runEffects(CommandRuleCatalog.Ability contract, List<UUID> targets, CommandRuleCatalog catalog) {
            var ordered = new ArrayList<Integer>();
            for (String timing : List.of("pre", "post")) {
                for (int index = 0; index < contract.effects().size(); index++) {
                    if (contract.effects().get(index).timing().equals(timing)) {
                        ordered.add(index);
                    }
                }
            }
            for (int index : ordered) {
                effect(contract.effects().get(index), "effects/" + index, contract, targets, catalog);
            }
            details.set("calculations", calculations);
        }

        /** Put on or take off one held instance; the rule gate proved the constraints, commit re-proves them. */
        private void wear(UUID instance, boolean equip, CommandRuleCatalog catalog) {
            var stack = inventory.find(instance)
                    .orElseThrow(() -> new IllegalStateException("validated item instance is missing"));
            var item = catalog.characters().items().get(stack.definition());
            if (item == null) {
                throw new IllegalStateException("validated item is missing from the pinned catalog");
            }
            equipment.add(equip ? new ItemPlan.Equip(snapshot.player(), instance)
                    : new ItemPlan.Unequip(snapshot.player(), instance));
            details.put("itemInstanceId", instance.toString());
            details.put("definitionId", stack.definition());
            event(equip ? "ITEM_EQUIPPED" : "ITEM_UNEQUIPPED", EventVisibility.PUBLIC, payload()
                    .put("actorId", snapshot.player().toString()).put("itemInstanceId", instance.toString())
                    .put("definitionId", stack.definition()).put("slot", item.gear().slot())
                    .put("hands", item.gear().hands()));
            line(equip ? "EQUIPPED" : "UNEQUIPPED", stack.definition(), instance, 1);
        }

        /** Gift units of an instance to the recipient. */
        private void give(TypedCommand.TransferItem gift) {
            var stack = move(gift.itemInstanceId(), gift.quantity(), snapshot.player(), gift.recipientActorId());
            details.put("recipientActorId", gift.recipientActorId().toString());
            event("ITEM_TRANSFERRED", EventVisibility.PUBLIC, payload().put("fromActorId", snapshot.player().toString())
                    .put("toActorId", gift.recipientActorId().toString()).put("itemInstanceId", stack.id().toString())
                    .put("definitionId", stack.definition()).put("quantity", gift.quantity()));
            line("GIVEN", stack.definition(), stack.id(), gift.quantity());
        }

        /**
         * Exchange both sides in one event: offered units move to the counterparty first, requested
         * units to the player second, so every merge sees the stacks in the same order on every run.
         */
        private void trade(TypedCommand.TradeItems trade, CommandRuleCatalog catalog) {
            UUID counterparty = trade.counterpartyActorId();
            details.put("counterpartyActorId", counterparty.toString());
            var offered = JSON.createArrayNode();
            var requested = JSON.createArrayNode();
            long given = 0;
            long received = 0;
            for (var line : trade.offered()) {
                var stack = move(line.itemInstanceId(), line.quantity(), snapshot.player(), counterparty);
                given = Math.addExact(given, worth(catalog, stack.definition(), line.quantity()));
                offered.addObject().put("itemInstanceId", stack.id().toString()).put("definitionId", stack.definition())
                        .put("quantity", line.quantity());
                line("GIVEN", stack.definition(), stack.id(), line.quantity());
            }
            for (var line : trade.requested()) {
                var stack = move(line.itemInstanceId(), line.quantity(), counterparty, snapshot.player());
                received = Math.addExact(received, worth(catalog, stack.definition(), line.quantity()));
                requested.addObject().put("itemInstanceId", stack.id().toString()).put("definitionId", stack.definition())
                        .put("quantity", line.quantity());
                line("RECEIVED", stack.definition(), stack.id(), line.quantity());
            }
            if (given < received) {
                throw new IllegalStateException("validated trade is unbalanced");
            }
            var body = payload().put("actorId", snapshot.player().toString())
                    .put("counterpartyActorId", counterparty.toString());
            body.set("offered", offered);
            body.set("requested", requested);
            body.put("givenValue", given).put("receivedValue", received);
            event("ITEM_TRADED", EventVisibility.PUBLIC, body);
        }

        /** Declared value of units of a definition; the rule gate refused unpriced items. */
        private static long worth(CommandRuleCatalog catalog, String definition, int quantity) {
            var rule = catalog.items().get(definition);
            if (rule == null || rule.value() == null) {
                throw new IllegalStateException("validated trade item has no value");
            }
            return Math.multiplyExact((long) rule.value(), quantity);
        }

        /** Move units between two actors in the model, returning the source stack as it was before the move. */
        private InventoryModel.Stack move(UUID instance, int quantity, UUID from, UUID to) {
            var stack = inventory.find(instance)
                    .orElseThrow(() -> new IllegalStateException("validated item instance is missing"));
            if (!stack.owner().equals(from)) {
                throw new IllegalStateException("validated item instance changed owner");
            }
            inventory.transfer(instance, quantity, to);
            return stack;
        }

        /**
         * Record one item line of the player's receipt. A created line names no instance: the units
         * may have merged into an existing stack, so no single instance identifies them.
         */
        private void line(String kind, String definition, UUID instance, int quantity) {
            var line = itemLines.addObject().put("kind", kind).put("definitionId", definition);
            if (instance != null) {
                line.put("itemInstanceId", instance.toString());
            }
            line.put("quantity", quantity);
        }

        /** Execute one supported node; the rule gate already refused unsupported programs. */
        private void effect(EffectProgram.Effect effect, String path, CommandRuleCatalog.Ability ability,
                List<UUID> targets, CommandRuleCatalog catalog) {
            switch (effect) {
                case EffectProgram.Conditional branch -> {
                    var ordinals = new ArrayList<Integer>();
                    boolean holds = bool(branch.when(), ordinals);
                    calculation(path + "/when", "condition", JSON.getNodeFactory().booleanNode(holds), ordinals);
                    var chosen = holds ? branch.then() : branch.otherwise();
                    String label = holds ? "/then/" : "/else/";
                    for (int index = 0; index < chosen.size(); index++) {
                        effect(chosen.get(index), path + label + index, ability, targets, catalog);
                    }
                }
                case EffectProgram.ModifyResource change -> modify(change, path, ability, targets);
                case EffectProgram.CreateItem create -> create(create, path, targets, catalog);
                case EffectProgram.Unsupported unsupported ->
                        throw new IllegalStateException("unsupported effect reached local resolution");
            }
        }

        /**
         * Create units for each affected actor. The units merge into the recipient's open stacks of the
         * item first, then open new stacks; each recipient gets one creation ledger entry citing this
         * operation and effect path as its source, and one announcing event. A recipient that is not
         * the player is announced as an engine-internal event, because another actor's inventory is
         * hidden information.
         */
        private void create(EffectProgram.CreateItem create, String path, List<UUID> targets,
                CommandRuleCatalog catalog) {
            var item = catalog.characters().items().get(create.item());
            if (item == null) {
                throw new IllegalStateException("created item is missing from the pinned catalog");
            }
            List<UUID> recipients = create.target().equals("self") || targets.isEmpty()
                    ? List.of(snapshot.player()) : targets;
            for (UUID recipient : recipients) {
                String source = snapshot.operationId() + "#" + path;
                creations.add(new ItemPlan.Creation(recipient, create.item(), create.quantity(),
                        ItemPlan.SourceKind.EFFECT, source, events.size()));
                inventory.deposit(recipient, create.item(), item.maxStack(), create.quantity());
                event("ITEM_CREATED", recipient.equals(snapshot.player()) ? EventVisibility.PUBLIC
                        : EventVisibility.INTERNAL, payload().put("actorId", snapshot.player().toString())
                        .put("recipientActorId", recipient.toString()).put("definitionId", create.item())
                        .put("quantity", create.quantity()).put("path", path).put("sourceKey", source));
                if (recipient.equals(snapshot.player())) {
                    line("CREATED", create.item(), null, create.quantity());
                }
            }
        }

        /**
         * Evaluate the amount once, then apply it to every affected actor in order. A value
         * outside the pool bounds is clamped (healing never exceeds the maximum, loss never
         * passes the minimum); an actor without a pool of that key in the ability's vocabulary
         * is recorded as explicitly unaffected instead of receiving an invented pool.
         */
        private void modify(EffectProgram.ModifyResource change, String path, CommandRuleCatalog.Ability ability,
                List<UUID> targets) {
            var ordinals = new ArrayList<Integer>();
            long amount = number(change.amount(), ordinals);
            calculation(path + "/amount", "amount", JSON.getNodeFactory().numberNode(amount), ordinals);
            // "self" is the actor; "target"/"targets" are the explicit targets, or the actor itself
            // for a self-policy ability that accepts none.
            List<UUID> affected = change.target().equals("self") || targets.isEmpty()
                    ? List.of(snapshot.player()) : targets;
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
