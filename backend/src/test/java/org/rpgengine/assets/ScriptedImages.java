package org.rpgengine.assets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;

/**
 * Images and results a scripted image port returns in the asset tests (P17.6-D): pictures of the requested size with
 * provenance that describes exactly the request and the bytes, as a conforming adapter reports them, so they pass the
 * acceptance review unless a test spoils one part on purpose.
 */
final class ScriptedImages {
    private ScriptedImages() {
    }

    /** A PNG of the given size whose bytes, and so hash, depend on the shade; it is never one flat colour. */
    static byte[] png(int width, int height, int shade) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x010101 * (shade & 0xff));
        image.setRGB(width - 1, height - 1, 0xffffff);
        return encode(image);
    }

    /** A PNG of the given size in one colour, as a blank or filtered backend output looks. */
    static byte[] uniform(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
    }

    /** A completed result for the request with honest provenance. */
    static ImageResult completed(ImageRequest request, byte[] png) {
        return ImageResult.completed(png, provenance(request, png, ManagedAssets.sha256(
                request.prompt().getBytes(StandardCharsets.UTF_8))));
    }

    /** Provenance of {@code png} for {@code request}, with the given prompt hash. */
    static ImageProvenance provenance(ImageRequest request, byte[] png, String promptSha256) {
        return new ImageProvenance("scripted", null, null, null, request.seed(), request.steps(), request.guidance(),
                request.width(), request.height(), promptSha256,
                ManagedAssets.sha256(request.negativePrompt().getBytes(StandardCharsets.UTF_8)), ManagedAssets.sha256(png),
                png.length);
    }

    private static byte[] encode(BufferedImage image) {
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
