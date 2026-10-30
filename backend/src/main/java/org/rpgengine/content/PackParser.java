package org.rpgengine.content;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parse a caller-staged inventory with exactly one root manifest, explicit categories and raw hashes.
 * This class performs no filesystem, archive, registry, dependency, renderer or network operations.
 * It cannot admit content: P04.2–P04.4 supply semantic validation and a protected import boundary.
 */
public final class PackParser {
    private static final long MAX_TOTAL_BYTES = 250L * 1024 * 1024;
    private static final Set<String> PROSE = Set.of("lore", "narrativeStyle", "license", "credits");
    private static final Set<String> DEFINITIONS = Set.of("locations", "factions", "creation", "npcs", "items",
            "skills", "abilities", "conditions", "progression", "encounters", "quests", "rules", "anchors");
    private final PackTextParser text = new PackTextParser();

    /** Construct a reusable stateless stage parser; individual parses share no source or work budgets. */
    public PackParser() {
    }

    /**
     * Parse already staged relative-path bytes into immutable data and digest inventory.
     * Missing, extra, ambiguous, invalid or oversized input throws PackException; no storage is touched.
     */
    public ParsedPack parse(Map<String, byte[]> input) {
        Map<String, byte[]> files = snapshot(input);
        boolean yaml = files.containsKey("adventure.yaml");
        boolean json = files.containsKey("adventure.json");
        if (yaml == json) {
            throw error("MANIFEST", "adventure.yaml", "Exactly one root adventure.yaml or adventure.json is required");
        }
        String root = yaml ? "adventure.yaml" : "adventure.json";
        PackDocument document = text.parse(root, files.get(root));
        PackManifest manifest = new PackManifest(document);
        Map<String, PackDocument> documents = new LinkedHashMap<>();
        Map<String, String> prose = new LinkedHashMap<>();
        Map<String, String> categories = new LinkedHashMap<>();
        documents.put(root, document);
        for (int index = 0; index < manifest.files().size(); index++) {
            var reference = manifest.files().get(index);
            String pointer = "/files/" + index + "/path";
            requirePath(reference.path(), document.location(pointer));
            if (reference.path().equals(root) || categories.putIfAbsent(reference.path(), reference.category()) != null) {
                throw new PackException("FILE_REFERENCE", document.location(pointer), "File paths must be unique and exclude the manifest");
            }
            byte[] bytes = files.get(reference.path());
            if (bytes == null) {
                throw new PackException("FILE_REFERENCE", document.location(pointer), "Referenced file is missing");
            }
            if (PROSE.contains(reference.category())) {
                if (!reference.path().endsWith(".md")) {
                    throw new PackException("FILE_TYPE", document.location(pointer), "Prose categories require .md");
                }
                prose.put(reference.path(), text.markdown(reference.path(), bytes));
            } else if (!reference.category().equals("asset")) {
                PackDocument parsed = text.parse(reference.path(), bytes);
                if (DEFINITIONS.contains(reference.category())) {
                    PackSchemas.definitions(parsed);
                } else if (reference.category().equals("localization")) {
                    PackSchemas.localization(parsed);
                } else if (reference.category().equals("textResources")) {
                    PackSchemas.textResources(parsed);
                } else if (!(parsed.value() instanceof Map)) {
                    throw new PackException("SCHEMA", parsed.location(""), "Structured content requires an object root");
                }
                documents.put(reference.path(), parsed);
            }
        }
        if (files.size() != categories.size() + 1) {
            String extra = files.keySet().stream().filter(path -> !path.equals(root) && !categories.containsKey(path)).findFirst().orElse(root);
            throw error("FILE_REFERENCE", extra, "Every distributed file must be explicitly categorized");
        }
        categoryReference(document, categories, "generationPolicy", "generationPolicy");
        categoryReference(document, categories, "presentationProfile", "theme");
        categoryReference(document, categories, "textResources", "textResources");
        categoryReference(document, categories, "presentation", "presentation");
        presentationFile(document, categories);
        moduleReferences(document, categories);
        PackHashes.Inventory hashes = PackHashes.calculate(files);
        return new ParsedPack(manifest, documents, prose, hashes.contentHash(), hashes.fileHashes());
    }

