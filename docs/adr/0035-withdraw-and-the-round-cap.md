# ADR 0035: Withdraw moves toward an escape without reactions, and the round cap names a harmless outcome

- Status: Accepted; implemented in P11.5-C.2 (13 November 2026). Supersedes decision 9 of [ADR 0032](0032-terminal-predicates-from-committed-state.md) ("the round cap stays a pause"). Settlement and the return to narrative remain for the rest of P11.5.
- Date: 13 November 2026
- Builds on: [ADR 0029](0029-initiative-rounds-activations-and-bounded-combat-dialogue.md) (movement and the scheduler), [ADR 0031](0031-predeclared-reactions-effect-phases-and-cooldowns.md) (`leavesZone` reactions), [ADR 0032](0032-terminal-predicates-from-committed-state.md) (terminal predicates), [ADR 0033](0033-negotiation-explicit-answers-and-surrender.md) (lapsing offers).

## Context

Docs/11 section 7 lists Withdraw (2 AP, "move one legal edge toward an escape zone and suppress supported movement-trigger reactions for that move"), and the profile already holds `withdrawCost`. Section 13 puts "configured stalemate/round cap" last in the predicate order, lists stalemate as a supported outcome, and says a default round cap of 30 "pauses combat for review or a declared disengagement policy; it does not arbitrarily kill participants". After P11.5-C.1 neither existed: a fight that reached its cap undecided stopped at its round end, where no command is legal, so it waited forever. The documents leave open: what "toward" measures; what Withdraw costs on an edge whose Move price is higher; which reactions it suppresses; where a disengagement policy is declared and what it may do; and what "pause for review" means when nothing can resume a round end.

## Decision

1. **Toward means strictly shorter.** `WITHDRAW` {zoneId} names a destination a `MOVE_ZONE` could reach (one unblocked edge, below capacity) whose cheapest walk in action points to any escape zone, over the current passages, is strictly shorter than from the player's zone. An encounter without escape zones offers no withdrawal. The tactic's approach already measures distance this way.
2. **It is never cheaper than walking.** The price is `withdrawCost`, raised to the edge's Move price when that is higher. With the defaults (Move 1, difficult 2, Withdraw 2) it is always 2; a pack with dearer terrain cannot make Withdraw a discount.
3. **It suppresses the only movement trigger.** No `leavesZone` reaction is considered for a withdrawal; every other rule of a move holds: it passes through `RESOLVING`, runs declared-action ticks, takes an offer off the table, and is refused while movement or actions are prevented. The public `PARTICIPANT_WITHDREW` names no reaction, so it reveals nothing about an opponent's hidden reactions.
4. **It decides nothing.** Withdraw sets no standing. An escape zone it reaches ends the fight through `EncounterOutcome.decide` in its usual order, so a reach-zone objective there comes first and a tick that fells the player first leaves it in place.
5. **The round cap is the last predicate.** Once `roundCap` rounds have completed and nothing above holds, the cap decides. It reads only the round count and changes no participant: the last round's own end, its ticks and seconds included, comes first as in every round, so the cap itself never harms anybody.
6. **The template declares the disengagement policy.** An encounter's optional `disengagement` is `stalemate` (default: the new `STALEMATE` outcome, both sides where they stand) or `escape` (the sides break off and the player gets away: `ESCAPE`). These are the only supported outcomes that bind nobody to terms and harm nobody; victory, defeat, surrender or an imposed truce would decide what the fight did not. The policy is frozen into the definition only when it is not the default, so every earlier definition keeps its text and reads as a stalemate.
7. **The verdict says the cap gave it.** A cap verdict carries `roundCap: true` in its public form, event and receipt; no other verdict does, and only the cap makes a stalemate. A cap escape moves nobody, so an escape objective keeps the status the player's zone gives it, and settlement can tell a release from a reached exit.
8. **The pause for review is the outcome pending.** `OUTCOME_PENDING` already waits for settlement and is readable; a round end at the cap could never move again. So no fight stops at a round end any more, and none opens a round beyond the cap.

## Alternatives considered

- Withdraw costing exactly `withdrawCost` on every edge: rejected; on a pack with dearer terrain it would cost less than walking and add a benefit docs/11 does not grant.
- Refusing a withdrawal across an edge dearer than `withdrawCost`: rejected; it would make escape possible by walking but not by withdrawing.
- Counting edges instead of action points for "toward": rejected; the tactic and the objective checks already use action points.
- Setting the player's status to `ESCAPED` on withdrawing into an escape zone: rejected; ADR 0032 leaves standings to settlement, and the predicate already reads the zone.
- A profile parameter for the policy: rejected; adding one changes every frozen profile's hash and cannot be read back from profiles frozen earlier.
- Keeping the pause at the round end: rejected; nothing can resume it, so the fight would wait forever.
- Policies making the hostiles withdraw: rejected for now; their leaving would read as a victory, which the cap must not grant.

## Consequences

- New intention `WITHDRAW` {zoneId}; new public event `PARTICIPANT_WITHDREW` with English and Italian statements; outcome `STALEMATE` and the `roundCap` mark with English and Italian statements; receipt `options.withdraw` [{zone, cost}] and `combat.outcome.roundCap`; encounter `disengagement` in the pack contract. No migration, backup schema change, refusal category or message.
- Every tactical receipt's `options` gains `withdraw`; `CommandReceipts` projects it only when present, so older receipts replay unchanged.
- Limits: non-player participants never withdraw (P12.3/P12.4); a cap escape needs settlement to say where the player goes; an encounter stored at the end of its last round before this change stays there, because no command can move it; the provisional numbers stay unbalanced (P18.3).

## Verification

`WithdrawStalemateTest` (pure), `WithdrawCommandsTest` (real PostgreSQL), `CombatPackTest`, `TypedCommandTest`, `EventStatementsTest` and the changed cap cases of `EncounterOutcomeTest` and `CombatSchedulerTest`. See the [roadmap entry](../16-development-roadmap.md#15-p11--deterministic-combat-foundation) for the recorded results and what was not proven.
