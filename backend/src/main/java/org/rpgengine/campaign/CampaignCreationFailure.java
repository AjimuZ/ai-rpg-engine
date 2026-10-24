package org.rpgengine.campaign;

/** Typed public creation failures; messages contain no private content or storage diagnostics. */
public final class CampaignCreationFailure extends IllegalArgumentException {
    private final Kind kind;

    /** Retain a trusted failure category; null categories fail construction. */
    public CampaignCreationFailure(Kind kind) {
        super(java.util.Objects.requireNonNull(kind, "kind").name());
        this.kind = kind;
    }

    /** Return the safe category for transport mapping; never fails. */
    public Kind kind() { return kind; }

    /** Inaccessible scope and changed durable request payload are distinct from illegal character choices. */
    public enum Kind { SCOPE_UNAVAILABLE, REQUEST_CONFLICT }
}
