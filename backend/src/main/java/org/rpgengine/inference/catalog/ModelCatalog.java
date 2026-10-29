package org.rpgengine.inference.catalog;

import static org.rpgengine.inference.catalog.JsonFields.array;
import static org.rpgengine.inference.catalog.JsonFields.bool;
import static org.rpgengine.inference.catalog.JsonFields.choice;
import static org.rpgengine.inference.catalog.JsonFields.choices;
import static org.rpgengine.inference.catalog.JsonFields.date;
import static org.rpgengine.inference.catalog.JsonFields.integer;
import static org.rpgengine.inference.catalog.JsonFields.matching;
import static org.rpgengine.inference.catalog.JsonFields.names;
import static org.rpgengine.inference.catalog.JsonFields.object;
import static org.rpgengine.inference.catalog.JsonFields.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.rpgengine.inference.catalog.CatalogEntry.ImageRequirements;
import org.rpgengine.inference.catalog.CatalogEntry.Kind;
import org.rpgengine.inference.catalog.CatalogEntry.LanguageSupport;
import org.rpgengine.inference.catalog.CatalogEntry.License;
import org.rpgengine.inference.catalog.CatalogEntry.LicenseClass;
import org.rpgengine.inference.catalog.CatalogEntry.Measured;
import org.rpgengine.inference.catalog.CatalogEntry.ModelFile;
import org.rpgengine.inference.catalog.CatalogEntry.Requirements;
import org.rpgengine.inference.catalog.CatalogEntry.Source;
import org.rpgengine.inference.catalog.CatalogEntry.TextRequirements;
import org.rpgengine.inference.catalog.CatalogEntry.Verification;
import tools.jackson.databind.JsonNode;

/**
 * The versioned model catalog (schema {@code rpg-engine.model-catalog/1}, ADR 0021 decision 3):
 * pinned, hash-carrying, licensed entries from which the advisor recommends defaults. The bundled
 * copy is {@code inference/model-catalog.json} on the class path; the format is documented in
 * docs/34 section 5.
 *
 * <p>Entries are listed in the catalog's preference order, which the advisor uses as its final
 * tie-break. Parsing is strict (see {@link JsonFields}) and also enforces cross-field rules: unique
 * identifiers; formats, capabilities, requirement shapes and slots that match the kind; download
 * URLs on the source's own HTTPS host; exactly one weights file; and a license confirmation for
 * every non-permissive class.
 *
 * @param schema always {@link #SCHEMA}
 * @param revision catalog revision, raised with every content change
 * @param reviewed date the source facts were last checked
 * @param entries at least one entry, in preference order
 */
