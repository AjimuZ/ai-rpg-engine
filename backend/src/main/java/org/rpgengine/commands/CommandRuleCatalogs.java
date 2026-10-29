package org.rpgengine.commands;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.content.CampaignDependencies;
import org.rpgengine.content.PackRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the pinned rule catalog of a campaign <em>before</em> admission or canonical commit
 * takes its campaign lock, so pack recompilation never extends lock hold time. This is safe
 * because {@code campaign.content_revision_id} is frozen by a database trigger and sealed
 * revisions are immutable: the facts loaded here are exactly the facts that will be pinned
 * when the lock is held. The locked validator still compares the revision identity.
 *
 * <p>Owner/adventure/campaign scope is authorized in the read transaction before any cache
 * lookup, so a cached catalog is never returned across scopes. Load failures do not throw:
 * they become {@link Content#UNAVAILABLE}, and only a fresh command whose rules actually need
 * pinned definitions fails, after matching-request replay. Catalogs hold no secret prose and
 * at most {@link #CACHE_ENTRIES} revisions are retained (least recently used first out).
 *
 * <p>Since P09.5 a failed load is classified by {@link CampaignDependencies}: a sealed closure
 * that is missing, corrupt or unusable under this engine build yields {@link Content#PAUSED}, and
 * admission then refuses every fresh command of the campaign ({@code DEPENDENCIES_MISSING}). A
 * legacy unsealed revision stays {@link Content#UNAVAILABLE}, with the earlier per-rule behavior.
 */
@Service
public class CommandRuleCatalogs {
    /** Retained compiled revisions; each catalog is small, but closures can be expensive to rebuild. */
    static final int CACHE_ENTRIES = 16;
    /** Registered intentions whose rules may consult pinned definitions; WAIT needs none. */
    private static final Set<String> CONTENT_RULES = Set.of("MOVE", "SPEAK", "USE_ABILITY", "USE_ITEM", "EQUIP_ITEM",
            "UNEQUIP_ITEM", "TRANSFER_ITEM", "TRADE_ITEMS", "ACCEPT_QUEST", "SUSPEND_QUEST", "RESUME_QUEST", "REST",
            // WAIT needs no definition to be legal, but a clock predicate can become true as it passes (P10.4).
            // It uses the catalog when one is available and never fails for lack of it (see Content#optional).
            "WAIT");

    private final JdbcTemplate jdbc;
    private final PackRegistry packs;
    private final CampaignDependencies dependencies;
    private final TransactionTemplate reads;
    private final Map<UUID, CommandRuleCatalog> cache = new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, CommandRuleCatalog> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /**
     * Wire storage and the sealed pack registry with a private dependency classifier (embedders and
     * suites that build their own pipeline); null infrastructure fails construction.
     *
     * @param jdbc JDBC access joined to the read transaction
     * @param packs registry that reparses and verifies sealed revision bytes
     * @param manager shared transaction manager
     */
    public CommandRuleCatalogs(JdbcTemplate jdbc, PackRegistry packs, PlatformTransactionManager manager) {
        this(jdbc, packs, manager, new CampaignDependencies(packs, jdbc));
    }

    /**
     * Spring constructor sharing the application's dependency status cache.
     *
     * @param jdbc JDBC access joined to the read transaction
     * @param packs registry that reparses and verifies sealed revision bytes
     * @param manager shared transaction manager
     * @param dependencies classifier of failed loads (P09.5)
     */
    @Autowired
    public CommandRuleCatalogs(JdbcTemplate jdbc, PackRegistry packs, PlatformTransactionManager manager,
            CampaignDependencies dependencies) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        // Matches campaign creation's bound for recompiling a large pinned closure.
        reads.setTimeout(60);
    }

    /**
     * Return the pinned catalog for a command that may need it, without taking campaign locks.
     * Unregistered types and WAIT receive no catalog, because no pinned definition participates
     * in their validation, but since P09.5 they still learn whether the campaign is paused:
     * {@link Content#PAUSED} or {@link Content#UNAVAILABLE}.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign target campaign
     * @param commandType envelope type; only registered content-dependent types load anything
     * @return available catalog or the unavailable marker; scope failures are left to the caller's
     *         own authorized transaction so that their public classification stays unchanged
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public Content preload(UUID owner, UUID adventure, UUID campaign, String commandType) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Content pinned = pinned(owner, adventure, campaign);
        if (CONTENT_RULES.contains(commandType)) {
            return pinned;
        }
        return pinned.paused() ? Content.PAUSED : Content.UNAVAILABLE;
    }

    /**
     * Return the pinned catalog whatever the command type, without taking campaign locks. The
     * canonical commit uses it for every registered intention because the perception that follows
     * the effects (P09.1) needs concealment facts even for {@code WAIT}. Scope and failure handling
     * are those of {@link #preload}.
     *
     * @return available catalog, {@link Content#PAUSED} when the sealed closure can no longer be
     *         loaded, or {@link Content#UNAVAILABLE} (unknown scope or a legacy unsealed revision)
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures, including while
     *         classifying a failed load
     */
    public Content pinned(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        MutationLeaseService.requireNoTransaction();
        UUID[] failed = new UUID[1];
        Content loaded = Objects.requireNonNull(reads.execute(status -> {
            jdbc.execute("set local statement_timeout = '30s'");
            List<UUID> pinned = jdbc.query("select c.content_revision_id from campaign c join adventure_workspace w "
                            + "on w.id = c.adventure_id where c.id = ? and c.adventure_id = ? and w.owner_id = ?",
                    (row, index) -> row.getObject(1, UUID.class), campaign, adventure, owner);
            if (pinned.isEmpty()) {
                return Content.UNAVAILABLE;
            }
            UUID revision = pinned.getFirst();
            synchronized (cache) {
                CommandRuleCatalog cached = cache.get(revision);
                if (cached != null) {
                    return new Content(cached);
                }
            }
            try {
                var catalog = CommandRuleCatalog.from(revision, packs.loadForCampaign(adventure, revision));
                synchronized (cache) {
                    cache.put(revision, catalog);
                }
                return new Content(catalog);
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException failure) {
                // Unsealed/legacy revisions, an unavailable pinned ruleset or changed stored bytes:
                // fail closed later, only where a rule needs definitions. Diagnostics stay private.
                status.setRollbackOnly();
                failed[0] = revision;
                return Content.UNAVAILABLE;
            }
        }));
        // Classified outside the read transaction, which the diagnosis must not join. Only a sealed
        // closure that can no longer be loaded pauses the campaign (P09.5).
        if (failed[0] != null && dependencies.check(adventure, failed[0]).pausesMutation()) {
            return Content.PAUSED;
        }
        return loaded;
    }

    /**
     * Result of {@link #preload}: a detached catalog or the unavailable marker.
     *
     * @param catalog immutable pinned rule facts, or null when unavailable
     * @param paused true when the campaign's sealed pinned closure is missing or unusable, so
     *        mutation is paused (P09.5); always false with a catalog
     */
    public record Content(CommandRuleCatalog catalog, boolean paused) {
        /** No pinned rule facts could be loaded or none were requested. */
        public static final Content UNAVAILABLE = new Content(null, false);
        /** The pinned closure is missing or unusable: fresh commands are refused. */
        public static final Content PAUSED = new Content(null, true);

        /** Require that a paused campaign carries no catalog. */
        public Content {
            if (paused && catalog != null) {
                throw new IllegalArgumentException("a paused campaign has no usable catalog");
            }
        }

        /** @param catalog available pinned rule facts */
        Content(CommandRuleCatalog catalog) {
            this(catalog, false);
        }

        /**
         * Return the catalog for the campaign's frozen pinned revision when there is one, for commands that
         * are legal without definitions and only use them to evaluate clock-driven quests.
         *
         * @param pinnedRevision revision read under the caller's campaign lock
         * @return the matching catalog, or null when absent or for another revision
         */
        CommandRuleCatalog optional(UUID pinnedRevision) {
            return catalog != null && catalog.revisionId().equals(pinnedRevision) ? catalog : null;
        }

        /**
         * Return the catalog for the campaign's frozen pinned revision.
         *
         * @param pinnedRevision revision read under the caller's campaign lock
         * @return the matching catalog
         * @throws OperationAdmissionFailure {@code CONTENT_UNAVAILABLE} when absent or for another revision
         */
        CommandRuleCatalog require(UUID pinnedRevision) {
            if (catalog == null || !catalog.revisionId().equals(pinnedRevision)) {
                throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.CONTENT_UNAVAILABLE);
            }
            return catalog;
        }
    }
}
