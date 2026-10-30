package org.rpgengine.commands;

import java.util.Objects;

/**
 * Expected outcome of command admission, common semantic or rule validation, or operation lookup. The message
 * is fixed per kind and never contains caller input, SQL or row identities, so adapters
 * can map {@link #kind()} to a stable public code. Every failure escapes the calling
 * admission or canonical transaction: no journal row, lease change or recovery write survives a rejection.
 * Busy/limit outcomes can be retried once capacity is available; stale/conflicting
 * commands require explicit correction rather than automatic submission against new state.
 */
public final class OperationAdmissionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Kind kind;

    /**
     * Create one failure.
     *
     * @param kind stable classification; null fails immediately
     */
    OperationAdmissionFailure(Kind kind) {
        super("operation admission failed: " + Objects.requireNonNull(kind, "kind"), null, false, false);
        this.kind = kind;
    }

    /** @return stable classification used for HTTP status/code mapping */
    public Kind kind() {
        return kind;
    }

    /** Admission and lookup outcomes from docs/07 §3 and docs/13 §3. */
    public enum Kind {
        /** Owner, adventure, campaign or operation is missing or belongs to another scope (HTTP 404). */
        SCOPE_UNAVAILABLE,
        /** The request identity was already used for a different canonical command (HTTP 409). */
        REQUEST_CONFLICT,
        /** {@code expectedRevision} differs from the committed campaign revision (HTTP 409). */
        STALE_REVISION,
        /** Another operation holds the live campaign mutation lease; nothing is queued (HTTP 409). */
        CAMPAIGN_BUSY,
        /** The owner already runs the maximum number of concurrently leased operations (HTTP 429). */
        ADMISSION_LIMIT,
        /** Unknown type, malformed typed payload or attempted generic effect/field patch (HTTP 422). */
        INVALID_COMMAND,
        /** No registered intention is eligible in the current scheduling mode (HTTP 422). */
        MODE_ILLEGAL,
        /** The stored pending decision has no supported input contract (HTTP 422). */
        DECISION_UNAVAILABLE,
        /**
         * Missing creation player, or a recipient/target that is missing, foreign, the speaker
         * itself, not co-present or concealed. One category for all of these avoids revealing
         * whether a hidden actor exists (HTTP 422).
         */
        ACTOR_UNAVAILABLE,
        /**
         * Movement destination is unknown, concealed, the current location, not linked from the
         * current location or not accessible; also used when the player has no current location.
         * One category avoids disclosing secret locations or links (HTTP 422).
         */
        DESTINATION_UNAVAILABLE,
        /** The ability is not a pinned definition or is not learned by the acting player (HTTP 422). */
        ABILITY_UNAVAILABLE,
        /** A required skill rank or prerequisite ability of the selected ability is missing (HTTP 422). */
        PREREQUISITE_UNMET,
        /**
         * Target count violates the ability's target policy, or the policy needs encounter zones; since P11.2 also an
         * encounter participant who cannot be addressed: missing, the player, of a side the declared action does not
         * admit, not standing or beyond speaking range (HTTP 422).
         */
        TARGET_ILLEGAL,
        /** A declared cost exceeds the acting player's current resource above its minimum (HTTP 422). */
        RESOURCE_INSUFFICIENT,
        /**
         * The campaign's pinned content closure cannot be loaded by this engine build, so rules
         * that consult definitions fail closed instead of guessing (HTTP 422).
         */
        CONTENT_UNAVAILABLE,
        /**
         * Mutation of the campaign is paused because its sealed pinned content closure is missing,
         * corrupt or unusable under this engine build (P09.5, docs/08 section 10). Every fresh
         * command is refused until the exact content is restored or imported; identical retries of
         * earlier admissions still replay. The resume card names the issues (HTTP 409).
         */
        DEPENDENCIES_MISSING,
        /**
         * The ability's effect program cannot be executed by the local exploration resolver: it
         * contains an effect type that needs later systems (damage/heal armor, conditions, forced
         * movement, reveals, item creation), reads attribute or resource values from a vocabulary
         * other than the acting player's, or could need more durable draws than one operation may
         * reserve. Nothing is journaled (HTTP 422).
         */
        EFFECT_UNSUPPORTED,
        /**
         * An item instance is not held by the party that must hold it, does not exist, cannot be used
         * (no use contract) or is worn when the action would use it up, give it away or trade it.
         * One category avoids confirming that another actor's instance exists (HTTP 422, P10.2).
         */
        ITEM_UNAVAILABLE,
        /**
         * Wearing the item breaks a pinned equipment rule (not equippable, unknown or incompatible
         * slot, slot full, hands exceeded, already worn), or the item to take off is not worn (HTTP 422, P10.2).
         */
        EQUIPMENT_ILLEGAL,
        /** More units are named than the stack holds (HTTP 422, P10.2). */
        QUANTITY_ILLEGAL,
        /**
         * The counterparty does not accept the exchange: an item has no declared value, or the value the
         * counterparty receives is lower than the value it gives (HTTP 422, P10.2).
         */
        TRADE_REFUSED,
        /**
         * The named quest does not exist, has not been discovered, is hidden background content, or is
         * not in the status the request needs (accept needs {@code available}, suspend {@code active},
         * resume {@code suspended}). One category avoids confirming that an undiscovered quest exists
         * (HTTP 422, P10.3).
         */
        QUEST_UNAVAILABLE,
        /**
         * The requested time cannot pass: a wait longer than the legal wait, or a duration (waiting, travel
         * or rest) that would carry the bounded world clock past its end (HTTP 422, P10.4).
         */
        TIME_ILLEGAL,
        /**
         * The named rest policy does not exist, cannot be taken at the player's location (a required location
         * tag is missing), or belongs to a vocabulary the player's resources do not use (HTTP 422, P10.4).
         */
        REST_UNAVAILABLE,
        /**
         * The named encounter does not exist, cannot be entered at the player's location or with the player's
         * vocabulary, the player is defeated or already in an encounter, or the declared opening strike is not
         * allowed or names no hostile participant (HTTP 422, P11.1).
         */
        ENCOUNTER_UNAVAILABLE,
        /**
         * The tactical intention is not legal at this point of the encounter: initiative is already rolled, or it is
         * not the player's own decision boundary (another participant acts, an action is resolving, the round has
         * closed). Nothing is queued for a later turn (HTTP 422, P11.2).
         */
        TURN_UNAVAILABLE,
        /** The action costs more action points than remain in the player's activation (HTTP 422, P11.2). */
        ACTION_POINTS_INSUFFICIENT,
        /**
         * The destination zone is unknown, the current zone, not one unblocked edge away from it or at its capacity
         * (HTTP 422, P11.2).
         */
        ZONE_UNAVAILABLE,
        /** The speech-only utterances of the current activation are used up (HTTP 422, P11.2). */
        SPEECH_LIMIT_REACHED,
        /**
         * The encounter declares no such social or scene action, it cannot be used from the player's zone, or it
         * would change nothing because its passage already has the requested state (HTTP 422, P11.2).
         */
        INTERACTION_UNAVAILABLE
    }
}
