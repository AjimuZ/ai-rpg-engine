package org.rpgengine.inference.slots;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP and PostgreSQL acceptance of the owner-only model status, settings and probe routes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000213")
class ModelSettingsHttpTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SlotSettingsService service;
    private final JsonMapper json = JsonMapper.builder().build();
    private String cookie;
    private String csrf;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    ModelSettingsHttpTest() {
    }

    /** Start from unassigned slots and a fresh admitted session. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        var session = exchange("GET", "/api/v1/session", "", "");
        assertThat(session.status()).isEqualTo(200);
        csrf = json.readTree(session.body()).get("csrfToken").asText();
        cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:"))
                .findFirst().orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** Remove only the slot rows this test wrote and drop the cached copy. */
    @AfterEach
    void clear() {
        jdbc.update("delete from inference_slot_setting");
        service.forget();
    }

    private Reply put(String body) throws Exception {
        return exchange("PUT", "/api/v1/settings/models", body, headers());
    }

    private JsonNode slot(Reply reply, String wire) throws Exception {
        for (JsonNode candidate : json.readTree(reply.body()).get("slots")) {
            if (candidate.get("slot").asText().equals(wire)) {
                return candidate;
            }
        }
        throw new AssertionError("slot missing: " + wire);
    }

    @Test
    void theRoutesRefuseARequestWithoutAdmission() throws Exception {
        assertThat(exchange("GET", "/api/v1/models/status", "", "").status()).isEqualTo(403);
        assertThat(exchange("PUT", "/api/v1/settings/models", "{}", "Origin: http://127.0.0.1:8080\r\n"
                + "Content-Type: application/json\r\nCookie: " + cookie + "\r\n").status()).isEqualTo(403);
        assertThat(exchange("POST", "/api/v1/models/probe", "{\"slot\":\"image\"}", "Content-Type: application/json\r\n")
                .status()).isEqualTo(403);
        assertThat(exchange("GET", "/api/v1/models/status", "", headers()).status()).isEqualTo(200);
    }

    @Test
    void statusListsFourUnassignedSlotsFirst() throws Exception {
        var reply = exchange("GET", "/api/v1/models/status", "", headers());
        assertThat(reply.status()).isEqualTo(200);
        JsonNode slots = json.readTree(reply.body()).get("slots");
        assertThat(slots).hasSize(4);
        assertThat(slots.get(0).get("slot").asText()).isEqualTo("text.light");
        assertThat(slots.get(3).get("slot").asText()).isEqualTo("image");
        for (JsonNode slot : slots) {
            assertThat(slot.get("source").asText()).isEqualTo("unassigned");
            assertThat(slot.get("class").isNull()).isTrue();
            assertThat(slot.get("probe").get("state").asText()).isEqualTo("not-run");
        }
    }

    @Test
    void aLocalAssignmentIsSavedWithItsBadgeAndAppearsInTheStatus() throws Exception {
        var saved = put("{\"slot\":\"text.light\",\"backend\":\"local\",\"model\":\"gemma\",\"effort\":\"low\"}");
        assertThat(saved.status()).isEqualTo(200);
        JsonNode slot = json.readTree(saved.body()).get("slot");
        assertThat(slot.get("class").get("class").asText()).isEqualTo("local");
        assertThat(slot.get("class").get("billing").asText()).isEqualTo("free");
        assertThat(slot.get("class").get("privacy").asText()).isEqualTo("this-machine");
        assertThat(slot.get("class").get("leavesMachine").asBoolean()).isFalse();
        assertThat(slot.get("acknowledged").isNull()).isTrue();

        JsonNode listed = slot(exchange("GET", "/api/v1/models/status", "", headers()), "text.light");
        assertThat(listed.get("source").asText()).isEqualTo("settings");
        assertThat(listed.get("model").asText()).isEqualTo("gemma");
        assertThat(jdbc.queryForObject("select count(*) from inference_slot_setting", Integer.class)).isEqualTo(1);
    }

    @Test
    void aClassChangeIsRefusedUntilTheNewClassIsAcknowledged() throws Exception {
        String codex = "{\"slot\":\"text.standard\",\"backend\":\"codex\",\"model\":\"m\",\"effort\":\"medium\"%s}";
        var refused = put(codex.formatted(""));
        assertThat(refused.status()).isEqualTo(409);
        JsonNode body = json.readTree(refused.body());
        assertThat(body.get("code").asText()).isEqualTo("CLASS_ACKNOWLEDGEMENT_REQUIRED");
        assertThat(body.get("from").asText()).isEqualTo("local");
        assertThat(body.get("to").asText()).isEqualTo("subscription");
        assertThat(jdbc.queryForObject("select count(*) from inference_slot_setting", Integer.class)).isZero();

        assertThat(put(codex.formatted(",\"acknowledgeClass\":\"metered\"")).status()).isEqualTo(409);
        var accepted = put(codex.formatted(",\"acknowledgeClass\":\"subscription\""));
        assertThat(accepted.status()).isEqualTo(200);
        JsonNode slot = json.readTree(accepted.body()).get("slot");
        assertThat(slot.get("acknowledged").get("class").asText()).isEqualTo("subscription");
        assertThat(slot.get("class").get("leavesMachine").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select acknowledged_class from inference_slot_setting", String.class))
                .isEqualTo("subscription");

        var toMetered = "{\"slot\":\"text.standard\",\"backend\":\"anthropic-api\",\"model\":\"m\",\"effort\":\"medium\"}";
        var again = put(toMetered);
        assertThat(again.status()).isEqualTo(409);
        assertThat(json.readTree(again.body()).get("from").asText()).isEqualTo("subscription");
        assertThat(jdbc.queryForObject("select backend from inference_slot_setting", String.class)).isEqualTo("codex");
    }

    @Test
    void aFallbackAcrossClassesIsRefusedAndASameClassOneIsKept() throws Exception {
        String head = "{\"slot\":\"text.standard\",\"backend\":\"codex\",\"model\":\"m\",\"effort\":\"low\","
                + "\"acknowledgeClass\":\"subscription\",\"fallbacks\":[%s]}";
        var crossing = put(head.formatted("{\"backend\":\"anthropic-api\",\"model\":\"x\",\"effort\":\"low\"}"));
        assertThat(crossing.status()).isEqualTo(422);
        assertThat(json.readTree(crossing.body()).get("code").asText()).isEqualTo("FALLBACK_CLASS_CROSSING");
        assertThat(jdbc.queryForObject("select count(*) from inference_slot_setting", Integer.class)).isZero();

        var same = put(head.formatted("{\"backend\":\"claude\",\"model\":\"x\",\"effort\":\"low\"}"));
        assertThat(same.status()).isEqualTo(200);
        JsonNode fallbacks = json.readTree(same.body()).get("slot").get("fallbacks");
        assertThat(fallbacks).hasSize(1);
        assertThat(fallbacks.get(0).get("backend").asText()).isEqualTo("claude");
    }

    @Test
    void anEndpointNeedsADeclaredClassAndTheImageSlotNeedsNoEffort() throws Exception {
        var undeclared = put("{\"slot\":\"text.deliberate\",\"backend\":\"openai-compatible\",\"model\":\"m\","
                + "\"effort\":\"low\"}");
        assertThat(undeclared.status()).isEqualTo(422);
        assertThat(json.readTree(undeclared.body()).get("code").asText()).isEqualTo("SLOT_CLASS_DECLARATION_INVALID");
        var declared = put("{\"slot\":\"text.deliberate\",\"backend\":\"openai-compatible\",\"model\":\"m\","
                + "\"effort\":\"low\",\"declaredClass\":\"local\"}");
        assertThat(declared.status()).isEqualTo(200);
        var image = put("{\"slot\":\"image\",\"backend\":\"local-image\",\"model\":\"sdxl\"}");
        assertThat(image.status()).isEqualTo(200);
        assertThat(json.readTree(image.body()).get("slot").get("effort").asText()).isEqualTo("none");
        var wrongBackend = put("{\"slot\":\"image\",\"backend\":\"codex\",\"model\":\"sdxl\"}");
        assertThat(wrongBackend.status()).isEqualTo(422);
        assertThat(json.readTree(wrongBackend.body()).get("code").asText()).isEqualTo("SLOT_BACKEND_UNSUPPORTED");
    }

    @Test
    void malformedBodiesAreRefusedWithoutEchoingValues() throws Exception {
        String valid = "{\"slot\":\"text.light\",\"backend\":\"local\",\"model\":\"m\",\"effort\":\"low\"}";
        for (String malformed : new String[] {"[]", "{}", "not json", valid.replace("\"local\"", "7"),
                valid.replace("{", "{\"extra\":1,"), valid.replace("{", "{\"model\":\"dup\","),
                valid.replace("text.light", "text.unknown"), valid.replace(",\"effort\":\"low\"", ""),
                valid.replace("{", "{\"declaredClass\":\"gold\","), valid.replace("{", "{\"fallbacks\":{},"),
                valid.replace("{", "{\"fallbacks\":[{\"backend\":\"local\",\"extra\":1}],")}) {
            var reply = put(malformed);
            assertThat(reply.status()).as(malformed).isEqualTo(400);
            assertThat(reply.body()).isEqualTo("{\"code\":\"MODEL_SETTINGS_MALFORMED\"}");
        }
        assertThat(put(" ".repeat(8193)).status()).isEqualTo(413);
        assertThat(jdbc.queryForObject("select count(*) from inference_slot_setting", Integer.class)).isZero();
    }

    @Test
    void aProbeResultIsVisibleAndSurvivesInStorage() throws Exception {
        var missing = exchange("POST", "/api/v1/models/probe", "{\"slot\":\"text.standard\"}", headers());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).isEqualTo("{\"code\":\"MODEL_SLOT_NOT_ASSIGNED\"}");
        assertThat(exchange("POST", "/api/v1/models/probe", "{\"slot\":\"nope\"}", headers()).status()).isEqualTo(400);

        put("{\"slot\":\"text.standard\",\"backend\":\"local\",\"model\":\"gemma\",\"effort\":\"low\"}");
        var probed = exchange("POST", "/api/v1/models/probe", "{\"slot\":\"text.standard\"}", headers());
        assertThat(probed.status()).isEqualTo(200);
        JsonNode probe = json.readTree(probed.body()).get("slot").get("probe");
        // The test installation runs no model server, so the managed local backend has no adapter.
        assertThat(probe.get("state").asText()).isEqualTo("unreachable");
        assertThat(probe.get("code").asText()).isEqualTo("adapter-not-installed");
        assertThat(probe.get("at").isNull()).isFalse();

        JsonNode listed = slot(exchange("GET", "/api/v1/models/status", "", headers()), "text.standard");
        assertThat(listed.get("probe").get("state").asText()).isEqualTo("unreachable");
        assertThat(jdbc.queryForObject("select probe_state || '/' || probe_code from inference_slot_setting",
                String.class)).isEqualTo("UNREACHABLE/adapter-not-installed");
        // Saving a different model discards the result that described the old one.
        put("{\"slot\":\"text.standard\",\"backend\":\"local\",\"model\":\"other\",\"effort\":\"low\"}");
        assertThat(slot(exchange("GET", "/api/v1/models/status", "", headers()), "text.standard")
                .get("probe").get("state").asText()).isEqualTo("not-run");
    }

    private String headers() {
        return "Origin: http://127.0.0.1:8080\r\nContent-Type: application/json\r\nCookie: " + cookie
                + "\r\nX-CSRF-Token: " + csrf + "\r\n";
    }

    /** Use a loopback socket so the Host header can be the configured origin while the port is random. */
    private Reply exchange(String method, String path, String body, String headers) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n"
                    + "Connection: close\r\n" + headers + "Content-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String[] parts = response.split("\r\n\r\n", 2);
            String bodyText = parts[1];
            if (parts[0].toLowerCase().contains("transfer-encoding: chunked")) {
                var decoded = new StringBuilder();
                int offset = 0;
                while (true) {
                    int end = bodyText.indexOf("\r\n", offset);
                    int length = Integer.parseInt(bodyText.substring(offset, end), 16);
                    if (length == 0) {
                        break;
                    }
                    decoded.append(bodyText, end + 2, end + 2 + length);
                    offset = end + 2 + length + 2;
                }
                bodyText = decoded.toString();
            }
            bodyText = new String(bodyText.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return new Reply(Integer.parseInt(parts[0].split(" ", 3)[1]), parts[0], bodyText);
        }
    }

    /** Detached HTTP status, raw headers and body; holds no live connection. */
    private record Reply(int status, String headers, String body) {
    }
}
