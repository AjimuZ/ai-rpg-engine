# ADR 0034: Ready/equip changes real equipment and Assess reveals sheet values on a recorded check

- Status: Accepted; implemented in P11.5-C.1 (13 November 2026). Withdraw, the stalemate policy and settlement remain for the rest of P11.5.
- Date: 13 November 2026
- Builds on: [ADR 0023](0023-character-rules-and-equipment-constraints.md) (slot and hand rules), [ADR 0030](0030-attacks-defense-damage-and-healing-with-recorded-draws.md) (worn gear frozen at entry), [ADR 0033](0033-negotiation-explicit-answers-and-surrender.md) (recorded checks and lapsing offers).

## Context

Docs/11 section 7 lists Ready/equip (1 AP, "change one valid equipment assignment; hand/slot rules apply") and Assess (1 AP, "attempt a supported observation/check; reveal permitted information on success"), and the profile already holds `readyCost` and `assessCost`. After P11.4 neither had a combat form, and P11.4 left the decision to P11.5. The documents leave open: whether readying changes the actor's real equipment or a fight-local copy; how a fight whose Guard, armor and attacks are frozen at entry (V22 makes the frozen profile immutable) can follow a change; what an observation checks against; and which information is "permitted".

## Decision

1. **Readying changes the real equipment.** `READY_ITEM` {itemInstanceId, change: `equip` | `unequip`} applies exactly the exploration rules of `EQUIP_ITEM` and `UNEQUIP_ITEM` (slot, tags, capacity, hands; `EQUIPMENT_ILLEGAL`, `ITEM_UNAVAILABLE`), and the commit writes the assignment through the same character service, which proves the rules again. Docs/11 section 4 says the actor carries its real equipment into a fight, so a fight-local copy would diverge from the inventory at settlement.
2. **The player says which change it means.** Any `change` but the two exact words is `INVALID_COMMAND`. A toggle would read the same request as opposite actions depending on state, and a full slot or too few free hands is refused rather than emptied for the player, because choosing what to take off would be a decision the player did not make.
3. **The fight stores the readied values beside the frozen ones.** The commands layer computes the player's new Guard (`guardBase + finesse` plus the capped equipment Guard), armor and attacks once, from the pinned item data with the same function entry uses (`EncounterGear.worn`), and the scheduler takes them over with the action's cost. V31 adds `encounter_participant.readied`, read in place of the frozen Guard, armor and attacks for the rest of the fight. The frozen profile never changes. Readying is the second tactical intention that reads the pinned catalog, after using an item, because both act on items whose rules are pinned item data.
4. **A weapon must not need the pack later.** A readied weapon whose hit applies an effect the fight did not freeze is refused as `EFFECT_UNSUPPORTED`, because resolving its hit would read the pack during the fight.
5. **Assess is a recorded check against Resistance.** `ASSESS` {targetId} names a different standing participant within `maxRangeEdges` range bands and in sight (from another zone, an obscured zone hides its occupants; cover does not stop a look). It pays `assessCost`, then draws `2d10 + insight` against the target's effective Resistance, the same score and margin as a social check with no fixed difficulty. No pack declares an observation yet, so the engine uses the one value every participant has for a mental contest.
6. **Permitted information is the target's sheet values, only on success.** A success reveals the target's current and maximum Health, Guard, Resistance and armor as its sheet holds them, before any stance, cover or effect, in the public `ASSESSMENT_RESOLVED` and that operation's receipt. A failure reveals nothing. Focus, reactions, policies, attacks, abilities and tactics are never revealed. A strong result adds nothing, because docs/11 section 8 lets a non-damaging action declare its own strong benefit or none. The difficulty and margin stay in the internal `ASSESSMENT_CALCULATED`, as a hidden defense does.
7. **Both are ordinary player actions.** They pass through `RESOLVING`, run the declared-action ticks, take an offer off the table and return to the decision; an effect that prevents actions refuses them. A tick that fells the player first readies nothing and assesses nothing. Neither provokes a reaction.

## Alternatives considered

- A fight-local equipment copy written back at settlement: rejected, see decision 1; it would also make an item worn in the fight and unworn in the inventory at the same time.
- Freezing every carried item's combat data at entry and deriving the sheet from the assignment rows when the state is read: rejected; it changes every frozen definition of a player carrying spare gear, cannot cover an item gained in the fight, and still needs the pinned slot rules for legality.
- Taking off whatever blocks the slot automatically: rejected, see decision 2.
- A fixed difficulty or a pack-declared observation for Assess: left for later; no pack declares one, and a profile parameter would change every frozen profile's hash.
- Revealing only a Health band: rejected as less useful than the sheet values with no gain in safety, since those values are what an attack already tests against.
- Keeping revealed values in later receipts: rejected; they are a reading at one moment and Health changes, so the event is the record.

## Consequences

- New intentions `READY_ITEM` and `ASSESS`; new public events `ITEM_READIED` and `ASSESSMENT_RESOLVED` with English and Italian statements, internal `ASSESSMENT_CALCULATED`; receipt `combat.readied`, `combat.assessment`, `options.ready` {cost, available} and `options.assess` {cost, targets}; migration V31 and backup schema 31. No new refusal category or message.
- Limits: readying moves one item per action and never swaps; a player holding two instances of one weapon gets one attack, as at entry; ammunition is not readied, because consumed stacks stay unworn; non-player participants neither ready nor assess (P12.3/P12.4); an assessment's values are not kept as player knowledge beyond the event; the Insight check and its use of Resistance are provisional numbers (P18.3). Withdraw, the stalemate policy and settlement are the rest of P11.5.

## Verification

`ReadyAssessTest` (pure), `ReadyAssessCommandsTest` (real PostgreSQL), `TypedCommandTest`, `EventStatementsTest` and the V31 step of `CanonicalMigrationUpgradeTest`. See the [roadmap entry](../16-development-roadmap.md#15-p11--deterministic-combat-foundation) for the recorded results and what was not proven.
