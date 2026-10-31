package org.rpgengine.inference.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;

/**
 * The port's shape: a request that carries no game state, a result that carries provenance, an adapter
 * that implements the port, and settings that refuse an unsafe gateway address.
 */
class ImageContractTest {
    private static ImageRequest request(int width, int steps, double guidance, long seed) {
        return new ImageRequest("a lantern", "", null, width, 512, steps, guidance, seed, null);
    }

    @Test
    void theRequestCarriesOnlyPromptGeometryAndSamplingFields() {
        List<String> components = Arrays.stream(ImageRequest.class.getRecordComponents()).map(RecordComponent::getName)
                .toList();
        List<Class<?>> types = Arrays.stream(ImageRequest.class.getRecordComponents()).map(RecordComponent::getType)
                .collect(java.util.stream.Collectors.toList());

        assertThat(components).containsExactly("prompt", "negativePrompt", "model", "width", "height", "steps",
                "guidance", "seed", "sampler");
        assertThat(types).containsOnly(String.class, int.class, double.class, long.class);
        assertThat(Arrays.stream(A1111ImageAdapter.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())).toList())
                .containsOnly(A1111Settings.class, int.class);
    }

    @Test
    void theResultCarriesProvenanceWhenCompletedAndNeverTextOfThePrompt() {
        List<String> components = Arrays.stream(ImageProvenance.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(components).contains("backend", "promptSha256", "negativePromptSha256", "outputSha256", "outputBytes",
                "seed", "serverModelHash").doesNotContain("prompt", "negativePrompt");
        assertThat(A1111ImageAdapter.class).isAssignableTo(ImageGenerationPort.class);
        assertThat(ImageResult.cancelled().provenance()).isNull();
        assertThatThrownBy(() -> new ImageResult(ImageResult.Status.COMPLETED, new byte[1], null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageResult(ImageResult.Status.FAILED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRequestOutsideTheWorkersBoundsIsRefusedWithoutEchoingTheValue() {
        List<ImageRequest> valid = List.of(request(512, 1, 1.0, 0), request(1536, 60, 12.0, ImageRequest.MAX_SEED));
        assertThat(valid).hasSize(2);
        String leaked = "leaked-prompt-text";
        assertThatThrownBy(() -> new ImageRequest(" ", "", null, 512, 512, 20, 6, 1, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prompt");
        assertThatThrownBy(() -> new ImageRequest(leaked + "x".repeat(ImageRequest.MAX_PROMPT_CHARACTERS), "", null, 512,
                512, 20, 6, 1, null)).hasMessageNotContaining(leaked);
        assertThatThrownBy(() -> request(504, 20, 6, 1)).hasMessageContaining("width");
        assertThatThrownBy(() -> request(1544, 20, 6, 1)).hasMessageContaining("width");
        assertThatThrownBy(() -> request(516, 20, 6, 1)).hasMessageContaining("width");
        assertThatThrownBy(() -> request(512, 0, 6, 1)).hasMessageContaining("steps");
        assertThatThrownBy(() -> request(512, 61, 6, 1)).hasMessageContaining("steps");
        assertThatThrownBy(() -> request(512, 20, 0.5, 1)).hasMessageContaining("guidance");
        assertThatThrownBy(() -> request(512, 20, Double.NaN, 1)).hasMessageContaining("guidance");
        assertThatThrownBy(() -> request(512, 20, 6, -1)).hasMessageContaining("seed");
        assertThatThrownBy(() -> request(512, 20, 6, ImageRequest.MAX_SEED + 1)).hasMessageContaining("seed");
        assertThatThrownBy(() -> new ImageRequest("a", "", "../model", 512, 512, 20, 6, 1, null))
                .hasMessageContaining("model");
        assertThatThrownBy(() -> new ImageRequest("a", "", null, 512, 512, 20, 6, 1, "bad\nsampler"))
                .hasMessageContaining("sampler");
    }

    @Test
    void theSettingsRefuseAGatewayAddressThatIsNotAnOriginAndNameOnlyTheField() {
        String leaked = "leaked-host-text";
        for (String url : new String[] {null, "", "ftp://gateway", "http://" + leaked + "@gateway:8080",
                "http://gateway:8080/" + leaked, "http://gateway:8080?" + leaked, "http://gateway:8080#" + leaked,
                "not a url " + leaked}) {
            assertThatThrownBy(() -> A1111Settings.of(url, "forge")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gatewayUrl").hasMessageNotContaining(leaked);
        }
        assertThat(A1111Settings.of("http://gateway:8080/", "forge").uri("/sdapi/v1/txt2img").toString())
                .isEqualTo("http://gateway:8080/upstream/forge/sdapi/v1/txt2img");
        assertThatThrownBy(() -> A1111Settings.of("http://gateway:8080", "Forge/..")).hasMessageContaining("destination");
        assertThatThrownBy(() -> new A1111Settings("http://gateway:8080", "forge", Duration.ZERO,
                Duration.ofSeconds(1), 1)).hasMessageContaining("generateTimeout");
        assertThatThrownBy(() -> new A1111Settings("http://gateway:8080", "forge", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 0)).hasMessageContaining("maxQueued");
    }
}
