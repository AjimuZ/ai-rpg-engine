package org.rpgengine.inference;

import java.util.Objects;

/**
 * Where one image came from and what produced it, kept next to the image so that it can be
 * explained and replayed. It holds hashes and settings and never prompt text, because prompts may
 * be private (docs/23 section on provenance, docs/34 section 8).
 *
 * <p>{@code backend} and the request echo are set by the adapter from what it sent. The hashes and
 * the byte count are computed over what was received and validated. {@code serverModelHash} is the
 * checkpoint hash the server itself reported, or null when it reported none: an owner-run server's
 * answer is recorded, not trusted.
 *
 * @param backend adapter protocol name, such as {@code a1111-http}
 * @param model checkpoint name requested, or null when the server's current checkpoint was used
 * @param serverModelHash checkpoint hash reported by the server, lowercase hexadecimal, or null
 * @param sampler sampler name requested, or null for the server's default
 * @param seed seed sent with the request
 * @param steps sampling steps sent
 * @param guidance guidance scale sent
 * @param width requested and validated picture width
 * @param height requested and validated picture height
 * @param promptSha256 SHA-256 of the UTF-8 prompt, lowercase hexadecimal
 * @param negativePromptSha256 SHA-256 of the UTF-8 negative prompt, lowercase hexadecimal
 * @param outputSha256 SHA-256 of the validated PNG bytes, lowercase hexadecimal
 * @param outputBytes size of the validated PNG in bytes
 */
public record ImageProvenance(String backend, String model, String serverModelHash, String sampler, long seed,
        int steps, double guidance, int width, int height, String promptSha256, String negativePromptSha256,
        String outputSha256, long outputBytes) {
    public ImageProvenance {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(promptSha256, "promptSha256");
        Objects.requireNonNull(negativePromptSha256, "negativePromptSha256");
        Objects.requireNonNull(outputSha256, "outputSha256");
    }
}
