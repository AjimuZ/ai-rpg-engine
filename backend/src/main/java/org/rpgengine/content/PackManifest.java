package org.rpgengine.content;

import java.util.List;
import java.util.Map;

/** Structurally validated contract-1 manifest; references and mechanics still require semantic compilation. */
public final class PackManifest {
    private final Map<String, Object> values;
    private final List<FileReference> files;

    /** Validate parsed input against the bundled schema; malformed manifests throw source-located PackException. */
    @SuppressWarnings("unchecked")
    PackManifest(PackDocument document) {
        PackSchemas.manifest(document);
        values = (Map<String, Object>) document.value();
        files = ((List<Map<String, Object>>) values.get("files")).stream()
                .map(file -> new FileReference((String) file.get("path"), (String) file.get("category"))).toList();
    }

    /** Return the declared stable pack ID; semantic namespace resolution is a later compilation step. */
    public String id() {
        return (String) values.get("id");
    }

    /** Return the deeply immutable JSON-shaped manifest, retaining declared array order and exact numeric values. */
    public Map<String, Object> values() {
        return values;
    }

    /** Return explicit file references in author order; callers cannot modify the list. */
    public List<FileReference> files() {
        return files;
    }

    /** A manifest-relative path and an explicit category from the contract schema, never an executable include. */
    public record FileReference(String path, String category) {
        /** Require non-null values; schema validation establishes the permitted syntax and category vocabulary. */
        public FileReference {
            java.util.Objects.requireNonNull(path, "path");
            java.util.Objects.requireNonNull(category, "category");
        }
    }
}