public record ModelCatalog(String schema, int revision, LocalDate reviewed, List<CatalogEntry> entries) {
    /** The only supported catalog format. */
    public static final String SCHEMA = "rpg-engine.model-catalog/1";
    /** Class-path location of the bundled catalog. */
    public static final String RESOURCE = "inference/model-catalog.json";

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,11}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}");
    private static final Pattern RESOLUTION = Pattern.compile("[1-9][0-9]{2,4}x[1-9][0-9]{2,4}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");
    private static final Map<String, String> SOURCE_HOSTS = Map.of("huggingface", "huggingface.co", "civitai", "civitai.com");
    private static final Set<String> FILE_ROLES = Set.of("weights", "vae", "projector");
    private static final Set<String> VERIFICATIONS = Set.of("download", "source-api");
    private static final Set<String> LICENSE_BASES = Set.of("model-card", "model-page", "base-model");
    private static final Set<String> BASES = Set.of("estimate", "measured");
    private static final Set<String> TEXT_CAPABILITIES = Set.of("system-role", "thinking", "tool-use-format",
            "schema-enforced-output");
    private static final Set<String> IMAGE_CAPABILITIES = Set.of("sdxl", "vae-integrated", "negative-prompt", "lora");
    private static final Set<String> TEXT_SLOTS = Set.of("text.light", "text.standard", "text.deliberate");
    private static final Set<String> IMAGE_SLOTS = Set.of("image");
    private static final Set<String> CONTENT_NOTES = Set.of("nsfw-capable", "sign-in-may-be-required",
            "monetized-use-forbidden", "third-party-quantization");
    /** Smallest context a text entry may propose; below it engine prompts do not fit. */
    static final int MIN_CONTEXT = 4096;

    /** Copy the entry list so a parsed catalog is immutable. */
    public ModelCatalog {
        entries = List.copyOf(entries);
    }

    /** Parse and validate the bundled class-path catalog; a broken bundle is a build defect. */
    public static ModelCatalog bundled() {
        try (InputStream in = ModelCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled model catalog " + RESOURCE + " is missing");
            }
            return parse(in.readNBytes(JsonFields.MAX_BYTES + 1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parse {@code bytes} as a catalog; any violation is a {@link CatalogException}. */
    public static ModelCatalog parse(byte[] bytes) {
        JsonNode root = object(JsonFields.document(bytes), "", names("schema", "revision", "reviewed", "entries"));
        if (!SCHEMA.equals(text(root.get("schema"), "/schema", 64))) {
            throw new CatalogException("SCHEMA", "/schema", "expected " + SCHEMA);
        }
        int revision = (int) integer(root.get("revision"), "/revision", 1, Integer.MAX_VALUE);
        LocalDate reviewed = date(root.get("reviewed"), "/reviewed");
        List<JsonNode> items = array(root.get("entries"), "/entries", 1, 256);
        List<CatalogEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            CatalogEntry entry = entry(items.get(i), "/entries/" + i);
            if (!ids.add(entry.id())) {
                throw new CatalogException("DUPLICATE_ID", "/entries/" + i + "/id", "identifier is used twice");
            }
            entries.add(entry);
        }
        return new ModelCatalog(SCHEMA, revision, reviewed, entries);
    }

    /** The entry with identifier {@code id}, if listed. */
    public Optional<CatalogEntry> entry(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    private static CatalogEntry entry(JsonNode node, String at) {
        object(node, at, names("id", "kind", "displayName", "format", "source", "files", "license", "attribution",
                "requirements", "languages", "capabilities", "slots", "contentNotes", "measured"));
        String id = matching(node.get("id"), at + "/id", ID, "ID");
        Kind kind = Kind.valueOf(choice(node.get("kind"), at + "/kind", Set.of("text", "image")).toUpperCase(Locale.ROOT));
        boolean text = kind == Kind.TEXT;
        String displayName = text(node.get("displayName"), at + "/displayName", 120);
        String format = choice(node.get("format"), at + "/format", Set.of(text ? "gguf" : "safetensors"));
        Source source = source(node.get("source"), at + "/source");
        List<ModelFile> files = files(node.get("files"), at + "/files", format, SOURCE_HOSTS.get(source.host()));
        License license = license(node.get("license"), at + "/license");
        String attribution = text(node.get("attribution"), at + "/attribution", 600);
        Requirements requirements = text
                ? textRequirements(node.get("requirements"), at + "/requirements")
                : imageRequirements(node.get("requirements"), at + "/requirements");
        Map<String, LanguageSupport> languages = languages(node.get("languages"), at + "/languages");
        List<String> capabilities = choices(node.get("capabilities"), at + "/capabilities",
                text ? TEXT_CAPABILITIES : IMAGE_CAPABILITIES, 0);
        List<String> slots = choices(node.get("slots"), at + "/slots", text ? TEXT_SLOTS : IMAGE_SLOTS, 1);
        List<String> contentNotes = choices(node.get("contentNotes"), at + "/contentNotes", CONTENT_NOTES, 0);
        Measured measured = measured(node.get("measured"), at + "/measured", text);
        return new CatalogEntry(id, kind, displayName, format, source, files, license, attribution, requirements,
                languages, capabilities, slots, contentNotes, measured);
    }

    private static Source source(JsonNode node, String at) {
        object(node, at, names("host", "page", "repository", "revision"));
        String host = choice(node.get("host"), at + "/host", SOURCE_HOSTS.keySet());
        URI page = https(node.get("page"), at + "/page", SOURCE_HOSTS.get(host));
        String repository = text(node.get("repository"), at + "/repository", 120);
        String revision = matching(node.get("revision"), at + "/revision",
                host.equals("huggingface") ? COMMIT : NUMBER, "REVISION");
        if (host.equals("civitai") && !NUMBER.matcher(repository).matches()) {
            throw new CatalogException("REPOSITORY", at + "/repository", "a CivitAI model number is required");
        }
        return new Source(host, page, repository, revision);
    }

    private static List<ModelFile> files(JsonNode node, String at, String format, String host) {
        List<JsonNode> items = array(node, at, 1, 8);
        List<ModelFile> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int weights = 0;
        for (int i = 0; i < items.size(); i++) {
            String p = at + "/" + i;
            JsonNode file = object(items.get(i), p, names("role", "name", "url", "bytes", "sha256", "verification"));
            String role = choice(file.get("role"), p + "/role", FILE_ROLES);
            String name = matching(file.get("name"), p + "/name", FILE_NAME, "FILE_NAME");
            if (!name.endsWith("." + format)) {
                throw new CatalogException("FILE_NAME", p + "/name", "file name must end with ." + format);
            }
            if (!names.add(name)) {
                throw new CatalogException("DUPLICATE", p + "/name", "file is listed twice");
            }
            URI url = https(file.get("url"), p + "/url", host);
            long bytes = integer(file.get("bytes"), p + "/bytes", 1, Long.MAX_VALUE);
            String sha256 = matching(file.get("sha256"), p + "/sha256", SHA256, "SHA256");
            JsonNode verification = object(file.get("verification"), p + "/verification", names("method", "date"));
            Verification checked = new Verification(
                    choice(verification.get("method"), p + "/verification/method", VERIFICATIONS),
                    date(verification.get("date"), p + "/verification/date"));
            weights += role.equals("weights") ? 1 : 0;
            files.add(new ModelFile(role, name, url, bytes, sha256, checked));
        }
        if (weights != 1) {
            throw new CatalogException("WEIGHTS", at, "exactly one file must have role weights");
        }
        return files;
    }

    private static License license(JsonNode node, String at) {
        object(node, at, names("id", "class", "url", "basis", "confirmationRequired"));
        String id = text(node.get("id"), at + "/id", 64);
        LicenseClass licenseClass = LicenseClass.valueOf(choice(node.get("class"), at + "/class",
                Set.of("permissive", "use-restricted", "restricted", "unknown")).replace('-', '_').toUpperCase(Locale.ROOT));
        URI url = https(node.get("url"), at + "/url", null);
        String basis = choice(node.get("basis"), at + "/basis", LICENSE_BASES);
        boolean confirmation = bool(node.get("confirmationRequired"), at + "/confirmationRequired");
        // docs/34 section 11: non-permissive or custom licenses need the owner's confirmation.
        if (licenseClass != LicenseClass.PERMISSIVE && !confirmation) {
            throw new CatalogException("LICENSE_CONFIRMATION", at + "/confirmationRequired",
                    "every non-permissive license requires confirmation");
        }
        return new License(id, licenseClass, url, basis, confirmation);
    }

    private static TextRequirements textRequirements(JsonNode node, String at) {
        object(node, at, names("kvBytesPerToken", "kvFixedBytes", "computeBytes", "contextMax", "contextDefault", "basis"));
        long perToken = integer(node.get("kvBytesPerToken"), at + "/kvBytesPerToken", 1, 1L << 30);
        long fixed = integer(node.get("kvFixedBytes"), at + "/kvFixedBytes", 0, 1L << 40);
        long compute = integer(node.get("computeBytes"), at + "/computeBytes", 1, 1L << 40);
        int max = (int) integer(node.get("contextMax"), at + "/contextMax", MIN_CONTEXT, 1L << 24);
        int preferred = (int) integer(node.get("contextDefault"), at + "/contextDefault", MIN_CONTEXT, max);
        return new TextRequirements(perToken, fixed, compute, max, preferred,
                choice(node.get("basis"), at + "/basis", BASES));
    }

    private static ImageRequirements imageRequirements(JsonNode node, String at) {
        object(node, at, names("activationBytes", "resolution", "basis"));
        return new ImageRequirements(integer(node.get("activationBytes"), at + "/activationBytes", 1, 1L << 40),
                matching(node.get("resolution"), at + "/resolution", RESOLUTION, "RESOLUTION"),
                choice(node.get("basis"), at + "/basis", BASES));
    }

    private static Map<String, LanguageSupport> languages(JsonNode node, String at) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new CatalogException("TYPE", at, "expected a non-empty object");
        }
        Map<String, LanguageSupport> languages = new LinkedHashMap<>();
        for (String code : node.propertyNames()) {
            if (!LANGUAGE.matcher(code).matches()) {
                throw new CatalogException("LANGUAGE", at + "/" + code, "expected an ISO 639-1 code");
            }
            languages.put(code, LanguageSupport.valueOf(choice(node.get(code), at + "/" + code,
                    Set.of("unnamed", "named", "evaluated")).toUpperCase(Locale.ROOT)));
        }
        if (!languages.containsKey("en")) {
            throw new CatalogException("LANGUAGE", at, "English (en) must be declared");
        }
        return languages;
    }

    private static Measured measured(JsonNode node, String at, boolean text) {
        if (node != null && node.isNull()) {
            return null;
        }
        object(node, at, names("date", "host", "peakVramBytes", "context"));
        Long context = JsonFields.optionalLong(node.get("context"), at + "/context", MIN_CONTEXT, 1L << 24);
        if (text != (context != null)) {
            throw new CatalogException("MEASURED", at + "/context", "a context is required for text and forbidden for image");
        }
        return new Measured(date(node.get("date"), at + "/date"), matching(node.get("host"), at + "/host", ID, "ID"),
                integer(node.get("peakVramBytes"), at + "/peakVramBytes", 1, 1L << 40),
                context == null ? null : context.intValue());
    }

    /**
     * An absolute HTTPS URI without user information or an explicit port. When {@code host} is set
     * the URI must be on exactly that host, which keeps downloads on the source's own domain.
     */
    private static URI https(JsonNode node, String at, String host) {
        String value = text(node, at, 512);
        URI uri;
        try {
            uri = new URI(value);
        } catch (java.net.URISyntaxException e) {
            throw new CatalogException("URL", at, "not a valid URI");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || (host != null && !host.equals(uri.getHost()))) {
            throw new CatalogException("URL", at, host == null ? "expected an HTTPS URL" : "expected an HTTPS URL on " + host);
        }
        return uri;
    }
}
