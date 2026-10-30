package org.rpgengine.content;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rpgengine.presentation.PresentationEvent;
import org.rpgengine.presentation.PublicPresentationScope;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify the optional public presentation declaration of a pack (P05.7-B.1.2.1): it is parsed and compiled
 * into the pack and the campaign snapshot, unsafe or unbounded references are refused with a located
 * diagnostic that never repeats the value, and a pack without it compiles as before.
 */
class PackPresentationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FILE = "presentation.json";
    private static final String GENERATION_FILE = "{\"path\":\"generation.json\",\"category\":\"generationPolicy\"}";
    private static final String PRESENTATION_FILE = "{\"path\":\"presentation.json\",\"category\":\"presentation\"}";

    /** Construct an independent corpus; no application context or storage is needed. */
    PackPresentationTest() {
    }

    /** Scene, line and asset references and per-actor looks are parsed, compiled and reach the snapshot. */
    @Test
    void compilesDeclarationIntoPackAndSnapshot() {
        var compiled = compile(bytes(valid(), true, true));
        var declared = compiled.presentation();
        assertEquals(Set.of("harbor-dawn", "market-day"), declared.scenes());
        assertEquals(Set.of("greeting.01", "farewell"), declared.lines());
        assertEquals(Set.of("harbor-backdrop", "greeting-voice"), declared.assets());
        assertEquals(Map.of("harbor-dawn", "harbor-backdrop"), declared.sceneBackdrops());
        assertEquals(Map.of("greeting.01", "greeting-voice"), declared.lineAudio());
        assertEquals(Set.of("keeper"), declared.actors().keySet());
        var looks = declared.actors().get("keeper");
        assertEquals(Set.of("idle", "wave"), looks.poses());
        assertEquals(Set.of("calm", "smile"), looks.expressions());
        assertSame(declared, CampaignPackSnapshot.local(compiled).root().presentation());
        assertThrows(UnsupportedOperationException.class, () -> declared.scenes().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> declared.actors().clear());
        // The compiled references satisfy the allowlist the events are later checked against.
        var scope = new PublicPresentationScope(declared.scenes(),
                Map.of(UUID.randomUUID(), new PublicPresentationScope.ActorLooks(looks.poses(), looks.expressions())),
                declared.lines(), declared.assets());
        assertEquals(declared.assets(), scope.assets());
    }

    /** Every part of the declaration is optional, so a file holding only the version is a valid empty one. */
    @Test
    void acceptsEmptyDeclaration() {
        var declared = compile(bytes(new LinkedHashMap<>(Map.of("schemaVersion", 1)), true, true)).presentation();
        assertEquals(PackPresentation.NONE, declared);
    }

    /** A pack without the declaration compiles to the empty one and keeps every file hash. */
    @Test
    void leavesPacksWithoutDeclarationUnchanged() {
        var plain = compile(bytes(null, false, false));
        assertSame(PackPresentation.NONE, plain.presentation());
        assertEquals(12, plain.definitions().size());
        var declaring = compile(bytes(valid(), true, true));
        assertEquals(plain.byId().keySet(), declaring.byId().keySet());
        assertEquals(plain.generationPolicy(), declaring.generationPolicy());
        // The declaration is a new file, so only the manifest and the new file differ from a pack without it.
        for (var entry : plain.parsed().fileHashes().entrySet()) {
            if (!entry.getKey().equals("adventure.json")) {
                assertEquals(entry.getValue(), declaring.parsed().fileHashes().get(entry.getKey()), entry.getKey());
            }
        }
        assertNotEquals(plain.parsed().contentHash(), declaring.parsed().contentHash());
        assertEquals(plain.parsed().contentHash(), compile(bytes(null, false, false)).parsed().contentHash());
    }

    /** A URL, markup, path or malformed value is refused in every reference position without echoing it. */
    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/a.png", "javascript:alert(1)", "<img src=x onerror=alert(1)>",
            "../../etc/passwd", "a/b.png", "file:///c:/secrets", "-leading", ".dot", "tab\there", ""})
    void refusesUnsafeReferencesWithoutEchoingThem(String value) {
        for (String where : List.of("scene", "line", "asset", "backdrop", "audio", "pose", "expression")) {
            var declaration = placed(where, value);
            var error = assertThrows(PackException.class, () -> compile(bytes(declaration, true, true)), where);
            assertEquals("SCHEMA", error.code(), where);
            assertEquals(FILE, error.location().file(), where);
            assertEquals(pointer(where), error.location().pointer(), where);
            if (!value.isEmpty()) {
                assertFalse(error.getMessage().contains(value), where + ": " + error.getMessage());
            }
        }
    }

    /** A reference one character over the bound is refused; the longest allowed one is accepted. */
    @Test
    void refusesOverLongReference() {
        String longest = "x".repeat(PresentationEvent.MAX_REFERENCE);
        assertTrue(compile(bytes(placed("scene", longest), true, true)).presentation().scenes().contains(longest));
        String tooLong = longest + "x";
        var error = assertThrows(PackException.class, () -> compile(bytes(placed("scene", tooLong), true, true)));
        assertEquals("SCHEMA", error.code());
        assertEquals("/scenes/0/id", error.location().pointer());
        assertFalse(error.getMessage().contains(tooLong));
    }

    /** At most 256 entries per collection are accepted and one more is refused at the collection. */
    @Test
    void refusesMoreEntriesThanTheScopeAllows() {
        int max = PublicPresentationScope.MAX_ENTRIES;
        assertEquals(max, compile(bytes(many("assets", max), true, true)).presentation().assets().size());
        assertEquals(max, compile(bytes(many("scenes", max), true, true)).presentation().scenes().size());
        assertEquals(max, compile(bytes(many("lines", max), true, true)).presentation().lines().size());
        for (String collection : List.of("assets", "scenes", "lines")) {
            var error = assertThrows(PackException.class, () -> compile(bytes(many(collection, max + 1), true, true)));
            assertEquals("SCHEMA", error.code(), collection);
            assertEquals("/" + collection, error.location().pointer());
        }
        for (String list : List.of("poses", "expressions")) {
            var ok = valid();
            actor(ok).put(list, names(max));
            var looks = compile(bytes(ok, true, true)).presentation().actors().get("keeper");
            assertEquals(max, (list.equals("poses") ? looks.poses() : looks.expressions()).size());
            var over = valid();
            actor(over).put(list, names(max + 1));
            var error = assertThrows(PackException.class, () -> compile(bytes(over, true, true)));
            assertEquals("/actors/0/" + list, error.location().pointer());
        }
        var actors = valid();
        actors.put("actors", IntStream.range(0, max + 1).mapToObj(index -> (Object) new LinkedHashMap<>(Map.of(
                "actor", "keeper"))).toList());
        assertEquals("/actors", assertThrows(PackException.class, () -> compile(bytes(actors, true, true)))
                .location().pointer());
    }

    /** A backdrop or audio naming an asset the declaration does not list is refused without echoing it. */
    @Test
    void refusesUndeclaredAssets() {
        var backdrop = valid();
        scene(backdrop).put("backdrop", "unlisted-asset");
        var error = assertThrows(PackException.class, () -> compile(bytes(backdrop, true, true)));
        assertEquals("REFERENCE_UNKNOWN", error.code());
        assertEquals("/scenes/0/backdrop", error.location().pointer());
        assertFalse(error.getMessage().contains("unlisted-asset"));
        var audio = valid();
        line(audio).put("audio", "unlisted-asset");
        error = assertThrows(PackException.class, () -> compile(bytes(audio, true, true)));
        assertEquals("REFERENCE_UNKNOWN", error.code());
        assertEquals("/lines/0/audio", error.location().pointer());
        assertFalse(error.getMessage().contains("unlisted-asset"));
    }

    /** Duplicates, undefined or non-NPC actors and unknown fields are refused with located diagnostics. */
    @Test
    void refusesDuplicatesUnknownActorsAndUnknownFields() {
        var scenes = valid();
        scenes.put("scenes", List.of(Map.of("id", "a"), Map.of("id", "a")));
        assertRefused(scenes, "DUPLICATE", "/scenes/1/id");
        var lines = valid();
        lines.put("lines", List.of(Map.of("id", "a"), Map.of("id", "a")));
        assertRefused(lines, "DUPLICATE", "/lines/1/id");
        var assets = valid();
        assets.put("assets", List.of("a", "a"));
        assertRefused(assets, "DUPLICATE", "/assets/1");
        var poses = valid();
        actor(poses).put("poses", List.of("idle", "idle"));
        assertRefused(poses, "DUPLICATE", "/actors/0/poses/1");
        var twice = valid();
        twice.put("actors", List.of(Map.of("actor", "keeper"), Map.of("actor", "keeper")));
        assertRefused(twice, "DUPLICATE", "/actors/1/actor");
        var missing = valid();
        actor(missing).put("actor", "nobody");
        assertRefused(missing, "REFERENCE_UNKNOWN", "/actors/0/actor");
        var notNpc = valid();
        actor(notNpc).put("actor", "harbor");
        assertRefused(notNpc, "REFERENCE_UNKNOWN", "/actors/0/actor");
        var external = valid();
        actor(external).put("actor", "other:pack#keeper");
        assertRefused(external, "SCHEMA", "/actors/0/actor");
        var url = valid();
        actor(url).put("actor", "https://evil.example/keeper");
        var error = assertThrows(PackException.class, () -> compile(bytes(url, true, true)));
        assertEquals("SCHEMA", error.code());
        assertFalse(error.getMessage().contains("evil.example"));
        var extra = valid();
        extra.put("script", "alert(1)");
        assertRefused(extra, "SCHEMA", "/script");
        var nested = valid();
        scene(nested).put("url", "https://evil.example/a.png");
        assertRefused(nested, "SCHEMA", "/scenes/0/url");
        var version = valid();
        version.put("schemaVersion", 2);
        assertRefused(version, "SCHEMA", "/schemaVersion");
        var notText = valid();
        notText.put("scenes", List.of(Map.of("id", 7)));
        assertRefused(notText, "SCHEMA", "/scenes/0/id");
    }

    /** A presentation file must be the single one the manifest names. */
    @Test
    void requiresTheManifestToNameTheFile() {
        var unnamed = assertThrows(PackException.class, () -> compile(bytes(valid(), true, false)));
        assertEquals("FILE_REFERENCE", unnamed.code());
        var unlisted = bytes(null, false, true);
        var error = assertThrows(PackException.class, () -> compile(unlisted));
        assertEquals("FILE_REFERENCE", error.code());
        assertEquals("/presentation", error.location().pointer());
        var second = bytes(valid(), true, true);
        second.put("more.json", "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
        second.put("adventure.json", new String(second.get("adventure.json"), StandardCharsets.UTF_8)
                .replace(PRESENTATION_FILE, PRESENTATION_FILE + ",{\"path\":\"more.json\",\"category\":\"presentation\"}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("FILE_REFERENCE", assertThrows(PackException.class, () -> compile(second)).code());
    }

    private static void assertRefused(Map<String, Object> declaration, String code, String pointer) {
        var error = assertThrows(PackException.class, () -> compile(bytes(declaration, true, true)), pointer);
        assertEquals(code, error.code(), error.getMessage());
        assertEquals(FILE, error.location().file());
        assertEquals(pointer, error.location().pointer());
    }

    private static CompiledPack compile(Map<String, byte[]> files) {
        return new PackCompiler().compile(new PackParser().parse(files));
    }

    /**
     * The reference pack as bytes, optionally with the declaration file, its category entry in the manifest
     * and the manifest field that names it.
     */
    private static Map<String, byte[]> bytes(Map<String, Object> declaration, boolean listed, boolean named) {
        var files = PackCompilerTest.sources();
        String manifest = files.get("adventure.json").replace("__DEPENDENCIES__", "");
        if (listed) {
            manifest = manifest.replace(GENERATION_FILE + "]", GENERATION_FILE + "," + PRESENTATION_FILE + "]");
        }
        if (named) {
            manifest = manifest.replace("\"generationPolicy\":\"generation.json\"",
                    "\"generationPolicy\":\"generation.json\",\"presentation\":\"presentation.json\"");
        }
        files.put("adventure.json", manifest);
        var result = new LinkedHashMap<String, byte[]>();
        files.forEach((path, text) -> result.put(path, text.getBytes(StandardCharsets.UTF_8)));
        if (declaration != null) {
            result.put(FILE, JSON.writeValueAsString(declaration).getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Map<String, Object> valid() {
        var declaration = new LinkedHashMap<String, Object>();
        declaration.put("schemaVersion", 1);
        declaration.put("assets", new ArrayList<>(List.of("harbor-backdrop", "greeting-voice")));
        declaration.put("scenes", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("id", "harbor-dawn", "backdrop", "harbor-backdrop")),
                new LinkedHashMap<>(Map.of("id", "market-day")))));
        declaration.put("lines", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("id", "greeting.01", "audio", "greeting-voice")),
                new LinkedHashMap<>(Map.of("id", "farewell")))));
        declaration.put("actors", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("actor", "keeper",
                "poses", new ArrayList<>(List.of("idle", "wave")),
                "expressions", new ArrayList<>(List.of("calm", "smile")))))));
        return declaration;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> actor(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) declaration.get("actors")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scene(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) declaration.get("scenes")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> line(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) declaration.get("lines")).getFirst();
    }

    /** A declaration holding exactly one candidate value in one position. */
    private static Map<String, Object> placed(String where, String value) {
        var declaration = new LinkedHashMap<String, Object>();
        declaration.put("schemaVersion", 1);
        switch (where) {
            case "scene" -> declaration.put("scenes", List.of(Map.of("id", value)));
            case "line" -> declaration.put("lines", List.of(Map.of("id", value)));
            case "asset" -> declaration.put("assets", List.of(value));
            case "backdrop" -> declaration.put("scenes", List.of(Map.of("id", "scene", "backdrop", value)));
            case "audio" -> declaration.put("lines", List.of(Map.of("id", "line", "audio", value)));
            case "pose" -> declaration.put("actors", List.of(Map.of("actor", "keeper", "poses", List.of(value))));
            case "expression" -> declaration.put("actors", List.of(Map.of("actor", "keeper",
                    "expressions", List.of(value))));
            default -> throw new IllegalArgumentException(where);
        }
        return declaration;
    }

    private static String pointer(String where) {
        return switch (where) {
            case "scene" -> "/scenes/0/id";
            case "line" -> "/lines/0/id";
            case "asset" -> "/assets/0";
            case "backdrop" -> "/scenes/0/backdrop";
            case "audio" -> "/lines/0/audio";
            case "pose" -> "/actors/0/poses/0";
            case "expression" -> "/actors/0/expressions/0";
            default -> throw new IllegalArgumentException(where);
        };
    }

    /** A declaration whose one collection holds {@code count} distinct references. */
    private static Map<String, Object> many(String collection, int count) {
        var declaration = new LinkedHashMap<String, Object>();
        declaration.put("schemaVersion", 1);
        declaration.put(collection, collection.equals("assets") ? names(count)
                : IntStream.range(0, count).mapToObj(index -> (Object) Map.of("id", "r" + index)).toList());
        return declaration;
    }

    private static List<Object> names(int count) {
        return IntStream.range(0, count).mapToObj(index -> (Object) ("n" + index)).toList();
    }
}
