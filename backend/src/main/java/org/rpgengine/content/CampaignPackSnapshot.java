package org.rpgengine.content;

import java.util.Map;
import java.util.Objects;

/**
 * Privileged immutable compiled closure of a campaign's pinned pack revisions. Inputs are
 * registry-verified packs keyed by pack ID; no files or database are read here. Resolution
 * keeps each definition's declaring pack, rejecting undeclared or absent references rather
 * than accidentally using an equal local ID from another pack. Never return this to clients.
 */
public record CampaignPackSnapshot(CompiledPack root, Map<String, CompiledPack> packs) {
    /** Copy the closure and require its root; malformed internal construction fails immediately. */
    public CampaignPackSnapshot {
        Objects.requireNonNull(root, "root");
        packs = Map.copyOf(packs);
        if (packs.get(root.parsed().manifest().id()) != root) {
            throw new IllegalArgumentException("snapshot must contain its root");
        }
    }

    /** Wrap a standalone compiled pack for pure creation validation; external references fail closed. */
    public static CampaignPackSnapshot local(CompiledPack pack) {
        return new CampaignPackSnapshot(pack, Map.of(pack.parsed().manifest().id(), pack));
    }

    /**
     * Resolve a typed reference relative to its declaring pack, returning canonical identity and
     * source. Only direct manifest dependencies are visible; transitive packs cannot be guessed.
     * Missing definitions, undeclared dependencies and wrong kinds fail with an argument error.
     */
    public Resolved resolve(CompiledPack source, String reference, String kind) {
        CompiledPack target = source;
        String id = reference;
        int separator = reference.indexOf('#');
        if (separator >= 0) {
            String packId = reference.substring(0, separator);
            if (dependencies(source).stream().noneMatch(value -> packId.equals(value.get("id")))) {
                throw new IllegalArgumentException("reference names an undeclared dependency");
            }
            target = packs.get(packId);
            id = reference.substring(separator + 1);
        }
        Definition definition = target == null ? null : target.byId().get(id);
        if (definition == null || !definition.kind().equals(kind)) {
            throw new IllegalArgumentException("creation requires a pinned " + kind + " definition: " + reference);
        }
        // Root IDs retain their existing representation; dependencies always carry a namespace.
        String canonical = target == root ? id : target.parsed().manifest().id() + "#" + id;
        return new Resolved(canonical, target, definition);
    }

    /** Read parser-validated pinned dependency objects; absent declarations yield an empty list. */
    @SuppressWarnings("unchecked")
    static java.util.List<Map<String, Object>> dependencies(CompiledPack pack) {
        return (java.util.List<Map<String, Object>>) pack.parsed().manifest().values()
                .getOrDefault("dependencies", java.util.List.of());
    }

    /** Resolved canonical definition ID, declaring pack and immutable data; failures occur at resolution. */
    public record Resolved(String id, CompiledPack source, Definition definition) {
    }
}
