package org.rpgengine.content;

import java.util.Map;

/** Immutable JSON-shaped parsed data and pointer-indexed coordinates; construction is internal to the parser. */
public final class PackDocument {
    private final Object value;
    private final Map<String, SourceLocation> locations;

    /** Retain an already deeply immutable value and copy its locations; null JSON values are supported. */
    PackDocument(Object value, Map<String, SourceLocation> locations) {
        this.value = value;
        this.locations = Map.copyOf(locations);
    }

    /** Return immutable maps/lists, strings, booleans, exact numbers or null; never constructs host objects. */
    public Object value() {
        return value;
    }

    /** Find a field or its closest existing ancestor, so missing-field errors still point into the document. */
    public SourceLocation location(String pointer) {
        String candidate = pointer;
        while (!locations.containsKey(candidate)) {
            int slash = candidate.lastIndexOf('/');
            candidate = slash < 0 ? "" : candidate.substring(0, slash);
        }
        SourceLocation source = locations.get(candidate);
        return new SourceLocation(source.file(), source.line(), source.column(), pointer);
    }

    /** Encode a field as one RFC 6901 path segment; callers supply a non-null key. */
    static String child(String pointer, String key) {
        return pointer + "/" + key.replace("~", "~0").replace("/", "~1");
    }
}
