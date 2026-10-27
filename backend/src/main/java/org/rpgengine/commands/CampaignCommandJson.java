package org.rpgengine.commands;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.content.PackException;
import org.rpgengine.content.PackTextParser;

/**
 * Decode the fixed public command envelope (docs/13 §3) without coercions or polymorphic
 * binding. Input is already byte-bounded JSON; the shared parser rejects duplicate keys,
 * invalid UTF-8 and excessive depth/node counts. Structural problems throw {@link Malformed}
 * (HTTP 400); the payload object itself stays opaque here because its typed validation is
 * P07.2 work.
 */
public final class CampaignCommandJson {
    /** Wire command identifiers; mirrors the service check so shape errors stay HTTP 400. */
    private static final Pattern COMMAND_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private final PackTextParser parser = new PackTextParser();

    /** Construct the stateless adapter; parser budgets are call-local. */
    public CampaignCommandJson() {
    }

    /**
     * Parse exactly one UTF-8 JSON envelope.
     *
     * @param bytes bounded request body
     * @return validated service command
     * @throws Malformed for invalid JSON, unknown or missing fields, wrong field types, a
     *         non-canonical request UUID, a negative or overflowing revision, or a bad type
     */
    public OperationAdmissionService.Command decode(byte[] bytes) {
        Object parsed;
        try {
            // The fixed filename selects JSON; YAML is never accepted on the wire.
            parsed = parser.parse("command-request.json", bytes).value();
        } catch (PackException failure) {
            throw new Malformed();
        }
        if (!(parsed instanceof Map<?, ?> root)
                || !Set.of("requestId", "expectedRevision", "commandType", "payload").equals(root.keySet())) {
            throw new Malformed();
        }
        String request = canonicalUuid(root.get("requestId"));
        long revision;
        try {
            // Only an exact JSON integer is a revision; 1.0, "1" and overflow are malformed.
            if (!(root.get("expectedRevision") instanceof BigInteger number) || number.signum() < 0) {
                throw new Malformed();
            }
            revision = number.longValueExact();
        } catch (ArithmeticException failure) {
            throw new Malformed();
        }
        if (!(root.get("commandType") instanceof String type) || !COMMAND_TYPE.matcher(type).matches()) {
            throw new Malformed();
        }
        if (!(root.get("payload") instanceof Map<?, ?> payload)) {
            throw new Malformed();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) payload; // JSON object keys are always strings.
        return new OperationAdmissionService.Command(request, revision, type, typed);
    }

    /**
     * Require canonical lowercase UUID text, so one logical request cannot hide behind two
     * spellings of the same identity and bypass idempotency.
     *
     * @param value decoded JSON value or path text
     * @return the canonical text
     * @throws Malformed for anything else
     */
    static String canonicalUuid(Object value) {
        return uuid(value).toString();
    }

    /**
     * Parse canonical UUID text from JSON or a path/query parameter.
     *
     * @param value candidate
     * @return parsed identity
     * @throws Malformed for null, non-string or non-canonical text
     */
    public static UUID uuid(Object value) {
        if (!(value instanceof String text)) {
            throw new Malformed();
        }
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equals(text)) {
                throw new Malformed();
            }
            return id;
        } catch (IllegalArgumentException failure) {
            throw new Malformed();
        }
    }

    /** Fixed malformed-input signal; never retains submitted text or parser coordinates. */
    public static final class Malformed extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        /** Construct a safe syntax category; no inputs or external effects. */
        Malformed() {
            super("malformed command input");
        }
    }
}
