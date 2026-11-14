# ADR 0041: Template-bounded encounter compositions and an authored fallback

- Status: Accepted; implemented in P13.2-A (14 November 2026) as a contract and a pure decision that no planning path calls yet. Changes no decision of ADRs 0006, 0028 or 0040.
- Date: 14 November 2026
- Builds on: [ADR 0006](0006-original-combat.md) (constrained proposals inside deterministic rules), [ADR 0007](0007-audience-isolation.md) (role and audience isolation), [ADR 0028](0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md) (authored templates), [ADR 0040](0040-capability-snapshot-and-a-versioned-threat-heuristic.md) (snapshot and threat budget).

## Context

Docs/12 section 3 asks the narration runtime for a bounded encounter candidate (step 5), validates it (step 6), allows at most one repair, and otherwise uses "a deterministic safe eligible template" or reports that no valid encounter exists (step 7). Docs/21 section 6 lists a much wider body: objective, motivation, variants, a zone graph, positions, visibility, phases, retreat policies and rewards.

The engine can check only part of that today. `ThreatBudget` (ADR 0040) checks a roster's cost and body count. The pack compiler and the `EncounterTemplate` constructor check zones, occupancy, objectives and escape zones. There are no catalog statistic bands per tier, so a generated participant's statistics cannot be judged. Reinforcement phases, objective reachability, hard counters and campaign-scoped identities for admitted content belong to P13.3. Rewards belong to P13.4.

## Decision

1. **A composition only selects among authored content.** The `encounter` contract's body is `snapshotHash`, `templateId`, `roster` (hostile `participantId` and starting `zoneId` pairs) and `reasonCode`. A proposal chooses one eligible template the caller offered, a subset of that template's hostile participants, and a starting zone for each. It cannot add a participant or change statistics, attacks, reactions, conditions, allies, objectives, escape zones, the zone graph, the opening policy or declared actions. Those fields do not exist in the schema, and an extra field is `field-set`.
2. **Escape stays open.** A hostile participant cannot start in an escape zone. Starting occupancy must fit every zone's capacity, counting the player and the template's allies.
3. **The engine owns the arithmetic.** Levels come from the caller per option (until P13.4). Costs come from `ThreatBudget`. The hostile limit is the lower of the budget's limit and the template's own, and every `ThreatBudget` finding is a violation the single repair can name.
4. **The task is privileged, interactive work.** It runs in the `encounter-proposer` role, whose only audience is `privileged`, so it never shares a conversation with a player-facing role. Its output is never shown. It is `INTERACTIVE` because the player waits for the fight to start when an encounter becomes eligible; this is provisional, like its 60-second budget.
5. **The fallback is the first authored template that fits, unchanged.** `EncounterFallback.decide` keeps an accepted composition only while the context read again before entry offers the same option at the same snapshot hash. Every other outcome (invalid after the repair, declined, refused, not completed, cancelled or stale) takes the first offered template, in the caller's order, whose authored roster passes the threat check. If none passes, the decision is `NoEncounter` and no fight starts. No player notice is produced.

## Alternatives considered

- Let the runtime propose new participants with statistics: rejected until catalog statistic bands exist, because "AI cannot declare an arbitrarily strong enemy minor" is not checkable without them.
- Let the runtime pick objectives or drop allies: rejected, because dropping an escape or protect objective can remove an approach the author declared (docs/21 section 12: "preserve available approaches").
- Compose a fitting subset deterministically as the fallback: rejected for now. The authored roster is the reviewed content; a search over subsets would be a second, unreviewed planner.
- Fall back to the first offered template regardless of the budget: rejected, because it could start a fight far above the selected difficulty.
- Show a notice when the fallback is used: rejected, because an authored encounter is an ordinary encounter. Whether to tell the player that no encounter started belongs to the future caller.

## Consequences

- New `combat/EncounterOption` and, in `inference/contract`, `EncounterContext`, `EncounterContract`, `EncounterProposal` and `EncounterFallback`, plus the schema `ai-contract/1/encounter.schema.json` and `TaskKind.ENCOUNTER`. No migration, command, route or page changed.
- Variety is limited to which authored hostiles take part and where they start. Motivation, visibility, retreat behavior, phases and reward sources are not proposed in contract version 1.
- With the provisional numbers, small parties often have no fitting authored roster (for example a band of exactly 8 for one fresh level-1 actor at Standard), so `NoEncounter` can be common until calibration (P18.3).
- A composed variant keeps its template's ID. Campaign-scoped identities for admitted content and freezing are P13.3.

## Verification

`EncounterOptionTest`, `EncounterContractTest` and the encounter case of `ContractSchemaTest`. See the [roadmap entry](../16-development-roadmap.md#17-p13--dynamic-encounter-and-advancement-pipeline) for the recorded results and what was not proven.
