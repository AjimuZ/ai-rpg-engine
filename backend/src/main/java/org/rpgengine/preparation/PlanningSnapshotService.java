package org.rpgengine.preparation;

import java.util.Objects;
import java.util.List;
import java.util.UUID;

import org.rpgengine.persistence.CanonicalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Capture immutable planning snapshots (docs/28 section 6). A snapshot freezes the
 * campaign generation, state revision, event high-water mark, world time, content
 * pack and ruleset hashes and the presentation locale/glossary revisions that a
 * planner may read, together with the caller-selected bounded state, source
 * references and dependency manifest. The database computes a context digest over
 * every stored field, so two snapshots with equal inputs have equal digests. Rows
 * are never updated or deleted (V6 trigger). Choosing which state a given audience
 * may see is the caller's job until projections land at P06; this class only bounds
 * and records it.
 */
@Service
public class PlanningSnapshotService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /** Wire local persistence only. */
    public PlanningSnapshotService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = PreparationSupport.template(manager);
    }

    /**
     * Capture one snapshot of a campaign under a share lock, so the recorded revision,
     * event high-water mark and generation describe one committed canonical state.
     *
     * @return the stored snapshot header including its digest
     * @throws IllegalArgumentException for unknown or foreign campaigns
     * @throws IllegalStateException when called inside another transaction
     */
    public Snapshot capture(UUID adventure, UUID campaign, SnapshotRequest request) {
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(request, "request");
        PreparationSupport.requireNoTransaction();
        return transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            var owned = jdbc.queryForList("select id from campaign where id = ? and adventure_id = ? for share",
                    UUID.class, campaign, adventure);
            if (owned.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            // Caller-assembled bytes must describe this exact committed state; otherwise
            // a concurrent world write could label old input with a new revision.
            var tokens = jdbc.queryForMap("select state_revision, recovery_generation from campaign where id = ?", campaign);
            if (((Number) tokens.get("state_revision")).longValue() != request.expectedRevision()
                    || ((Number) tokens.get("recovery_generation")).longValue() != request.expectedGeneration()) {
                throw new IllegalStateException("stale snapshot input");
            }
            var settings = jdbc.queryForList("select locale_revision, glossary_revision from presentation_campaign_settings "
                    + "where campaign_id = ? for share", campaign);
            if (request.audience() == Audience.PUBLIC && settings.isEmpty()) {
                // Public snapshots need a lockable preference row; absence cannot be
                // locked against concurrent initialization and a locale switch.
                throw new IllegalStateException("public snapshot requires presentation settings");
            }
            long locale = settings.isEmpty() ? 0 : ((Number) settings.getFirst().get("locale_revision")).longValue();
            long glossary = settings.isEmpty() ? 0 : ((Number) settings.getFirst().get("glossary_revision")).longValue();
            if (locale != request.expectedLocaleRevision() || glossary != request.expectedGlossaryRevision()) {
                throw new IllegalStateException("stale snapshot presentation input");
            }
            for (var dependency : request.dependencies()) {
                if (dependency.kind() == PlanAdmissionService.Kind.QUERY
                        && dependency.epoch() != QueryEpochs.current(jdbc, campaign, dependency.scope())) {
                    throw new IllegalStateException("stale snapshot query input");
                }
            }
            // Reuse the checked presentation tokens, including the private default
            // when no settings row exists, rather than relabel bytes after initialization.
            // The digest covers every stored field in a fixed order; jsonb text output
            // is canonical (sorted keys, normalized spacing), so it is reproducible.
            return jdbc.queryForObject("insert into planning_snapshot (id, campaign_id, campaign_generation, "
                            + "content_revision_id, source_revision, event_high_water, world_time_seconds, "
                            + "locale_revision, glossary_revision, audience, selected_state, source_refs, "
                            + "dependency_manifest, pack_hash, ruleset_hash, author_constraint_hash, policy_hash, "
                            + "context_digest, created_at) "
                            + "select s.*, encode(sha256(convert_to(jsonb_build_array(s.campaign_id, "
                            + "s.campaign_generation, s.content_revision_id, s.source_revision, s.event_high_water, "
                            + "s.world_time_seconds, s.locale_revision, s.glossary_revision, s.audience, "
                            + "s.selected_state, s.source_refs, s.dependency_manifest, s.pack_hash, s.ruleset_hash, "
                            + "s.author_constraint_hash, s.policy_hash)::text, 'UTF8')), 'hex'), clock_timestamp() "
                            + "from (select ?::uuid as id, c.id as campaign_id, c.recovery_generation as "
                            + "campaign_generation, c.content_revision_id, c.state_revision as source_revision, "
                            + "c.event_sequence as event_high_water, c.world_time_seconds, "
                            + "?::bigint as locale_revision, "
                            + "?::bigint as glossary_revision, ?::text as audience, "
                            + "?::jsonb as selected_state, ?::jsonb as source_refs, ?::jsonb as dependency_manifest, "
                            + "r.content_hash as pack_hash, r.ruleset_hash, ?::text as author_constraint_hash, "
                            + "?::text as policy_hash from campaign c "
                            + "join content_revision r on r.id = c.content_revision_id "
                            + "left join presentation_campaign_settings p on p.campaign_id = c.id where c.id = ?) s "
                            + "returning id, campaign_id, campaign_generation, source_revision, event_high_water, "
                            + "world_time_seconds, locale_revision, glossary_revision, context_digest",
                    (row, index) -> new Snapshot(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getLong(3), row.getLong(4), row.getLong(5), row.getLong(6), row.getLong(7),
                            row.getLong(8), row.getString(9)),
                    UUID.randomUUID(), request.expectedLocaleRevision(), request.expectedGlossaryRevision(),
                    request.audience().name(), request.selectedStateJson(),
                    request.sourceRefsJson(), PlanAdmissionService.manifest(request.dependencies()), request.authorConstraintHash(),
                    request.policyHash(), campaign);
        });
    }

    /**
     * Bounded snapshot inputs chosen by trusted backend code.
     *
     * @param audience visibility the snapshot was built for
     * @param selectedStateJson JSON object of selected state, at most 65536 bytes
     * @param sourceRefsJson JSON array of source references, at most 16384 bytes
     * @param dependencies complete engine-recorded dependencies, at most 512 and 65536 JSON bytes
     * @param expectedRevision revision from which the caller assembled the input
     * @param expectedGeneration recovery generation from which the input was assembled
     * @param expectedLocaleRevision presentation locale revision used by the assembler
     * @param expectedGlossaryRevision glossary revision used by the assembler
     * @param authorConstraintHash SHA-256 of the applicable author constraints
     * @param policyHash SHA-256 of the effective policy; must equal the binding job's
     */
    public record SnapshotRequest(Audience audience, String selectedStateJson, String sourceRefsJson,
            List<PlanAdmissionService.Dependency> dependencies, String authorConstraintHash, String policyHash,
            long expectedRevision, long expectedGeneration, long expectedLocaleRevision, long expectedGlossaryRevision) {
        /** Reject malformed inputs before any transaction opens; the database re-checks bounds. */
        public SnapshotRequest {
            Objects.requireNonNull(audience, "audience");
            CanonicalValues.requireJsonObject(selectedStateJson, "selectedStateJson", 65536);
            PreparationSupport.requireJsonArray(sourceRefsJson, "sourceRefsJson", 16384);
            dependencies = List.copyOf(dependencies);
            if (dependencies.size() > 512) {
                throw new IllegalArgumentException("at most 512 dependencies");
            }
            PreparationSupport.requireJsonArray(PlanAdmissionService.manifest(dependencies), "dependencyManifest", 65536);
            CanonicalValues.requireNonNegative(expectedRevision, "expectedRevision");
            CanonicalValues.requireNonNegative(expectedGeneration, "expectedGeneration");
            CanonicalValues.requireNonNegative(expectedLocaleRevision, "expectedLocaleRevision");
            CanonicalValues.requireNonNegative(expectedGlossaryRevision, "expectedGlossaryRevision");
            CanonicalValues.requireSha256Hex(authorConstraintHash, "authorConstraintHash");
            CanonicalValues.requireSha256Hex(policyHash, "policyHash");
        }
    }

    /**
     * Stored snapshot header.
     *
     * @param id snapshot identity
     * @param campaignId owning campaign
     * @param generation campaign recovery generation at capture
     * @param sourceRevision campaign state revision at capture
     * @param eventHighWater campaign event sequence at capture
     * @param worldTime world time in seconds at capture
     * @param localeRevision presentation locale revision at capture
     * @param glossaryRevision presentation glossary revision at capture
     * @param contextDigest database-computed SHA-256 over every stored field
     */
    public record Snapshot(UUID id, UUID campaignId, long generation, long sourceRevision, long eventHighWater,
            long worldTime, long localeRevision, long glossaryRevision, String contextDigest) {
    }
}
