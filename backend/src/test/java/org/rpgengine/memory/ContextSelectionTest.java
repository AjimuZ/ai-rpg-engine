package org.rpgengine.memory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.contract.GlossaryTerm;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.memory.ContextItem.Section;
import org.rpgengine.memory.ContextItem.Standing;
import org.rpgengine.memory.ContextItem.Tier;
import org.rpgengine.presentation.SupportedLocale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure proof of the P09.2 budget arithmetic, deterministic selection and envelope invariants
 * (docs/08 sections 5 and 6): the estimate is a subadditive upper bound, mandatory material is
 * never truncated and overflows as a controlled result, optional material is dropped from the
 * lowest rank and the latest section first and always counted, epistemic standings and audiences
 * cannot be mixed, and rendering is deterministic.
 */
class ContextSelectionTest {
    private static final String HASH = "a".repeat(64);

    /** Pure fixture; no Spring context. */
    ContextSelectionTest() { }

    /** One token per two UTF-8 bytes rounded up, subadditive, and multi-byte text costs more. */
    @Test
    void theEstimatorIsAConservativeSubadditiveBound() {
        assertEquals(0, TokenEstimator.tokens(0));
        assertEquals(1, TokenEstimator.tokens(1));
        assertEquals(1, TokenEstimator.tokens(2));
        assertEquals(2, TokenEstimator.tokens(3));
        assertEquals(2, TokenEstimator.tokens("àà"), "à is two UTF-8 bytes");
        assertEquals(1, TokenEstimator.tokens("aa"));
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.tokens(-1));
        for (int a = 0; a < 9; a++) {
            for (int b = 0; b < 9; b++) {
                assertTrue(TokenEstimator.tokens(a + b) <= TokenEstimator.tokens(a) + TokenEstimator.tokens(b));
            }
        }
    }

    /**
     * The standard profile follows docs/08 section 6; a smaller model shrinks input and allowances
     * proportionally; impossible budgets are refused; only privileged audiences get planning room.
     */
    @Test
    void budgetsDeriveFromTheModelAllowanceAndRejectImpossibleProfiles() {
        var standard = ContextBudget.standard(false);
        assertEquals(12_000, standard.inputTokens());
        assertEquals(2_000, standard.outputReserve());
        assertEquals(0, standard.allowance(Section.PLANNING));
        assertEquals(1_000, ContextBudget.standard(true).allowance(Section.PLANNING));
        assertEquals(standard, ContextBudget.forModel(200_000, false), "the application cap wins over a large model");
        var small = ContextBudget.forModel(9_500, false);
        assertEquals(6_000, small.inputTokens(), "9,500 - 2,000 output - 1,000 safety - 500 protocol");
        assertEquals(1_500, small.allowance(Section.STATE));
        assertThrows(IllegalArgumentException.class, () -> ContextBudget.forModel(3_500, false));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(0, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(10, 0, 0, Map.of(Section.STATE, 11)));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(10, -1, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(10, 0, 0, Map.of(Section.STATE, -1)));
    }

    /**
     * An item is mandatory exactly in the required tier, an assumption lives only in the planning
     * section, and only supported languages and stable references are accepted.
     */
    @Test
    void itemsKeepTheirStandingSectionAndTierConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new ContextItem("actor:a", Section.STATE, Standing.STATE,
                true, Tier.ID, 0, null, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextItem("actor:a", Section.STATE, Standing.STATE,
                false, Tier.REQUIRED, 0, null, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextItem("assumption:a", Section.KNOWLEDGE,
                Standing.ASSUMPTION, false, Tier.RECENT, 0, null, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextItem("claim:a", Section.PLANNING,
                Standing.FACT, false, Tier.RECENT, 0, null, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextItem("claim:a", Section.KNOWLEDGE,
                Standing.BELIEF, false, Tier.RECENT, 0, "fr", Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextItem("bad ref", Section.KNOWLEDGE,
                Standing.BELIEF, false, Tier.RECENT, 0, null, Map.of(), List.of()));
        var item = optional("belief:x", Section.KNOWLEDGE, Standing.BELIEF, Tier.TEXT, 3, "it", "Il guardiano mente.");
        assertEquals(List.of("ref", "standing", "lang", "text"), List.copyOf(item.rendered().keySet()));
        assertEquals("belief", item.rendered().get("standing"));
        assertEquals("common-knowledge", Standing.COMMON_KNOWLEDGE.wire());
        assertFalse(Standing.BELIEF.admitted());
        assertFalse(Standing.NARRATION.admitted());
        assertFalse(Standing.ASSUMPTION.admitted());
        assertTrue(Standing.ASSUMPTION.privilegedOnly());
        assertTrue(Standing.FACT.privilegedOnly());
        assertFalse(Standing.COMMON_KNOWLEDGE.privilegedOnly());
    }

    /**
     * Duplicates keep the best-ranked copy; ranking is tier, then score, then reference; when
     * room runs out a section stops at its first item that does not fit and later sections lose
     * allowance first; every dropped candidate is counted.
     */
    @Test
    void selectionDeduplicatesRanksAndDropsLowRankedOptionalHistoryFirst() {
        var candidates = new ArrayList<ContextItem>();
        candidates.add(mandatory("actor:self", Section.STATE, "x".repeat(40)));
        candidates.add(optional("commitment:1", Section.OBLIGATIONS, Standing.OBLIGATION, Tier.RECENT, 9, null, "o"));
        candidates.add(optional("claim:a", Section.KNOWLEDGE, Standing.COMMON_KNOWLEDGE, Tier.RECENT, 1, null, "a"));
        candidates.add(optional("claim:a", Section.KNOWLEDGE, Standing.COMMON_KNOWLEDGE, Tier.ID, 0, null, "a"));
        candidates.add(optional("claim:b", Section.KNOWLEDGE, Standing.COMMON_KNOWLEDGE, Tier.TEXT, 0, null, "b"));
        for (int index = 0; index < 6; index++) {
            candidates.add(optional("event:" + (index + 1), Section.HISTORY, Standing.EVENT, Tier.RECENT, index, null,
                    "e".repeat(20)));
        }
        long state = ContextSelection.cost(candidates.get(0));
        long obligation = ContextSelection.cost(candidates.get(1));
        long knowledge = ContextSelection.cost(candidates.get(3)) + ContextSelection.cost(candidates.get(4));
        long event = ContextSelection.cost(candidates.get(5));
        int fixed = 10;
        // Room for the mandatory item, the obligation, both claims and exactly two events.
        int input = (int) (fixed + state + obligation + knowledge + 2 * event + 1);
        var budget = new ContextBudget(input, 0, 0, Map.of());
        var result = (ContextSelection.Selected) ContextSelection.select(candidates, budget, fixed, false);
        assertEquals(List.of("actor:self"), refs(result.items().get(Section.STATE)));
        assertEquals(List.of("commitment:1"), refs(result.items().get(Section.OBLIGATIONS)));
        assertEquals(List.of("claim:a", "claim:b"), refs(result.items().get(Section.KNOWLEDGE)), "ID tier before TEXT");
        assertEquals(Tier.ID, result.items().get(Section.KNOWLEDGE).getFirst().tier(), "the better duplicate wins");
        assertEquals(List.of("event:6", "event:5"), refs(result.items().get(Section.HISTORY)), "highest score first");
        assertEquals(4, result.omitted().get(Section.HISTORY));
        assertEquals(0, result.omitted().get(Section.KNOWLEDGE));
        assertEquals(1, result.duplicates());
        assertTrue(result.tokens() <= input);
        assertEquals(result, ContextSelection.select(candidates.reversed(), budget, fixed, false), "order-independent");
    }

    /**
     * Section allowances reserve optional room in section order: when everything is optional and
     * the history allowance is spent, knowledge keeps its allowance and history overflow only uses
     * headroom.
     */
    @Test
    void sectionAllowancesProtectEarlierSectionsFromLaterOnes() {
        var candidates = new ArrayList<ContextItem>();
        for (int index = 0; index < 5; index++) {
            candidates.add(optional("event:" + (index + 1), Section.HISTORY, Standing.EVENT, Tier.ID, index, null,
                    "e".repeat(20)));
        }
        candidates.add(optional("claim:a", Section.KNOWLEDGE, Standing.COMMON_KNOWLEDGE, Tier.RECENT, 0, null,
                "k".repeat(20)));
        long each = ContextSelection.cost(candidates.getFirst());
        long claim = ContextSelection.cost(candidates.getLast());
        // Knowledge reserves exactly its claim; history gets two events and no headroom.
        var budget = new ContextBudget((int) (claim + 2 * each), 0, 0, Map.of(Section.KNOWLEDGE, (int) claim,
                Section.HISTORY, (int) (2 * each)));
        var result = (ContextSelection.Selected) ContextSelection.select(candidates, budget, 0, false);
        assertEquals(List.of("claim:a"), refs(result.items().get(Section.KNOWLEDGE)));
        assertEquals(2, result.items().get(Section.HISTORY).size());
        assertEquals(3, result.omitted().get(Section.HISTORY));
    }

    /**
     * Mandatory material that cannot fit is a controlled overflow naming the required tokens per
     * section, never a truncated context; extra charges count toward it; a limited audience
     * offered a fact or an assumption is an assembler defect.
     */
    @Test
    void mandatoryOverflowIsControlledAndPrivilegedMaterialIsRefusedForLimitedAudiences() {
        var self = mandatory("actor:self", Section.STATE, "x".repeat(100));
        long cost = ContextSelection.cost(self);
        var fits = ContextSelection.select(List.of(self), new ContextBudget((int) cost + 5, 0, 0, Map.of()), 5, false);
        assertInstanceOf(ContextSelection.Selected.class, fits);
        var overflow = (ContextSelection.TooLarge) ContextSelection.select(List.of(self),
                new ContextBudget((int) cost + 5, 0, 0, Map.of()), 5, item -> 1, false);
        assertEquals(cost + 6, overflow.requiredTokens());
        assertEquals(cost + 1, overflow.mandatoryBySection().get(Section.STATE));
        assertThrows(IllegalArgumentException.class, () -> ContextSelection.select(List.of(self),
                ContextBudget.standard(false), 0, item -> -1, false));
        var fact = optional("claim:f", Section.KNOWLEDGE, Standing.FACT, Tier.RECENT, 0, null, "f");
        var assumption = optional("assumption:p", Section.PLANNING, Standing.ASSUMPTION, Tier.RECENT, 0, null, "p");
        assertThrows(IllegalArgumentException.class, () -> ContextSelection.select(List.of(fact),
                ContextBudget.standard(false), 0, false));
        assertThrows(IllegalArgumentException.class, () -> ContextSelection.select(List.of(assumption),
                ContextBudget.standard(false), 0, false));
        var privileged = (ContextSelection.Selected) ContextSelection.select(List.of(fact, assumption),
                ContextBudget.standard(true), 0, true);
        assertEquals(List.of("assumption:p"), refs(privileged.items().get(Section.PLANNING)));
    }

    /**
     * The stamp refuses a role that does not permit the audience and a holder mismatch; a limited
     * envelope refuses truth and assumptions; rendering is deterministic, lists only the standings
     * present, marks an unavailable glossary and counts omitted terms only beyond the bound.
     */
    @Test
    void envelopesEnforceAudiencePolicyAndRenderDeterministically() {
        UUID holder = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> stamp(AiRole.NARRATOR, Audience.PRIVILEGED, null));
        assertThrows(IllegalArgumentException.class, () -> stamp(AiRole.NARRATOR, Audience.PUBLIC, null));
        assertThrows(IllegalArgumentException.class, () -> stamp(AiRole.QUEST_PLANNER, Audience.PRIVILEGED, holder));
        var publicStamp = stamp(AiRole.NARRATOR, Audience.PUBLIC, holder);
        var fact = optional("claim:f", Section.KNOWLEDGE, Standing.FACT, Tier.RECENT, 0, null, "f");
        assertThrows(IllegalArgumentException.class, () -> envelope(publicStamp, List.of(fact), List.of(), true, 0));

        var belief = optional("belief:b", Section.KNOWLEDGE, Standing.BELIEF, Tier.RECENT, 0, "en", "The key is lost.");
        var term = new GlossaryTerm("grove.name", GlossaryTerm.Kind.TERM, "Lantern Grove", List.of("Boschetto"));
        var first = envelope(publicStamp, List.of(belief), List.of(term), true, 0);
        var again = envelope(publicStamp, List.of(belief), List.of(term), true, 0);
        assertEquals(first.json(), again.json());
        assertEquals(first.digest(), again.digest());
        assertEquals(64, first.digest().length());
        @SuppressWarnings("unchecked")
        var legend = (Map<String, Object>) first.render().get("standings");
        assertEquals(List.of("belief"), List.copyOf(legend.keySet()));
        assertTrue(first.json().contains("\"glossaryStatus\":\"bound\""));
        assertTrue(first.json().contains("\"avoid\":[\"Boschetto\"]"));
        assertFalse(first.partial());
        assertTrue(ContextEnvelope.overhead(publicStamp, ContextBudget.standard(false), true)
                + ContextSelection.cost(belief) + ContextEnvelope.glossaryCost(term)
                >= TokenEstimator.tokens(first.json()), "overhead plus charges bounds the rendering");

        var unavailable = envelope(publicStamp, List.of(belief), List.of(), false, 0);
        assertTrue(unavailable.json().contains("\"glossaryStatus\":\"unavailable\""));
        assertThrows(IllegalArgumentException.class, () -> envelope(publicStamp, List.of(), List.of(term), false, 0));
        assertThrows(IllegalArgumentException.class, () -> envelope(publicStamp, List.of(), List.of(term), true, 1));
        assertThrows(IllegalArgumentException.class, () -> envelope(publicStamp, List.of(), List.of(term, term), true, 0));

        var privileged = stamp(AiRole.QUEST_PLANNER, Audience.PRIVILEGED, null);
        var assumption = optional("assumption:p", Section.PLANNING, Standing.ASSUMPTION, Tier.RECENT, 0, null, "p");
        var full = envelope(privileged, List.of(fact, assumption), List.of(), true, 0);
        assertTrue(full.json().contains("A private planning assumption"));
        assertEquals("quest-planner", ((Map<?, ?>) full.render().get("stamp")).get("role"));
    }

    /** @return a stamp at revision 3 with one epoch */
    private static ContextEnvelope.Stamp stamp(AiRole role, Audience audience, UUID holder) {
        var epochs = new TreeMap<String, Long>(Map.of("knowledge:claims", 2L));
        return new ContextEnvelope.Stamp(UUID.randomUUID(), UUID.randomUUID(), 0, 3, 60, role, audience, holder, null,
                HASH, HASH, HASH, SupportedLocale.EN, 0, 0, epochs);
    }

    /** @return an envelope over the items, every section present */
    private static ContextEnvelope envelope(ContextEnvelope.Stamp stamp, List<ContextItem> items,
            List<GlossaryTerm> glossary, boolean available, int glossaryOmitted) {
        var bySection = new EnumMap<Section, List<ContextItem>>(Section.class);
        var omitted = new EnumMap<Section, Integer>(Section.class);
        for (Section section : Section.values()) {
            bySection.put(section, items.stream().filter(item -> item.section() == section).toList());
            omitted.put(section, 0);
        }
        return new ContextEnvelope(stamp, ContextBudget.standard(stamp.holderId() == null), bySection, omitted,
                glossary, available, glossaryOmitted, 100);
    }

    /** @return a mandatory state item with one text field */
    private static ContextItem mandatory(String ref, Section section, String text) {
        return new ContextItem(ref, section, Standing.STATE, true, Tier.REQUIRED, 0, null, Map.of("text", text),
                List.of());
    }

    /** @return an optional item with one text field */
    private static ContextItem optional(String ref, Section section, Standing standing, Tier tier, long score,
            String language, String text) {
        return new ContextItem(ref, section, standing, false, tier, score, language, Map.of("text", text), List.of());
    }

    /** @return the references in order */
    private static List<String> refs(List<ContextItem> items) {
        return items.stream().map(ContextItem::ref).toList();
    }
}
