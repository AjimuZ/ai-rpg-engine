# ADR 0040: A capability snapshot and a versioned threat heuristic

- Status: Accepted; implemented in P13.1-A (14 November 2026) as pure values and checks that no planning path calls yet. Changes no decision of ADRs 0006 or 0028.
- Date: 14 November 2026
- Builds on: [ADR 0006](0006-original-combat.md) (constrained proposals inside deterministic rules), [ADR 0028](0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md) (frozen combat profiles and the default roster limit).

## Context

Docs/12 sections 2–4 ask encounter planning to read a hashed snapshot of the party's capabilities, derive a party capacity, a threat band for the selected difficulty and a hostile-body limit, and refuse a roster outside them before any generated proposal can be admitted (QA-14). The numbers are the provisional threat heuristic of the docs/11 section 17 register.

That register asks for every provisional value to be ruleset-profile data, not a hard-coded constant. The only profile data the engine has is `CombatProfile`, and its frozen form is stored in every active encounter. `CombatProfile.fromCanonical` requires exactly the current parameter set, so adding the threat values there would make every encounter frozen before the change unreadable.

The engine has no character level yet (advancement is P13.4), and docs/12 section 10 forbids feeding an undefined level into the formula.

## Decision

1. **The heuristic is a fixed, versioned engine rule, `threat-heuristic/1`.** `ThreatBudget` holds its constants (base capacity 8, 2 per level, preparedness 55/30/15 percent, costs 4/8/12/20, 20 percent per level, party size + 2 hostile bodies). `EncounterDifficulty` holds the four bands. A retuned value is a new identity. Packs cannot override these values yet. Making them pack-tunable is left to calibration (P18.3) and needs a profile format that keeps older frozen profiles readable.
2. **The snapshot is a pure hashed value.** `CapabilitySnapshot` records the content and profile hashes, the source revision, the location, the scene tags, the difficulty, the heuristic identity and each actor's level, Health, relied resources, worn equipment, conditions, learned abilities, skill ranks and ammunition. Its canonical text does not depend on input order. A plan is stale when a fresh reading hashes differently. Every actor carries an explicit level from 1 to 20; there is no default.
3. **Arithmetic is exact.** Capacity sums exact rationals and rounds down once; the band compares `100 × cost` against `capacity × percent` in integers. The same inputs always give the same answer.
4. **A roster outside the band in either direction is refused.** `OVER_BUDGET` protects the player. `UNDER_BUDGET` stops a proposal from misstating the selected difficulty. Structural faults (empty roster, invalid, reserved or duplicate ID, level out of range, too many hostile bodies) are findings, not exceptions. They are returned all together in a fixed order, so the one permitted repair (P13.2) can name them all.
5. **Only actors that can fight count.** Defeated, absent and excluded actors, and actors at zero Health, add neither capacity nor party size. A party with none admits no roster (`NO_ACTIVE_PARTY`).

## Alternatives considered

- Add the threat values to `CombatProfile.PARAMETERS`: rejected for now, because stored encounter profiles would no longer read back.
- Floating-point preparedness: rejected. A binary sum such as 5.6 + 8.4 can fall below 14 and round down to 13.
- Reject only over-budget rosters: rejected. An "under" roster would be admitted as a Severe fight it is not. The cost is that some small parties have no level-1 roster that fits. For example, one fresh level-1 actor has a Gentle band of 4.8–6.4, which no level-1 catalog cost meets. This is recorded for calibration.
- Default an unknown level to 1: rejected by docs/12 section 10.

## Consequences

- New pure classes in `org.rpgengine.combat`: `CapabilitySnapshot`, `EncounterDifficulty` and `ThreatBudget`. No migration, command, route or page changed.
- Nothing reads a campaign into a snapshot or calls the check yet. Planning (P13.2) and freezing admitted content (P13.3) must call it. Authored templates entered with `ENGAGE` are not budget-checked.
- Not covered: catalog statistic bands per tier, control, burst and area caps, extra activations, reinforcement phases, objective reachability and hard counters (P13.3). Ammunition is recorded but does not change capacity.

## Verification

`ThreatBudgetTest`. See the [roadmap entry](../16-development-roadmap.md#17-p13--dynamic-encounter-and-advancement-pipeline) for the recorded results and what was not proven.
