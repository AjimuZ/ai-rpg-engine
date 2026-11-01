# ADR 0032: Terminal predicates from committed state

- Status: Accepted; implemented for victory, defeat, escape and objective success in P11.5-A (1 November 2026). [ADR 0033](0033-negotiation-explicit-answers-and-surrender.md) extends the predicates with surrender and agreement. The stalemate policy and settlement are later P11.5 work and build on it.
- Date: 1 November 2026
- Builds on: [ADR 0028](0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md) (lifecycle and objectives), [ADR 0029](0029-initiative-rounds-activations-and-bounded-combat-dialogue.md) (scheduler), [ADR 0031](0031-predeclared-reactions-effect-phases-and-cooldowns.md) (effects).

## Context

After P11.4 no terminal predicate ran: a fight whose player fell waited at a round end and a won fight ran on to the round cap. Docs/11 section 13 fixes when to check (after each resolved action, defeat event and round-end trigger) and an order of priority. It does not say how several objectives combine, what a failed objective does, how a mutual fall reads, or how a fight decided at a phase without an arrow to `OUTCOME_PENDING` reaches it. V22 and `EncounterPhase` allow that arrow only from `ACTOR_START`, `RESOLVING` and `ROUND_END`.

## Decision

1. **One pure function of state decides.** `EncounterOutcome.decide` takes an `EncounterState` and nothing else, so no narration, speech or social attempt can establish or prevent an outcome. The state now carries the frozen objectives, read back from the stored definition.
2. **Priority.** Defeat, then an explicit objective, then no active opponent, then escape. Defeat goes first because a fallen protagonist meets no objective (ADR 0028), so a mutual fall is a defeat. This reads docs/11's "emergency rule" as the protagonist's fall.
3. **Objectives are alternatives.** A reach-zone, survive or protect objective met ends the fight, the first in declared order. Templates such as `trail-ambush` declare ways out that exclude each other, and the schema has no grouping. A defeat-hostiles or escape objective reads the same facts as the structural predicates, so it keeps their outcome and place in the order.
4. **A failed objective ends nothing by itself.** The fight can still be won, lost or left. Every objective's status travels with the verdict, so settlement can honour a lost ally.
5. **An escape zone is an escape, with or without an escape objective.** The compiler already refuses a template whose player starts in an escape zone. The player's status stays `ACTIVE`; recording `ESCAPED` belongs to settlement.
6. **The scheduler checks at each arrow.** A verdict moves the encounter to `OUTCOME_PENDING` with one public `ENCOUNTER_OUTCOME_PENDING` event, from `RESOLVING` after any action, from `ACTOR_START` after start triggers and from `ROUND_END` after its ticks.
7. **A fight decided elsewhere takes the shortest legal path.** Decided at an activation's end, the row passes through the next `ACTOR_START` with zero points, no announcement and no start trigger; with nobody left to start, it closes the round, whose time passes but whose ticks do not fire, so poison cannot turn a won fight into a lost one. A round opening whose ticks fell everyone also passes through `ACTOR_START`; before this, that path broke the state machine.
8. **The stored row is enough to repeat the verdict, and no migration is needed.** An outcome reached inside a round keeps its cursor, and one reached at a round end has none, which tells the survive objective whether the current round completed. V23 already permits both.
9. **The round cap stays a pause.** A decided fight at the cap has its outcome; an undecided one still waits at its round end until the stalemate policy exists.

## Alternatives considered

- All objectives required: rejected, because `trail-ambush` could then never end by its objectives alone.
- Ending the fight when every objective has failed: rejected for now; it would take away a fight the player can still win, and docs/11 does not ask for it.
- An `outcome` column: rejected; it would repeat what state and the event already say and add a migration and a backup schema step. Settlement can add one if it needs to.
- Clearing the cursor at every outcome: rejected, see decision 8.
- Letting round-end ticks fire in a decided fight: rejected, see decision 7.

## Consequences

- New public event `ENCOUNTER_OUTCOME_PENDING` {encounterId, round, outcome, objectiveId?, objectives} with English and Italian statements, a receipt member `combat.outcome`, and a new `OPENING_STRIKE_LAPSED` reason, `ENCOUNTER_ENDED`.
- `EncounterState` holds `cursor` and `actionPoints` at an `OUTCOME_PENDING` reached inside a round.
- Limits: nothing leaves `OUTCOME_PENDING` until settlement exists, so a campaign whose fight has ended stays in encounter mode and refuses every tactical and exploration command. A round left unfinished adds no world time. All hostiles having escaped or surrendered reads as a victory until those predicates exist, and no code sets those statuses yet. A surrendered player gets no verdict. Effects are not converted when a fight ends. Withdraw, Ready and Assess remain undecided.

## Verification

`EncounterOutcomeTest` (pure predicates, ties, every scheduler path, 200 seeded fights), `OutcomeCommandsTest` (real PostgreSQL) and the long fight of `TacticalCommandsTest`. See the [roadmap entry](../16-development-roadmap.md#15-p11--deterministic-combat-foundation) for the recorded results and what was not proven.
