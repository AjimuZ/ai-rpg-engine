package org.rpgengine.content;

/** A source filename, one-based line/column and RFC 6901 pointer; no installation path is stored. */
public record SourceLocation(String file, int line, int column, String pointer) {
    /** Require usable diagnostic coordinates; invalid internal coordinates throw IllegalArgumentException. */
    public SourceLocation {
        if (file == null || pointer == null || line < 1 || column < 1) {
            throw new IllegalArgumentException("Invalid source location");
        }
    }
}
