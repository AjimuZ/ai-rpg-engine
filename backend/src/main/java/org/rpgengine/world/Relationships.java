package org.rpgengine.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.knowledge.KnowledgeLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the relationship rules of the pinned content inside one canonical commit (P15.1-B). Like
 * {@link NpcWorld} it is a static transaction participant: the caller holds the campaign row lock, and has already
 * written and flushed the operation's events and knowledge changes, so this reads the committing state and every
 * write rolls back with the operation.
 *
 * <p>In this order:
 * <ol>
 *   <li><b>Causes.</b> The typed facts of this revision that a rule can name are read in event-sequence order:
 *       the acting player's {@code SPEECH_DELIVERED}, {@code ITEM_TRANSFERRED} and {@code ITEM_TRADED} events toward
 *       an NPC, and every commitment resolved FULFILLED or BROKEN at this revision whose creditor is an NPC. For each
 *       cause the NPC's rules declare, the value of that dimension toward the other actor moves by the declared
 *       delta, clamped to its bounds. A change that leaves the value as it was is not recorded. Every recorded
 *       change is one immutable {@code relationship_change} row that cites its cause event (and, for a promise, the
 *       commitment), with the value before and after; the database refuses a cited event of another revision.</li>
 *   <li><b>Membership.</b> A change that brings a companion dimension to its threshold toward that actor is marked
 *       JOINED, and one that takes it below is marked LEFT. Membership is never stored apart from these rows: an NPC
 *       is a companion of the player exactly while the latest recorded value is at least the threshold.</li>
 *   <li><b>Following.</b> Each companion of the acting player that stood where the player's {@code ACTOR_MOVED}
 *       event started moves to where it ended, guarded by the actor's row version. Its schedule is suspended while
 *       it is a companion, so the returned set must be passed to {@link NpcWorld}.</li>
 * </ol>
 * A campaign whose pinned content declares no relationship rules returns before any query and writes nothing, so a
 * pack that does not enable the module behaves exactly as before the module existed. Reads never call this class.
 */
public final class Relationships {
    /** Most changes one commit records; more means the operation is not one a player command can produce. */
    public static final int MAX_CHANGES = 64;

    /** Static transaction participant only. */
    private Relationships() { }

    /**
     * What the commit changed.
     *
     * @param scopes query scopes written ({@code relationships:<npc>} for a recorded change, {@code actor:<npc>} for a
     *        companion that followed the player), in first-touch order
     * @param companions NPC actors that are companions of the acting player after this commit; their schedules do
     *        not apply
     */
    public record Outcome(Set<String> scopes, Set<UUID> companions) {
        /** Nothing changed and nobody follows the player. */
        public static final Outcome NONE = new Outcome(Set.of(), Set.of());

        /** Freeze both sets, keeping their order. */
        public Outcome {
            scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
            companions = Collections.unmodifiableSet(new LinkedHashSet<>(companions));
        }
    }

    /** One NPC actor with relationship rules, read under the campaign lock. */
    private static final class Npc {
        private final UUID id;
        private final RelationshipRules rules;
        private long rowVersion;
        private String location;

        private Npc(UUID id, RelationshipRules rules, String location, long rowVersion) {
            this.id = id;
            this.rules = rules;
            this.location = location;
            this.rowVersion = rowVersion;
        }
    }

    /** One typed fact of this revision that may cause a change; {@code commitment} is set for promises only. */
    private record Occurrence(long sequence, RelationshipRules.Trigger trigger, UUID holder, UUID subject,
            UUID commitment) { }

    /** Relationship key: who holds it, toward whom, along which dimension. */
    private record Key(UUID holder, UUID subject, String dimension) { }

