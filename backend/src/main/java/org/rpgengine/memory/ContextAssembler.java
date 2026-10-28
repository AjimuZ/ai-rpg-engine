package org.rpgengine.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.inference.contract.GlossaryTerm;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.rpgengine.knowledge.KnowledgeView;
import org.rpgengine.memory.ContextItem.Section;
import org.rpgengine.memory.ContextItem.Standing;
import org.rpgengine.memory.ContextItem.Tier;
import org.rpgengine.narration.EventStatements;
import org.rpgengine.preparation.PlanAssumptions;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assembles stamped, budgeted AI task contexts from canonical storage (P09.2, docs/08 sections 5
 * and 6, ADR 0005, ADR 0007).
 *
 * <p><b>Audience first.</b> Every candidate is read for exactly one audience before ranking, so a
 * filtered-out record never reaches retrieval, selection, rendering or a digest:
 * <ul>
 *   <li>{@link Audience.Kind#PUBLIC}: the player character's {@link KnowledgeView#forActorWithin}
 *       view (beliefs without truth, common claims, last known locations, visible open
 *       commitments), the campaign's {@code PUBLIC} events stated by {@link EventStatements} and
 *       the published narration;</li>
 *   <li>{@link Audience.Kind#CHARACTER}: the same knowledge view for the subject actor
 *       ({@code actor:<uuid>}), without event history or narration, because no NPC-side record of
 *       what a character witnessed exists yet;</li>
 *   <li>{@link Audience.Kind#PRIVILEGED}: the {@link KnowledgeView#privilegedWithin} record with
 *       truth and real locations, public history, and the private assumptions of usable quest
 *       plans, labelled {@link Standing#ASSUMPTION} with their freshness.</li>
 * </ul>
 * All reads share one read-only repeatable-read snapshot, and the stamp records its revision,
 * generation, hashes, locale and glossary revisions and the query-scope epochs that later writers
 * bump, so {@link #staleness} can tell exactly what changed.
 *
 * <p><b>Retrieval order</b> (docs/08 section 5): mandatory state, then records naming an explicit
 * focus reference ({@link Tier#ID}; an {@code event:<sequence>} or {@code narration:<operation>}
 * reference also fetches that public record when it is older than the recent window), then records written by the focus operation or told by a focus
 * actor ({@link Tier#CAUSAL}), then PostgreSQL full-text matches of the task query
 * ({@link Tier#TEXT}), then everything else by importance and recency ({@link Tier#RECENT}).
 * Full-text search runs in the database over audience-authorized rows only: claim statements and
 * commitment terms restricted to the audience's own candidate ids, and, for public and privileged
 * audiences, public event payload strings and published narration passages over the whole campaign
 * history, so older dialogue is reachable beyond the recent window. Each text uses the stemming
 * configuration of its own language ({@code english} or {@code italian}).
 *
 * <p><b>Mandatory material</b> is the audience's own state, the actors it currently sees at its
 * location, the open commitments it is party to and the focus operation's public events. It is
 * never dropped; if it does not fit the budget the result is {@link TooLarge}.
 *
 * <p><b>Language binding.</b> Each text item carries its own language; the envelope carries the
 * output locale with its locale and glossary revisions, and the glossary terms of the pinned pack
 * for the definitions the selected items name ({@link PackGlossaries}).
 */
@Service
public class ContextAssembler {
    /** Code of the controlled overflow result. */
    public static final String TOO_LARGE = "CONTEXT_TOO_LARGE";
    /** Most explicit focus references per request. */
    public static final int MAX_FOCUS = 64;
    /** Longest full-text query. */
    public static final int MAX_QUERY = 1000;
    /** Recent public events read as recency candidates. */
    static final int RECENT_EVENTS = 64;
    /** Recent narration publications read as recency candidates. */
    static final int RECENT_NARRATIONS = 16;
    /** Most full-text matches per source. */
    static final int TEXT_MATCHES = 16;
    /** Separates importance from recency inside one score. */
    private static final long IMPORTANCE = 1L << 40;
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern ACTOR = Pattern.compile(
            "actor:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    /** Focus reference of one campaign event; the sequence is bounded so it parses as a long. */
    private static final Pattern EVENT_REF = Pattern.compile("event:([1-9][0-9]{0,17})");
    /** Focus reference of one narration publication. */
    private static final Pattern NARRATION_REF = Pattern.compile(
            "narration:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final KnowledgeView knowledge;
    private final PackGlossaries glossaries;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate check;

    /** Wire storage, the knowledge views and the glossary binder; missing beans fail startup. */
    public ContextAssembler(JdbcTemplate jdbc, PlatformTransactionManager manager, KnowledgeView knowledge,
            PackGlossaries glossaries) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.glossaries = Objects.requireNonNull(glossaries, "glossaries");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(15);
        check = new TransactionTemplate(manager);
        check.setReadOnly(true);
        check.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        check.setTimeout(5);
    }

    /**
     * Assemble one context.
     *
     * @param request what to assemble, for whom and under which budget
     * @return the stamped envelope, or the controlled overflow when mandatory material does not fit
     * @throws IllegalArgumentException for an unknown campaign or audience actor, a role that does
     *         not permit the audience, or an uncommitted focus operation
     * @throws IllegalStateException inside an ambient transaction, when a public campaign has no
     *         player character, or when a knowledge list exceeds its bound
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public Result assemble(Request request) {
        Objects.requireNonNull(request, "request");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("context assembly requires an independent snapshot");
        }
        Gathered gathered = Objects.requireNonNull(snapshot.execute(status -> gather(request)));
        boolean privileged = request.audience().kind() == Audience.Kind.PRIVILEGED;
        // Terminology is bound once for every candidate's definitions. Each item is charged for the
        // glossary entries of the definitions it names, so a term costs budget only when an item that
        // needs it is selected; an entry shared by several items is charged to each (an upper bound).
        var bound = glossaries.bind(request.adventureId(), gathered.contentRevision(), gathered.stamp().locale(),
                terms(gathered.candidates()));
        boolean available = bound.isPresent();
        Map<String, List<GlossaryTerm>> byDefinition = bound.orElse(Map.of());
        var termCost = new HashMap<String, Long>();
        byDefinition.forEach((definition, list) -> termCost.put(definition,
                list.stream().mapToLong(ContextEnvelope::glossaryCost).sum()));
        int fixed = Math.addExact(request.instructionTokens(),
                ContextEnvelope.overhead(gathered.stamp(), request.budget(), available));
        var result = ContextSelection.select(gathered.candidates(), request.budget(), fixed,
                item -> item.terms().stream().distinct().mapToLong(term -> termCost.getOrDefault(term, 0L)).sum(),
                privileged);
        if (result instanceof ContextSelection.TooLarge overflow) {
            return new TooLarge(gathered.stamp(), overflow);
        }
        var selected = (ContextSelection.Selected) result;
        // The glossary keeps the terms of the selected items in render order (mandatory and best
        // ranked first); terms beyond the contract bound are counted, never dropped silently.
        var glossary = new ArrayList<GlossaryTerm>();
        var seen = new HashSet<String>();
        int glossaryOmitted = 0;
        for (String definition : terms(selected.items().values().stream().flatMap(List::stream).toList())) {
            for (GlossaryTerm term : byDefinition.getOrDefault(definition, List.of())) {
                if (!seen.add(term.termId())) {
                    continue;
                }
                if (glossary.size() < ContextEnvelope.MAX_GLOSSARY) {
                    glossary.add(term);
                } else {
                    glossaryOmitted++;
                }
            }
        }
        var envelope = new ContextEnvelope(gathered.stamp(), request.budget(), selected.items(), selected.omitted(),
                glossary, available, glossaryOmitted, selected.tokens());
        // Defence in depth: the item-by-item charge must bound the real rendering (TokenEstimator is
        // subadditive); a violation is an estimator or overhead defect, never a reason to send more.
        if (request.instructionTokens() + TokenEstimator.tokens(envelope.json()) > selected.tokens()) {
            throw new IllegalStateException("context estimate is not subadditive");
        }
        return new Assembled(envelope);
    }

    /**
     * Compare a stamp with the campaign now.
     *
     * @param stamp stamp of an earlier envelope
     * @return changed components in a fixed order: {@code generation}, {@code revision},
     *         {@code locale}, {@code glossary}, then {@code epoch:<scope>} for each bumped scope;
     *         empty when the context is still current
     * @throws IllegalArgumentException when the campaign no longer exists
     */
    public List<String> staleness(ContextEnvelope.Stamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        return Objects.requireNonNull(check.execute(status -> {
            var rows = jdbc.query("select c.recovery_generation, c.state_revision, coalesce(p.narrative_locale, "
                    + "w.source_locale), coalesce(p.locale_revision, 0), coalesce(p.glossary_revision, 0) from campaign c "
                    + "join adventure_workspace w on w.id = c.adventure_id left join presentation_campaign_settings p "
                    + "on p.campaign_id = c.id where c.id = ? and c.adventure_id = ?",
                    (row, index) -> new Object[] {row.getLong(1), row.getLong(2), row.getString(3), row.getLong(4),
                            row.getLong(5)}, stamp.campaignId(), stamp.adventureId());
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            var now = rows.getFirst();
            var changed = new ArrayList<String>();
            if ((long) now[0] != stamp.campaignGeneration()) {
                changed.add("generation");
            }
            if ((long) now[1] != stamp.stateRevision()) {
                changed.add("revision");
            }
            if (!now[2].equals(stamp.locale().tag()) || (long) now[3] != stamp.localeRevision()) {
                changed.add("locale");
            }
            if ((long) now[4] != stamp.glossaryRevision()) {
                changed.add("glossary");
            }
            stamp.epochs().forEach((scope, epoch) -> {
                if (QueryEpochs.current(jdbc, stamp.campaignId(), scope) != epoch) {
                    changed.add("epoch:" + scope);
                }
            });
            return List.copyOf(changed);
        }));
    }

    /**
     * Privileged phrases one audience actor must not be shown, for a contract's disclosure guard
     * (docs/21 section 14). The guard is validator input only and is never rendered.
     *
     * <p>Withheld are the statements of {@code HOLDERS} claims the actor has never held a belief
     * about (current or superseded) and the terms of {@code PARTIES} commitments it is not party to.
     * Truth plays no part: a false secret is still a secret. Phrases shorter than
     * {@value #MIN_PHRASE} characters after folding are left out because such short generic
     * wording would refuse ordinary public text; longer ones are cut to the guard's
     * {@value org.rpgengine.inference.contract.DisclosureGuard#MAX_MARKER}-character bound, which
     * still detects verbatim copying. Newest records come first and at most
     * {@value org.rpgengine.inference.contract.DisclosureGuard#MAX_MARKERS} distinct phrases are
     * returned. Paraphrase is not detected by any guard.
     *
     * @param campaign campaign the caller authorized
     * @param holder audience actor
     * @return distinct phrases, folded duplicates removed
     * @throws IllegalArgumentException when the holder is not an actor of the campaign
     * @throws IllegalStateException inside an ambient transaction or when a knowledge list exceeds its bound
     */
    public List<String> withheldPhrases(UUID campaign, UUID holder) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(holder, "holder");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("context assembly requires an independent snapshot");
        }
        return Objects.requireNonNull(snapshot.execute(status -> {
            knowledge.forActorWithin(campaign, holder);
            KnowledgeView.Privileged view = knowledge.privilegedWithin(campaign);
            var known = new HashSet<UUID>();
            view.beliefs().stream().filter(belief -> belief.holderId().equals(holder))
                    .forEach(belief -> known.add(belief.claimId()));
            var candidates = new ArrayList<String>();
            for (var claim : view.claims().reversed()) {
                if (claim.disclosure() == KnowledgeChange.Disclosure.HOLDERS && !known.contains(claim.claim().claimId())
                        && claim.claim().statement() != null) {
                    candidates.add(claim.claim().statement());
                }
            }
            for (var record : view.commitments().reversed()) {
                if (record.disclosure() == KnowledgeChange.CommitmentDisclosure.PARTIES
                        && !holder.equals(record.debtorId()) && !holder.equals(record.creditorId())) {
                    candidates.add(record.terms());
                }
            }
            var folded = new HashSet<String>();
            var phrases = new ArrayList<String>();
            for (String text : candidates) {
                String phrase = marker(text);
                if (phrase != null && phrases.size() < org.rpgengine.inference.contract.DisclosureGuard.MAX_MARKERS
                        && folded.add(fold(phrase))) {
                    phrases.add(phrase);
                }
            }
            return List.copyOf(phrases);
        }));
    }

    /** Shortest withheld phrase, in folded characters. */
    static final int MIN_PHRASE = 12;

    /** @return the phrase cut to the guard bound at a code-point boundary, or null when too short */
    private static String marker(String text) {
        String phrase = text.strip();
        int max = org.rpgengine.inference.contract.DisclosureGuard.MAX_MARKER;
        if (phrase.length() > max) {
            int end = Character.isHighSurrogate(phrase.charAt(max - 1)) ? max - 1 : max;
            phrase = phrase.substring(0, end).strip();
        }
        return fold(phrase).length() < MIN_PHRASE ? null : phrase;
    }

    /** Case and whitespace folding identical to the contract guard's matching. */
    private static String fold(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Read every candidate inside the snapshot; see the class description for what may be read. */
    private Gathered gather(Request request) {
        UUID campaign = request.campaignId();
        var tokens = jdbc.query("select c.state_revision, c.recovery_generation, c.world_time_seconds, "
                + "c.content_revision_id, r.content_hash, r.ruleset_hash, coalesce(p.narrative_locale, w.source_locale), "
                + "coalesce(p.locale_revision, 0), coalesce(p.glossary_revision, 0) from campaign c join content_revision r "
                + "on r.id = c.content_revision_id and r.adventure_id = c.adventure_id join adventure_workspace w "
                + "on w.id = c.adventure_id left join presentation_campaign_settings p on p.campaign_id = c.id "
                + "where c.id = ? and c.adventure_id = ?",
                (row, index) -> new Object[] {row.getLong(1), row.getLong(2), row.getLong(3),
                        row.getObject(4, UUID.class), row.getString(5), row.getString(6), row.getString(7),
                        row.getLong(8), row.getLong(9)}, campaign, request.adventureId());
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("unknown campaign");
        }
        var token = tokens.getFirst();
        SupportedLocale locale = SupportedLocale.fromTag((String) token[6]);
        Audience audience = request.audience();
        UUID holder = switch (audience.kind()) {
            case PUBLIC -> {
                var players = jdbc.queryForList("select actor_id from campaign_creation where campaign_id = ?",
                        UUID.class, campaign);
                if (players.isEmpty()) {
                    throw new IllegalStateException("public context needs the campaign's player character");
                }
                yield players.getFirst();
            }
            case CHARACTER -> {
                var matcher = ACTOR.matcher(audience.subject());
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("a character audience must name an actor:<uuid>");
                }
                yield UUID.fromString(matcher.group(1));
            }
            case PRIVILEGED -> null;
        };
        Long focusRevision = null;
        if (request.operationId() != null) {
            var committed = jdbc.query("select committed_revision from operation_journal where id = ? and campaign_id = ?",
                    (row, index) -> row.getObject(1, Long.class), request.operationId(), campaign);
            if (committed.isEmpty() || committed.getFirst() == null) {
                throw new IllegalArgumentException("focus operation is not a committed operation of the campaign");
            }
            focusRevision = committed.getFirst();
        }
        var epochs = new TreeMap<String, Long>();
        var scopes = holder == null ? List.of(KnowledgeLedger.CLAIMS_SCOPE) : List.of(KnowledgeLedger.CLAIMS_SCOPE,
                KnowledgeLedger.knowledgeScope(holder), KnowledgeLedger.sightingScope(holder),
                KnowledgeLedger.commitmentScope(holder), "actor:" + holder);
        scopes.forEach(scope -> epochs.put(scope, QueryEpochs.current(jdbc, campaign, scope)));
        var stamp = new ContextEnvelope.Stamp(request.adventureId(), campaign, (long) token[1], (long) token[0],
                (long) token[2], request.role(), audience, holder, request.operationId(), (String) token[4],
                (String) token[5], request.policyHash(), locale, (long) token[7], (long) token[8], epochs);

        var drafts = new ArrayList<Draft>();
        var focus = new LinkedHashSet<>(request.focusRefs());
        boolean history = audience.kind() != Audience.Kind.CHARACTER;
        if (history && request.operationId() != null) {
            focusEvents(campaign, request.operationId(), locale, holder, focus, drafts);
        }
        String query = request.query();
        if (holder != null) {
            audienceKnowledge(campaign, holder, query, drafts);
        } else {
            privilegedKnowledge(campaign, query, drafts);
            assumptions(campaign, drafts);
        }
        if (history) {
            history(campaign, request.operationId(), locale, query, focus, drafts);
        }
        var items = new ArrayList<ContextItem>();
        for (Draft draft : drafts) {
            items.add(draft.finish(focus, focusRevision));
        }
        return new Gathered(stamp, (UUID) token[3], List.copyOf(items));
    }

    /** Focus operation's public events: mandatory history; the actors they name join the focus. */
    private void focusEvents(UUID campaign, UUID operation, SupportedLocale locale, UUID holder, Set<String> focus,
            List<Draft> drafts) {
        var events = jdbc.query("select sequence, event_type, event_version, payload::text, state_revision "
                + "from campaign_event where campaign_id = ? and operation_id = ? and visibility = 'PUBLIC' "
                + "order by sequence limit ?", (row, index) -> new StoredEvent(row.getLong(1), row.getString(2),
                        row.getInt(3), JSON.readTree(row.getString(4)), row.getLong(5)),
                campaign, operation, RECENT_EVENTS + 1);
        if (events.size() > RECENT_EVENTS) {
            throw new IllegalStateException("focus operation has too many public events");
        }
        var names = names(campaign, events.stream().flatMap(e -> EventStatements.referencedActors(e.payload()).stream())
                .toList());
        for (StoredEvent event : events) {
            drafts.add(eventDraft(event, locale, names, true));
            for (UUID actor : EventStatements.referencedActors(event.payload())) {
                if (!actor.equals(holder)) {
                    focus.add("actor:" + actor);
                }
            }
        }
    }

    /** The holder's own state and knowledge view; see the class description. */
    private void audienceKnowledge(UUID campaign, UUID holder, String query, List<Draft> drafts) {
        KnowledgeView.Audience view = knowledge.forActorWithin(campaign, holder);
        var actors = new LinkedHashSet<UUID>();
        actors.add(holder);
        view.lastKnown().forEach(seen -> actors.add(seen.subjectId()));
        view.beliefs().forEach(belief -> {
            if (belief.informantId() != null) {
                actors.add(belief.informantId());
            }
            actorOf(belief.claim().subjectRef(), actors);
            actorOf(belief.claim().objectRef(), actors);
        });
        view.common().forEach(common -> {
            actorOf(common.claim().subjectRef(), actors);
            actorOf(common.claim().objectRef(), actors);
        });
        view.commitments().forEach(open -> {
            actors.add(open.debtorId());
            actors.add(open.creditorId());
        });
        var names = names(campaign, actors);

        var self = fields("name", names.get(holder), "location", locationRef(view.ownLocation()), "worldTime",
                view.worldTime());
        drafts.add(new Draft("actor:" + holder, Section.STATE, Standing.STATE, true, null, self,
                terms(locationRef(view.ownLocation())), Set.of(), view.stateRevision(), null, 0));

        Set<String> claimText = query == null ? Set.of() : textClaims(campaign, query, view);
        Set<String> commitmentText = query == null ? Set.of() : textCommitments(campaign, query,
                view.commitments().stream().map(KnowledgeView.OpenCommitment::commitmentId).toList());
        for (KnowledgeView.LastKnown seen : view.lastKnown()) {
            // Someone seen here and not since found absent is present as far as the holder knows.
            boolean here = seen.absenceRevision() == null && Objects.equals(seen.locationId(), view.ownLocation());
            var data = fields("subject", "actor:" + seen.subjectId(), "subjectName", names.get(seen.subjectId()),
                    "location", locationRef(seen.locationId()), "acquisition", wire(seen.acquisition()),
                    "informant", actorRef(seen.informantId()), "observedRevision", seen.observedRevision(),
                    "observedWorldTime", seen.observedWorldTime(), "absentSinceRevision", seen.absenceRevision());
            drafts.add(new Draft("sighting:" + seen.subjectId(), here ? Section.STATE : Section.KNOWLEDGE,
                    Standing.LAST_KNOWN, here, null, data, terms(locationRef(seen.locationId())),
                    Set.of("actor:" + seen.subjectId()), seen.observedRevision(), seen.informantId(), 4));
        }
        for (KnowledgeView.HeldBelief belief : view.beliefs()) {
            var data = claimFields(belief.claim(), names);
            data.put("stance", wire(belief.stance()));
            data.put("acquisition", wire(belief.acquisition()));
            data.put("informant", actorRef(belief.informantId()));
            data.put("source", belief.sourceRef());
            data.put("learnedRevision", belief.learnedRevision());
            drafts.add(new Draft("belief:" + belief.beliefId(), Section.KNOWLEDGE, Standing.BELIEF, false,
                    belief.claim().statementLocale(), data, claimTerms(belief.claim()), claimLinks(belief.claim()),
                    belief.learnedRevision(), belief.informantId(), stanceWeight(belief.stance()))
                    .text(claimText.contains(belief.claim().claimId().toString())));
        }
        for (KnowledgeView.CommonClaim common : view.common()) {
            var data = claimFields(common.claim(), names);
            data.put("establishedRevision", common.establishedRevision());
            drafts.add(new Draft("claim:" + common.claim().claimId(), Section.KNOWLEDGE, Standing.COMMON_KNOWLEDGE,
                    false, common.claim().statementLocale(), data, claimTerms(common.claim()), claimLinks(common.claim()),
                    common.establishedRevision(), null, 5).text(claimText.contains(common.claim().claimId().toString())));
        }
        for (KnowledgeView.OpenCommitment open : view.commitments()) {
            boolean party = holder.equals(open.debtorId()) || holder.equals(open.creditorId());
            var data = fields("kind", wire(open.kind()), "debtor", "actor:" + open.debtorId(), "debtorName",
                    names.get(open.debtorId()), "creditor", "actor:" + open.creditorId(), "creditorName",
                    names.get(open.creditorId()), "subject", open.subjectRef(), "terms", open.terms(), "quoted",
                    open.quoted(), "dueWorldTime", open.dueWorldTime(), "overdue", open.overdue(), "madeRevision",
                    open.madeRevision());
            var links = new HashSet<String>(List.of("actor:" + open.debtorId(), "actor:" + open.creditorId()));
            if (open.subjectRef() != null) {
                links.add(open.subjectRef());
            }
            drafts.add(new Draft("commitment:" + open.commitmentId(), Section.OBLIGATIONS, Standing.OBLIGATION, party,
                    open.termsLocale(), data, terms(open.subjectRef()), links, open.madeRevision(), null,
                    open.overdue() ? 9 : 8).text(commitmentText.contains(open.commitmentId().toString())));
        }
    }

    /** The privileged record: real state, truth, every belief and sighting, open commitments. */
    private void privilegedKnowledge(UUID campaign, String query, List<Draft> drafts) {
        KnowledgeView.Privileged view = knowledge.privilegedWithin(campaign);
        var players = jdbc.queryForList("select actor_id from campaign_creation where campaign_id = ?", UUID.class,
                campaign);
        if (!players.isEmpty()) {
            UUID player = players.getFirst();
            var located = jdbc.query("select display_name, location_id from actor where campaign_id = ? and id = ?",
                    (row, index) -> new String[] {row.getString(1), row.getString(2)}, campaign, player);
            if (!located.isEmpty()) {
                var data = fields("name", located.getFirst()[0], "location", locationRef(located.getFirst()[1]),
                        "worldTime", view.worldTime(), "player", true);
                drafts.add(new Draft("actor:" + player, Section.STATE, Standing.STATE, true, null, data,
                        terms(locationRef(located.getFirst()[1])), Set.of(), view.stateRevision(), null, 0));
                var present = jdbc.query("select id, display_name from actor where campaign_id = ? and id <> ? "
                        + "and location_id = ? order by id limit ?", (row, index) -> new Object[] {
                                row.getObject(1, UUID.class), row.getString(2)},
                        campaign, player, located.getFirst()[1], KnowledgeLedger.MAX_OBSERVED);
                for (Object[] actor : present) {
                    drafts.add(new Draft("actor:" + actor[0], Section.STATE, Standing.STATE, false, null,
                            fields("name", actor[1], "location", locationRef(located.getFirst()[1])),
                            terms(locationRef(located.getFirst()[1])), Set.of("actor:" + actor[0]), view.stateRevision(),
                            null, 6));
                }
            }
        }
        var actors = new LinkedHashSet<UUID>();
        view.claims().forEach(claim -> {
            actorOf(claim.claim().subjectRef(), actors);
            actorOf(claim.claim().objectRef(), actors);
        });
        view.beliefs().forEach(belief -> actors.add(belief.holderId()));
        view.whereabouts().forEach(where -> {
            actors.add(where.observerId());
            actors.add(where.subjectId());
        });
        view.commitments().forEach(record -> {
            actors.add(record.debtorId());
            actors.add(record.creditorId());
        });
        var names = names(campaign, actors);
        var current = view.claims().stream().filter(claim -> claim.supersededRevision() == null).toList();
        var claimIds = current.stream().map(claim -> claim.claim().claimId()).toList();
        Set<String> claimText = query == null ? Set.of() : textRows(campaign, query, "knowledge_claim", "statement",
                "statement_locale", claimIds);
        var contents = new HashMap<UUID, KnowledgeView.ClaimContent>();
        for (KnowledgeView.TruthClaim claim : current) {
            contents.put(claim.claim().claimId(), claim.claim());
            var data = claimFields(claim.claim(), names);
            data.put("disclosure", wire(claim.disclosure()));
            data.put("truth", wire(claim.truth()));
            data.put("establishedRevision", claim.establishedRevision());
            drafts.add(new Draft("claim:" + claim.claim().claimId(), Section.KNOWLEDGE, Standing.FACT, false,
                    claim.claim().statementLocale(), data, claimTerms(claim.claim()), claimLinks(claim.claim()),
                    claim.establishedRevision(), null, 6).text(claimText.contains(claim.claim().claimId().toString())));
        }
        for (KnowledgeView.RecordedBelief belief : view.beliefs()) {
            if (belief.supersededRevision() != null || !contents.containsKey(belief.claimId())) {
                continue;
            }
            var data = fields("holder", "actor:" + belief.holderId(), "holderName", names.get(belief.holderId()),
                    "claim", "claim:" + belief.claimId(), "stance", wire(belief.stance()), "acquisition",
                    wire(belief.acquisition()), "informant", actorRef(belief.informantId()), "source", belief.sourceRef(),
                    "learnedRevision", belief.learnedRevision());
            var links = new HashSet<>(claimLinks(contents.get(belief.claimId())));
            links.add("actor:" + belief.holderId());
            links.add("claim:" + belief.claimId());
            drafts.add(new Draft("belief:" + belief.beliefId(), Section.KNOWLEDGE, Standing.BELIEF, false, null, data,
                    List.of(), links, belief.learnedRevision(), belief.informantId(), stanceWeight(belief.stance())));
        }
        for (KnowledgeView.Whereabouts where : view.whereabouts()) {
            var data = fields("observer", "actor:" + where.observerId(), "subject", "actor:" + where.subjectId(),
                    "subjectName", names.get(where.subjectId()), "location", locationRef(where.lastKnownLocation()),
                    "realLocation", locationRef(where.currentLocation()), "acquisition", wire(where.acquisition()),
                    "informant", actorRef(where.informantId()), "observedRevision", where.observedRevision(),
                    "absentSinceRevision", where.absenceRevision());
            drafts.add(new Draft("sighting:" + where.observerId() + ":" + where.subjectId(), Section.KNOWLEDGE,
                    Standing.LAST_KNOWN, false, null, data, terms(locationRef(where.lastKnownLocation()),
                            locationRef(where.currentLocation())),
                    Set.of("actor:" + where.observerId(), "actor:" + where.subjectId()), where.observedRevision(),
                    where.informantId(), 4));
        }
        var open = view.commitments().stream()
                .filter(record -> record.status() == KnowledgeChange.CommitmentStatus.OPEN).toList();
        Set<String> commitmentText = query == null ? Set.of() : textCommitments(campaign, query,
                open.stream().map(KnowledgeView.CommitmentRecord::commitmentId).toList());
        for (KnowledgeView.CommitmentRecord record : open) {
            var data = fields("kind", wire(record.kind()), "debtor", "actor:" + record.debtorId(), "debtorName",
                    names.get(record.debtorId()), "creditor", "actor:" + record.creditorId(), "creditorName",
                    names.get(record.creditorId()), "subject", record.subjectRef(), "terms", record.terms(), "quoted",
                    record.quoted(), "disclosure", wire(record.disclosure()), "dueWorldTime", record.dueWorldTime(),
                    "overdue", record.overdue(), "madeRevision", record.madeRevision());
            var links = new HashSet<String>(List.of("actor:" + record.debtorId(), "actor:" + record.creditorId()));
            if (record.subjectRef() != null) {
                links.add(record.subjectRef());
            }
            drafts.add(new Draft("commitment:" + record.commitmentId(), Section.OBLIGATIONS, Standing.OBLIGATION, false,
                    record.termsLocale(), data, terms(record.subjectRef()), links, record.madeRevision(), null,
                    record.overdue() ? 9 : 8).text(commitmentText.contains(record.commitmentId().toString())));
        }
    }

    /**
     * Private assumptions of usable quest plans, read through {@link PlanAssumptions} because plan
     * storage belongs to the preparation package (ADR 0014). Fresh assumptions rank above stale
     * ones; each keeps its plan, status, freshness and citations so the planner can tell which
     * hypotheses a replan must revisit.
     */
    private void assumptions(UUID campaign, List<Draft> drafts) {
        for (PlanAssumptions.Assumption assumption : PlanAssumptions.usableWithin(jdbc, campaign)) {
            String ref = "assumption:" + assumption.artifactId() + ":" + assumption.assumptionId();
            var data = fields("plan", "plan:" + assumption.planId(), "planVersion", assumption.planVersion(), "quest",
                    assumption.questTarget(), "planStatus", assumption.planStatus().toLowerCase(java.util.Locale.ROOT),
                    "fresh", assumption.fresh(), "assumptionId", assumption.assumptionId(), "note", assumption.note(),
                    "branchRefs", assumption.branchRefs(), "dependencyRefs", assumption.dependencyRefs());
            drafts.add(new Draft(ref, Section.PLANNING, Standing.ASSUMPTION, false, assumption.locale(), data, List.of(),
                    Set.of("plan:" + assumption.planId(), assumption.questTarget()), assumption.createdEpochSeconds(),
                    null, assumption.fresh() ? 7 : 2));
        }
    }

    /**
     * Public history: the recent window, older records named explicitly by a focus reference
     * ({@code event:<sequence>} or {@code narration:<operation>}, so ID retrieval reaches beyond
     * the window) and full-text matches over the whole campaign. Every read keeps the
     * {@code PUBLIC} visibility filter; a focus reference to an internal event, another campaign's
     * record or a missing one simply finds nothing.
     */
    private void history(UUID campaign, UUID focusOperation, SupportedLocale locale, String query, Set<String> focus,
            List<Draft> drafts) {
        var events = new LinkedHashMap<Long, StoredEvent>();
        jdbc.query("select sequence, event_type, event_version, payload::text, state_revision from campaign_event "
                + "where campaign_id = ? and visibility = 'PUBLIC' and operation_id is distinct from cast(? as uuid) "
                + "order by sequence desc limit ?", row -> {
                    events.put(row.getLong(1), new StoredEvent(row.getLong(1), row.getString(2), row.getInt(3),
                            JSON.readTree(row.getString(4)), row.getLong(5)));
                }, campaign, focusOperation, RECENT_EVENTS);
        var namedEvents = focusIds(focus, EVENT_REF).stream().map(Long::valueOf)
                .filter(sequence -> !events.containsKey(sequence)).toArray(Long[]::new);
        if (namedEvents.length > 0) {
            jdbc.query("select sequence, event_type, event_version, payload::text, state_revision from campaign_event "
                    + "where campaign_id = ? and visibility = 'PUBLIC' and operation_id is distinct from cast(? as uuid) "
                    + "and sequence = any(?) order by sequence", row -> {
                        events.put(row.getLong(1), new StoredEvent(row.getLong(1), row.getString(2), row.getInt(3),
                                JSON.readTree(row.getString(4)), row.getLong(5)));
                    }, campaign, focusOperation, namedEvents);
        }
        var matchedEvents = new HashSet<Long>();
        if (query != null) {
            matchedEvents.addAll(jdbc.queryForList("select e.sequence from campaign_event e cross join lateral "
                    + "(select " + config("?") + " as cfg) l where e.campaign_id = ? and e.visibility = 'PUBLIC' "
                    + "and e.operation_id is distinct from cast(? as uuid) and jsonb_to_tsvector(l.cfg, e.payload, '[\"string\"]') "
                    + "@@ websearch_to_tsquery(l.cfg, ?) order by ts_rank(jsonb_to_tsvector(l.cfg, e.payload, "
                    + "'[\"string\"]'), websearch_to_tsquery(l.cfg, ?)) desc, e.sequence desc limit ?", Long.class,
                    locale.tag(), campaign, focusOperation, query, query, TEXT_MATCHES));
            var missing = matchedEvents.stream().filter(sequence -> !events.containsKey(sequence)).toArray(Long[]::new);
            if (missing.length > 0) {
                jdbc.query("select sequence, event_type, event_version, payload::text, state_revision from campaign_event "
                        + "where campaign_id = ? and visibility = 'PUBLIC' and sequence = any(?)", row -> {
                            events.put(row.getLong(1), new StoredEvent(row.getLong(1), row.getString(2), row.getInt(3),
                                    JSON.readTree(row.getString(4)), row.getLong(5)));
                        }, campaign, missing);
            }
        }
        var names = names(campaign, events.values().stream()
                .flatMap(e -> EventStatements.referencedActors(e.payload()).stream()).toList());
        for (StoredEvent event : events.values()) {
            drafts.add(eventDraft(event, locale, names, false).text(matchedEvents.contains(event.sequence())));
        }

        var narrations = new LinkedHashMap<UUID, Object[]>();
        jdbc.query("select operation_id, committed_revision, passage_locale, passages::text from narration_publication "
                + "where campaign_id = ? and operation_id is distinct from cast(? as uuid) order by committed_revision desc limit ?",
                row -> {
                    narrations.put(row.getObject(1, UUID.class), new Object[] {row.getLong(2), row.getString(3),
                            JSON.readTree(row.getString(4))});
                }, campaign, focusOperation, RECENT_NARRATIONS);
        var namedNarrations = focusIds(focus, NARRATION_REF).stream().map(UUID::fromString)
                .filter(id -> !id.equals(focusOperation) && !narrations.containsKey(id)).toArray(UUID[]::new);
        if (namedNarrations.length > 0) {
            jdbc.query("select operation_id, committed_revision, passage_locale, passages::text "
                    + "from narration_publication where campaign_id = ? and operation_id = any(?) "
                    + "order by committed_revision desc", row -> {
                        narrations.put(row.getObject(1, UUID.class), new Object[] {row.getLong(2), row.getString(3),
                                JSON.readTree(row.getString(4))});
                    }, campaign, namedNarrations);
        }
        var matchedNarrations = new HashSet<UUID>();
        if (query != null) {
            matchedNarrations.addAll(jdbc.queryForList("select n.operation_id from narration_publication n "
                    + "cross join lateral (select " + config("n.passage_locale") + " as cfg) l cross join lateral "
                    + "(select to_tsvector(l.cfg, coalesce((select string_agg(p ->> 'text', ' ') from "
                    + "jsonb_array_elements(n.passages) p), '')) as doc, websearch_to_tsquery(l.cfg, ?) as q) v "
                    + "where n.campaign_id = ? and n.operation_id is distinct from cast(? as uuid) and v.doc @@ v.q "
                    + "order by ts_rank(v.doc, v.q) desc, n.committed_revision desc limit ?", UUID.class, query, campaign,
                    focusOperation, TEXT_MATCHES));
            var missing = matchedNarrations.stream().filter(id -> !narrations.containsKey(id)).toArray(UUID[]::new);
            if (missing.length > 0) {
                jdbc.query("select operation_id, committed_revision, passage_locale, passages::text "
                        + "from narration_publication where campaign_id = ? and operation_id = any(?)", row -> {
                            narrations.put(row.getObject(1, UUID.class), new Object[] {row.getLong(2), row.getString(3),
                                    JSON.readTree(row.getString(4))});
                        }, campaign, missing);
            }
        }
        for (var entry : narrations.entrySet()) {
            Object[] row = entry.getValue();
            var passages = new ArrayList<Map<String, Object>>();
            for (JsonNode passage : (JsonNode) row[2]) {
                var spoken = new LinkedHashMap<String, Object>();
                spoken.put("speaker", passage.path("speakerName").isString() ? passage.path("speakerName").asString() : null);
                spoken.put("text", passage.path("text").asString());
                passages.add(spoken);
            }
            drafts.add(new Draft("narration:" + entry.getKey(), Section.HISTORY, Standing.NARRATION, false,
                    (String) row[1], fields("operation", "operation:" + entry.getKey(), "revision", row[0], "passages",
                            passages), List.of(), Set.of(), (long) row[0], null, 1)
                    .text(matchedNarrations.contains(entry.getKey())));
        }
    }

    /** One public event as a draft; an event without a supported statement keeps only its type. */
    private static Draft eventDraft(StoredEvent event, SupportedLocale locale, Map<UUID, String> names, boolean mandatory) {
        var statement = EventStatements.state(locale, event.type(), event.version(), event.payload(), names);
        var links = new HashSet<String>();
        EventStatements.referencedActors(event.payload()).forEach(actor -> links.add("actor:" + actor));
        var terms = new ArrayList<String>();
        for (String field : List.of("from", "to")) {
            if (event.payload().path(field).isString()) {
                links.add("location:" + event.payload().path(field).asString());
                terms.add(event.payload().path(field).asString());
            }
        }
        if (event.payload().path("abilityId").isString()) {
            terms.add(event.payload().path("abilityId").asString());
        }
        var data = fields("sequence", event.sequence(), "revision", event.revision(), "type", event.type(),
                "statement", statement.orElse(null));
        return new Draft("event:" + event.sequence(), Section.HISTORY, Standing.EVENT, mandatory,
                statement.isPresent() ? locale.tag() : null, data, terms, links, event.revision(), null, 2);
    }

    /**
     * Claims of the audience view whose statement matches the query.
     *
     * @return matching claim ids as strings
     */
    private Set<String> textClaims(UUID campaign, String query, KnowledgeView.Audience view) {
        var ids = new ArrayList<UUID>();
        view.beliefs().forEach(belief -> ids.add(belief.claim().claimId()));
        view.common().forEach(common -> ids.add(common.claim().claimId()));
        return textRows(campaign, query, "knowledge_claim", "statement", "statement_locale", ids);
    }

    /** @return ids of the given commitments whose terms match the query */
    private Set<String> textCommitments(UUID campaign, String query, List<UUID> ids) {
        return textRows(campaign, query, "actor_commitment", "terms", "terms_locale", ids);
    }

    /**
     * Full-text match restricted to already authorized row ids. Table and column names are
     * constants of this class, never caller input.
     *
     * @return matching ids as strings, at most {@value #TEXT_MATCHES}, best rank first
     */
    private Set<String> textRows(UUID campaign, String query, String table, String column, String language,
            Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.queryForList("select t.id::text from " + table + " t cross join lateral (select "
                + config("t." + language) + " as cfg) l where t.campaign_id = ? and t.id = any(?) and t." + column
                + " is not null and to_tsvector(l.cfg, t." + column + ") @@ websearch_to_tsquery(l.cfg, ?) "
                + "order by ts_rank(to_tsvector(l.cfg, t." + column + "), websearch_to_tsquery(l.cfg, ?)) desc, t.id "
                + "limit ?", String.class, campaign, ids.toArray(new UUID[0]), query, query, TEXT_MATCHES));
    }

    /** @return the identifier part of every focus reference matching the pattern */
    private static List<String> focusIds(Set<String> focus, Pattern pattern) {
        var out = new ArrayList<String>();
        for (String ref : focus) {
            var matcher = pattern.matcher(ref);
            if (matcher.matches()) {
                out.add(matcher.group(1));
            }
        }
        return out;
    }

    /** @return SQL choosing the stemming configuration of a language expression ({@code it} or English) */
    private static String config(String languageExpression) {
        return "case " + languageExpression + " when 'it' then 'italian'::regconfig else 'english'::regconfig end";
    }

    /** @return display names of campaign actors, one bounded query */
    private Map<UUID, String> names(UUID campaign, Collection<UUID> actors) {
        var names = new HashMap<UUID, String>();
        if (actors.isEmpty()) {
            return names;
        }
        jdbc.query("select id, display_name from actor where campaign_id = ? and id = any(?)", row -> {
            names.put(row.getObject(1, UUID.class), row.getString(2));
        }, campaign, new LinkedHashSet<>(actors).toArray(new UUID[0]));
        return names;
    }

    /** @return ordered fields of a claim proposition with actor names for actor subjects and objects */
    private static Map<String, Object> claimFields(KnowledgeView.ClaimContent claim, Map<UUID, String> names) {
        return fields("claim", "claim:" + claim.claimId(), "subject", claim.subjectRef(), "subjectName",
                nameOf(claim.subjectRef(), names), "predicate", wire(claim.predicate()), "qualifier", claim.qualifier(),
                "value", claim.valueJson() == null ? null : JSON.readTree(claim.valueJson()), "object", claim.objectRef(),
                "objectName", nameOf(claim.objectRef(), names), "statement", claim.statement());
    }

    /** @return references a claim names, for ID-tier matching */
    private static Set<String> claimLinks(KnowledgeView.ClaimContent claim) {
        var links = new HashSet<String>();
        links.add("claim:" + claim.claimId());
        links.add(claim.subjectRef());
        if (claim.objectRef() != null) {
            links.add(claim.objectRef());
        }
        return links;
    }

    /** @return definition ids a claim names */
    private static List<String> claimTerms(KnowledgeView.ClaimContent claim) {
        return terms(claim.subjectRef(), claim.objectRef());
    }

    /** @return definition ids of {@code location:} and {@code definition:} references; others are skipped */
    private static List<String> terms(String... refs) {
        var out = new ArrayList<String>();
        for (String ref : refs) {
            if (ref != null && ref.startsWith("location:")) {
                out.add(ref.substring("location:".length()));
            } else if (ref != null && ref.startsWith("definition:")) {
                out.add(ref.substring("definition:".length()));
            }
        }
        return out;
    }

    /** @return the definition ids named by the items, in order, without duplicates */
    private static List<String> terms(List<ContextItem> items) {
        var out = new LinkedHashSet<String>();
        items.forEach(item -> out.addAll(item.terms()));
        return List.copyOf(out);
    }

    /** Add the actor of an {@code actor:<uuid>} reference. */
    private static void actorOf(String ref, Set<UUID> actors) {
        if (ref != null) {
            var matcher = ACTOR.matcher(ref);
            if (matcher.matches()) {
                actors.add(UUID.fromString(matcher.group(1)));
            }
        }
    }

    /** @return the display name of an actor reference, or null */
    private static String nameOf(String ref, Map<UUID, String> names) {
        if (ref == null) {
            return null;
        }
        var matcher = ACTOR.matcher(ref);
        return matcher.matches() ? names.get(UUID.fromString(matcher.group(1))) : null;
    }

    /** Importance of a stance: firmer knowledge first. */
    private static int stanceWeight(KnowledgeChange.Stance stance) {
        return switch (stance) {
            case KNOWS -> 6;
            case BELIEVES -> 5;
            case SUSPECTS -> 4;
            case RUMORED, DOUBTS -> 3;
        };
    }

    /** @return lower-case hyphenated wire form of an enum value */
    private static String wire(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** @return {@code location:<id>} or null */
    private static String locationRef(String location) {
        return location == null ? null : "location:" + location;
    }

    /** @return {@code actor:<uuid>} or null */
    private static String actorRef(UUID actor) {
        return actor == null ? null : "actor:" + actor;
    }

    /** @return an ordered field map from alternating keys and values; null values are kept */
    private static Map<String, Object> fields(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            out.put((String) pairs[index], pairs[index + 1]);
        }
        return out;
    }

    /** One stored public event. */
    private record StoredEvent(long sequence, String type, int version, JsonNode payload, long revision) {
    }

    /** Everything read inside the snapshot. */
    private record Gathered(ContextEnvelope.Stamp stamp, UUID contentRevision, List<ContextItem> candidates) {
    }

    /**
     * A candidate before its tier is known.
     *
     * @param links references the record names (subjects, objects, parties); one in the focus makes it ID tier
     * @param revision revision the record was written or observed at; the focus operation's revision makes it causal
     * @param informant who told the audience; a focus actor makes it causal
     * @param importance 0..9, ranked before recency
     */
    private record Draft(String ref, Section section, Standing standing, boolean mandatory, String language,
            Map<String, Object> fields, List<String> terms, Set<String> links, long revision, UUID informant,
            int importance, boolean matched) {
        Draft(String ref, Section section, Standing standing, boolean mandatory, String language,
                Map<String, Object> fields, List<String> terms, Set<String> links, long revision, UUID informant,
                int importance) {
            this(ref, section, standing, mandatory, language, fields, terms, links, revision, informant, importance, false);
        }

        /** @return this draft, marked as a full-text match when {@code matched} */
        Draft text(boolean matched) {
            return matched ? new Draft(ref, section, standing, mandatory, language, fields, terms, links, revision,
                    informant, importance, true) : this;
        }

        /** @return the ranked item for this focus */
        ContextItem finish(Set<String> focus, Long focusRevision) {
            Tier tier;
            if (mandatory) {
                tier = Tier.REQUIRED;
            } else if (focus.contains(ref) || links.stream().anyMatch(focus::contains)) {
                tier = Tier.ID;
            } else if ((focusRevision != null && revision == focusRevision)
                    || (informant != null && focus.contains("actor:" + informant))) {
                tier = Tier.CAUSAL;
            } else if (matched) {
                tier = Tier.TEXT;
            } else {
                tier = Tier.RECENT;
            }
            return new ContextItem(ref, section, standing, mandatory, tier, importance * IMPORTANCE + revision,
                    language, fields, terms);
        }
    }

    /**
     * What to assemble.
     *
     * @param adventureId adventure owning the campaign
     * @param campaignId campaign the caller authorized
     * @param role task role; must permit the audience
     * @param audience disclosure audience
     * @param operationId committed focus operation whose public events are mandatory, or null
     * @param focusRefs explicit references the task is about, at most {@link #MAX_FOCUS}
     * @param query full-text query (for example the player's words), at most {@link #MAX_QUERY}
     *        characters, or null for none
     * @param instructionTokens estimated tokens of the task instructions rendered around the context
     * @param budget input budget
     * @param policyHash audience policy hash of the caller
     */
    public record Request(UUID adventureId, UUID campaignId, AiRole role, Audience audience, UUID operationId,
            List<String> focusRefs, String query, int instructionTokens, ContextBudget budget, String policyHash) {
        /** @throws IllegalArgumentException for out-of-bound or malformed values or a role/audience mismatch */
        public Request {
            Objects.requireNonNull(adventureId, "adventureId");
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(policyHash, "policyHash");
            if (!role.permits(audience)) {
                throw new IllegalArgumentException("role " + role.wire() + " does not permit audience " + audience.wire());
            }
            focusRefs = List.copyOf(focusRefs);
            if (focusRefs.size() > MAX_FOCUS || focusRefs.stream().anyMatch(ref -> !REFERENCE.matcher(ref).matches())) {
                throw new IllegalArgumentException("focus must hold at most " + MAX_FOCUS + " stable references");
            }
            if (query != null) {
                query = query.strip();
                if (query.isEmpty()) {
                    query = null;
                } else if (query.length() > MAX_QUERY) {
                    throw new IllegalArgumentException("query longer than " + MAX_QUERY + " characters");
                }
            }
            if (instructionTokens < 0) {
                throw new IllegalArgumentException("instruction estimate must not be negative");
            }
        }
    }

    /** Outcome of {@link #assemble}. */
    public sealed interface Result permits Assembled, TooLarge {
    }

    /**
     * A context within budget.
     *
     * @param envelope the stamped envelope
     */
    public record Assembled(ContextEnvelope envelope) implements Result {
    }

    /**
     * Mandatory material does not fit; the caller must reduce scope, choose a larger approved
     * profile or report {@value #TOO_LARGE}. Nothing was truncated.
     *
     * @param stamp what was read
     * @param overflow required and available tokens with the mandatory cost of each section
     */
    public record TooLarge(ContextEnvelope.Stamp stamp, ContextSelection.TooLarge overflow) implements Result {
        /** @return {@value #TOO_LARGE} */
        public String code() {
            return TOO_LARGE;
        }
    }
}
