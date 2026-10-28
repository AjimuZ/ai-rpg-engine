package org.rpgengine.inference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Owner-approved model and effort pairs per backend, read from private settings
 * {@code engine.inference.allowlist.<backend>}: a comma-separated list of {@code model@effort}
 * entries (docs/33 model routing, docs/34 section 12).
 *
 * <p>The allowlist has two roles. For a backend that cannot enumerate its own models (an agent
 * client or an owner-run endpoint) it <em>is</em> the catalog: only listed pairs run. For a
 * backend that does enumerate them (the Codex model list) it narrows the discovered catalog to
 * what the owner approved, so a model that appears in an account later is never used silently.
 * A backend with no entry has no allowlist and is bounded by its discovered catalog alone.
 * Instances are immutable.
 */
public final class ModelAllowlist {
    /** Bound of one backend's list; the allowlist is an owner decision and stays reviewable. */
    public static final int MAX_ENTRIES = 256;

    private static final ModelAllowlist EMPTY = new ModelAllowlist(Map.of());

    private final Map<String, Set<EffectiveSettings>> approved;

    private ModelAllowlist(Map<String, Set<EffectiveSettings>> approved) {
        this.approved = approved;
    }

    /** @return an allowlist that configures no backend */
    public static ModelAllowlist empty() {
        return EMPTY;
    }

    /**
     * @param approved approved pairs by backend; a backend mapped to an empty list is configured
     *        and permits nothing
     * @return the immutable allowlist
     * @throws IllegalArgumentException for an unknown backend or more than {@link #MAX_ENTRIES} pairs
     */
    public static ModelAllowlist of(Map<String, List<EffectiveSettings>> approved) {
        Map<String, Set<EffectiveSettings>> copy = new HashMap<>();
        approved.forEach((backend, pairs) -> {
            if (!SlotAssignment.TEXT_BACKENDS.contains(backend)) {
                throw new IllegalArgumentException("unknown text backend");
            }
            if (pairs.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("allowlist too long");
            }
            copy.put(backend, Set.copyOf(new HashSet<>(pairs)));
        });
        return new ModelAllowlist(Map.copyOf(copy));
    }

    /**
     * Read {@code engine.inference.allowlist.<backend>} for every text backend (package-private:
     * the neutral API names no framework type, and {@link ProfileConfiguration} is the reader). A blank value is
     * treated as unset, never as "approve nothing": an owner who wants to approve nothing leaves
     * the slot unassigned.
     *
     * @param environment Spring environment holding the private settings
     * @return the configured allowlist
     * @throws IllegalStateException for a malformed list; the message names the backend, never the value
     */
    static ModelAllowlist from(Environment environment) {
        Map<String, List<EffectiveSettings>> read = new HashMap<>();
        for (String backend : SlotAssignment.TEXT_BACKENDS) {
            String raw = environment.getProperty("engine.inference.allowlist." + backend);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            List<EffectiveSettings> pairs = new ArrayList<>();
            try {
                for (String entry : raw.split(",")) {
                    pairs.add(EffectiveSettings.parse(entry.strip()));
                }
                read.put(backend, pairs);
                of(Map.of(backend, pairs));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("allowlist of backend " + backend + " is malformed or too long", e);
            }
        }
        return of(read);
    }

    /** @return whether the owner configured an allowlist for {@code backend} */
    public boolean configured(String backend) {
        return approved.containsKey(Objects.requireNonNull(backend, "backend"));
    }

    /**
     * @param backend protocol adapter name
     * @param settings model and effort to test
     * @return true only when an allowlist is configured for {@code backend} and lists the exact pair
     */
    public boolean permits(String backend, EffectiveSettings settings) {
        Set<EffectiveSettings> pairs = approved.get(Objects.requireNonNull(backend, "backend"));
        return pairs != null && pairs.contains(Objects.requireNonNull(settings, "settings"));
    }
}
