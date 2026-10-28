package org.rpgengine.inference;

import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the profile resolution of P08.4 from private owner settings: the approved allowlist
 * ({@code engine.inference.allowlist.<backend>}) and the optional {@code fixedQuality} slot
 * ({@code engine.inference.fixed-quality}, one of {@code light}, {@code standard},
 * {@code deliberate}). Malformed values fail startup without echoing them.
 */
@Configuration(proxyBeanMethods = false)
class ProfileConfiguration {
    /**
     * @param environment private settings
     * @return the owner's allowlist; empty when no backend is configured
     */
    @Bean
    ModelAllowlist modelAllowlist(Environment environment) {
        return ModelAllowlist.from(environment);
    }

    /**
     * @param assignments owner slot assignment
     * @param allowlist owner-approved pairs
     * @param environment private settings
     * @return the resolver adapters consult for every task
     * @throws IllegalStateException when {@code fixed-quality} names no text slot
     */
    @Bean
    ProfileResolver profileResolver(SlotAssignments assignments, ModelAllowlist allowlist, Environment environment) {
        String raw = environment.getProperty("engine.inference.fixed-quality");
        Optional<InferenceSlot> fixed = Optional.empty();
        if (raw != null && !raw.isBlank()) {
            String wanted = raw.strip().toLowerCase(Locale.ROOT);
            for (InferenceSlot slot : InferenceSlot.values()) {
                if (slot.key().equals(wanted)) {
                    fixed = Optional.of(slot);
                }
            }
            if (fixed.isEmpty()) {
                throw new IllegalStateException("engine.inference.fixed-quality must be light, standard or deliberate");
            }
        }
        return new ProfileResolver(assignments, allowlist, fixed);
    }
}