    /**
     * Record this commit's relationship changes and move the acting player's companions with it.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked
     * @param rules relationship rules by canonical NPC definition ID, from the campaign's pinned catalog
     * @param actingPlayer the player whose registered intention commits, or null when the operation has none (then
     *        only resolved commitments can cause changes and nobody follows)
     * @param at provenance of the committing operation
     * @return the written scopes and the acting player's companions; {@link Outcome#NONE} when no rules exist
     * @throws IllegalStateException when more than {@link NpcWorld#MAX_ACTORS} NPC actors have relationship rules,
     *         more than {@link #MAX_CHANGES} changes would be recorded, or a companion row changed since it was read
     */
    public static Outcome applyWithin(JdbcTemplate jdbc, UUID campaign, Map<String, RelationshipRules> rules,
            UUID actingPlayer, KnowledgeLedger.Provenance at) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(at, "at");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("relationship rules require the caller's transaction");
        }
        if (rules.isEmpty()) {
            return Outcome.NONE;
        }
        Map<UUID, Npc> npcs = load(jdbc, campaign, rules);
        if (npcs.isEmpty()) {
            return Outcome.NONE;
        }
        var scopes = new LinkedHashSet<String>();
        var values = new HashMap<Key, Integer>();
        record(jdbc, campaign, npcs, occurrences(jdbc, campaign, actingPlayer, at), values, at, scopes);
        if (actingPlayer == null) {
            return new Outcome(scopes, Set.of());
        }
        var companions = new LinkedHashSet<UUID>();
        for (Npc npc : npcs.values()) {
            var companion = npc.rules.companion();
            if (companion != null && companion.holds(value(jdbc, campaign, npc, actingPlayer, companion.dimension(),
                    values))) {
                companions.add(npc.id);
            }
        }
        if (!companions.isEmpty()) {
            follow(jdbc, campaign, npcs, companions, actingPlayer, at, scopes);
        }
        return new Outcome(scopes, companions);
    }

    /** Read every NPC actor whose definition has relationship rules, in identity order. */
    private static Map<UUID, Npc> load(JdbcTemplate jdbc, UUID campaign, Map<String, RelationshipRules> rules) {
        var npcs = new LinkedHashMap<UUID, Npc>();
        jdbc.query("select id, definition_id, location_id, row_version from actor where campaign_id = ? "
                + "and kind = 'NPC' and definition_id is not null order by id", row -> {
                    RelationshipRules declared = rules.get(row.getString(2));
                    if (declared != null) {
                        if (npcs.size() == NpcWorld.MAX_ACTORS) {
                            throw new IllegalStateException("more NPC actors with relationship rules than one commit "
                                    + "evaluates");
                        }
                        UUID id = row.getObject(1, UUID.class);
                        npcs.put(id, new Npc(id, declared, row.getString(3), row.getLong(4)));
                    }
                }, campaign);
        return npcs;
    }

    /** The typed facts of this revision a cause can name, in event-sequence order. */
    private static List<Occurrence> occurrences(JdbcTemplate jdbc, UUID campaign, UUID player,
            KnowledgeLedger.Provenance at) {
        var found = new ArrayList<Occurrence>();
        if (player != null) {
            jdbc.query("select sequence, event_type, case event_type "
                    + "when 'SPEECH_DELIVERED' then payload ->> 'recipientActorId' "
                    + "when 'ITEM_TRANSFERRED' then payload ->> 'toActorId' "
                    + "else payload ->> 'counterpartyActorId' end from campaign_event "
                    + "where campaign_id = ? and state_revision = ? and actor_id = ? "
                    + "and event_type in ('SPEECH_DELIVERED', 'ITEM_TRANSFERRED', 'ITEM_TRADED') order by sequence",
                    row -> {
                        String other = row.getString(3);
                        if (other != null) {
                            var trigger = switch (row.getString(2)) {
                                case "SPEECH_DELIVERED" -> RelationshipRules.Trigger.SPOKEN_TO;
                                case "ITEM_TRANSFERRED" -> RelationshipRules.Trigger.GIFT;
                                default -> RelationshipRules.Trigger.TRADE;
                            };
                            found.add(new Occurrence(row.getLong(1), trigger, UUID.fromString(other), player, null));
                        }
                    }, campaign, at.revision(), player);
        }
        jdbc.query("select resolved_event_sequence, status, creditor_actor_id, debtor_actor_id, id from actor_commitment "
                + "where campaign_id = ? and resolved_revision = ? and status in ('FULFILLED', 'BROKEN') "
                + "order by resolved_event_sequence, id", row -> {
                    found.add(new Occurrence(row.getLong(1), "FULFILLED".equals(row.getString(2))
                            ? RelationshipRules.Trigger.PROMISE_KEPT : RelationshipRules.Trigger.PROMISE_BROKEN,
                            row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class)));
                }, campaign, at.revision());
        found.sort(Comparator.comparingLong(Occurrence::sequence).thenComparing(Occurrence::trigger)
                .thenComparing(occurrence -> occurrence.commitment() == null ? "" : occurrence.commitment().toString()));
        return found;
    }

    /** Apply every declared cause of every occurrence, recording the changes that move a value. */
    private static void record(JdbcTemplate jdbc, UUID campaign, Map<UUID, Npc> npcs, List<Occurrence> occurrences,
            Map<Key, Integer> values, KnowledgeLedger.Provenance at, Set<String> scopes) {
        int ordinal = 0;
        for (Occurrence occurrence : occurrences) {
            Npc holder = npcs.get(occurrence.holder());
            if (holder == null || holder.id.equals(occurrence.subject())) {
                continue;
            }
            for (RelationshipRules.Cause cause : holder.rules.causesOf(occurrence.trigger())) {
                var dimension = holder.rules.dimensions().get(cause.dimension());
                int before = value(jdbc, campaign, holder, occurrence.subject(), cause.dimension(), values);
                int after = dimension.clamp((long) before + cause.delta());
                if (after == before) {
                    continue;
                }
                if (ordinal == MAX_CHANGES) {
                    throw new IllegalStateException("more relationship changes than one commit records");
                }
                String membership = null;
                var companion = holder.rules.companion();
                if (companion != null && companion.dimension().equals(cause.dimension())
                        && companion.holds(before) != companion.holds(after)) {
                    membership = companion.holds(after) ? "JOINED" : "LEFT";
                }
                jdbc.update("insert into relationship_change (campaign_id, changed_revision, ordinal, holder_actor_id, "
                        + "subject_actor_id, dimension_id, cause, cause_event_sequence, commitment_id, delta, "
                        + "value_before, value_after, membership, changed_world_time) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", campaign, at.revision(), ordinal,
                        holder.id, occurrence.subject(), cause.dimension(), occurrence.trigger().name(),
                        occurrence.sequence(), occurrence.commitment(), cause.delta(), before, after, membership,
                        at.worldTime());
                values.put(new Key(holder.id, occurrence.subject(), cause.dimension()), after);
                scopes.add(scope(holder.id));
                ordinal++;
            }
        }
    }

    /** Current value of one relationship: this commit's latest change, else the latest recorded one, else initial. */
    private static int value(JdbcTemplate jdbc, UUID campaign, Npc holder, UUID subject, String dimension,
            Map<Key, Integer> values) {
        return values.computeIfAbsent(new Key(holder.id, subject, dimension), key -> {
            var recorded = jdbc.queryForList("select value_after from relationship_change where campaign_id = ? "
                    + "and holder_actor_id = ? and subject_actor_id = ? and dimension_id = ? "
                    + "order by changed_revision desc, ordinal desc limit 1", Integer.class, campaign, holder.id,
                    subject, dimension);
            return recorded.isEmpty() ? holder.rules.dimensions().get(dimension).initial() : recorded.getFirst();
        });
    }

    /** Move each companion that stood at the start of one of the player's moves in this revision to its end. */
    private static void follow(JdbcTemplate jdbc, UUID campaign, Map<UUID, Npc> npcs, Set<UUID> companions,
            UUID player, KnowledgeLedger.Provenance at, Set<String> scopes) {
        var moves = jdbc.query("select payload ->> 'from', payload ->> 'to' from campaign_event where campaign_id = ? "
                + "and state_revision = ? and actor_id = ? and event_type = 'ACTOR_MOVED' "
                + "and payload ->> 'actorId' = ? order by sequence", (row, index) -> new String[] {row.getString(1),
                        row.getString(2)}, campaign, at.revision(), player, player.toString());
        for (String[] move : moves) {
            for (UUID id : companions) {
                Npc npc = npcs.get(id);
                if (move[0] == null || move[1] == null || !move[0].equals(npc.location)) {
                    continue;
                }
                if (jdbc.update("update actor set location_id = ?, row_version = row_version + 1 where campaign_id = ? "
                        + "and id = ? and row_version = ?", move[1], campaign, npc.id, npc.rowVersion) != 1) {
                    throw new IllegalStateException("companion changed while it followed the player");
                }
                npc.location = move[1];
                npc.rowVersion++;
                scopes.add("actor:" + npc.id);
            }
        }
    }

    /**
     * @param holder NPC actor that holds relationships
     * @return query scope of every relationship that actor holds
     */
    public static String scope(UUID holder) {
        return "relationships:" + Objects.requireNonNull(holder, "holder");
    }
}
