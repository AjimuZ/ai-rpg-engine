package org.rpgengine.inference.contract;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.presentation.SupportedLocale;

/**
 * Identity and presentation generation that one contract task is bound to (docs/21 sections 2 and
 * 13, docs/29 section 5). The binding is supplied by the trusted caller, rendered into the request
 * envelope and echoed back by the output; an echo is a stale-result check, never authentication.
 *
 * <p>The binding is also the publication guard: a result generated for one locale or glossary
 * revision must not be shown after either changed, which {@link #presentationCurrent} decides.
 *
 * @param taskId contract task identity, linked by the caller to its operation or job; echoed by
 *        the output and shared by the original attempt and its single repair
 * @param adventureId owning adventure
 * @param campaignId owning campaign; never an account or profile identifier
 * @param baseRevision canonical state revision the context was assembled from
 * @param outputLocale selected narrative locale every human-facing output string must use
 * @param localeRevision campaign locale revision at assembly
 * @param glossaryRevision campaign glossary revision at assembly
 * @param packHash pinned content hash, 64 lowercase hexadecimal characters
 * @param policyHash permission/audience policy hash, 64 lowercase hexadecimal characters
 */
public record TaskBinding(UUID taskId, UUID adventureId, UUID campaignId, long baseRevision,
        SupportedLocale outputLocale, long localeRevision, long glossaryRevision, String packHash, String policyHash) {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    /**
     * Reject incomplete bindings before any instruction is rendered.
     *
     * @throws NullPointerException for a missing component
     * @throws IllegalArgumentException for a negative counter or a malformed hash
     */
    public TaskBinding {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(adventureId, "adventureId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(outputLocale, "outputLocale");
        Objects.requireNonNull(packHash, "packHash");
        Objects.requireNonNull(policyHash, "policyHash");
        if (baseRevision < 0 || localeRevision < 0 || glossaryRevision < 0) {
            throw new IllegalArgumentException("binding counters must not be negative");
        }
        if (!HASH.matcher(packHash).matches() || !HASH.matcher(policyHash).matches()) {
            throw new IllegalArgumentException("binding hashes must be lowercase SHA-256 hex");
        }
    }

    /**
     * Decide whether output generated under this binding may still be presented.
     *
     * @param locale campaign narrative locale now
     * @param currentLocaleRevision campaign locale revision now
     * @param currentGlossaryRevision campaign glossary revision now
     * @return true only when the locale and both generations are unchanged since assembly
     */
    public boolean presentationCurrent(SupportedLocale locale, long currentLocaleRevision, long currentGlossaryRevision) {
        return outputLocale == locale && localeRevision == currentLocaleRevision
                && glossaryRevision == currentGlossaryRevision;
    }
}
