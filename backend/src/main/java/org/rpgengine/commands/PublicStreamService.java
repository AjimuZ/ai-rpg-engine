package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.presentation.PresentationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read side of the resumable public operation stream (P07.5, docs/13 section 4). The V11
 * journal trigger appends one {@code campaign_stream_message} per operation status change in the
 * transaction that made it, so the log is complete and never contains an uncommitted change. The
 * V26 trigger appends one more per stored presentation event, in the same way.
 * This service turns a client cursor into the next ordered batch and renders each message from
 * the immutable journal outcome with the same allowlist as the status API.
 *
 * <p>Message identity is {@code <recoveryGeneration>:<sequence>}. A cursor from another
 * generation (a restored save), beyond the campaign's head (another timeline) or older than the
 * retained window (the newest 1000 messages) cannot be resumed: the batch then starts with a
 * {@code stream.reset} control message carrying the head identity and current revision, and the
 * client must reload its complete projection. A connection without a cursor starts at the head
 * with a {@code stream.ready} control message. Delivery is at-least-once: the same identity always
 * renders the same content, so clients deduplicate by identity and never replay an action.
 *
 * <p>Rendered data is limited to campaign and operation identity, command type, status,
 * operation version and state revision; {@code state.committed} adds the player-safe receipt
 * projection ({@link CommandReceipts}), {@code narrative.pending} a fixed reason code and
 * {@code presentation.event} a delivery identity plus the allowlisted fields of one admitted cue.
 * Payloads, hashes, internal events, other actors' values, draw identities and permits never appear.
 * Reads are owner-scoped, read-only and change no row.
 *
 * <p>A cue (P05.7-B.1.1) is one more message of the same log: the V26 trigger appends it when
 * {@link org.rpgengine.presentation.PresentationEventStore} stores the event, so it shares the
 * sequence, retention, cursor and reset rules. Its {@code id} stays a plain cursor, and clients
 * that keep cues across connections deduplicate by {@code deliveryId}. Since P05.7-B.1.2.3.2 the
 * settlement of a publication that carries cues writes them through that store, ahead of its
 * {@code narrative.ready} message.
 */
@Service
public class PublicStreamService {
    /** Maximum messages per batch; a longer backlog continues from the last delivered identity. */
    public static final int MAX_BATCH = 100;
    /** Retained messages per campaign; must match the retention of the V11 and V26 triggers. */
    public static final int RETAINED = 1000;
    /** Stream type of one admitted presentation event. */
    public static final String PRESENTATION = "presentation.event";
    private static final Pattern CURSOR = Pattern.compile("(0|[1-9][0-9]{0,17}):(0|[1-9][0-9]{0,17})");
    private static final Set<String> REASONS = Set.of("NARRATION_UNAVAILABLE", "NARRATION_CANCELLED",
            "NARRATION_FAILED", "GENERATION_RETIRED");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;

