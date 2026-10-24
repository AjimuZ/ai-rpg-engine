package org.rpgengine.content;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Typed, source-located reader over one parsed JSON object.
 * Every accessor marks its key as consumed; {@link #close()} then rejects any unconsumed key, so
 * unknown mechanical fields are errors as docs/09 requires. All failures throw PackException
 * carrying the field's file/line/pointer; nothing here touches storage or the network.
 */
final class Fields {
    /** Same identifier grammar as the definition envelope schema, for nested identities. */
    static final Pattern ID = Pattern.compile("[a-z][a-z0-9._:-]{0,127}");
    private static final int TEXT_MAX = 4096;

    /** Resolves a reference string to a definition kind; implemented by the compiler's index. */
    interface Resolver {
        /** Throw a located PackException unless {@code ref} names a definition of one of {@code kinds}. */
        void resolve(PackDocument document, String pointer, String ref, Set<String> kinds);
    }

    private final PackDocument document;
    private final String pointer;
    private final Map<?, ?> map;
    private final Resolver resolver;
    private final Set<String> seen = new HashSet<>();

    /** Wrap an object value; a non-object throws SCHEMA at the given pointer. */
    Fields(PackDocument document, String pointer, Object value, Resolver resolver) {
        if (!(value instanceof Map<?, ?> object)) {
            throw new PackException("SCHEMA", document.location(pointer), "Expected object");
        }
        this.document = document;
        this.pointer = pointer;
        this.map = object;
        this.resolver = resolver;
    }

    /** Document that owns this object, for building located diagnostics outside this class. */
    PackDocument document() {
        return document;
    }

    /** Return the RFC 6901 pointer of a child key. */
    String at(String key) {
        return PackDocument.child(pointer, key);
    }

    /** Build a located SCHEMA diagnostic for a child key (or the object itself when key is null). */
    PackException fail(String key, String message) {
        return new PackException("SCHEMA", document.location(key == null ? pointer : at(key)), message);
    }

    /** Build a located diagnostic with an explicit code for a child key. */
    PackException fail(String code, String key, String message) {
        return new PackException(code, document.location(key == null ? pointer : at(key)), message);
    }

    /** True when the key is present; marks it consumed. */
    boolean has(String key) {
        seen.add(key);
        return map.containsKey(key);
    }

    /** Required or optional non-blank text up to 4096 code points; null when optional and absent. */
    String text(String key, boolean required) {
        Object value = raw(key, required);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.codePointCount(0, text.length()) > TEXT_MAX) {
            throw fail(key, "Expected non-blank text of at most 4096 characters");
        }
        return text;
    }

    /** Required or optional identifier following the envelope ID grammar; null when optional and absent. */
    String id(String key, boolean required) {
        Object value = raw(key, required);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || !ID.matcher(text).matches()) {
            throw fail(key, "Expected a lowercase stable identifier");
        }
        return text;
    }

    /** Value restricted to a fixed vocabulary; a null default makes the key required. */
    String choice(String key, Set<String> allowed, String defaultValue) {
        Object value = raw(key, defaultValue == null);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text) || !allowed.contains(text)) {
            throw fail(key, "Unsupported value; expected one of " + new TreeSet<>(allowed));
        }
        return text;
    }

    /** Boolean with a default for absent keys. */
    boolean bool(String key, boolean defaultValue) {
        Object value = raw(key, false);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean flag)) {
            throw fail(key, "Expected boolean");
        }
        return flag;
    }

    /** Integer in an inclusive range; a null default makes the key required. */
    int integer(String key, int min, int max, Integer defaultValue) {
        Object value = raw(key, defaultValue == null);
        if (value == null) {
            return defaultValue;
        }
        return exactInt(value, at(key), min, max);
    }

    /** Convert a numeric value to an in-range int; fractional or out-of-range numbers are SCHEMA errors. */
    private int exactInt(Object value, String valuePointer, int min, int max) {
        BigInteger whole = null;
        if (value instanceof BigInteger integer) {
            whole = integer;
        } else if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0
                && decimal.precision() - decimal.scale() < 20) {
            whole = decimal.toBigIntegerExact();
        }
        if (whole == null || whole.compareTo(BigInteger.valueOf(min)) < 0 || whole.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new PackException("SCHEMA", document.location(valuePointer), "Expected integer " + min + ".." + max);
        }
        return whole.intValueExact();
    }

    /** Nested object; null when absent and not required. */
    Fields object(String key, boolean required) {
        Object value = raw(key, required);
        return value == null ? null : new Fields(document, at(key), value, resolver);
    }

    /** Array of objects (empty when absent and min is 0) with size bounds. */
    List<Fields> objects(String key, int min, int max) {
        List<?> list = list(key, min, max);
        List<Fields> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            result.add(new Fields(document, element(key, index), list.get(index), resolver));
        }
        return result;
    }

    /** Array of unique non-blank texts with a size bound; absent yields an empty list. */
    List<String> texts(String key, int max) {
        List<?> list = list(key, 0, max);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof String text) || text.isBlank() || text.length() > TEXT_MAX) {
                throw new PackException("SCHEMA", document.location(element(key, index)), "Expected non-blank text");
            }
            if (result.contains(text)) {
                throw new PackException("DUPLICATE", document.location(element(key, index)), "Duplicate entry");
            }
            result.add(text);
        }
        return result;
    }

    /** Array of unique identifiers that are not references (e.g. tags); absent yields an empty list. */
    List<String> ids(String key, int max) {
        List<String> values = new ArrayList<>();
        List<?> list = list(key, 0, max);
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof String text) || !ID.matcher(text).matches()) {
                throw new PackException("SCHEMA", document.location(element(key, index)), "Expected a lowercase stable identifier");
            }
            if (values.contains(text)) {
                throw new PackException("DUPLICATE", document.location(element(key, index)), "Duplicate entry");
            }
            values.add(text);
        }
        return values;
    }

    /** Single reference resolved against the pack index; null when optional and absent. */
    String ref(String key, Set<String> kinds, boolean required) {
        Object value = raw(key, required);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > 300) {
            throw fail(key, "Expected a reference string");
        }
        resolver.resolve(document, at(key), text, kinds);
        return text;
    }

    /** Array of unique references to definitions of the given kinds; absent yields an empty list. */
    List<String> refs(String key, Set<String> kinds, int max) {
        List<?> list = list(key, 0, max);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            String elementPointer = element(key, index);
            if (!(list.get(index) instanceof String text) || text.isBlank() || text.length() > 300) {
                throw new PackException("SCHEMA", document.location(elementPointer), "Expected a reference string");
            }
            if (result.contains(text)) {
                throw new PackException("DUPLICATE", document.location(elementPointer), "Duplicate reference");
            }
            resolver.resolve(document, elementPointer, text, kinds);
            result.add(text);
        }
        return result;
    }

    /**
     * Object whose keys are identifiers and whose values are bounded integers, in author order.
     * The caller validates the keys semantically (attribute or skill IDs) using {@link #keyPointer}.
     */
    Map<String, Integer> intMap(String key, int min, int max, int maxEntries) {
        return integerMap(key, min, max, maxEntries, false);
    }

    /**
     * Bounded integer map keyed by typed local or dependency references. Invalid syntax,
     * undeclared/missing IDs and wrong definition kinds produce located pack diagnostics.
     */
    Map<String, Integer> intRefs(String key, int min, int max, int maxEntries, Set<String> kinds) {
        Map<String, Integer> result = integerMap(key, min, max, maxEntries, true);
        result.keySet().forEach(ref -> resolve(keyPointer(key, ref), ref, kinds));
        return result;
    }

    /** Read integer values with identifier or reference keys; never relax ordinary attribute/property keys. */
    private Map<String, Integer> integerMap(String key, int min, int max, int maxEntries, boolean references) {
        Object value = raw(key, false);
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value == null) {
            return result;
        }
        if (!(value instanceof Map<?, ?> object) || object.size() > maxEntries) {
            throw fail(key, "Expected object with at most " + maxEntries + " entries");
        }
        for (var entry : object.entrySet()) {
            String name = (String) entry.getKey();
            String entryPointer = keyPointer(key, name);
            if (references ? name.isBlank() || name.length() > 300 : !ID.matcher(name).matches()) {
                throw new PackException("SCHEMA", document.location(entryPointer),
                        references ? "Expected a reference key" : "Expected a lowercase stable identifier key");
            }
            result.put(name, exactInt(entry.getValue(), entryPointer, min, max));
        }
        return result;
    }

    /** Pointer of one entry inside an object-valued child key. */
    String keyPointer(String key, String entryKey) {
        return PackDocument.child(at(key), entryKey);
    }

    /** Resolve a reference string at an explicit pointer, for map keys. */
    void resolve(String valuePointer, String ref, Set<String> kinds) {
        resolver.resolve(document, valuePointer, ref, kinds);
    }

    /** Reject every key no accessor consumed; call after reading all known fields. */
    void close() {
        for (Object key : map.keySet()) {
            if (!seen.contains(key)) {
                throw new PackException("SCHEMA", document.location(at((String) key)), "Unknown field");
            }
        }
    }

    /**
     * Fetch an untyped child value (e.g. an expression tree analyzed elsewhere), marking it consumed.
     * Returns null when absent; a missing required key is a located SCHEMA error.
     */
    Object value(String key, boolean required) {
        return raw(key, required);
    }

    /** Fetch a child value, marking it consumed; a missing required key is a located SCHEMA error. */
    private Object raw(String key, boolean required) {
        seen.add(key);
        Object value = map.get(key);
        if (value == null && required) {
            throw fail(key, "Required field is missing");
        }
        return value;
    }

    /** Fetch an array child with size bounds; absent yields an empty list (or fails when min is positive). */
    private List<?> list(String key, int min, int max) {
        Object value = raw(key, min > 0);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) {
            throw fail(key, "Expected array with " + min + ".." + max + " entries");
        }
        return list;
    }

    /** Pointer of one array element. */
    private String element(String key, int index) {
        return PackDocument.child(at(key), Integer.toString(index));
    }
}
