package org.rpgengine.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Pinned content dependency status of campaigns (P09.5, docs/08 section 10).
 *
 * <p>A campaign whose pinned closure is {@link ClosureDiagnosis.Status#MISSING} pauses mutation:
 * command admission refuses fresh commands with {@code DEPENDENCIES_MISSING} until the exact
 * content is restored or imported again, and the resume card shows the issues. Nothing substitutes
 * another pack or version.
 *
 * <p><b>Caching.</b> Sealed revisions and the engine build are immutable for the life of a process,
 * so an {@code AVAILABLE} diagnosis of a revision is cached (at most {@value #CACHE_ENTRIES}
 * revisions, least recently used first out). {@code MISSING} and {@code UNSEALED} results are never
 * cached: importing or restoring the missing pack is picked up by the next check without a restart.
 * Startup reconciliation therefore re-verifies every pinned closure once per process.
 */
@Service
public class CampaignDependencies {
    /** Revisions whose closure is known to be loadable. */
    static final int CACHE_ENTRIES = 256;

    private final PackRegistry packs;
    private final JdbcTemplate jdbc;
    private final Map<UUID, Boolean> available = new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /**
     * @param packs registry that diagnoses pinned closures
     * @param jdbc storage for the campaign's pinned revision
     */
    public CampaignDependencies(PackRegistry packs, JdbcTemplate jdbc) {
        this.packs = Objects.requireNonNull(packs, "packs");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Diagnose one pinned root revision. The caller must already have authorized the scope.
     *
     * @param adventure adventure that owns the revision
     * @param revision the campaign's frozen pinned root revision
     * @return the diagnosis; {@code AVAILABLE} may come from the cache
     * @throws IllegalStateException inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public ClosureDiagnosis check(UUID adventure, UUID revision) {
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(revision, "revision");
        synchronized (available) {
            if (available.get(revision) != null) {
                return ClosureDiagnosis.AVAILABLE;
            }
        }
        ClosureDiagnosis diagnosis = packs.diagnose(adventure, revision);
        if (diagnosis.status() == ClosureDiagnosis.Status.AVAILABLE) {
            synchronized (available) {
                available.put(revision, Boolean.TRUE);
            }
        }
        return diagnosis;
    }

    /**
     * Diagnose a campaign's pinned closure for internal callers (startup reconciliation). Owner
     * authorization is the caller's concern; HTTP callers use {@link #check} after their own
     * scoped read.
     *
     * @param campaign campaign identity
     * @return the diagnosis, or empty for an unknown campaign
     * @throws IllegalStateException inside an ambient transaction
     */
    public Optional<ClosureDiagnosis> forCampaign(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        var pinned = jdbc.query("select adventure_id, content_revision_id from campaign where id = ?",
                (row, index) -> new UUID[] {row.getObject(1, UUID.class), row.getObject(2, UUID.class)}, campaign);
        return pinned.stream().findFirst().map(ids -> check(ids[0], ids[1]));
    }
}
