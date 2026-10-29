package org.rpgengine.inference.catalog;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One pinned model in the catalog: where its files come from, their exact sizes and SHA-256
 * digests, its license and attribution, the memory it needs, what it can do and which slots it is
 * intended for. Instances are only built by {@link ModelCatalog#parse}, which enforces every rule
 * documented on the components, so code holding an entry may rely on them.
 *
 * @param id stable lower-case identifier, unique in the catalog
 * @param kind text or image
 * @param displayName human-readable name shown to the owner
 * @param format {@code gguf} for text and {@code safetensors} for image entries; no executable
 *     format (pickle checkpoints) is admitted (ADR 0021 decision 9)
 * @param source the publication the files are pinned to
 * @param files at least one file, exactly one with role {@code weights}
 * @param license license identity, class and whether the owner must confirm it before a fetch
 * @param attribution credit text for the credits registry
 * @param requirements memory estimate inputs; {@link TextRequirements} for text and
 *     {@link ImageRequirements} for image entries
 * @param languages declared language coverage keyed by ISO 639-1 code; always contains {@code en}
 * @param capabilities kind-specific capability names
 * @param slots intended slots ({@code text.light}, {@code text.standard}, {@code text.deliberate}
 *     or {@code image})
 * @param contentNotes owner-visible notes such as {@code nsfw-capable}
 * @param measured the measured profile recorded on a real host, or {@code null} until one exists
 */
public record CatalogEntry(String id, Kind kind, String displayName, String format, Source source,
        List<ModelFile> files, License license, String attribution, Requirements requirements,
        Map<String, LanguageSupport> languages, List<String> capabilities, List<String> slots,
        List<String> contentNotes, Measured measured) {

    /** Copy collections so a parsed entry is immutable. */
    public CatalogEntry {
        files = List.copyOf(files);
        languages = Map.copyOf(languages);
        capabilities = List.copyOf(capabilities);
        slots = List.copyOf(slots);
        contentNotes = List.copyOf(contentNotes);
    }

    /** Model kind; it decides the format, requirement shape, capabilities and slots. */
    public enum Kind {
        TEXT, IMAGE
    }

    /** How a declared language is supported, weakest first. */
    public enum LanguageSupport {
        /** The publisher claims broad multilingual coverage without naming the language. */
        UNNAMED,
        /** The publisher names the language (for example in a benchmark or language list). */
        NAMED,
        /** This project's bilingual evaluation (P21.6) passed for the language. */
        EVALUATED
    }

    /**
     * License class used by the advisor and the fetch confirmation. {@code PERMISSIVE} (Apache-2.0,
     * MIT) and {@code USE_RESTRICTED} (OpenRAIL-style open weights with a list of prohibited uses,
     * which includes FAIPL-1.0-SD) rank equally; {@code RESTRICTED} adds conditions such as a ban
     * on monetized use and is never a default; {@code UNKNOWN} is never a default either.
     */
    public enum LicenseClass {
        PERMISSIVE, USE_RESTRICTED, RESTRICTED, UNKNOWN
    }

    /**
     * Publication the files are pinned to.
     *
     * @param host {@code huggingface} or {@code civitai}
     * @param page the human-readable page (HTTPS)
     * @param repository Hugging Face repository or CivitAI model number
     * @param revision Hugging Face commit (40 hex digits) or CivitAI model-version number
     */
    public record Source(String host, URI page, String repository, String revision) {
    }

    /**
     * One file to fetch and verify.
     *
     * @param role {@code weights}, {@code vae} or {@code projector}
     * @param name plain file name with the entry's format extension, no directory part
     * @param url HTTPS download location on the source host, pinned to {@code revision}
     * @param bytes exact size
     * @param sha256 lower-case hexadecimal SHA-256 of the exact bytes
     * @param verification how {@code sha256} was confirmed
     */
    public record ModelFile(String role, String name, URI url, long bytes, String sha256, Verification verification) {
    }

    /**
     * Evidence for a file digest. {@code download} means the file was downloaded and hashed and
     * matched the publisher's digest on {@code date}; {@code source-api} means only the publisher's
     * own digest was read. Only {@code download} counts as verified for defaults.
     */
    public record Verification(String method, LocalDate date) {
        /** True when the digest was recomputed from the downloaded bytes. */
        public boolean downloaded() {
            return "download".equals(method);
        }
    }

    /**
     * License facts.
     *
     * @param id SPDX-style identifier, for example {@code apache-2.0} or {@code faipl-1.0-sd}
     * @param licenseClass advisor class
     * @param url HTTPS location of the license text
     * @param basis where the license was read: {@code model-card}, {@code model-page} or
     *     {@code base-model} (inherited from the model the entry derives from)
     * @param confirmationRequired whether the owner must confirm the license before a fetch;
     *     always true for any class other than {@code PERMISSIVE}
     */
    public record License(String id, LicenseClass licenseClass, URI url, String basis, boolean confirmationRequired) {
    }

    /** Memory-estimate inputs, by kind. {@code basis} is {@code estimate} or {@code measured}. */
    public sealed interface Requirements permits TextRequirements, ImageRequirements {
        /** {@code estimate} until a measured profile replaces the numbers. */
        String basis();
    }

    /**
     * Inputs of the text fit rule: weights (the sum of the file sizes) plus {@code kvFixedBytes}
     * plus {@code kvBytesPerToken} times the context plus {@code computeBytes}.
     *
     * @param kvBytesPerToken KV-cache bytes per context token at f16 for the layers whose cache
     *     grows with the context (global attention layers)
     * @param kvFixedBytes cache that does not grow with the context: sliding-window layers sized to
     *     window plus batch, or a recurrent state
     * @param computeBytes compute buffers of the server at the default batch size
     * @param contextMax trained context limit
     * @param contextDefault context the advisor proposes when it fits
     */
    public record TextRequirements(long kvBytesPerToken, long kvFixedBytes, long computeBytes, int contextMax,
            int contextDefault, String basis) implements Requirements {
    }

    /**
     * Inputs of the image fit rule: all files plus {@code activationBytes} at {@code resolution}.
     *
     * @param activationBytes working memory of one generation at {@code resolution}
     * @param resolution width x height the estimate applies to, for example {@code 1024x1024}
     */
    public record ImageRequirements(long activationBytes, String resolution, String basis) implements Requirements {
    }

    /**
     * A profile measured on a real host (P21.2 and P21.5 record them).
     *
     * @param date measurement date
     * @param host the measured host's name in project documentation, for example {@code reference-host}
     * @param peakVramBytes peak GPU memory during the measurement
     * @param context text context used, or {@code null} for an image model
     */
    public record Measured(LocalDate date, String host, long peakVramBytes, Integer context) {
    }

    /** Total bytes of every file; the model's resident size before caches and buffers. */
    public long fileBytes() {
        return files.stream().mapToLong(ModelFile::bytes).sum();
    }

    /** True when every file's digest was recomputed from downloaded bytes. */
    public boolean verified() {
        return files.stream().allMatch(file -> file.verification().downloaded());
    }

    /** Text requirements; fails for an image entry. */
    public TextRequirements textRequirements() {
        return (TextRequirements) Objects.requireNonNull(requirements);
    }

    /** Image requirements; fails for a text entry. */
    public ImageRequirements imageRequirements() {
        return (ImageRequirements) Objects.requireNonNull(requirements);
    }
}
