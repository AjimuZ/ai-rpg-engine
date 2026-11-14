package org.rpgengine.panels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.combat.EncounterObjective;
import org.rpgengine.combat.EncounterOutcome;
import org.rpgengine.combat.EncounterPhase;
import org.rpgengine.combat.EncounterState;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.combat.TacticalRules;
import org.rpgengine.commands.CommandReceipts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The player-safe combat projection behind the combat panel (P12.1, docs/13 section 7, docs/11 section 12): the active
 * fight's initiative order, the acting participant and its action points, the zone graph as it stands, every participant's
 * public standing and known conditions, the player's own fighting values, the defenses the player has learned by assessment,
 * the objectives with their status, the legal candidates with their costs and targets, and the verdict waiting for
 * settlement.
 *
 * <p><b>What it never holds.</b> Another participant's current Health, Focus, Guard, Resistance, armor, stance, reactions,
 * reaction policies, attacks, abilities, role values or initiative modifier; any effect's clock, absorption or source; a
 * check's difficulty or margin; a counteroffer not yet made; zone tags (author vocabulary); and anything from an
 * {@code INTERNAL} event. The only values of another participant it shows are those a successful assessment published in a
 * {@code PUBLIC} {@code ASSESSMENT_RESOLVED} event of this encounter, labelled with the round and revision they were learned
 * at, because Health changes afterwards and the panel must not present an old reading as current.
 *
 * <p><b>Consistency and writes.</b> The scope is authorized and every row is read in one read-only repeatable-read snapshot,
 * so the whole projection describes the {@link Scope#stateRevision} it names. Reading it never advances time, writes a row,
 * draws a die or starts work; it lists candidates, it does not submit one.
 */
@Service
public class CombatPanels {
    /** Most assessment events read for one encounter; a larger history fails closed rather than presenting a partial truth. */
    static final int MAX_ASSESSMENTS = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;

    /**
     * @param jdbc storage
     * @param manager transaction manager
     */
    public CombatPanels(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(15);
    }

    /**
     * Read the combat projection of one owned campaign.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign identity
     * @return the projection; its {@code encounter} is null when no fight is active
     * @throws PlayPanels.ScopeUnavailable for a missing, foreign or mismatched scope
     * @throws IllegalStateException inside an ambient transaction, for an unreadable stored fight or event, or when the
     *         assessment history exceeds {@value #MAX_ASSESSMENTS} events
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public CombatPanel read(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the combat panel requires an independent snapshot");
        }
        return Objects.requireNonNull(snapshot.execute(status -> project(owner, adventure, campaign)));
    }

    /** Authorize the scope and read the whole projection in the caller's snapshot. */
    private CombatPanel project(UUID owner, UUID adventure, UUID campaign) {
        jdbc.execute("set local statement_timeout = '5s'");
        var rows = jdbc.query("select c.mode, c.state_revision, c.content_revision_id from campaign c join adventure_workspace w "
                + "on w.id = c.adventure_id where c.id = ? and c.adventure_id = ? and w.owner_id = ?",
                (row, index) -> new Object[] {row.getString(1), row.getLong(2), row.getObject(3, UUID.class)},
                campaign, adventure, owner);
        if (rows.isEmpty()) {
            throw new PlayPanels.ScopeUnavailable();
        }
        Object[] row = rows.getFirst();
        var scope = new Scope(adventure, campaign, (UUID) row[2], (long) row[1]);
        var fight = EncounterWriter.active(jdbc, campaign);
        if (fight.isEmpty()) {
            return new CombatPanel(scope, (String) row[0], null);
        }
        EncounterState state = EncounterWriter.state(jdbc, campaign)
                .orElseThrow(() -> new IllegalStateException("active encounter unreadable in the panel snapshot"));
        return new CombatPanel(scope, (String) row[0], encounter(fight.get().templateId(), state,
                assessed(campaign, state)));
    }

    /**
     * The newest successful public assessment of each participant in this encounter. Only {@code PUBLIC} events count, and
     * only their {@code revealed} member, so an internal calculation can never feed the panel.
     *
     * @return known values by participant ID
     */
    private Map<String, Known> assessed(UUID campaign, EncounterState state) {
        var events = jdbc.query("select payload::text, state_revision from campaign_event where campaign_id = ? and "
                + "event_type = 'ASSESSMENT_RESOLVED' and visibility = 'PUBLIC' and payload ->> 'encounterId' = ? and "
                + "payload -> 'revealed' is not null order by sequence limit ?", (result, index) -> new Object[] {
                        result.getString(1), result.getLong(2)}, campaign, state.id().toString(), MAX_ASSESSMENTS + 1);
        if (events.size() > MAX_ASSESSMENTS) {
            throw new IllegalStateException("combat panel assessment limit exceeded");
        }
        var known = new HashMap<String, Known>();
        for (Object[] event : events) {
            JsonNode payload = JSON.readTree((String) event[0]);
            JsonNode revealed = payload.path("revealed");
            String target = payload.path("targetId").asString(null);
            if (target == null || state.participant(target) == null || !revealed.isObject()) {
                throw new IllegalStateException("unreadable assessment event");
            }
            known.put(target, new Known(number(revealed, "health"), number(revealed, "healthMax"),
                    number(revealed, "guard"), number(revealed, "resistance"), number(revealed, "armor"),
                    number(payload, "round"), (long) event[1]));
        }
        return known;
    }

    /** @return a required integer member of a stored public payload */
    private static int number(JsonNode node, String member) {
        JsonNode value = node.path(member);
        if (!value.isInt()) {
            throw new IllegalStateException("unreadable assessment value");
        }
        return value.intValue();
    }

    /**
     * Project a stored fight. The acting participant and its points follow the receipt's rule: set while an activation is
     * in progress and the fight has no verdict. The verdict is decided again from the stored state, as settlement does.
     */
    private static Encounter encounter(String templateId, EncounterState state, Map<String, Known> known) {
        boolean decided = state.phase() == EncounterPhase.OUTCOME_PENDING;
        EncounterState.Combatant acting = decided ? null : state.acting();
        var initiative = new ArrayList<Initiative>();
        for (EncounterState.Combatant participant : state.order()) {
            initiative.add(new Initiative(participant.rank(), participant.id(), participant.initiative()));
        }
        var space = state.space();
        var zones = new ArrayList<Zone>();
        space.zones().values().forEach(zone -> zones.add(new Zone(zone.id(), zone.cover().wire(), zone.obscured(),
                zone.capacity())));
        var edges = new ArrayList<Edge>();
        for (var edge : space.edges()) {
            int cost = space.stepCost(edge.from(), edge.to(), state.profile());
            edges.add(new Edge(edge.from(), edge.to(), edge.difficult(), edge.blocked(), cost < 0 ? null : cost));
        }
        var participants = new ArrayList<Participant>();
        for (EncounterState.Combatant participant : state.participants()) {
            var effects = participant.effects().stream().map(active -> new Effect(active.effectId(), active.stacks()))
                    .toList();
            participants.add(new Participant(participant.id(), participant.side().wire(), participant.zone(),
                    participant.status().name(), effects, participant.player() ? null : known.get(participant.id())));
        }
        var player = state.player();
        var own = new PlayerValues(player.health(), player.healthMax(), player.focus(), player.focusMax(),
                player.sheet().guard(), player.sheet().resistance(), player.sheet().armor(), player.guarding(),
                player.reactions());
        var progress = EncounterOutcome.progress(state);
        var objectives = new ArrayList<Objective>();
        for (EncounterObjective objective : state.objectives()) {
            objectives.add(new Objective(objective.id(), objective.kind().wire(), objective.zone(), objective.participant(),
                    objective.kind() == EncounterObjective.Kind.SURVIVE ? objective.rounds() : null,
                    objective.evaluate(progress).wire()));
        }
        // Options and verdict pass through the receipt's allowlist, so the panel shows them exactly as a receipt does.
        var summary = new LinkedHashMap<String, Object>();
        var options = TacticalRules.options(state);
        if (!options.isEmpty()) {
            summary.put("options", options);
        }
        if (decided) {
            summary.put("outcome", EncounterOutcome.decide(state).publicForm());
        }
        var view = CommandReceipts.tactical(summary);
        return new Encounter(state.id(), templateId, state.phase().name(), state.round(), state.profile().roundCap(),
                acting == null ? null : acting.id(), acting == null ? null : state.actionPoints(),
                state.profile().value("actionPoints"), state.playerDecision(), List.copyOf(initiative), List.copyOf(zones),
                List.copyOf(edges), List.copyOf(state.escapeZones()), List.copyOf(participants), own,
                List.copyOf(objectives), member(view, "options"), member(view, "outcome"));
    }

    /** @return a map member of the allowlisted view: an empty map for absent options, null for an absent verdict */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> member(Map<String, Object> view, String name) {
        Object value = view.get(name);
        if (value == null) {
            return "options".equals(name) ? Map.of() : null;
        }
        return (Map<String, Object>) value;
    }

    /**
     * The combat projection.
     *
     * @param scope identity and revision tokens; a client discards a projection of another scope or an older revision
     * @param mode campaign mode
     * @param encounter the active fight, or null without one
     */
    public record CombatPanel(Scope scope, String mode, Encounter encounter) {
    }

    /**
     * Identity and revision tokens.
     *
     * @param adventureId adventure
     * @param campaignId campaign
     * @param contentRevisionId pinned root revision
     * @param stateRevision committed revision the projection describes; a command built on it names this revision
     */
    public record Scope(UUID adventureId, UUID campaignId, UUID contentRevisionId, long stateRevision) {
    }

    /**
     * The active fight as the player may see it.
     *
     * @param encounterId encounter identity, the one {@code SETTLE_ENCOUNTER} names
     * @param templateId template the fight was entered from
     * @param phase stored lifecycle phase
     * @param round current round, 0 before initiative
     * @param roundCap frozen round limit
     * @param acting participant whose activation is in progress, null between activations and once the fight has a verdict
     * @param actionPoints the acting participant's remaining points, null whenever {@code acting} is
     * @param actionPointsPerActivation frozen points every activation starts with
     * @param playerDecision true only at the player's own {@code AWAITING_ACTION}; only then are {@code options} listed
     * @param initiative initiative order, first to act first; empty before initiative is rolled
     * @param zones the zones in ID order
     * @param edges directed edges with the current passage states
     * @param escapeZones frozen escape zones in ID order
     * @param participants every participant in roster order, the player first
     * @param player the player's own fighting values
     * @param objectives frozen objectives in declared order with their current status
     * @param options the player's legal candidates with costs, targets and disabled reasons ({@link TacticalRules#options}
     *        through the receipt allowlist {@link CommandReceipts#tactical}), member for member what a tactical receipt
     *        lists; empty when the player has no decision now
     * @param outcome verdict waiting for the player's explicit {@code SETTLE_ENCOUNTER} at {@code OUTCOME_PENDING}, in the
     *        receipt's form; otherwise null
     */
    public record Encounter(UUID encounterId, String templateId, String phase, int round, int roundCap, String acting,
            Integer actionPoints, int actionPointsPerActivation, boolean playerDecision, List<Initiative> initiative,
            List<Zone> zones, List<Edge> edges, List<String> escapeZones, List<Participant> participants, PlayerValues player,
            List<Objective> objectives, Map<String, Object> options, Map<String, Object> outcome) {
    }

    /**
     * One place in the initiative order.
     *
     * @param rank 0 acts first
     * @param participantId participant
     * @param initiative rolled total, public in the initiative event
     */
    public record Initiative(int rank, String participantId, int initiative) {
    }

    /**
     * One zone.
     *
     * @param id zone ID
     * @param cover {@code none}, {@code partial} or {@code full}
     * @param obscured whether visibility into the zone is obscured
     * @param capacity most occupants, 0 when unbounded
     */
    public record Zone(String id, String cover, boolean obscured, int capacity) {
    }

    /**
     * One directed edge as it stands now.
     *
     * @param from origin zone
     * @param to destination zone
     * @param difficult whether crossing costs the difficult-terrain multiple
     * @param blocked whether a passage is closed; a blocked edge still borders the zones for range and sight
     * @param cost action points one crossing costs, null while blocked
     */
    public record Edge(String from, String to, boolean difficult, boolean blocked, Integer cost) {
    }

    /**
     * One participant's public standing.
     *
     * @param participantId roster ID ({@code player} for the protagonist)
     * @param side {@code player}, {@code ally} or {@code hostile}
     * @param zone current zone
     * @param status {@code ACTIVE}, {@code DEFEATED}, {@code ESCAPED} or {@code SURRENDERED}
     * @param effects active effects with their stacks, as tactical receipts list them
     * @param known values the player learned by assessing this participant, null for the player and for a participant
     *        never assessed successfully in this encounter
     */
    public record Participant(String participantId, String side, String zone, String status, List<Effect> effects,
            Known known) {
    }

    /**
     * One active effect.
     *
     * @param effectId frozen effect definition
     * @param stacks current stacks
     */
    public record Effect(String effectId, int stacks) {
    }

    /**
     * The player's own fighting values. Guard, Resistance and armor are the sheet's (Guard and armor as last readied), before
     * any stance, cover or effect; {@code guarding} says whether the Guard action holds.
     *
     * @param health current Health above the pool's minimum
     * @param healthMax Health maximum above the pool's minimum
     * @param focus current Focus, 0 without a Focus mechanic
     * @param focusMax Focus maximum
     * @param guard Guard
     * @param resistance Resistance
     * @param armor armor
     * @param guarding whether the Guard action holds until the start of the next activation
     * @param reactionsLeft reactions left this round
     */
    public record PlayerValues(int health, int healthMax, int focus, int focusMax, int guard, int resistance, int armor,
            boolean guarding, int reactionsLeft) {
    }

    /**
     * Another participant's values as the newest successful assessment published them: the sheet's values before any
     * stance, cover or effect, and Health as it stood then.
     *
     * @param health Health when assessed
     * @param healthMax Health maximum
     * @param guard Guard
     * @param resistance Resistance
     * @param armor armor
     * @param round round of the assessment
     * @param stateRevision revision that committed the assessment
     */
    public record Known(int health, int healthMax, int guard, int resistance, int armor, int round, long stateRevision) {
    }

    /**
     * One objective.
     *
     * @param objectiveId objective ID
     * @param kind {@code defeatHostiles}, {@code escape}, {@code reachZone}, {@code survive} or {@code protect}
     * @param zone target zone of {@code reachZone}, otherwise null
     * @param participantId protected ally of {@code protect}, otherwise null
     * @param rounds rounds to survive for {@code survive}, otherwise null
     * @param status {@code pending}, {@code met} or {@code failed} on the stored state
     */
    public record Objective(String objectiveId, String kind, String zone, String participantId, Integer rounds,
            String status) {
    }
}
