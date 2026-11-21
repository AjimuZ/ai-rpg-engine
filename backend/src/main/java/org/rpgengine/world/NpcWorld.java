package org.rpgengine.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the NPC world rules of the pinned content inside one canonical commit (P15.1-A). It is a static
 * transaction participant: the caller holds the campaign row lock every canonical writer takes first and has
 * already written the operation's world time, placements, events and knowledge changes, so everything read
 * here is the committing state and every write rolls back with the operation.
 *
 * <p>In this order, after every commit that carries the pinned catalog:
 * <ol>
 *   <li><b>Schedules.</b> Every NPC actor whose definition has a schedule is placed where the schedule puts
 *       it at the committed world time. The row version steps, so plans that read the actor become stale;
 *       the move is not announced by an event, because the public journal must not reveal where unseen NPCs
 *       walk.</li>
 *   <li><b>Witnesses.</b> Every witness NPC at the acting player's location after the commit records an
 *       OBSERVED sighting of the player there. A witness that already holds a current first-hand sighting at
 *       the same place keeps it, so the sighting dates from when the witness first saw the player there.
 *       Concealment does not matter: a hidden NPC sees whoever stands beside it. A crowd larger than
 *       {@link KnowledgeLedger#MAX_OBSERVED} witnesses records nothing, like the player's own perception.</li>
 *   <li><b>Reports.</b> A witness's current first-hand sightings travel along its declared routes and arrive
 *       a fixed number of in-world minutes after the sighting. An arrival due by the committed world time is
 *       delivered as a TOLD sighting (the witness is the informant, the arrival time is when the recipient
 *       learned it) when the recipient's own knowledge of that subject was acquired before the witness's
 *       sighting. Reports are first-hand only: a TOLD sighting is never relayed again, so knowledge moves one
 *       declared hop and routes cannot echo. At most {@value #MAX_DELIVERIES} reports are delivered per commit
 *       in arrival order; the rest stay derivable from the witness's sighting and arrive in a later commit.</li>
 * </ol>
 * Nothing is evaluated on a read, and the wall clock is never consulted: presence and knowledge follow only
 * committed world time and committed sightings.
 */
public final class NpcWorld {
    /** Most NPC actors with authored world rules one commit evaluates; more fails instead of skipping some. */
    public static final int MAX_ACTORS = 512;
    /** Most reports one commit delivers; later arrivals wait for the next commit (bounded catch-up). */
    public static final int MAX_DELIVERIES = 64;

    /** Static transaction participant only. */
    private NpcWorld() { }

    /** One NPC actor with world rules, as read under the campaign lock; the location follows schedule moves. */
    private static final class Npc {
        private final UUID id;
        private final NpcRules rules;
        private final long rowVersion;
        private String location;

        private Npc(UUID id, NpcRules rules, String location, long rowVersion) {
            this.id = id;
            this.rules = rules;
            this.location = location;
            this.rowVersion = rowVersion;
        }
    }

    /** One report due by the committed world time, before the recipient's knowledge is checked. */
    private record Arrival(long arrivalTime, long sightingTime, UUID informant, UUID recipient, UUID subject,
            String location) { }

    /** One current first-hand sighting of a witness: whom, where, and since when in world time. */
    private record Seen(UUID subject, String location, long worldTime) { }

    /**
     * Apply schedules, witnesses and reports for one committing operation.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked
     * @param npcs world rules by canonical NPC definition ID, from the campaign's pinned catalog
     * @param actingPlayer the player whose registered intention commits, or null when the operation has none
     *        (then nobody is witnessed, but schedules and reports still follow the clock)
     * @param at provenance of the committing operation; its first event is the source of every sighting
     * @return the query scopes written ({@code actor:<npc>} for a schedule move, {@code sightings:<npc>} for a
     *         witnessed or delivered sighting), in first-touch order
     * @throws IllegalStateException when more than {@link #MAX_ACTORS} NPC actors have world rules, or when an
     *         NPC row changed since it was read in this transaction
     */
    public static Set<String> advanceWithin(JdbcTemplate jdbc, UUID campaign, Map<String, NpcRules> npcs,
            UUID actingPlayer, KnowledgeLedger.Provenance at) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(at, "at");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("world rules require the caller's transaction");
        }
        var scopes = new LinkedHashSet<String>();
        if (npcs.values().stream().noneMatch(NpcRules::active)) {
            return scopes;
        }
        List<Npc> actors = load(jdbc, campaign, npcs);
        follow(jdbc, campaign, actors, at.worldTime(), scopes);
        if (actingPlayer != null) {
            witness(jdbc, campaign, actors, actingPlayer, at, scopes);
        }
        report(jdbc, campaign, actors, at, scopes);
        return scopes;
    }

    /** Read every NPC actor whose definition has active rules, in identity order. */
    private static List<Npc> load(JdbcTemplate jdbc, UUID campaign, Map<String, NpcRules> npcs) {
        var actors = new ArrayList<Npc>();
        jdbc.query("select id, definition_id, location_id, row_version from actor where campaign_id = ? "
                + "and kind = 'NPC' and definition_id is not null order by id", row -> {
                    NpcRules rules = npcs.getOrDefault(row.getString(2), NpcRules.NONE);
                    if (rules.active()) {
                        if (actors.size() == MAX_ACTORS) {
                            throw new IllegalStateException("more NPC actors with world rules than one commit evaluates");
                        }
                        actors.add(new Npc(row.getObject(1, UUID.class), rules, row.getString(3), row.getLong(4)));
                    }
                }, campaign);
        return actors;
    }

    /** Place every scheduled NPC where its routine puts it now; unchanged rows are not written. */
    private static void follow(JdbcTemplate jdbc, UUID campaign, List<Npc> actors, long worldTime,
            Set<String> scopes) {
        for (Npc npc : actors) {
            if (npc.rules.schedule() == null) {
                continue;
            }
            String target = npc.rules.schedule().locationAt(worldTime);
            if (target.equals(npc.location)) {
                continue;
            }
            if (jdbc.update("update actor set location_id = ?, row_version = row_version + 1 where campaign_id = ? "
                    + "and id = ? and row_version = ?", target, campaign, npc.id, npc.rowVersion) != 1) {
                throw new IllegalStateException("NPC changed while its schedule was applied");
            }
            npc.location = target;
            scopes.add("actor:" + npc.id);
        }
    }

    /** Record what witnesses beside the acting player see. */
    private static void witness(JdbcTemplate jdbc, UUID campaign, List<Npc> actors, UUID player,
            KnowledgeLedger.Provenance at, Set<String> scopes) {
        var located = jdbc.queryForList("select location_id from actor where campaign_id = ? and id = ?", String.class,
                campaign, player);
        String here = located.isEmpty() ? null : located.getFirst();
        if (here == null) {
            return;
        }
        var present = actors.stream().filter(npc -> npc.rules.witness() && here.equals(npc.location)
                && !npc.id.equals(player)).toList();
        if (present.size() > KnowledgeLedger.MAX_OBSERVED) {
            return;
        }
        var changes = new ArrayList<KnowledgeChange>();
        for (Npc npc : present) {
            // A current first-hand sighting at this very place is kept: the witness has been watching since then.
            boolean current = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from actor_sighting "
                    + "where campaign_id = ? and observer_actor_id = ? and subject_actor_id = ? and location_id = ? "
                    + "and acquisition = 'OBSERVED' and absence_revision is null)", Boolean.class, campaign, npc.id,
                    player, here));
            if (!current) {
                changes.add(new KnowledgeChange.Sighting(npc.id, player, here, null, 0));
            }
        }
        scopes.addAll(KnowledgeLedger.applyWithin(jdbc, campaign, at, changes));
    }

    /** Deliver the reports due by now, earliest arrival first, up to the per-commit bound. */
    private static void report(JdbcTemplate jdbc, UUID campaign, List<Npc> actors, KnowledgeLedger.Provenance at,
            Set<String> scopes) {
        var due = new ArrayList<Arrival>();
        for (Npc informant : actors) {
            if (informant.rules.reports().isEmpty()) {
                continue;
            }
            List<Seen> seen = jdbc.query("select subject_actor_id, location_id, observed_world_time from actor_sighting "
                    + "where campaign_id = ? and observer_actor_id = ? and acquisition = 'OBSERVED' "
                    + "and absence_revision is null order by subject_actor_id limit ?",
                    (row, index) -> new Seen(row.getObject(1, UUID.class), row.getString(2), row.getLong(3)),
                    campaign, informant.id, KnowledgeLedger.MAX_OBSERVED);
            for (Seen sighting : seen) {
                for (NpcRules.Report route : informant.rules.reports()) {
                    long arrival = sighting.worldTime() + route.delaySeconds();
                    if (arrival > at.worldTime()) {
                        continue;
                    }
                    for (UUID recipient : recipients(jdbc, campaign, route.recipient(), informant.id,
                            sighting.subject())) {
                        due.add(new Arrival(arrival, sighting.worldTime(), informant.id, recipient, sighting.subject(),
                                sighting.location()));
                    }
                }
            }
        }
        due.sort(Comparator.comparingLong(Arrival::arrivalTime).thenComparing(Arrival::informant)
                .thenComparing(Arrival::recipient).thenComparing(Arrival::subject));
        int delivered = 0;
        for (Arrival arrival : due) {
            if (delivered == MAX_DELIVERIES) {
                break;
            }
            var known = jdbc.queryForList("select observed_world_time from actor_sighting where campaign_id = ? "
                    + "and observer_actor_id = ? and subject_actor_id = ?", Long.class, campaign, arrival.recipient(),
                    arrival.subject());
            // Only news travels: a recipient that learned of the subject at or after this sighting keeps its own.
            if (!known.isEmpty() && known.getFirst() >= arrival.sightingTime()) {
                continue;
            }
            var told = new KnowledgeLedger.Provenance(at.revision(), arrival.arrivalTime(), at.firstSequence(),
                    at.eventCount());
            scopes.addAll(KnowledgeLedger.applyWithin(jdbc, campaign, told, List.of(new KnowledgeChange.Sighting(
                    arrival.recipient(), arrival.subject(), arrival.location(), arrival.informant(), 0))));
            delivered++;
        }
    }

    /**
     * NPC actors created from the recipient definition, other than the informant and the subject, in identity
     * order. A recipient needs no world rules of its own, so it is read here rather than from the loaded set.
     */
    private static List<UUID> recipients(JdbcTemplate jdbc, UUID campaign, String definition, UUID informant,
            UUID subject) {
        return jdbc.queryForList("select id from actor where campaign_id = ? and kind = 'NPC' and definition_id = ? "
                + "and id <> ? and id <> ? order by id limit ?", UUID.class, campaign, definition, informant, subject,
                MAX_ACTORS);
    }
}
