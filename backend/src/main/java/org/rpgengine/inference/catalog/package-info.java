/**
 * Model catalog, hardware profile and hardware advisor (P21.1, ADR 0021 decisions 3 and 4,
 * docs/34 section 5).
 *
 * <ul>
 *   <li>{@link org.rpgengine.inference.catalog.ModelCatalog} reads the bundled, versioned catalog
 *       {@code inference/model-catalog.json}: pinned sources and revisions, files with exact sizes
 *       and SHA-256 digests, license class and attribution, memory-estimate inputs, languages,
 *       capabilities, intended slots and content notes.</li>
 *   <li>{@link org.rpgengine.inference.catalog.HardwareProfile} reads the profile that
 *       {@code ./start.sh --models profile} writes to {@code .local/hardware.json}.</li>
 *   <li>{@link org.rpgengine.inference.catalog.ModelAdvisor} ranks the catalog against a profile,
 *       explains each verdict and proposes presets. It recommends and never restricts.</li>
 * </ul>
 *
 * <p>Both documents are closed, strictly parsed formats; a violation is a
 * {@link org.rpgengine.inference.catalog.CatalogException} naming the rule and JSON Pointer. The
 * package is pure: it performs no I/O beyond reading the bundled resource, downloads nothing,
 * starts no process and is not yet wired into Spring. The settings API, the Settings → Models
 * panel, fetch and slot assignment use it in later P21 tasks. Every memory figure in the catalog is
 * an estimate until a measured profile is recorded.
 */
package org.rpgengine.inference.catalog;
