package org.rpgengine.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.presentation.PresentationEvents.Draft;
import org.rpgengine.presentation.PresentationEvents.Reason;
import org.rpgengine.presentation.PresentationEvents.Rejection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verify the admission of typed public presentation events without a database: only allowlisted
 * references for visible actors pass, and URLs, executable directives and hidden actors are refused
 * with the reason the audit trail needs.
 */
class PresentationEventsTest {
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VISIBLE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID HIDDEN = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final PublicPresentationScope SCOPE = new PublicPresentationScope(
            Set.of("harbour-dusk", "station-hall"),
            Map.of(VISIBLE, new PublicPresentationScope.ActorLooks(Set.of("leaning", "standing"),
                    Set.of("smile", "worried"))),
            Set.of("line-001"),
            Set.of("bg-harbour", "audio-line-001"));

    private static Draft scene(String sceneId, String assetId) {
        return new Draft("SCENE", OPERATION, 7, 0, sceneId, null, null, null, null, assetId);
    }

    private static Draft pose(String actorId, String poseId, String expressionId) {
        return new Draft("POSE", OPERATION, 7, 1, null, actorId, poseId, expressionId, null, null);
    }

    private static Draft audio(String actorId, String lineId, String assetId) {
        return new Draft("LINE_AUDIO", OPERATION, 7, 2, null, actorId, null, null, lineId, assetId);
    }

    private static Reason refusal(Draft draft) {
        return assertThrows(Rejection.class, () -> PresentationEvents.accept(draft, SCOPE)).reason();
    }

    /** A scene with and without a backdrop, a pose, and a line hook with and without a speaker are accepted. */
    @Test
    void acceptsAllowlistedPublicReferences() {
        PresentationEvent withBackdrop = PresentationEvents.accept(scene("harbour-dusk", "bg-harbour"), SCOPE);
        assertEquals(PresentationEvent.Kind.SCENE, withBackdrop.kind());
        assertEquals("harbour-dusk", withBackdrop.sceneId());
        assertEquals("bg-harbour", withBackdrop.assetId());
        assertNull(PresentationEvents.accept(scene("station-hall", null), SCOPE).assetId());

        PresentationEvent posed = PresentationEvents.accept(pose(VISIBLE.toString(), "leaning", "worried"), SCOPE);
        assertEquals(VISIBLE, posed.actorId());
        assertEquals("worried", posed.expressionId());
        assertEquals(1, posed.ordinal());
        assertNull(PresentationEvents.accept(pose(VISIBLE.toString(), "", "smile"), SCOPE).poseId());

        PresentationEvent spoken = PresentationEvents.accept(audio(VISIBLE.toString(), "line-001", "audio-line-001"),
                SCOPE);
        assertEquals("audio-line-001", spoken.assetId());
        assertNull(PresentationEvents.accept(audio(null, "line-001", "audio-line-001"), SCOPE).actorId());
    }

    /** The kind is matched exactly after trimming and upper-casing; anything else is malformed. */
    @Test
    void refusesUnknownKindsAndMismatchedFields() {
        assertEquals(Reason.MALFORMED, refusal(new Draft("script", OPERATION, 7, 0, "harbour-dusk", null, null, null,
                null, null)));
        assertEquals(Reason.MALFORMED, refusal(new Draft(null, OPERATION, 7, 0, "harbour-dusk", null, null, null,
                null, null)));
        // A scene may not carry an expression; a pose may not carry a scene or an asset.
        assertEquals(Reason.MALFORMED, refusal(new Draft("SCENE", OPERATION, 7, 0, "harbour-dusk", null, null, "smile",
                null, null)));
        assertEquals(Reason.MALFORMED, refusal(new Draft("POSE", OPERATION, 7, 0, "harbour-dusk",
                VISIBLE.toString(), null, "smile", null, null)));
        assertEquals(Reason.MALFORMED, refusal(new Draft("POSE", OPERATION, 7, 0, null, VISIBLE.toString(), null,
                "smile", null, "bg-harbour")));
        // A line hook needs both its line and its audio asset.
        assertEquals(Reason.MALFORMED, refusal(audio(null, "line-001", null)));
        assertEquals(Reason.MALFORMED, refusal(new Draft("SCENE", null, 7, 0, "harbour-dusk", null, null, null, null,
                null)));
        assertEquals(Reason.MALFORMED, refusal(new Draft("SCENE", OPERATION, 0, 0, "harbour-dusk", null, null, null,
                null, null)));
    }

