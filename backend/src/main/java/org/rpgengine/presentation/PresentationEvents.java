package org.rpgengine.presentation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admission gate for untrusted presentation event drafts: turns a {@link Draft} into a
 * {@link PresentationEvent} only when every reference is a plain content reference that the
 * audience's {@link PublicPresentationScope} allows. Pure and stateless; it reads and writes
 * nothing durable and takes no clock, so the same draft and scope always give the same answer.
 *
 * <p>Failure is {@link Rejection} with a {@link Reason}. A rejection message names the field and
 * never echoes the rejected value, so a log or response cannot reflect an injected string.
 */
public final class PresentationEvents {
    /** Most events accepted for one operation. */
    public static final int MAX_PER_OPERATION = 16;

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Set<String> SCRIPT_SCHEMES = Set.of("javascript", "vbscript", "data");
    private static final String EXECUTABLE_CHARACTERS = "<>(){}[]=;'\"`$|&*\\!";

    private PresentationEvents() {
    }

    /** Why a draft was refused. */
    public enum Reason {
        /** The kind is unknown, the draft is empty or a field does not belong to its kind. */
        MALFORMED,
        /** A value is or contains a URL, scheme, path or protocol-relative reference. */
        RAW_URL,
        /** A value carries markup, script, a handler, a command or a script scheme. */
        EXECUTABLE,
        /** A well-formed reference is not on the audience's allowlist. */
        NOT_ALLOWLISTED,
        /** The actor is not visible to the audience (hidden or unknown; not distinguished). */
        HIDDEN_ACTOR,
        /** The operation would exceed {@link PresentationEvents#MAX_PER_OPERATION} events or repeat an ordinal. */
        BOUNDS
    }

    /** Refusal of one draft; the message never contains the rejected value. */
    public static final class Rejection extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        Rejection(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        /** @return the machine-readable reason */
        public Reason reason() {
            return reason;
        }
    }

    /**
     * Untrusted wire form of one event. Every field is a raw string so that nothing is interpreted
     * before it is checked; an empty optional field is treated as absent.
     *
     * @param kind {@link PresentationEvent.Kind} name
     * @param operationId committed operation
     * @param committedRevision revision that operation produced
     * @param ordinal position within the operation
     * @param sceneId scene reference or null
     * @param actorId actor identity text or null
     * @param poseId pose reference or null
     * @param expressionId expression reference or null
     * @param lineId line reference or null
     * @param assetId asset reference or null
     */
    public record Draft(String kind, UUID operationId, long committedRevision, int ordinal, String sceneId,
            String actorId, String poseId, String expressionId, String lineId, String assetId) {
    }

    /**
     * Admit one draft.
     *
     * @param draft untrusted event
     * @param scope allowlist of the audience the event will be delivered to
     * @return the typed event; never null
     * @throws Rejection for any syntactic, allowlist or visibility violation
     */
    public static PresentationEvent accept(Draft draft, PublicPresentationScope scope) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(scope, "scope");
        PresentationEvent.Kind kind = kind(draft.kind());
        String sceneId = reference(draft.sceneId(), "sceneId");
        String poseId = reference(draft.poseId(), "poseId");
        String expressionId = reference(draft.expressionId(), "expressionId");
        String lineId = reference(draft.lineId(), "lineId");
        String assetId = reference(draft.assetId(), "assetId");
        UUID actorId = actor(draft.actorId());
        PresentationEvent event;
        try {
            event = new PresentationEvent(kind, draft.operationId(), draft.committedRevision(), draft.ordinal(),
                    sceneId, actorId, poseId, expressionId, lineId, assetId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new Rejection(Reason.MALFORMED, "draft fields do not form a " + kind + " event");
        }
        allow(sceneId, scope.scenes(), "sceneId");
        allow(lineId, scope.lines(), "lineId");
        allow(assetId, scope.assets(), "assetId");
        if (actorId != null) {
            PublicPresentationScope.ActorLooks looks = scope.actors().get(actorId);
            if (looks == null) {
                throw new Rejection(Reason.HIDDEN_ACTOR, "actor is not visible to the audience");
            }
            allow(poseId, looks.poses(), "poseId");
            allow(expressionId, looks.expressions(), "expressionId");
        }
        return event;
    }

    /**
     * Admit every draft of one operation, all or nothing.
     *
     * @param drafts untrusted events, at most {@link #MAX_PER_OPERATION}
     * @param scope allowlist of the audience
     * @return accepted events in input order
     * @throws Rejection when any draft is refused, the list is too long, or one operation, revision
     *         or ordinal repeats or differs across drafts
     */
    public static List<PresentationEvent> acceptAll(List<Draft> drafts, PublicPresentationScope scope) {
        Objects.requireNonNull(drafts, "drafts");
        if (drafts.size() > MAX_PER_OPERATION) {
            throw new Rejection(Reason.BOUNDS, "an operation carries at most " + MAX_PER_OPERATION + " events");
        }
        var accepted = new ArrayList<PresentationEvent>(drafts.size());
        var ordinals = new HashSet<Integer>();
        for (Draft draft : drafts) {
            PresentationEvent event = accept(draft, scope);
            PresentationEvent first = accepted.isEmpty() ? event : accepted.get(0);
            if (!ordinals.add(event.ordinal()) || !event.operationId().equals(first.operationId())
                    || event.committedRevision() != first.committedRevision()) {
                throw new Rejection(Reason.BOUNDS, "events of one operation must share it and have distinct ordinals");
            }
            accepted.add(event);
        }
        return List.copyOf(accepted);
    }

    private static PresentationEvent.Kind kind(String raw) {
        if (raw == null) {
            throw new Rejection(Reason.MALFORMED, "kind is required");
        }
        try {
            return PresentationEvent.Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Rejection(Reason.MALFORMED, "kind is not a presentation event kind");
        }
    }

    private static UUID actor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new Rejection(classify(raw), "actorId must be an actor identity");
        }
    }

    /** @return the reference, or null when absent; classifies every refusal by what the value looks like */
    private static String reference(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!PresentationEvent.REFERENCE.matcher(raw).matches()) {
            throw new Rejection(classify(raw), field + " must be a stable content reference");
        }
        return raw;
    }

    /** Names the likeliest hazard so a rejection is diagnosable without echoing the value. */
    private static Reason classify(String value) {
        var scheme = SCHEME.matcher(value);
        if (scheme.find() && SCRIPT_SCHEMES.contains(scheme.group(1).toLowerCase(Locale.ROOT))) {
            return Reason.EXECUTABLE;
        }
        if (value.chars().anyMatch(c -> EXECUTABLE_CHARACTERS.indexOf(c) >= 0)) {
            return Reason.EXECUTABLE;
        }
        if (scheme.reset().find() || value.contains("/") || value.contains("%2")) {
            return Reason.RAW_URL;
        }
        return Reason.MALFORMED;
    }

    private static void allow(String value, Set<String> allowed, String field) {
        if (value != null && !allowed.contains(value)) {
            throw new Rejection(Reason.NOT_ALLOWLISTED, field + " is not an allowed public reference");
        }
    }
}
