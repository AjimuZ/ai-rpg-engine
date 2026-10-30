# Adventure Pack Specification


P06.1 adds an atomic snapshot consumer for creation: it loads only a
sealed revision in the selected adventure, verifies the byte inventory hash and
current ruleset contract, and recompiles it inside the creation transaction.
Player allocations cannot supply grants. Creation reloads the transitive dependency
closure using exact adventure-scoped ID/version/hash pins, verifies every sealed
inventory and ruleset, and recompiles each pack. No filesystem or network is read.
The closure permits at most 32 revisions, traversal depth 16 (root depth zero),
and 250 MiB of stored file bytes in total. Cycles and different versions of the
same pack ID fail closed; `pack#definition` references cannot distinguish versions.
These are campaign loading limits; registration can admit a larger dependency graph.

Creation references are resolved relative to their declaring pack and only its
direct declared dependencies. An external creation profile uses its own attribute
and resource rules; rules from unrelated packs are never merged. Persisted IDs and
custom skill allocation keys keep root-local IDs bare and qualify all other IDs
as `pack#definition`. Preset skills and starting grants receive the same canonical
identities. Actual dependency skill/stack caps are checked at creation, even when
registration validated only export metadata. The immutable root manifest and its
transitive sealed manifests pin the closure; no mutable dependency index is saved.
Preset skill maps accept the same typed qualified references as skill allowlists;
attribute/property map keys remain local identifiers. With export metadata alone,
the compiler checks external preset ranks against the global cap of ten; creation
then enforces each dependency skill's actual maximum before committing any state.

P06.3 consumes the existing required `entrypoint.situation` as the public starting
presentation. It resolves the fixed `$pack` `/entrypoint/situation` text key in the
campaign's narrative locale, with explicit source-language fallback. Only the
root's pinned sealed snapshot supplies prose, including when creation mechanics
come from a dependency. No new manifest fields, optional prologue, guide, quest
or hardcoded choice list are required. A versioned pending marker preserves the
waiting-for-input state across restart without copying privileged pack content.

## 1. Purpose and contract status

