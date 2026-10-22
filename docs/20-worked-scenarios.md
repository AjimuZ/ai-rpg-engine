# Worked Cross-Module Scenarios

All examples are neutral design examples, not a required story or included executable pack. Their values use the proposed AI RPG Skirmish defaults. They illustrate contracts an implementation must test.

## 1. Import an adventure without a predetermined plot

An author defines a small region, three locations, two factions with conflicting goals, a starting situation, character creation, encounter templates, and bounded generation rules. They provide no mandatory main quest or ending. The manifest selects hybrid mode and disables the guide/creative-ability modules.

Import validates references, creation budgets, connected starting location, effect graphs, generation limits, and assets. It registers one immutable pack hash. A new campaign copies initial instances from those definitions and stores the exact pack/ruleset hashes. Codex can propose a new local NPC when allowed, but cannot replace the two factions' established history.

A second author imports an orbital salvage setting with engineering and navigation skills, battery charge as a resource, mechanical opponents, and a different visual theme. It uses the same engine and supported rules primitives. No Java or TypeScript file changes. These are genericity acceptance fixtures, not a prescribed plot for players.

## 2. Consume one healing item

Before: Health 16/24, one healing item restoring eight Health, canonical revision 40, game turn 12. The player submits request `example-heal-1`, expected revision 40, using that item on themselves.

The backend verifies possession, target, prerequisites, and whether a healing action is permitted now. It calculates `min(24, 16 + 8) = 24`, consumes one item, advances time according to the action, appends events, increments revision to 41 and game turn to 13, and stores one receipt. The operation emits a public state update and then grounded narration.

If the client loses the response and resends `example-heal-1` with the same payload, it receives the receipt for revision 41. It does not consume another item. If the payload changes to another target under the same key, the result is an idempotency conflict. If the database transaction fails, neither healing nor consumption occurs.

## 3. A complete two-round fight

### Starting state

The player actor is level 1 with Force 2, Finesse 2, Insight 2, Resolve 2, melee skill 2, Health 24/24, Focus 10/10, Armor 1, and no equipment Guard bonus. A light blade is already held and deals `1d6 + Force`. Player Guard is `10 + 2 = 12`. Their reaction policy is Brace on the first eligible attack.

One validated standard creature-template opponent has explicit Health 14, Finesse 1, Insight 1, effective base Guard 12 through its admitted profile, Armor 1, attack attribute 1, skill 1, and a two-AP Strike. The creature template uses catalog-approved explicit statistics rather than the player progression formulas. Its catalog threat cost is 8. The full-health/resource level-1 player's capacity is `8 × (0.55 + 0.30 + 0.15) = 8`, so a threat total of 8 fits the standard 90–110% band. This heuristic does not itself prove the profile is balanced.

Zones are entry, yard, and escape route. Entry connects to yard. Both can reach the escape route through legal declared edges. Player starts at entry; opponent starts in yard. All profile values, reactions, objective, and escape policy freeze before initiative.

### Initiative

Recorded player dice are 6 and 7: `6 + 7 + Finesse 2 + Insight 2 = 17`.

Recorded opponent dice are 5 and 6: `5 + 6 + 1 + 1 = 13`.

Player acts first. A fresh AI thread or browser reload would reuse this order.

### Round 1, player activation

The player receives 3 AP. Move from entry to yard costs 1 AP, leaving 2. Strike costs 2 AP, leaving 0. The opponent's predeclared Brace spends its one reaction and raises Guard from 12 to 14 for this attack.

Recorded attack dice are 9 and 9: `9 + 9 + Force 2 + melee 2 = 22`. Against Guard 14, margin 8 is a strong hit. Damage die is 4 on d6 and strong-hit die is 2 on d4: `4 + 2 + 2 = 8 raw damage`. Armor 1 reduces this to 7. Opponent Health becomes 7/14. The complete action and costs commit together.

### Round 1, opponent activation

