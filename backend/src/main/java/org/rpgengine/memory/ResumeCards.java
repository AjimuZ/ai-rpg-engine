package org.rpgengine.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.commands.CommandReceipts;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.content.CampaignDependencies;
import org.rpgengine.content.ClosureDiagnosis;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.presentation.PresentationJobService;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The short factual resume card of a campaign (P09.5, docs/08 section 10): where play stands,
 * read from the last committed campaign state, never from the last chat message, and without
 * advancing time or writing anything.
 *
 * <p><b>Content.</b> The player's current location (its public display name when the pinned root
 * pack has one), the actors the player last saw there and has not since found absent (the
 * player's knowledge, never the actors' real positions), the player's resources, the active
 * encounter (always null until P11), the pending decision ({@code STARTING_SITUATION},
 * {@code FREE_INPUT} or {@code OTHER}, without its stored payload), the committed actions still
 * awaiting narration with their player-safe receipts and presentation state, the pinned content
 * diagnosis with whether mutation is paused, and continuity diagnostics (checkpoint verification
 * and summary freshness codes, never their content).
 *
 * <p><b>Consistency and writes.</b> Canonical rows are read in one read-only repeatable-read
 * snapshot after the owner, adventure and campaign scope is authorized. The content diagnosis
 * (cached when available), the display name (cached localization) and the continuity codes are
 * read afterwards in their own read-only work; none of them writes: no checkpoint is captured, no
 * summary is requested, no job, journal, transcript or counter changes.
 */
@Service
public class ResumeCards {
    /** Most actions awaiting narration listed; the total is always reported. */
    public static final int MAX_AWAITING = 16;
    /** Most rows of one list; a larger list fails closed rather than presenting a partial truth. */
    static final int MAX_ROWS = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final CampaignDependencies dependencies;
    private final PackGlossaries glossaries;
    private final OperationalCheckpoints checkpoints;