    /**
     * Wire read-only persistence; missing dependencies fail startup.
     *
     * @param jdbc JDBC access joined to the read-only transaction
     * @param manager shared transaction manager
     */
    public PublicStreamService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        reads.setTimeout(10);
    }

    /**
     * Read the batch after a cursor.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign whose stream is read
     * @param cursor last delivered identity ({@code generation:sequence}), or null to start at the head
     * @return ordered messages, possibly starting with a control message; never null
     * @throws CampaignCommandJson.Malformed if the cursor is not a canonical identity
     * @throws OperationAdmissionFailure {@code SCOPE_UNAVAILABLE} for missing or foreign identities
     * @throws IllegalStateException if called inside an ambient transaction
     */
    public Batch read(UUID owner, UUID adventure, UUID campaign, String cursor) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        long[] position = cursor == null ? null : parse(cursor);
        MutationLeaseService.requireNoTransaction();
        // REPEATABLE READ gives head, window and messages from one snapshot.
        return reads.execute(status -> batch(owner, adventure, campaign, position));
    }

    /** Parse a canonical {@code generation:sequence} identity; anything else is malformed. */
    private static long[] parse(String cursor) {
        var match = CURSOR.matcher(cursor);
        if (!match.matches()) {
            throw new CampaignCommandJson.Malformed();
        }
        return new long[] {Long.parseLong(match.group(1)), Long.parseLong(match.group(2))};
    }

    /** Snapshot body of {@link #read}. */
    private Batch batch(UUID owner, UUID adventure, UUID campaign, long[] position) {
        jdbc.execute("set local statement_timeout = '5s'");
        List<long[]> scope = jdbc.query("select c.recovery_generation, c.state_revision from campaign c "
                        + "join adventure_workspace a on a.id = c.adventure_id where c.id = ? and c.adventure_id = ? "
                        + "and a.owner_id = ?", (row, index) -> new long[] {row.getLong(1), row.getLong(2)},
                campaign, adventure, owner);
        if (scope.isEmpty()) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE);
        }
        long generation = scope.getFirst()[0];
        long revision = scope.getFirst()[1];
        long head = jdbc.query("select last_sequence from campaign_stream_head where campaign_id = ?",
                (row, index) -> row.getLong(1), campaign).stream().findFirst().orElse(0L);
        String headId = generation + ":" + head;
        if (position == null) {
            return new Batch(List.of(control("stream.ready", headId, campaign, revision, null)));
        }
        Long oldest = jdbc.queryForObject("select min(sequence) from campaign_stream_message where campaign_id = ?",
                Long.class, campaign);
        // Resumable only inside this generation's retained window: next expected message must still exist.
        String reset = position[0] != generation ? "GENERATION_CHANGED"
                : position[1] > head ? "CURSOR_UNKNOWN"
                : position[1] < head && (oldest == null || position[1] < oldest - 1) ? "CURSOR_EXPIRED" : null;
        if (reset != null) {
            return new Batch(List.of(control("stream.reset", headId, campaign, revision, reset)));
        }
        var messages = new ArrayList<Message>();
        jdbc.query("select m.sequence, m.campaign_generation, m.message_type, m.status, m.operation_id, "
                        + "m.operation_version, m.state_revision, o.command_type, "
                        + "case when m.message_type = 'state.committed' then o.receipt::text end, j.outcome_code, "
                        + "e.kind, e.ordinal, e.committed_revision, e.scene_id, e.actor_id, e.pose_id, e.expression_id, "
                        + "e.line_id, e.asset_id "
                        + "from campaign_stream_message m join operation_journal o "
                        + "on o.campaign_id = m.campaign_id and o.id = m.operation_id "
                        + "left join presentation_job j on j.campaign_id = m.campaign_id and j.operation_id = m.operation_id "
                        + "and j.kind = 'NARRATION' and j.round = 1 "
                        + "left join presentation_event e on e.campaign_id = m.campaign_id and e.operation_id = m.operation_id "
                        + "and e.ordinal = m.presentation_ordinal where m.campaign_id = ? and m.sequence > ? "
                        + "order by m.sequence limit ?",
                row -> {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("campaignId", campaign.toString());
                    data.put("operationId", row.getObject(5, UUID.class).toString());
                    data.put("commandType", row.getString(8));
                    data.put("status", row.getString(4));
                    data.put("operationVersion", row.getLong(6));
                    data.put("stateRevision", row.getLong(7));
                    String type = row.getString(3);
                    String id = row.getLong(2) + ":" + row.getLong(1);
                    if (type.equals(PRESENTATION)) {
                        // Rebuilt from the typed columns only; V26 keeps every stored row a valid event.
                        var event = new PresentationEvent(PresentationEvent.Kind.valueOf(row.getString(11)),
                                row.getObject(5, UUID.class), row.getLong(13), row.getInt(12), row.getString(14),
                                row.getObject(15, UUID.class), row.getString(16), row.getString(17), row.getString(18),
                                row.getString(19));
                        data.put("deliveryId", event.deliveryId(id));
                        data.putAll(event.publicFields());
                    } else if (type.equals("state.committed")) {
                        data.put("receipt", CommandReceipts.project(row.getString(9)));
                    } else if (type.equals("narrative.pending")) {
                        // Only a settled job's write-once outcome names a reason; legacy recovery was an interruption.
                        String outcome = row.getString(10);
                        data.put("reason", outcome != null && REASONS.contains(outcome) ? outcome : "NARRATION_INTERRUPTED");
                    }
                    messages.add(new Message(id, type, data));
                }, campaign, position[1], MAX_BATCH);
        return new Batch(messages);
    }

    /** Build a control message positioned at the head. */
    private static Message control(String type, String headId, UUID campaign, long revision, String reason) {
        var data = new LinkedHashMap<String, Object>();
        data.put("campaignId", campaign.toString());
        data.put("stateRevision", revision);
        if (reason != null) {
            data.put("reason", reason);
        }
        return new Message(headId, type, data);
    }

    /**
     * One rendered stream message.
     *
     * @param id durable identity {@code generation:sequence}; control messages carry the head
     * @param type message type, for example {@code state.committed}, {@link #PRESENTATION} or {@code stream.reset}
     * @param data allowlisted JSON object
     */
    public record Message(String id, String type, Map<String, Object> data) {
        /** Freeze the rendered data. */
        public Message {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    /**
     * Ordered messages after a cursor.
     *
     * @param messages at most {@link #MAX_BATCH} messages, possibly one control message only
     */
    public record Batch(List<Message> messages) {
        /** Freeze the list. */
        public Batch {
            messages = List.copyOf(messages);
        }

        /** @return identity of the last message, or null when the batch is empty */
        public String last() {
            return messages.isEmpty() ? null : messages.getLast().id();
        }
    }
}
