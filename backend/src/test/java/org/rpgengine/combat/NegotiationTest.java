package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rpgengine.combat.EncounterOutcomeTest.active;
import static org.rpgengine.combat.EncounterOutcomeTest.after;
import static org.rpgengine.combat.EncounterOutcomeTest.fight;
import static org.rpgengine.combat.EncounterOutcomeTest.foe;
import static org.rpgengine.combat.EncounterOutcomeTest.library;
import static org.rpgengine.combat.EncounterOutcomeTest.phases;
import static org.rpgengine.combat.EncounterOutcomeTest.player;
import static org.rpgengine.combat.EncounterOutcomeTest.script;
import static org.rpgengine.combat.EncounterOutcomeTest.tick;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pure acceptance of negotiation, surrender and agreement (docs/11 sections 7.1 and 13, QA-11, QA-13). A social attempt pays its
 * declared cost and draws only its declared check, a response comes from recorded dice and frozen data alone and never ends a
 * fight or binds the player, and only an explicit answer naming the offer applies its terms. The PostgreSQL path is
 * {@code NegotiationCommandsTest}. A fixture player adds force 2.
 */
class NegotiationTest {
    private static final Negotiation.Terms PASS = new Negotiation.Terms("pass", Negotiation.Terms.Kind.TRUCE, 0);
    private static final Negotiation.Terms TOLL = new Negotiation.Terms("toll", Negotiation.Terms.Kind.TRUCE, 2);
    private static final Negotiation.Terms YIELD = new Negotiation.Terms("yield", Negotiation.Terms.Kind.RECIPIENT_SURRENDERS, 0);
    private static final Negotiation.Terms CAPTIVE = new Negotiation.Terms("captive", Negotiation.Terms.Kind.PLAYER_SURRENDERS, 0);
    private static final Negotiation.Check AT_14 = new Negotiation.Check("force", null, 0, 14);
    /** A negotiation with a check and a counteroffer, the shape of the worked talk of docs/20. */
    private static final EncounterAction PARLEY = negotiation("parley", AT_14, PASS, TOLL);
    /** No check and no counteroffer: the proposed terms are always on the table. */
    private static final EncounterAction PEACE = negotiation("peace", null, PASS, null);
    /** No check with a counteroffer: the recipient always answers with its own terms. */
    private static final EncounterAction HAGGLE = negotiation("haggle", null, PASS, TOLL);
    private static final EncounterAction DEMAND = negotiation("demand", null, YIELD, null);
    private static final EncounterAction GIVE_UP = negotiation("give-up", null, CAPTIVE, null);
    /** A check against the recipient's Resistance, with no terms. */
    private static final EncounterAction SWAY = new EncounterAction("sway", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.PERSUADE,
            EncounterAction.Recipients.ANY, 1, null, null, new Negotiation.Check("force", null, 0, null), null, null);
    /** A social action that declares nothing beyond its cost, the shape before P11.5. */
    private static final EncounterAction PLAIN = new EncounterAction("plain", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.NEGOTIATE,
            EncounterAction.Recipients.HOSTILE, 1, null, null);
    private static final EncounterObjective WIN = new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0);
    private static final EncounterObjective HOLD = new EncounterObjective("hold", EncounterObjective.Kind.SURVIVE, null, null, 9);

    /** Construct a stateless fixture. */
    NegotiationTest() {
    }

    private static EncounterAction negotiation(String id, Negotiation.Check check, Negotiation.Terms terms, Negotiation.Terms counter) {
        return new EncounterAction(id, EncounterAction.Kind.SOCIAL, EncounterAction.Intent.NEGOTIATE, EncounterAction.Recipients.HOSTILE,
                1, null, null, check, terms, counter);
    }

    // ---- the response ----------------------------------------------------------------------------------------------

    /**
     * Every pair of recorded dice against an independent model: below the difficulty is a refusal, a margin below the profile's
     * six is the counteroffer, and from six up the proposed terms. Without a counteroffer any success puts the terms on the table.
     */
    @Test
    void everyRollGivesTheDeclaredResponse() {
        var state = talking(PARLEY);
        var plain = talking(negotiation("parley", AT_14, PASS, null));
        for (int first = 1; first <= 10; first++) {
            for (int second = 1; second <= 10; second++) {
                int margin = first + second + 2 - 14;
                var offer = CombatScheduler.apply(state, attempt("parley"), script(first, second)).last().openOffer();
                if (margin < 0) {
                    assertNull(offer, first + "+" + second);
                } else {
                    assertEquals(margin < 6 ? TOLL : PASS, offer.terms(), first + "+" + second);
                    assertEquals(margin < 6 ? Negotiation.Response.COUNTEROFFERED : Negotiation.Response.OFFERED, offer.response());
                }
                var single = CombatScheduler.apply(plain, attempt("parley"), script(first, second)).last().openOffer();
                assertEquals(margin < 0 ? null : PASS, single == null ? null : single.terms(), first + "+" + second);
            }
        }
    }

    /** Without a check the response follows from frozen data and draws nothing; the decision itself is one pure function. */
    @Test
    void withoutACheckFrozenDataDecides() {
        assertEquals(TOLL, CombatScheduler.apply(talking(HAGGLE), attempt("haggle"), script()).last().openOffer().terms());
        assertEquals(PASS, CombatScheduler.apply(talking(PEACE), attempt("peace"), script()).last().openOffer().terms());
        var roll = new CombatMath.Roll(10, true, 0, false);
        assertNull(Negotiation.respond(PLAIN, null), "an action that proposes nothing gets no response");
        assertNull(Negotiation.respond(SWAY, roll), "nor does a check alone");
        assertEquals(Negotiation.Response.COUNTEROFFERED, Negotiation.respond(HAGGLE, null));
        assertThrows(IllegalArgumentException.class, () -> Negotiation.respond(PARLEY, null), "a declared check needs its roll");
    }

    /** A refusal costs what the attempt declares and leaves the fight at the same activation with nothing on the table (QA-13). */
    @Test
    void aRefusalChangesOnlyTheCost() {
        var state = talking(PARLEY);
        var plan = CombatScheduler.apply(state, attempt("parley"), script(5, 6));
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(List.of("INTERACTION_ATTEMPTED", "SOCIAL_CHECK_RESOLVED", "SOCIAL_CHECK_CALCULATED", "NEGOTIATION_RESPONDED"), types(plan));
        assertEquals(2, plan.last().actionPoints(), "the declared cost, 3 to 2");
        assertEquals(0, plan.last().cursor());
        assertEquals("REFUSED", note(plan, "NEGOTIATION_RESPONDED").payload().get("response"));
        assertEquals(Set.of("encounterId", "round", "participantId", "actionId", "response"),
                note(plan, "NEGOTIATION_RESPONDED").payload().keySet());
        assertTrue(plan.participants().isEmpty(), "no participant changed");
        assertEquals(0L, plan.elapsedSeconds());
        var stored = after(state, plan);
        assertNull(stored.openOffer());
        assertNull(stored.agreement());
        assertNull(EncounterOutcome.decide(stored));
        assertTrue(stored.playerDecision());
    }

    /** The check draws two d10 at the operation's next ordinals, and only the internal record states what it met. */
    @Test
    void aCheckIsRecordedAndHidesItsDifficulty() {
        var state = talking(PARLEY);
        var plan = CombatScheduler.apply(state, attempt("parley"), script(9, 9));
        var shown = note(plan, "SOCIAL_CHECK_RESOLVED");
        assertFalse(shown.internal());
        assertTrue(shown.byPlayer());
        assertEquals(List.of(9, 9), shown.payload().get("dice"));
        assertEquals(20, shown.payload().get("total"));
        assertEquals(true, shown.payload().get("success"));
        assertEquals(List.of(0, 1), shown.payload().get("drawOrdinals"));
        assertFalse(shown.payload().containsKey("difficulty"));
        assertFalse(shown.payload().containsKey("margin"));
        var hidden = note(plan, "SOCIAL_CHECK_CALCULATED");
        assertTrue(hidden.internal());
        assertEquals(14, hidden.payload().get("difficulty"));
        assertEquals(6, hidden.payload().get("margin"));
        assertEquals(true, hidden.payload().get("strong"));
        assertEquals("force", hidden.payload().get("attributeRole"));
        assertEquals(2, hidden.payload().get("attributeValue"));
        assertEquals(0, hidden.payload().get("rank"));
        var line = (Map<?, ?>) plan.summary().get("social");
        assertEquals(20, line.get("total"));
        assertFalse(line.containsKey("difficulty"));
        assertFalse(line.containsKey("margin"));
    }

    /**
     * A check adds the frozen rank of the player's skill and, without a fixed difficulty, meets the recipient's Resistance with
     * its active modifiers. A check without terms ends there: it spends its cost and changes nothing else.
     */
    @Test
    void aCheckAgainstResistanceAddsTheFrozenRank() {
        var ward = new CombatEffect("ward", "ward", CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0, false, 0, 0, 3, 0, 0,
                Set.of(), List.of(), false);
        var trained = new EncounterAction("sway", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.PERSUADE, EncounterAction.Recipients.ANY,
                1, null, null, new Negotiation.Check("force", "talk", 3, null), null, null);
        var state = library(with(fight(List.of(WIN), player(), foe("foe").holding(active(ward, 1))), trained), ward);
        var plan = CombatScheduler.apply(state, attempt("sway"), script(4, 4));
        assertEquals(13, note(plan, "SOCIAL_CHECK_CALCULATED").payload().get("difficulty"), "Resistance 10 and the ward's 3");
        assertEquals(13, note(plan, "SOCIAL_CHECK_RESOLVED").payload().get("total"), "4 + 4 + force 2 + rank 3");
        assertEquals(true, note(plan, "SOCIAL_CHECK_RESOLVED").payload().get("success"));
        assertEquals(0, count(plan, "NEGOTIATION_RESPONDED"));
        assertNull(plan.last().openOffer());
        assertTrue(plan.participants().isEmpty());
        assertEquals(EncounterPhase.AWAITING_ACTION, plan.last().phase());
    }

    /** An action that declares nothing still only pays its cost, with no draw and no trace in the receipt, as before P11.5. */
    @Test
    void anActionDeclaringNothingStaysAPaidAttempt() {
        var plan = CombatScheduler.apply(talking(PLAIN), attempt("plain"), script());
        assertEquals(List.of("INTERACTION_ATTEMPTED"), types(plan));
        assertFalse(plan.summary().containsKey("social"));
        assertNull(plan.last().openOffer());
    }

    // ---- the offer and the explicit answer ---------------------------------------------------------------------------

    /** A response only puts terms on the table: no status, outcome or point changes for it, and the receipt names them. */
    @Test
    void aResponseNeverEndsTheFightOrBindsThePlayer() {
        var state = talking(HAGGLE);
        var plan = CombatScheduler.apply(state, attempt("haggle"), script());
        var offer = plan.last().openOffer();
        assertEquals(Negotiation.Response.COUNTEROFFERED, offer.response());
        assertEquals("foe", offer.recipient());
        assertEquals("haggle", offer.action());
        var responded = note(plan, "NEGOTIATION_RESPONDED");
        assertFalse(responded.byPlayer());
        assertEquals("foe", responded.payload().get("participantId"));
        assertEquals(offer.id(), responded.payload().get("offerId"));
        assertEquals("toll", responded.payload().get("termsId"));
        assertEquals("truce", responded.payload().get("termsKind"));
        assertEquals(2, responded.payload().get("cost"));
        assertEquals(0, count(plan, "ENCOUNTER_OUTCOME_PENDING"));
        var stored = after(state, plan);
        assertEquals(offer, stored.openOffer());
        assertNull(stored.agreement());
        assertNull(EncounterOutcome.decide(stored));
        assertTrue(stored.playerDecision());
        var listed = (Map<?, ?>) ((Map<?, ?>) plan.summary().get("options")).get("offer");
        assertEquals(offer.id(), listed.get("id"));
        assertEquals(Boolean.TRUE, listed.get("canAccept"));
        assertEquals(Map.of("id", "toll", "kind", "truce", "cost", 2), listed.get("terms"));
    }

    /** Words accept nothing: speech that says so commits as speech, and the terms stay on the table unchanged (QA-13). */
    @Test
    void wordsAcceptNothing() {
        var state = after(talking(HAGGLE), CombatScheduler.apply(talking(HAGGLE), attempt("haggle"), script()));
        var plan = CombatScheduler.apply(state, new TacticalIntent.Speech("foe", "I accept your terms: the fight is over."), script());
        assertEquals(List.of("COMBAT_SPEECH"), types(plan));
        assertEquals(state.openOffer(), plan.last().openOffer());
        assertEquals(EncounterPhase.AWAITING_ACTION, plan.last().phase());
        assertEquals(state.actionPoints(), plan.last().actionPoints());
        assertNull(plan.last().agreement());
        assertNull(EncounterOutcome.decide(after(state, plan)));
    }

    /** Only the explicit acceptance makes the agreement: it pays what the terms declare and ends the fight with everyone standing. */
    @Test
    void anExplicitAcceptanceMakesTheAgreement() {
        var state = after(talking(HAGGLE), CombatScheduler.apply(talking(HAGGLE), attempt("haggle"), script()));
        var plan = CombatScheduler.apply(state, accept(state), script());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.OUTCOME_PENDING), phases(plan));
        assertEquals(List.of("OFFER_ANSWERED", "ENCOUNTER_OUTCOME_PENDING"), types(plan));
        var answered = note(plan, "OFFER_ANSWERED");
        assertTrue(answered.byPlayer());
        assertEquals("ACCEPTED", answered.payload().get("decision"));
        assertEquals("toll", answered.payload().get("termsId"));
        assertEquals("foe", answered.payload().get("recipientId"));
        assertEquals(2, answered.payload().get("actionPoints"));
        assertEquals(0, answered.payload().get("remainingActionPoints"));
        assertEquals(0, plan.last().actionPoints(), "the toll's two points on top of the attempt's one");
        var outcome = note(plan, "ENCOUNTER_OUTCOME_PENDING");
        assertEquals("AGREEMENT", outcome.payload().get("outcome"));
        assertEquals("toll", outcome.payload().get("termsId"));
        assertEquals(new Negotiation.Agreement(state.openOffer().id(), "toll", "foe"), plan.last().agreement());
        assertNull(plan.last().openOffer());
        assertTrue(plan.participants().isEmpty(), "everyone keeps standing");
        assertEquals(0L, plan.elapsedSeconds(), "settlement owns the unfinished round");
        var stored = after(state, plan);
        assertEquals(EncounterOutcome.Kind.AGREEMENT, EncounterOutcome.decide(stored).kind());
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(stored, new TacticalIntent.EndActivation()));
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(stored, new TacticalIntent.AnswerOffer(state.openOffer().id(), true)));
    }

    /** Declining is free and stays at the decision, with no resolving step; the same terms cannot be answered twice. */
    @Test
    void decliningIsFreeAndFinal() {
        var state = after(talking(PEACE), CombatScheduler.apply(talking(PEACE), attempt("peace"), script()));
        var id = state.openOffer().id();
        var plan = CombatScheduler.apply(state, new TacticalIntent.AnswerOffer(id, false), script());
        assertEquals(List.of(EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(List.of("OFFER_ANSWERED"), types(plan));
        assertEquals("DECLINED", note(plan, "OFFER_ANSWERED").payload().get("decision"));
        assertEquals(state.actionPoints(), plan.last().actionPoints());
        assertEquals("DECLINED", ((Map<?, ?>) plan.summary().get("social")).get("decision"));
        var stored = after(state, plan);
        assertNull(stored.openOffer());
        assertNull(EncounterOutcome.decide(stored));
        assertEquals(TacticalRules.Refusal.OFFER, TacticalRules.refusal(stored, new TacticalIntent.AnswerOffer(id, true)));
    }

    /** An answer that names no offer on the table, or that cannot pay, is refused and the rule gate changes nothing (QA-11). */
    @Test
    void anAnswerMustNameTheOfferAndAffordIt() {
        var bare = talking(HAGGLE);
        assertEquals(TacticalRules.Refusal.OFFER, TacticalRules.refusal(bare, new TacticalIntent.AnswerOffer("offer-5", true)), "nothing is on the table");
        var state = after(bare, CombatScheduler.apply(bare, attempt("haggle"), script()));
        assertEquals(TacticalRules.Refusal.OFFER, TacticalRules.refusal(state, new TacticalIntent.AnswerOffer("offer-999", true)), "not these terms");
        assertEquals(TacticalRules.Refusal.OFFER, TacticalRules.refusal(state, new TacticalIntent.AnswerOffer("offer-999", false)));
        assertNull(TacticalRules.refusal(state, accept(state)), "two points are left");

        var poor = at(state, 1);
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(poor, accept(poor)), "the toll costs two");
        assertNull(TacticalRules.refusal(poor, new TacticalIntent.AnswerOffer(poor.openOffer().id(), false)), "declining stays free");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) TacticalRules.options(poor).get("offer")).get("canAccept"));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(poor, accept(poor), script()));

        var stun = new CombatEffect("stun", "stun", CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0, false, 0, 0, 0, 0, 0,
                Set.of(CombatEffect.Prevents.ACTIONS), List.of(), false);
        var stunned = library(with(fight(List.of(WIN), player().holding(active(stun, 1)), foe("foe")), HAGGLE), stun);
        var held = withOffer(stunned, state.openOffer());
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(held, accept(held)), "paying is an action");
        assertNull(TacticalRules.refusal(held, new TacticalIntent.AnswerOffer(held.openOffer().id(), false)), "a decision is not");
        var free = withOffer(stunned, new Negotiation.Offer("offer-1", "peace", "foe", PASS, Negotiation.Response.OFFERED));
        assertNull(TacticalRules.refusal(free, accept(free)), "terms with no price are a decision alone");
    }

    /** Ambiguous input changes nothing: no recipient, an unknown action, the wrong side or out of voice is refused before any cost. */
    @Test
    void ambiguousAttemptsAreRefused() {
        var state = with(fight(List.of(WIN), player(), foe("foe"), EncounterOutcomeTest.ally("ally"), foe("away").zone("far").attacks()),
                PARLEY, new EncounterAction("cover", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.REQUEST, EncounterAction.Recipients.ALLY, 1, null, null));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Interact("parley", null)), "whom?");
        assertEquals(TacticalRules.Refusal.ACTION, TacticalRules.refusal(state, new TacticalIntent.Interact("charm", "foe")), "what?");
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Interact("parley", "ally")), "the wrong side");
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Interact("parley", "nobody")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Interact("cover", "foe")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Interact("parley", EncounterTemplate.PLAYER)));
        var quiet = withProfile(state, Map.of("maxRangeEdges", 1));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(quiet, new TacticalIntent.Interact("parley", "away")), "out of voice");
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(at(state, 0), new TacticalIntent.Interact("parley", "foe")));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(state, new TacticalIntent.Interact("parley", null), script()));
    }

    /** An offer stands through speech and a policy change, and lapses publicly at the end of the activation or at any other action. */
    @Test
    void anOfferLapsesUnlessAnswered() {
        var state = after(talking(HAGGLE), CombatScheduler.apply(talking(HAGGLE), attempt("haggle"), script()));
        var id = state.openOffer().id();
        assertEquals(id, CombatScheduler.apply(state, new TacticalIntent.SetReactionPolicy("brace", CombatReaction.Policy.OFF), script()).last().openOffer().id());

        var ended = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("ACTIVATION_ENDED", note(ended, "OFFER_LAPSED").payload().get("reason"));
        assertEquals(id, note(ended, "OFFER_LAPSED").payload().get("offerId"));
        assertEquals("foe", note(ended, "OFFER_LAPSED").payload().get("participantId"));
        assertFalse(note(ended, "OFFER_LAPSED").byPlayer());
        assertTrue(ended.steps().stream().allMatch(step -> step.openOffer() == null), "not one row after the decision holds it");
        assertEquals(TacticalRules.Refusal.OFFER, TacticalRules.refusal(after(state, ended), new TacticalIntent.AnswerOffer(id, true)));

        var withBlade = withAttack(state);
        for (var intent : List.of(new TacticalIntent.Guard(), new TacticalIntent.MoveZone("mid"), new TacticalIntent.Strike("blade", "foe"),
                new TacticalIntent.UseAbility("focus", List.of()), new TacticalIntent.Interact("haggle", "foe"))) {
            var plan = CombatScheduler.apply(withBlade, intent, script(1, 1));
            assertEquals("ACTION_TAKEN", note(plan, "OFFER_LAPSED").payload().get("reason"), intent.toString());
            assertTrue(plan.steps().stream().filter(step -> step.phase() != EncounterPhase.AWAITING_ACTION)
                    .allMatch(step -> step.openOffer() == null), "only a decision can hold an offer: " + intent);
        }
        var item = CombatScheduler.useItem(withBlade, new TacticalIntent.UseItem(null),
                new CombatScheduler.ItemOutcome("salve", withBlade.player().health(), null, 0), script(), 0);
        assertEquals("ACTION_TAKEN", note(item, "OFFER_LAPSED").payload().get("reason"));
        assertNull(item.last().openOffer());
    }

    /** A new attempt replaces the terms, under a new identity, so an answer to the old ones can never reach the new ones. */
    @Test
    void aStaleAnswerCannotReachLaterTerms() {
        var state = after(talking(PEACE), CombatScheduler.apply(talking(PEACE), attempt("peace"), script()));
        var first = state.openOffer().id();
        var again = after(state, CombatScheduler.apply(state, attempt("peace"), script()));
        assertNotEquals(first, again.openOffer().id());
        assertEquals(TacticalRules.Refusal.OFFER, TacticalRules.refusal(again, new TacticalIntent.AnswerOffer(first, true)));
        assertNull(TacticalRules.refusal(again, accept(again)));
    }

    /** A declared-action tick of paying the price can fell the player first: nothing is agreed and the fight is lost. A free answer runs no tick. */
    @Test
    void aTickWhilePayingStopsTheAgreement() {
        var burn = tick("burn", CombatEffect.Phase.ACTION_DECLARED, 5);
        var state = library(with(fight(List.of(WIN), player().health(3, 24).holding(active(burn, 1)), foe("foe")), HAGGLE), burn);
        var held = withOffer(state, new Negotiation.Offer("offer-1", "haggle", "foe", TOLL, Negotiation.Response.COUNTEROFFERED));
        var plan = CombatScheduler.apply(held, accept(held), script());
        assertEquals("DEFEAT", note(plan, "ENCOUNTER_OUTCOME_PENDING").payload().get("outcome"));
        assertEquals("ENCOUNTER_ENDED", note(plan, "OFFER_LAPSED").payload().get("reason"));
        assertEquals(0, count(plan, "OFFER_ANSWERED"));
        assertFalse(plan.summary().containsKey("social"), "the receipt claims no answer");
        assertNull(plan.last().agreement());
        assertNull(plan.last().openOffer());

        var free = withOffer(state, new Negotiation.Offer("offer-1", "peace", "foe", PASS, Negotiation.Response.OFFERED));
        assertEquals("AGREEMENT", note(CombatScheduler.apply(free, accept(free), script()), "ENCOUNTER_OUTCOME_PENDING").payload().get("outcome"));
    }

    // ---- surrender ---------------------------------------------------------------------------------------------------

    /**
     * Accepted surrender terms take only the recipient out of the fight, with no Health change: its stance and effects end as a
     * defeat's do, a sourced effect follows its definition, and the fight goes on at the same decision while another opponent
     * stands. The surrendered one takes no activation and cannot be attacked, but can still be spoken to.
     */
    @Test
    void anAcceptedSurrenderTakesTheRecipientOut() {
        var poison = tick("poison", CombatEffect.Phase.ROUND_END, 1);
        var state = library(with(fight(List.of(WIN), player().attacks(new CombatAttack("blade", "force", 2, 1, 6, "force", 0, 0, 0,
                CombatAttack.Defense.GUARD, true, null)), foe("foe").holding(active(poison, 1)), foe("other")), DEMAND, PLAIN), poison);
        var offered = after(state, CombatScheduler.apply(state, attempt("demand"), script()));
        assertEquals(EncounterState.Status.ACTIVE, offered.participant("foe").status(), "the response binds nobody");
        var plan = CombatScheduler.apply(offered, accept(offered), script());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.AWAITING_ACTION), phases(plan));
        var surrendered = note(plan, "PARTICIPANT_SURRENDERED");
        assertEquals("foe", surrendered.payload().get("participantId"));
        assertEquals("yield", surrendered.payload().get("termsId"));
        assertFalse(surrendered.byPlayer());
        var stored = after(offered, plan);
        var foe = stored.participant("foe");
        assertEquals(EncounterState.Status.SURRENDERED, foe.status());
        assertEquals(24, foe.health());
        assertTrue(foe.effects().isEmpty());
        var ended = (List<?>) note(plan, "EFFECTS_RESOLVED").payload().get("lines");
        assertEquals("HOLDER_SURRENDERED", ((Map<?, ?>) ended.getFirst()).get("reason"));
        assertNull(stored.agreement());
        assertNull(EncounterOutcome.decide(stored));
        assertTrue(stored.playerDecision());

        assertEquals(TacticalRules.AttackBlock.NOT_STANDING,
                TacticalRules.attackBlock(stored.space(), stored.player(), stored.player().sheet().attack("blade"), foe));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(stored, new TacticalIntent.Strike("blade", "foe")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(stored, new TacticalIntent.Interact("demand", "foe")),
                "a surrendered recipient has nothing left to agree");
        assertNull(TacticalRules.refusal(stored, new TacticalIntent.Interact("plain", "foe")), "a declared word is still heard");
        assertNull(TacticalRules.refusal(stored, new TacticalIntent.Speech("foe", "Stay down.")));
        var round = CombatScheduler.apply(stored, new TacticalIntent.EndActivation(), script());
        assertTrue(notes(round, "ACTIVATION_STARTED").stream().noneMatch(note -> "foe".equals(note.payload().get("participantId"))));
        assertEquals(EncounterState.Status.SURRENDERED, after(stored, round).participant("foe").status());
    }

    /** An effect a surrendering source applied follows its definition, and one waiting for its next activation has nothing to wait for. */
    @Test
    void aSurrenderingSourceEndsWhatDependsOnIt() {
        var bound = new CombatEffect("bound", "bound", CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0, false, 0, 0, 0, 0, 0,
                Set.of(), List.of(), true);
        var state = library(with(fight(List.of(WIN), player().holding(new CombatEffects.Active("e1", "bound", "foe", 1, 0, 1, 0, null, false)),
                foe("foe"), foe("other")), DEMAND), bound);
        var offered = after(state, CombatScheduler.apply(state, attempt("demand"), script()));
        var plan = CombatScheduler.apply(offered, accept(offered), script());
        var lines = (List<?>) note(plan, "EFFECTS_RESOLVED").payload().get("lines");
        assertEquals("SOURCE_SURRENDERED", ((Map<?, ?>) lines.getFirst()).get("reason"));
        assertTrue(after(offered, plan).player().effects().isEmpty());
    }

    /** The last opponent's surrender ends the fight inside the answer, as that surrender and not as a victory. */
    @Test
    void theLastOpponentsSurrenderEndsTheFight() {
        var state = with(fight(List.of(WIN, HOLD), player(), foe("foe")), DEMAND);
        var offered = after(state, CombatScheduler.apply(state, attempt("demand"), script()));
        var plan = CombatScheduler.apply(offered, accept(offered), script());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.OUTCOME_PENDING), phases(plan));
        var outcome = note(plan, "ENCOUNTER_OUTCOME_PENDING");
        assertEquals("HOSTILE_SURRENDER", outcome.payload().get("outcome"));
        var stored = after(offered, plan);
        assertEquals(EncounterObjective.Status.MET, EncounterOutcome.decide(stored).objectives().get("win"));
        assertEquals(EncounterOutcome.Kind.HOSTILE_SURRENDER, EncounterOutcome.decide(stored).kind());
    }

    /** The player's own surrender needs the same explicit answer; it ends the fight, fails every objective and clears its effects. */
    @Test
    void thePlayersSurrenderEndsTheFight() {
        var poison = tick("poison", CombatEffect.Phase.ROUND_END, 1);
        var state = library(with(fight(List.of(WIN, HOLD), player().holding(active(poison, 1)), foe("foe")), GIVE_UP), poison);
        var offered = after(state, CombatScheduler.apply(state, attempt("give-up"), script()));
        assertEquals(EncounterState.Status.ACTIVE, offered.player().status(), "offering is not surrendering");
        assertNull(EncounterOutcome.decide(offered));
        var declined = CombatScheduler.apply(offered, new TacticalIntent.AnswerOffer(offered.openOffer().id(), false), script());
        assertEquals(EncounterState.Status.ACTIVE, after(offered, declined).player().status(), "declining keeps the player in the fight");
        var plan = CombatScheduler.apply(offered, accept(offered), script());
        assertTrue(note(plan, "PARTICIPANT_SURRENDERED").byPlayer());
        var outcome = note(plan, "ENCOUNTER_OUTCOME_PENDING");
        assertEquals("PLAYER_SURRENDER", outcome.payload().get("outcome"));
        var stored = after(offered, plan);
        assertEquals(EncounterState.Status.SURRENDERED, stored.player().status());
        assertTrue(stored.player().effects().isEmpty());
        assertEquals(0, plan.playerHealthDelta());
        assertTrue(EncounterOutcome.decide(stored).objectives().values().stream().allMatch(status -> status == EncounterObjective.Status.FAILED));
        assertFalse(stored.playerDecision());

        var guarded = after(state, CombatScheduler.apply(state, new TacticalIntent.Guard(), script()));
        assertTrue(guarded.player().guarding());
        var askedOnGuard = after(guarded, CombatScheduler.apply(guarded, attempt("give-up"), script()));
        assertFalse(after(askedOnGuard, CombatScheduler.apply(askedOnGuard, accept(askedOnGuard), script())).player().guarding(),
                "a stance ends with leaving the fight");
    }

    // ---- frozen forms ------------------------------------------------------------------------------------------------

    /** Every declared form reads back from what is stored; an action declaring nothing keeps its earlier form, key for key. */
    @Test
    void frozenFormsReadBack() {
        for (var action : List.of(PARLEY, PEACE, HAGGLE, DEMAND, GIVE_UP, SWAY, PLAIN)) {
            assertEquals(action, EncounterAction.fromCanonical(action.canonicalForm()));
        }
        assertEquals(Set.of("id", "kind", "cost", "intent", "recipients"), PLAIN.canonicalForm().keySet());
        var trained = new Negotiation.Check("insight", "talk", 2, 9);
        assertEquals(trained, Negotiation.Check.fromCanonical(trained.canonicalForm()));
        assertEquals(Set.of("attribute"), new Negotiation.Check("insight", null, 0, null).canonicalForm().keySet());
        assertFalse(trained.publicForm().containsKey("difficulty"), "the player never sees it before the roll");
        var offer = new Negotiation.Offer("offer-3", "haggle", "foe", TOLL, Negotiation.Response.COUNTEROFFERED);
        assertEquals(offer, Negotiation.Offer.fromCanonical(offer.canonicalForm()));
        var truce = new Negotiation.Agreement("offer-3", "toll", "foe");
        assertEquals(truce, Negotiation.Agreement.fromCanonical(truce.canonicalForm()));
    }

    /** The shapes refuse what could never mean anything, and a check's rank is the player's, frozen under the attack skill's cap. */
    @Test
    void shapesAreClosed() {
        assertEquals(3, new Negotiation.Check("force", "talk", 0, null).forPlayer(Map.of("talk", 3L)).rank());
        assertEquals(10, new Negotiation.Check("force", "talk", 0, null).forPlayer(Map.of("talk", 99L)).rank());
        assertEquals(0, new Negotiation.Check("force", "talk", 0, null).forPlayer(Map.of()).rank());
        assertEquals(0, AT_14.forPlayer(Map.of("talk", 3L)).rank());
        assertEquals(5, new EncounterAction("sway", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.PERSUADE, EncounterAction.Recipients.ANY, 1,
                null, null, new Negotiation.Check("force", "talk", 0, null), null, null).forPlayer(Map.of("talk", 5L)).check().rank());
        assertEquals(PLAIN, PLAIN.forPlayer(Map.of("talk", 5L)));
        assertThrows(IllegalArgumentException.class, () -> new Negotiation.Check("charm", null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Negotiation.Check("force", null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Negotiation.Check("force", "talk", 11, null));
        assertThrows(IllegalArgumentException.class, () -> new Negotiation.Check("force", null, 0, 41));
        assertThrows(IllegalArgumentException.class, () -> new Negotiation.Check("force", null, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Negotiation.Terms("t", Negotiation.Terms.Kind.TRUCE, -1));
        assertThrows(IllegalArgumentException.class, () -> Negotiation.Terms.Kind.parse("peace"));
        assertThrows(IllegalArgumentException.class, () -> new Negotiation.Offer("o", "a", "r", PASS, Negotiation.Response.REFUSED), "a refusal is no offer");
        assertThrows(IllegalArgumentException.class, () -> new EncounterAction("a", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.PERSUADE,
                EncounterAction.Recipients.HOSTILE, 1, null, null, null, PASS, null), "only a negotiation proposes terms");
        assertThrows(IllegalArgumentException.class, () -> new EncounterAction("a", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.NEGOTIATE,
                EncounterAction.Recipients.ANY, 1, null, null, null, PASS, null), "only to hostiles");
        assertThrows(IllegalArgumentException.class, () -> negotiation("a", null, null, TOLL), "a counteroffer answers proposed terms");
        assertThrows(IllegalArgumentException.class, () -> negotiation("a", null, TOLL, TOLL), "and differs from them");
        assertThrows(IllegalArgumentException.class, () -> new EncounterAction("a", EncounterAction.Kind.SCENE, null, null, 1, null,
                new EncounterAction.Passage("x", "y", true), AT_14, null, null), "a scene action has no check");
    }

    /** The fixture's actions freeze with the rank this player holds, and the stored definition reads back the same. */
    @Test
    void theFreezeHoldsThePlayersRank() throws Exception {
        var rules = EncounterPack.rules(EncounterPack.tactics());
        var template = rules.templates().get("parley-ground");
        var measured = template.actions().stream().filter(action -> action.id().equals("measured-offer")).findFirst().orElseThrow();
        assertEquals(0, measured.check().rank(), "a template holds no player's rank");
        assertEquals(14, measured.check().difficulty());
        assertEquals("small-toll", measured.counteroffer().id());
        assertEquals(1, measured.counteroffer().cost());
        assertEquals(Negotiation.Terms.Kind.TRUCE, measured.terms().kind());
        for (long rank : List.of(0L, 2L)) {
            var facts = new FrozenEncounter.PlayerFacts(java.util.UUID.randomUUID(), Map.of("awareness", 2L, "might", 2L),
                    new FrozenEncounter.Pool(8, 8), null, List.of(), Set.of(), rank == 0 ? Map.of() : Map.of(measured.check().skill(), rank));
            var frozen = FrozenEncounter.freeze(java.util.UUID.randomUUID(), template, rules.profiles().get("sample:armory-tactics"), facts, null, rules.library());
            var held = frozen.actions().stream().filter(action -> action.id().equals("measured-offer")).findFirst().orElseThrow();
            assertEquals(rank, held.check().rank());
            var stored = (List<?>) tools.jackson.databind.json.JsonMapper.builder().build().readValue(frozen.definition(), Map.class).get("actions");
            assertEquals(frozen.actions(), stored.stream().map(action -> EncounterAction.fromCanonical((Map<?, ?>) action)).toList());
        }
    }

    /** The options name the declared check and terms, but never a difficulty or a counteroffer not yet made. */
    @Test
    void optionsDiscloseOnlyWhatIsKnown() {
        var options = TacticalRules.options(talking(PARLEY));
        var line = (Map<?, ?>) ((List<?>) options.get("actions")).getFirst();
        assertEquals(Map.of("id", "pass", "kind", "truce", "cost", 0), line.get("terms"));
        var check = (Map<?, ?>) line.get("check");
        assertEquals("force", check.get("attribute"));
        assertFalse(check.containsKey("difficulty"));
        assertFalse(line.containsKey("counteroffer"));
        var plain = (Map<?, ?>) ((List<?>) TacticalRules.options(talking(PLAIN)).get("actions")).getFirst();
        assertFalse(plain.containsKey("check"));
        assertFalse(plain.containsKey("terms"));
        assertFalse(TacticalRules.options(talking(PLAIN)).containsKey("offer"));
    }

    /** The same state and draws always give the same plan. */
    @Test
    void negotiationsAreDeterministic() {
        var state = talking(PARLEY);
        assertEquals(CombatScheduler.apply(state, attempt("parley"), script(7, 8)), CombatScheduler.apply(state, attempt("parley"), script(7, 8)));
        assertNotNull(CombatScheduler.apply(state, attempt("parley"), script(7, 8)).last().openOffer());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** A fight at the player's decision with one opponent and this declared action. */
    private static EncounterState talking(EncounterAction action) {
        return with(fight(List.of(WIN), player(), foe("foe")), action);
    }

    /** The attempt of an action on the fixture's opponent. */
    private static TacticalIntent attempt(String action) {
        return new TacticalIntent.Interact(action, "foe");
    }

    /** The explicit acceptance of the terms on the table. */
    private static TacticalIntent accept(EncounterState state) {
        return new TacticalIntent.AnswerOffer(state.openOffer().id(), true);
    }

    /** The state with these declared actions. */
    private static EncounterState with(EncounterState s, EncounterAction... actions) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), List.of(actions), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), s.openOffer(), s.agreement());
    }

    /** The state with this offer on the table. */
    private static EncounterState withOffer(EncounterState s, Negotiation.Offer offer) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), offer, s.agreement());
    }

    /** The state with this many action points left. */
    private static EncounterState at(EncounterState s, int points) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), points, s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), s.openOffer(), s.agreement());
    }

    /** The state under another profile. */
    private static EncounterState withProfile(EncounterState s, Map<String, Integer> overrides) {
        var profile = org.rpgengine.rules.CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp", null, overrides);
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                profile, s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), s.openOffer(), s.agreement());
    }

    /** The state with its offer, whose player also holds an attack and a free self ability that do nothing here. */
    private static EncounterState withAttack(EncounterState s) {
        var blade = new CombatAttack("blade", "force", 2, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, true, null);
        var focus = new CombatAbility("focus", 1, CombatAbility.Limit.NONE, 0, 0, CombatAbility.Targets.SELF, 1, 0,
                List.of(new CombatAbility.Operation(CombatAbility.Kind.HEAL, 0, 0, null, 1, 0, null, true)));
        var armed = fight(s.objectives(), player().attacks(blade).abilities(focus), foe("foe").guard(40));
        return withOffer(with(armed, s.actions().toArray(EncounterAction[]::new)), s.openOffer());
    }

    private static List<String> types(TacticalPlan plan) {
        return plan.notes().stream().map(TacticalPlan.Note::type).toList();
    }

    private static TacticalPlan.Note note(TacticalPlan plan, String type) {
        return notes(plan, type).getFirst();
    }

    private static List<TacticalPlan.Note> notes(TacticalPlan plan, String type) {
        return plan.notes().stream().filter(note -> note.type().equals(type)).toList();
    }

    private static long count(TacticalPlan plan, String type) {
        return notes(plan, type).size();
    }
}
