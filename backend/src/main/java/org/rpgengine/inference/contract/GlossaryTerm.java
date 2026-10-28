package org.rpgengine.inference.contract;

import java.util.List;

/**
 * Stable terminology binding for the selected output locale (docs/29 sections 4 and 5).
 *
 * <p>A {@link Kind#PROPER_NAME} must keep its exact spelling and capitalization wherever it
 * appears. A {@link Kind#TERM} must use {@code rendering} in the selected locale; each entry of
 * {@code avoidForms} is a rendering of the same key that is wrong here (usually the other
 * locale's name, such as "Force" in Italian narration where the reviewed name is "Forza").
 * Possessing a glossary entry is never knowledge authorization: callers include only terms of
 * entities the audience may know.
 *
 * @param termId stable glossary key, for example {@code attribute.force}
 * @param kind proper name or translatable term
 * @param rendering exact text to use in the selected locale
 * @param avoidForms renderings that must not appear in the selected locale; empty for proper
 *        names, at most {@link #MAX_AVOID_FORMS}, none equal to the rendering ignoring case
 */
public record GlossaryTerm(String termId, Kind kind, String rendering, List<String> avoidForms) {
    /** Longest rendering or avoided form. */
    public static final int MAX_TEXT = 80;
    /** Most avoided forms per term. */
    public static final int MAX_AVOID_FORMS = 4;

    /** Terminology class of an entry. */
    public enum Kind {
        /** A name that is identical in every locale. */
        PROPER_NAME,
        /** A term with a reviewed rendering per locale. */
        TERM
    }

    /**
     * @throws IllegalArgumentException for malformed or contradictory entries
     */
    public GlossaryTerm {
        ContextValues.reference(termId, "term id");
        java.util.Objects.requireNonNull(kind, "kind");
        ContextValues.text(rendering, "rendering", MAX_TEXT);
        avoidForms = ContextValues.distinct(avoidForms, "avoid forms", 0, MAX_AVOID_FORMS,
                form -> ContextValues.text(form, "avoid form", MAX_TEXT).toLowerCase(java.util.Locale.ROOT));
        if (kind == Kind.PROPER_NAME && !avoidForms.isEmpty()) {
            throw new IllegalArgumentException("a proper name is identical in every locale");
        }
        for (String form : avoidForms) {
            if (form.equalsIgnoreCase(rendering)) {
                throw new IllegalArgumentException("an avoided form cannot equal the rendering");
            }
        }
    }
}
