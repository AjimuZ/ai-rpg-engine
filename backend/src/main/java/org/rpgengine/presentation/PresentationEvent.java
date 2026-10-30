package org.rpgengine.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One bounded, typed public presentation event of a committed operation (P05.7-A): a scene change,
 * an actor pose/expression change or an optional line-audio hook. An event carries stable content
 * references only, never markup, URLs, script or file paths; the browser resolves a reference
 * against assets it already holds, so an event cannot make a client fetch or run anything.
 *
 * <p>Always holds: every reference matches {@link #REFERENCE}, and the fields present are exactly
 * those of the {@link Kind}. This record checks shape only. Whether the references are public and
 * admitted for the audience is decided by {@link PresentationEvents#accept}, which is the only
 * supported way to obtain an event from untrusted input.
 *
 * @param kind event kind
 * @param operationId committed operation the event belongs to
 * @param committedRevision revision that operation produced
 * @param ordinal position within the operation's events, 0-based; part of {@link #deliveryId}
 * @param sceneId scene reference, present exactly for {@link Kind#SCENE}
 * @param actorId actor shown or speaking; required for {@link Kind#POSE}, optional speaker for
 *        {@link Kind#LINE_AUDIO}, absent for {@link Kind#SCENE}
 * @param poseId optional pose reference, only for {@link Kind#POSE}
 * @param expressionId expression reference, present exactly for {@link Kind#POSE}
 * @param lineId line reference, present exactly for {@link Kind#LINE_AUDIO}
 * @param assetId asset reference: optional backdrop of a scene, required audio of a line, absent for a pose
 */
public record PresentationEvent(Kind kind, UUID operationId, long committedRevision, int ordinal, String sceneId,
        UUID actorId, String poseId, String expressionId, String lineId, String assetId) {
    /** Longest reference. */
    public static final int MAX_REFERENCE = 64;
    /** Stable content reference: no separator, scheme, markup or whitespace can occur. */
    public static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_REFERENCE - 1) + "}");

    /** Event kinds; the wire names are the upper-case enum names. */
    public enum Kind {
        /** The visible scene changes. */
        SCENE,
        /** One visible actor takes a pose and expression. */
        POSE,
        /** A recorded or synthesized audio asset is available for one line. */
        LINE_AUDIO
    }

    /** @throws IllegalArgumentException for a malformed reference or fields that do not match the kind */
    public PresentationEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operationId, "operationId");
        if (committedRevision <= 0 || ordinal < 0) {
            throw new IllegalArgumentException("committed revision must be positive and ordinal non-negative");
        }
        boolean ok = switch (kind) {
            case SCENE -> sceneId != null && actorId == null && poseId == null && expressionId == null
                    && lineId == null;
            case POSE -> sceneId == null && actorId != null && expressionId != null && lineId == null
                    && assetId == null;
            case LINE_AUDIO -> sceneId == null && poseId == null && expressionId == null && lineId != null
                    && assetId != null;
        };
        if (!ok) {
            throw new IllegalArgumentException("fields do not match the " + kind + " event");
        }
        for (String reference : new String[] {sceneId, poseId, expressionId, lineId, assetId}) {
            if (reference != null && !REFERENCE.matcher(reference).matches()) {
                throw new IllegalArgumentException("presentation references must be stable content references");
            }
        }
    }

    /**
     * Identity a client deduplicates this cue by. It never changes for a stored cue, because the stream
     * identity is fixed when the message is written, and operation and ordinal keep two timelines
     * that reuse one stream position apart.
     *
     * @param streamId the message's {@code generation:sequence} identity
     * @return {@code generation:sequence:operationId:ordinal}
     */
    public String deliveryId(String streamId) {
        return streamId + ":" + operationId + ":" + ordinal;
    }

    /** @return the allowlisted public fields in a fixed order: kind, ordinal and the references this kind holds */
    public Map<String, Object> publicFields() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("kind", kind.name());
        fields.put("ordinal", ordinal);
        putPresent(fields, "sceneId", sceneId);
        putPresent(fields, "actorId", actorId == null ? null : actorId.toString());
        putPresent(fields, "poseId", poseId);
        putPresent(fields, "expressionId", expressionId);
        putPresent(fields, "lineId", lineId);
        putPresent(fields, "assetId", assetId);
        return fields;
    }

    private static void putPresent(Map<String, Object> fields, String name, String value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
