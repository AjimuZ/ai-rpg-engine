package org.rpgengine.narration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.inference.contract.DisclosureGuard;
import org.rpgengine.inference.contract.KnownActor;
import org.rpgengine.inference.contract.NarrationContext;
import org.rpgengine.inference.contract.PublicFact;
import org.rpgengine.inference.contract.TaskBinding;
import org.rpgengine.presentation.NarrationPort;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the public narration context of one committed operation from stored canonical data
 * (P08.5), in one read-only repeatable-read snapshot, so every part describes the same commit.
 *
 * <p>Audience rule (ADR 0007, docs/06 section 7): the context is assembled only from
 * <ul>
 *   <li>the operation's {@code PUBLIC} events, in sequence order, stated deterministically by
 *       {@link EventStatements} (never raw payloads; {@code INTERNAL} events such as effects on
 *       other actors' resources are never read);</li>
 *   <li>display names of the actors those public events reference, which the player perceived
 *       when the action was validated;</li>
 *   <li>the campaign's presentation settings (locale, locale and glossary revisions), its pinned
 *       content hash and recovery generation.</li>
 * </ul>
 * Hidden canon, planning artifacts, receipts of other actors and authoring definitions are never
 * read. The disclosure guard is therefore empty until P09.2 supplies audience-filtered privileged
 * phrase sets; the contract still refuses and validates against it.
 *
 * <p>The narration stops at a generic free-action decision: local actions resolve to "no pending
 * decision" (P07.4), so the next step is any registered intention and no action suggestion can
 * be offered. A context that cannot be built faithfully is reported as unnarratable with a fixed
 * code; the caller then presents the deterministic receipt.
 */
@Component
public class NarrationContextAssembler {
    /**
     * Version label of the public narration audience policy. Its hash is the session-scope policy
     * component (P08.3), so changing the assembly rules renews every narrator session.
     */
    static final String POLICY = "rpgengine.narration.public-policy.v1";
    /** Stable decision reference of the free-action decision. */
    static final String DECISION = "decision.next";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String POLICY_HASH = sha256(POLICY);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate reader;

    /**
     * @param jdbc JDBC access joined to the read snapshot
     * @param manager shared transaction manager
     */
    public NarrationContextAssembler(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        reader = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reader.setReadOnly(true);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reader.setTimeout(10);
    }

    /**
     * Assemble the narration context for one job attempt.
     *
     * @param request committed operation references
     * @return the context, or the fixed reason it cannot be narrated
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures (a transient failure)
     */
    public Assembly assemble(NarrationPort.Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("narration context requires an independent snapshot");
        }
        return reader.execute(status -> read(request));
    }

    /** Read every part inside the snapshot; see the class description for what may be read. */
    private Assembly read(NarrationPort.Request request) {
        UUID campaign = request.campaignId();
        var pinned = jdbc.query("select c.recovery_generation, r.content_hash from campaign c join content_revision r "
                        + "on r.id = c.content_revision_id and r.adventure_id = c.adventure_id "
                        + "where c.id = ? and c.adventure_id = ?",
                (row, index) -> new Object[] {row.getLong(1), row.getString(2)}, campaign, request.adventureId());
        if (pinned.isEmpty() || (long) pinned.getFirst()[0] != request.campaignGeneration()) {
            return new Assembly.Unnarratable("stale-scope");
        }
        var committed = jdbc.query("select committed_revision from operation_journal where id = ? and campaign_id = ?",
                (row, index) -> row.getObject(1, Long.class), request.operationId(), campaign);
        if (committed.isEmpty() || committed.getFirst() == null || committed.getFirst() != request.committedRevision()) {
            return new Assembly.Unnarratable("stale-scope");
        }
        var settings = jdbc.query("select narrative_locale, locale_revision, glossary_revision "
                        + "from presentation_campaign_settings where campaign_id = ?",
                (row, index) -> new Object[] {row.getString(1), row.getLong(2), row.getLong(3)}, campaign);
        var players = jdbc.query("select cc.actor_id from campaign_creation cc where cc.campaign_id = ?",
                (row, index) -> row.getObject(1, UUID.class), campaign);
        if (settings.isEmpty() || players.isEmpty()) {
            return new Assembly.Unnarratable("context-unavailable");
        }
        SupportedLocale locale = SupportedLocale.fromTag((String) settings.getFirst()[0]);
        UUID player = players.getFirst();
        var events = jdbc.query("select sequence, event_type, event_version, payload::text from campaign_event "
                        + "where campaign_id = ? and operation_id = ? and visibility = 'PUBLIC' order by sequence limit ?",
                (row, index) -> new StoredEvent(row.getLong(1), row.getString(2), row.getInt(3),
                        JSON.readTree(row.getString(4))), campaign, request.operationId(), NarrationContext.MAX_EVENTS + 1);
        if (events.isEmpty()) {
            return new Assembly.Unnarratable("no-public-events");
        }
        if (events.size() > NarrationContext.MAX_EVENTS) {
            return new Assembly.Unnarratable("too-many-events");
        }
        var referenced = new LinkedHashSet<UUID>();
        referenced.add(player);
        events.forEach(event -> referenced.addAll(EventStatements.referencedActors(event.payload())));
        Map<UUID, String> names = new HashMap<>();
        jdbc.query("select id, display_name from actor where campaign_id = ? and id = any(?)",
                row -> {
                    names.put(row.getObject(1, UUID.class), row.getString(2));
                },
                campaign, referenced.toArray(new UUID[0]));
        if (!names.containsKey(player)) {
            return new Assembly.Unnarratable("context-unavailable");
        }
        var facts = new ArrayList<PublicFact>();
        var speakers = new ArrayList<KnownActor>();
        for (StoredEvent event : events) {
            var text = EventStatements.state(locale, event.type(), event.version(), event.payload(), names);
            if (text.isEmpty()) {
                return new Assembly.Unnarratable("unsupported-event");
            }
            facts.add(new PublicFact("event." + event.sequence(), text.get()));
            if (event.type().equals("SPEECH_DELIVERED")) {
                // The addressed character may answer in character; the player never speaks here.
                UUID recipient = UUID.fromString(event.payload().path("recipientActorId").asString());
                if (!recipient.equals(player) && speakers.stream().noneMatch(s -> s.actorId().equals(recipient))) {
                    speakers.add(new KnownActor(recipient, names.get(recipient)));
                }
            }
        }
        boolean it = locale == SupportedLocale.IT;
        var binding = new TaskBinding(taskId(request), request.adventureId(), campaign, request.campaignGeneration(),
                request.committedRevision(), locale, (long) settings.getFirst()[1], (long) settings.getFirst()[2],
                (String) pinned.getFirst()[1], POLICY_HASH);
        var context = new NarrationContext(binding, request.operationId(), player, facts, List.of(), speakers, List.of(),
                new NarrationContext.PendingDecision(DECISION, it ? "Cosa fai adesso?" : "What do you do next?", List.of()),
                List.of(), List.of(), DisclosureGuard.none());
        return new Assembly.Ready(context);
    }

    /**
     * Contract task identity of one job attempt: stable for that attempt, distinct across attempts
     * and rounds, so the binding's task identity is unique per live call (docs/21 section 14).
     */
    static UUID taskId(NarrationPort.Request request) {
        return UUID.nameUUIDFromBytes(("narration:" + request.jobId() + ":" + request.attempt())
                .getBytes(StandardCharsets.UTF_8));
    }

    /** @return lowercase SHA-256 hex of UTF-8 text */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }

    /** One stored public event. */
    private record StoredEvent(long sequence, String type, int version, JsonNode payload) {
    }

    /** Result of {@link #assemble}. */
    public sealed interface Assembly permits Assembly.Ready, Assembly.Unnarratable {
        /**
         * A faithful public context.
         *
         * @param context narration context bound to the attempt
         */
        record Ready(NarrationContext context) implements Assembly {
        }

        /**
         * The operation cannot be narrated faithfully; the receipt is its presentation.
         *
         * @param code fixed reason: {@code stale-scope}, {@code context-unavailable},
         *        {@code no-public-events}, {@code too-many-events} or {@code unsupported-event}
         */
        record Unnarratable(String code) implements Assembly {
        }
    }
}
