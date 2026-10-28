package org.rpgengine.inference;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Model and reasoning effort a slot resolves to for one task. Both values are opaque private
 * identifiers of the selected backend's catalog (never product constants); an adapter sends
 * them explicitly on every turn and records them as the task's provenance.
 *
 * @param model catalog model identifier, 1–128 characters of {@code [A-Za-z0-9._:/-]}
 * @param effort reasoning effort name, 1–32 lowercase letters
 */
public record EffectiveSettings(String model, String effort) {
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern EFFORT = Pattern.compile("[a-z]{1,32}");

    /** Reject values that could not be a catalog identifier, before any runtime sees them. */
    public EffectiveSettings {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(effort, "effort");
        if (!MODEL.matcher(model).matches() || !EFFORT.matcher(effort).matches()) {
            throw new IllegalArgumentException("invalid model or effort identifier");
        }
    }
}
