package org.rpgengine.content;

import java.util.Map;
import java.util.Optional;

/**
 * Trusted lookup of already admitted, locally installed packs that a pack declares as dependencies.
 * The compiler never fetches anything: a dependency that the catalog cannot resolve is a blocking diagnostic.
 */
public interface DependencyCatalog {
    /**
     * Resolve one pinned dependency.
     *
     * @param packId declared pack ID
     * @param version declared version label (exact match; range resolution is P04.3)
     * @param hash declared lowercase SHA-256 content hash; an implementation must compare it to the admitted pack
     * @return the exported definition IDs mapped to their kinds, or empty when no matching pack is installed
     */
    Optional<Map<String, String>> exports(String packId, String version, String hash);

    /** Catalog with no installed packs; any declared dependency then fails to resolve. */
    static DependencyCatalog none() {
        return (packId, version, hash) -> Optional.empty();
    }
}
