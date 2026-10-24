package org.rpgengine.content;

import java.util.Map;

/**
 * Immutable output of the manifest/parsing stage, not a compiled/admitted content revision.
 * Structured documents and plain Markdown remain untrusted data until later semantic checks.
 */
public record ParsedPack(PackManifest manifest, Map<String, PackDocument> documents,
        Map<String, String> prose, String contentHash, Map<String, String> fileHashes) {
    /** Copy output indexes; all nested data already comes from the immutable parser and hash protocol. */
    public ParsedPack {
        java.util.Objects.requireNonNull(manifest, "manifest");
        documents = Map.copyOf(documents);
        prose = Map.copyOf(prose);
        java.util.Objects.requireNonNull(contentHash, "contentHash");
        fileHashes = Map.copyOf(fileHashes);
    }
}
