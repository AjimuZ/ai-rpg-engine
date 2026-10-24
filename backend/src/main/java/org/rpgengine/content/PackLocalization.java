package org.rpgengine.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compile optional EN/IT overlays into a separate immutable text catalog, never definition patches.
 * Inputs are validated local definitions and parser documents; failures are source-located
 * PackExceptions. No I/O, rendering or authorization occurs here. Consumers must authorize the
 * source owner and field before lookup: possession of a translation is never visibility evidence.
 */
public final class PackLocalization {
    private static final List<String> LOCALES = List.of("en", "it");
    private final String sourceLocale;
    private final List<String> sourceFiles;
    private final Map<Key, SourceText> sources;
    private final Map<String, Map<Key, String>> overlays;
    private final Map<Key, Glossary> glossary;
    private final Map<Key, String> quotes;

    /** Snapshot already validated inputs; maps and nested policy values cannot be changed by callers. */
    private PackLocalization(String sourceLocale, List<String> sourceFiles, Map<Key, SourceText> sources,
            Map<String, Map<Key, String>> overlays, Map<Key, Glossary> glossary, Map<Key, String> quotes) {
        this.sourceLocale = sourceLocale;
        this.sourceFiles = List.copyOf(sourceFiles);
        this.sources = Map.copyOf(sources);
        Map<String, Map<Key, String>> copy = new TreeMap<>();
        overlays.forEach((locale, entries) -> copy.put(locale, Map.copyOf(entries)));
        this.overlays = Map.copyOf(copy);
        this.glossary = Map.copyOf(glossary);
        this.quotes = Map.copyOf(quotes);
    }

    /** Build a catalog from source definitions and declared files; throw located errors on any invalid text policy. */
    static PackLocalization compile(ParsedPack pack, Map<String, Definition> definitions) {
        return new Builder(pack, definitions).compile();
    }

    /** Return immutable glossary bindings; these carry source-owner scope and are not a public projection. */
    public Map<Key, Glossary> glossary() {
        return glossary;
    }

    /**
     * Resolve an already authorized text key in exact en/it, otherwise the declared source language.
     * Verbatim quotes retain source locale even when repeated in an overlay. Unknown keys throw an
     * explicit MISSING_TEXT diagnostic; unsupported locale arguments throw IllegalArgumentException.
     */
    public ResolvedText resolve(String id, String field, String locale) {
        if (!LOCALES.contains(locale)) {
            throw new IllegalArgumentException("Selected pack locale must be en or it");
        }
        Key key = new Key(id, field);
        SourceText source = sources.get(key);
        if (source == null) {
            throw new PackException("MISSING_TEXT", new SourceLocation("<text-catalog>", 1, 1, field),
                    "No source text for " + id + " " + field);
        }
        String policy = quotes.getOrDefault(key, "none");
        String translated = overlays.getOrDefault(locale, Map.of()).get(key);
        boolean useSource = translated == null || policy.equals("verbatim");
        String actualLocale = useSource ? sourceLocale : locale;
        return new ResolvedText(useSource ? source.text() : translated, actualLocale,
                !locale.equals(actualLocale), source.owner(), source.visibility(), source.kind(), policy);
    }

    /**
     * Return deterministic privileged import metadata: sorted keys/counts, never private text or hints.
     * Coverage includes only catalog leaves; source-only Markdown files are explicitly listed.
     */
    public Map<String, Object> report() {
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (String locale : LOCALES) {
            List<Map<String, String>> fallbacks = new ArrayList<>();
            for (Key key : new TreeMap<>(sources).keySet()) {
                if (resolve(key.id(), key.field(), locale).fallback()) {
                    fallbacks.add(ordered(Map.of("id", key.id(), "field", key.field())));
                }
            }
            coverage.add(ordered(Map.of("locale", locale, "total", sources.size(), "overlaid",
                    overlays.getOrDefault(locale, Map.of()).size(), "sourceFallbacks", List.copyOf(fallbacks))));
        }
        return ordered(Map.of("sourceLocale", sourceLocale, "coverage", List.copyOf(coverage), "sourceFiles", sourceFiles));
    }

