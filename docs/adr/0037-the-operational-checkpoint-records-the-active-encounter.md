# ADR 0037: The operational checkpoint records the active encounter

- Status: Accepted; implemented in P11.6-A (14 November 2026). Completes the `"encounter"` member that V16 reserved under [ADR 0005](0005-renewable-memory.md); changes no decision of ADRs 0028 to 0036.
- Date: 14 November 2026
- Builds on: [ADR 0005](0005-renewable-memory.md) (operational checkpoints and restart reconciliation), [ADR 0028](0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md) to [ADR 0031](0031-predeclared-reactions-effect-phases-and-cooldowns.md) (the stored encounter, its cursor and its timers), [ADR 0036](0036-explicit-settlement-and-the-return-to-narrative.md) (an unfinished round adds its seconds once at settlement).

## Context

Docs/11 section 2 asks that a saved active encounter include its phase, actor cursor, round, remaining action points, reaction state, effect timers and pending decision, and that renewal change none of them; docs/08 lists encounter and RNG cursors among what an operational checkpoint holds; QA-09 asks that renewal and restore preserve initiative, AP, reactions, effects, resources and the RNG cursor, and QA-12 that effect clocks tick exactly once through renewal.

Since P11.1 to P11.5 every one of those facts is a column of `encounter` or `encounter_participant`, written by the canonical commit, and the RNG cursor is the recorded draws. Nothing about a fight lives only in memory, so a restart or a restore that keeps the rows keeps the fight. But the checkpoint function of V16 still wrote `"encounter": null`: a checkpoint could not show a fight, restart reconciliation could not tell when one had changed outside a command, and a renewal's verified checkpoint said nothing about it. Docs/11 section 6 adds a partial-round time boundary: a fight decided inside a round adds that round's seconds once at settlement, which the stored cursor already signals.

## Decision

1. **The snapshot records the active encounter.** V32 replaces `memory_operational_snapshot`. Its `"encounter"` member is the campaign's encounter in any phase other than `SETTLED` and `NARRATIVE_RESUME` (V22 allows at most one), or null without one. It holds identity, template, location, phase, round, actor cursor, action points, speech count, opening status, passages, the application counter, the offer on the table, the accepted truce, the frozen profile's and definition's SHA-256, the revision and row version, and per participant in roster order its identity, ordinal, actor, side, zone, standing, local Health and Focus, initiative and rank, reaction budget, Guard stance, effect instances with their clocks, reaction policies, ability uses, readied values and row version. The frozen content itself is left out: V22 never lets it change, and its hash identifies it. The player's pools, the ammunition and the RNG cursor stay where V16 put them (`resources`, `items`, `rng`).
2. **The round's time boundary is explicit.** `roundSeconds` comes from the frozen profile, and `roundEndsAt` is the world time plus those seconds while the cursor is set, otherwise null. It mirrors `CombatScheduler`: a set cursor means an activation in progress or a fight decided inside its round, whose seconds the round end or the settlement adds exactly once; a null cursor means nothing is owed (before the first round, or at a round end that already added its time).
3. **This is layout version 2.** `OperationalCheckpoints.SNAPSHOT_SCHEMA_VERSION` becomes 2, and a capture no longer treats a stored checkpoint of another version as the existing one. A version 1 checkpoint therefore verifies `UNSUPPORTED`, which restart reconciliation discards without invalidating summaries before capturing a new one. Keeping version 1 would have made every checkpoint of a fight in progress at the upgrade look `DIVERGED`, and that invalidates every summary of the campaign.
4. **No new state, no new recovery step.** No column or table is added, and no command, scheduler rule, receipt or event changes. Restore and restart keep a fight because they keep its rows. The fight continues from those rows through the same commands, and an attempt cut off before its commit is retried with its reserved draws through the existing equivalent-attempt stream. Renewal writes only the derived checkpoint row.

## Alternatives considered

- Storing the frozen profile and definition in the snapshot: rejected. They never change, they are the largest part of an encounter, and their hashes are already part of the row.
- Keeping layout version 1 because a campaign without a fight gets the same snapshot: rejected, for the false divergence described in decision 3.
- Deriving the remaining round time in Java at capture: rejected. The snapshot is computed in SQL so that verification re-derives it from storage, and it must be the same function of the rows at capture and at verification.
- Recording settled encounters too: rejected. A settled fight is history in the event log and the encounter row, not a cursor to resume.

## Consequences

- Migration V32 (function only); backups require schema 32 ([tools/database_backup/README.md](../../tools/database_backup/README.md)). A campaign without an active encounter gets exactly the V16 snapshot.
- Restart reconciliation now reports an encounter changed outside a command as `DIVERGED` and replaces the checkpoint from canonical state.
- Unchanged and now visible: the snapshot has listed the status of narration still owed since V16, and startup after a restore retires queued narration. A checkpoint taken while narration was queued is therefore discarded as `DIVERGED` after a restore and captured again. Its encounter, resources, items and RNG members are the same, and the summaries it invalidates were already stale under the new recovery generation.
- Limits: combat-dialogue operations and transcripts, and their renewal and reconnect, are not covered (the rest of P11.6 and P12). No live dump and restore of a fight has been rehearsed. An encounter stored at its round end at the cap before P11.5-C.2 is recorded but still cannot move.

## Verification

`CombatRecoveryTest` (real PostgreSQL), the V32 step of `CanonicalMigrationUpgradeTest` and `MemoryContinuityTest`. See the [roadmap entry](../16-development-roadmap.md#15-p11--deterministic-combat-foundation) for the recorded results and what was not proven.
