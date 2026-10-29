package org.rpgengine.characters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.rules.CharacterSheet;
import org.rpgengine.rules.ConditionRules;
import org.rpgengine.rules.EquipmentRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Typed character state operations: the effective sheet, learned abilities, active conditions
 * and equipment, each validated against the campaign's pinned {@link CharacterRuleCatalog}
 * (docs/05 sections 4 and 5, docs/10 sections 1 and 3).
 *
 * <p>Every operation is a rule-checked, narrow write, never a generic patch, and none of them
 * invents rewards, rolls dice or advances time. They run inside the caller's transaction, which
 * must already hold the campaign's mutation lease (the canonical commit of P10.2 does): each
 * writer first takes a row lock on the actor, so two operations on one character serialize
 * even if the lease were bypassed, and a refused operation throws {@link CharacterRuleViolation}
 * before writing anything. Without an ambient transaction the service refuses to run, so it can
 * never commit a half-finished change.
 *
 * <p>The sheet is recomputed from committed rows on every read; nothing derived is stored, so a
 * derived value cannot go stale and the campaign state revision it was read at identifies it.
 */
@Service
public class CharacterService {
    private static final TypeReference<Map<String, Long>> NUMBERS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param jdbc JDBC access joined to the caller's transaction
     */
    public CharacterService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * One worn item.
     *
     * @param itemInstanceId item instance identity
     * @param definitionId canonical item definition ID
     * @param slot slot key
     * @param hands hands occupied
     */
    public record Equipped(UUID itemInstanceId, String definitionId, String slot, int hands) {
    }

