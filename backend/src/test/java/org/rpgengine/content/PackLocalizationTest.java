package org.rpgengine.content;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify localized pack compilation, policy enforcement and fallback using real neutral fixture bytes.
 * Inputs are private mutable test inventories; outputs remain immutable catalogs/definitions.
 * Invalid shape, authority, scope, placeholders or policy must fail with located PackExceptions.
 */
class PackLocalizationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Construct an isolated corpus without a database, network or runtime dependency. */
    PackLocalizationTest() {
    }

    /** Optional overlays leave source mechanics, IDs, visibility and unchanged file hashes intact. */
    @Test
    void acceptsOptionalIdBasedOverlay() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        var source = compile(files);
        overlay(files, "it", List.of(entry("fieldwork", "/name", "Conoscenza dei sentieri")));
        var localized = compile(files);
        assertEquals(source.byId(), localized.byId());
        assertEquals(source.generationPolicy(), localized.generationPolicy());
        assertEquals("Trailcraft", localized.byId().get("fieldwork").data().get("name"));
        var text = localized.localization().resolve("fieldwork", "/name", "it");
        assertEquals("Conoscenza dei sentieri", text.text());
        assertEquals("it", text.locale());
        assertFalse(text.fallback());
        assertNotEquals(source.parsed().contentHash(), localized.parsed().contentHash());
        assertEquals(source.parsed().fileHashes().get("skills.json"), localized.parsed().fileHashes().get("skills.json"));
    }

    /** Both actual fixture inventories have exact reviewed resources for every approved leaf in each language. */
    @ParameterizedTest
    @ValueSource(strings = {"fantasy-exploration", "science-fiction-salvage"})
    void coversBothNeutralGenres(String name) throws Exception {
        var compiled = compile(fixture(name));
        var localization = compiled.localization();
        var report = localization.report();
        for (var coverage : coverage(report)) {
            assertEquals(coverage.get("total"), coverage.get("overlaid"));
            var fallbacks = (List<?>) coverage.get("sourceFallbacks");
            assertEquals(coverage.get("locale").equals("en") ? 0 : 1, fallbacks.size());
        }
        assertTrue(((List<?>) report.get("sourceFiles")).contains("lore.md"));
        assertEquals(name.equals("fantasy-exploration") ? "Conoscenza dei sentieri" : "Sistemi",
                localization.resolve("fieldwork", "/name", "it").text());
        assertEquals("Raggiungi il sito da esaminare.", localization.resolve("survey", "/objectives/reach/description", "it").text());
        assertEquals("term", localization.glossary().get(new PackLocalization.Key("fieldwork", "/name")).kind());
        assertEquals("nome di abilità", localization.glossary().get(new PackLocalization.Key("fieldwork", "/name")).grammar().get("it"));
        assertEquals(List.of(), compiled.warnings());
    }

    /** Missing translations remain valid, report exact source fallback, and missing source keys fail explicitly. */
    @Test
    void reportsPartialAndAbsentOverlaysWithoutInventingText() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        overlay(files, "it", List.of(entry("fieldwork", "/name", "Conoscenza dei sentieri")));
        var localization = compile(files).localization();
        var fallback = localization.resolve("fieldwork", "/description", "it");
        assertEquals("Read paths and natural signs.", fallback.text());
        assertEquals("en", fallback.locale());
        assertTrue(fallback.fallback());
        assertFalse(localization.resolve("fieldwork", "/description", "en").fallback());
        assertEquals("MISSING_TEXT", assertThrows(PackException.class,
                () -> localization.resolve("missing", "/name", "it")).code());
        assertThrows(IllegalArgumentException.class, () -> localization.resolve("fieldwork", "/name", "it-IT"));
        assertFalse(((List<?>) coverage(localization.report()).get(1).get("sourceFallbacks")).isEmpty());
        var absent = compile(sourceFixture("fantasy-exploration")).localization();
        assertTrue(absent.resolve("fieldwork", "/name", "it").fallback());
    }

    /** The fallback source is the declared Italian source, rather than an assumed English default. */
    @Test
    void fallsBackToItalianSource() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        var manifest = read(files, "adventure.json");
        manifest.put("contentLocale", "it");
        write(files, "adventure.json", manifest);
        var skills = read(files, "skills.json");
        data(skills, 0).put("name", "Conoscenza dei sentieri");
        write(files, "skills.json", skills);
        var text = compile(files).localization().resolve("fieldwork", "/name", "en");
        assertEquals("Conoscenza dei sentieri", text.text());
        assertEquals("it", text.locale());
        assertTrue(text.fallback());
    }

    /** Mechanical, audience, identity and noncanonical/indexed paths are never legal translation targets. */
    @ParameterizedTest
    @ValueSource(strings = {"/maxRank", "/attribute", "/knowledge", "/visibility", "/id", "/effects/0/amount",
            "/links/0/to", "/objectives/reach/when", "/objectives/0/description", "/appearance/id", "/name/", "/Name"})
    void rejectsForbiddenFields(String field) throws Exception {
        var files = sourceFixture("fantasy-exploration");
        overlay(files, "it", List.of(entry("fieldwork", field, "altered")));
        assertLocated(files, "TEXT_TARGET", "locales/it.json", "/entries/0/field");
    }

    /** Targets must belong to this pack; translations cannot create IDs or override dependency exports. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "other:pack#fieldwork", "$pack-unknown"})
    void rejectsUnknownAndExternalIdentities(String id) throws Exception {
        var files = sourceFixture("fantasy-exploration");
        overlay(files, "it", List.of(entry(id, "/name", "testo")));
        assertLocated(files, "TEXT_TARGET", "locales/it.json", "/entries/0/field");
    }

    /** One locale cannot override a key twice, even when the entries come from distinct declared files. */
    @Test
    void rejectsDuplicatesAcrossOverlayFiles() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        overlay(files, "it", List.of(entry("fieldwork", "/name", "Uno"), entry("fieldwork", "/name", "Due")));
        assertLocated(files, "DUPLICATE", "locales/it.json", "/entries/1/id");
        overlay(files, "it", List.of(entry("fieldwork", "/name", "Uno")));
        var manifest = read(files, "adventure.json");
        manifest.put("localizations", Map.of("it", List.of("locales/it.json", "extra.json")));
        fileReferences(manifest).add(Map.of("path", "extra.json", "category", "localization"));
        write(files, "adventure.json", manifest);
        write(files, "extra.json", Map.of("schemaVersion", 1, "entries", List.of(entry("fieldwork", "/name", "Due"))));
        assertLocated(files, "DUPLICATE", "extra.json", "/entries/0/id");
    }

    /** Closed overlay objects reject injected authority and metadata fields even when a text target is valid. */
    @ParameterizedTest
    @ValueSource(strings = {"visibility", "knowledge", "effects", "owner", "quotePolicy", "kind"})
    void rejectsAdditionalOverlayFields(String field) throws Exception {
        var files = sourceFixture("fantasy-exploration");
        var entry = new TreeMap<String, Object>(entry("fieldwork", "/name", "Sentieri"));
        entry.put(field, "public");
        overlay(files, "it", List.of(entry));
        assertLocated(files, "SCHEMA", "locales/it.json", "/entries/0/" + field);
    }

    /** Unsupported locales and unbound/reused/wrong-category file references fail before admission. */
    @Test
    void rejectsUnboundAndInvalidLocaleFiles() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        overlay(files, "it", List.of());
        var manifest = read(files, "adventure.json");
        manifest.put("localizations", Map.of("fr", List.of("locales/it.json")));
        write(files, "adventure.json", manifest);
        assertLocated(files, "SCHEMA", "adventure.json", "/localizations/fr");
        manifest.put("localizations", Map.of());
        write(files, "adventure.json", manifest);
        assertLocated(files, "FILE_REFERENCE", "adventure.json", "/files");
        manifest.put("localizations", Map.of("it", List.of("skills.json")));
        write(files, "adventure.json", manifest);
        assertLocated(files, "FILE_REFERENCE", "adventure.json", "/localizations/it");
        manifest.put("localizations", Map.of("en", List.of("locales/it.json"), "it", List.of("locales/it.json")));
        write(files, "adventure.json", manifest);
        assertLocated(files, "FILE_REFERENCE", "adventure.json", "/localizations/it");
        manifest.put("localizations", Map.of("it", List.of("../it.json")));
        write(files, "adventure.json", manifest);
        assertLocated(files, "SCHEMA", "adventure.json", "/localizations/it/0");
    }

    /** Verbatim quotes keep exact bytes and actual source locale; translatable quotes accept reviewed alternate text. */
    @Test
    void enforcesQuotePolicies() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        resources(files, "Password: {key}", "quote", "secret");
        quotePolicy(files, "verbatim");
        overlay(files, "it", List.of(entry("text.sample", "/text", "Password: {key}")));
        var result = compile(files).localization().resolve("text.sample", "/text", "it");
        assertEquals("en", result.locale());
        assertEquals("verbatim", result.quotePolicy());
        assertEquals("secret", result.visibility());
        assertTrue(result.fallback());
        overlay(files, "it", List.of(entry("text.sample", "/text", "Parola d’ordine: {key}")));
        assertLocated(files, "TEXT_POLICY", "locales/it.json", "/entries/0/text");
        quotePolicy(files, "translate");
        assertEquals("Parola d’ordine: {key}", compile(files).localization().resolve("text.sample", "/text", "it").text());
        var source = read(files, "texts.json");
        source.remove("quotes");
        write(files, "texts.json", source);
        assertLocated(files, "TEXT_POLICY", "texts.json", "/quotes");
    }

    /** Protected proper names stay unchanged while grammatical and pronunciation hints remain immutable. */
    @Test
    void enforcesGlossaryIdentityAndHints() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        resources(files, "Choose carefully.", "tutorial", "public");
        var source = read(files, "texts.json");
        source.put("glossary", List.of(Map.of("id", "fieldwork", "field", "/name", "kind", "properName",
                "grammar", Map.of("it", "nome proprio"), "pronunciation", Map.of("en", "trail-craft"))));
        write(files, "texts.json", source);
        overlay(files, "it", List.of(entry("fieldwork", "/name", "Trailcraft")));
        var localization = compile(files).localization();
        var key = new PackLocalization.Key("fieldwork", "/name");
        assertEquals("nome proprio", localization.glossary().get(key).grammar().get("it"));
        assertThrows(UnsupportedOperationException.class, () -> localization.glossary().get(key).grammar().put("it", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> localization.glossary().clear());
        overlay(files, "it", List.of(entry("fieldwork", "/name", "Sentieri")));
        assertLocated(files, "TEXT_POLICY", "locales/it.json", "/entries/0/text");
    }

    /** Translation must retain named placeholder identity and multiplicity; source text is never executed. */
    @ParameterizedTest
    @ValueSource(strings = {"Ciao", "Ciao {other}", "Ciao {name} {name}"})
    void rejectsChangedPlaceholders(String text) throws Exception {
        var files = sourceFixture("fantasy-exploration");
        resources(files, "Hello {name}", "dialogue", "public");
        overlay(files, "it", List.of(entry("text.sample", "/text", text)));
        assertLocated(files, "TEXT_PLACEHOLDER", "locales/it.json", "/entries/0/text");
    }

    /** Private text remains source-owned and private, and reports contain keys/counts without secret prose. */
    @Test
    void preservesPrivateAudienceAndRejectsPublicDowngrade() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        resources(files, "SECRET_SOURCE_PHRASE", "dialogue", "secret");
        overlay(files, "it", List.of(entry("text.sample", "/text", "FRASE_RISERVATA")));
        var localization = compile(files).localization();
        var resolved = localization.resolve("text.sample", "/text", "it");
        assertEquals("secret", resolved.visibility());
        assertEquals("trailhead", resolved.owner());
        assertFalse(JSON.writeValueAsString(localization.report()).contains("SECRET_SOURCE_PHRASE"));
        assertFalse(JSON.writeValueAsString(localization.report()).contains("FRASE_RISERVATA"));
        var locations = read(files, "locations.json");
        data(locations, 0).put("knowledge", "secret");
        write(files, "locations.json", locations);
        resources(files, "Secret owner", "dialogue", "public");
        assertLocated(files, "VISIBILITY", "texts.json", "/texts/0/visibility");
    }

    /** Source policy targets and hint locales are closed; missing owners and identity collisions are located errors. */
    @Test
    void rejectsInvalidSourcePolicyData() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        resources(files, "Hello", "dialogue", "public");
        var source = read(files, "texts.json");
        source.put("glossary", List.of(Map.of("id", "fieldwork", "field", "/maxRank", "kind", "term")));
        write(files, "texts.json", source);
        assertLocated(files, "TEXT_TARGET", "texts.json", "/glossary/0/field");
        source.put("glossary", List.of(Map.of("id", "fieldwork", "field", "/name", "kind", "term", "grammar", Map.of("fr", "nom"))));
        write(files, "texts.json", source);
        assertLocated(files, "SCHEMA", "texts.json", "/glossary/0/grammar/fr");
        source.remove("glossary");
        source.put("texts", List.of(Map.of("id", "fieldwork", "owner", "trailhead", "kind", "dialogue", "visibility", "public", "text", "Hello")));
        write(files, "texts.json", source);
        assertLocated(files, "DUPLICATE", "texts.json", "/texts/0/id");
        source.put("texts", List.of(Map.of("id", "new.text", "owner", "missing", "kind", "dialogue", "visibility", "public", "text", "Hello")));
        write(files, "texts.json", source);
        assertLocated(files, "REFERENCE_UNKNOWN", "texts.json", "/texts/0/owner");
    }

    /** Report object fields have a fixed lexical order across JVMs, including nested fallback identities. */
    @Test
    void ordersSerializedCoverageDeterministically() throws Exception {
        var report = compile(sourceFixture("fantasy-exploration")).localization().report();
        assertEquals(List.of("coverage", "sourceFiles", "sourceLocale"), List.copyOf(report.keySet()));
        for (var locale : coverage(report)) {
            assertEquals(List.of("locale", "overlaid", "sourceFallbacks", "total"), List.copyOf(locale.keySet()));
            for (var fallback : (List<?>) locale.get("sourceFallbacks")) {
                assertEquals(List.of("field", "id"), List.copyOf(((Map<?, ?>) fallback).keySet()));
            }
        }
    }

    /** Nested text keys follow stable child IDs even when the source array order changes. */
    @Test
    void resolvesNestedIdsAfterSourceReordering() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        var rules = read(files, "rules.json");
        var awareness = Map.of("id", "awareness", "name", "Awareness", "min", 0, "max", 4);
        var focus = Map.of("id", "focus", "name", "Focus", "min", 0, "max", 4);
        data(rules, 0).put("attributes", List.of(awareness, focus));
        write(files, "rules.json", rules);
        overlay(files, "it", List.of(entry("rules.attributes", "/attributes/awareness/name", "Consapevolezza")));
        var before = compile(files).localization().resolve("rules.attributes", "/attributes/awareness/name", "it");
        data(rules, 0).put("attributes", List.of(focus, awareness));
        write(files, "rules.json", rules);
        assertEquals(before, compile(files).localization().resolve("rules.attributes", "/attributes/awareness/name", "it"));
    }

    /** Duplicate glossary/quote bindings and contradictory policies fail at their source declarations. */
    @Test
    void rejectsAmbiguousSourcePolicies() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        resources(files, "Hello", "dialogue", "public");
        var source = read(files, "texts.json");
        var term = Map.of("id", "fieldwork", "field", "/name", "kind", "properName");
        source.put("glossary", List.of(term, term));
        write(files, "texts.json", source);
        assertLocated(files, "DUPLICATE", "texts.json", "/glossary/1/id");
        source.put("glossary", List.of(term));
        var quote = Map.of("id", "fieldwork", "field", "/name", "policy", "verbatim");
        source.put("quotes", List.of(quote, quote));
        write(files, "texts.json", source);
        assertLocated(files, "DUPLICATE", "texts.json", "/quotes/1/id");
        source.put("quotes", List.of(Map.of("id", "fieldwork", "field", "/name", "policy", "translate")));
        write(files, "texts.json", source);
        assertLocated(files, "TEXT_POLICY", "texts.json", "/quotes/0/policy");
    }

    /** Lexically valid but unsupported source locales fail compilation before an unusable catalog is returned. */
    @Test
    void rejectsUnsupportedSourceLanguage() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        var manifest = read(files, "adventure.json");
        manifest.put("contentLocale", "fr");
        write(files, "adventure.json", manifest);
        assertLocated(files, "UNSUPPORTED_LOCALE", "adventure.json", "/contentLocale");
    }

    /** Text bounds and schema version are enforced by the existing located semantic reader. */
    @Test
    void rejectsMalformedAndOversizedOverlay() throws Exception {
        var files = sourceFixture("fantasy-exploration");
        overlay(files, "it", List.of(entry("fieldwork", "/name", " ")));
        assertLocated(files, "SCHEMA", "locales/it.json", "/entries/0/text");
        overlay(files, "it", List.of(entry("fieldwork", "/name", "x".repeat(4097))));
        assertLocated(files, "SCHEMA", "locales/it.json", "/entries/0/text");
        write(files, "locales/it.json", Map.of("schemaVersion", 2, "entries", List.of()));
        assertLocated(files, "SCHEMA", "locales/it.json", "/schemaVersion");
    }

    /** Compile one test inventory through the production parser; validation errors propagate to the caller. */
    private static CompiledPack compile(Map<String, byte[]> files) {
        return new PackCompiler().compile(new PackParser().parse(files));
    }

    /** Require exact diagnostic code and source field; missing or misplaced errors fail the assertion. */
    private static void assertLocated(Map<String, byte[]> files, String code, String file, String pointer) {
        var error = assertThrows(PackException.class, () -> compile(files));
        assertEquals(code, error.code(), error.getMessage());
        assertEquals(file, error.location().file());
        assertEquals(pointer, error.location().pointer());
        assertTrue(error.location().line() > 0);
        assertTrue(error.location().column() > 0);
    }

    /** Read actual fixture bytes through protected directory staging; unavailable resources fail the test. */
    private static Map<String, byte[]> fixture(String name) throws Exception {
        var root = Path.of(PackLocalizationTest.class.getResource("/packs/" + name).toURI());
        return new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(root).files());
    }

    /** Remove optional localization files from a fixture copy to test compatibility with single-language inventories. */
    private static Map<String, byte[]> sourceFixture(String name) throws Exception {
        var files = fixture(name);
        var manifest = read(files, "adventure.json");
        manifest.remove("localizations");
        manifest.remove("textResources");
        fileReferences(manifest).removeIf(file -> {
            if (List.of("localization", "textResources").contains(file.get("category"))) {
                files.remove((String) file.get("path"));
                return true;
            }
            return false;
        });
        write(files, "adventure.json", manifest);
        return files;
    }

    /** Create a stable target/text overlay entry; callers can add malicious fields to private copies. */
    private static Map<String, Object> entry(String id, String field, String text) {
        return Map.of("id", id, "field", field, "text", text);
    }

    /** Bind or replace one locale overlay while retaining explicit manifest file categorization. */
    private static void overlay(Map<String, byte[]> files, String locale, List<Map<String, Object>> entries) {
        var manifest = read(files, "adventure.json");
        String path = "locales/" + locale + ".json";
        manifest.put("localizations", Map.of(locale, List.of(path)));
        if (fileReferences(manifest).stream().noneMatch(file -> path.equals(file.get("path")))) {
            fileReferences(manifest).add(Map.of("path", path, "category", "localization"));
        }
        write(files, "adventure.json", manifest);
        write(files, path, Map.of("schemaVersion", 1, "entries", entries));
    }

    /** Bind a simple source-owned text file for glossary, quote and audience cases; invalid values are intentional inputs. */
    private static void resources(Map<String, byte[]> files, String text, String kind, String visibility) {
        var manifest = read(files, "adventure.json");
        manifest.put("textResources", "texts.json");
        if (fileReferences(manifest).stream().noneMatch(file -> "texts.json".equals(file.get("path")))) {
            fileReferences(manifest).add(Map.of("path", "texts.json", "category", "textResources"));
        }
        write(files, "adventure.json", manifest);
        write(files, "texts.json", Map.of("schemaVersion", 1, "texts", List.of(Map.of("id", "text.sample",
                "owner", "trailhead", "kind", kind, "visibility", visibility, "text", text))));
    }

    /** Set an explicit quote policy for the source sample; unknown policy strings remain testable inputs. */
    private static void quotePolicy(Map<String, byte[]> files, String policy) {
        var source = read(files, "texts.json");
        source.put("quotes", List.of(Map.of("id", "text.sample", "field", "/text", "policy", policy)));
        write(files, "texts.json", source);
    }

    /** Decode mutable test JSON copies; parser/type failures are test construction errors. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Map<String, byte[]> files, String path) {
        return JSON.readValue(files.get(path), Map.class);
    }

    /** Serialize private test data without modifying source fixture files or weakening production parsing. */
    private static void write(Map<String, byte[]> files, String path, Object value) {
        files.put(path, JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    }

    /** Expose a mutable test manifest's file array; only known test JSON is cast. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fileReferences(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("files");
    }

    /** Expose one mutable definition data object for intentional source-language/audience mutations. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> document, int index) {
        return (Map<String, Object>) ((List<Map<String, Object>>) document.get("definitions")).get(index).get("data");
    }

    /** Read typed coverage metadata produced by the catalog, failing tests if the contract shape changes. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> coverage(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("coverage");
    }
}
