package org.rpgengine.inference;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One image to generate, as every image backend receives it. It holds the prompt text, the picture
 * geometry and the sampling settings, and nothing else: no session, actor, scene or canonical state
 * is passed to a backend, and a backend decides nothing about the game (docs/34 section 8).
 *
 * <p>The bounds are those of the packaged image worker, so one request is valid for every adapter.
 * The record validates itself and throws {@link IllegalArgumentException} naming the field and never
 * its value, because prompts may be private.
 *
 * @param prompt positive prompt, at most {@value #MAX_PROMPT_CHARACTERS} characters and not blank
 * @param negativePrompt negative prompt, possibly empty, at most {@value #MAX_PROMPT_CHARACTERS} characters
 * @param model checkpoint name the backend should use, or null for the backend's current one
 * @param width pixels, {@value #MIN_SIDE} to {@value #MAX_SIDE} in steps of {@value #SIDE_STEP}
 * @param height pixels, same bounds as the width
 * @param steps sampling steps, 1 to {@value #MAX_STEPS}
 * @param guidance guidance scale, 1 to {@value #MAX_GUIDANCE}
 * @param seed 0 to {@value #MAX_SEED}; always explicit so that the provenance can replay the image
 * @param sampler sampler name, or null for the backend's default
 */
public record ImageRequest(String prompt, String negativePrompt, String model, int width, int height, int steps,
        double guidance, long seed, String sampler) {
    /** Longest accepted prompt of either kind, in characters. */
    public static final int MAX_PROMPT_CHARACTERS = 8192;
    public static final int MIN_SIDE = 512;
    public static final int MAX_SIDE = 1536;
    public static final int SIDE_STEP = 8;
    public static final int MAX_STEPS = 60;
    public static final double MAX_GUIDANCE = 12.0;
    public static final long MAX_SEED = (1L << 32) - 1;

    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()\\[\\]+/-]{0,199}");
    private static final Pattern SAMPLER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._+-]{0,63}");

    public ImageRequest {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(negativePrompt, "negativePrompt");
        require(!prompt.isBlank() && prompt.length() <= MAX_PROMPT_CHARACTERS, "prompt");
        require(negativePrompt.length() <= MAX_PROMPT_CHARACTERS, "negativePrompt");
        require(model == null || (MODEL.matcher(model).matches() && !model.contains("..")), "model");
        require(side(width), "width");
        require(side(height), "height");
        require(steps >= 1 && steps <= MAX_STEPS, "steps");
        require(guidance >= 1.0 && guidance <= MAX_GUIDANCE, "guidance");
        require(seed >= 0 && seed <= MAX_SEED, "seed");
        require(sampler == null || SAMPLER.matcher(sampler).matches(), "sampler");
    }

    private static boolean side(int value) {
        return value >= MIN_SIDE && value <= MAX_SIDE && value % SIDE_STEP == 0;
    }

    private static void require(boolean valid, String field) {
        if (!valid) {
            throw new IllegalArgumentException("invalid image request field: " + field);
        }
    }
}
