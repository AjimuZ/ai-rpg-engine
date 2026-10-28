package org.rpgengine.inference.session;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Disclosure scope of one AI session: whose knowledge the session's context may contain (ADR 0007,
 * docs/06 section 7). The audience is part of a {@link SessionScope}, so sessions of different
 * audiences never share a runtime conversation, even within one campaign and role.
 *
 * <p>Three kinds exist:
 * <ul>
 *   <li>{@link Kind#PUBLIC}: what the player may see. Public narration, intent interpretation and
 *       quick replies run here; their context is the campaign's public projection only.</li>
 *   <li>{@link Kind#CHARACTER}: the observations and beliefs of one in-world character, for
 *       example an NPC choosing a tactic or a guide persona with its own senses. The subject is
 *       that character's stable reference, so two characters never share a session.</li>
 *   <li>{@link Kind#PRIVILEGED}: may contain hidden canon, undiscovered facts and planner text.
 *       It corresponds to the preparation audience {@code PLANNER_PRIVATE} of planning snapshots
 *       (docs/28 section 7). No player-facing role may run in it ({@link AiRole}).</li>
 * </ul>
 *
 * @param kind disclosure kind
 * @param subject stable character reference for {@link Kind#CHARACTER}; null for the other kinds
 */
public record Audience(Kind kind, String subject) {
    /** Stable reference syntax shared with contract contexts (letters, digits, {@code ._:-}). */
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** The player audience of a campaign. */
    public static final Audience PUBLIC = new Audience(Kind.PUBLIC, null);
    /** The privileged planning audience of a campaign. */
    public static final Audience PRIVILEGED = new Audience(Kind.PRIVILEGED, null);

    /** Disclosure kinds, ordered from least to most knowledge. */
    public enum Kind {
        /** Player-visible knowledge only. */
        PUBLIC,
        /** One character's own observations and beliefs. */
        CHARACTER,
        /** Hidden canon and planning material; never player-facing. */
        PRIVILEGED
    }

    /**
     * Reject a subject on a campaign-wide audience and a missing or malformed subject on a
     * character audience.
     *
     * @throws NullPointerException for a missing kind
     * @throws IllegalArgumentException when the subject does not match the kind
     */
    public Audience {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.CHARACTER) {
            if (subject == null || !SUBJECT.matcher(subject).matches()) {
                throw new IllegalArgumentException("a character audience needs a stable subject reference");
            }
        } else if (subject != null) {
            throw new IllegalArgumentException("only a character audience has a subject");
        }
    }

    /**
     * @param subject stable reference of the character whose knowledge the session holds
     * @return the audience of that character
     * @throws IllegalArgumentException for a malformed reference
     */
    public static Audience character(String subject) {
        return new Audience(Kind.CHARACTER, subject);
    }

    /**
     * @return the envelope value: {@code public}, {@code privileged} or
     *         {@code character:<subject>}
     */
    public String wire() {
        return switch (kind) {
            case PUBLIC -> "public";
            case PRIVILEGED -> "privileged";
            case CHARACTER -> "character:" + subject;
        };
    }
}
