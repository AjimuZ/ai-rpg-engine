package org.rpgengine.inference.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.ImageResult.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter against the scripted gateway fixture, which shows nothing about how a real Forge or A1111
 * server answers. Failures must carry a fixed code and none of the server's text or address.
 */
class A1111ImageAdapterTest {
    private static final String SERVER_TEXT = "private-server-text";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SIDE = 512;

    private ScriptedForgeServer server;
    private A1111ImageAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = new ScriptedForgeServer();
        adapter = new A1111ImageAdapter(A1111Settings.of(server.url(), ScriptedForgeServer.DESTINATION));
    }

    @AfterEach
    void stop() {
        adapter.close();
        server.close();
    }

    private static ImageRequest request() {
        return new ImageRequest("a lantern in fog, " + SERVER_TEXT, "blurry", "modelA.safetensors", SIDE, SIDE, 20, 6.5,
                1234, "Euler a");
    }

    private static String image() {
        return PngFixtures.base64(PngFixtures.valid(SIDE, SIDE));
    }

    private static ImageResult await(ImageHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private ImageResult run(ImageRequest request) throws Exception {
        return await(adapter.submit(request));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void assertFailed(ImageResult result, String code) {
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo(code).doesNotContain(SERVER_TEXT, server.url());
        assertThat(result.png()).isNull();
        assertThat(result.provenance()).isNull();
    }

    @Test
    void aSuccessfulJobReturnsTheValidatedImageWithItsProvenance() throws Exception {
        server.answers(image());

        ImageResult result = run(request());

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        byte[] png = result.png();
        assertThat(PngCheck.validate(png, SIDE, SIDE)).isEqualTo(png);
        assertThat(new String(png, StandardCharsets.ISO_8859_1)).doesNotContain("parameters", "private prompt");
        var provenance = result.provenance();
        assertThat(provenance.backend()).isEqualTo("a1111-http");
        assertThat(provenance.model()).isEqualTo("modelA.safetensors");
        assertThat(provenance.serverModelHash()).isEqualTo("abcdef0123");
        assertThat(provenance.sampler()).isEqualTo("Euler a");
        assertThat(List.of(provenance.seed(), (long) provenance.steps(), (long) provenance.width(),
                (long) provenance.height())).containsExactly(1234L, 20L, 512L, 512L);
        assertThat(provenance.guidance()).isEqualTo(6.5);
        assertThat(provenance.promptSha256())
                .isEqualTo(sha256(request().prompt().getBytes(StandardCharsets.UTF_8)));
        assertThat(provenance.negativePromptSha256()).isEqualTo(sha256("blurry".getBytes(StandardCharsets.UTF_8)));
        assertThat(provenance.outputSha256()).isEqualTo(sha256(png));
        assertThat(provenance.outputBytes()).isEqualTo(png.length);
        assertThat(provenance.toString()).doesNotContain(SERVER_TEXT);
    }

    @Test
    void theRequestsFollowTheDocumentedRoutesAndCarryOnlyTheImageFields() throws Exception {
        server.answers(image());

        run(request());

        assertThat(server.paths()).containsExactly("/upstream/forge/sdapi/v1/progress?skip_current_image=true",
                "/upstream/forge/sdapi/v1/txt2img");
        ScriptedForgeServer.Seen generation = server.seen().get(1);
        assertThat(generation.method()).isEqualTo("POST");
        assertThat(generation.headers()).containsEntry("content-type", "application/json").doesNotContainKeys("cookie",
                "authorization");
        JsonNode body = JSON.readTree(generation.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("prompt", "negative_prompt", "width", "height",
                "steps", "cfg_scale", "seed", "batch_size", "n_iter", "send_images", "save_images", "sampler_name",
                "override_settings", "override_settings_restore_afterwards");
        assertThat(body.path("prompt").stringValue()).isEqualTo(request().prompt());
        assertThat(body.path("seed").longValue()).isEqualTo(1234);
        assertThat(body.path("save_images").asBoolean(true)).isFalse();
        assertThat(body.path("override_settings").path("sd_model_checkpoint").stringValue())
                .isEqualTo("modelA.safetensors");
    }

    @Test
    void aRequestWithoutModelOrSamplerLeavesTheServerDefaults() throws Exception {
        server.answers(image());

        run(new ImageRequest("a lantern", "", null, SIDE, SIDE, 20, 6.5, 1, null));

        JsonNode body = JSON.readTree(server.seen().get(1).body());
        assertThat(body.has("override_settings")).isFalse();
        assertThat(body.has("sampler_name")).isFalse();
    }

    @Test
    void noCookieIsKeptBetweenCalls() throws Exception {
        server.answers(image()).answers(image());

        run(request());
        run(request());

        assertThat(server.seen()).hasSize(4).allSatisfy(seen -> assertThat(seen.headers()).doesNotContainKey("cookie"));
    }

    @Test
    void cancellingARunningJobInterruptsTheServerAndDropsTheImage() throws Exception {
        server.holds(ScriptedForgeServer.success(image()));
        ImageHandle handle = adapter.submit(request());
        assertThat(server.awaitGeneration()).isTrue();

        assertThat(handle.cancel()).isTrue();
        ImageResult result = await(handle);

        assertThat(result.status()).isEqualTo(Status.CANCELLED);
        assertThat(result.png()).isNull();
        assertThat(result.provenance()).isNull();
        assertThat(server.interrupts()).isEqualTo(1);
        assertThat(server.paths()).contains("/upstream/forge/sdapi/v1/interrupt");
        assertThat(handle.cancel()).isFalse();
    }

    @Test
    void cancellingAQueuedJobEndsItAtOnceAndNeverTouchesTheServer() throws Exception {
        server.holds(ScriptedForgeServer.success(image())).answers(image());
        ImageHandle running = adapter.submit(request());
        assertThat(server.awaitGeneration()).isTrue();
        ImageHandle queued = adapter.submit(request());

        assertThat(queued.cancel()).isTrue();

        assertThat(await(queued).status()).isEqualTo(Status.CANCELLED);
        assertThat(server.interrupts()).isZero();
        server.release();
        assertThat(await(running).status()).isEqualTo(Status.COMPLETED);
        assertThat(server.seen().stream().filter(seen -> seen.path().endsWith("/txt2img"))).hasSize(1);
        assertThat(server.interrupts()).isZero();
    }

    @Test
    void cancellingAFinishedJobDoesNothing() throws Exception {
        server.answers(image());
        ImageHandle handle = adapter.submit(request());
        assertThat(await(handle).status()).isEqualTo(Status.COMPLETED);

        assertThat(handle.cancel()).isFalse();

        assertThat(server.interrupts()).isZero();
    }

    @Test
    void ownJobsRunOneAtATime() throws Exception {
        server.answers(image()).answers(image()).answers(image());

        List<ImageHandle> handles = List.of(adapter.submit(request()), adapter.submit(request()),
                adapter.submit(request()));

        for (ImageHandle handle : handles) {
            assertThat(await(handle).status()).isEqualTo(Status.COMPLETED);
        }
        assertThat(server.peakGenerations()).isEqualTo(1);
    }

    @Test
    void aFullQueueFailsTheSubmissionWithoutBlocking() throws Exception {
        adapter.close();
        adapter = new A1111ImageAdapter(new A1111Settings(server.url(), ScriptedForgeServer.DESTINATION,
                Duration.ofSeconds(20), Duration.ofSeconds(5), 1));
        server.holds(ScriptedForgeServer.success(image())).answers(image());
        ImageHandle running = adapter.submit(request());
        assertThat(server.awaitGeneration()).isTrue();
        ImageHandle queued = adapter.submit(request());

        ImageResult refused = await(adapter.submit(request()));

        assertFailed(refused, "image-queue-full");
        server.release();
        assertThat(await(running).status()).isEqualTo(Status.COMPLETED);
        assertThat(await(queued).status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void closingTheAdapterFailsQueuedJobs() throws Exception {
        server.holds(ScriptedForgeServer.success(image()));
        ImageHandle running = adapter.submit(request());
        assertThat(server.awaitGeneration()).isTrue();
        ImageHandle queued = adapter.submit(request());

        adapter.close();

        assertFailed(await(queued), "image-shutdown");
        assertFailed(await(adapter.submit(request())), "image-shutdown");
        server.release();
        assertThat(await(running).status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void aServerBusyWithAForeignJobIsReportedAndLeftAlone() throws Exception {
        server.busyWith(1);

        ImageResult result = run(request());

        assertFailed(result, "image-server-busy");
        assertThat(server.paths()).containsExactly("/upstream/forge/sdapi/v1/progress?skip_current_image=true");
        assertThat(server.interrupts()).isZero();
    }

    @Test
    void anImageThatIsNotValidIsRefusedWithTheCodeOfTheBrokenRule() throws Exception {
        byte[] other = PngFixtures.valid(SIDE + 8, SIDE);
        byte[] badCrc = PngFixtures.valid(SIDE, SIDE);
        badCrc[badCrc.length - 1] ^= 1;
        byte[] truncated = java.util.Arrays.copyOf(PngFixtures.valid(SIDE, SIDE), 60);
        String[][] cases = {
                {PngFixtures.base64(PngFixtures.valid(SIDE, SIDE)).substring(1) + "!", "image-bad-encoding"},
                {"not base64 " + SERVER_TEXT, "image-bad-encoding"},
                {PngFixtures.base64("plain text, not a png".getBytes(StandardCharsets.UTF_8)), "image-bad-png"},
                {PngFixtures.base64(badCrc), "image-bad-png"},
                {PngFixtures.base64(truncated), "image-bad-png"},
                {PngFixtures.base64(other), "image-wrong-size"},
        };
        for (String[] invalid : cases) {
            server.answers(invalid[0]);
            assertFailed(run(request()), invalid[1]);
        }
    }

    @Test
    void anAnswerWithoutExactlyOneImageOrNotJsonIsMalformed() throws Exception {
        server.answers().answers(image(), image()).replies(200, "<html>" + SERVER_TEXT + "</html>")
                .replies(200, "[]").replies(200, "{\"images\":[5]}");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertFailed(run(request()), "image-malformed");
        }
    }

    @Test
    void aServerThatDoesNotAnswerInTimeEndsTheJobWithATimeoutCode() throws Exception {
        adapter.close();
        adapter = new A1111ImageAdapter(new A1111Settings(server.url(), ScriptedForgeServer.DESTINATION,
                Duration.ofMillis(400), Duration.ofSeconds(5), 4));
        server.holds(ScriptedForgeServer.success(image()));

        assertFailed(run(request()), "image-timeout");
    }

    @Test
    void anHttpErrorIsAStatusCodeAndItsBodyIsDropped() throws Exception {
        server.replies(500, "{\"error\":\"" + SERVER_TEXT + "\"}");

        assertFailed(run(request()), "image-status");
    }

    @Test
    void theGatewaysRefusalOfTheDestinationIsNamedAndEveryOtherRefusalIsMapped() throws Exception {
        String[][] refusals = {
                {"destination-not-allowed", "image-destination-not-allowed"},
                {"path-not-allowed", "image-destination-not-allowed"},
                {"method-not-allowed", "image-destination-not-allowed"},
                {"response-too-large", "image-response-too-large"},
                {"upstream-timeout", "image-timeout"},
                {"upstream-unreachable", "image-unreachable"},
                {"upstream-status", "image-status"},
                {"surprising-" + SERVER_TEXT, "image-status"},
        };
        for (String[] refusal : refusals) {
            server.replies(refusal[0].startsWith("upstream-t") ? 504 : 403, "{\"code\":\"" + refusal[0] + "\"}");
            assertFailed(run(request()), refusal[1]);
        }
    }

    @Test
    void aResponseOverTheBoundIsRefusedWithoutBeingKept() throws Exception {
        adapter.close();
        adapter = new A1111ImageAdapter(A1111Settings.of(server.url(), ScriptedForgeServer.DESTINATION), 1000);
        server.sendsBytes(5000);

        assertFailed(run(request()), "image-response-too-large");
    }

    @Test
    void aDroppedConnectionIsUnreachable() throws Exception {
        server.dropsConnection();

        assertFailed(run(request()), "image-unreachable");
    }

    @Test
    void aGatewayThatIsNotThereIsUnreachableAndShowsNoAddress() throws Exception {
        String url = server.url();
        server.close();

        ImageResult result = run(request());

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isIn("image-unreachable", "image-timeout").doesNotContain(url);
    }

    @Test
    void aRedirectIsNotFollowed() throws Exception {
        server.redirectsTo("http://127.0.0.1:1/elsewhere");

        assertFailed(run(request()), "image-status");
        assertThat(server.paths()).hasSize(2);
    }
}
