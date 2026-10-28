package org.rpgengine.knowledge;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Typed knowledge reference grammar, mirroring the V15 {@code knowledge_ref_valid} function.
 * Campaign rows are {@code actor:<uuid>} and {@code item:<uuid>}; pinned content is
 * {@code location:<content id>} and {@code definition:<content id>}, where a content id is a
 * local id or a dependency-qualified {@code pack#id} exactly as campaign creation stores it.
 * Content references are checked for form only: the pinned closure is frozen per campaign and
 * trusted resolvers take ids from it, while readers resolve them against that closure and treat
 * an unknown id as nothing (never as a reason to disclose).
 */
public final class KnowledgeRefs {
    private static final String CONTENT_ID = "[a-z][a-z0-9._:-]{0,127}(?:#[a-z][a-z0-9._:-]{0,127})?";
    private static final Pattern REFERENCE = Pattern.compile("(?:(actor|item):"
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|(location|definition):" + CONTENT_ID + ")");

    /** Static helpers only. */
    private KnowledgeRefs() { }

    /** @return true when the text is a well-formed typed reference */
    public static boolean valid(String reference) {
        return reference != null && REFERENCE.matcher(reference).matches();
    }

    /**
     * Return the reference unchanged when it is well formed.
     *
     * @throws IllegalArgumentException naming only the field, never echoing the value
     */
    public static String require(String reference, String name) {
        if (!valid(reference)) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return reference;
    }

    /** @return true for a well-formed reference a holder can read: an item or an authored definition */
    public static boolean document(String reference) {
        return valid(reference) && (reference.startsWith("item:") || reference.startsWith("definition:"));
    }

    /** @return the canonical reference of a campaign actor */
    public static String actor(UUID actor) {
        return "actor:" + actor;
    }

    /** @return the canonical reference of a pinned location id */
    public static String location(String locationId) {
        return require("location:" + locationId, "locationId");
    }

    /**
     * Campaign-row identity named by an {@code actor:} or {@code item:} reference.
     *
     * @return the UUID, or null for a content reference
     */
    static UUID row(String reference) {
        return reference.startsWith("actor:") || reference.startsWith("item:")
                ? UUID.fromString(reference.substring(reference.indexOf(':') + 1)) : null;
    }
}
