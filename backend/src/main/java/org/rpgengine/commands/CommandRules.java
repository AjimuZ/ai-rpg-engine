package org.rpgengine.commands;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.characters.CharacterRuleViolation;
import org.rpgengine.characters.CharacterService;
import org.rpgengine.inventory.ItemChange;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.rpgengine.knowledge.KnowledgeRefs;
import org.rpgengine.quests.QuestStatus;
import org.rpgengine.quests.QuestStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Current-state rule gate for registered intentions (P07.3), shared by admission and
 * canonical commit. It runs after {@link CommandSemantics} in the same transaction, while
 * the caller holds the campaign row lock that every canonical writer takes first, so the
 * facts read here cannot change before the caller's journal insert or effects commit.
 *
 * <p>Checked rules, each against committed state rather than a copied validation flag:
 * <ul>
 *   <li><b>Reachability and knowledge</b> — {@code MOVE} needs a current location, a different
 *       pinned destination that is disclosed or revealed to the player (P09.1) and an accessible
 *       authored link from here.</li>
 *   <li><b>Recipient knowledge and range</b> — {@code SPEAK} and ability targets must be other
 *       campaign actors that share the player's current location and are not concealed, or whose
 *       concealed origin has been revealed to the player (P09.1).</li>
 *   <li><b>Ownership and prerequisites</b> — {@code USE_ABILITY} needs a pinned ability the player
 *       has learned, its prerequisite abilities and skill ranks.</li>
 *   <li><b>Target policy and resources</b> — target count must fit the ability's policy and every
 *       declared nonzero cost must be affordable above the minimum of the acting player's own
 *       resource from the same pack vocabulary as the ability.</li>
 *   <li><b>Executable effects</b> — the ability's effect program must be one the local resolver
 *       executes, read actor values only in the player's own vocabulary and fit the per-operation
 *       durable draw budget (P07.4).</li>
 *   <li><b>Items (P10.2)</b> — {@code USE_ITEM} needs an instance the player holds whose pinned
 *       definition has a use contract (targets and effects are checked like an ability's, and a
 *       consuming use needs the instance unworn); {@code EQUIP_ITEM}/{@code UNEQUIP_ITEM} apply the
 *       P10.1 equipment constraints; {@code TRANSFER_ITEM} gives units of an unworn instance to a
 *       perceivable actor; {@code TRADE_ITEMS} needs a perceivable counterparty, unworn instances
 *       on both sides, every item priced and a counterparty that receives at least the value it gives.</li>
 *   <li><b>Quests (P10.3)</b> — {@code ACCEPT_QUEST}, {@code SUSPEND_QUEST} and {@code RESUME_QUEST} name a
 *       pinned, non-secret quest whose stored status (or initial status, when it has no row) is
 *       {@code available}, {@code active} and {@code suspended} respectively. An unknown, undiscovered,
 *       hidden or ended quest, and any other status, share one category ({@code QUEST_UNAVAILABLE}).</li>
 *   <li><b>Causal attribution</b> — at commit, resolved effects may only touch the participants
 *       this intention lawfully involves ({@link #requireAttributed}).</li>
 * </ul>
 * {@code WAIT} has no additional rule here; its legal duration belongs to the world clock
 * (P10.4). {@link LocalActionResolver} calculates the effects of an intention that passed here.
 * A reveal is a typed knowledge record ({@link KnowledgeLedger#revealedTo}): a current TRUE
 * {@code EXISTS} claim about the location or actor that is common knowledge or that the player
 * KNOWS or BELIEVES. Free-text {@code campaign_known_fact} rows, rumors and suspicions never reveal.
 * {@link #perceived} reuses the same perception rule for the observation that follows a commit.
 * Every rule rejection is a sanitized {@link OperationAdmissionFailure}; an unattributable
 * resolved effect is a trusted-resolver defect and raises {@link IllegalStateException}.
 * Both escape the caller's transaction and therefore roll back everything it wrote.
 */
final class CommandRules {
    /**
     * Most units one operation may create across all recipients. It keeps the instance mutations,
     * ledger entries and announcing events of a single commit far below the plan bounds.
     */
    static final int MAX_CREATED_UNITS = 64;

    /** Static transaction participant with no lifecycle or external clients. */
    private CommandRules() { }

    /** One held instance as read under the campaign lock: definition, units and whether it is worn. */
    private record Held(String definition, int quantity, boolean worn) { }

    /**
     * Validate one decoded intention for the already eligible acting player.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked
     * @param player acting player returned by {@link CommandSemantics#requireEligible}
     * @param command decoded intention from the frozen canonical bytes
     * @param content catalog preloaded outside the lock; required only by rules that need definitions
     * @return actors this intention involves: the player plus a recipient or explicit targets
     * @throws OperationAdmissionFailure with the first violated rule's category
     */
    static Set<UUID> require(JdbcTemplate jdbc, UUID campaign, UUID player, TypedCommand command,
            CommandRuleCatalogs.Content content) {
        var check = new Check(jdbc, campaign, player, content);
        var participants = new LinkedHashSet<UUID>();
        participants.add(player);
        switch (command) {
            case TypedCommand.Move move -> check.reachable(move.destinationId());
            case TypedCommand.Wait ignored -> { }
            case TypedCommand.Speak speech -> {
                if (speech.recipientActorId().equals(player)) {
                    throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
                }
                check.perceivable(speech.recipientActorId());
                participants.add(speech.recipientActorId());
            }
            case TypedCommand.UseAbility use -> participants.addAll(check.ability(use));
            case TypedCommand.UseItem use -> participants.addAll(check.itemUse(use));
            case TypedCommand.EquipItem equip -> check.equip(equip.itemInstanceId());
            case TypedCommand.UnequipItem unequip -> check.unequip(unequip.itemInstanceId());
            case TypedCommand.TransferItem gift -> {
                if (gift.recipientActorId().equals(player)) {
                    throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
                }
                check.perceivable(gift.recipientActorId());
                check.give(gift.itemInstanceId(), gift.quantity());
                participants.add(gift.recipientActorId());
            }
            case TypedCommand.AcceptQuest accept -> check.quest(accept.questId(), QuestStatus.AVAILABLE);
            case TypedCommand.SuspendQuest suspend -> check.quest(suspend.questId(), QuestStatus.ACTIVE);
            case TypedCommand.ResumeQuest resume -> check.quest(resume.questId(), QuestStatus.SUSPENDED);
            case TypedCommand.TradeItems trade -> {
                if (trade.counterpartyActorId().equals(player)) {
                    throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
                }
                check.perceivable(trade.counterpartyActorId());
                check.trade(trade);
                participants.add(trade.counterpartyActorId());
            }
        }
        return Set.copyOf(participants);
    }

    /**
     * Reject resolved effects that the admitted intention cannot have caused: every adjusted
     * resource, placed actor, item mutation, created item and equipment change must belong to a
     * participant, every settled reward addressed to an actor must name one as its recipient, every recorded visit must be a
     * participant's, and every event actor must be absent or a participant. A resolver bug or a forged plan therefore cannot charge or credit an
     * uninvolved actor. Runs under the commit's campaign lock before any entity changes.
     *
     * @throws IllegalStateException for an unattributable or out-of-scope effect
     */
    static void requireAttributed(JdbcTemplate jdbc, UUID campaign, Set<UUID> participants, ResolvedCommit plan) {
        for (var change : plan.resources()) {
            List<UUID> owner = jdbc.query("select actor_id from actor_resource where id = ? and campaign_id = ?",
                    (row, index) -> row.getObject(1, UUID.class), change.resourceId(), campaign);
            if (owner.isEmpty() || !participants.contains(owner.getFirst())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        for (var placement : plan.placements()) {
            if (!participants.contains(placement.actorId())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        // Items: every instance mutation, creation and equipment change must stay between participants.
        for (ItemChange change : plan.items().changes()) {
            boolean attributable = switch (change) {
                case ItemChange.Update update -> participants.contains(update.expectedOwner())
                        && participants.contains(update.newOwner());
                case ItemChange.Delete delete -> participants.contains(delete.expectedOwner());
                case ItemChange.Insert insert -> participants.contains(insert.ownerId());
            };
            if (!attributable) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        for (var creation : plan.items().creations()) {
            if (!participants.contains(creation.recipientId())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        for (var change : plan.items().equipment()) {
            if (!participants.contains(change.actorId())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        // A reward whose recipient names an actor must name a participant; other recipient keys (a group or
        // faction recipient policy) do not identify an actor and are not attributed here. A recorded visit
        // belongs to the visiting participant.
        for (var reward : plan.rewards()) {
            if (isActorKey(reward.recipientKey()) && participants.stream()
                    .noneMatch(actor -> actor.toString().equals(reward.recipientKey()))) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        for (var visit : plan.quests().visits()) {
            if (!participants.contains(visit.actorId())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        for (var event : plan.events()) {
            if (event.actorId() != null && !participants.contains(event.actorId())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        // Knowledge records of uninvolved actors (their beliefs, sightings, commitments or testimony)
        // cannot be written by this intention; a mere subject of a claim or sighting is not touched.
        for (var change : plan.knowledge()) {
            if (!participants.containsAll(change.actors())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
    }

    /** Whether a reward recipient key is the canonical text of an actor identity. */
    private static boolean isActorKey(String key) {
        try {
            return UUID.fromString(key).toString().equals(key);
        } catch (IllegalArgumentException notAnActor) {
            return false;
        }
    }

    /**
     * What the acting player perceives after the commit's effects: its current location and every
     * other campaign actor there that passes the same perception rule as {@code SPEAK} recipients,
     * ordered by identity. Runs under the commit's campaign lock after placements and knowledge
     * changes were written, so movement and reveals of this very operation count.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked
     * @param player acting player
     * @param content pinned catalog, possibly unavailable
     * @return the perception, or null when nothing can be observed safely: no current location, no
     *         pinned catalog (concealment could not be decided) or more co-present actors than
     *         {@link KnowledgeLedger#MAX_OBSERVED}; null never records a sighting or an absence
     */
    static Perception perceived(JdbcTemplate jdbc, UUID campaign, UUID player, CommandRuleCatalogs.Content content) {
        if (content.catalog() == null) {
            return null;
        }
        var check = new Check(jdbc, campaign, player, content);
        try {
            check.catalog();
        } catch (OperationAdmissionFailure unavailable) {
            // A catalog of another revision cannot decide concealment for this campaign.
            return null;
        }
        String here = check.here();
        if (here == null) {
            return null;
        }
        List<UUID> present = jdbc.queryForList("select id from actor where campaign_id = ? and location_id = ? "
                + "and id <> ? order by id limit ?", UUID.class, campaign, here, player, KnowledgeLedger.MAX_OBSERVED + 1);
        if (present.size() > KnowledgeLedger.MAX_OBSERVED) {
            return null;
        }
        var seen = new java.util.ArrayList<UUID>();
        for (UUID actor : present) {
            try {
                check.perceivable(actor);
                seen.add(actor);
            } catch (OperationAdmissionFailure concealed) {
                // Concealed, unresolvable or revealed-to-nobody actors are simply not perceived.
            }
        }
        return new Perception(here, List.copyOf(seen));
    }

    /**
     * Result of {@link #perceived}.
     *
     * @param location the player's current location
     * @param actors perceived co-present actors, ordered by identity
     */
    record Perception(String location, List<UUID> actors) { }

    /** Build a sanitized category for {@code throw}; the caller's transaction rolls back every write. */
    private static OperationAdmissionFailure fail(OperationAdmissionFailure.Kind kind) {
        return new OperationAdmissionFailure(kind);
    }

    /**
     * Call-local rule reader. The pinned catalog is resolved lazily so that rules needing
     * no definitions (for example speech to an actor without an authored origin) never fail
     * merely because content is unavailable.
     */
    private static final class Check {
        private final JdbcTemplate jdbc;
        private final UUID campaign;
        private final UUID player;
        private final CommandRuleCatalogs.Content content;
        private CommandRuleCatalog catalog;
        private String here;
        private String origin;
        private boolean located;

        /** Bind the locked campaign, acting player and preloaded content. */
        Check(JdbcTemplate jdbc, UUID campaign, UUID player, CommandRuleCatalogs.Content content) {
            this.jdbc = jdbc;
            this.campaign = campaign;
            this.player = player;
            this.content = content;
        }

        /** Return the catalog for the campaign's frozen revision, or fail closed. */
        CommandRuleCatalog catalog() {
            if (catalog == null) {
                catalog = content.require(jdbc.queryForObject("select content_revision_id from campaign where id = ?",
                        UUID.class, campaign));
            }
            return catalog;
        }

        /** Current committed location of the player; null when the save records none. */
        String here() {
            if (!located) {
                // Location is mutable world state; the creation-profile origin is frozen by a trigger.
                var row = jdbc.queryForObject("select location_id, definition_id from actor "
                        + "where campaign_id = ? and id = ?", (result, index) -> new String[] {result.getString(1),
                        result.getString(2)}, campaign, player);
                here = row[0];
                origin = row[1];
                located = true;
            }
            return here;
        }

        /** Canonical creation-profile ID the player was created from; null for legacy rows. */
        private String origin() {
            here();
            return origin;
        }

        /**
         * Movement uses one authored link. Unknown, concealed (and not revealed), current, unlinked
         * and blocked destinations share one category so a secret location or route is never confirmed.
         */
        void reachable(String destination) {
            String current = here();
            if (current == null || current.equals(destination)) {
                throw fail(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE);
            }
            var rules = catalog();
            var origin = rules.locations().get(current);
            var target = rules.locations().get(destination);
            if (origin == null || target == null || !origin.accessibleLinks().contains(destination)
                    || (!target.disclosed() && !KnowledgeLedger.revealedTo(jdbc, campaign, player,
                            KnowledgeRefs.location(destination)))) {
                throw fail(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE);
            }
        }

        /**
         * A quest request needs a pinned, addressable quest in exactly the status the transition leaves.
         * A quest without a stored row is read at its pack-declared initial status, so an undiscovered
         * quest is indistinguishable from an unknown one.
         */
        void quest(String questId, QuestStatus needed) {
            var quest = catalog().quests().quests().get(questId);
            if (quest == null || quest.secret()) {
                throw fail(OperationAdmissionFailure.Kind.QUEST_UNAVAILABLE);
            }
            var stored = QuestStore.read(jdbc, campaign, player).states().get(questId);
            QuestStatus status = stored == null ? quest.initial() : stored.status();
            if (status != needed) {
                throw fail(OperationAdmissionFailure.Kind.QUEST_UNAVAILABLE);
            }
        }

        /**
         * Perception basis for addressing an actor: same campaign, a shared non-null current
         * location and an authored origin that is disclosed, or concealed but revealed to the player
         * as this actor. An origin that does not resolve in the pinned content is treated as
         * concealed and cannot be revealed, because disclosure cannot be established. Actors without
         * an authored origin are perceived through co-presence alone.
         */
        void perceivable(UUID actor) {
            String current = here();
            var rows = jdbc.query("select location_id, definition_id from actor where campaign_id = ? and id = ?",
                    (row, index) -> new String[] {row.getString(1), row.getString(2)}, campaign, actor);
            if (rows.isEmpty() || current == null || !current.equals(rows.getFirst()[0])) {
                throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
            }
            String origin = rows.getFirst()[1];
            if (origin == null) {
                return;
            }
            Boolean disclosed = catalog().actorDefinitions().get(origin);
            if (disclosed == null || (!disclosed && !KnowledgeLedger.revealedTo(jdbc, campaign, player,
                    KnowledgeRefs.actor(actor)))) {
                throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
            }
        }

        /**
         * Ability rules in a fixed order: ownership, prerequisites, target policy, perceived
         * targets, affordable costs and finally an executable effect program. The first failure
         * wins deterministically.
         *
         * @return explicit targets that become commit participants
         */
        List<UUID> ability(TypedCommand.UseAbility use) {
            var ability = catalog().abilities().get(use.abilityId());
            if (ability == null || !learned(use.abilityId())) {
                throw fail(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE);
            }
            for (String required : ability.abilities()) {
                if (!learned(required)) {
                    throw fail(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
                }
            }
            for (var need : ability.skills()) {
                // CASE keeps the cast behind the type test; a missing row or skill is rank zero.
                if (!Boolean.TRUE.equals(jdbc.query("select case when jsonb_typeof(skills -> ?) = 'number' "
                                + "then (skills ->> ?)::numeric >= ? else false end from character_state "
                                + "where campaign_id = ? and actor_id = ?", (row, index) -> row.getBoolean(1),
                        need.skill(), need.skill(), need.rank(), campaign, player).stream().findFirst().orElse(false))) {
                    throw fail(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
                }
            }
            targets(ability.targetPolicy(), ability.maxTargets(), use.targetActorIds());
            // Sorted keys give a deterministic first failure when several costs are unaffordable.
            for (var cost : new TreeMap<>(ability.costs()).entrySet()) {
                if (cost.getValue() == 0) {
                    continue;
                }
                // Resource keys are unqualified: a cost declared by another pack's vocabulary may
                // share a key with the player's resource while meaning something else, so the
                // player cannot pay it. Cost-free dependency abilities remain usable. A player
                // without a resolvable creation origin has no provable vocabulary and cannot pay.
                if (!ability.pack().equals(vocabulary())) {
                    throw fail(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT);
                }
                List<Long> headroom = jdbc.query("select current_value - minimum_value from actor_resource "
                                + "where campaign_id = ? and actor_id = ? and resource_key = ?",
                        (row, index) -> row.getLong(1), campaign, player, cost.getKey());
                if (headroom.isEmpty() || headroom.getFirst() < cost.getValue()) {
                    throw fail(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT);
                }
            }
            runnable(ability, use.targetActorIds().size());
            return use.targetActorIds();
        }

        /**
         * Target count must fit the policy, and every explicit target must be perceivable. Shared by
         * abilities and item uses so both follow one targeting rule.
         */
        private void targets(String policy, int maxTargets, List<UUID> targets) {
            int count = targets.size();
            boolean legal = switch (policy) {
                case "self" -> count == 0;
                case "single" -> count == 1;
                case "multiple" -> count >= 1 && count <= maxTargets;
                // Area targeting needs encounter zones (P11); unknown future policies fail closed.
                default -> false;
            };
            if (!legal) {
                throw fail(OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
            }
            for (UUID target : targets) {
                perceivable(target);
            }
        }

        /**
         * Effects the resolver cannot execute are refused now, before a journal row, permit or draw
         * exists. Attribute/resource atoms and resource changes use unqualified keys, so reading them
         * is meaningful only in the player's own vocabulary. Writing a foreign vocabulary key is
         * allowed: the resolver records that actor as explicitly unaffected. The units an action can
         * create are bounded so a single operation writes a bounded number of rows and ledger entries.
         */
        private void runnable(CommandRuleCatalog.Ability contract, int targetCount) {
            var effects = contract.effects();
            if (!EffectProgram.supported(effects)
                    || EffectProgram.maxDraws(effects) > org.rpgengine.rules.DeterministicDraw.MAX_DRAWS
                    || (EffectProgram.readsActorValues(effects) && !contract.pack().equals(vocabulary()))
                    || (long) EffectProgram.maxCreatedUnits(effects) * Math.max(1, targetCount) > MAX_CREATED_UNITS) {
                throw fail(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED);
            }
        }

        /**
         * Item use: a held instance whose pinned definition declares a use contract. A contract that
         * consumes a unit needs the instance unworn, because a worn instance is bound to its slot.
         *
         * @return explicit targets that become commit participants
         */
        List<UUID> itemUse(TypedCommand.UseItem use) {
            Held item = held(use.itemInstanceId(), player);
            var rule = catalog().items().get(item.definition());
            if (rule == null || rule.use() == null || (rule.use().consumes() && item.worn())) {
                throw fail(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE);
            }
            var contract = rule.use().contract();
            targets(contract.targetPolicy(), contract.maxTargets(), use.targetActorIds());
            runnable(contract, use.targetActorIds().size());
            return use.targetActorIds();
        }

        /**
         * Wearing an instance: it must be held and unworn, and the P10.1 equipment constraints (slot,
         * tags, capacity, hands) must all hold. Facts the character rules do not know reject the action.
         */
        void equip(UUID instance) {
            held(instance, player);
            try {
                if (!new CharacterService(jdbc).equipViolations(campaign, player, instance, catalog().characters()).isEmpty()) {
                    throw fail(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL);
                }
            } catch (CharacterRuleViolation violation) {
                throw fail(violation.reason() == CharacterRuleViolation.Reason.UNKNOWN_ITEM
                        ? OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE : OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL);
            }
        }

        /** Taking off requires an instance the player actually wears. */
        void unequip(UUID instance) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment_assignment "
                    + "where campaign_id = ? and actor_id = ? and item_instance_id = ?)", Boolean.class, campaign,
                    player, instance))) {
                throw fail(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL);
            }
        }

        /** A gift moves units of an unworn instance of the player; the player cannot give more than held. */
        void give(UUID instance, int quantity) {
            Held item = held(instance, player);
            if (item.worn()) {
                throw fail(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE);
            }
            if (quantity > item.quantity()) {
                throw fail(OperationAdmissionFailure.Kind.QUANTITY_ILLEGAL);
            }
        }

        /**
         * Trade lines must be unworn instances held by the right side in the stated quantities, every
         * item must carry a declared value, and the counterparty must receive at least the value it
         * gives. Sums use exact long arithmetic (value &le; 10^6, quantity &le; 9999, at most 16 lines).
         */
        void trade(TypedCommand.TradeItems trade) {
            long given = value(trade.offered(), player);
            long received = value(trade.requested(), trade.counterpartyActorId());
            if (given < received) {
                throw fail(OperationAdmissionFailure.Kind.TRADE_REFUSED);
            }
        }

        /** Total declared value of lines that {@code holder} must hold; unpriced items refuse the trade. */
        private long value(List<TypedCommand.TradeItems.Line> lines, UUID holder) {
            long total = 0;
            for (var line : lines) {
                Held item = held(line.itemInstanceId(), holder);
                if (item.worn()) {
                    throw fail(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE);
                }
                if (line.quantity() > item.quantity()) {
                    throw fail(OperationAdmissionFailure.Kind.QUANTITY_ILLEGAL);
                }
                var rule = catalog().items().get(item.definition());
                if (rule == null || rule.value() == null) {
                    throw fail(OperationAdmissionFailure.Kind.TRADE_REFUSED);
                }
                total = Math.addExact(total, Math.multiplyExact((long) rule.value(), line.quantity()));
            }
            return total;
        }

        /**
         * Read one instance that {@code owner} must hold. A missing instance and an instance of
         * another actor share one category, so probing identities reveals nothing.
         */
        private Held held(UUID instance, UUID owner) {
            var rows = jdbc.query("select i.definition_id, i.quantity, exists(select 1 from equipment_assignment e "
                    + "where e.campaign_id = i.campaign_id and e.item_instance_id = i.id) from item_instance i "
                    + "where i.campaign_id = ? and i.id = ? and i.actor_id = ?",
                    (row, index) -> new Held(row.getString(1), row.getInt(2), row.getBoolean(3)), campaign, instance,
                    owner);
            if (rows.isEmpty()) {
                throw fail(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE);
            }
            return rows.getFirst();
        }

        /**
         * Declaring pack of the player's creation profile, which supplied its resource and
         * attribute keys; null for a player without a resolvable creation origin.
         */
        private String vocabulary() {
            String origin = origin();
            return origin == null ? null : catalog().resourceVocabularies().get(origin);
        }

        /** Whether the acting player currently holds a learned row for the canonical ability ID. */
        private boolean learned(String ability) {
            return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from learned_ability "
                    + "where campaign_id = ? and actor_id = ? and definition_id = ?)", Boolean.class,
                    campaign, player, ability));
        }
    }
}
