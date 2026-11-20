# ADR 0043: Planned encounters are frozen once and admitted only when feasible

- Status: Accepted; implemented in P13.3-A (20 November 2026) as a pure admission step that no planning path calls yet. Changes no decision of ADRs 0028, 0040, 0041 or 0042.
- Date: 20 November 2026
- Builds on: [ADR 0028](0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md) (frozen definitions), [ADR 0040](0040-capability-snapshot-and-a-versioned-threat-heuristic.md) (threat limits), [ADR 0041](0041-template-bounded-encounter-compositions-and-an-authored-fallback.md) (proposals and the authored fallback), [ADR 0042](0042-offered-encounters-reveal-only-what-entry-reveals.md) (what entry reveals).

## Context

Docs/12 section 3 asks planning to validate solvability and spatial connectivity, then freeze the admitted encounter before the first irreversible action (steps 6–9). QA-14 requires an unreachable objective and a hidden hard counter to be rejected. ADR 0041 checks only the threat budget, starting zones and capacities of a composition; its fallback takes the first authored template whose roster fits the band, whatever the player can do against it.

Three facts shape the check:

- Whether the player can meet an objective or harm an opponent depends on the player's frozen profile (attacks from worn gear, abilities, Guard, roles), which depends on the template pack's combat profile and the content's combat forms. The proposal context does not carry them.
- The definition entry stores is already a pure function of the template, profile and player facts (`FrozenEncounter.freeze`), and the stored row can never change afterwards (V22 trigger `encounter_frozen`).
- Entry reveals no statistic, attack, reaction or effect (ADR 0042), so the player cannot see any mechanical counter before entering.

## Decision

1. **Check the frozen encounter, not the template.** `EncounterFeasibility.check(frozen)` reads only a `FrozenEncounter`, the exact definition entry would store. An objective is unreachable when no walk of the player reaches its zone or an escape zone (`no-path`), when it needs more rounds than the round cap (`round-cap`), or when it needs a countered hostile out of the fight (`countered`). A hostile is a hard counter when an objective needs it out of the fight and no attack, ability or surrender negotiation of the player can ever harm it (`unharmable`) or reach it (`out-of-reach`), or, under any objective, when it can stop the player's actions for the rest of the fight (`action-lock`).
2. **Every counter is hidden, so every counter is refused.** Because entry discloses no mechanics, nothing in the encounter can make a counter visible yet. A later disclosure rule would have to be a new decision.
3. **Judge the player's own means on their best case.** An attack's best roll and largest damage, the lowest defense effects can leave, ammunition assumed available, an opponent's own walk with capacity ignored. A refusal is then never a matter of dice or of the opponent's choice. Allies, reactions and opponents' choices are not counted, because a counter the player escapes only if someone else acts is still a counter.
4. **A zone filled at entry is closed to the player.** A zone other than the player's whose capacity the other starting participants already fill cannot be entered: the player cannot make them leave, and a defeated participant keeps its place (`EncounterState.Combatant.present`).
5. **Freeze once at admission.** `EncounterAdmission.admit(decision, current, entry)` freezes the decided encounter with the entry's identity, profiles, player facts and combat forms, without an opening strike, and returns `Admitted` only without a finding. An infeasible proposal falls back with `PROPOSAL_INVALID` and its first finding's code; an existing fallback keeps its cause. Either way the result is the first offered option whose authored roster fits the band and whose frozen authored template is feasible, or `NoEncounter`.
6. **Enter the admitted definition or none.** `unchanged(admitted, current, entry)` holds only when the snapshot hash, the offered option and the definition frozen again from current facts are all unchanged. Entry stores the admitted value as it is; otherwise planning runs again, so nothing is quietly composed again.

## Alternatives considered

- Feasibility codes inside the `encounter` contract's validator, so the single repair could fix them: rejected for now, because the context would need each pack's profile, the player's facts and the combat library, and the checked encounter would not be the stored one. Consequence: feasibility findings get no repair.
- Check at `ENGAGE` for authored templates as well: rejected in this story, because it changes what the player can start today and the authored fixtures and sample packs were not reviewed against it (an unarmed player could no longer start a defeat-hostiles fight). It stays a separate decision.
- Count allies' attacks as a way to defeat a hostile: rejected, because ally tactics are not guaranteed.
- Treat a full zone as passable because its occupants may move: rejected, because only the opponents' choice could open it.

## Consequences

- New `combat/EncounterFeasibility` and `inference/contract/EncounterAdmission`. No migration, route, command or page changed.
- A player without a weapon or a damaging ability is planned no defeat-hostiles or protect encounter.
- Not covered: reinforcement phases (no pack grammar exists, so there is nothing to validate), repeated short control effects, a player's own entry conditions that prevent movement, opening strikes chosen at entry, statistic bands per tier, and any caller.

## Verification

`EncounterFeasibilityTest` (6) and `EncounterAdmissionTest` (3). See the [roadmap entry](../16-development-roadmap.md#17-p13--dynamic-encounter-and-advancement-pipeline) for the recorded results and what was not proven.