    /**
     * Snapshot non-null report fields in lexical order, returning an immutable map with stable iteration.
     * Values supplied here are already immutable; unlike Map.copyOf, this preserves order across JVMs.
     */
    private static <T> Map<String, T> ordered(Map<String, T> values) {
        return java.util.Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }

    /** Stable source definition/resource identity and allowlisted field path; sorting is locale-independent. */
    public record Key(String id, String field) implements Comparable<Key> {
        /** Require non-null identity components; syntax and existence are validated during compilation. */
        public Key {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(field, "field");
        }

        /** Order keys by identity then field, returning the ordinary lexical comparison without failures. */
        @Override
        public int compareTo(Key other) {
            int result = id.compareTo(other.id);
            return result == 0 ? field.compareTo(other.field) : result;
        }
    }

    /** Text with authoritative audience/owner provenance; it is internal content, never an authorization token. */
    private record SourceText(String text, String owner, String visibility, String kind) {
    }

    /** Source-bound terminology metadata; hint maps contain only supported locale keys. */
    public record Glossary(String kind, Map<String, String> grammar, Map<String, String> pronunciation) {
        /** Freeze hint maps so successful compilation cannot later acquire unvalidated policy values. */
        public Glossary {
            grammar = Map.copyOf(grammar);
            pronunciation = Map.copyOf(pronunciation);
        }
    }

    /** Resolved text plus actual language, fallback flag, source audience and protected-quote policy. */
    public record ResolvedText(String text, String locale, boolean fallback, String owner,
            String visibility, String kind, String quotePolicy) {
    }

    /** Per-compilation collector; all mutable maps are discarded after the immutable result is built. */
    private static final class Builder {
        private final ParsedPack pack;
        private final Map<String, Definition> definitions;
        private final PackDocument manifest;
        private final Map<Key, SourceText> sources = new TreeMap<>();
        private final Map<String, Map<Key, String>> overlays = new TreeMap<>();
        private final Map<Key, Glossary> glossary = new TreeMap<>();
        private final Map<Key, String> quotes = new TreeMap<>();

        /** Capture immutable parser/compiler input; subsequent operations report failures at these source documents. */
        Builder(ParsedPack pack, Map<String, Definition> definitions) {
            this.pack = pack;
            this.definitions = definitions;
            this.manifest = pack.documents().get(pack.documents().containsKey("adventure.json")
                    ? "adventure.json" : "adventure.yaml");
        }

        /** Collect source leaves before resolving policies/overlays; reject unreferenced text files as well as bad entries. */
        @SuppressWarnings("unchecked")
        PackLocalization compile() {
            String locale = (String) pack.manifest().values().get("contentLocale");
            if (!LOCALES.contains(locale)) {
                throw new PackException("UNSUPPORTED_LOCALE", manifest.location("/contentLocale"),
                        "Pack source locale must be en or it");
            }
            add("$pack", "", pack.manifest().values(), "$pack", "public", "manifest", "title", "summary");
            add("$pack", "/entrypoint", (Map<String, Object>) pack.manifest().values().get("entrypoint"),
                    "$pack", "public", "manifest", "situation");
            definitions.values().forEach(this::definition);
            String resources = (String) pack.manifest().values().get("textResources");
            if (resources != null) {
                resources(pack.documents().get(resources));
            }
            Map<String, List<String>> declarations = (Map<String, List<String>>) pack.manifest().values()
                    .getOrDefault("localizations", Map.of());
            Set<String> used = new java.util.HashSet<>();
            if (resources != null) {
                used.add(resources);
            }
            for (String selected : LOCALES) {
                for (String path : declarations.getOrDefault(selected, List.of())) {
                    String pointer = "/localizations/" + selected;
                    if (!used.add(path) || !category(path, "localization")) {
                        throw new PackException("FILE_REFERENCE", manifest.location(pointer),
                                "Overlay must name a unique listed localization file");
                    }
                    overlay(pack.documents().get(path), selected);
                }
            }
            for (var file : pack.manifest().files()) {
                if (Set.of("localization", "textResources").contains(file.category()) && !used.contains(file.path())) {
                    throw new PackException("FILE_REFERENCE", manifest.location("/files"),
                            "Text file is not bound by the manifest: " + file.path());
                }
            }
            return new PackLocalization(locale, pack.prose().keySet().stream().sorted().toList(),
                    sources, overlays, glossary, quotes);
        }