    /**
     * @param jdbc storage
     * @param manager transaction manager
     * @param dependencies pinned content diagnosis
     * @param glossaries cached pinned localization for the location name
     * @param checkpoints checkpoint verification (read-only)
     */
    public ResumeCards(JdbcTemplate jdbc, PlatformTransactionManager manager, CampaignDependencies dependencies,
            PackGlossaries glossaries, OperationalCheckpoints checkpoints) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.glossaries = Objects.requireNonNull(glossaries, "glossaries");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(15);
    }

    /**
     * Read the resume card of one owned campaign.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign identity
     * @return the card
     * @throws ScopeUnavailable for a missing, foreign or mismatched scope
     * @throws IllegalStateException inside an ambient transaction, or when a list exceeds
     *         {@value #MAX_ROWS} rows
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public ResumeCard read(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the resume card requires an independent snapshot");
        }
        Canonical state = Objects.requireNonNull(snapshot.execute(status -> canonical(owner, adventure, campaign)));
        ClosureDiagnosis content = dependencies.check(adventure, state.revisionId());
        Location location = null;
        if (state.locationId() != null) {
            var name = glossaries.name(adventure, state.revisionId(), state.locale(), state.locationId()).orElse(null);
            location = new Location(state.locationId(), name == null ? null : name.text(),
                    name == null ? null : name.locale(), name != null && name.fallback());
        }
        var verification = checkpoints.verify(campaign);
        Continuity continuity = Objects.requireNonNull(snapshot.execute(status -> new Continuity(
                verification.status().name(), summaryState(campaign, Audience.PUBLIC))));
        return new ResumeCard(new Scope(adventure, campaign, state.revisionId(), state.stateRevision()), state.title(),
                state.mode(), state.gameTurn(), state.worldTime(), location, state.present(), state.resources(), null,
                state.decision(), state.awaitingTotal(), state.awaiting(),
                new Dependencies(content.status().name(), content.pausesMutation(), content.issues().stream()
                        .map(issue -> new DependencyIssue(issue.code().name(), issue.packId(), issue.packVersion()))
                        .toList()),
                continuity);
    }

    /** Authorize the scope and read every canonical part of the card in the caller's snapshot. */
    private Canonical canonical(UUID owner, UUID adventure, UUID campaign) {
        jdbc.execute("set local statement_timeout = '5s'");
        var rows = jdbc.query("select c.title, c.mode, c.state_revision, c.game_turn, c.world_time_seconds, "
                + "c.content_revision_id, c.pending_decision::text, coalesce(p.narrative_locale, w.source_locale) "
                + "from campaign c join adventure_workspace w on w.id = c.adventure_id left join "
                + "presentation_campaign_settings p on p.campaign_id = c.id where c.id = ? and c.adventure_id = ? "
                + "and w.owner_id = ?", (row, index) -> new Object[] {row.getString(1), row.getString(2), row.getLong(3),
                        row.getLong(4), row.getLong(5), row.getObject(6, UUID.class), row.getString(7), row.getString(8)},
                campaign, adventure, owner);
        if (rows.isEmpty()) {
            throw new ScopeUnavailable();
        }
        Object[] row = rows.getFirst();
        String stored = (String) row[6];
        String decision = switch (SessionRehydration.describe(stored == null ? null : JSON.readTree(stored))) {
            case "free-input" -> "FREE_INPUT";
            case "starting-situation" -> "STARTING_SITUATION";
            default -> "OTHER";
        };
        // The creation-bound player only; a legacy campaign without one has no location or resources.
        var players = jdbc.query("select a.id, a.location_id from campaign_creation cc join actor a on a.campaign_id = "
                + "cc.campaign_id and a.id = cc.actor_id where cc.adventure_id = ? and cc.campaign_id = ?",
                (result, index) -> new Object[] {result.getObject(1, UUID.class), result.getString(2)}, adventure, campaign);
        UUID player = players.isEmpty() ? null : (UUID) players.getFirst()[0];
        String here = players.isEmpty() ? null : (String) players.getFirst()[1];
        List<PresentActor> present = List.of();
        List<Resource> resources = List.of();
        if (player != null) {
            // Someone the player saw here and has not since found absent is present as far as the
            // player knows (the same rule as the context envelope); real positions are never read.
            present = bounded(jdbc.query("select s.subject_actor_id, a.display_name from actor_sighting s join actor a on "
                    + "a.campaign_id = s.campaign_id and a.id = s.subject_actor_id where s.campaign_id = ? and "
                    + "s.observer_actor_id = ? and s.location_id = ? and s.absence_revision is null "
                    + "order by a.display_name, s.subject_actor_id limit ?", (result, index) -> new PresentActor(
                            result.getObject(1, UUID.class), result.getString(2)), campaign, player, here, MAX_ROWS + 1));
            resources = bounded(jdbc.query("select resource_key, minimum_value, current_value, maximum_value from "
                    + "actor_resource where campaign_id = ? and actor_id = ? order by resource_key limit ?",
                    (result, index) -> new Resource(result.getString(1), result.getLong(2), result.getLong(3),
                            result.getLong(4)), campaign, player, MAX_ROWS + 1));
        }
        String owed = "from operation_journal o where o.campaign_id = ? and o.status in ('COMMITTED', 'NARRATING', "
                + "'COMMITTED_NARRATION_PENDING') and not exists(select 1 from narration_publication p where "
                + "p.campaign_id = o.campaign_id and p.operation_id = o.id)";
        int total = Objects.requireNonNull(jdbc.queryForObject("select count(*) " + owed, Integer.class, campaign));
        var awaiting = new ArrayList<Awaiting>();
        jdbc.query("select o.id, o.command_type, o.status, o.committed_revision, o.receipt::text " + owed
                + " order by o.committed_revision desc, o.id limit ?", result -> {
                    UUID operation = result.getObject(1, UUID.class);
                    OperationStatus journal = OperationStatus.valueOf(result.getString(3));
                    String receipt = result.getString(5);
                    awaiting.add(new Awaiting(operation, result.getString(2), journal.name(), result.getLong(4),
                            receipt == null ? null : CommandReceipts.project(receipt), null));
                }, campaign, MAX_AWAITING);
        var presented = new ArrayList<Awaiting>();
        for (Awaiting entry : awaiting) {
            var rounds = jdbc.query("select status, outcome_code from presentation_job where campaign_id = ? and "
                    + "operation_id = ? and kind = 'NARRATION' order by round", (result, index) -> new String[] {
                            result.getString(1), result.getString(2)}, campaign, entry.operationId());
            var view = PresentationJobService.Presentation.of(OperationStatus.valueOf(entry.status()), rounds, null);
            presented.add(entry.with(new Narration(view.mode().name(), view.reason(), view.retriesRemaining())));
        }
        return new Canonical((String) row[0], (String) row[1], (long) row[2], (long) row[3], (long) row[4],
                (UUID) row[5], decision, SupportedLocale.fromTag((String) row[7]), here, present, resources, total,
                List.copyOf(presented));
    }

    /** @return the audience's summary state, read in the caller's snapshot */
    private String summaryState(UUID campaign, Audience audience) {
        String wire = MemorySummaries.audienceWire(audience);
        boolean any = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from memory_summary where "
                + "campaign_id = ? and audience = ?)", Boolean.class, campaign, wire));
        if (!any) {
            return "NONE";
        }
        return MemorySummaries.usableWithin(jdbc, campaign, wire).map(usable -> usable.freshness().status().name())
                .orElse("STALE");
    }

    /** Fail closed rather than presenting a silently incomplete list. */
    private static <T> List<T> bounded(List<T> rows) {
        if (rows.size() > MAX_ROWS) {
            throw new IllegalStateException("resume card limit exceeded");
        }
        return List.copyOf(rows);
    }

    /** Canonical part of the card, read in one snapshot. */
    private record Canonical(String title, String mode, long stateRevision, long gameTurn, long worldTime,
            UUID revisionId, String decision, SupportedLocale locale, String locationId, List<PresentActor> present,
            List<Resource> resources, int awaitingTotal, List<Awaiting> awaiting) {
    }

    /**
     * The resume card.
     *
     * @param scope identity and revision tokens
     * @param title campaign title
     * @param mode campaign mode
     * @param gameTurn game turn
     * @param worldTimeSeconds world time
     * @param location the player's current location, null without a creation player or location
     * @param presentActors actors the player knows to be here, by name
     * @param resources the player's resources with exact bounds
     * @param encounter the active encounter, always null until P11
     * @param pendingDecision {@code STARTING_SITUATION}, {@code FREE_INPUT} or {@code OTHER}
     * @param awaitingNarrationTotal committed actions whose narration is not published
     * @param awaitingNarration the newest {@value #MAX_AWAITING} of them
     * @param dependencies pinned content diagnosis
     * @param continuity checkpoint and summary codes
     */
    public record ResumeCard(Scope scope, String title, String mode, long gameTurn, long worldTimeSeconds,
            Location location, List<PresentActor> presentActors, List<Resource> resources, Object encounter,
            String pendingDecision, int awaitingNarrationTotal, List<Awaiting> awaitingNarration,
            Dependencies dependencies, Continuity continuity) {
    }

    /**
     * Identity and revision tokens; a client discards a card of another scope.
     *
     * @param adventureId adventure
     * @param campaignId campaign
     * @param contentRevisionId pinned root revision
     * @param stateRevision committed revision the card describes
     */
    public record Scope(UUID adventureId, UUID campaignId, UUID contentRevisionId, long stateRevision) {
    }

    /**
     * The player's location.
     *
     * @param id location definition id
     * @param name public display name, null when the pinned root pack has none (or cannot be loaded)
     * @param nameLocale language of the name, null without one
     * @param nameFallback true when the name is in the source language because no overlay exists
     */
    public record Location(String id, String name, String nameLocale, boolean nameFallback) {
    }

    /**
     * An actor the player last saw at the player's location.
     *
     * @param actorId actor identity
     * @param name display name
     */
    public record PresentActor(UUID actorId, String name) {
    }

    /**
     * One player resource.
     *
     * @param key resource key
     * @param minimum inclusive minimum
     * @param current current value
     * @param maximum inclusive maximum
     */
    public record Resource(String key, long minimum, long current, long maximum) {
    }

    /**
     * A committed action whose narration is not published.
     *
     * @param operationId operation identity
     * @param commandType admitted command type
     * @param status journal status: {@code COMMITTED}, {@code NARRATING} or {@code COMMITTED_NARRATION_PENDING}
     * @param committedRevision revision of its commit
     * @param receipt player-safe receipt projection, the mechanical recap while narration is owed
     * @param narration presentation state
     */
    public record Awaiting(UUID operationId, String commandType, String status, long committedRevision,
            Map<String, Object> receipt, Narration narration) {
        /** @return this entry with its presentation state */
        Awaiting with(Narration value) {
            return new Awaiting(operationId, commandType, status, committedRevision, receipt, value);
        }
    }

    /**
     * Presentation state of an action awaiting narration.
     *
     * @param mode {@code IN_PROGRESS} (a round is queued or running and resumes by itself) or
     *        {@code RECEIPT} (narration ended pending; the receipt is the presentation)
     * @param reason public reason of the receipt fallback, null while in progress
     * @param retriesRemaining narration-only retries still allowed
     */
    public record Narration(String mode, String reason, int retriesRemaining) {
    }

    /**
     * Pinned content diagnosis.
     *
     * @param status {@code AVAILABLE}, {@code UNSEALED} or {@code MISSING}
     * @param mutationPaused true when fresh commands are refused until the content is restored or imported
     * @param issues fixed codes with public pack coordinates
     */
    public record Dependencies(String status, boolean mutationPaused, List<DependencyIssue> issues) {
    }

    /**
     * One missing or unusable pinned pack.
     *
     * @param code fixed issue code
     * @param packId pack identity, null when unknown
     * @param packVersion pack version, null when unknown
     */
    public record DependencyIssue(String code, String packId, String packVersion) {
    }

    /**
     * Continuity diagnostics: codes only, never checkpoint or summary content.
     *
     * @param checkpoint verification status of the newest operational checkpoint
     * @param publicSummary {@code NONE}, {@code CURRENT}, {@code BEHIND} or {@code STALE}
     */
    public record Continuity(String checkpoint, String publicSummary) {
    }

    /** Missing, foreign or mismatched scope; carries no identity. */
    public static final class ScopeUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Fixed message without scope input. */
        public ScopeUnavailable() {
            super("campaign scope unavailable", null, false, false);
        }
    }
}
