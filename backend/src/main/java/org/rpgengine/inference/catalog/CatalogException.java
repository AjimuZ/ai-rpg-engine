package org.rpgengine.inference.catalog;

/**
 * A model catalog or hardware profile that cannot be admitted. The {@link #code()} names the
 * broken rule and {@link #pointer()} the JSON Pointer of the offending value ({@code ""} for the
 * whole document), so the owner and the tests can locate the problem. Messages describe the rule,
 * never echo document content beyond field names, and are safe to show.
 */
public final class CatalogException extends RuntimeException {
    private final String code;
    private final String pointer;

    /** Create a failure for rule {@code code} at {@code pointer} with an explanatory message. */
    public CatalogException(String code, String pointer, String message) {
        super(code + " at '" + pointer + "': " + message);
        this.code = code;
        this.pointer = pointer;
    }

    /** Stable rule identifier such as {@code UNKNOWN_FIELD}, {@code SHA256} or {@code DUPLICATE_ID}. */
    public String code() {
        return code;
    }

    /** JSON Pointer of the rejected value; {@code ""} means the document itself. */
    public String pointer() {
        return pointer;
    }
}
