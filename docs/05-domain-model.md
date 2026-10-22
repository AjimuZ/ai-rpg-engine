# Domain Model and Persistence

## 1. Identity and versioning

Use opaque identifiers for runtime entities. Every mutable entity belongs to a campaign. Content identifiers are namespaced, such as `sample:travel-ration`; a runtime item instance additionally has its own identity. Human-readable labels can change without changing identity.

Separate these counters:

| Counter | Meaning |
| --- | --- |
| `stateRevision` | Monotonic canonical revision; increments once per successful state-changing operation |
| `gameTurn` | Resolved in-world player actions; administrative or presentation changes do not increment it |
| `worldTime` | Campaign-relative elapsed time; independent of computer time |
| `eventSequence` | Monotonic append-only domain event ordering per campaign |
| `aiSessionNumber` | Number of the renewable AI context generation |
| `encounterRound` | Current tactical round inside one encounter |
| `operationVersion` | Journal updates for progress and delivery; not a world revision |

One game action can emit many events but advances the game turn once. An NPC combat action has its own state-changing operation and advances the combat scheduler; it does not add a human player game turn. A read-only consultation may create a transcript record without changing canonical revision, game turn, time, RNG, or event sequence.

## 2. Core aggregates

| Aggregate | Principal fields and responsibilities |
| --- | --- |
| `OwnerProfile` | Local identity, display preferences, administrative access; no Codex token material |
| `InstalledPack` | Pack ID, semantic version, content hash, schema version, ruleset constraints, provenance |
| `RulesetRevision` | Ruleset ID/version/hash, attributes, formulas, action and effect vocabulary, policy selections |
| `Campaign` | Owner, pack snapshot hash, ruleset hash, seed identity, revision, turn, time, mode, pending decision |
| `Actor` | Character/NPC/creature instance; origin definition, appearance data, location, public labels |
| `CharacterState` | Attribute values, trained skills, XP/level or advancement track, resources, conditions |
| `AbilityDefinition` | Costs, targeting, tags, predicates, effect graph, disclosure and generation constraints |
| `LearnedAbility` | Actor, definition version, unlocked rank, acquisition source, configured choices |
| `ItemDefinition` | Category, stack policy, slot tags, prices or valuation policy, effects, provenance |
| `ItemInstance` | Owner/container, quantity, durability/charges, definition, bound/quest status |
| `EquipmentAssignment` | Actor, slot ID, item instance; hand/slot compatibility and availability |
| `QuestInstance` | Definition/version, discovered state, objective progress, outcome, reward settlement identity |
| `LocationState` | Definition, spatial adjacency, discovered properties, dynamic conditions, last observed time |
| `FactionState` | Goals, relationships, reputation, clocks, known/public state |
| `Relationship` | Directed actor pair, dimensions, commitments, private/public disclosures, sources |
| `KnowledgeFact` | Typed claim, truth category, audience, source, valid time, supersession, confidence where meaningful |
| `Encounter` | Frozen roster/profile, spatial state, initiative, round, current actor, lifecycle, objectives |
| `EffectInstance` | Source operation, rule definition, target, magnitude, timing, duration, stacking key |
| `RewardSettlement` | Unique source key, recipients, XP/items/currency, applied revision |
| `CampaignOperation` | Idempotency key, payload hash, base revision, lease/fence, statuses, result receipt |
| `DomainEvent` | Campaign sequence, event type/version, operation, causation, correlation, rule/source, payload |
| `NarrativeRecord` | Public text, audience, source event range, generation profile, delivery revision |
| `Checkpoint` | Snapshot revision/hash, pending decision, memory references, combat state, RNG cursor |
| `MemorySummary` | Source event interval, source revision, audience, text, covered entities, freshness status |
| `AiSession` | Logical role, private thread reference, generation number, counters, source checkpoint hash |
| `AssetRecord` | Category, content hash, path/object reference, rights metadata, entity links, audience |

Definition entities are immutable per revision. Campaign instances may extend them using validated namespaced data. New generated definitions are campaign-scoped unless explicitly exported by the owner into a pack.

## 3. Database organization

Proposed table families: `content_*`, `campaign_*`, `actor_*`, `inventory_*`, `quest_*`, `world_*`, `knowledge_*`, `combat_*`, `memory_*`, `ai_*`, `operation_*`, and `presentation_*`. Use explicit foreign keys and campaign-scoped unique constraints. No table names or SQL migrations are implemented in this phase.

Core state is relational. Immutable compiled content and event payloads may use JSONB with an explicit schema version. Store large media on disk under managed identifiers, keeping metadata and hashes in PostgreSQL. Do not store arbitrary user-supplied paths as trusted URLs.

