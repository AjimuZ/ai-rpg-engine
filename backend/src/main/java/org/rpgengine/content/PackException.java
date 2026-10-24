package org.rpgengine.content;

/** A blocking author diagnostic with a stable code and source context; raw parser exceptions are not exposed. */
public final class PackException extends IllegalArgumentException {
    private final String code;
    private final SourceLocation location;

    /** Build a diagnostic from trusted explanation and untrusted source coordinates; returns no source contents. */
    public PackException(String code, SourceLocation location, String explanation) {
        super(code + " at " + location.file() + ":" + location.line() + ":" + location.column()
                + " " + location.pointer() + ": " + explanation);
        this.code = code;
        this.location = location;
    }

    /** Return the stable diagnostic code; never fails. */
    public String code() {
        return code;
    }

    /** Return the source coordinates; never fails. */
    public SourceLocation location() {
        return location;
    }
}