    /** Copy a bounded logical inventory before parsing; reject noncanonical paths and case or prefix collisions without filesystem access. */
    private static Map<String, byte[]> snapshot(Map<String, byte[]> input) {
        if (input == null || input.isEmpty() || input.size() > 2000) {
            throw error("LIMIT", "adventure.yaml", "Inventory requires 1..2000 files");
        }
        long total = 0;
        for (var entry : input.entrySet()) {
            requirePath(entry.getKey(), new SourceLocation("adventure.yaml", 1, 1, ""));
            if (entry.getValue() == null || (total += entry.getValue().length) > MAX_TOTAL_BYTES) {
                throw error("LIMIT", entry.getKey(), "Inventory exceeds 250 MiB or contains absent bytes");
            }
        }
        // Case-folded duplicates and file/directory prefix clashes cannot coexist on every host.
        String collision = PackPaths.collision(new TreeMap<>(input).keySet(), List.of());
        if (collision != null) {
            throw error("PATH", collision, "Case-colliding or file/directory-colliding paths are forbidden");
        }
        Map<String, byte[]> snapshot = new TreeMap<>();
        input.forEach((path, bytes) -> snapshot.put(path, bytes.clone()));
        return snapshot;
    }

    /** Require canonical portable relative names; host containment, links and ZIP handling belong to PackStaging. */
    private static void requirePath(String path, SourceLocation location) {
        PackPaths.require(path, location);
    }

    /** Check that one manifest policy/theme path resolves to its explicit category; no external includes are followed. */
    @SuppressWarnings("unchecked")
    private static void categoryReference(PackDocument document, Map<String, String> categories, String field, String category) {
        Map<String, Object> values = (Map<String, Object>) document.value();
        if (values.containsKey(field) && !category.equals(categories.get(values.get(field)))) {
            throw new PackException("FILE_REFERENCE", document.location("/" + field), "Reference requires category " + category);
        }
    }

    /**
     * Require that a file categorized {@code presentation} is the one the manifest names, so a second or
     * unreferenced declaration cannot sit in the pack unread while appearing admitted.
     */
    @SuppressWarnings("unchecked")
    private static void presentationFile(PackDocument document, Map<String, String> categories) {
        Object named = ((Map<String, Object>) document.value()).get("presentation");
        for (var entry : categories.entrySet()) {
            if (entry.getValue().equals("presentation") && !entry.getKey().equals(named)) {
                throw new PackException("FILE_REFERENCE", document.location("/files"),
                        "A presentation file must be the single file named by the manifest's presentation field");
            }
        }
    }

    /** Require unique enabled-module IDs and structured configuration files; module semantics are not accepted here. */
    @SuppressWarnings("unchecked")
    private static void moduleReferences(PackDocument document, Map<String, String> categories) {
        var values = (Map<String, Object>) document.value();
        var modules = (List<Map<String, Object>>) values.get("modules");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < modules.size(); index++) {
            var module = modules.get(index);
            String pointer = "/modules/" + index;
            if (!ids.add((String) module.get("id"))) {
                throw new PackException("FILE_REFERENCE", document.location(pointer + "/id"), "Duplicate module ID");
            }
            if (module.containsKey("configuration")) {
                String category = categories.get(module.get("configuration"));
                if (category == null || PROSE.contains(category) || category.equals("asset")) {
                    throw new PackException("FILE_REFERENCE", document.location(pointer + "/configuration"), "Module configuration requires a listed structured file");
                }
            }
        }
    }

    /** Build an inventory-level diagnostic when no parsed field coordinates exist yet. */
    private static PackException error(String code, String file, String message) {
        return new PackException(code, new SourceLocation(file, 1, 1, ""), message);
    }
}