Minimum indexes include campaign/revision, campaign/event sequence, operation idempotency key, current actor and encounter, knowledge audience/entity, quest source settlement, source-summary event range, and owned item/container. Full-text indexes support lore and event retrieval. Indexes must be justified by measured query plans later.

## 4. Mandatory invariants

### Campaign and operations

- A transaction cannot mix campaign IDs or content hashes from another campaign.
- `expectedRevision` must equal the current revision at commit.
- A state-changing operation has exactly one canonical receipt and at most one successful commit.
- An idempotency key is unique within its campaign; the same key with a different normalized payload is a conflict.
- Committed event sequence numbers are unique and increasing. Gaps in internal reserved numbers are acceptable; reuse and reordering are not.
- No schema-valid proposal becomes canon without semantic validation and a source.

### Actors and resources

- Resource values stay within declared bounds; debt is a separate entity if enabled.
- Unknown values are represented explicitly, not silently converted to zero.
- Unlocked abilities reference a valid definition and acquisition event.
- Derived statistics use a pinned formula and source values; cached derived values carry a revision.
- Defeat, death, unconsciousness, and recoverable incapacitation are distinct statuses.

### Inventory

- Quantities are integers and nonnegative; zero stacks may be archived but history remains.
- A unique item has exactly one owner/container at a time.
- Equipment references an item actually possessed and available to the actor.
- Hand occupancy, slots, capacity, ammunition, charges, and binding rules are checked together.
- Consumption and effect resolution are atomic. An attack cannot consume ammunition twice on a network retry.
- Item descriptions do not create mechanical bonuses. Bonuses require a validated effect definition.

### Knowledge and progression

- A rumor is not a confirmed world fact; a believed fact can differ from truth.
- Current real location and an observer's last known location are separate records.
- A reward source has one settlement per defined recipient policy.
- Level/skill/stat changes require a supported advancement operation; narration cannot award them directly.
- A correction appends evidence of what changed; it does not edit the historical event that originally occurred.

## 5. Typed operations instead of arbitrary patches

Permitted commands include move actor, use item, equip item, perform ability, inspect target, speak, transfer item, create bounded content, reveal fact, advance objective, change relationship for a documented cause, and propose encounter. Their exact effects are determined by rules.

Do not accept generic operations such as “set any field,” arbitrary JSON Patch paths, raw SQL, or AI-specified HP deltas. A model may identify an intended ability or propose a definition; the engine derives HP changes, costs, target legality, and reward consequences.

New facts also require a boundary: world compatibility, allowed generation category, scope, conflicts, identity resolution, and provenance. A decorative description may enrich a scene, but a locked door, weapon, exit, NPC identity, or promise with future consequences must be registered before becoming an actionable canonical fact.

## 6. Event envelope

Every event includes campaign ID, event ID and sequence, operation ID, event type/version, actor, targets, real creation timestamp, in-world time, ruleset hash, causation references, visibility classification, and a bounded typed payload. Mechanical events include calculation inputs and random-draw references.

Examples: `ItemConsumed`, `ResourceChanged`, `ActorMoved`, `AbilityLearned`, `FactDiscovered`, `QuestObjectiveSatisfied`, `EncounterStarted`, `CombatActionResolved`, `ActorDefeated`, `RewardSettled`, and `CanonCorrectionApplied`.

The event log is an audit and recovery foundation. Current state is maintained transactionally alongside it. v1 does not promise that every future database version can be rebuilt solely by replaying events. Full replay requires versioned reducers and explicit support tests; distinguish that from replaying an encounter's recorded calculations.

## 7. Save and checkpoint model

Every canonical operation is saved by its transaction. A checkpoint is an additional consistent snapshot for fast restart, export, and AI rehydration. A checkpoint failure after a canonical commit cannot undo the committed action; the snapshot job retries from its revision.

A complete checkpoint references exact pack/ruleset/prompt-policy versions, current actors and resources, inventory, quest state, world time and clocks, knowledge scopes, unresolved commitments, current encounter, pending player decision, event high-water mark, RNG algorithm/version/cursor, and summary freshness. It does not need to contain the provider's conversation history.

## 8. Corrections and migrations

Content upgrades require a dry-run migration, backup, compatibility checks, reference mapping, and owner confirmation of concrete changes. Running campaigns do not silently follow edited pack files. New content versions apply to newly created campaigns unless a migration is performed.

Administrative corrections have a reason, before/after values, dependencies affected, actor identity, and a new revision. If a correction invalidates a summary or active encounter, mark it stale and revalidate. Rewinding a campaign is a restore or branch operation, never deletion of audit history presented as if it never occurred.