        /** Determine one file's explicit category without following paths or accepting external resources. */
        private boolean category(String path, String category) {
            return pack.manifest().files().stream().anyMatch(file -> file.path().equals(path)
                    && file.category().equals(category));
        }

        /** Enumerate approved source leaves explicitly; mechanical strings, facts without IDs and visibility are excluded. */
        @SuppressWarnings("unchecked")
        private void definition(Definition definition) {
            var data = definition.data();
            String visibility = (String) data.getOrDefault("knowledge", data.getOrDefault("visibility", "public"));
            add(definition.id(), "", data, definition.id(), visibility, definition.kind(), "name", "description", "summary");
            if (definition.kind().equals("npc")) {
                add(definition.id(), "", data, definition.id(), visibility, "npc", "role");
            }
            if (definition.kind().equals("item") && data.get("appearance") instanceof Map<?, ?> appearance) {
                add(definition.id(), "/appearance", (Map<String, Object>) appearance,
                        definition.id(), visibility, "item", "summary");
            }
            List<String> groups = switch (definition.kind()) {
                case "rule" -> List.of("attributes", "resources", "tiers");
                case "creation" -> List.of("archetypes");
                case "quest" -> List.of("objectives", "outcomes");
                default -> List.of();
            };
            for (String group : groups) {
                for (Map<String, Object> child : (List<Map<String, Object>>) data.getOrDefault(group, List.of())) {
                    // Nested stable IDs survive reordering; numeric array offsets are never overlay keys.
                    add(definition.id(), "/" + group + "/" + child.get("id"), child,
                            definition.id(), visibility, definition.kind(), "name", "description");
                }
            }
        }

        /** Register existing allowlisted strings only; callers cannot manufacture new fields via an overlay. */
        private void add(String id, String prefix, Map<String, Object> data, String owner,
                String visibility, String kind, String... fields) {
            for (String field : fields) {
                if (data.get(field) instanceof String text) {
                    sources.put(new Key(id, prefix + "/" + field), new SourceText(text, owner, visibility, kind));
                }
            }
        }

        /** Wrap an object with the existing closed, bounded semantic reader; no references are resolved implicitly. */
        private Fields fields(PackDocument document) {
            Fields fields = new Fields(document, "", document.value(), null);
            fields.integer("schemaVersion", 1, 1, null);
            return fields;
        }

