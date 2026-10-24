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
     * @param version declared exact SemVer version (the compiler rejects ranges here)
     * @param hash declared lowercase SHA-256 content hash; an implementation must compare it to the admitted pack
     * @return the exported definition IDs mapped to their kinds, or empty when no matching pack is installed
     */
    Optional<Map<String, String>> exports(String packId, String version, String hash);

    /**
     * Hash of an installed pack with this ID and version, used only for conflict diagnostics: when
     * {@link #exports} finds no match but the ID/version exists with other bytes, the compiler reports
     * both hashes instead of a generic "not installed" error (docs/09 section 10).
     *
     * @param packId pack ID
     * @param version exact version
     * @return the installed lowercase SHA-256 content hash, or empty when that ID/version is not installed
     */
    default Optional<String> installedHash(String packId, String version) {
        return Optional.empty();
    }

    /** Catalog with no installed packs; any declared dependency then fails to resolve. */
    static DependencyCatalog none() {
        return (packId, version, hash) -> Optional.empty();
    }
}
