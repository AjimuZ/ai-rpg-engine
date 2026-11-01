# ADR 0033: Negotiation by declared checks, stored offers and explicit answers

- Status: Accepted; implemented in P11.5-B (1 November 2026). Stalemate, settlement and the return to narrative are P11.5-C and build on it.
- Date: 1 November 2026
- Builds on: [ADR 0029](0029-initiative-rounds-activations-and-bounded-combat-dialogue.md) (declared social actions), [ADR 0030](0030-attacks-defense-damage-and-healing-with-recorded-draws.md) (recorded draws), [ADR 0032](0032-terminal-predicates-from-committed-state.md) (terminal predicates), which it extends for surrender and agreement.

## Context

Docs/11 section 7.1 and docs/20 section 9 require that a negotiation can fail, be refused or produce a counteroffer, that binding terms are never accepted for the protagonist, and that an accepted agreement ends the fight. After P11.5-A a social attempt only paid its cost, nothing set `SURRENDERED`, a surrendered player got no verdict and hostiles who surrendered read as a victory. The documents leave open how a non-player's response is decided without prose, how long an offer stands, how terms are represented, and how the outcomes rank.

## Decision

1. **The response is data and dice.** A social action may declare a `check` (a role, an optional skill, and a fixed `difficulty` or the recipient's effective Resistance). A negotiation addressing hostiles may declare the `terms` it proposes and a `counteroffer`. `Negotiation.respond` is a pure function of them and the recorded roll: a failed check is a refusal; a success is the counteroffer when one is declared and the margin is below `strongHitMargin`, otherwise the proposed terms. Without a check the declared counteroffer stands, otherwise the proposed terms do. Nothing reads text.
2. **A response never binds anyone.** Anything but a refusal stores one offer on the encounter row and changes nothing else. This also holds when the recipient meets the player's own proposal, because docs/11 forbids accepting terms for the protagonist and a retry or misread attempt must not end a fight.
3. **Only an explicit answer applies terms.** `ANSWER_OFFER` names the offer's identity and exactly `accept` or `decline`, so an answer cannot reach terms the player did not read: an offer's identity comes from the row version that made it, which never recurs. Any other decision is `INVALID_COMMAND`, and an unknown, answered or lapsed offer is the new refusal `OFFER_UNAVAILABLE`.
4. **An offer stands only through words.** It survives speech and a reaction-policy change and lapses, with a public event, when the activation ends or the player takes any other action. Otherwise a player could strike a recipient and then accept its truce. One offer at most is on the table, and a new attempt replaces it.
5. **Terms are a closed list.** `truce` ends the encounter for everyone as an agreement, because the template declares that this negotiation can do so. `recipientSurrenders` takes only the addressed participant out. `playerSurrenders` takes the player out, which ends the fight. Terms may declare action points, paid on acceptance; declining is always free. Paying makes acceptance an action, with its declared-action ticks and the prevention rule, while free acceptance is a decision.
6. **Surrender is a lasting standing, not a defeat.** A surrendered participant keeps its Health, loses its stance and held effects as a fallen one does, takes no activation, cannot be attacked and cannot agree to more, but can still be addressed by voice. V30 refuses bringing it back or turning it into a defeat. A sourced effect follows the same `sourceDefeated` definition.
7. **The predicates extend ADR 0032.** The order is defeat, player's surrender, explicit objective, no active hostile, escape, truce. No active hostile is `HOSTILE_SURRENDER` when any hostile surrendered, otherwise `VICTORY`, because settlement must not read a spared opponent as a fallen one. A surrendered player fails every objective, as a defeated one does. The scheduler's branches for a player out of the fight without a verdict are removed, since that state no longer exists.
8. **Checks hide their difficulty.** A public event and the receipt state the dice, total and result; an internal event holds the difficulty and margin, because either can stand for a recipient's Resistance. The options list a check's role and rank and the proposed terms, but not a counteroffer not yet made.
9. **Storage repeats what it can see.** V30 adds `open_offer` and `agreement`, nullable JSON on the encounter row: an offer exists only at `AWAITING_ACTION`, a truce only at `OUTCOME_PENDING` or later and never changes.

## Alternatives considered

- Reading speech text or classifying it: rejected; prose establishes nothing (QA-13), and interpretation belongs to the composer (P12.2).
- A separate confirmation only for counteroffers, with proposed terms taking effect at once: rejected, see decision 2.
- An offer that stands until the player's next activation: rejected, see decision 4; it would also store an open decision across other participants' turns.
- A table of offers: rejected; one offer at a time needs no table, and the event log keeps its history.
- Reporting every end with no active hostile as a victory, as docs/11's order alone suggests: rejected, see decision 7.
- A per-encounter limit on attempts: left out for now. Each attempt costs points and the fight goes on, so it is bounded; it is a provisional number to review in calibration (P18.3).

## Consequences

- New intention `ANSWER_OFFER`, refusal `COMMAND_OFFER_UNAVAILABLE` with reviewed English and Italian wording, public events `SOCIAL_CHECK_RESOLVED`, `NEGOTIATION_RESPONDED`, `OFFER_ANSWERED`, `OFFER_LAPSED` and `PARTICIPANT_SURRENDERED`, internal `SOCIAL_CHECK_CALCULATED`, outcomes `AGREEMENT`, `PLAYER_SURRENDER` and `HOSTILE_SURRENDER`, migration V30 and backup schema 30. An action declaring neither a check nor terms keeps its frozen form and draws nothing.
- Limits: nothing leaves `OUTCOME_PENDING` until settlement exists (P11.5-C), so an accepted agreement or surrender stays pending there. Terms cannot move items or other binding transfers; they cost points only. A check on a persuade, intimidate, distract or request action records its result and changes nothing else. Morale, knowledge and objective policies, and a recipient proposing terms on its own, are not modelled (P12.3/P12.4). A truce binds every hostile; the pack cannot name who may grant it. Generated replies and the composer's preview and confirmation are P12.

## Verification

`NegotiationTest` (pure: every dice pair against an independent model), `NegotiationCommandsTest` (real PostgreSQL), `EncounterOutcomeTest` (predicates and seeded negotiated fights), `CampaignCommandHttpTest.aNegotiatedEndingAnswersAndReplays` and the V30 step of `CanonicalMigrationUpgradeTest`. See the [roadmap entry](../16-development-roadmap.md#15-p11--deterministic-combat-foundation) for the recorded results and what was not proven.
