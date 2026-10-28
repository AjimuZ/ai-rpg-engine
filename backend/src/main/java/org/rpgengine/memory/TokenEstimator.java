package org.rpgengine.memory;

import java.nio.charset.StandardCharsets;

/**
 * Conservative token estimate for context budgets (docs/08 section 6). No tokenizer of the
 * selected runtime is available to the backend, so the estimate is an intentional upper bound:
 * one token per two UTF-8 bytes, rounded up.
 *
 * <p>Why two bytes: subword tokenizers average roughly four bytes per token on English prose and
 * a little less on Italian, but identifier-heavy JSON (UUIDs, references, punctuation) can fall to
 * about two bytes per token. Contexts are mostly such JSON, so the bound is set by the worst
 * common case rather than by prose. It is a fixed application assumption, versioned by
 * {@link #METHOD}, and is recorded in every envelope; P21 measurements may replace it.
 *
 * <p>The estimate is subadditive: {@code estimate(a + b) <= estimate(a) + estimate(b)}, so a
 * selection that fits item by item also fits when the items are rendered together.
 */
public final class TokenEstimator {
    /** Method label recorded in every envelope. */
    public static final String METHOD = "utf8-bytes-div-2-v1";

    /** Static helpers only. */
    private TokenEstimator() {
    }

    /**
     * @param bytes UTF-8 byte length, not negative
     * @return estimated tokens, rounded up
     * @throws IllegalArgumentException for a negative length
     */
    public static int tokens(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("byte length must not be negative");
        }
        return Math.toIntExact((bytes + 1) / 2);
    }

    /** @return estimated tokens of the UTF-8 encoding of the text */
    public static int tokens(String text) {
        return tokens(text.getBytes(StandardCharsets.UTF_8).length);
    }
}
