# ADR 0024: Atomic item commands, unit conservation and source-linked item creation

- Status: Accepted and implemented in P10.2 (29 October 2026). Quest rewards that name items (P10.3), item-bearing panels (P10.5) and loot ownership (P13.4) build on it.
- Date: 29 October 2026
- Builds on: [ADR 0003](0003-canonical-transactions.md) (one canonical commit), [ADR 0017](0017-bounded-pack-semantics.md) (bounded expressions), [ADR 0023](0023-character-rules-and-equipment-constraints.md) (equipment constraints), [ADR 0011](0011-visual-novel-and-persistent-artwork.md) (artwork is presentation only).

## Context

After P10.1 an actor could hold item stacks and wear items, but nothing in play changed them: no command used, gave, traded, wore or created an item, and `createItem` was a compiled effect the resolver refused. The specification requires that trades commit offered items, prices, ownership and the event together; that a unique object is never held by two actors; that a generated reward is admitted as a valid instance with a recorded source; and that potion quantity and capped healing commit together (QA-10).

## Decision

1. **Five typed commands, no generic writes.** `USE_ITEM`, `EQUIP_ITEM`, `UNEQUIP_ITEM`, `TRANSFER_ITEM` and `TRADE_ITEMS` join the closed vocabulary. Each names item instances (and actors and unit counts) only; prices, slots, magnitudes and targets of patches are not payload fields. They run through the existing pipeline: admission, rule gate under the campaign lock, deterministic resolution, one canonical commit, journal receipt and replay.
2. **Items change through an `ItemPlan`.** The resolver edits an `InventoryModel` (a pure working copy of the participants' stacks) and returns the minimal `ItemChange` list (update owner and/or quantity, delete, insert), the units it creates (`Creation`) or uses up (`Consumption`) and the equipment changes. The commit applies the plan beside the events in the same transaction.
3. **Unit conservation is a proof, twice.** For every definition the net unit change of the instance mutations must equal created minus consumed units. `ItemPlan` proves it when it is built (before any lock), and `InventoryWriter` applies every update and delete guarded by the exact owner, quantity, definition and storage version the resolver read, so a plan resolved from state that moved on fails and rolls the whole commit back. A transfer or trade declares nothing and must balance by itself.
4. **Identity and merging.** Moving everything that is left of a stack reassigns the stack itself, so a unique item keeps its identity (and its artwork) from one owner to the next. Units entering an actor first fill that actor's non-full stacks of the same definition in ascending identity order, then open new stacks. New instance identities are derived from the operation, so a retried resolution proposes the same rows.
5. **Item use is a pack contract.** An item may declare `use`: `consumes` (default true), an optional `target` policy (`self`, `single`, `multiple`; `area` still needs encounter zones) and `effects` in the same validated grammar as an ability. Costs and prerequisites do not exist for items. The rule gate shares the target, vocabulary, draw-budget and executable-effect checks with abilities, so a potion and an ability use one effect resolver. A consuming use requires the instance to be unworn.
6. **Source-linked creation.** The `createItem` effect (with an optional `quantity` of 1 to 99 per recipient) is now executable. Every created unit is recorded in the append-only `item_creation` ledger (V19) with the committing operation, the announcing `ITEM_CREATED` event, a source kind and key, the recipient, definition and quantity. An `EFFECT` source key is `<operation>#<effect path>`. A `REWARD` source (reserved for P10.3 and P13) must name a reward settlement of the same commit by its exact source key. The ledger is unique per source, recipient and definition, so a replayed source cannot create a second time even if a defect bypassed the idempotent journal. One operation may create at most 64 units across all recipients; the bound is checked statically before anything is journaled.
7. **Trade is an exchange the counterparty accepts by rule.** An item may declare an explicit integer `value` (1 to 1,000,000 units). A trade needs a perceivable counterparty, unworn instances on both sides held in the stated quantities, every item priced, and a counterparty that receives at least the value it gives. Currency is therefore an item (a stack with a value), never a negative number: a quantity cannot go below one and a spent stack disappears. Wording, rarity and artwork never create value. A "price rumor" is not an offer; the command itself is the offer and the rule decides.
8. **Gifts are explicit and unilateral.** `TRANSFER_ITEM` moves units of an unworn instance to a perceivable actor without payment or consent. Taking from others needs a trade (or, later, loot rules).
9. **Equip and unequip reuse P10.1.** The rule gate calls the same constraint check as the commit (`CharacterService.equipViolations` and `equip`), so slots, tags, capacity and hands are checked together twice. A worn instance cannot be given, traded or consumed until unequipped; the equipment foreign key rejects it independently of the gate.
10. **Stable categories.** Four new rejection categories, all HTTP 422: `ITEM_UNAVAILABLE` (not held by the required party, unknown, not usable, or worn when it would leave), `EQUIPMENT_ILLEGAL`, `QUANTITY_ILLEGAL` and `TRADE_REFUSED`. A missing instance and another actor's instance share one category.
11. **Provisional timing.** Item commands take no world time and advance the game turn once, like the other exploration intentions. P10.4 assigns durations (drawing, resting, travel).

## Alternatives considered

- Applying item changes as generic row patches in the resolver: rejected; it would bypass the conservation proof and the stale-state guards.
- Recomputing the inventory from events instead of rows: rejected; the rows are the queried state for panels and rules, and events remain the narrative record.
- Always opening a new stack on transfer: simpler, but it fragments inventories and changes the identity of unique items, which breaks the identity-preserving artwork rule.
- Letting an NPC's attitude or a model decide trade acceptance: rejected; acceptance must be reproducible. A reviewed social rule can later refine the balance rule without changing the command.
- A `price` in the trade payload: rejected; prices are content, and a client-supplied price would be a generic effect.
- Putting the use contract on abilities only (an item grants an ability): rejected; abilities carry prerequisites and costs that would have to be invented for items.
- A foreign key from the ledger to `item_instance`: rejected; consumed or fully moved stacks may be deleted while their creation history must remain.

## Consequences

- V19 adds `item_creation`; the backup tool covers it at schema version 19. Existing saves gain an empty ledger and nothing else changes.
- Packs gain three optional fields (`value`, `use`, `createItem.quantity`); existing packs and their hashes are unchanged. Abilities may now create items.
- `CommandRuleCatalog` carries the character rule projection, so equip and unequip validate against the same pinned facts as campaign creation.
- Receipts gain an `items` list (kind, definition, instance when one is identifiable, quantity) only when the player's own items were involved. Items created for another actor stay in an internal event and out of the receipt.
- The AI intent proposals (`IntentKind`) still list only the original four intentions; offering item intents to a model is separate work.

## Limits

- NPC inventories exist only through transfers and creation; packs cannot yet declare NPC possessions or shops, and a player must know an instance identity to request it in a trade. A knowledge rule for NPC wares belongs with the inventory panels (P10.5).
- Weight, capacity and durability policies are not enforced; the value rule is the only economic rule.
- `damage`, `heal`, `move`, condition and `reveal` effects stay unsupported and are refused as `EFFECT_UNSUPPORTED`.
- Durations, drawing time and rest are P10.4; the inventory and equipment panels and the journal are P10.5.

## Verification

Recorded with the P10.2 task in the [development roadmap](../16-development-roadmap.md) (29 October 2026): 817 backend tests pass with zero failures on PostgreSQL 18.6 (`InventoryModelTest`, `ItemContractPackTest`, `ItemCommandsTest`, the reward-sourced creation case of `CanonicalCommitServiceTest`, `EventStatementsTest` and the extended typed-command, resolved-commit and V19 upgrade tests). Eleven deliberate weakenings, applied to an exported copy only, each made at least one test fail: ignoring the trade balance, allowing a worn gift, allowing a worn consuming use, dropping the creation ledger entry, skipping the item writer, disabling merging, loosening the owner guard, skipping the equipment check, dropping the participant attribution, and citing the wrong announcing event. The attribution probe first survived the suite, which exposed a missing test; `attributionRefusesItemEffectsOnUninvolvedActors` now covers it.