    /** Well-formed references that the pack or projection did not publish are refused field by field. */
    @Test
    void refusesReferencesOutsideTheAllowlist() {
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(scene("secret-vault", null)));
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(scene("harbour-dusk", "bg-secret")));
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(pose(VISIBLE.toString(), "levitating", "smile")));
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(pose(VISIBLE.toString(), "leaning", "menacing")));
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(audio(null, "line-999", "audio-line-001")));
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(audio(null, "line-001", "audio-line-999")));
        // Allowlist membership is exact: neither case folding nor a suffix is a match.
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(scene("Harbour-Dusk", null)));
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(scene("harbour-dusk.png", null)));
        // An asset that is public is still not a scene: each field is checked against its own list.
        assertEquals(Reason.NOT_ALLOWLISTED, refusal(scene("bg-harbour", null)));
    }

    /** Raw URLs, paths and protocol-relative references never reach the allowlist lookup. */
    @Test
    void refusesRawUrls() {
        for (String url : new String[] {"https://example.org/a.png", "http://127.0.0.1/x", "//cdn.example.org/a.png",
            "file:///etc/passwd", "ftp:backup", "/assets/bg.png", "../bg-harbour", "assets/bg-harbour",
            "bg-harbour%2F..%2Fsecret"}) {
            assertEquals(Reason.RAW_URL, refusal(scene("harbour-dusk", url)), url);
            assertEquals(Reason.RAW_URL, refusal(audio(null, "line-001", url)), url);
            assertEquals(Reason.RAW_URL, refusal(scene(url, null)), url);
        }
        // Even a URL placed in an allowlist-looking position is refused, not looked up.
        assertEquals(Reason.RAW_URL, refusal(pose(VISIBLE.toString(), "leaning", "https://example.org/smile")));
    }

    /** Markup, handlers, script schemes and command syntax are refused as executable directives. */
    @Test
    void refusesExecutableDirectives() {
        for (String directive : new String[] {"<script>alert(1)</script>", "javascript:alert(1)", "JavaScript:void",
            "vbscript:run", "data:text/html;base64,AAAA", "onload=steal()", "smile;rm -rf", "smile`id`",
            "$(reboot)", "{{template}}", "smile|cat", "bg\" onerror=\"x", "'; DROP TABLE x; --"}) {
            assertEquals(Reason.EXECUTABLE, refusal(pose(VISIBLE.toString(), "leaning", directive)), directive);
            assertEquals(Reason.EXECUTABLE, refusal(scene(directive, null)), directive);
            assertEquals(Reason.EXECUTABLE, refusal(audio(null, directive, "audio-line-001")), directive);
            assertEquals(Reason.EXECUTABLE, refusal(audio(null, "line-001", directive)), directive);
        }
        assertEquals(Reason.EXECUTABLE, refusal(pose("<img src=x onerror=alert(1)>", "leaning", "smile")));
    }

    /** Whitespace and other characters outside the reference alphabet are malformed, not trimmed. */
    @Test
    void refusesWhitespaceAndOversizedReferences() {
        assertEquals(Reason.MALFORMED, refusal(scene(" harbour-dusk", null)));
        assertEquals(Reason.MALFORMED, refusal(scene("harbour dusk", null)));
        assertEquals(Reason.MALFORMED, refusal(scene("harbour-dusk\n", null)));
        assertEquals(Reason.MALFORMED, refusal(scene("a".repeat(PresentationEvent.MAX_REFERENCE + 1), null)));
        assertEquals(Reason.MALFORMED, refusal(scene("_harbour", null)));
    }

    /** A hidden actor and an unknown actor give the same refusal, so a rejection reveals nothing. */
    @Test
    void refusesHiddenAndUnknownActorsIndistinguishably() {
        Rejection hidden = assertThrows(Rejection.class,
                () -> PresentationEvents.accept(pose(HIDDEN.toString(), "leaning", "smile"), SCOPE));
        Rejection unknown = assertThrows(Rejection.class, () -> PresentationEvents.accept(
                pose(UUID.randomUUID().toString(), "leaning", "smile"), SCOPE));
        assertEquals(Reason.HIDDEN_ACTOR, hidden.reason());
        assertEquals(Reason.HIDDEN_ACTOR, unknown.reason());
        assertEquals(hidden.getMessage(), unknown.getMessage());
        assertFalse(hidden.getMessage().contains(HIDDEN.toString()));
        // The same actor is refused as a line speaker, and an actor id is never taken as free text.
        assertEquals(Reason.HIDDEN_ACTOR, refusal(audio(HIDDEN.toString(), "line-001", "audio-line-001")));
        assertEquals(Reason.MALFORMED, refusal(pose("hero", "leaning", "smile")));
        // Expressions are per actor: a visible actor does not inherit another actor's looks.
        PublicPresentationScope other = new PublicPresentationScope(Set.of(), Map.of(HIDDEN,
                new PublicPresentationScope.ActorLooks(Set.of("leaning"), Set.of("menacing"))), Set.of(), Set.of());
        assertEquals(Reason.NOT_ALLOWLISTED, assertThrows(Rejection.class, () -> PresentationEvents.accept(
                pose(HIDDEN.toString(), "leaning", "smile"), other)).reason());
    }

    /** A rejection never echoes the injected value into messages that reach logs or responses. */
    @Test
    void doesNotEchoRejectedValues() {
        String injected = "<script>alert('x-marker')</script>";
        Rejection rejection = assertThrows(Rejection.class,
                () -> PresentationEvents.accept(scene(injected, null), SCOPE));
        assertFalse(rejection.getMessage().contains("x-marker"));
        assertFalse(rejection.getMessage().contains("script"));
    }

    /** A pack cannot declare a URL or script as a presentation identifier in the allowlist itself. */
    @Test
    void scopeRefusesMalformedDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> new PublicPresentationScope(
                Set.of("https://example.org/scene"), Map.of(), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PublicPresentationScope(
                Set.of(), Map.of(), Set.of(), Set.of("<script>")));
        assertThrows(IllegalArgumentException.class, () -> new PublicPresentationScope(
                Set.of(), Map.of(VISIBLE, new PublicPresentationScope.ActorLooks(Set.of(), Set.of("javascript:x"))),
                Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PublicPresentationScope(
                Set.of("a".repeat(PresentationEvent.MAX_REFERENCE + 1)), Map.of(), Set.of(), Set.of()));
    }

    /** The typed event itself refuses unvetted shapes, so it cannot be built around the gate with a URL. */
    @Test
    void eventRecordRefusesUrlsAndWrongShapes() {
        assertThrows(IllegalArgumentException.class, () -> new PresentationEvent(PresentationEvent.Kind.SCENE,
                OPERATION, 7, 0, "https://example.org/x", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PresentationEvent(PresentationEvent.Kind.POSE,
                OPERATION, 7, 0, null, null, null, "smile", null, null));
        assertThrows(IllegalArgumentException.class, () -> new PresentationEvent(PresentationEvent.Kind.SCENE,
                OPERATION, 7, -1, "harbour-dusk", null, null, null, null, null));
    }

    /** One operation is admitted all or nothing, within its bound, with distinct ordinals. */
    @Test
    void acceptsAnOperationAllOrNothing() {
        List<PresentationEvent> events = PresentationEvents.acceptAll(List.of(scene("harbour-dusk", "bg-harbour"),
                pose(VISIBLE.toString(), "leaning", "smile")), SCOPE);
        assertEquals(2, events.size());
        assertEquals(PresentationEvent.Kind.POSE, events.get(1).kind());

        Rejection partial = assertThrows(Rejection.class, () -> PresentationEvents.acceptAll(
                List.of(scene("harbour-dusk", null), pose(HIDDEN.toString(), "leaning", "smile")), SCOPE));
        assertEquals(Reason.HIDDEN_ACTOR, partial.reason());

        Rejection duplicate = assertThrows(Rejection.class, () -> PresentationEvents.acceptAll(
                List.of(scene("harbour-dusk", null), scene("station-hall", null)), SCOPE));
        assertEquals(Reason.BOUNDS, duplicate.reason());

        Rejection mixed = assertThrows(Rejection.class, () -> PresentationEvents.acceptAll(List.of(
                scene("harbour-dusk", null),
                new Draft("SCENE", UUID.randomUUID(), 7, 1, "station-hall", null, null, null, null, null)), SCOPE));
        assertEquals(Reason.BOUNDS, mixed.reason());

        List<Draft> tooMany = new ArrayList<>();
        for (int i = 0; i <= PresentationEvents.MAX_PER_OPERATION; i++) {
            tooMany.add(new Draft("SCENE", OPERATION, 7, i, "harbour-dusk", null, null, null, null, null));
        }
        assertEquals(Reason.BOUNDS, assertThrows(Rejection.class,
                () -> PresentationEvents.acceptAll(tooMany, SCOPE)).reason());
        assertEquals(PresentationEvents.MAX_PER_OPERATION,
                PresentationEvents.acceptAll(tooMany.subList(0, PresentationEvents.MAX_PER_OPERATION), SCOPE).size());
    }
}
