package org.rpgengine.campaign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.content.PackRegistry;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal owner-scoped campaign creation boundary. A trusted caller supplies owner context,
 * selected adventure, sealed revision and bounded choices. All initial state and the durable
 * request result commit together; validation, contention, SQL or commit failures roll back.
 * Returns detached identities only after COMMIT. HTTP identity/CSRF admission remains separate.
 */
@Service
public class CampaignCreationService {
    private final JdbcTemplate jdbc;
    private final PackRegistry packs;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Wire local persistence and the sealed registry; null infrastructure fails construction. */
    public CampaignCreationService(JdbcTemplate jdbc, PackRegistry packs, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.packs = Objects.requireNonNull(packs, "packs");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(60);
    }

    /**
     * Create once per adventure/request identity, or return the matching committed identities.
     * Owner context must come from a trusted application boundary, never a client owner field.
     * Changed-payload retries and inaccessible scopes fail without returning stored identities.
     * Reject ambient transactions so a result cannot precede the real commit or retain locks.
     */
    public CreatedCampaign create(UUID ownerId, UUID adventureId, Request request) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(adventureId, "adventureId");
        Objects.requireNonNull(request, "request");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("campaign creation requires no ambient transaction");
        }
        // Hash explicit versioned fields, with defensively copied sorted allocation maps.
        String payload = json.writeValueAsString(new Payload(1, request.contentRevisionId(), request.title(),
                request.narrativeLocale().tag(), request.choices()));
        String hash = digest(payload);
        return Objects.requireNonNull(transaction.execute(status -> write(ownerId, adventureId, request, hash)));
    }

    /** Authorize, serialize, resolve and insert one tentative result; exceptions force complete rollback. */
    private CreatedCampaign write(UUID ownerId, UUID adventureId, Request request, String hash) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '30s'");
        // Registry writers use this same lock. After a wait, READ COMMITTED sees the winner's
        // request row, making concurrent identical submissions and import/creation races safe.
        var scope = jdbc.query("select id from adventure_workspace where id = ? and owner_id = ? for update",
                (row, index) -> row.getObject(1, UUID.class), adventureId, ownerId);
        if (scope.isEmpty()) {
            throw new CampaignCreationFailure(CampaignCreationFailure.Kind.SCOPE_UNAVAILABLE);
        }
        var previous = jdbc.query("select r.payload_hash, r.campaign_id, r.actor_id, c.content_revision_id "
                + "from campaign_creation r join campaign c on c.id = r.campaign_id "
                + "where r.adventure_id = ? and r.request_id = ?", (row, index) -> {
                    if (!hash.equals(row.getString(1))) {
                        throw new CampaignCreationFailure(CampaignCreationFailure.Kind.REQUEST_CONFLICT);
                    }
                    return new CreatedCampaign(row.getObject(2, UUID.class), row.getObject(3, UUID.class),
                            row.getObject(4, UUID.class));
                }, adventureId, request.requestId());
        if (!previous.isEmpty()) {
            return previous.getFirst();
        }
        // Resolve identity scope before mechanics. Missing and foreign revisions share one safe
        // transport category; neither can leak its existence outside the selected adventure.
        if (jdbc.queryForObject("select count(*) from content_revision where id = ? and adventure_id = ?",
                Integer.class, request.contentRevisionId(), adventureId) != 1) {
            throw new CampaignCreationFailure(CampaignCreationFailure.Kind.SCOPE_UNAVAILABLE);
        }
        var pack = packs.loadForCampaign(adventureId, request.contentRevisionId());
        var initial = CharacterCreation.resolve(pack, request.choices());
        UUID campaignId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        // The marker records waiting for player input; prose stays in the immutable pinned pack.
        jdbc.update("insert into campaign (id, adventure_id, content_revision_id, title, seed_identity, mode, "
                + "pending_decision, created_at, updated_at) values (?, ?, ?, ?, ?, 'EXPLORATION', ?::jsonb, now(), now())",
                campaignId, adventureId, request.contentRevisionId(), request.title(), UUID.randomUUID().toString(),
                StartingDecision.STORED_JSON);
        jdbc.update("insert into presentation_campaign_settings (campaign_id, narrative_locale, updated_at) "
                + "values (?, ?, now())", campaignId, request.narrativeLocale().tag());
        jdbc.update("insert into actor (id, campaign_id, kind, definition_id, display_name, location_id, "
                + "created_revision, created_at) values (?, ?, 'CHARACTER', ?, ?, ?, 0, now())",
                actorId, campaignId, initial.profileId(), request.choices().name(), initial.locationId());
        jdbc.update("insert into character_state (campaign_id, actor_id, attributes, skills, background, role) "
                + "values (?, ?, ?::jsonb, ?::jsonb, ?, ?)", campaignId, actorId,
                json.writeValueAsString(initial.attributes()), json.writeValueAsString(initial.skills()),
                request.choices().background(), request.choices().role());
        initial.resources().forEach((key, maximum) -> jdbc.update("insert into actor_resource (id, campaign_id, "
                + "actor_id, resource_key, minimum_value, current_value, maximum_value, updated_revision, "
                + "created_at, updated_at) values (?, ?, ?, ?, 0, ?, ?, 0, now(), now())",
                UUID.randomUUID(), campaignId, actorId, key, maximum, maximum));
        for (var item : initial.items()) {
            jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, "
                    + "maximum_stack, acquired_revision) values (?, ?, ?, ?, ?, ?, 0)", UUID.randomUUID(),
                    campaignId, actorId, item.definitionId(), item.quantity(), item.maximumStack());
        }
        for (String ability : initial.abilities()) {
            jdbc.update("insert into learned_ability (campaign_id, actor_id, definition_id, rank, acquired_revision) "
                    + "values (?, ?, ?, 1, 0)", campaignId, actorId, ability);
        }
        jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, ?, ?::jsonb, now())", adventureId,
                request.requestId(), hash, campaignId, actorId, initial.profileId(), json.writeValueAsString(request.choices()));
        // Creation is revision zero: no action, event, draw, narration job or world-time advance.
        return new CreatedCampaign(campaignId, actorId, request.contentRevisionId());
    }

    /** Hash a versioned canonical JSON payload; a broken JDK fails explicitly rather than changing algorithms. */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    /** Required creation intent; identities, locale, bounded title and character choices are never inferred. */
    public record Request(UUID requestId, UUID contentRevisionId, String title,
            SupportedLocale narrativeLocale, CharacterChoices choices) {
        /** Reject missing fields and invalid text before opening any transaction. */
        public Request {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(contentRevisionId, "contentRevisionId");
            title = CanonicalValues.requireText(title, "title", 200);
            Objects.requireNonNull(narrativeLocale, "narrativeLocale");
            Objects.requireNonNull(choices, "choices");
        }
    }

    /** Durable creation result; contains no seed, private pack text or live state projection. */
    public record CreatedCampaign(UUID campaignId, UUID actorId, UUID contentRevisionId) {
    }

    /** Hash protocol v1; request identity is excluded because it scopes storage rather than intent. */
    private record Payload(int version, UUID revision, String title, String locale, CharacterChoices choices) {
    }
}