    /**
     * Compute the actor's effective sheet from committed rows.
     *
     * @param campaignId owning campaign
     * @param actorId actor with creation-time character state
     * @param catalog the campaign's pinned rules
     * @return attributes after conditions, skills, derived statistics and active conditions
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR} when the actor has no character state or
     *         {@code UNKNOWN_DEFINITION} when its profile is not in the pinned rules
     * @throws IllegalStateException if stored values disagree with the pinned rules
     */
    public CharacterSheet sheet(UUID campaignId, UUID actorId, CharacterRuleCatalog catalog) {
        requireTransaction();
        var vocabulary = vocabulary(actorId, campaignId, catalog, false);
        var rows = jdbc.query("select attributes::text, skills::text from character_state where campaign_id = ? "
                + "and actor_id = ?", (row, index) -> new String[] {row.getString(1), row.getString(2)}, campaignId, actorId);
        if (rows.isEmpty()) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.UNKNOWN_ACTOR,
                    "actor has no character state", List.of());
        }
        Map<String, Long> attributes = json.readValue(rows.getFirst()[0], NUMBERS);
        Map<String, Long> skills = json.readValue(rows.getFirst()[1], NUMBERS);
        var active = conditions(campaignId, actorId);
        return CharacterSheet.compute(vocabulary.attributes(), vocabulary.derived(), attributes, skills, active,
                catalog.conditions());
    }

    /**
     * Apply a condition under its stacking policy.
     *
     * @param campaignId owning campaign
     * @param actorId target actor
     * @param conditionId canonical condition ID
     * @param catalog the campaign's pinned rules
     * @param revision campaign state revision of this change
     * @return the stored state and what changed
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR}, {@code UNKNOWN_DEFINITION} or {@code STALE_REVISION}
     */
    public ConditionRules.Application applyCondition(UUID campaignId, UUID actorId, String conditionId,
            CharacterRuleCatalog catalog, long revision) {
        requireTransaction();
        lockActor(campaignId, actorId);
        var definition = catalog.conditions().get(conditionId);
        if (definition == null) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.UNKNOWN_DEFINITION,
                    "unknown condition " + conditionId, List.of());
        }
        var stored = jdbc.query("select stacks, remaining_rounds, updated_revision from actor_condition where "
                + "campaign_id = ? and actor_id = ? and condition_id = ?", (row, index) ->
                        new Object[] {new ConditionRules.Active(conditionId, row.getInt(1), row.getInt(2)), row.getLong(3)},
                campaignId, actorId, conditionId);
        if (!stored.isEmpty() && revision < (Long) stored.getFirst()[1]) {
            throw stale(revision);
        }
        var result = ConditionRules.apply(definition, stored.isEmpty() ? Optional.empty()
                : Optional.of((ConditionRules.Active) stored.getFirst()[0]));
        var state = result.state();
        if (stored.isEmpty()) {
            jdbc.update("insert into actor_condition (campaign_id, actor_id, condition_id, stacks, remaining_rounds, "
                    + "applied_revision, updated_revision) values (?, ?, ?, ?, ?, ?, ?)", campaignId, actorId,
                    conditionId, state.stacks(), state.remainingRounds(), revision, revision);
        } else if (result.change() != ConditionRules.Change.UNCHANGED) {
            jdbc.update("update actor_condition set stacks = ?, remaining_rounds = ?, updated_revision = ?, "
                    + "row_version = row_version + 1 where campaign_id = ? and actor_id = ? and condition_id = ?",
                    state.stacks(), state.remainingRounds(), revision, campaignId, actorId, conditionId);
        }
        return result;
    }

    /**
     * Remove one active condition, for example when a rule or treatment ends it.
     *
     * @return whether the condition was active
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR}
     */
    public boolean removeCondition(UUID campaignId, UUID actorId, String conditionId) {
        requireTransaction();
        lockActor(campaignId, actorId);
        return jdbc.update("delete from actor_condition where campaign_id = ? and actor_id = ? and condition_id = ?",
                campaignId, actorId, conditionId) == 1;
    }

    /**
     * Let rounds pass: shorten every active condition and delete those whose duration is used up.
     *
     * @param rounds elapsed rounds, not negative
     * @param revision campaign state revision of this change
     * @return IDs of the conditions that expired, sorted
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR} or {@code STALE_REVISION}
     */
    public List<String> elapse(UUID campaignId, UUID actorId, long rounds, long revision) {
        requireTransaction();
        lockActor(campaignId, actorId);
        var expired = new ArrayList<String>();
        for (var active : conditions(campaignId, actorId)) {
            var next = ConditionRules.elapse(active, rounds);
            if (next.isEmpty()) {
                jdbc.update("delete from actor_condition where campaign_id = ? and actor_id = ? and condition_id = ?",
                        campaignId, actorId, active.id());
                expired.add(active.id());
            } else {
                long updated = jdbc.queryForObject("select updated_revision from actor_condition where campaign_id = ? "
                        + "and actor_id = ? and condition_id = ?", Long.class, campaignId, actorId, active.id());
                if (revision < updated) {
                    throw stale(revision);
                }
                jdbc.update("update actor_condition set remaining_rounds = ?, updated_revision = ?, "
                        + "row_version = row_version + 1 where campaign_id = ? and actor_id = ? and condition_id = ?",
                        next.get().remainingRounds(), revision, campaignId, actorId, active.id());
            }
        }
        return expired;
    }

    /**
     * Record a learned ability at rank one when its prerequisites hold. Prerequisites are the
     * pinned definition's skill ranks (read from the actor's stored skills) and abilities already
     * learned; nothing is spent, rolled or inferred from prose. Rank advancement is a separate
     * advancement operation (docs/05 section 4) and is not offered here.
     *
     * @param abilityId canonical ability ID
     * @param revision campaign state revision that becomes the acquisition revision
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR}, {@code UNKNOWN_DEFINITION},
     *         {@code ALREADY_LEARNED} or {@code PREREQUISITE_MISSING}
     */
    public void learnAbility(UUID campaignId, UUID actorId, String abilityId, CharacterRuleCatalog catalog,
            long revision) {
        requireTransaction();
        lockActor(campaignId, actorId);
        var rule = catalog.abilities().get(abilityId);
        if (rule == null) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.UNKNOWN_DEFINITION,
                    "unknown ability " + abilityId, List.of());
        }
        Set<String> learned = Set.copyOf(jdbc.queryForList("select definition_id from learned_ability where "
                + "campaign_id = ? and actor_id = ?", String.class, campaignId, actorId));
        if (learned.contains(abilityId)) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.ALREADY_LEARNED,
                    "ability already learned: " + abilityId, List.of());
        }
        Map<String, Long> skills = json.readValue(jdbc.queryForObject("select skills::text from character_state "
                + "where campaign_id = ? and actor_id = ?", String.class, campaignId, actorId), NUMBERS);
        for (var need : rule.skills().entrySet()) {
            if (skills.getOrDefault(need.getKey(), 0L) < need.getValue()) {
                throw new CharacterRuleViolation(CharacterRuleViolation.Reason.PREREQUISITE_MISSING,
                        "skill " + need.getKey() + " needs rank " + need.getValue(), List.of());
            }
        }
        for (String required : rule.abilities()) {
            if (!learned.contains(required)) {
                throw new CharacterRuleViolation(CharacterRuleViolation.Reason.PREREQUISITE_MISSING,
                        "ability " + required + " must be learned first", List.of());
            }
        }
        jdbc.update("insert into learned_ability (campaign_id, actor_id, definition_id, rank, acquired_revision) "
                + "values (?, ?, ?, 1, ?)", campaignId, actorId, abilityId, revision);
    }

    /**
     * List what the actor currently wears, ordered by slot then instance.
     *
     * @return worn items; empty when nothing is worn
     */
    public List<Equipped> equipment(UUID campaignId, UUID actorId) {
        requireTransaction();
        return jdbc.query("select e.item_instance_id, i.definition_id, e.slot_id, e.hands from equipment_assignment e "
                + "join item_instance i on i.campaign_id = e.campaign_id and i.id = e.item_instance_id "
                + "where e.campaign_id = ? and e.actor_id = ? order by e.slot_id, e.item_instance_id",
                (row, index) -> new Equipped(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                        row.getInt(4)), campaignId, actorId);
    }

    /**
     * Wear an item instance the actor holds. Compatibility, slot capacity and hand occupancy are
     * checked together against the actor's vocabulary; the item's definition, never its artwork,
     * decides the slot, hands and tags.
     *
     * @param itemInstanceId instance to wear
     * @param revision campaign state revision recorded as the equip revision
     * @return the new assignment
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR}, {@code UNKNOWN_DEFINITION},
     *         {@code UNKNOWN_ITEM} or {@code EQUIPMENT} with every violated constraint
     */
    public Equipped equip(UUID campaignId, UUID actorId, UUID itemInstanceId, CharacterRuleCatalog catalog,
            long revision) {
        requireTransaction();
        var vocabulary = vocabulary(actorId, campaignId, catalog, true);
        var check = evaluate(campaignId, actorId, itemInstanceId, catalog, vocabulary);
        if (!check.violations().isEmpty()) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.EQUIPMENT,
                    "item " + check.definitionId() + " cannot be equipped: " + check.violations(), check.violations());
        }
        var gear = check.item().gear();
        jdbc.update("insert into equipment_assignment (campaign_id, actor_id, item_instance_id, slot_id, hands, "
                + "equipped_revision) values (?, ?, ?, ?, ?, ?)", campaignId, actorId, itemInstanceId, gear.slot(),
                gear.hands(), revision);
        return new Equipped(itemInstanceId, check.definitionId(), gear.slot(), gear.hands());
    }

    /**
     * Report why an item instance could not be worn right now, without writing or locking. The
     * commands' rule gate uses it to refuse an impossible equip before anything is journaled, with
     * exactly the check {@link #equip} repeats under the commit's locks.
     *
     * @param itemInstanceId instance the actor holds
     * @return every violated constraint in declaration order; empty when the item can be worn
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR}, {@code UNKNOWN_DEFINITION} or {@code UNKNOWN_ITEM}
     */
    public List<EquipmentRules.Violation> equipViolations(UUID campaignId, UUID actorId, UUID itemInstanceId,
            CharacterRuleCatalog catalog) {
        requireTransaction();
        return evaluate(campaignId, actorId, itemInstanceId, catalog, vocabulary(actorId, campaignId, catalog, false))
                .violations();
    }

    /** Facts of one equip check: the held definition, its rules and the violations against current equipment. */
    private record Evaluation(String definitionId, CharacterRuleCatalog.Item item,
            List<EquipmentRules.Violation> violations) {
    }

    /** Resolve the held instance and check it against the actor's slots and hands together. */
    private Evaluation evaluate(UUID campaignId, UUID actorId, UUID itemInstanceId, CharacterRuleCatalog catalog,
            CharacterRuleCatalog.Vocabulary vocabulary) {
        var held = jdbc.queryForList("select definition_id from item_instance where campaign_id = ? and actor_id = ? "
                + "and id = ?", String.class, campaignId, actorId, itemInstanceId);
        if (held.isEmpty()) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.UNKNOWN_ITEM,
                    "item instance is not held by the actor", List.of());
        }
        var item = catalog.items().get(held.getFirst());
        if (item == null) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.UNKNOWN_DEFINITION,
                    "unknown item " + held.getFirst(), List.of());
        }
        var worn = new ArrayList<EquipmentRules.Worn>();
        for (var entry : equipment(campaignId, actorId)) {
            worn.add(new EquipmentRules.Worn(entry.itemInstanceId().toString(), entry.slot(), entry.hands()));
        }
        return new Evaluation(held.getFirst(), item, EquipmentRules.check(vocabulary.hands(), vocabulary.slots(), worn,
                itemInstanceId.toString(), item.gear()));
    }

    /**
     * Stop wearing an item instance; the item stays in the inventory.
     *
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR} or {@code NOT_WORN}
     */
    public void unequip(UUID campaignId, UUID actorId, UUID itemInstanceId) {
        requireTransaction();
        lockActor(campaignId, actorId);
        if (jdbc.update("delete from equipment_assignment where campaign_id = ? and actor_id = ? and item_instance_id = ?",
                campaignId, actorId, itemInstanceId) != 1) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.NOT_WORN, "item instance is not worn",
                    List.of());
        }
    }

    /** Read the actor's active conditions ordered by ID. */
    private List<ConditionRules.Active> conditions(UUID campaignId, UUID actorId) {
        return jdbc.query("select condition_id, stacks, remaining_rounds from actor_condition where campaign_id = ? "
                + "and actor_id = ? order by condition_id", (row, index) ->
                        new ConditionRules.Active(row.getString(1), row.getInt(2), row.getInt(3)), campaignId, actorId);
    }

    /**
     * Lock the actor row and return its creation profile ID.
     *
     * @throws CharacterRuleViolation {@code UNKNOWN_ACTOR}
     */
    private String lockActor(UUID campaignId, UUID actorId) {
        var rows = jdbc.query("select definition_id from actor where campaign_id = ? and id = ? for update",
                (row, index) -> Optional.ofNullable(row.getString(1)), campaignId, actorId);
        if (rows.isEmpty()) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.UNKNOWN_ACTOR, "unknown actor", List.of());
        }
        return rows.getFirst().orElse(null);
    }

    /** Resolve the actor's vocabulary, locking the actor first when the caller is about to write. */
    private CharacterRuleCatalog.Vocabulary vocabulary(UUID actorId, UUID campaignId, CharacterRuleCatalog catalog,
            boolean lock) {
        String profile = lock ? lockActor(campaignId, actorId) : jdbc.query("select definition_id from actor where "
                + "campaign_id = ? and id = ?", (row, index) -> Optional.ofNullable(row.getString(1)), campaignId, actorId)
                .stream().findFirst().orElseThrow(() -> new CharacterRuleViolation(
                        CharacterRuleViolation.Reason.UNKNOWN_ACTOR, "unknown actor", List.of())).orElse(null);
        var vocabulary = profile == null ? null : catalog.vocabularyOf(profile);
        if (vocabulary == null) {
            throw new CharacterRuleViolation(CharacterRuleViolation.Reason.UNKNOWN_DEFINITION,
                    "the actor has no pinned character vocabulary", List.of());
        }
        return vocabulary;
    }

    private static CharacterRuleViolation stale(long revision) {
        return new CharacterRuleViolation(CharacterRuleViolation.Reason.STALE_REVISION,
                "revision " + revision + " is older than the state it changes", List.of());
    }

    /** Refuse to run outside the caller's transaction, so a failure can never leave a partial change. */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("character operations require the caller's transaction");
        }
    }
}
