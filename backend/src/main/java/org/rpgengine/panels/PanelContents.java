package org.rpgengine.panels;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.rpgengine.content.PackRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads and caches {@link PanelContent} per sealed content revision (P10.5). A revision is immutable,
 * so the cache never needs invalidation; it keeps at most {@value #CACHE_ENTRIES} revisions and drops
 * the least recently used.
 *
 * <p>A revision that cannot be loaded (legacy unsealed bytes, changed stored bytes, an unavailable
 * ruleset) yields {@link Optional#empty()}: panels then show canonical numbers and identifiers but no
 * names, no quests and no rarity, and say so, because hiding rules that cannot be checked is safer than
 * guessing which quests are secret. Storage failures are not caught and reach the caller.
 */
@Service
class PanelContents {
    /** Retained revisions. */
    static final int CACHE_ENTRIES = 16;

    private final PackRegistry packs;
    private final TransactionTemplate reads;
    private final Map<UUID, PanelContent> cache = new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, PanelContent> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /**
     * @param packs registry that reparses and verifies sealed revision bytes
     * @param manager shared transaction manager
     */
    PanelContents(PackRegistry packs, PlatformTransactionManager manager) {
        this.packs = Objects.requireNonNull(packs, "packs");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(60);
    }

    /**
     * Load the panel content of a campaign's pinned revision.
     *
     * @param adventure adventure owning the revision
     * @param revision the campaign's frozen content revision
     * @return the content, or empty when the revision cannot be loaded
     * @throws IllegalStateException inside an ambient transaction
     */
    Optional<PanelContent> load(UUID adventure, UUID revision) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("panel content loading must not extend a caller transaction");
        }
        synchronized (cache) {
            PanelContent cached = cache.get(revision);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        PanelContent loaded = reads.execute(status -> {
            try {
                return PanelContent.from(packs.loadForCampaign(adventure, revision));
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException unavailable) {
                status.setRollbackOnly();
                return null;
            }
        });
        if (loaded != null) {
            synchronized (cache) {
                cache.put(revision, loaded);
            }
        }
        return Optional.ofNullable(loaded);
    }
}
