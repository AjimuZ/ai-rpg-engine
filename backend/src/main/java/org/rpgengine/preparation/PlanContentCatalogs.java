package org.rpgengine.preparation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.content.PackRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads a campaign's {@link PlanContentCatalog} <em>before</em> snapshot capture or plan
 * admission takes the campaign lock, so recompiling a sealed pack never extends lock hold
 * time. This is safe because {@code campaign.content_revision_id} is frozen by a database
 * trigger and sealed revisions are immutable; the locked gate still compares the revision
 * identity. The campaign's own adventure is read from its row, so a cached catalog can only
 * describe that campaign's pinned closure. Load failures never throw: legacy metadata-only
 * revisions, changed stored bytes or an unavailable ruleset become {@link Content#UNAVAILABLE}
 * and the gate rejects with {@code CONTENT_UNAVAILABLE}. At most {@link #CACHE_ENTRIES}
 * revisions are retained, least recently used first out.
 */
@Service
public class PlanContentCatalogs {
    /** Retained compiled revisions; the same bound as the command rule catalog cache. */
    static final int CACHE_ENTRIES = 16;

    private final JdbcTemplate jdbc;
    private final PackRegistry packs;
    private final TransactionTemplate reads;
    private final Map<UUID, PlanContentCatalog> cache = new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, PlanContentCatalog> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /**
     * Wire storage and the sealed pack registry; missing beans fail application startup.
     *
     * @param jdbc JDBC access joined to the read transaction
     * @param packs registry that reparses and verifies sealed revision bytes
     * @param manager shared transaction manager
     */
    public PlanContentCatalogs(JdbcTemplate jdbc, PackRegistry packs, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.packs = Objects.requireNonNull(packs, "packs");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        // Matches the command catalog's bound for recompiling a large pinned closure.
        reads.setTimeout(60);
    }

    /**
     * Return the pinned catalog of a campaign without taking campaign locks.
     *
     * @param campaign campaign whose frozen content revision is loaded
     * @return available catalog, or the unavailable marker for unknown campaigns and
     *         revisions that cannot be reloaded from sealed bytes
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public Content preload(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        PreparationSupport.requireNoTransaction();
        return Objects.requireNonNull(reads.execute(status -> {
            jdbc.execute("set local statement_timeout = '30s'");
            List<Map<String, Object>> pinned = jdbc.queryForList(
                    "select adventure_id, content_revision_id from campaign where id = ?", campaign);
            if (pinned.isEmpty()) {
                return Content.UNAVAILABLE;
            }
            UUID adventure = (UUID) pinned.getFirst().get("adventure_id");
            UUID revision = (UUID) pinned.getFirst().get("content_revision_id");
            synchronized (cache) {
                PlanContentCatalog cached = cache.get(revision);
                if (cached != null) {
                    return new Content(cached);
                }
            }
            try {
                var catalog = PlanContentCatalog.from(revision, packs.loadForCampaign(adventure, revision));
                synchronized (cache) {
                    cache.put(revision, catalog);
                }
                return new Content(catalog);
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException failure) {
                // Diagnostics stay private; the gate reports only CONTENT_UNAVAILABLE.
                status.setRollbackOnly();
                return Content.UNAVAILABLE;
            }
        }));
    }

    /**
     * Result of {@link #preload}: a detached catalog or the unavailable marker.
     *
     * @param catalog immutable pinned facts, or null when unavailable
     */
    public record Content(PlanContentCatalog catalog) {
        /** No pinned facts could be loaded. */
        public static final Content UNAVAILABLE = new Content(null);

        /**
         * Return the catalog when it describes the revision read under the caller's lock.
         *
         * @param pinnedRevision campaign content revision read under the campaign lock
         * @return the catalog, or null when unavailable or for another revision
         */
        public PlanContentCatalog matching(UUID pinnedRevision) {
            return catalog != null && catalog.revisionId().equals(pinnedRevision) ? catalog : null;
        }
    }
}