An adventure pack is a versioned set of declarative files describing playable content and supported rules configuration. It is not a script that can command the host computer. P04.1–P04.4 implement parsing, semantic compilation, protected directory/ZIP staging and immutable registration through internal Java APIs. Only `PackRegistry` admits staged bytes as a sealed `ContentRevision`; `ParsedPack` and `CompiledPack` alone do not establish admission. P04.5 adds two standalone neutral genre fixtures and import checks; P04.6 adds optional validated EN/IT text catalogs, policies and source-fallback reports. Import UI/endpoints and playable campaigns remain later work. The concrete file syntax of pack contract 1 (`schemaVersion: 1`) is fixed by [ADR 0010](adr/0010-name-scope-format-licensing.md#decision-initial-pack-file-format-pack-contract-1). It covers UTF-8 encoding, YAML 1.2 core-schema `.yaml` files without anchors, aliases, tags, or duplicate keys, `.json` as an equivalent, CommonMark `.md` prose without front matter or rendered HTML, and directory or `.zip` distribution.

The engine must not need a fixed plot. A minimal pack supplies world principles, a starting location/situation, character creation, a ruleset, and generation boundaries. Quests, endings, a prologue, companions, and a guide are optional.

## 2. Proposed file tree

```text
my-adventure/
  adventure.yaml
  world/
    overview.md
    locations.yaml
    factions.yaml
    timeline.yaml
    secrets.yaml
  characters/
    creation.yaml
    npcs.yaml
  gameplay/
    items.yaml
    abilities.yaml
    conditions.yaml
    progression.yaml
    encounters.yaml
    quests.yaml
  narrative/
    style.md
    generation.yaml
    anchors.yaml
  presentation/
    theme.yaml
    assets.yaml
    audio.yaml
  extensions/
    guide.yaml
    creative-abilities.yaml
  assets/
  LICENSE.md
  CREDITS.md
```

Only `adventure.yaml` and the files it requires are mandatory. Files can be split into subdirectories through explicit manifest references. There is no unrestricted include, script loader, or recursive remote fetch.

## 3. Manifest fields

| Field | Required | Meaning |
| --- | --- | --- |
| `schemaVersion` | Yes | Version of the pack contract |
| `id` | Yes | Stable namespaced pack identifier |
| `version` | Yes | Semantic content release version |
| `title`, `summary` | Yes | Display information |
| `authors` | Yes | Credited people/organizations, without required personal contact details |
| `license` | Yes for distributable packs | SPDX identifier or explicit license-file reference |
| `engineCompatibility` | Yes | Supported engine version range |
| `ruleset` | Yes | Supported ruleset ID, version range, selected policy profile |
| `contentLocale` | Yes | Language tag for story content |
| `narrativeMode` | Yes | Authored, emergent, or hybrid |
| `entrypoint` | Yes | Starting situation/location and creation profile reference |
| `files` | Yes | Explicit content file references and categories |
| `modules` | Yes | Enabled supported modules and configuration references |
| `generationPolicy` | Yes | Allowed content categories, budgets, canon constraints |
| `presentationProfile` | No | Theme, panels, scene/map/audio behavior |
| `contentGuidance` | No | Tone, themes, exclusions, audience guidance |
| `dependencies` | No | Locally installed, explicitly versioned content packages |
| `provenance` | No | Source/authoring information and inherited material |

The parser produces a deeply immutable JSON-shaped manifest and a content digest covering the root manifest and every explicitly listed file, including asset bytes, in sorted relative-path order. Import timestamps and installation paths are metadata outside that digest. A changed byte in the distributed pack produces a different digest even if the author forgot to bump its version; registration reports `HASH_CONFLICT` with both hashes. YAML/JSON equivalence means the same schema and values, not the same raw-byte digest.

### 3.1 Concrete manifest shape

The bundled [manifest schema](../backend/src/main/resources/pack-contract/1/manifest.schema.json)
is JSON Schema draft 2020-12. Unknown fields are errors, including inside nested
manifest records. This stage requires a nonempty license declaration for every
pack; P04.4 preserves that declaration but does not verify SPDX identifiers,
license-file contents or redistribution rights. Per-asset provenance and export
rights review remain part of the later asset/editor/export workflows. Version is a
SemVer release label. Engine/ruleset range strings and policy/module IDs are
structurally required; support and compatibility are checked by the compiler.
`contentLocale` retains its authored language tag; this stage checks its lexical
shape without mapping it to a player language or claiming locale coverage.
Compilation and registration accept supported source locales (`en`, `it`). P04.6
validates optional text overlays, glossary/quote policies and coverage separately
from the language-independent mechanical definitions.

```yaml
schemaVersion: 1
id: example:harbor
version: 1.0.0
title: Harbor
summary: A quiet harbor.
authors: [Example Authors]
license: MIT
engineCompatibility: '>=0.1.0 <1.0.0'
ruleset: {id: skirmish, version: '>=1.0.0 <2.0.0', policyProfile: standard}
contentLocale: en
narrativeMode: emergent
entrypoint: {situation: arrival, location: harbor, creationProfile: visitor}
files:
  - {path: world.yaml, category: locations}
  - {path: generation.json, category: generationPolicy}
modules: []
generationPolicy: generation.json
```

This illustrates manifest syntax, not a complete playable fixture. `files` is an
array of unique `{path, category}` records. The root is exactly one of
`adventure.yaml`/`adventure.json`, excluded from `files`. Every other supplied file
must be listed; hidden/unreferenced extra bytes are rejected. Logical paths use
portable ASCII names, `/`, at most 240 characters, and no empty, `.` or `..`
segments. Absolute paths, backslashes and case collisions fail. Filesystem
containment, reparse points, Windows reserved names, ZIP handling and media
type/size checks run in P04.4 staging/registration; this parser API receives bytes
and opens no paths.

Categories `lore`, `narrativeStyle`, `license`, `credits` require `.md`;
`asset` holds opaque binary bytes. Structured `.yaml`/`.json` categories are
`locations`, `factions`, `timeline`, `secrets`, `creation`, `npcs`, `items`,
`skills`, `abilities`, `conditions`, `progression`, `encounters`, `quests`,
`rules`, `generationPolicy`, `anchors`, `theme`, `assetCatalog`, `audio`, `guide`,
`creativeAbilities`. Category never comes from a filename guess.
`generationPolicy` names a listed file of that category; optional
`presentationProfile` names a listed `theme`. Modules are `{id, configuration?}`
records with unique IDs and optional listed structured-file paths. Dependencies
are `{id, version, hash}` records, where `hash` is lowercase hexadecimal SHA-256;
resolution/conflicts remain later compiler work. `contentGuidance` accepts
`tone`, `themes`, `exclusions`, `audience`; `provenance` accepts `source`, `notice`.
P04.6 adds structured `localization` and `textResources` categories. Optional
`localizations: {en: [path], it: [path]}` binds unique listed overlay files; optional
`textResources: path` binds one listed source-resource file. Unbound text files,
unsupported locales and reused overlay paths fail. See the exact
[localization contract](29-localization.md#4-pack-localization-contract).

The [definition-envelope schema](../backend/src/main/resources/pack-contract/1/definitions.schema.json)
publishes `{schemaVersion: 1, definitions: [{id, kind, data}]}`. It checks only
the envelope; `data` intentionally remains an object awaiting category-specific
P04.2–P04.3 schemas. Passing this schema cannot validate mechanics, visibility,
references or author constraints and cannot authorize registration.
The parser applies it to `locations`, `factions`, `creation`, `npcs`, `items`,
`skills`, `abilities`, `conditions`, `progression`, `encounters`, `quests`,
`rules`, `anchors`. Other structured categories currently require an object
root and safe parsing; their category-specific semantics are still unavailable.
Whole-value schema patterns require the actual end of input, including rejection
of a final line terminator in identifiers, versions, locales, paths and hashes.
They use an ECMAScript-compatible negative any-character assertion because `$`
alone can match before a final newline.

### 3.2 Normalized hash protocol

Pack raw protocol 1 computes SHA-256 over the UTF-8 domain
`rpgengine.pack.raw.v1` followed by one NUL byte, then a four-byte unsigned
big-endian file count. For each ASCII relative path in ascending ordinal order,
append its four-byte big-endian UTF-8 byte length, its UTF-8 bytes, the eight-byte
big-endian raw file byte length, and the 32 raw SHA-256 digest bytes of that file.
The root manifest participates exactly like other files. The result and per-file
digests use lowercase hexadecimal. No JSON reserialization, whitespace/comment,
Unicode, BOM or line-ending normalization occurs. Identical inventories have
identical hashes regardless of caller map order or host root; renamed files,
changed comments, assets, LF/CRLF or YAML/JSON source representations change them.
The immutable output does not expose caller-owned byte arrays. Future revisions
must preserve this framing or introduce an explicit new digest protocol.

## 4. Definition model

| Definition | Required design data |
| --- | --- |
| Location | ID, name, public description, links, accessibility, tags, initial conditions, knowledge visibility |
| NPC | ID, identity, role, personality constraints, goals, initial location, actor profile, private/public facts |
| Faction | ID, public identity, goals, relationships, relevant clocks, initial knowledge |
| Item | ID, type, stack/slot policy, validated rarity tier and mechanical properties, public appearance/visual identity, description, provenance |
| Ability | ID, display text, prerequisites, action/resource costs, target policy, effect graph, tags |
| Condition | ID, timing, duration, stacking policy, supported modifiers/effects, removal conditions |
| Quest | ID, discoverability, objectives as predicates, transitions, outcomes, settlement policy |
| Encounter template | Eligible scene tags, roles, roster bounds, threat weights, zones, objectives, escape policy |
| Narrative anchor | Optional situation or potential development, eligibility, flexible outcomes; never an event already completed |
| Character creation | Allowed names/backgrounds/roles, attribute budget, skills, starting items and abilities |
| Progression profile | Thresholds, rewards, purchase choices, unlock requirements, caps |

P04.2 implements the semantic shapes for rule (`attributes`, `resources`, `rarityTiers`, plus `derivedStats` and `equipment` since P10.1 and `rest` since P10.4), location, faction, NPC, item, skill, ability, condition and creation definitions in `PackCompiler`; each `data` object is closed, so unknown fields are errors. Rule definitions supply the attribute, resource and rarity vocabularies that items, creation profiles and abilities are checked against. Item appearance identities are unique per pack and items must name a defined rarity tier when tiers exist. Creation profiles bound attribute and skill budgets, archetype presets and starting stacks. P04.3 adds quest definitions: visibility, initial status (`undiscovered`/`available`), optional issuer/faction, an optional `availableWhen` predicate, 1..32 objectives with `when` predicates, and 1..8 outcomes (`success`/`failure`/`alternative`, at least one success) with optional item rewards and consequence texts. Since P11.1 encounter definitions and the `combat` rule type are implemented (below); narrative anchor and progression definitions still have no semantic schema and are rejected (`UNSUPPORTED_KIND`) rather than ignored.

**Combat profile and encounters (P11.1).** [ADR 0028](adr/0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md) adds one rule type and the `encounter` kind, both closed and bounded:

- `combat` (at most one per pack): `roles` maps `force`, `finesse`, `insight` and `resolve` to attributes of the pack (`REFERENCE_UNKNOWN` otherwise; several roles may share one attribute); `health` names a resource of the pack and the optional `focus` another one (`DUPLICATE` when equal). `scheduler` may only be `initiative-rounds` and `space` only `zones`: a different scheduler or spatial model is an engine extension (section 6, level 5), not pack data, and fails as `SCHEMA`. `values` may override any provisional parameter of [docs/11 section 17](11-combat-system.md#17-provisional-numerical-defaults) inside its bound: `actionPoints` 1..6 (default 3), `reactionsPerRound` 0..3 (1), `roundSeconds` 1..60 (8), `roundCap` 1..100 (30), `guardBase` and `resistanceBase` 0..20 (10), `equipmentGuardCap` 0..6 (3), `situationalGuardCap` 0..8 (4), `armorMax` 0..10 (4), `guardBonus` 1..4 (2), `partialCoverBonus` 0..4 (2), `situationalModifierCap` 0..6 (3), `strongHitMargin` 1..20 (6), `strongHitDieSides` 2..12 (4), `maxRangeEdges` 0..4 (2), `speechPerActivation` 0..3 (1), `difficultTerrainMultiplier` 1..3 (2), and the action costs `moveCost` 1..3 (1), `strikeCost` (2), `guardCost` (1), `useItemCost` (2), `readyCost` (1), `interactCost` (1), `assessCost` (1) and `withdrawCost` (2), each 1..6. An unknown parameter is `SCHEMA`; a cost above `actionPoints`, a difficult edge costing more than one activation, or a Guard or cover bonus above `situationalGuardCap` is `CONSTRAINT_CONFLICT`. The combat rule is validated after the attribute and resource rules, whatever the file order.
- `encounter` (category `encounters`): `name`, optional `description`, `location` (the location where `ENGAGE` can start it), 1..16 `zones` (`id`, `name`, up to 8 `tags`, `cover` `none`/`partial`/`full`, `visibility` `clear`/`obscured`, optional `capacity` 1..16), up to 64 directed `edges` after expansion (`from`, `to`, `difficult`, `blocked`, `bidirectional` default true), `playerZone`, optional `escapeZones`, 1..8 `participants` (`id` other than `player`, `name`, `side` `ally`/`hostile`, `zone`, optional `npc` reference, `threat` `minor`/`standard`/`elite`/`anchor`, and `stats` with `health` 1..999, `focus` 0..999, `guard` and `resistance` 0..40, `armor` 0..the profile's `armorMax`, and `force`, `finesse`, `insight`, `resolve` 0..6, default 0), 1..8 `objectives` (`id`, optional `name`, `kind` `defeatHostiles`, `escape`, `reachZone` with `zone`, `survive` with `rounds` 1..`roundCap`, or `protect` with an allied `participant`; a kind accepts only its own parameter) and `opening` `initiative` (default), `first` or `forbidden`. The pack must declare a combat rule (`CONSTRAINT_CONFLICT`). Zone references are `REFERENCE_UNKNOWN`; a self-edge, a duplicate edge, a disconnected zone, an escape or objective zone that equals the start or cannot be reached along unblocked edges, an escape objective without escape zones, a zone over its starting capacity, no hostile participant, or more hostiles than party size plus two are `CONSTRAINT_CONFLICT`. Zone, participant and objective names are localizable public text. Since P11.2 ([ADR 0029](adr/0029-initiative-rounds-activations-and-bounded-combat-dialogue.md)) an encounter may also declare up to 8 `actions`, the only way words or interactions change a fight: each has `id`, `name`, optional `description`, `kind` `social` or `scene` and an optional `cost` (1..the profile's `actionPoints`, default its `interactCost`). A `social` action needs `intent` (`persuade`, `intimidate`, `negotiate`, `distract` or `request`) and may set `recipients` (`hostile` default, `ally` or `any`). A `scene` action may name the `zone` it is used from and needs `passage` {`from`, `to`, `blocked`}: two zones joined by at least one declared edge (`REFERENCE_UNKNOWN` otherwise) and the state the action leaves every edge between them in. Unknown fields, a social field on a scene action and the reverse are `SCHEMA` errors; action names are localizable public text.

P10.1 ([ADR 0023](adr/0023-character-rules-and-equipment-constraints.md)) adds two rule types and a few fields, all closed and bounded:

- `equipment`: `hands` (1–4, default 2) and 1–16 `slots` with `id`, `name`, `capacity` (1–8, default 1) and optional `accepts` tags. A pack has at most one equipment rule. A slot with `accepts` takes only items carrying one of those tags.
- `derivedStats`: 1–16 `stats` with `id`, `name`, a numeric `formula` over `attribute` and `skill` atoms (dice and `resource` atoms are `SCHEMA` errors) and `min` (default 0) and `max`. The formula's proven interval must lie inside the declared bounds (`RANGE`); an ID may not equal an attribute or resource (`DUPLICATE`). Derived statistics are validated after the other rules, so file order does not matter.
- Item: `slot` must name a slot of the pack's equipment rule (`REFERENCE_UNKNOWN`, listing the defined slots; a pack without that rule has none), `hands` (0–4, at most the pack's hands, and only with a slot), `tags` (up to 16 identifiers, one must match a slot that accepts tags: `CONSTRAINT_CONFLICT`) and `maxStack` 1 for any item with a slot, because an assignment names one instance. `rarity` is unchanged: a declared tier, never derived from `appearance`.
- Condition: `maxStacks` (1–10, default 3 for `stack`); any other stacking policy allows exactly one stack.
- Creation `startingItems[].equipped` (default false): the item starts worn. Local items are checked at compile time for slot capacity and hands (`CONSTRAINT_CONFLICT`, listing every equipped item); items from dependencies are rechecked against the pinned closure when the campaign is created.

References are plain local IDs, or `packId#definitionId` for a definition exported by a declared dependency. A dependency (`id`, `version`, `hash`) must be resolved by a locally installed pack through the `DependencyCatalog`; the compiler never downloads anything, and an unresolved, duplicated or self-referencing dependency is a `DEPENDENCY` error. Reference diagnostics use `REFERENCE_UNKNOWN` and `REFERENCE_KIND`; other codes are `DUPLICATE_ID`, `KIND_MISMATCH`, `RANGE`, `DUPLICATE` and `SCHEMA`.

Definitions reference stable IDs, never display-name matching. A label can be localized. Unknown optional data must be in a namespaced extension block validated by an enabled module; arbitrary unknown mechanical fields are errors.

## 5. Markdown versus structured data

Markdown holds atmosphere, lore descriptions, personality notes, and author guidance. YAML or JSON holds mechanics, references, conditions, starting state, and generation policies. Front matter is not part of pack contract 1: a leading metadata block in a Markdown file is a blocking error, so prose cannot hide operational instructions or a second set of metadata. A later contract version may add one documented front-matter schema.

Prose may say that a sword is remarkable, but a numerical bonus requires a structured effect. A lore note saying “this always succeeds” cannot override target legality, resource bounds, or engine permissions. Import diagnostics should point to this mismatch rather than relying on the model to reconcile it.

Secrets use separate files and explicit visibility. They may be shipped in a full author pack but are not sent to player projections. A full author export inherently contains secrets; a player-facing campaign export is a separate product action.

## 6. Supported customization levels

1. **Presentation:** names, terminology, text, art, panel visibility, theme tokens, music mappings.
2. **Content:** NPCs, factions, locations, objects, quests, abilities, encounter templates.
3. **Rules data:** attribute/skill definitions and limits, costs, progression, approved formulas, allowed condition combinations.
4. **Policy selection:** difficulty bands, defeat/recovery policy, retreat behavior, loot policy, authored/emergent mode.
5. **Engine extension:** new effect primitive, scheduler, spatial model, or custom execution. Requires reviewed engine code in a future implementation phase; it cannot be achieved safely by arbitrary prose or JavaScript in a pack.

## 7. Bounded expressions and effects

The proposed expression language permits constants, allowed stat/resource references, arithmetic, bounded comparisons, boolean combinations, min/max/clamp, and references to supported dice expressions resolved by the backend. No loops, recursion, reflection, file access, network access, evaluation of host-language code, or imported functions.

Validate expression depth, node count, numeric ranges, division by zero, overflow, missing references, and evaluation cost. Recommended initial limits are depth 12, 200 nodes per formula, 20 effect nodes per ability, and eight targets per normal action. Ruleset-specific larger actions require explicit bounded support.

Effects are declarative nodes such as damage, heal, move, apply/remove condition, modify a supported resource, reveal a permitted fact, or create a bounded item instance from an admitted definition. Each has target, timing, source, and validation rules. Conditional effects are acyclic and use pre-resolution or post-resolution timing explicitly.

P04.3 implements this as a static analyzer (`Expressions`); nothing is evaluated at import. An expression is an integer or boolean constant, or a single-key object naming an operator (`add`, `mul`, `min`, `max` with 2..8 operands; `sub`, `div`, `clamp`; comparisons `eq ne lt le gt ge`; `all`, `any`, `not`) or an atom. Actor-scope atoms read `attribute`, `resource`, `skill` and `dice` (`{count 1..20, sides 2..100}`); world-scope quest predicates read `visited`, `possesses`, `questStatus`, `objective` and (since P10.4) `worldTime` and may not roll dice. `worldTime` takes one unit (`seconds`, `minutes`, `hours` or `days`) and is the number of whole units of the bounded in-world clock that have elapsed since the campaign began, rounded down, with the clock's own proven interval (for example `{"ge": [{"worldTime": "hours"}, 8]}`); it is not readable in actor-scope expressions. Limits: depth 12, 200 nodes, 40 dice, integer constants within ±1,000,000, and every intermediate interval within ±10⁹. Numbers and booleans never mix. Violations are `LIMIT`, `COST`, `OVERFLOW`, `DIVISION_BY_ZERO` (a divisor interval containing zero), `RANGE` (inverted clamp), `REFERENCE_UNKNOWN`/`REFERENCE_KIND` or `SCHEMA`.

**Time and rest (P10.4).** A location link may declare `minutes` (an integer 0..1,440, default 0): the in-world time a move along that link takes. A `rule` definition of `ruleType: rest` lists `policies` (1..16), each with `id`, `name`, `minutes` (1..1,440), an optional `locationTag` (the player's location must carry that tag), `interruptionChancePercent` (0..99, default 0), up to eight `restores` (`resource`, `amount` 1..1,000,000; the resource must be declared by a `resources` rule of the pack and is restored up to its maximum) and up to eight `removesConditions` (condition references). A policy must restore a resource or remove a condition; policy IDs are unique within the pack, and restored resource keys belong to the declaring pack's vocabulary like ability costs. Rest rules are validated after the other rules because they read the resource vocabulary. See [ADR 0026](adr/0026-bounded-world-clock-travel-duration-and-rest.md) and [rest](10-gameplay-modules.md#8-rest-recovery-and-defeat).

**Quest semantics (P10.3).** The engine evaluates quest predicates over committed state ([ADR 0025](adr/0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md)). `initialStatus: undiscovered` becomes `available` in the first evaluation where `availableWhen` holds, and always when it is absent, so an author who wants a gated quest writes the predicate. `visibility: secret` makes the quest hidden background content: the engine starts it as soon as it is available, the player cannot address it, and only its ending is public. Objective `when` predicates are evaluated in authored order; outcomes in authored order, and the first that holds ends the quest (`success` and `alternative` complete it, `failure` fails it). `optional` on an objective is informational: it gates nothing unless a predicate names it. An outcome may reward at most 32 item units in total (the compiler reports `LIMIT`); rewards naming the same item are merged and granted once, to the protagonist, under the source key `quest:<id>:outcome`. `visited` is true for every location the player has been recorded at and for the current location; `possesses` counts units across all the player's stacks. Consequence texts are not yet shown by any screen.

Effect types are `damage`, `heal`, `move`, `applyCondition`, `removeCondition`, `modifyResource`, `reveal`, `createItem` and `conditional`; amounts are expressions whose derived interval must stay inside the effect's bounds. A `conditional` branches on an actor boolean expression into `then`/`else` lists. Nesting is limited to 4 levels and each top-level list to 20 effect nodes including nested ones. Timing (`pre`/`post`) is inherited from the enclosing conditional, and a contradicting nested timing is `SCHEMA`. Ability targets use `self`, `single`, `multiple` or `area` with `maxTargets` 1..8. Conditions define stacking (`none`, `refresh`, `stack`), `durationRounds`, up to 8 attribute-unique modifiers in −100..100, and tick effects. Ability prerequisite chains, conditions applied by condition ticks, and quest predicates that unconditionally require another quest (not one alternative of a multi-way `any`, nor under `not`) form dependency graphs; a cycle is a `CYCLE` error whose message lists the path (`Cyclic quest dependency: a -> b -> a`). A quest predicate may read its own objectives without creating an edge.

**Item values and use contracts (P10.2).** An item may carry an integer `value` (1 to 1,000,000 trade units; absent means the item is unpriced and cannot be traded) and a `use` object: `consumes` (boolean, default `true`: one unit is used up), an optional `target` (`policy` `self`, `single`, `multiple` or `area`, `maxTargets` 1..8, default `self`) and `effects`, a non-empty list in exactly the grammar, node budget and timing rules of an ability's effects (there are no costs and no prerequisites). A `createItem` effect names an `item` and may carry `quantity` 1..99 (default 1 per recipient). Value, rarity, artwork and prose never substitute for each other: only `value` prices an item, only `use` makes it usable. The engine executes `modifyResource`, `createItem` and `conditional` nodes; the other types are refused when the item or ability is used ([P10.2 intentions](07-turn-protocol.md#p102-item-intentions), [ADR 0024](adr/0024-atomic-item-commands-and-source-linked-creation.md)). Existing packs and their content hashes are unchanged.

## 8. AI generation policy

Per category, select `disabled`, `catalogOnly`, or `bounded`. A bounded policy defines eligible tags, existing templates, numerical ranges, maximum new entities per scene, allowed novelty, forbidden contradictions, knowledge scope, budget, and approval behavior.

Generated additions remain proposals until validated and committed. Record generation profile, content source, base revision, references, and assigned campaign ID. The same named NPC must resolve to an existing identity when appropriate. A later scene must retrieve the admitted definition rather than generate the person again.

Presentation policy separately selects `disabled`, `catalogOnly` or `bounded` scene/portrait/item art, authorized style/reference assets and generation budgets. Artwork belongs to the admitted entity/appearance identity, is durably saved and reused, and never changes mechanical definitions. Pack-defined rarity tiers carry stable IDs, localized names and accessible presentation tokens; any mechanical effect or pricing rule must be explicit supported rules data. See [presentation assets](23-presentation-assets.md).

P04.3 compiles the manifest's `generationPolicy` file into an immutable `GenerationPolicy`. `categories` maps `locations`, `factions`, `npcs`, `items`, `abilities`, `conditions`, `quests` or `encounters` to a `mode`. Categories that are absent are disabled. A `bounded` category requires `maxNewPerScene` (1..8) and `maxNewPerCampaign` (≥ the scene budget, ≤ 1,000), and may list `tags`, authored `templates` of the category's kind, and up to 16 numeric `ranges` (`{min,max}`). `constraints` lists `required` entity IDs, `fixed` entries (`entity` plus the authored `fields` generation may not touch) and `excludedTags`. Constraints may only name this pack's own definitions, and a fixed field must already be authored. An excluded tag that a bounded category allows, or that a required entity carries, is `CONSTRAINT_CONFLICT`. The pure `GenerationGate` checks proposals against the compiled pack. `checkNew` rejects disabled/catalog-only categories, authored IDs, exhausted budgets, tags/templates/ranges outside policy, excluded tags and unauthored references. `checkFill` rejects fixed fields and any field already authored. `checkRetire` rejects removing a required entity. It reports every violation at once. Runtime callers arrive with scene generation (P10/P12).

In combat, generated mechanics must use supported templates/effects and fit the frozen budget. Codex cannot invent an ability that bypasses the player's only available defense or retroactively increase a monster's HP. Secret content cannot be hinted by a public media cue.

## 9. Import and compilation pipeline

P06.5 can project sealed originals into separate private specification/authored-media
folders when `engine.workspaces.root` is configured. Registration commits PostgreSQL
first, then publishes an atomic revision directory; projection failure leaves the
sealed registration intact and an identical retry reconciles it. Startup rebuilds
missing directories from the same canonical bytes. These files are not editable
pack authority, player exports or public assets; see [workspace storage](25-adventure-workspaces.md#2-separate-managed-folders).

P04.4 implements `PackStaging.directory(Path)` and `archive(InputStream)` followed
by `PackRegistry.preview(adventureId, staged)` or `register(adventureId, staged)`.
Staging buffers bytes in memory without extraction or execution; its inventory
has a private constructor and defensive byte-array copies. Preview compiles and
returns a deterministic report without writes. No import endpoint or UI is exposed.

1. Stage the owner-selected directory/archive in a non-executable area.
2. Reject absolute paths, `..` traversal, symlinks/reparse points, case collisions, executable payload types, oversized or explosive archives, and remote includes.
3. Parse with safe YAML 1.2 core-schema and JSON settings. Prohibit tags and object construction, reject anchors, aliases, merge keys, and duplicate keys (contract 1 allows no aliases), and bound nesting and file count.
4. Validate manifest and individual definition schemas, preserving filename/line/field locations.
5. Resolve dependencies and cross-file references; detect duplicate IDs and unsupported versions.
6. Validate rules, effect graphs, expressions, creation budgets, quest cycles, and initial invariants.
7. Validate asset paths/types/hashes and the required nonempty license/authorship declarations; these declarations do not prove rights.
8. Detect direct mechanical contradictions and issue warnings for unverifiable narrative ambiguity.
9. Produce a normalized immutable pack revision and an import report.
10. Register only if no blocking errors remain. Preview is read-only; activation requires the owner's import action.

Default staging limits are 100 MiB compressed, 250 MiB expanded, 2,000 files,
4,000 total file/directory entries, 32 MiB per file, and 50:1 expanded bytes to
whole archive bytes. Text categories have a further 1 MiB parser limit, including
Markdown. `PackStaging.Limits` can tighten budgets for callers/tests; parser and
media ceilings still apply. Actual reads/inflation enforce budgets independently
of declared lengths. Concurrent imports need a future request-level memory budget;
the per-pack limits are not a bound on process-wide memory.

Portable logical names are ASCII, at most 240 characters and 16 path components.
Each component starts with an ASCII letter, digit or underscore and otherwise
contains only letters, digits, underscores, dots and hyphens; trailing dots and
Windows device names (including stems before extensions) are forbidden. Paths
cannot be absolute, contain backslashes/drive prefixes, dot traversal or empty
components. File and directory-prefix spellings must agree under case folding;
files cannot shadow directory names. Diagnostics contain bounded logical names,
never host source paths. Directory staging rejects links/junctions/special files
and opens files without following final links. The owner-selected source tree
must remain under owner control and quiescent: portable JDK path traversal does
not prevent another local process replacing an intermediate directory during
the walk. Use the in-memory ZIP boundary for untrusted distributed content.

ZIP accepts only stored/deflate, single-disk, nonencrypted, non-ZIP64 entries with
portable ASCII names. Central and local names, methods, flags, CRCs, sizes and
streaming descriptors must agree. Local records must be contiguous with no
overlap, prefix or hidden entries; every deflate stream must finish and consume
all compressed bytes. Links, special files, directory payloads, unknown flags,
truncation and CRC mismatches fail closed. A single top folder containing the
manifest is stripped only when every entry belongs to it; its name and ZIP
metadata do not enter the content hash. Extra fields never override entry names
or trigger filesystem writes. [ADR 0018](adr/0018-protected-pack-registration.md)
records the accepted subset and storage decision.

Assets allow PNG, JPEG, WebP, Ogg, MP3 and WAV with matching extension and magic
bytes; SVG, HTML, scripts, fonts and nested archives are refused. Images must
declare sides of 1..8,192 and at most 32 Mi pixels. Inspection is bounded header
validation, not full codec validation or a decompressed-memory guarantee. Future
decoding/serving must impose allocation, frame/duration and audience restrictions,
validated MIME and `nosniff`; there is no media-serving API yet.

Registration locks the owning adventure, compiles against only its already sealed
local dependencies, and stores original bytes, file hashes, revision coordinates,
exports and an import report in one PostgreSQL transaction. Same ID/version/hash
returns the existing revision; different bytes require a new version. Legacy
metadata without a sealed inventory cannot satisfy a retry (`UNSEALED_REVISION`).
V7 file/report triggers reject updates/deletes and serialize file insertion and
sealing on the revision row under READ COMMITTED; other write isolation levels
are rejected. A seal checks count/total size, each file's digest/size is recomputed
by the database, and late insertion is forbidden. Failed sealing rolls back the
entire registration. `PackRegistry.files` verifies the full content hash on reload.

Report version 2 adds localization coverage to the version-1 pack/ruleset
coordinates, source locale, file hashes,
sizes/categories/media types/dimensions, definition counts and compiler warnings.
V8 accepts versions 1 and 2 without rewriting seals; identical re-imports retain
the original report, while a new preview can report current coverage. It excludes host paths, timestamps and archive metadata; PostgreSQL JSONB may
reorder object keys without changing its meaning. The provisional ruleset hash
frames the ruleset ID/version and sorted policy profiles with SHA-256; it is a
contract descriptor until P07 pins executable mechanics, not a kernel code hash.

The P04.1 in-memory boundary enforces at most 2,000 files/250 MiB total,
1 MiB per text file (including Markdown), depth 32, 20,000 nodes including mapping
keys, 65,536 UTF-16 units per scalar, 128 source characters per number and exact
decimal scale magnitude at most 1,000. YAML uses parser events and an explicit
[YAML 1.2 core resolver](https://yaml.org/spec/1.2.2/#1032-tag-resolution), never
the library's YAML 1.1 scalar resolution or object constructors. `yes/no/on/off`,
timestamps and `1:30` remain strings; `0777` is decimal 777, `0o17` is 15.
Nonfinite numbers and YAML directives are rejected as contract restrictions;
quoted scalars stay strings. JSON is strict RFC syntax with one root, no comments,
trailing commas, duplicate/merge keys or unpaired Unicode surrogates. Diagnostics
carry a stable code, relative filename, one-based line/column and RFC 6901 pointer;
raw parser messages and source snippets are not returned. Markdown is returned as
unrendered text; future rendering must preserve the HTML/remote-fetch restrictions.

## 10. Diagnostic examples

| Condition | Required diagnostic |
| --- | --- |
| Ability references an unknown resource | File, ability ID, field path, missing resource ID, supported alternatives |
| Item requires an undefined equipment slot | Item ID and missing slot reference |
| Starting loadout violates hand capacity | Actor profile, conflicting items, source definitions |
| Quest completion depends on itself | Cycle path and affected quest IDs |
| Mandatory scene requires an impossible capability | Entrypoint/anchor path and unsatisfied prerequisite |
| Unsupported module | Module ID, required engine contract version |
| Image path escapes pack root | Path rejected; no host content included in output |
| Same ID/version has changed bytes | Existing/new hashes; require a new release or explicit local development revision |

Do not label a pack valid merely because YAML parses. Narrative reachability cannot always be proved statically; distinguish warnings from guaranteed checks.

P04.3 compiler codes: `engineCompatibility` and `ruleset.version` are SemVer 2.0.0 ranges of 1..4 space-separated comparators (`>=`, `<=`, `>`, `<`, `=`, default `=`); the pack and dependency versions are exact SemVer versions; malformed ones are `VERSION_SYNTAX`. The pack is checked against `EngineContract.current()` (engine 0.1.0, ruleset `skirmish` 1.0.0, provisional until P07, policy profile `standard`, no modules), giving `INCOMPATIBLE_ENGINE`, `INCOMPATIBLE_RULESET` and `UNSUPPORTED_MODULE`. `HASH_CONFLICT` fires when the catalog already has the same pack ID and version with a different content hash, or when a dependency's declared hash differs from the installed one. Other codes are `CYCLE`, `CONSTRAINT_CONFLICT` and the expression codes from section 7. The only non-blocking result so far is the `UNREACHABLE_LOCATION` warning (`CompiledPack.warnings()`). It names locations that cannot be reached over links from the entrypoint. Locked links count as reachable, and move effects are ignored, so this is a heuristic rather than a guarantee.

## 11. Author workflow

Write a minimal manifest and world overview. Define character creation and starting situation. Select the default ruleset. Add only content needed for the first scene and set generation boundaries. Run validation, inspect diagnostics, preview a campaign, and perform a short playtest. Revise files, bump version, and import a new revision. Existing campaigns continue on their pinned snapshot.

A future editor must write this same contract. Its visual form cannot become a second incompatible authoring model.

Author-specified facts, required entities and supported constraints are fixed inputs, not suggestions the narrator can replace. Use stable IDs and supported requirement predicates; generate only permitted missing details within policy/budgets. Missing required technical fields and contradictory constraints are diagnostics, not permission to invent rules. The integrated text/graphical editor, genre examples and fixed/open workflow are in [the later editor roadmap](26-adventure-editor-roadmap.md).

## 12. Engine-generic acceptance

One neutral fantasy exploration pack and one science-fiction salvage pack must differ in vocabulary, world structure, skills, resources, assets, and optional guide/creative-ability settings. Both must run with the same built engine. Neither needs a full authored plot. Shared mechanics can remain AI RPG Skirmish while their presentation and supported numerical configuration differ.

P04.5 supplies [Mosslight Trail and Silent Relay](../backend/src/test/resources/packs/README.md)
as complete contract-1 test-resource directories with explicit inventories,
connected location graphs, creation profiles, abilities, quests, original MIT
map assets and source-language prose. `NeutralGenreImportTest` imports both
unchanged through the same registry and recompiles stored bytes; it also proves
directory/ZIP equivalence, idempotency, adventure-local revision identities and
located rejection of broken references without partial writes. Production source
and the engine contract are unchanged. This establishes the import portion of
QA-01/02, not gameplay or full QA-33. The current contract has no module runtime,
so both packs keep `modules: []`; distinct guide/creative settings and their
behavior remain deferred until supported. P04.6 adds reviewed EN/IT resources
for every approved catalog leaf, source-owned tutorial/quote text and explicit
English quote fallback in Italian. Source-only prose remains outside that coverage.

## 13. Export and migration

Export an admitted campaign-generated NPC or ability only through a review that removes private runtime identifiers, selects rights/provenance, resolves dependencies, and creates stable content IDs. Do not copy the live save into an adventure pack and call it reusable content.

Pack migration is explicit, previewed, backed up, and reference-mapped. Removing an ability used by an active encounter blocks migration until a supported resolution is selected. The engine must never hot-reload changed combat formulas into a fight already in progress.

## 14. Portable stories and person-to-person sharing

An adventure bundle is the reusable story/world definition, distributed as the
existing contract-1 directory or `.zip`: manifest, explicit lore/entity/rule
files, authored anchors/generation boundaries, locale overlays/glossary, and
licensed presentation assets. An editor export uses this same contract; manual
authors can exchange it before the later editor exists. A finished plot is
optional and an exported story can remain authored, emergent or hybrid.

Export a new immutable pack revision with stable pack/content IDs, exact
supported schemas/engine/ruleset ranges, dependency versions/hashes, file/asset
hash manifest, provenance and per-component license/credit records. Bundle
permitted local dependencies explicitly or list required separately installable
revisions; no silent remote include or account-bound runtime dependency.
Nonredistributable assets are omitted with a diagnostic and usable fallback;
never claim that free playback or local generation automatically grants sharing
rights. Optional model weights/reference voice recordings are not part of an
ordinary story bundle.

Default export includes authored public and clearly marked GM/secret content
needed to run the story, not private campaign transcripts, player choices,
generated private memories/plans, job leases, provider IDs, credentials, machine
paths or active saves. Warn authors that a complete editable adventure can
contain plot secrets; a player-facing journal export is a separate audience.
Promoting a campaign-generated character/artifact into reusable authored content
requires an explicit reviewed content-export operation, rights and dependency
checks; do not export the whole playthrough implicitly.

Recipients preview origin, title, version, supported locales, dependencies,
rights, required modules and warnings before explicit import. Apply the existing
hostile-archive/parser limits regardless of trusted sender. An existing ID/version
with different hashes is a conflict; never overwrite silently. Duplicate imports
are idempotent, and a deliberate fork receives explicit new identity/provenance.
Install content immutably, then create a new local adventure/campaign with local
IDs. No recipient account, active world or save is replaced by import.

P16.2/P16.5 verify export followed by import on a separate clean installation,
both locales/genres, missing dependencies/assets, incompatible revisions,
malicious files and unchanged sender/recipient saves. The later editor E05/E06
adds graphical preview and round-trip tests over exactly this interchange format.

## 15. Localized content and narrative plans

P04.6 implements the optional `localizations` map and source-owned `textResources`
in [localization](29-localization.md#4-pack-localization-contract). Catalog lookup
never patches definitions: stable IDs, formulas, references and source visibility
remain authoritative. Nested text keys use stable child IDs rather than array
offsets. Closed fields, placeholder parity and glossary/quote policy are validated
with source locations. The sealed import report includes EN/IT coverage, fallback
keys and source-only Markdown paths; every listed overlay/policy byte participates
in the existing revision hash. Both neutral fixtures include reviewed EN/IT
catalog resources. Partial external translations are valid, with source-language
fallback; import/play labels and runtime audience-filtered rendering remain future
consumer responsibilities. The internal catalog and its reports are not public
projections or semantic translation-quality guarantees.

Authors may define flexible quest premises, allowed planning categories, protected
facts/required entities, forbidden developments and supported conditional beats.
The [plan contract](28-asynchronous-narrative-planning.md) references compiled IDs
and predicates, not arbitrary executable scripts. Generated plans cannot override
author-fixed inputs, invent guaranteed future events or authorize player choices.
