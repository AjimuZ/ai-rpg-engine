package org.rpgengine.content;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonFactory;

/**
 * Bounded, data-only contract-1 UTF-8 parsing. YAML events bypass constructors and the
 * library's YAML 1.1 resolver entirely. Only JSON-shaped values can leave this boundary.
 * Invalid syntax, encoding or limits produce PackException with file/pointer context.
 */
public final class PackTextParser {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 20_000;
    public static final int MAX_SCALAR = 65_536;
    private static final Pattern INTEGER = Pattern.compile("[-+]?[0-9]+");
    private static final Pattern FLOAT = Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?");
    private static final Pattern NON_FINITE = Pattern.compile("[-+]?\\.(?:inf|Inf|INF|nan|NaN|NAN)");
    private static final JsonFactory JSON = JsonFactory.builder().build();

    /** Construct a stateless parser; all mutable cursors and budgets belong to individual calls. */
    public PackTextParser() {
    }

    /** Parse one .yaml or .json document from raw bytes; reject unsupported extensions and all invalid input. */
    public PackDocument parse(String file, byte[] bytes) {
        String text = decode(file, bytes);
        Cursor cursor = new Cursor(file);
        try {
            Object value;
            if (file.endsWith(".yaml")) {
                // Directives are unnecessary in this fixed-version contract. Rejecting all
                // avoids a parser accepting a 1.1 directive while applying our 1.2 resolver.
                if (Pattern.compile("(?m)^%").matcher(text).find()) {
                    throw cursor.fail("YAML_FEATURE", "", "Directives are not supported");
                }
                LoaderOptions options = new LoaderOptions();
                options.setCodePointLimit(MAX_BYTES);
                Parser parser = new ParserImpl(new StreamReader(text), options);
                require(parser.getEvent(), Event.ID.StreamStart, cursor, "");
                require(parser.getEvent(), Event.ID.DocumentStart, cursor, "");
                value = yaml(parser, cursor, "", 0);
                require(parser.getEvent(), Event.ID.DocumentEnd, cursor, "");
                require(parser.getEvent(), Event.ID.StreamEnd, cursor, "");
            } else if (file.endsWith(".json")) {
                try (JsonParser parser = JSON.createParser(text)) {
                    parser.nextToken();
                    value = json(parser, cursor, "", 0);
                    if (parser.nextToken() != null) {
                        throw cursor.fail("SYNTAX", "", "Exactly one JSON document is required");
                    }
                }
            } else {
                throw cursor.fail("FILE_TYPE", "", "Expected .yaml or .json");
            }
            return new PackDocument(value, cursor.locations);
        } catch (MarkedYAMLException failure) {
            var mark = failure.getProblemMark();
            cursor.locations.put(cursor.pointer, new SourceLocation(file, mark.getLine() + 1,
                    mark.getColumn() + 1, cursor.pointer));
            throw cursor.fail("SYNTAX", cursor.pointer, "Malformed YAML");
        } catch (org.yaml.snakeyaml.error.YAMLException failure) {
            throw cursor.fail("SYNTAX", cursor.pointer, "Malformed YAML");
        } catch (JacksonException failure) {
            var mark = failure.getLocation();
            if (mark != null && mark.getLineNr() > 0 && mark.getColumnNr() > 0) {
                cursor.locations.put(cursor.pointer, new SourceLocation(file, mark.getLineNr(),
                        mark.getColumnNr(), cursor.pointer));
            }
            throw cursor.fail("SYNTAX", cursor.pointer, "Malformed JSON");
        }
    }

    /** Decode plain Markdown without rendering or fetching links; reject BOM, bad UTF-8 and leading front matter. */
    public String markdown(String file, byte[] bytes) {
        String text = decode(file, bytes);
        if (Pattern.compile("\\A\\s*---[ \\t]*(?:\\r?\\n|\\z)").matcher(text).find()) {
            throw new PackException("FRONT_MATTER", new SourceLocation(file, 1, 1, ""), "Metadata belongs in structured files");
        }
        return text;
    }

