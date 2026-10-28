package org.rpgengine.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.inference.contract.GlossaryTerm;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.memory.ContextItem.Section;
import org.rpgengine.memory.ContextItem.Standing;
import org.rpgengine.presentation.SupportedLocale;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stamped, budgeted context of one AI task (P09.2, docs/08 section 5). The stamp names exactly what
 * the context was assembled from: campaign, recovery generation, state revision, world time, role,
 * audience, holder, focus operation, pinned pack, ruleset and policy hashes, output locale with its
 * locale and glossary revisions, and the query-scope epochs the reads depended on. The digest is the
 * SHA-256 of the exact rendered JSON, so two envelopes with equal digests carry equal input.
 *
 * <p>Invariants checked at construction (a violation is an assembler defect and throws):
 * <ul>
 *   <li>the role permits the audience (a player-facing role never receives a privileged context);</li>
 *   <li>a non-privileged audience receives no {@link Standing#FACT} (truth) and no
 *       {@link Standing#ASSUMPTION} (private planning);</li>
 *   <li>glossary terms are unique, at most {@link #MAX_GLOSSARY}, and absent when the glossary is
 *       unavailable; the assembler resolves them for the stamped output locale, whose glossary
 *       revision the stamp records. Terms beyond the bound are counted in
 *       {@code glossaryOmitted}, never dropped silently.</li>
 * </ul>
 * Rendering is deterministic: sections in {@link Section} order, items in selection order, and a
 * per-standing legend so the model reads every item's epistemic label in the same words.
 *
 * @param stamp provenance of every read
 * @param budget budget the selection was made under
 * @param items selected items per section
 * @param omitted distinct candidates per section that did not fit
 * @param glossary selected-locale terminology for the definitions the items name
 * @param glossaryAvailable false when the pinned pack could not be loaded, so no terminology could
 *        be bound; rendered as {@code glossaryStatus: unavailable} rather than passing silently
 * @param glossaryOmitted terms of the selected items left out by the {@link #MAX_GLOSSARY} bound
 * @param estimatedTokens estimated input tokens of instructions plus this context
 */
public record ContextEnvelope(Stamp stamp, ContextBudget budget, Map<Section, List<ContextItem>> items,
        Map<Section, Integer> omitted, List<GlossaryTerm> glossary, boolean glossaryAvailable, int glossaryOmitted,
        long estimatedTokens) {
    /** Version of the rendered context layout. */
    public static final int CONTEXT_VERSION = 1;
    /** Most glossary terms bound to one context. */
    public static final int MAX_GLOSSARY = 128;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    /** Placeholder count used to size the omission block before the real counts are known. */
    private static final int WORST_COUNT = 999_999;

    /** Fixed English legend of every standing; the model reads the label, never infers it. */
    static final Map<Standing, String> LEGEND = legend();

    /**
     * @throws IllegalArgumentException when a policy invariant above is violated or a map misses a section
     */
    public ContextEnvelope {
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(budget, "budget");
        var copy = new EnumMap<Section, List<ContextItem>>(Section.class);
        var counts = new EnumMap<Section, Integer>(Section.class);
        boolean privileged = stamp.audience().kind() == Audience.Kind.PRIVILEGED;
        for (Section section : Section.values()) {
            var list = List.copyOf(Objects.requireNonNull(items.get(section), "section " + section));
            for (ContextItem item : list) {
                if (item.section() != section) {
                    throw new IllegalArgumentException("item filed under the wrong section");
                }
                if (!privileged && item.standing().privilegedOnly()) {
                    throw new IllegalArgumentException("privileged-only material in a limited-audience context");
                }
            }
            copy.put(section, list);
            int count = Objects.requireNonNull(omitted.get(section), "omitted " + section);
            if (count < 0) {
                throw new IllegalArgumentException("omission counts must not be negative");
            }
            counts.put(section, count);
        }
        items = Collections.unmodifiableMap(copy);
        omitted = Collections.unmodifiableMap(counts);
        glossary = List.copyOf(glossary);
        if (glossary.size() > MAX_GLOSSARY || glossary.stream().map(GlossaryTerm::termId).distinct().count() != glossary.size()) {
            throw new IllegalArgumentException("glossary must hold at most " + MAX_GLOSSARY + " distinct terms");
        }
        if (!glossaryAvailable && (!glossary.isEmpty() || glossaryOmitted != 0)) {
            throw new IllegalArgumentException("an unavailable glossary has no terms");
        }
        if (glossaryOmitted < 0 || (glossaryOmitted > 0 && glossary.size() < MAX_GLOSSARY)) {
            throw new IllegalArgumentException("glossary terms are omitted only beyond the bound");
        }
        if (estimatedTokens < 0 || estimatedTokens > budget.inputTokens()) {
            throw new IllegalArgumentException("estimated tokens must fit the input budget");
        }
    }

    /** @return the exact ordered JSON-ready tree whose compact JSON is digested and rendered */
    public Map<String, Object> render() {
        return render(stamp, budget, items, omitted, glossary, glossaryAvailable, glossaryOmitted,
                presentStandings(items));
    }

    /** @return the compact JSON of {@link #render()} */
    public String json() {
        return JSON.writeValueAsString(render());
    }

    /** @return lowercase SHA-256 hex of {@link #json()} */
    public String digest() {
        return sha256(json());
    }

    /** @return every selected item in render order */
    public List<ContextItem> all() {
        return items.values().stream().flatMap(List::stream).toList();
    }

    /** @return whether any optional candidate or glossary term was left out */
    public boolean partial() {
        return glossaryOmitted > 0 || omitted.values().stream().anyMatch(count -> count > 0);
    }

    /**
     * Upper bound of the tokens the envelope adds around its items and glossary entries: stamp,
     * budget, legend of every standing, empty section lists, an empty glossary and worst-case
     * omission counts. Each item and each glossary entry is charged separately, including its list
     * separator, so this plus those charges bounds the whole rendering.
     *
     * @return estimated fixed tokens, to be charged before any item
     */
    static int overhead(Stamp stamp, ContextBudget budget, boolean glossaryAvailable) {
        var empty = new EnumMap<Section, List<ContextItem>>(Section.class);
        var worst = new EnumMap<Section, Integer>(Section.class);
        for (Section section : Section.values()) {
            empty.put(section, List.of());
            worst.put(section, WORST_COUNT);
        }
        return TokenEstimator.tokens(JSON.writeValueAsString(render(stamp, budget, empty, worst, List.of(),
                glossaryAvailable, WORST_COUNT, EnumSet.allOf(Standing.class))));
    }

    /** @return estimated tokens of one rendered glossary entry including its list separator */
    static long glossaryCost(GlossaryTerm term) {
        return TokenEstimator.tokens(JSON.writeValueAsString(glossaryEntry(term)).getBytes(StandardCharsets.UTF_8).length
                + 1L);
    }

    /** Shared renderer; see {@link #render()}. */
    private static Map<String, Object> render(Stamp stamp, ContextBudget budget, Map<Section, List<ContextItem>> items,
            Map<Section, Integer> omitted, List<GlossaryTerm> glossary, boolean glossaryAvailable, int glossaryOmitted,
            Set<Standing> standings) {
        var root = new LinkedHashMap<String, Object>();
        root.put("contextVersion", CONTEXT_VERSION);
        root.put("stamp", stamp.render());
        var limits = new LinkedHashMap<String, Object>();
        limits.put("inputTokens", budget.inputTokens());
        limits.put("estimator", TokenEstimator.METHOD);
        root.put("budget", limits);
        var legend = new LinkedHashMap<String, Object>();
        LEGEND.forEach((standing, text) -> {
            if (standings.contains(standing)) {
                legend.put(standing.wire(), text);
            }
        });
        root.put("standings", legend);
        var sections = new LinkedHashMap<String, Object>();
        var omissions = new LinkedHashMap<String, Object>();
        for (Section section : Section.values()) {
            String key = section.name().toLowerCase(java.util.Locale.ROOT);
            sections.put(key, items.get(section).stream().map(ContextItem::rendered).toList());
            omissions.put(key, omitted.get(section));
        }
        root.put("sections", sections);
        root.put("omitted", omissions);
        root.put("glossaryStatus", glossaryAvailable ? "bound" : "unavailable");
        root.put("glossary", glossary.stream().map(ContextEnvelope::glossaryEntry).toList());
        root.put("glossaryOmitted", glossaryOmitted);
        return root;
    }

    /** @return the ordered JSON-ready form of one glossary term */
    private static Map<String, Object> glossaryEntry(GlossaryTerm term) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("termId", term.termId());
        entry.put("kind", term.kind() == GlossaryTerm.Kind.PROPER_NAME ? "properName" : "term");
        entry.put("rendering", term.rendering());
        entry.put("avoid", term.avoidForms());
        return entry;
    }

    /** @return the standings that occur among the items */
    private static Set<Standing> presentStandings(Map<Section, List<ContextItem>> items) {
        var present = EnumSet.noneOf(Standing.class);
        items.values().forEach(list -> list.forEach(item -> present.add(item.standing())));
        return present;
    }

    /** @return the fixed legend in standing order */
    private static Map<Standing, String> legend() {
        var legend = new EnumMap<Standing, String>(Standing.class);
        legend.put(Standing.STATE, "Current canonical state at the stamped revision.");
        legend.put(Standing.EVENT, "A committed event; it happened.");
        legend.put(Standing.FACT, "A claim with its privileged truth; never disclose it to a limited audience.");
        legend.put(Standing.COMMON_KNOWLEDGE, "Common knowledge everyone in the world shares; its truth is not stated.");
        legend.put(Standing.OBLIGATION, "An open commitment; it stays open until a recorded resolution.");
        legend.put(Standing.BELIEF, "What this audience holds with the given stance; it may be wrong.");
        legend.put(Standing.LAST_KNOWN, "Where this audience last knew someone to be; not their real current location.");
        legend.put(Standing.NARRATION, "Text already told to the player; not a source of truth.");
        legend.put(Standing.ASSUMPTION, "A private planning assumption; unverified, never admitted, never public.");
        return Collections.unmodifiableMap(legend);
    }

    /** @return lowercase SHA-256 hex of UTF-8 text */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }

    /**
     * Provenance of one context.
     *
     * @param adventureId owning adventure
     * @param campaignId campaign every read belongs to
     * @param campaignGeneration recovery generation at assembly
     * @param stateRevision committed state revision every read describes
     * @param worldTime campaign world time at that revision, in seconds
     * @param role task role
     * @param audience disclosure audience; the role must permit it
     * @param holderId actor whose knowledge a public or character audience holds; null for privileged
     * @param operationId focus operation, or null
     * @param packHash pinned content hash
     * @param rulesetHash pinned ruleset hash
     * @param policyHash audience policy hash of the caller
     * @param locale output locale
     * @param localeRevision campaign locale revision at assembly
     * @param glossaryRevision campaign glossary revision at assembly
     * @param epochs query-scope epochs read, by scope key; a later bump makes the context stale
     */
    public record Stamp(UUID adventureId, UUID campaignId, long campaignGeneration, long stateRevision, long worldTime,
            AiRole role, Audience audience, UUID holderId, UUID operationId, String packHash, String rulesetHash,
            String policyHash, SupportedLocale locale, long localeRevision, long glossaryRevision,
            SortedMap<String, Long> epochs) {
        /**
         * @throws IllegalArgumentException when the role does not permit the audience, a holder is
         *         missing for a limited audience or present for a privileged one, a counter is
         *         negative or a hash is malformed
         */
        public Stamp {
            Objects.requireNonNull(adventureId, "adventureId");
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(locale, "locale");
            if (!role.permits(audience)) {
                throw new IllegalArgumentException("role " + role.wire() + " does not permit audience " + audience.wire());
            }
            if ((holderId == null) != (audience.kind() == Audience.Kind.PRIVILEGED)) {
                throw new IllegalArgumentException("exactly the limited audiences have a holder");
            }
            if (campaignGeneration < 0 || stateRevision < 0 || worldTime < 0 || localeRevision < 0 || glossaryRevision < 0) {
                throw new IllegalArgumentException("stamp counters must not be negative");
            }
            for (String hash : List.of(packHash, rulesetHash, policyHash)) {
                if (hash == null || !HASH.matcher(hash).matches()) {
                    throw new IllegalArgumentException("stamp hashes must be lowercase SHA-256 hex");
                }
            }
            epochs = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(epochs, "epochs")));
        }

        /** @return the ordered JSON-ready stamp */
        Map<String, Object> render() {
            var out = new LinkedHashMap<String, Object>();
            out.put("adventureId", adventureId.toString());
            out.put("campaignId", campaignId.toString());
            out.put("campaignGeneration", campaignGeneration);
            out.put("stateRevision", stateRevision);
            out.put("worldTime", worldTime);
            out.put("role", role.wire());
            out.put("audience", audience.wire());
            out.put("holder", holderId == null ? null : "actor:" + holderId);
            out.put("operation", operationId == null ? null : "operation:" + operationId);
            out.put("packHash", packHash);
            out.put("rulesetHash", rulesetHash);
            out.put("policyHash", policyHash);
            out.put("locale", locale.tag());
            out.put("localeRevision", localeRevision);
            out.put("glossaryRevision", glossaryRevision);
            out.put("epochs", epochs);
            return out;
        }
    }
}
