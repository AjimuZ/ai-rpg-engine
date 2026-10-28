package org.rpgengine.preparation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.Definition;
import org.rpgengine.content.GenerationPolicy;
import org.rpgengine.persistence.CanonicalValues;

/**
 * Prose-free facts of one campaign's sealed pinned content that the plan-admission gate
 * checks (docs/28 section 7): which definition identities exist, which of them are secret,
 * which carry fixed author constraints, and a reproducible digest of those constraints.
 * Identities follow the rule catalog: root definitions keep their local ID, definitions of
 * pinned dependency packs are namespaced {@code <packId>#<id>}. The value is immutable and
 * holds no secret text, so it can be cached by revision; it is never returned to clients.
 *
 * @param revisionId pinned content revision the facts were compiled from
 * @param definitions every pinned definition identity
 * @param secretDefinitions identities whose authored knowledge visibility is {@code secret}
 * @param constrainedDefinitions identities that the root pack's generation policy marks
 *        required or fixed, the author constraints a plan may cite
 * @param authorConstraintHash {@link #authorConstraintHash(GenerationPolicy)} of the root policy
 */
public record PlanContentCatalog(UUID revisionId, Set<String> definitions, Set<String> secretDefinitions,
        Set<String> constrainedDefinitions, String authorConstraintHash) {
    // Version label inside the constraint digest, so a future encoding change cannot collide.
    private static final String CONSTRAINT_ENCODING = "author-constraints-v1";

    /** Copy the identity sets and require a well-formed digest. */
    public PlanContentCatalog {
        Objects.requireNonNull(revisionId, "revisionId");
        definitions = Set.copyOf(definitions);
        secretDefinitions = Set.copyOf(secretDefinitions);
        constrainedDefinitions = Set.copyOf(constrainedDefinitions);
        CanonicalValues.requireSha256Hex(authorConstraintHash, "authorConstraintHash");
    }

    /**
     * Compile the catalog from a registry-verified closure. The root pack's generation policy
     * governs the campaign; dependency packs contribute definitions but not constraints.
     *
     * @param revisionId pinned root revision
     * @param snapshot privileged compiled closure; it is not retained
     * @return detached identity facts
     */
    static PlanContentCatalog from(UUID revisionId, CampaignPackSnapshot snapshot) {
        var definitions = new HashSet<String>();
        var secrets = new HashSet<String>();
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            for (Definition definition : pack.definitions()) {
                String id = pack == snapshot.root() ? definition.id() : packId + "#" + definition.id();
                definitions.add(id);
                // Same rule as the pack compiler and command rules: omitted visibility is public.
                if ("secret".equals(definition.data().get("knowledge"))) {
                    secrets.add(id);
                }
            }
        }
        GenerationPolicy policy = snapshot.root().generationPolicy();
        var constrained = new HashSet<>(policy.required());
        constrained.addAll(policy.fixed().keySet());
        return new PlanContentCatalog(revisionId, definitions, secrets, constrained, authorConstraintHash(policy));
    }

    /**
     * Digest the fixed author constraints of one generation policy: required identities, fixed
     * identities with their protected fields, and excluded tags, each in sorted order and
     * length-prefixed, so equal constraints always produce the same digest and different ones
     * cannot collide by concatenation. Category budgets are generation settings, not author
     * constraints, and are deliberately excluded.
     *
     * @return lowercase SHA-256 hex digest
     */
    public static String authorConstraintHash(GenerationPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        var parts = new ArrayList<Object>();
        parts.add(CONSTRAINT_ENCODING);
        var required = new TreeSet<>(policy.required());
        parts.add(required.size());
        parts.addAll(required);
        var fixed = new TreeMap<String, Set<String>>(policy.fixed());
        parts.add(fixed.size());
        for (Map.Entry<String, Set<String>> entry : fixed.entrySet()) {
            var fields = new TreeSet<>(entry.getValue());
            parts.add(entry.getKey());
            parts.add(fields.size());
            parts.addAll(fields);
        }
        var excluded = new TreeSet<>(policy.excludedTags());
        parts.add(excluded.size());
        parts.addAll(excluded);
        return PreparationSupport.sha256(parts.toArray());
    }
}
