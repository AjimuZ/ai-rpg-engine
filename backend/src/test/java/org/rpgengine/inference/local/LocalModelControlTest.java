package org.rpgengine.inference.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ModelControlException;
import org.rpgengine.inference.ModelServerControl;
import org.rpgengine.inference.ModelServerControl.Residency;
import org.springframework.mock.env.MockEnvironment;

/**
 * The HTTP shapes of the load, unload, model list and slot routes against the scripted server, which is
 * a fixture and shows nothing about a real server. Failures must carry a fixed code only.
 */
class LocalModelControlTest {
    private static final String SERVER_TEXT = "private-server-text";
    private ScriptedModelServer server;
    private ModelServerControl control;

    @BeforeEach
    void start() throws IOException {
        server = new ScriptedModelServer();
        control = LocalAdapters.control(server);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void assertCode(ThrowingCall call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ModelControlException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).isEqualTo(code).doesNotContain(SERVER_TEXT, server.url());
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void loadAndUnloadPostTheModelToTheDocumentedRoutes() throws Exception {
        control.load("text-a");
        assertThat(control.status("text-a")).isEqualTo(Residency.LOADED);
        control.unload("text-a");
        assertThat(control.status("text-a")).isEqualTo(Residency.GONE);

        assertThat(server.controlPaths()).containsExactly("/models/load", "/models/unload");
        assertThat(server.controlBodies()).allSatisfy(body -> {
            assertThat(body.path("model").stringValue()).isEqualTo("text-a");
            assertThat(body.size()).isEqualTo(1);
        });
    }

    @Test
    void slotActionsPostTheFileAndTheModelToTheSlotRoute() throws Exception {
        control.saveSlot("text-a", 2, "engine-slot-1.bin");
        control.restoreSlot("text-a", 2, "engine-slot-1.bin");

        assertThat(server.controlPaths()).containsExactly("/slots/2?action=save", "/slots/2?action=restore");
        assertThat(server.controlBodies()).allSatisfy(body -> {
            assertThat(body.path("filename").stringValue()).isEqualTo("engine-slot-1.bin");
            assertThat(body.path("model").stringValue()).isEqualTo("text-a");
        });
    }

    @Test
    void aSlotFileOrNumberOutOfRangeIsRefusedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> control.saveSlot("text-a", 0, "../escape.bin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.restoreSlot("text-a", -1, "ok.bin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.controlPaths()).isEmpty();
    }

    @Test
    void theModelListIsReadIntoResidency() throws Exception {
        server.modelListAnswers(200, """
                {"data":[{"id":"a","status":{"value":"loaded"}},{"id":"b","status":{"value":"loading"}},
                {"id":"c","status":{"value":"unloaded"}},{"id":"d","status":{"value":"unloaded","failed":true,"exit_code":1}},
                {"id":"e","status":{"value":"sleeping"}}]}""");

        assertThat(control.status("a")).isEqualTo(Residency.LOADED);
        assertThat(control.status("b")).isEqualTo(Residency.LOADING);
        assertThat(control.status("c")).isEqualTo(Residency.GONE);
        assertThat(control.status("d")).isEqualTo(Residency.FAILED);
        assertThat(control.status("e")).as("a state with no name may still hold memory").isEqualTo(Residency.OTHER);
        assertThat(control.status("unlisted")).isEqualTo(Residency.GONE);
        assertThat(control.resident()).containsExactly("a", "b", "e");
    }

    @Test
    void aMalformedModelListIsAFixedCode() {
        server.modelListAnswers(200, "{\"data\":[{\"id\":\"a\"}]}");
        assertCode(() -> control.status("a"), "control-malformed");
        server.modelListAnswers(200, "not json " + SERVER_TEXT);
        assertCode(control::resident, "control-malformed");
    }

    @Test
    void anErrorStatusIsAFixedCodeAndTheServerTextIsDropped() {
        server.controlAnswers("load", 500, "{\"error\":\"" + SERVER_TEXT + "\"}");
        assertCode(() -> control.load("text-a"), "control-status");
        server.controlAnswers("save", 501, "{\"error\":\"" + SERVER_TEXT + "\"}");
        assertCode(() -> control.saveSlot("text-a", 0, "f.bin"), "control-status");
    }

    @Test
    void anAnswerThatDoesNotReportSuccessIsRefused() {
        server.controlAnswers("unload", 200, "{\"success\":false}");
        assertCode(() -> control.unload("text-a"), "control-malformed");
        server.controlAnswers("restore", 200, "[]");
        assertCode(() -> control.restoreSlot("text-a", 0, "f.bin"), "control-malformed");
    }

    @Test
    void anOversizedAnswerIsAFixedCode() {
        server.controlAnswers("load", 200, "x".repeat(1024 * 1024 + 1));
        assertCode(() -> control.load("text-a"), "control-too-large");
    }

    @Test
    void aServerThatNeverAnswersMeetsTheDeadlineAndAClosedOneIsUnreachable() {
        var settings = LocalSettings.from(new MockEnvironment().withProperty(LocalSettings.PREFIX + "url", server.url()));
        var hurried = new LocalModelControl(settings, Duration.ofMillis(200));
        server.controlHolds("unload");
        assertCode(() -> hurried.unload("text-a"), "control-timeout");

        server.close();
        assertCode(() -> control.load("text-a"), "control-unreachable");
    }
}
