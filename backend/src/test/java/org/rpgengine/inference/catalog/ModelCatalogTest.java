package org.rpgengine.inference.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.catalog.CatalogEntry.Kind;
import org.rpgengine.inference.catalog.CatalogEntry.LicenseClass;
import org.rpgengine.inference.catalog.CatalogEntry.ModelFile;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bundled catalog is valid and carries the pinned facts recorded in docs/34, and the parser
 * rejects every documented rule violation with its rule code and JSON Pointer.
 */
class ModelCatalogTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static byte[] bundledBytes() throws IOException {
        try (InputStream in = ModelCatalog.class.getClassLoader().getResourceAsStream(ModelCatalog.RESOURCE)) {
            return in.readAllBytes();
        }
    }

    /** Parse the bundled catalog after applying {@code change} to its JSON tree. */
    static ModelCatalog mutated(Consumer<ObjectNode> change) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(bundledBytes());
        change.accept(root);
        return ModelCatalog.parse(JSON.writeValueAsBytes(root));
    }

    static ObjectNode entry(ObjectNode root, int index) {
        return (ObjectNode) root.get("entries").get(index);
    }

    static ObjectNode file(ObjectNode root, int entry, int file) {
        return (ObjectNode) entry(root, entry).get("files").get(file);
    }

    static void rejects(Consumer<ObjectNode> change, String code, String pointer) {
        assertThatThrownBy(() -> mutated(change)).isInstanceOfSatisfying(CatalogException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.pointer()).isEqualTo(pointer);
        });
    }

    @Test
    void bundledCatalogListsTheReferenceHostEntriesInPreferenceOrder() {
        ModelCatalog catalog = ModelCatalog.bundled();
        assertThat(catalog.schema()).isEqualTo(ModelCatalog.SCHEMA);
        assertThat(catalog.reviewed()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(catalog.entries()).extracting(CatalogEntry::id).containsExactly(
                "gemma-4-26b-a4b-it-qat-q4_0", "gemma-4-12b-it-qat-q4_0", "qwen3.5-9b-q4_k_m",
                "gemma-4-e4b-it-qat-q4_0", "gemma-4-e2b-it-qat-q4_0",
                "wai-illustrious-sdxl-v17", "sdxl-base-1.0", "pony-diffusion-v6-xl");
        assertThat(catalog.entries()).filteredOn(e -> e.kind() == Kind.TEXT)
                .allSatisfy(e -> {
                    assertThat(e.format()).isEqualTo("gguf");
                    assertThat(e.slots()).containsExactly("text.light", "text.standard", "text.deliberate");
                    assertThat(e.license().licenseClass()).isEqualTo(LicenseClass.PERMISSIVE);
                    assertThat(e.textRequirements().basis()).isEqualTo("estimate");
                });
        assertThat(catalog.entries()).filteredOn(e -> e.kind() == Kind.IMAGE)
                .allSatisfy(e -> {
                    assertThat(e.format()).isEqualTo("safetensors");
                    assertThat(e.slots()).containsExactly("image");
                    assertThat(e.license().confirmationRequired()).isTrue();
                });
        assertThat(catalog.entries()).allSatisfy(e -> {
            assertThat(e.measured()).as("no profile is measured before P21.2").isNull();
            assertThat(e.verified()).as("every digest was recomputed from downloaded bytes").isTrue();
            assertThat(e.languages()).containsKey("en");
            for (ModelFile file : e.files()) {
                assertThat(file.url().toString()).contains(e.source().host().equals("huggingface")
                        ? "/resolve/" + e.source().revision() + "/" + file.name()
                        : "/api/download/models/" + e.source().revision());
            }
        });
    }

    @Test
    void bundledCatalogCarriesThePinnedDigestsOfDocs34() {
        ModelCatalog catalog = ModelCatalog.bundled();
        assertWeights(catalog, "gemma-4-12b-it-qat-q4_0", 6_975_879_296L,
                "93567e57a8fe10b23569b9d9ec38cd005deedf71e29477c421a4b83f418a538b");
        assertWeights(catalog, "gemma-4-e2b-it-qat-q4_0", 3_349_516_256L,
                "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634");
        assertWeights(catalog, "gemma-4-e4b-it-qat-q4_0", 5_154_941_280L,
                "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee");
        assertWeights(catalog, "wai-illustrious-sdxl-v17", 6_938_040_682L,
                "f116b0c78ff441467b0cdc8f1936e1ed18ea31e9997c7b132b1b8db533f0bd04");
        assertWeights(catalog, "pony-diffusion-v6-xl", 6_938_041_050L,
                "67ab2fd8ec439a89b3fedb15cc65f54336af163c7eb5e4f2acc98f090a29b0b3");
        assertWeights(catalog, "sdxl-base-1.0", 6_938_078_334L,
                "31e35c80fc4829d14f90153f4c74cd59c90b779f6afe05a74cd6120b893f7e5b");
        CatalogEntry pony = catalog.entry("pony-diffusion-v6-xl").orElseThrow();
        assertThat(pony.files()).extracting(ModelFile::role).containsExactly("weights", "vae");
        assertThat(pony.license().licenseClass()).isEqualTo(LicenseClass.RESTRICTED);
        assertThat(pony.contentNotes()).contains("monetized-use-forbidden", "nsfw-capable");
        CatalogEntry wai = catalog.entry("wai-illustrious-sdxl-v17").orElseThrow();
        assertThat(wai.license().id()).isEqualTo("faipl-1.0-sd");
        assertThat(wai.license().basis()).isEqualTo("base-model");
        assertThat(wai.contentNotes()).contains("nsfw-capable");
    }

    static void assertWeights(ModelCatalog catalog, String id, long bytes, String sha256) {
        ModelFile weights = catalog.entry(id).orElseThrow().files().get(0);
        assertThat(weights.role()).isEqualTo("weights");
        assertThat(weights.bytes()).isEqualTo(bytes);
        assertThat(weights.sha256()).isEqualTo(sha256);
    }

    @Test
    void rejectsStructuralViolations() throws IOException {
        rejects(r -> r.put("extra", 1), "UNKNOWN_FIELD", "/extra");
        rejects(r -> r.put("schema", "rpg-engine.model-catalog/2"), "SCHEMA", "/schema");
        rejects(r -> r.remove("reviewed"), "MISSING_FIELD", "/reviewed");
        rejects(r -> r.put("revision", 0), "RANGE", "/revision");
        rejects(r -> r.put("revision", "1"), "TYPE", "/revision");
        rejects(r -> entry(r, 1).put("id", "Gemma"), "ID", "/entries/1/id");
        rejects(r -> entry(r, 2).put("id", entry(r, 1).get("id").stringValue()), "DUPLICATE_ID", "/entries/2/id");
        rejects(r -> ((ArrayNode) r.get("entries")).removeAll(), "RANGE", "/entries");
        String text = new String(bundledBytes(), StandardCharsets.UTF_8).replaceFirst("\"revision\": 1,",
                "\"revision\": 1, \"revision\": 2,");
        assertThatThrownBy(() -> ModelCatalog.parse(text.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(CatalogException.class, e -> assertThat(e.code()).isEqualTo("SYNTAX"));
    }

    @Test
    void rejectsUnsafeOrUnverifiableFiles() {
        rejects(r -> file(r, 1, 0).put("sha256", file(r, 1, 0).get("sha256").stringValue().toUpperCase()),
                "SHA256", "/entries/1/files/0/sha256");
        rejects(r -> file(r, 1, 0).put("url", "https://example.org/gemma.gguf"), "URL", "/entries/1/files/0/url");
        rejects(r -> file(r, 1, 0).put("url", file(r, 1, 0).get("url").stringValue().replace("https:", "http:")),
                "URL", "/entries/1/files/0/url");
        rejects(r -> file(r, 1, 0).put("url", "https://user@huggingface.co/x.gguf"), "URL", "/entries/1/files/0/url");
        rejects(r -> file(r, 1, 0).put("name", "../gemma.gguf"), "FILE_NAME", "/entries/1/files/0/name");
        rejects(r -> file(r, 5, 0).put("name", "wai.ckpt"), "FILE_NAME", "/entries/5/files/0/name");
        rejects(r -> file(r, 1, 0).put("bytes", 0), "RANGE", "/entries/1/files/0/bytes");
        rejects(r -> file(r, 7, 1).put("role", "weights"), "WEIGHTS", "/entries/7/files");
        rejects(r -> file(r, 7, 1).put("name", file(r, 7, 0).get("name").stringValue()), "DUPLICATE",
                "/entries/7/files/1/name");
        rejects(r -> ((ObjectNode) file(r, 1, 0).get("verification")).put("method", "trust"), "VALUE",
                "/entries/1/files/0/verification/method");
        rejects(r -> ((ObjectNode) entry(r, 1).get("source")).put("revision", "main"), "REVISION",
                "/entries/1/source/revision");
        rejects(r -> ((ObjectNode) entry(r, 5).get("source")).put("repository", "wai"), "REPOSITORY",
                "/entries/5/source/repository");
    }

    @Test
    void rejectsKindMismatchesAndUnconfirmedLicenses() {
        rejects(r -> entry(r, 1).put("format", "safetensors"), "VALUE", "/entries/1/format");
        rejects(r -> ((ArrayNode) entry(r, 5).get("capabilities")).set(0, "thinking"), "VALUE",
                "/entries/5/capabilities/0");
        rejects(r -> ((ArrayNode) entry(r, 1).get("slots")).set(0, "image"), "VALUE", "/entries/1/slots/0");
        rejects(r -> ((ArrayNode) entry(r, 1).get("slots")).set(1, "text.light"), "DUPLICATE", "/entries/1/slots/1");
        rejects(r -> ((ArrayNode) entry(r, 1).get("slots")).removeAll(), "RANGE", "/entries/1/slots");
        rejects(r -> ((ObjectNode) entry(r, 5).get("license")).put("confirmationRequired", false),
                "LICENSE_CONFIRMATION", "/entries/5/license/confirmationRequired");
        rejects(r -> ((ObjectNode) entry(r, 1).get("languages")).remove("en"), "LANGUAGE", "/entries/1/languages");
        rejects(r -> ((ObjectNode) entry(r, 1).get("languages")).put("ita", "named"), "LANGUAGE",
                "/entries/1/languages/ita");
        rejects(r -> ((ObjectNode) entry(r, 1).get("requirements")).put("contextDefault", 300000), "RANGE",
                "/entries/1/requirements/contextDefault");
        rejects(r -> ((ObjectNode) entry(r, 5).get("requirements")).put("resolution", "huge"), "RESOLUTION",
                "/entries/5/requirements/resolution");
        rejects(r -> entry(r, 5).set("requirements", entry(r, 1).get("requirements").deepCopy()), "UNKNOWN_FIELD",
                "/entries/5/requirements/kvBytesPerToken");
        rejects(r -> ((ArrayNode) entry(r, 1).get("contentNotes")).add("unsafe"), "VALUE", "/entries/1/contentNotes/0");
    }

    @Test
    void acceptsAMeasuredProfileWithTheRightShape() throws IOException {
        ModelCatalog catalog = mutated(r -> entry(r, 1).set("measured", JSON.createObjectNode()
                .put("date", "2026-11-02").put("host", "reference-host").put("peakVramBytes", 9_000_000_000L)
                .put("context", 32768)));
        CatalogEntry.Measured measured = catalog.entries().get(1).measured();
        assertThat(measured.context()).isEqualTo(32768);
        assertThat(measured.host()).isEqualTo("reference-host");
        rejects(r -> entry(r, 5).set("measured", JSON.createObjectNode().put("date", "2026-11-02")
                .put("host", "reference-host").put("peakVramBytes", 1).put("context", 4096)), "MEASURED",
                "/entries/5/measured/context");
        rejects(r -> entry(r, 1).set("measured", JSON.createObjectNode().put("date", "2026-11-02")
                .put("host", "reference-host").put("peakVramBytes", 1).putNull("context")), "MEASURED",
                "/entries/1/measured/context");
    }

    @Test
    void parsedCatalogIsImmutable() {
        ModelCatalog catalog = ModelCatalog.bundled();
        List<CatalogEntry> entries = catalog.entries();
        assertThatThrownBy(() -> entries.add(entries.get(0))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> entries.get(0).files().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
