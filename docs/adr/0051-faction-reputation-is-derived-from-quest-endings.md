# ADR 0051: Faction reputation is derived from quest endings

- Status: Accepted; implemented in P15.2-A (21 November 2026) for quest outcomes only.
- Date: 21 November 2026
- Changes: a quest outcome's `settlement` gains an optional `reputation` list, and world-scope predicates gain the
  numeric `reputation` atom. The pinned quest rules carry both as types. No migration, table, route, panel, event type
  or player command was added.

## Context

docs/10 section 7 says factions keep goals, reputation, resources, relationships and clocks, and that mechanical
triggers are causal and deterministic. ADR 0025 kept reputation out of quest rewards and left it to P15.2. Before this
change a faction definition had a name, a description, goals and stances toward other factions, and no rule read any
of them. Quest outcomes could only grant item units.

Quest endings already have the properties a reputation source needs:

- an ended quest row is terminal, and a trigger refuses any later change to it;
- the row records the selected outcome and the sequence of the event that ended it;
- the quest engine is a pure function of the pinned rules, the stored rows and committed facts.

## Decision

1. **Outcomes declare typed changes.** `settlement.reputation` holds 0..8 entries `{faction, delta}`. `faction` is a
   faction reference, one entry per faction. `delta` is a non-zero integer within -25..25. The compiler reports
   `SCHEMA`, `DUPLICATE`, `REFERENCE_UNKNOWN` or `REFERENCE_KIND` at the entry. `QuestRules.Outcome.reputation()`
   carries them as `ReputationChange(faction, delta)` with canonical faction IDs, and its constructor enforces the same
   bounds.
2. **Standing is derived, not stored.** `Reputation.of(rules, endings)` sums the changes of every ended quest's
   recorded outcome toward each faction and clamps the sum once to -100..100. A faction that no ending names stands at
   zero. An outcome that did not end its quest counts nothing. So the stored cause of every point is the quest row and
   its ending event, a quest can never count twice, and the result does not depend on the order of the endings.
3. **Predicates read it.** `{"reputation": "<faction>"}` is a numeric world-scope atom with the proven interval
   -100..100, for example `{"ge": [{"reputation": "wardens"}, 15]}`. Inside one evaluation `QuestPlan` derives it from
   the working quest states, so an ending raises the standing that a later quest in the same commit reads. The atom is
   not readable in actor-scope formulas.

## Alternatives

- **A `faction_reputation_change` table, clamped step by step.** This mirrors `relationship_change` (ADR 0050) and would
  let later sources (encounters, clocks, dialogue) add changes. It needs a migration, backup scope and cleanup-list
  changes, plus a defined order between sources. It is deferred until a second source of reputation exists.
- **Clamping after every ending.** This makes the standing depend on the order of endings, which the derived value
  could only recover from event sequences. A single clamp keeps it a pure function of which quests ended. The cost is
  that a standing pushed past a bound keeps the excess. For example, five +25 endings and one -25 ending stand at 100,
  not 75.
- **A module gate.** Factions are core content (docs/09), and an outcome without `reputation` behaves exactly as before.
  So no module is required.

## Consequences

- Existing packs and campaigns are unchanged. Adding `reputation` to an outcome changes the pack hash like any edit.
- No view, panel, receipt or narration context shows a standing yet. Quest endings already announce the outcome, and
  the change can be recomputed from the pinned rules.
- Only quest outcomes change reputation. Faction clocks, bounded time catch-up, reputation from encounters or dialogue,
  faction resources, and world changes triggered by a standing remain P15.2 work.
- If a later source of reputation needs step-by-step clamping or storage, it replaces this derivation. That later
  change must either keep these values or migrate them.
