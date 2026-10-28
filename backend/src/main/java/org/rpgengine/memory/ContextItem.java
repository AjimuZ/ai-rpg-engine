package org.rpgengine.memory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

/**
 * One candidate piece of an AI task context (P09.2, docs/08 sections 5 and 6): a stable reference,
 * the section it belongs to, its epistemic standing, whether it is mandatory, how it was retrieved,
 * its rank inside that retrieval tier, its language and its ordered JSON-ready fields.
 *
 * <p>The {@link Standing} is the label a model must see to keep categories apart: an admitted
 * canonical record ({@link Standing#STATE}, {@link Standing#EVENT}, {@link Standing#FACT},
 * {@link Standing#COMMON_KNOWLEDGE}, {@link Standing#OBLIGATION}), what one character holds
 * ({@link Standing#BELIEF}, {@link Standing#LAST_KNOWN}), what was told to the player
 * ({@link Standing#NARRATION}) and what a planner privately assumed ({@link Standing#ASSUMPTION}).
 * The label is rendered into every item, so a belief, a narrated sentence or a planning hypothesis
 * is never presented as an admitted fact.
 *
 * <p>Items are built by trusted backend code from audience-filtered reads; a malformed item is a
 * programming error and throws.
 *
 * @param ref stable unique reference of the record, for example {@code claim:<uuid>} or
 *        {@code event:<sequence>}; also the deduplication key across retrieval tiers
 * @param section budget section the item is charged to
 * @param standing epistemic standing rendered with the item
 * @param mandatory whether the item may never be dropped; a mandatory item that cannot fit makes
 *        the whole context too large instead of being truncated
 * @param tier how the item was retrieved; the best tier wins when the same record is found twice
 * @param score rank inside the tier, higher first; ties break on {@code ref}
 * @param language BCP 47 tag of the item's human text ({@code en} or {@code it}), or null when the
 *        item carries no free text
 * @param fields ordered JSON-ready fields (strings, numbers, booleans, lists or maps of them);
 *        rendered after the fixed {@code ref}, {@code standing} and {@code lang} keys
 * @param terms pinned definition identities the item names; glossary terms are bound for them
 */
public record ContextItem(String ref, Section section, Standing standing, boolean mandatory, Tier tier, long score,
        String language, Map<String, Object> fields, List<String> terms) {
    /** Stable reference syntax shared with contract contexts. */
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Budget sections, in the order the selector fills them and the envelope renders them. The
     * order is the drop order in reverse: when optional room runs out, later sections lose first.
     */
    public enum Section {
        /** Current state of the audience: location, present actors, world time. */
        STATE,
        /** Unresolved commitments and objectives. */
        OBLIGATIONS,
        /** Claims, beliefs and last known locations. */
        KNOWLEDGE,
        /** Committed public events and published narration. */
        HISTORY,
        /** Private planning assumptions; privileged audiences only. */
        PLANNING
    }

    /** Epistemic standing of an item; see the class description. */
    public enum Standing {
        /** Current canonical state read from the database. */
        STATE(true, false),
        /** A committed event, stated deterministically. */
        EVENT(true, false),
        /** A claim with its privileged truth and validity interval. */
        FACT(true, true),
        /** A current common-knowledge claim, without its truth. */
        COMMON_KNOWLEDGE(true, false),
        /** An open commitment between actors with its exact terms. */
        OBLIGATION(true, false),
        /** One character's stance on a claim, which may be wrong; never its truth. */
        BELIEF(false, false),
        /** One character's last known location of another actor; never the real location. */
        LAST_KNOWN(false, false),
        /** Published narration: what the player was told, not a source of truth. */
        NARRATION(false, false),
        /** A planner's private, unverified assumption; never admitted and never public. */
        ASSUMPTION(false, true);

        private final boolean admitted;
        private final boolean privilegedOnly;

        Standing(boolean admitted, boolean privilegedOnly) {
            this.admitted = admitted;
            this.privilegedOnly = privilegedOnly;
        }

        /** @return whether the item is an admitted canonical record rather than a perspective or text */
        public boolean admitted() {
            return admitted;
        }

        /** @return whether only a privileged audience may receive items of this standing */
        public boolean privilegedOnly() {
            return privilegedOnly;
        }

        /** @return the rendered label, for example {@code common-knowledge} */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * Retrieval tiers, best first (docs/08 section 5: explicit IDs and causal links first, then
     * full-text matches, then recency and importance).
     */
    public enum Tier {
        /** Mandatory state; always selected. */
        REQUIRED,
        /** The record or one of its subjects, objects or parties is an explicit focus reference. */
        ID,
        /** Written by the focus operation, or told by a focus actor. */
        CAUSAL,
        /** Matched the task's full-text query. */
        TEXT,
        /** Neither; ranked by importance, then recency. */
        RECENT
    }

    /**
     * Freeze and check the item.
     *
     * @throws IllegalArgumentException for a malformed reference or language, a mandatory item
     *         outside {@link Tier#REQUIRED} (or the reverse), or a planning assumption outside
     *         {@link Section#PLANNING}
     */
    public ContextItem {
        if (ref == null || !REFERENCE.matcher(ref).matches()) {
            throw new IllegalArgumentException("context item reference must be a stable reference");
        }
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(tier, "tier");
        if (mandatory != (tier == Tier.REQUIRED)) {
            throw new IllegalArgumentException("exactly the mandatory items use the required tier");
        }
        if ((standing == Standing.ASSUMPTION) != (section == Section.PLANNING)) {
            throw new IllegalArgumentException("planning assumptions and only they belong to the planning section");
        }
        if (language != null && !language.equals("en") && !language.equals("it")) {
            throw new IllegalArgumentException("item language must be en or it");
        }
        fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(fields, "fields")));
        terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
    }

    /**
     * @return the exact ordered map rendered into the context: {@code ref}, {@code standing},
     *         {@code lang} when present, then {@link #fields}
     */
    public Map<String, Object> rendered() {
        var out = new LinkedHashMap<String, Object>();
        out.put("ref", ref);
        out.put("standing", standing.wire());
        if (language != null) {
            out.put("lang", language);
        }
        out.putAll(fields);
        return out;
    }

    /** @return UTF-8 size of the compact JSON rendering; the token estimate is derived from it */
    public int renderedBytes() {
        return JSON.writeValueAsString(rendered()).getBytes(StandardCharsets.UTF_8).length;
    }
}
