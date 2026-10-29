# ADR 0023: Character rule vocabularies, derived statistics, conditions and equipment constraints

- Status: Accepted and implemented in P10.1 (29 October 2026). Commands that use these rules during play arrive with P10.2 and later.
- Date: 29 October 2026
- Builds on: [ADR 0017](0017-bounded-pack-semantics.md) (bounded expressions), [ADR 0019](0019-local-campaign-admission.md) (creation), [ADR 0011](0011-visual-novel-and-persistent-artwork.md) (artwork is presentation only).

## Context

Before P10.1 a character was a row of attribute and skill numbers, resources, item stacks and learned abilities (V9). Packs could name a `slot` on an item and a `rarity` tier, but nothing checked the slot, no hand rule existed, conditions had no runtime state, and derived values such as defense had no definition. The specification requires that derived statistics use a pinned formula, that hand occupancy, slots and capacity are checked together, that rarity is a validated tier, and that artwork never decides a mechanic.

## Decision

1. **Vocabulary belongs to the pack that declares the creation profile.** Attribute ranges, derived statistics, hands and equipment slots are read from the actor's creation-profile pack, exactly like resource keys. An item from a dependency pack is wearable only where its slot key exists in that vocabulary. Otherwise it is reported as `UNKNOWN_SLOT`; the engine never guesses a mapping.
2. **Two new rule types.** `equipment` declares `hands` (1–4, default 2) and 1–16 slots with `capacity` (1–8) and optional accepted `tags`. `derivedStats` declares statistics with a formula and declared `min`/`max`. A pack may have one `equipment` rule.
3. **Derived statistics are pure.** A formula may read attributes and skill ranks only. Dice and resource atoms are rejected when the pack is compiled, so the value is reproducible from stored base values. The proven interval of the formula must lie inside the declared bounds. Derived values are never stored: the sheet is recomputed from committed rows on every read, so a cached value cannot go stale and the campaign revision identifies the inputs. A statistic whose attributes the actor does not hold is absent, not zero.
4. **Conditions are rows with a policy.** `actor_condition` holds stacks and remaining rounds. `none` ignores a second application, `refresh` restores the duration, `stack` adds a stack up to `maxStacks` (1–10, default 3) and restores the duration. Modifiers apply per stack and the effective attribute stays inside its declared range.
5. **Equipment is one row per worn instance.** `equipment_assignment` has the instance as its key, so an instance is worn at most once. A composite foreign key ties it to the holding actor, so an equipped item cannot be transferred until it is unequipped. An equippable item must not stack (`maxStack` 1). The rule check combines slot existence, tag compatibility, slot capacity and the actor's total hands, reports every violated constraint, and an item's slot, hands and tags come only from its definition. The database independently bounds stack size and the four-hand maximum.
6. **Rarity is the declared tier ID**, copied from the item definition into the rule catalog. The catalog has no appearance field, so artwork cannot change rarity, slot, hands, tags or any bonus.
7. **Creation may equip explicitly.** A starting item with `equipped: true` is worn from the start and validated by the same rules at compile time (local items) and at campaign creation (any pinned item). Nothing is ever equipped implicitly.
8. **Operations are narrow typed services** (`CharacterService`) run inside the caller's transaction with an actor row lock, and they refuse to run without a transaction. They are not commands yet: atomic use, transfer and equip commands with events are P10.2.

## Alternatives considered

- Storing derived values and invalidating them: rejected because every consumer would need the invalidation rule and a missed one shows a wrong number.
- Letting the player choose the slot at equip time: rejected for now; a definition naming one slot is deterministic and explainable. A multi-slot item can be added later as a superseding decision.
- Tracking hands per slot instead of per actor: rejected because a two-handed weapon and a shield in different slots must compete for the same hands.
- Putting rarity effects (price, loot odds) in the catalog: out of scope. A mechanical effect or price rule needs explicit supported rules data.

## Consequences

- Existing packs stay valid: without an `equipment` rule no item may name a slot. A pack that already named a slot without defining it now fails with a diagnostic (no bundled fixture did).
- V18 adds two tables and one unique constraint; the backup tool covers them at schema version 18.
- Rank advancement, XP and level changes are not implemented here ([progression](../12-encounters-and-progression.md)); `learnAbility` records rank 1 only.
- The tick effects of conditions and condition removal rules during rest belong to combat (P11) and time (P10.4); `elapse` only shortens durations.
