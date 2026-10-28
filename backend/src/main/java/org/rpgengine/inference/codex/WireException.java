package org.rpgengine.inference.codex;

import java.io.IOException;

/**
 * Transport failure carrying only a fixed diagnostic code (for example {@code transport-eof}).
 * Frame contents, runtime messages and paths are never attached, because runtime output can
 * contain account data or task context.
 */
final class WireException extends IOException {
    private static final long serialVersionUID = 1L;

    /** @param code fixed lowercase code */
    WireException(String code) {
        super(code);
    }

    /** @return the fixed code */
    String code() {
        return getMessage();
    }
}
