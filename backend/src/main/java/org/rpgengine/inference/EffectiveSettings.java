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

    /**
     * Parse the private-settings notation {@code model@effort} (neither part may contain
     * {@code @}, which is outside the model alphabet).
     *
     * @param entry notation to parse
     * @return the settings it names
     * @throws IllegalArgumentException when the notation is malformed; the message never echoes
     *         {@code entry}, because settings are private owner data
     */
    public static EffectiveSettings parse(String entry) {
        Objects.requireNonNull(entry, "entry");
        int at = entry.indexOf('@');
        if (at < 0 || at != entry.lastIndexOf('@')) {
            throw new IllegalArgumentException("expected model@effort");
        }
        return new EffectiveSettings(entry.substring(0, at).strip(), entry.substring(at + 1).strip());
    }
}
