package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trusted input of one continuity-summary task (P09.3, docs/21 section 10, docs/08 section 8): a
 * contiguous run of one audience's committed public events, the knowledge records that audience
 * holds, and the entities the summary may name.
 *
 * <p>Everything here was already filtered for the task's audience by the context assembler; the
 * contract never filters visibility itself. Each source keeps its epistemic standing, so the model
 * can tell an admitted record from a belief that may be wrong. The binding's base revision is the
 * revision the sources were read at; a summary describes those sources and nothing later.
 *
 * @param binding identity, generation, locale and glossary revisions of the task
 * @param events the events to summarize in sequence order, 1..{@link #MAX_EVENTS}; ids are
 *        {@code event:<sequence>} and every one must be covered by the output
 * @param sources audience-held knowledge records the summary may rely on, at most {@link #MAX_SOURCES}
 * @param entities actors and locations the summary may list as covered entities, at most {@link #MAX_ENTITIES}
 * @param glossary selected-locale terminology
 * @param guard privileged phrases that must not appear; empty for a privileged summary
 */
public record SummaryContext(TaskBinding binding, List<PublicFact> events, List<Source> sources,
        List<Entity> entities, List<GlossaryTerm> glossary, DisclosureGuard guard) implements ContractContext {
    /** Most events per summary; the job range is cut to this size. */
    public static final int MAX_EVENTS = 32;
    /** Most knowledge sources per summary. */
    public static final int MAX_SOURCES = 64;
    /** Most entities per summary. */
    public static final int MAX_ENTITIES = 64;
    /** Most glossary entries. */
    public static final int MAX_GLOSSARY = 128;
    private static final Pattern EVENT = Pattern.compile("event:[1-9][0-9]{0,17}");

    /**
     * Freeze and bound every list.
     *
     * @throws IllegalArgumentException for out-of-bound or duplicate entries, an event id that is
     *         not {@code event:<sequence>}, events out of sequence order, or an identity shared
     *         between events, sources and entities
     */
    public SummaryContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(guard, "guard");
        events = ContextValues.distinct(events, "events", 1, MAX_EVENTS, PublicFact::id);
        sources = ContextValues.distinct(sources, "sources", 0, MAX_SOURCES, Source::id);
        entities = ContextValues.distinct(entities, "entities", 0, MAX_ENTITIES, Entity::ref);
        glossary = ContextValues.distinct(glossary, "glossary", 0, MAX_GLOSSARY, GlossaryTerm::termId);
        long previous = 0;
        for (PublicFact event : events) {
            if (!EVENT.matcher(event.id()).matches()) {
                throw new IllegalArgumentException("summary events must be event:<sequence> references");
            }
            long sequence = Long.parseLong(event.id().substring("event:".length()));
            if (sequence <= previous) {
                throw new IllegalArgumentException("summary events must be in increasing sequence order");
            }
            previous = sequence;
        }
        for (Source source : sources) {
            if (events.stream().anyMatch(e -> e.id().equals(source.id()))
                    || entities.stream().anyMatch(e -> e.ref().equals(source.id()))) {
                throw new IllegalArgumentException("event, source and entity identities must be distinct");
            }
        }
    }

    /** @return the identities of the supplied open obligations, in supplied order */
    public List<String> obligationIds() {
        return sources.stream().filter(source -> source.standing().equals(Source.OBLIGATION)).map(Source::id).toList();
    }

    /**
     * One knowledge record the audience holds.
     *
     * @param id stable record reference, for example {@code belief:<uuid>} or {@code commitment:<uuid>}
     * @param standing epistemic label from the context envelope: {@code belief},
     *        {@code common-knowledge}, {@code obligation} (an open commitment) or {@code fact}
     *        (privileged summaries only)
     * @param text localized statement or exact terms, at most {@link PublicFact#MAX_TEXT} characters
     */
    public record Source(String id, String standing, String text) {
        /** An open commitment. */
        public static final String OBLIGATION = "obligation";
        /** A held stance that may be wrong. */
        public static final String BELIEF = "belief";
        /** Supported standings. */
        static final Set<String> STANDINGS = Set.of(BELIEF, "common-knowledge", OBLIGATION, "fact");

        /** @throws IllegalArgumentException for a malformed reference, unknown standing or unbounded text */
        public Source {
            ContextValues.reference(id, "source id");
            if (!STANDINGS.contains(standing)) {
                throw new IllegalArgumentException("unsupported source standing");
            }
            ContextValues.text(text, "source text", PublicFact.MAX_TEXT);
        }
    }

    /**
     * An actor or location the audience knows, with the name it knows it by.
     *
     * @param ref {@code actor:<uuid>} or {@code location:<id>}
     * @param name known display name, at most {@link KnownActor#MAX_NAME} characters
     */
    public record Entity(String ref, String name) {
        /** @throws IllegalArgumentException for a malformed reference or unbounded name */
        public Entity {
            ContextValues.reference(ref, "entity ref");
            if (!ref.startsWith("actor:") && !ref.startsWith("location:")) {
                throw new IllegalArgumentException("summary entities are actors or locations");
            }
            ContextValues.text(name, "entity name", KnownActor.MAX_NAME);
        }
    }
}
