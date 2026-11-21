package org.rpgengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enforces P15.5-A structurally: the engine names no adventure. No string literal of the backend's main Java sources is
 * an identifier or a title declared by the bundled sample or by a neutral fixture pack, and no main resource outside the
 * bundled sample folder contains a pack ID or title. The identifiers are read from the packs themselves (manifest ID,
 * title and entrypoint, every top-level definition ID, and the attribute and resource IDs their rules declare), so a new
 * fixture or definition is covered without editing this test. Comments are skipped, since an explanatory example may
 * name sample data without the engine depending on it.
 *
 * <p>Limits: the scan is textual. It does not find an identifier assembled from parts, a nested ID other than attributes
 * and resources (equipment slots, rarity tiers and quest objectives reuse ordinary words such as {@code body}), nor the
 * in-memory test packs that tests build in Java.
 */
class AdventureIndependenceTest {
    static final Path MAIN = Path.of("src/main/java/org/rpgengine");
    static final Path RESOURCES = Path.of("src/main/resources");
    static final Path SAMPLE = RESOURCES.resolve("sample-adventure");
    static final Path FIXTURES = Path.of("src/test/resources/packs");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void noEngineLiteralNamesAnAdventure() throws IOException {
        var names = new TreeSet<String>();
        var phrases = new TreeSet<String>();
        for (Path pack : packs()) {
            declared(pack, names, phrases);
        }
        // The scan must see the identifiers it guards, or a parsing regression would pass vacuously.
        assertThat(names).contains("ford-camp", "sample:lantern-ford", "traveler", "vigor", "trailhead", "data-dock");
        assertThat(phrases).contains("Lantern Ford", "Mosslight Trail", "Silent Relay", "Neon Alley");

        var leaks = new ArrayList<String>();
        List<Path> sources = files(MAIN, ".java");
        assertThat(sources).hasSizeGreaterThan(100);
        for (Path source : sources) {
            for (String literal : literals(Files.readString(source))) {
                if (names.contains(literal) || phrases.stream().anyMatch(literal::contains)) {
                    leaks.add(source + " -> \"" + literal + "\"");
                }
            }
        }
        for (Path resource : files(RESOURCES, "")) {
            if (resource.startsWith(SAMPLE)) {
                continue;
            }
            String text = Files.readString(resource);
            for (String phrase : phrases) {
                if (text.contains(phrase)) {
                    leaks.add(resource + " -> " + phrase);
                }
            }
        }
        assertThat(leaks).isEmpty();
    }

    /** The literal reader skips comments and character literals and reads text blocks whole. */
    @Test
    void literalsAreReadOutsideComments() {
        String source = """
                // "line comment"
                /* "block comment" */
                /** {@code "javadoc"} */
                char quote = '"'; char escaped = '\\'';
                String a = "plain \\" quoted"; String b = ""\"
                    block
                    ""\";
                String url = "http://host/path";
                """;
        assertThat(literals(source)).containsExactly("plain \\\" quoted", "    block\n    ", "http://host/path");
    }

    /** The bundled sample and every fixture pack folder that has a manifest. */
    static List<Path> packs() throws IOException {
        var packs = new ArrayList<Path>(List.of(SAMPLE));
        try (Stream<Path> folders = Files.list(FIXTURES)) {
            folders.filter(folder -> Files.isRegularFile(folder.resolve("adventure.json"))).sorted().forEach(packs::add);
        }
        assertThat(packs).hasSizeGreaterThan(1);
        return packs;
    }

    /** Add a pack's exact identifiers to {@code names} and its ID and title to {@code phrases}. */
    @SuppressWarnings("unchecked")
    static void declared(Path pack, Set<String> names, Set<String> phrases) throws IOException {
        Map<String, Object> manifest = JSON.readValue(Files.readAllBytes(pack.resolve("adventure.json")), Map.class);
        phrases.add((String) manifest.get("id"));
        phrases.add((String) manifest.get("title"));
        names.add((String) manifest.get("id"));
        var entrypoint = (Map<String, Object>) manifest.get("entrypoint");
        names.add((String) entrypoint.get("location"));
        names.add((String) entrypoint.get("creationProfile"));
        for (Object entry : (List<Object>) manifest.get("files")) {
            String path = (String) ((Map<String, Object>) entry).get("path");
            if (!path.endsWith(".json")) {
                continue;
            }
            Map<String, Object> document = JSON.readValue(Files.readAllBytes(pack.resolve(path)), Map.class);
            if (!(document.get("definitions") instanceof List<?> definitions)) {
                continue;
            }
            for (Object item : definitions) {
                var definition = (Map<String, Object>) item;
                names.add((String) definition.get("id"));
                if (definition.get("data") instanceof Map<?, ?> data) {
                    for (String member : List.of("attributes", "resources")) {
                        if (data.get(member) instanceof List<?> declared) {
                            for (Object value : declared) {
                                if (value instanceof Map<?, ?> named && named.get("id") instanceof String id) {
                                    names.add(id);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Every regular file below a root whose name ends with the suffix, sorted. */
    static List<Path> files(Path root, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(suffix)).sorted().toList();
        }
    }

    /**
     * The string literals of a Java source, as written (escapes are kept), outside line, block and documentation comments;
     * a text block is one literal. Character literals are skipped so {@code '"'} opens nothing.
     */
    static List<String> literals(String source) {
        var found = new ArrayList<String>();
        int index = 0;
        int length = source.length();
        while (index < length) {
            char current = source.charAt(index);
            if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                index = end < 0 ? length : end + 1;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", index)) {
                int start = source.indexOf('\n', index + 3) + 1;
                int end = source.indexOf("\"\"\"", start);
                found.add(source.substring(start, end));
                index = end + 3;
            } else if (current == '"' || current == '\'') {
                var literal = new StringBuilder();
                index++;
                while (index < length && source.charAt(index) != current) {
                    if (source.charAt(index) == '\\') {
                        literal.append('\\');
                        index++;
                    }
                    literal.append(source.charAt(index));
                    index++;
                }
                index++;
                if (current == '"') {
                    found.add(literal.toString());
                }
            } else {
                index++;
            }
        }
        return found;
    }
}
