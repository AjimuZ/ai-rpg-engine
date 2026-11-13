# ADR 0038: Combat dialogue recovery and the resume card's encounter

- Status: Accepted; implemented in P11.6-B (14 November 2026). Changes no decision of ADRs 0029, 0033, 0036 or 0037.
- Date: 14 November 2026
- Builds on: [ADR 0003](0003-canonical-transactions.md) (one canonical commit, idempotent retries), [ADR 0029](0029-initiative-rounds-activations-and-bounded-combat-dialogue.md) (bounded speech and declared social actions), [ADR 0033](0033-negotiation-explicit-answers-and-surrender.md) (offers and explicit answers), [ADR 0036](0036-explicit-settlement-and-the-return-to-narrative.md) (explicit settlement), [ADR 0037](0037-the-operational-checkpoint-records-the-active-encounter.md) (the checkpoint records the active encounter).

## Context

QA-09 asks that renewal mid-fight keep the dialogue operation and its transcript, and that a reconnect can neither submit a draft nor repeat a social effect. QA-13 asks that an agreement and its settlement happen once. Docs/15 asks that renewal or reconnect during a social check, and after an accepted agreement, reuse the original operation and draws and settle once. Docs/08 section 10 asks that a restart rebuild the active encounter and show it on the resume card. Until now the card's `encounter` member was always null.

Every part of a combat conversation is already stored. Speech, a social attempt, its check, the recipient's response, the answer and the settlement are commits of the canonical pipeline. Their public events are the transcript, which the journal panel words and the context assembler retrieves. The offer, the truce and the speech count are columns of the encounter row, and since V32 the checkpoint records them. A retry with the same request identity replays the stored result. A new attempt with the same intention at the same revision shares the draw stream of the first, and journal recovery ends an attempt left unresolved by a crash as `FAILED_BEFORE_COMMIT`. A draft exists only in the browser: no API stores one. None of this had been exercised across a restore, a renewal and a reconnect during a social check.

## Decision

1. **No new recovery state or step.** Combat dialogue recovers through the same rows and mechanisms as the rest of a fight. A lost response is replayed with the same request. An attempt cut off before its commit is asked again as a new request and reproduces its reserved dice. Nothing on the server stores a draft, and nothing resubmits one. A request held from before a reconnect is refused by the revision check or by the rule it now breaks, such as the speech limit or a turn that is no longer the player's.
2. **The resume card shows the active encounter.** `ResumeCards.Encounter` is read in the card's own read-only snapshot. It holds the encounter and template identity, phase, round, the acting participant and its remaining points (set while an activation is in progress and the fight has no verdict, as in the receipt), `playerDecision`, the speech left in this activation, the offer waiting for an explicit `ANSWER_OFFER`, and, at `OUTCOME_PENDING`, the verdict that `SETTLE_ENCOUNTER` would finalize, decided again from the stored state as settlement does. It holds nothing the tactical receipt does not already show the player: no other participant's Health, defenses, effect clocks or reaction policies, no check difficulty and no counteroffer not yet made. A card that cannot read its active encounter fails closed (503), like a list beyond its bound.
3. **The card stays a display.** Reading it answers, settles and submits nothing, and it lists no legal actions. The receipt of the last command keeps that role, and a full tactical projection is P12.1.

## Alternatives considered

- Storing drafts or a pending dialogue operation on the server so that a reconnect could restore them: rejected. A stored draft is exactly what a reconnect must not submit, and the browser already keeps drafts (docs/13 section 7.1).
- Listing every legal option on the card, as the receipt does: rejected for now. It would make the card a second tactical projection and duplicate P12.1.
- Recording the verdict on the encounter row at `OUTCOME_PENDING` so that the card could read it: rejected. Settlement already decides it again from the stored state, so the card's reading cannot differ from the one that will be finalized.
- Adding the transcript to the card: rejected. The journal panel already words every public event, and the card is meant to be short.

## Consequences

- No migration, receipt, event, rule or command changes. The card's `encounter` member is null outside a fight, as before, and is now filled during one. The interface still does not read it (P12.1).
- `CombatRecoveryTest` proves on PostgreSQL that a social check cut off by a crash, and an accepted agreement with its settlement, each recover through a restore, renewals and every reconnect read. Each happens once, and the transcript is unchanged.
- Limits: the restore is simulated on the same database, not a dump and reload; renewals are prepared through `SessionRehydration`, not by a running runtime session; generated replies and the browser path (no submission of a draft held in the page, draft preservation) are P12.4 and P12.5.

## Verification

`CombatRecoveryTest` (real PostgreSQL), with `MemoryContinuityTest` for the card outside a fight. See the [roadmap entry](../16-development-roadmap.md#15-p11--deterministic-combat-foundation) for the recorded results and what was not proven.
