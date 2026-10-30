package org.rpgengine.combat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.rpgengine.rules.CanonicalJson;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Storage of frozen encounters (P11.1, V22) and of their tactical progress (P11.2, V23). {@link #insertWithin} runs
 * inside the canonical commit's transaction, after the events it cites are flushed; it writes the encounter at
 * {@link EncounterPhase#ENTERING} and one row per roster entry. {@link #state} reads the active encounter's tactical
 * state back, with the frozen profile, space and declared actions taken from the stored canonical text, and
 * {@link #progressWithin} writes a {@link TacticalPlan}: each participant's final facts and every encounter row
 * state in order, each guarded by the row version the plan was calculated from. The database repeats every
 * guarantee: frozen columns are immutable, the lifecycle follows the state machine one arrow per update, initiative
 * is written once and before the first round opens, action points only grow when an activation starts, the speech
 * allowance holds, encounter-local Health stays in its bounds and a defeated participant stays defeated (V24), and a
 * second active encounter of the campaign violates a unique index, so a replayed, stale or
 * racing write rolls the whole commit back. {@link #active} reads the current encounter summary for rules and tests.
 * Since P11.4 each participant row also stores its active effects, reaction policies and ability uses as canonical JSON,
 * and the encounter row its application counter; V25 bounds them and refuses a counter that goes back.
 */
public final class EncounterWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Static transaction participant only. */
    private EncounterWriter() {
    }

    /**
     * Stored summary of an active encounter.
     *
     * @param id encounter identity
     * @param templateId canonical template ID
     * @param phase current lifecycle phase
     * @param round current round, 0 before initiative
     * @param profileHash SHA-256 of the frozen profile
     * @param definitionHash SHA-256 of the frozen definition
     * @param openingTarget declared opening strike target, or null
     * @param openingStatus {@code NONE}, {@code PENDING} or {@code RESOLVED}
     */
    public record Stored(UUID id, String templateId, EncounterPhase phase, int round, String profileHash,
            String definitionHash, String openingTarget, String openingStatus) {
    }

    /**
     * Store a frozen encounter and its roster.
     *
     * @param jdbc JDBC access joined to the commit transaction
     * @param campaign campaign whose row the caller has locked
     * @param operation committing operation
     * @param revision state revision the commit produces
     * @param eventSequence sequence of the event announcing the entry
     * @param contentRevision pinned content revision
     * @param rulesetHash pinned ruleset hash
     * @param frozen the frozen encounter
     * @param now commit timestamp
     * @throws org.springframework.dao.DataAccessException when a guard rejects the rows, including a second active
     *         encounter; the caller's transaction then rolls back every effect
     */
    public static void insertWithin(JdbcTemplate jdbc, UUID campaign, UUID operation, long revision, long eventSequence,
            UUID contentRevision, String rulesetHash, FrozenEncounter frozen, Instant now) {
        Objects.requireNonNull(frozen, "frozen");
        jdbc.update("insert into encounter (id, campaign_id, template_id, location_id, phase, round, content_revision_id, "
                        + "ruleset_hash, profile, profile_hash, definition, definition_hash, opening_policy, opening_target, "
                        + "opening_status, started_operation_id, event_sequence, started_revision, updated_revision, created_at) "
                        + "values (?, ?, ?, ?, ?, 0, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                frozen.id(), campaign, frozen.templateId(), frozen.location(), EncounterPhase.ENTERING.name(),
                contentRevision, rulesetHash, frozen.profile().canonical(), frozen.profile().hash(), frozen.definition(),
                frozen.definitionHash(), frozen.opening().wire(), frozen.openingTarget(),
                frozen.openingTarget() == null ? "NONE" : "PENDING", operation, eventSequence, revision, revision,
                Timestamp.from(now));
        List<FrozenEncounter.Participant> participants = frozen.participants();
        for (int ordinal = 0; ordinal < participants.size(); ordinal++) {
            var participant = participants.get(ordinal);
            boolean local = participant.actorId() == null;
            jdbc.update("insert into encounter_participant (encounter_id, campaign_id, participant_id, ordinal, actor_id, "
                            + "side, zone_id, health_current, focus_current, frozen, reaction_policies) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                    frozen.id(), campaign, participant.id(), ordinal, participant.actorId(), participant.side().name(),
                    participant.zone(), local ? (Object) Math.toIntExact(participant.health()) : null,
                    local ? (Object) Math.toIntExact(participant.focus()) : null,
                    CanonicalJson.write(participant.canonicalForm()), policies(participant.policies()));
        }
    }

    /**
     * Read the active encounter's tactical state under the caller's campaign lock.
     *
     * @param jdbc JDBC access joined to the caller's transaction
     * @param campaign campaign whose row the caller has locked
     * @return the state, or empty when no encounter is active
     * @throws IllegalStateException when a stored frozen form cannot be read back (a broken storage guarantee)
     */
    public static Optional<EncounterState> state(JdbcTemplate jdbc, UUID campaign) {
        var rows = jdbc.query("select id, row_version, phase, round, actor_cursor, action_points, speech_used, "
                        + "passages::text, profile::text, definition::text, opening_policy, opening_target, opening_status, "
                        + "effects_applied from encounter where campaign_id = ? and phase not in ('SETTLED', 'NARRATIVE_RESUME')",
                (row, index) -> new Object[] {row.getObject(1, UUID.class), row.getLong(2), row.getString(3),
                        row.getInt(4), row.getObject(5, Integer.class), row.getObject(6, Integer.class), row.getInt(7),
                        row.getString(8), row.getString(9), row.getString(10), row.getString(11), row.getString(12),
                        row.getString(13), row.getInt(14)}, campaign);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        UUID id = (UUID) row[0];
        try {
            var profile = CombatProfile.fromCanonical(JSON.readValue((String) row[8], Map.class));
            Map<?, ?> definition = JSON.readValue((String) row[9], Map.class);
            var space = ZoneGraph.fromCanonical((Map<?, ?>) definition.get("space"));
            var escapes = new java.util.TreeSet<String>();
            ((List<?>) definition.get("escapeZones")).forEach(zone -> escapes.add((String) zone));
            var actions = new ArrayList<EncounterAction>();
            if (definition.get("actions") instanceof List<?> declared) {
                declared.forEach(action -> actions.add(EncounterAction.fromCanonical((Map<?, ?>) action)));
            }
            // A definition frozen before P11.4 has no effects: its participants hold none.
            var effects = new java.util.TreeMap<String, CombatEffect>();
            if (definition.get("effects") instanceof List<?> frozen) {
                frozen.forEach(effect -> {
                    var read = CombatEffect.fromCanonical((Map<?, ?>) effect);
                    effects.put(read.id(), read);
                });
            }
            var passages = new ArrayList<EncounterAction.Passage>();
            for (Object passage : JSON.readValue((String) row[7], List.class)) {
                var form = (Map<?, ?>) passage;
                passages.add(new EncounterAction.Passage((String) form.get("from"), (String) form.get("to"),
                        Boolean.TRUE.equals(form.get("blocked"))));
            }
            var participants = jdbc.query("select p.participant_id, p.ordinal, p.actor_id, p.side, p.zone_id, p.status, "
                            + "p.initiative, p.initiative_rank, p.reactions, p.row_version, p.frozen::text, "
                            // The player's Health lives in its pool, read relative to the pool's minimum, the zero of combat.
                            + "coalesce(p.health_current, r.current_value - r.minimum_value), "
                            + "r.maximum_value - r.minimum_value, p.guarding, "
                            // Likewise its Focus; a profile without Focus, or a player without the pool, reads as none.
                            + "coalesce(p.focus_current, f.current_value - f.minimum_value, 0), "
                            + "coalesce(f.maximum_value - f.minimum_value, 0), p.effects::text, p.reaction_policies::text, "
                            + "p.ability_uses::text from encounter_participant p "
                            + "left join actor_resource r on r.campaign_id = p.campaign_id and r.actor_id = p.actor_id "
                            + "and r.resource_key = ? "
                            + "left join actor_resource f on f.campaign_id = p.campaign_id and f.actor_id = p.actor_id "
                            + "and f.resource_key = ?::text "
                            + "where p.encounter_id = ? and p.campaign_id = ? order by p.ordinal",
                    (participant, index) -> {
                        Map<?, ?> frozen = JSON.readValue(participant.getString(11), Map.class);
                        boolean local = participant.getObject(3, UUID.class) == null;
                        var held = new ArrayList<CombatEffects.Active>();
                        for (Object active : JSON.readValue(participant.getString(17), List.class)) {
                            held.add(CombatEffects.Active.fromCanonical((Map<?, ?>) active));
                        }
                        var policies = new java.util.TreeMap<String, CombatReaction.Policy>();
                        ((Map<?, ?>) JSON.readValue(participant.getString(18), Map.class)).forEach((reaction, policy) ->
                                policies.put((String) reaction, CombatReaction.Policy.fromCanonical((Map<?, ?>) policy)));
                        var uses = new java.util.TreeMap<String, CombatAbility.Use>();
                        ((Map<?, ?>) JSON.readValue(participant.getString(19), Map.class)).forEach((ability, use) ->
                                uses.put((String) ability, new CombatAbility.Use(
                                        ((Number) ((Map<?, ?>) use).get("lastRound")).intValue(),
                                        ((Number) ((Map<?, ?>) use).get("uses")).intValue())));
                        return new EncounterState.Combatant(participant.getString(1), participant.getInt(2),
                                participant.getObject(3, UUID.class),
                                EncounterTemplate.Side.valueOf(participant.getString(4)), participant.getString(5),
                                EncounterState.Status.valueOf(participant.getString(6)),
                                ((Number) ((Map<?, ?>) frozen.get("roles")).get("finesse")).intValue(),
                                ((Number) frozen.get("initiativeModifier")).intValue(),
                                participant.getObject(7, Integer.class), participant.getObject(8, Integer.class),
                                participant.getInt(9), participant.getLong(10), participant.getInt(12),
                                local ? ((Number) frozen.get("healthMax")).intValue() : participant.getInt(13),
                                participant.getBoolean(14), EncounterState.Sheet.fromCanonical(frozen),
                                participant.getInt(15),
                                local ? ((Number) frozen.get("focusMax")).intValue() : participant.getInt(16), held, policies,
                                uses);
                    }, profile.health(), profile.focus(), id, campaign);
            // Only unworn stacks can be spent: a worn instance is bound to its slot.
            var consumed = new java.util.TreeSet<String>();
            participants.getFirst().sheet().attacks().forEach(attack -> {
                if (attack.ammunition() != null) {
                    consumed.add(attack.ammunition());
                }
            });
            var ammunition = consumed.isEmpty() ? List.<EncounterState.Ammunition>of() : jdbc.query(
                    "select i.id, i.definition_id, i.quantity from item_instance i where i.campaign_id = ? and i.actor_id = ? "
                            + "and i.definition_id = any(?) and not exists (select 1 from equipment_assignment e "
                            + "where e.campaign_id = i.campaign_id and e.item_instance_id = i.id) order by i.id",
                    (stack, index) -> new EncounterState.Ammunition(stack.getObject(1, UUID.class), stack.getString(2),
                            stack.getInt(3)), campaign, participants.getFirst().actorId(), consumed.toArray(new String[0]));
            return Optional.of(new EncounterState(id, (Long) row[1], EncounterPhase.valueOf((String) row[2]), (Integer) row[3],
                    (Integer) row[4], (Integer) row[5], (Integer) row[6], passages, profile, space, escapes, actions,
                    OpeningPolicy.parse((String) row[10]), (String) row[11], (String) row[12], participants, ammunition,
                    effects, (Integer) row[13]));
        } catch (RuntimeException unreadable) {
            throw new IllegalStateException("stored encounter " + id + " cannot be read back", unreadable);
        }
    }

    /**
     * Apply a tactical plan: participant facts first (initiative must exist before the first round opens), then each
     * encounter row state as the next row version. Every update names the exact row version it expects.
     *
     * @param jdbc JDBC access joined to the commit transaction
     * @param campaign campaign whose row the caller has locked
     * @param revision state revision the commit produces
     * @param plan resolved tactical plan
     * @throws IllegalStateException when a row changed since the plan was calculated
     * @throws org.springframework.dao.DataAccessException when a database guard rejects a step; the caller's
     *         transaction then rolls back every effect
     */
    public static void progressWithin(JdbcTemplate jdbc, UUID campaign, long revision, TacticalPlan plan) {
        for (TacticalPlan.ParticipantUpdate update : plan.participants()) {
            var uses = new java.util.TreeMap<String, Object>();
            update.uses().forEach((ability, use) -> uses.put(ability, use.canonicalForm()));
            if (jdbc.update("update encounter_participant set zone_id = ?, initiative = ?, initiative_rank = ?, "
                            + "reactions = ?, status = ?, health_current = ?, guarding = ?, focus_current = ?, "
                            + "effects = ?::jsonb, reaction_policies = ?::jsonb, ability_uses = ?::jsonb, "
                            + "row_version = row_version + 1 "
                            + "where encounter_id = ? and campaign_id = ? and participant_id = ? and row_version = ?",
                    update.zone(), update.initiative(), update.rank(), update.reactions(), update.status().name(),
                    update.health(), update.guarding(), update.focus(),
                    CanonicalJson.write(update.effects().stream().map(active -> (Object) active.canonicalForm()).toList()),
                    policies(update.policies()), CanonicalJson.write(uses), plan.encounterId(), campaign,
                    update.participantId(), update.expectedRowVersion()) != 1) {
                throw new IllegalStateException("stale encounter participant");
            }
        }
        long version = plan.baseRowVersion();
        for (TacticalPlan.Step step : plan.steps()) {
            var passages = step.passages().stream().map(passage -> {
                var form = new java.util.LinkedHashMap<String, Object>();
                form.put("from", passage.from());
                form.put("to", passage.to());
                form.put("blocked", passage.blocked());
                return (Object) form;
            }).toList();
            if (jdbc.update("update encounter set phase = ?, round = ?, actor_cursor = ?, action_points = ?, "
                            + "speech_used = ?, passages = ?::jsonb, opening_status = ?, effects_applied = ?, "
                            + "updated_revision = ?, row_version = row_version + 1 "
                            + "where id = ? and campaign_id = ? and row_version = ?",
                    step.phase().name(), step.round(), step.cursor(), step.actionPoints(), step.speechUsed(),
                    CanonicalJson.write(passages), step.openingStatus(), step.effectsApplied(), revision,
                    plan.encounterId(), campaign, version) != 1) {
                throw new IllegalStateException("stale encounter");
            }
            version++;
        }
    }

    /** @return the policies as canonical stored text */
    private static String policies(Map<String, CombatReaction.Policy> policies) {
        var form = new java.util.TreeMap<String, Object>();
        policies.forEach((reaction, policy) -> form.put(reaction, policy.canonicalForm()));
        return CanonicalJson.write(form);
    }

    /**
     * Read the campaign's active encounter.
     *
     * @param jdbc JDBC access
     * @param campaign campaign
     * @return the encounter whose phase is not terminal, if any
     */
    public static Optional<Stored> active(JdbcTemplate jdbc, UUID campaign) {
        return jdbc.query("select id, template_id, phase, round, profile_hash, definition_hash, opening_target, "
                        + "opening_status from encounter where campaign_id = ? and phase not in ('SETTLED', 'NARRATIVE_RESUME')",
                (row, index) -> new Stored(row.getObject(1, UUID.class), row.getString(2),
                        EncounterPhase.valueOf(row.getString(3)), row.getInt(4), row.getString(5), row.getString(6),
                        row.getString(7), row.getString(8)), campaign).stream().findFirst();
    }
}
