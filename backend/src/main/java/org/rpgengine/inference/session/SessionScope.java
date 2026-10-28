package org.rpgengine.inference.session;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Complete isolation key of one logical AI session (ADR 0007, docs/06 section 7): campaign,
 * campaign recovery generation, role, audience and the pinned content and policy hashes. Two
 * tasks share a runtime conversation only when every component is equal; {@link ScopedSessions}
 * keys its sessions by this record's value equality.
 *
 * <p>Why each component is in the key:
 * <ul>
 *   <li>{@code campaignId}: a conversation never carries another campaign's history.</li>
 *   <li>{@code campaignGeneration}: a restore increments it (docs/28 section 10), and sessions
 *       created before the restore are invalid in the restored campaign.</li>
 *   <li>{@code role} and {@code audience}: a privileged planner, an NPC tactician and the public
 *       narrator never see each other's earlier context.</li>
 *   <li>{@code packHash} and {@code policyHash}: a content or permission/audience policy change
 *       renews the session (docs/08 section 7) instead of leaving context assembled under the
 *       previous rules in the conversation.</li>
 * </ul>
 * The adventure is carried for consistency checks only; a campaign belongs to one adventure.
 *
 * @param adventureId owning adventure
 * @param campaignId owning campaign
 * @param campaignGeneration campaign recovery generation, at least 0
 * @param role logical AI role
 * @param audience disclosure scope; must be permitted for {@code role}
 * @param packHash pinned content hash, 64 lowercase hexadecimal characters
 * @param policyHash permission/audience policy hash, 64 lowercase hexadecimal characters
 */
public record SessionScope(UUID adventureId, UUID campaignId, long campaignGeneration, AiRole role, Audience audience,
        String packHash, String policyHash) {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    /**
     * Reject incomplete scopes and every role/audience pair the role matrix forbids, in particular
     * a player-facing role in the privileged audience.
     *
     * @throws NullPointerException for a missing component
     * @throws IllegalArgumentException for a negative generation, a malformed hash or a forbidden
     *         role/audience pair
     */
    public SessionScope {
        Objects.requireNonNull(adventureId, "adventureId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(packHash, "packHash");
        Objects.requireNonNull(policyHash, "policyHash");
        if (campaignGeneration < 0) {
            throw new IllegalArgumentException("campaign generation must not be negative");
        }
        if (!HASH.matcher(packHash).matches() || !HASH.matcher(policyHash).matches()) {
            throw new IllegalArgumentException("scope hashes must be lowercase SHA-256 hex");
        }
        if (!role.permits(audience)) {
            throw new IllegalArgumentException("role " + role.wire() + " may not run in audience " + audience.kind());
        }
    }

    /**
     * @param other another scope
     * @return true when both scopes are the same continuing conversation line (campaign, role and
     *         audience), whatever their generation or hashes
     */
    boolean sameLine(SessionScope other) {
        return campaignId.equals(other.campaignId) && role == other.role && audience.equals(other.audience);
    }
}