    /** Decode a bounded text file without replacing bad bytes; failures never include the source contents. */
    private static String decode(String file, byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new PackException("LIMIT", new SourceLocation(file, 1, 1, ""), "Text exceeds 1 MiB");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) {
                throw new PackException("ENCODING", new SourceLocation(file, 1, 1, ""), "UTF-8 BOM is forbidden");
            }
            return text;
        } catch (CharacterCodingException failure) {
            throw new PackException("ENCODING", new SourceLocation(file, 1, 1, ""), "Expected valid UTF-8");
        }
    }

    /** Consume one YAML node recursively within call-local budgets; arbitrary tags/aliases never reach a constructor. */
    private static Object yaml(Parser parser, Cursor cursor, String pointer, int depth) {
        Event event = parser.getEvent();
        cursor.mark(pointer, depth, event.getStartMark().getLine() + 1, event.getStartMark().getColumn() + 1);
        if ((event instanceof NodeEvent node && node.getAnchor() != null) || event instanceof AliasEvent) {
            throw cursor.fail("YAML_FEATURE", pointer, "Anchors and aliases are forbidden");
        }
        if (event instanceof ScalarEvent scalar) {
            if (scalar.getTag() != null) {
                throw cursor.fail("YAML_FEATURE", pointer, "Explicit tags are forbidden");
            }
            cursor.string(scalar.getValue(), pointer);
            return scalar.isPlain() ? core(scalar.getValue(), cursor, pointer) : scalar.getValue();
        }
        if (event instanceof CollectionStartEvent collection && collection.getTag() != null) {
            throw cursor.fail("YAML_FEATURE", pointer, "Explicit tags are forbidden");
        }
        if (event instanceof MappingStartEvent) {
            Map<String, Object> values = new LinkedHashMap<>();
            while (!parser.checkEvent(Event.ID.MappingEnd)) {
                Event keyEvent = parser.peekEvent();
                // Key parsing consumes a node but must not replace the containing object's
                // source location. That location is used for missing-field diagnostics.
                SourceLocation parent = cursor.locations.get(pointer);
                Object key = yaml(parser, cursor, pointer, depth + 1);
                cursor.locations.put(pointer, parent);
                if (!(key instanceof String name)) {
                    throw cursor.fail("KEY", pointer, "Mapping keys must be strings");
                }
                String child = PackDocument.child(pointer, name);
                cursor.locations.put(child, new SourceLocation(cursor.file, keyEvent.getStartMark().getLine() + 1,
                        keyEvent.getStartMark().getColumn() + 1, child));
                if (name.equals("<<") || values.containsKey(name)) {
                    throw cursor.fail("KEY", child, "Merge and duplicate keys are forbidden");
                }
                values.put(name, yaml(parser, cursor, child, depth + 1));
            }
            parser.getEvent();
            return Collections.unmodifiableMap(values);
        }
        if (event instanceof SequenceStartEvent) {
            List<Object> values = new ArrayList<>();
            while (!parser.checkEvent(Event.ID.SequenceEnd)) {
                values.add(yaml(parser, cursor, PackDocument.child(pointer, Integer.toString(values.size())), depth + 1));
            }
            parser.getEvent();
            return Collections.unmodifiableList(values);
        }
        throw cursor.fail("SYNTAX", pointer, "Expected a YAML value");
    }

    /** Resolve only YAML 1.2 core plain scalars to JSON-compatible exact values; reject nonfinite/oversized numbers. */
    private static Object core(String value, Cursor cursor, String pointer) {
        if (value.isEmpty() || value.equals("~") || value.equals("null") || value.equals("Null") || value.equals("NULL")) {
            return null;
        }
        if (value.equals("true") || value.equals("True") || value.equals("TRUE")) {
            return true;
        }
        if (value.equals("false") || value.equals("False") || value.equals("FALSE")) {
            return false;
        }
        if (NON_FINITE.matcher(value).matches()) {
            throw cursor.fail("NUMBER", pointer, "Nonfinite numbers have no JSON equivalent");
        }
        if (INTEGER.matcher(value).matches()) {
            return number(value, true, cursor, pointer);
        }
        if (value.matches("0o[0-7]+") || value.matches("0x[0-9a-fA-F]+")) {
            if (value.length() > 128) {
                throw cursor.fail("LIMIT", pointer, "Number exceeds 128 characters");
            }
            return new BigInteger(value.substring(2), value.charAt(1) == 'o' ? 8 : 16);
        }
        if (FLOAT.matcher(value).matches()) {
            return number(value, false, cursor, pointer);
        }
        return value;
    }

    /** Consume strict JSON tokens into immutable data; duplicate names are checked before their values are read. */
    private static Object json(JsonParser parser, Cursor cursor, String pointer, int depth) {
        var mark = parser.currentTokenLocation();
        cursor.mark(pointer, depth, Math.max(1, mark.getLineNr()), Math.max(1, mark.getColumnNr()));
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            Map<String, Object> values = new LinkedHashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                    throw cursor.fail("SYNTAX", pointer, "Expected an object property");
                }
                String name = parser.currentName();
                String child = PackDocument.child(pointer, name);
                var keyMark = parser.currentTokenLocation();
                cursor.mark(child, depth + 1, keyMark.getLineNr(), keyMark.getColumnNr());
                cursor.string(name, child);
                if (values.containsKey(name) || name.equals("<<")) {
                    throw cursor.fail("KEY", child, "Merge and duplicate keys are forbidden");
                }
                parser.nextToken();
                values.put(name, json(parser, cursor, child, depth + 1));
            }
            return Collections.unmodifiableMap(values);
        }
        if (token == JsonToken.START_ARRAY) {
            List<Object> values = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                values.add(json(parser, cursor, PackDocument.child(pointer, Integer.toString(values.size())), depth + 1));
            }
            return Collections.unmodifiableList(values);
        }
        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getString();
            cursor.string(value, pointer);
            return value;
        }
        if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
            return token == JsonToken.VALUE_TRUE;
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return number(parser.getString(), token == JsonToken.VALUE_NUMBER_INT, cursor, pointer);
        }
        throw cursor.fail("SYNTAX", pointer, "Expected a JSON value");
    }

    /** Bound exact numeric parsing and exponent magnitude before downstream arithmetic; fail without rounding. */
    private static Number number(String text, boolean integer, Cursor cursor, String pointer) {
        if (text.length() > 128) {
            throw cursor.fail("LIMIT", pointer, "Number exceeds 128 characters");
        }
        try {
            if (integer) {
                return new BigInteger(text);
            }
            BigDecimal value = new BigDecimal(text);
            if (Math.abs((long) value.scale()) > 1000) {
                throw cursor.fail("LIMIT", pointer, "Number scale exceeds 1000");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw cursor.fail("NUMBER", pointer, "Invalid or oversized number");
        }
    }

    /** Require a stream/document delimiter; wrong or absent events fail closed rather than accepting another root. */
    private static void require(Event event, Event.ID expected, Cursor cursor, String pointer) {
        if (event == null || !event.is(expected)) {
            throw cursor.fail("SYNTAX", pointer, "Exactly one YAML document is required");
        }
    }

    /** Call-local source index and work budget, never shared across concurrent parser calls. */
    private static final class Cursor {
        private final String file;
        private final Map<String, SourceLocation> locations = new LinkedHashMap<>();
        private String pointer = "";
        private int nodes;

        /** Initialize the filename and a root fallback for errors before any value is available. */
        private Cursor(String file) {
            this.file = file;
            locations.put("", new SourceLocation(file, 1, 1, ""));
        }

        /** Record a consumed node and enforce depth/count budgets before recursing or allocating a collection. */
        private void mark(String path, int depth, int line, int column) {
            pointer = path;
            locations.put(path, new SourceLocation(file, line, column, path));
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw fail("LIMIT", path, "Document exceeds depth 32 or 20000 nodes");
            }
        }

        /** Validate bounded Unicode scalar strings; escaped lone surrogates must not pass through JSON parsing. */
        private void string(String value, String path) {
            if (value.length() > MAX_SCALAR) {
                throw fail("LIMIT", path, "Scalar exceeds 65536 UTF-16 units");
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                        throw fail("ENCODING", path, "Unpaired Unicode surrogate");
                    }
                } else if (Character.isLowSurrogate(character)) {
                    throw fail("ENCODING", path, "Unpaired Unicode surrogate");
                }
            }
        }

        /** Build a safe author error at the last known location; only trusted explanatory text is included. */
        private PackException fail(String code, String path, String message) {
            SourceLocation location = locations.getOrDefault(path, locations.get(""));
            return new PackException(code, new SourceLocation(file, location.line(), location.column(), path), message);
        }
    }
}
