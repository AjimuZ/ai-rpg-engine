package org.rpgengine.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.content.PackLocalization;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.inference.contract.GlossaryTerm;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Binds the pinned pack's reviewed terminology to a context (P09.2, docs/29 sections 4 and 5).
 *
 * <p>For each referenced root-pack definition, every glossary entry of that definition becomes a
 * {@link GlossaryTerm}: the rendering is the text resolved for the output locale (an overlay, or
 * the source text when no overlay exists), and a {@code term} avoids the other locale's resolved
 * text when it differs. A {@code properName} keeps one spelling everywhere. Only definitions named
 * by already audience-filtered items are bound, so a glossary entry never authorizes knowledge.
 *
 * <p>Limits, all deliberate: dependency-pack definitions are not bound (their references are
 * qualified and their glossaries are a later refinement); a rendering or avoided form longer than
 * {@link GlossaryTerm#MAX_TEXT} characters, a key that cannot be written as a stable term id and a
 * text without a source entry are skipped. A term id is derived from one glossary key (definition
 * and field), so two definitions never share one. Loading never runs inside a caller transaction; the
 * compiled localization is cached per immutable sealed revision ({@link #CACHE_ENTRIES} at most).
 * A revision that cannot be loaded (unsealed, changed bytes, unavailable ruleset) yields
 * {@link Optional#empty()}, which the envelope records as an unavailable glossary.
 */
@Service
public class PackGlossaries {
    /** Retained compiled localizations. */
    static final int CACHE_ENTRIES = 16;
    private static final Pattern TERM_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final PackRegistry packs;
    private final TransactionTemplate reads;
    private final Map<UUID, PackLocalization> cache = new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, PackLocalization> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /**
     * @param packs registry that reparses and verifies sealed revision bytes
     * @param manager shared transaction manager
     */
    public PackGlossaries(PackRegistry packs, PlatformTransactionManager manager) {
        this.packs = Objects.requireNonNull(packs, "packs");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(60);
    }

    /**
     * Bind the terms of the referenced definitions.
     *
     * <p>The result is keyed by definition so a caller can charge each context item for exactly
     * the terms it names and later keep only the terms of the items it selected. A definition
     * without glossary entries maps to an empty list.
     *
     * @param adventure adventure owning the revision
     * @param revision the campaign's frozen pinned content revision
     * @param locale output locale
     * @param definitions referenced root-pack definition ids; duplicates are ignored
     * @return terms per definition in the given definition order, each list in key order; empty
     *         when the revision cannot be loaded
     * @throws IllegalStateException inside an ambient transaction
     */
    public Optional<Map<String, List<GlossaryTerm>>> bind(UUID adventure, UUID revision, SupportedLocale locale,
            Collection<String> definitions) {
        Objects.requireNonNull(locale, "locale");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("glossary loading must not extend a caller transaction");
        }
        var wanted = new LinkedHashSet<>(definitions);
        if (wanted.isEmpty()) {
            return Optional.of(Map.of());
        }
        PackLocalization localization = load(adventure, revision);
        if (localization == null) {
            return Optional.empty();
        }
        SupportedLocale other = locale == SupportedLocale.EN ? SupportedLocale.IT : SupportedLocale.EN;
        var byDefinition = new LinkedHashMap<String, List<GlossaryTerm>>();
        wanted.forEach(definition -> byDefinition.put(definition, new ArrayList<>()));
        // Keys are visited in sorted order, so every definition's list is deterministic.
        for (PackLocalization.Key key : localization.glossary().keySet().stream().sorted().toList()) {
            var list = byDefinition.get(key.id());
            String termId = list == null ? null : termId(key);
            if (termId == null) {
                continue;
            }
            GlossaryTerm term = term(localization, key, termId, locale, other);
            if (term != null) {
                list.add(term);
            }
        }
        var frozen = new LinkedHashMap<String, List<GlossaryTerm>>();
        byDefinition.forEach((definition, list) -> frozen.put(definition, List.copyOf(list)));
        return Optional.of(java.util.Collections.unmodifiableMap(frozen));
    }

    /** @return the bounded term, or null when it cannot be expressed within the contract bounds */
    private static GlossaryTerm term(PackLocalization localization, PackLocalization.Key key, String termId,
            SupportedLocale locale, SupportedLocale other) {
        try {
            String rendering = localization.resolve(key.id(), key.field(), locale.tag()).text().strip();
            if (rendering.isEmpty() || rendering.length() > GlossaryTerm.MAX_TEXT) {
                return null;
            }
            if (localization.glossary().get(key).kind().equals("properName")) {
                return new GlossaryTerm(termId, GlossaryTerm.Kind.PROPER_NAME, rendering, List.of());
            }
            String foreign = localization.resolve(key.id(), key.field(), other.tag()).text().strip();
            List<String> avoid = foreign.isEmpty() || foreign.length() > GlossaryTerm.MAX_TEXT
                    || foreign.equalsIgnoreCase(rendering) ? List.of() : List.of(foreign);
            return new GlossaryTerm(termId, GlossaryTerm.Kind.TERM, rendering, avoid);
        } catch (IllegalArgumentException unusable) {
            // A glossary target without source text, or text the contract bounds reject, is skipped.
            return null;
        }
    }

    /**
     * @return a stable term id such as {@code fieldwork.name} for key {@code (fieldwork, /name)},
     *         or null when the key cannot be written in the reference syntax
     */
    static String termId(PackLocalization.Key key) {
        String id = key.id().replace("$", "");
        String field = key.field().replace('/', '.');
        String candidate = field.startsWith(".") ? id + field : id + "." + field;
        return TERM_ID.matcher(candidate).matches() ? candidate : null;
    }

    /** @return the cached or freshly compiled localization, or null when the revision cannot be loaded */
    private PackLocalization load(UUID adventure, UUID revision) {
        synchronized (cache) {
            PackLocalization cached = cache.get(revision);
            if (cached != null) {
                return cached;
            }
        }
        PackLocalization loaded = reads.execute(status -> {
            try {
                return packs.loadForCampaign(adventure, revision).root().localization();
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException unavailable) {
                // Unsealed or legacy revisions, changed bytes or an unavailable ruleset: no glossary.
                // Storage failures are not caught: they are transient and reach the caller.
                status.setRollbackOnly();
                return null;
            }
        });
        if (loaded != null) {
            synchronized (cache) {
                cache.put(revision, loaded);
            }
        }
        return loaded;
    }
}
