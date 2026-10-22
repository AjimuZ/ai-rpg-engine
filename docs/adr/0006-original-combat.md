# ADR 0006: Original Deterministic Zone Combat

**Status:** Proposed ruleset requiring playtest. **Date:** 22 October 2026.

## Context

The owner requests dynamically created turn-based battles using actual inventory, stats, skills, abilities, and progression, informed by familiar tabletop play while avoiding a copied branded ruleset. Neither predecessor contains a complete reusable tactical engine.

## Decision

Supply AI RPG Skirmish: zone-based space, persistent initiative, three AP per activation, predeclared reactions, 2d10 checks, margin-based strong hits, flat armor, bounded effects, and explicit multi-outcome settlement. Codex proposes encounters and legal tactics; deterministic Java rules resolve the fight. Freeze mechanical profiles before entry and account for planned phases in the threat budget.

Use original text, formulas, terminology, and sample content. No external stat blocks or SRD text are required. File-defined supported values are configurable, while a different scheduler/spatial model requires a versioned extension.

## Alternatives

Pure narrative combat cannot guarantee inspectable outcomes. Copying an external ruleset adds compatibility and rights obligations. A grid simulation expands UI and generation complexity before a complete combat loop exists.

## Consequences

The default is concrete enough to build and test, but balance is provisional. Zone combat sacrifices precise grid geometry for manageable generated scenes. Interactive manual reactions are deferred; predeclared reactions keep individual actions atomic.

## Verification

Recorded-calculation replay, timing/stacking/property tests, terminal outcomes, dynamic encounter validation, simulations, human playtest, and resume mid-round. See [combat](../11-combat-system.md) and [encounters](../12-encounters-and-progression.md).
