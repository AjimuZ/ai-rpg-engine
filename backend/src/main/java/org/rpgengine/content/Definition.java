package org.rpgengine.content;

import java.util.Map;

/**
 * One definition whose envelope and category-specific data passed semantic validation.
 * {@code data} is the deeply immutable parsed object; {@code file} and {@code pointer} locate it for diagnostics.
 */
public record Definition(String id, String kind, String file, String pointer, Map<String, Object> data) {
    /** Require non-null parts; the compiler is the only producer and has already validated their syntax. */
    public Definition {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(file, "file");
        java.util.Objects.requireNonNull(pointer, "pointer");
        data = Map.copyOf(data);
    }
}
