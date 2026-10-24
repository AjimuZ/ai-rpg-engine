package org.rpgengine.content;

import java.util.Objects;

/**
 * Non-blocking compiler finding, kept separate from guaranteed checks (docs/09 section 10): a pack
 * with warnings still compiles, and a warning never implies that a property was proven.
 *
 * @param code stable machine-readable warning code, e.g. {@code UNREACHABLE_LOCATION}
 * @param location file, line, column and pointer the warning refers to
 * @param message author-facing explanation
 */
public record PackWarning(String code, SourceLocation location, String message) {
    /** Require every component; warnings are always located and explained. */
    public PackWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(message, "message");
    }
}