Opponent receives 3 AP and uses Strike for 2. Player Brace spends one reaction and raises Guard 12 to 14 against that attack. Recorded opponent attack dice are 5 and 6: `5 + 6 + 1 + 1 = 13`. It misses Guard 14. AP and any declared costs remain spent; the player loses no Health.

Opponent spends its remaining 1 AP on Guard, gaining +2 until the start of its next activation. It is now effectively Guard 14. Round 1 completes and advances world time by eight seconds. Round 2 opens and refreshes each eligible reaction budget once.

### Round 2, player activation

Player has 3 AP and remains in the same zone. A Strike costs 2. The opponent's Guard action is still active until its next activation; Brace uses the same stacking key and would add nothing, so the default policy skips redundant Brace.

Recorded attack dice are 6 and 5: `6 + 5 + 2 + 2 = 15`. Against Guard 14, margin 1 is a normal hit. Damage die is 6: `6 + Force 2 = 8`; Armor 1 leaves 7. Opponent Health becomes 0/14. The opponent is defeated under the default policy; no automatic death is asserted.

### Settlement

The objective is satisfied. The opponent does not receive another activation. Settlement advances the unfinished second round to its eight-second boundary, for **16 seconds total** across the encounter, without applying a duplicate condition tick. Player Health remains 24/24 and Focus 10/10. Any loot remains at its actual owner/location unless collected.

The standard challenge awards 20 XP once under the encounter's challenge-source key. If the player had 90 XP before the fight, total XP becomes 110, crossing the level-2 threshold of 100. Level 2 grants one training point and one attribute-point entitlement. Health maximum becomes 26 and Focus maximum 11; current values remain 24 and 10 until recovery. Attribute allocation has not happened automatically. Advancement choices open at the safe post-fight boundary.

The receipt records outcome, event range, resources, reward settlement, and pending decision. Codex narrates that public result. It does not decide that the player killed or looted the opponent or automatically chose an attribute upgrade.

## 4. Renew Codex context during combat

An encounter is in round 4, player activation, with 1 AP remaining, a known two-round condition with one round-end tick left, and an enemy reaction already spent. The narration task reaches its AI-session ceiling.

The operation completes or is reconciled, then the memory service validates the checkpoint and retires the affected AI thread. A new thread receives the current revision, initiative cursor, remaining AP, reaction budgets, effects with clock types, public positions, learned abilities, and pending decision. It receives no full transcript by default.

The player still has 1 AP. No round opens, no resource regenerates, no condition expires early, no initiative rerolls, and no prologue plays. A guide's separate session is renewed only if its own policy requires it.

## 5. Crash after a saved action, before narration

The engine commits a successful escape at revision 85, but the Codex connection fails before a narrative response arrives. The operation status is committed with narration pending. The player can inspect position, resources, and the outcome. Reconnect returns that receipt and retries only narration. It cannot re-enter the battle or award escape XP again.

## 6. Rumor, map, and location knowledge

An NPC reports that a bridge is passable. Record a rumor with that speaker and time. The canonical world may contain a destroyed bridge; the protagonist does not know that yet. A map labels the bridge's location but does not prove its condition or count as a visit.

When the player sees the destroyed bridge, record a discovery and update their knowledge. A later summary cannot override that observation with the earlier rumor. Music must not reveal the collapse before the player can observe it. A guide without remote senses cannot confirm the bridge while the party is elsewhere.

## 7. Creative ability is drafted but never cast

With the optional creative module enabled, a player asks for a recipe that could repel two creatures. Codex proposes a bounded effect graph. The backend previews cost, targets, duration, prerequisites, and limitations. The player asks for an explanation and then closes the panel.

No resource is spent, no creature moves, no combat event occurs, and no reusable learned ability is granted unless a separate learning operation was explicitly selected. A later execution request validates the then-current state again.

## 8. Content upgrade during an active campaign

An author changes a weapon formula in pack version 1.1. A campaign pinned to 1.0 retains its original formula. The owner can preview migration, including equipped items, derived statistics, active encounter dependencies, and affected summaries. Migration is blocked during an incompatible active encounter or waits for settlement. A verified backup and audited revision accompany an applied upgrade.
