package org.rpgengine.speech;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Run QA-45 at disabled scope: every scenario of {@code docs/35} section 7 that can reach the engine without a
 * recognizer captures nothing, drafts nothing and queues nothing, and the HTTP surface accepts no audio (P17.10-A).
 *
 * <p>This proves the disabled disposition only. Recognition quality, echo, interruption and latency need a qualified
 * recognizer and are not exercised here.
 */
class SpeechInputDisabledScopeTest {
    /** Route fragments that would mean an audio, speech or microphone input exists. */
    private static final Pattern VOICE_ROUTE = Pattern.compile(
            "speech|voice|audio|micro|stt|transcri|recogni|dictat|listen|utteranc", Pattern.CASE_INSENSITIVE);

    /** Construct an independent corpus; no application context is needed. */
    SpeechInputDisabledScopeTest() {
    }

    /**
     * EN/IT silence, noise, accents, negation, control phrases embedded in dialogue and duplicated results, across
     * both modes, two same-name adventure scopes and repeated starts (reconnect, restart, renewal), all stay inert.
     */
    @Test
    void everyReachableScenarioStaysInert() {
        var qualification = SpeechInputQualification.load();
        String[] scopes = {"adventure-a/the-harbour", "adventure-b/the-harbour"};
        String[] utterances = {"", " ", "[noise]", "\u0000\u0000", "don't attack the guard",
            "non attaccare la guardia", "Mira, cover the exit", "Mira, copri l'uscita", "say \"confirm\" to the guard",
            "conferma", "cancel", "annulla", "stop listening", "attack the guard twice", null};
        List<SpeechInput.Status> first = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            for (boolean requested : new boolean[] {true, false}) {
                var decision = qualification.decide(requested);
                assertFalse(decision.captureAllowed());
                for (String scope : scopes) {
                    for (String locale : new String[] {"en", "it"}) {
                        for (String mode : new String[] {"push-to-talk", "hands-free"}) {
                            var status = SpeechInput.start(decision, scope, locale, mode);
                            assertEquals(SpeechInput.UNAVAILABLE, status.state());
                            assertFalse(status.capturing());
                            assertFalse(status.permissionRequested());
                            assertNull(status.sessionId());
                            assertTrue(status.typedInputAvailable());
                            assertEquals(scope, status.scope());
                            assertEquals(decision.reasons(), status.reasons());
                            for (String text : utterances) {
                                for (int duplicate = 0; duplicate < 2; duplicate++) {
                                    var receipt = SpeechInput.receive(status, text);
                                    assertNull(receipt.draft());
                                    assertFalse(receipt.queued());
                                    assertEquals(List.of("no-capture-session"), receipt.reasons());
                                }
                            }
                            if (attempt == 0) {
                                first.add(status);
                            } else {
                                assertTrue(first.contains(status), "a repeated start must answer as the first one");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * No controller maps a route or a media type for audio or speech input, so no browser can upload audio, while the
     * typed command route stays the one gameplay entry point.
     */
    @Test
    void httpSurfaceAcceptsNoAudioAndKeepsTypedCommands() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> paths = new ArrayList<>();
        boolean typedCommands = false;
        for (BeanDefinition candidate : scanner.findCandidateComponents("org.rpgengine")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (String consumed : mapping.consumes()) {
                    assertFalse(consumed.toLowerCase(Locale.ROOT).startsWith("audio/"), type.getName() + " " + consumed);
                }
                for (String path : mapping.path()) {
                    paths.add(path);
                    assertFalse(VOICE_ROUTE.matcher(path).find(), type.getName() + " maps " + path);
                    typedCommands |= path.equals("/api/v1/campaigns/{campaignId}/commands")
                            && List.of(mapping.method()).contains(RequestMethod.POST);
                }
            }
        }
        assertFalse(paths.isEmpty(), "the scan found no route, so it proves nothing");
        assertTrue(typedCommands, "the typed command route must stay mapped");
    }
}
