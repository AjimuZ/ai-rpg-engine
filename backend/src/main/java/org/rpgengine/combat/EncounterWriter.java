package org.rpgengine.combat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.rpgengine.rules.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Storage of frozen encounters (P11.1, V22). {@link #insertWithin} runs inside the canonical commit's transaction,
 * after the events it cites are flushed; it writes the encounter at {@link EncounterPhase#ENTERING} and one row per
 * roster entry. The database repeats every guarantee: frozen columns are immutable, the lifecycle follows the state
 * machine, and a second active encounter of the campaign violates a unique index, so a replayed or racing entry
 * rolls the whole commit back. {@link #active} reads the current encounter for rules and tests.
 */
public final class EncounterWriter {
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
                            + "side, zone_id, health_current, focus_current, frozen) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                    frozen.id(), campaign, participant.id(), ordinal, participant.actorId(), participant.side().name(),
                    participant.zone(), local ? (Object) Math.toIntExact(participant.health()) : null,
                    local ? (Object) Math.toIntExact(participant.focus()) : null,
                    CanonicalJson.write(participant.canonicalForm()));
        }
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