        /** Read source-owned dialogue/tutorial/quote texts and policies, rejecting duplicate or missing identities. */
        private void resources(PackDocument document) {
            Fields root = fields(document);
            Set<String> ids = new java.util.HashSet<>(definitions.keySet());
            for (Fields entry : root.objects("texts", 0, 10000)) {
                String id = entry.id("id", true);
                if (!ids.add(id)) {
                    throw entry.fail("DUPLICATE", "id", "Text ID collides with another local identity");
                }
                String owner = entry.id("owner", true);
                Definition definition = definitions.get(owner);
                if (definition == null) {
                    throw entry.fail("REFERENCE_UNKNOWN", "owner", "Text owner must be a local definition");
                }
                String visibility = entry.choice("visibility", Set.of("public", "secret"), null);
                if ("secret".equals(definition.data().getOrDefault("knowledge", definition.data().get("visibility")))
                        && visibility.equals("public")) {
                    throw entry.fail("VISIBILITY", "visibility", "Text cannot expose a secret owner");
                }
                String kind = entry.choice("kind", Set.of("dialogue", "tutorial", "quote"), null);
                String text = entry.text("text", true);
                sources.put(new Key(id, "/text"), new SourceText(text, owner, visibility, kind));
                entry.close();
            }
            for (Fields entry : root.objects("glossary", 0, 10000)) {
                Key key = target(entry);
                String kind = entry.choice("kind", Set.of("properName", "term"), null);
                Glossary value = new Glossary(kind, hints(entry.object("grammar", false)),
                        hints(entry.object("pronunciation", false)));
                if (glossary.putIfAbsent(key, value) != null) {
                    throw entry.fail("DUPLICATE", "id", "Duplicate glossary target");
                }
                entry.close();
            }
            for (Fields entry : root.objects("quotes", 0, 10000)) {
                Key key = target(entry);
                String policy = entry.choice("policy", Set.of("verbatim", "translate"), null);
                if (quotes.putIfAbsent(key, policy) != null) {
                    throw entry.fail("DUPLICATE", "id", "Duplicate quote target");
                }
                if (policy.equals("translate") && glossary.containsKey(key)
                        && glossary.get(key).kind().equals("properName")) {
                    throw entry.fail("TEXT_POLICY", "policy", "Proper names cannot require translation");
                }
                entry.close();
            }
            // A quote is uninterpretable without an explicit preservation/translation policy.
            for (var entry : sources.entrySet()) {
                if (entry.getValue().kind().equals("quote") && !quotes.containsKey(entry.getKey())) {
                    throw root.fail("TEXT_POLICY", "quotes", "Every source quote needs an explicit policy");
                }
            }
            root.close();
        }

        /** Read optional locale-scoped grammatical/pronunciation hints; reject unsupported locales and unknown fields. */
        private Map<String, String> hints(Fields fields) {
            if (fields == null) {
                return Map.of();
            }
            Map<String, String> result = new TreeMap<>();
            for (String locale : LOCALES) {
                String text = fields.text(locale, false);
                if (text != null) {
                    result.put(locale, text);
                }
            }
            fields.close();
            return result;
        }

        /** Bind an entry to an existing approved text leaf; mechanical fields and dependency targets fail closed. */
        private Key target(Fields entry) {
            Key key = new Key(entry.text("id", true), entry.text("field", true));
            if (!sources.containsKey(key)) {
                throw entry.fail("TEXT_TARGET", "field", "Unknown local text identity or forbidden field");
            }
            return key;
        }

        /** Count named {token} slots without interpreting text; malformed/literal braces remain ordinary prose. */
        private Map<String, Integer> placeholders(String text) {
            Map<String, Integer> counts = new TreeMap<>();
            var matcher = java.util.regex.Pattern.compile("\\{[A-Za-z][A-Za-z0-9_.-]*\\}").matcher(text);
            while (matcher.find()) {
                counts.merge(matcher.group(), 1, Integer::sum);
            }
            return counts;
        }

        /** Compile one overlay, rejecting duplicate keys, policy changes and protected-text rewrites at their source. */
        private void overlay(PackDocument document, String locale) {
            Fields root = fields(document);
            Map<Key, String> entries = overlays.computeIfAbsent(locale, ignored -> new LinkedHashMap<>());
            for (Fields entry : root.objects("entries", 0, 10000)) {
                Key key = target(entry);
                String text = entry.text("text", true);
                // Named placeholders are data slots, not translatable words or executable templates.
                if (!placeholders(sources.get(key).text()).equals(placeholders(text))) {
                    throw entry.fail("TEXT_PLACEHOLDER", "text", "Named placeholder identities and counts must match source");
                }
                boolean protectedName = glossary.containsKey(key) && glossary.get(key).kind().equals("properName");
                if ((protectedName || "verbatim".equals(quotes.get(key))) && !sources.get(key).text().equals(text)) {
                    throw entry.fail("TEXT_POLICY", "text", "Protected source text must remain byte-for-byte identical");
                }
                if (entries.putIfAbsent(key, text) != null) {
                    throw entry.fail("DUPLICATE", "id", "Duplicate overlay target in this locale");
                }
                entry.close();
            }
            root.close();
        }
    }
}
