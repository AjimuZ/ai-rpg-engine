# ADR 0050: Relationship changes cite their cause, and companions follow the player

- Status: Accepted; implemented in P15.1-B (21 November 2026) for NPC actors that already exist in a campaign.
- Date: 21 November 2026
- Changes: the engine contract now supports one module, `relationships`. The NPC definition gains one optional field
  that only that module allows. The pinned rule catalog and the canonical commit run the new rules. Migration V33 adds
  the `relationship_change` table. No route, panel or player command was added.

## Context

docs/10 section 6 asks for pack-defined bounded relationship dimensions whose significant changes need an event and a
cause, hidden values that stay hidden, and companions with party membership. Section 12 adds that trust numbers alone
never create consent, romance or an obligation. Commitments already exist in the typed knowledge ledger (P09.1,
`actor_commitment`, resolved FULFILLED, BROKEN or RELEASED), but no rule reacts to them. P15.5 requires a disabled module
to disappear cleanly. Before this change the engine contract supported no module at all, and every manifest that listed
one was refused with `UNSUPPORTED_MODULE`.

A commit's event count is fixed by its plan before any later rule runs (ADR 0049). So a rule that runs after the plan
cannot add an event of its own to explain a change.

## Decision

1. **The module is the gate.** `EngineContract.current()` supports `relationships`. An NPC definition may declare
   `relationship` only when its own pack's manifest lists `{"id": "relationships"}`. Otherwise the compiler reports a
   located `MODULE_DISABLED` error, so the rules are never accepted and then silently ignored. The module takes no
   configuration file (`CONSTRAINT_CONFLICT`). A dependency's modules do not count for the root pack.
2. **Rules are authored per NPC.** `relationship` holds:
   - `dimensions`: 1..4 entries `{id, min, max, initial = 0}`, with -100 <= min < max <= 100 and the initial value
     between them;
   - `causes`: 1..16 entries `{on, dimension, delta}`, one per trigger and dimension, with a delta of 1..10 in either
     direction. The triggers are `spoken-to`, `gift`, `trade`, `promise-kept` and `promise-broken`;
   - an optional `companion` `{dimension, atLeast}`. The threshold must lie above the initial value and at most the
     maximum, so every companion joins through a recorded change.
3. **Causes are typed facts of the committing revision.** After the operation's knowledge changes and before the NPC
   world rules, `Relationships.applyWithin` reads:
   - the acting player's `SPEECH_DELIVERED`, `ITEM_TRANSFERRED` and `ITEM_TRADED` events whose recipient or
     counterparty is an NPC with rules; these are held toward the player;
   - every commitment resolved FULFILLED or BROKEN at this revision whose creditor is such an NPC; these are held
     toward the debtor. RELEASED changes nothing.

   Causes are applied in event-sequence order. Each declared cause moves the value by its delta, clamped to the
   bounds. No prose is read.
4. **A change is one immutable row.** A change that moves a value inserts one `relationship_change` row: the NPC, the
   other actor, the dimension, the cause, the cited event sequence (and the commitment for a promise), the delta, the
   values before and after, the revision and the world time. The database refuses an update or delete, a cited event
   of another revision, a no-op change and a promise without its commitment. A clamped change that leaves the value as
   it was records nothing. One commit records at most 64 changes. Each recorded holder's `relationships:<npc>` scope is
   bumped.
5. **Membership is derived, and its changes are marked.** There is no membership table. An NPC is a companion of a
   player exactly while its latest recorded value of the companion dimension toward that player is at least the
   threshold. The row that crosses the threshold upward is marked `JOINED`, and the row that crosses it downward is
   marked `LEFT`.
6. **Companions follow the player's move.** In the same commit, each companion that stood where the acting player's
   `ACTOR_MOVED` event started moves to where it ended. The write is guarded by the row version and bumps
   `actor:<npc>`. A companion's schedule is suspended while it is a companion.
7. **Reads decide nothing.** Nothing here runs outside a committing transaction. No view projects relationship values
   yet, so they stay hidden from every audience.

## Alternatives considered

- A current-value table beside the history: faster to read, but every value then exists twice, and each new table
  that references `actor` must be added to about fifty test cleanup statements. The latest-row index answers the
  same question.
- Internal events for each change or membership: these would place the change in the journal. They are not possible
  after the plan has fixed the commit's event count, and the row already cites the event that caused it.
- A membership table with join and leave rows: an explicit record, but it can disagree with the values that justify
  it. The marked rows record the same transitions with their cause.
- Joining by an accepted agreement (a commitment) instead of a threshold: this is closer to consent, but no player
  command creates commitments yet. The threshold is an authored rule of that NPC, not an inference from a number.
  Romance and obligations remain outside this module.
- Ignoring the field when the module is off: quieter, but an author would get an NPC whose rules silently do
  nothing.

## Consequences

- Authors can give NPCs bounded attitudes that typed play changes. A campaign whose content does not enable the module
  runs no query and writes no row (`Relationships` returns before reading anything), so it behaves as before.
- An NPC that joins the party travels with the player. Its schedule no longer applies until it leaves.
- Not covered:
  - Production campaign creation still creates no NPC actors, so packaged play has no NPC these rules can change.
  - No player command creates commitments, so `promise-kept` and `promise-broken` take effect only when trusted code
    resolves a commitment.
  - Companions do not join combat: `CapabilitySnapshot` membership, control policy and formation are not wired.
  - No panel, view or narration context shows relationship values or membership.
  - Typed NPC goals, expressed boundaries, faction relationships and romance are not included.

## Verification

`RelationshipWorldTest` (PostgreSQL, through the production admission, resolver and commit path) and
`PackCompilerTest#validatesRelationshipRulesOnlyWithTheModule`. See the
[roadmap entry](../16-development-roadmap.md#19-p15--complete-supported-world-and-optional-modules) for the recorded
results and what was not proven.
