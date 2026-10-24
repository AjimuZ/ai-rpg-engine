package org.rpgengine.content;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise untrusted contract-1 input without storage or external services; failures block admission work. */
class PackParsingTest {
    /** Construct an independent parser corpus; no application context is required. */
    PackParsingTest() {
    }

    /** Encode source text as contract UTF-8; returns fresh bytes without platform-dependent newlines. */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Supply the smallest manifest-stage pack; semantic references deliberately await P04.2. */
    private static Map<String, byte[]> pack() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("adventure.yaml", bytes("""
                schemaVersion: 1
                id: example:harbor
                version: 1.0.0
                title: Harbor
                summary: A quiet harbor.
                authors: [Example Authors]
                license: MIT
                engineCompatibility: '>=0.1.0 <1.0.0'
                ruleset: {id: skirmish, version: '>=1.0.0 <2.0.0', policyProfile: standard}
                contentLocale: en
                narrativeMode: emergent
                entrypoint: {situation: arrival, location: harbor, creationProfile: visitor}
                files:
                  - {path: world.yaml, category: locations}
                  - {path: generation.json, category: generationPolicy}
                  - {path: lore.md, category: lore}
                modules: []
                generationPolicy: generation.json
                """));
        files.put("world.yaml", bytes("schemaVersion: 1\ndefinitions: []\n"));
        files.put("generation.json", bytes("{}"));
        files.put("lore.md", bytes("# Harbor\nA quiet place.\n"));
        return files;
    }

    /** Parse the bounded manifest stage and retain explicit categories and immutable nested values. */
    @Test
    void readsManifestAndRawHashes() {
        var result = new PackParser().parse(pack());
        assertEquals("example:harbor", result.manifest().id());
        assertEquals("locations", result.manifest().files().get(0).category());
        assertEquals(64, result.contentHash().length());
        assertEquals(4, result.fileHashes().size());
        assertThrows(UnsupportedOperationException.class, () -> result.manifest().values().put("id", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> result.documents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<?, ?>) result.manifest().values().get("ruleset")).clear());
        assertThrows(UnsupportedOperationException.class, () -> result.fileHashes().clear());
    }

    /** YAML and JSON share the same schema/value model; integer-valued decimals obey JSON Schema integer semantics. */
    @Test
    void acceptsEquivalentJsonManifest() {
        var files = pack();
        var yaml = new PackParser().parse(files);
        files.remove("adventure.yaml");
        byte[] json = JsonMapper.builder().build().writeValueAsBytes(yaml.manifest().values());
        files.put("adventure.json", json);
        var parsed = new PackParser().parse(files);
        assertEquals(yaml.manifest().values(), parsed.manifest().values());
        assertNotEquals(yaml.contentHash(), parsed.contentHash());
        files.put("adventure.json", bytes(new String(json, StandardCharsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":1.0")));
        assertEquals("example:harbor", new PackParser().parse(files).manifest().id());
    }

    /** Removing any required manifest field must fail at that field rather than accepting an implicit default. */
    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "id", "version", "title", "summary", "authors", "license",
            "engineCompatibility", "ruleset", "contentLocale", "narrativeMode", "entrypoint", "files", "modules", "generationPolicy"})
    void requiresEveryManifestField(String field) {
        var files = pack();
        var values = new LinkedHashMap<>(new PackParser().parse(files).manifest().values());
        values.remove(field);
        files.remove("adventure.yaml");
        files.put("adventure.json", JsonMapper.builder().build().writeValueAsBytes(values));
        PackException failure = assertThrows(PackException.class, () -> new PackParser().parse(files));
        assertEquals("/" + field, failure.location().pointer());
    }

    /** Manifest records remain closed and bounded even when JSON/YAML parsing itself succeeds. */
    @ParameterizedTest
    @ValueSource(strings = {"version: 01.0.0", "version: 1.0.0-01", "id: bare", "title: ''", "authors: []",
            "authors: [same, same]", "narrativeMode: scripted", "contentLocale: en_XX",
            "ruleset: {id: skirmish, version: '*', policyProfile: standard, executable: true}",
            "entrypoint: {situation: arrival, location: harbor}"})
    void rejectsInvalidManifestValues(String replacement) {
        var files = pack();
        String field = replacement.substring(0, replacement.indexOf(':'));
        String source = new String(files.get("adventure.yaml"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^" + field + ":.*$", java.util.regex.Matcher.quoteReplacement(replacement));
        files.put("adventure.yaml", bytes(source));
        assertThrows(PackException.class, () -> new PackParser().parse(files));
    }

    /** Whole-value identifiers and versions cannot exploit regex end anchors that precede a final line terminator. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "version", "contentLocale"})
    void rejectsTrailingLineTerminators(String field) {
        for (String terminator : new String[] {"\n", "\r", "\r\n", "\u2028", "\u2029"}) {
            var files = pack();
            var values = new LinkedHashMap<>(new PackParser().parse(files).manifest().values());
            values.put(field, values.get(field) + terminator);
            files.remove("adventure.yaml");
            files.put("adventure.json", JsonMapper.builder().build().writeValueAsBytes(values));
            PackException failure = assertThrows(PackException.class, () -> new PackParser().parse(files));
            assertEquals("/" + field, failure.location().pointer());
        }
    }

    /** Envelope validation rejects unknown wrapper fields but deliberately does not claim mechanical data validation. */
    @Test
    void publishesAndEvaluatesDefinitionEnvelope() {
        var parser = new PackTextParser();
        PackSchemas.definitions(parser.parse("definitions.yaml", bytes("""
                schemaVersion: 1
                definitions:
                  - {id: harbor, kind: location, data: {name: Harbor}}
                """)));
        for (String source : new String[] {"schemaVersion: 2\ndefinitions: []", "schemaVersion: 1\ndefinitions: [{}]",
                "schemaVersion: 1\ndefinitions: [{id: x, kind: script, data: {}}]", "schemaVersion: 1\ndefinitions: []\nscript: x"}) {
            assertThrows(PackException.class, () -> PackSchemas.definitions(parser.parse("definitions.yaml", bytes(source))));
        }
        var files = pack();
        files.put("world.yaml", bytes("schemaVersion: 1\ndefinitions: [{id: harbor, kind: location, data: {name: Harbor}}]"));
        assertDoesNotThrow(() -> new PackParser().parse(files));
        files.put("world.yaml", bytes("schemaVersion: 1\ndefinitions: [{id: harbor, kind: script, data: {}}]"));
        PackException failure = assertThrows(PackException.class, () -> new PackParser().parse(files));
        assertEquals("world.yaml", failure.location().file());
        assertEquals("/definitions/0/kind", failure.location().pointer());
        var invalidPolicy = pack();
        invalidPolicy.put("generation.json", bytes("[]"));
        assertThrows(PackException.class, () -> new PackParser().parse(invalidPolicy));
    }

    /** YAML core typing must avoid the default YAML 1.1 booleans, timestamps and sexagesimal numbers. */
    @Test
    void usesCoreScalarsWithoutObjectConstruction() {
        var document = new PackTextParser().parse("data.yaml", bytes("""
                a: yes
                b: no
                c: on
                d: off
                e: 0777
                f: 1:30
                g: 2026-10-24
                h: 0o17
                i: 0x10
                j: TRUE
                k: null
                l: 'true'
                """));
        var map = (Map<?, ?>) document.value();
        assertEquals("yes", map.get("a"));
        assertEquals("no", map.get("b"));
        assertEquals("on", map.get("c"));
        assertEquals("off", map.get("d"));
        assertEquals(new BigInteger("777"), map.get("e"));
        assertEquals("1:30", map.get("f"));
        assertEquals("2026-10-24", map.get("g"));
        assertEquals(new BigInteger("15"), map.get("h"));
        assertEquals(new BigInteger("16"), map.get("i"));
        assertEquals(true, map.get("j"));
        assertNull(map.get("k"));
        assertEquals("true", map.get("l"));
    }

    /** Reject YAML capabilities even when they would otherwise resolve to harmless standard types. */
    @ParameterizedTest
    @ValueSource(strings = {"a: &x 1", "a: *x", "a: !!str hi", "a: !custom hi", "a: ! hi",
            "a: {<<: {b: 1}}", "a: 1\na: 2", "a: 1\n'a': 2", "true: x", "[a, b]: x",
            "---\na: 1\n---\nb: 2", "a: .inf", "a: .NaN", "%YAML 1.1\n---\na: yes"})
    void rejectsUnsafeYaml(String source) {
        PackException failure = assertThrows(PackException.class,
                () -> new PackTextParser().parse("attack.yaml", bytes(source)));
        assertEquals("attack.yaml", failure.location().file());
        assertTrue(failure.location().line() > 0);
    }

    /** JSON uses strict RFC syntax, string keys, unique properties and one complete root. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1,\"a\":2}", "{\"a\":{\"b\":1,\"b\":2}}", "{} {}", "//x\n{}",
            "{'a':1}", "{\"a\":NaN}", "{\"a\":1,}", "{\"a\":01}", "{\"a\":\"\\uD800\"}", ""})
    void rejectsUnsafeJson(String source) {
        assertThrows(PackException.class, () -> new PackTextParser().parse("attack.json", bytes(source)));
    }

    /** Report duplicate keys and schema failures by stable file/line/column/JSON-pointer context. */
    @Test
    void preservesDiagnostics() {
        PackException duplicate = assertThrows(PackException.class,
                () -> new PackTextParser().parse("bad.yaml", bytes("a: 1\na: 2")));
        assertEquals("/a", duplicate.location().pointer());
        assertEquals(2, duplicate.location().line());
        var files = pack();
        files.put("adventure.yaml", bytes(new String(files.get("adventure.yaml"), StandardCharsets.UTF_8)
                .replace("schemaVersion: 1", "schemaVersion: 2")));
        PackException version = assertThrows(PackException.class, () -> new PackParser().parse(files));
        assertEquals("/schemaVersion", version.location().pointer());
        assertEquals("SCHEMA", version.code());
        PackException nested = assertThrows(PackException.class,
                () -> new PackTextParser().parse("bad.json", bytes("{\n\"a/b~c\": {\"x\":1,\"x\":2}}")));
        assertEquals("/a~1b~0c/x", nested.location().pointer());
        assertEquals(2, nested.location().line());
        assertTrue(nested.location().column() > 1);
        assertFalse(nested.getMessage().contains("a/b~c"));
    }

    /** Reject malformed encoding, unsupported suffixes and front matter before downstream prose use. */
    @Test
    void enforcesTextBoundary() {
        var parser = new PackTextParser();
        assertThrows(PackException.class, () -> parser.parse("a.json", new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(PackException.class, () -> parser.parse("a.yaml", bytes("\ufeffa: 1")));
        assertThrows(PackException.class, () -> parser.parse("a.yml", bytes("a: 1")));
        assertThrows(PackException.class, () -> parser.markdown("a.md", bytes("\n---\na: 1\n---\ntext")));
        assertEquals("<b>text</b>", parser.markdown("a.md", bytes("<b>text</b>")));
    }

    /** Bound recursion, total nodes, individual scalars and numeric work before excessive allocation. */
    @Test
    void enforcesParserLimits() {
        var parser = new PackTextParser();
        for (String suffix : new String[] {"yaml", "json"}) {
            assertThrows(PackException.class, () -> parser.parse("a." + suffix, bytes("[".repeat(40) + "0" + "]".repeat(40))));
            assertThrows(PackException.class, () -> parser.parse("a." + suffix, bytes("[" + "0,".repeat(20001) + "0]")));
            assertThrows(PackException.class, () -> parser.parse("a." + suffix, bytes("\"" + "x".repeat(65537) + "\"")));
            assertThrows(PackException.class, () -> parser.parse("a." + suffix, bytes("1e999999999")));
        }
        assertThrows(PackException.class, () -> parser.parse("a.yaml", new byte[1048577]));
        assertEquals("x".repeat(65536), parser.parse("ok.json", bytes("\"" + "x".repeat(65536) + "\"")).value());
        assertDoesNotThrow(() -> parser.parse("ok.yaml", bytes("[".repeat(32) + "0" + "]".repeat(32))));
    }

    /** The normalized inventory has deterministic order but hashes every raw byte, including comments and CRLF. */
    @Test
    void hashesRawBytesInSortedPathOrder() {
        var parser = new PackParser();
        var original = pack();
        String hash = parser.parse(original).contentHash();
        var reversed = new LinkedHashMap<String, byte[]>();
        original.keySet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(k -> reversed.put(k, original.get(k)));
        assertEquals(hash, parser.parse(reversed).contentHash());
        original.put("lore.md", bytes("# Harbor\r\nA quiet place.\r\n"));
        assertNotEquals(hash, parser.parse(original).contentHash());
        var commented = pack();
        commented.put("adventure.yaml", bytes(new String(commented.get("adventure.yaml"), StandardCharsets.UTF_8) + "# comment\n"));
        assertNotEquals(hash, parser.parse(commented).contentHash());
    }

    /** Pin an independently computed Python hashlib/struct vector, including binary content and filename framing. */
    @Test
    void pinsPortableRawHashVector() {
        var hashes = PackHashes.calculate(Map.of("adventure.json", bytes("{}"), "assets/x.bin", new byte[] {0, 1, (byte) 255}));
        assertEquals("82417ed5e0d9f0cf135be2148afa70ce04e1be6b4b9047c2351d94fdd8e86e15", hashes.contentHash());
        assertEquals("26a66b061e8f48f39927c312f25293959729eee95978e2892d49d3512a5cc092", hashes.fileHashes().get("assets/x.bin"));
        var files = pack();
        String source = new String(files.get("adventure.yaml"), StandardCharsets.UTF_8)
                .replace("modules: []", "modules: []\n");
        files.put("adventure.yaml", bytes(source.replace("modules: []", "  - {path: assets/x.bin, category: asset}\nmodules: []")));
        files.put("assets/x.bin", new byte[] {0, 1, (byte) 255});
        String first = new PackParser().parse(files).contentHash();
        files.get("assets/x.bin")[0] = 1;
        assertNotEquals(first, new PackParser().parse(files).contentHash());
    }

    /** Resolve only categorized local references, rejecting duplicates and case/path aliases before hashing. */
    @Test
    void rejectsAmbiguousPathsAndPolicyReferences() {
        for (String path : new String[] {"/world.yaml", "C:/world.yaml", "world\\x.yaml", "a/../x.yaml", "a//x.yaml", "a/./x.yaml", "a/"}) {
            var files = pack();
            files.put(path, bytes("{}"));
            assertThrows(PackException.class, () -> new PackParser().parse(files));
        }
        var files = pack();
        files.put("World.yaml", bytes("{}"));
        assertThrows(PackException.class, () -> new PackParser().parse(files));
        for (String replacement : new String[] {"generationPolicy: missing.json", "generationPolicy: world.yaml",
                "modules: [{id: guide, configuration: lore.md}]", "modules: [{id: guide}, {id: guide}]",
                "presentationProfile: generation.json"}) {
            var input = pack();
            String source = new String(input.get("adventure.yaml"), StandardCharsets.UTF_8);
            if (replacement.startsWith("modules:")) {
                source = source.replace("modules: []", replacement);
            } else if (replacement.startsWith("presentation")) {
                source += replacement + "\n";
            } else {
                source = source.replace("generationPolicy: generation.json", replacement);
            }
            input.put("adventure.yaml", bytes(source));
            assertThrows(PackException.class, () -> new PackParser().parse(input));
        }
        var duplicate = pack();
        duplicate.put("adventure.yaml", bytes(new String(duplicate.get("adventure.yaml"), StandardCharsets.UTF_8)
                .replace("modules: []", "  - {path: world.yaml, category: locations}\nmodules: []")));
        assertThrows(PackException.class, () -> new PackParser().parse(duplicate));
    }

    /** Unknown fields, ambiguous manifests, unlisted bytes, missing files and mismatched categories fail closed. */
    @Test
    void rejectsInvalidInventoriesAndManifestFields() {
        for (String mutation : new String[] {"schemaVersion: '1'", "schemaVersion: 0", "schemaVersion: true"}) {
            var files = pack();
            files.put("adventure.yaml", bytes(new String(files.get("adventure.yaml"), StandardCharsets.UTF_8)
                    .replace("schemaVersion: 1", mutation)));
            assertThrows(PackException.class, () -> new PackParser().parse(files));
        }
        var unknown = pack();
        unknown.put("adventure.yaml", bytes(new String(unknown.get("adventure.yaml"), StandardCharsets.UTF_8) + "script: x\n"));
        assertThrows(PackException.class, () -> new PackParser().parse(unknown));
        var both = pack();
        both.put("adventure.json", bytes("{}"));
        assertThrows(PackException.class, () -> new PackParser().parse(both));
        var unlisted = pack();
        unlisted.put("extra.json", bytes("{}"));
        assertThrows(PackException.class, () -> new PackParser().parse(unlisted));
        var missing = pack();
        missing.remove("world.yaml");
        assertThrows(PackException.class, () -> new PackParser().parse(missing));
        for (String replacement : new String[] {"category: typo", "category: lore", "path: ../world.yaml"}) {
            var files = pack();
            String source = new String(files.get("adventure.yaml"), StandardCharsets.UTF_8);
            files.put("adventure.yaml", bytes(replacement.startsWith("path")
                    ? source.replace("path: world.yaml", replacement) : source.replace("category: locations", replacement)));
            assertThrows(PackException.class, () -> new PackParser().parse(files));
        }
    }
}
