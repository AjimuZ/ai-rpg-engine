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
| `ItemDefinition` | Category, stack policy, slot tags, prices or valuation policy, validated rarity tier, effects, visual identity, provenance |
| `ItemInstance` | Owner/container, quantity, durability/charges, definition, bound/quest status, optional admitted appearance variant |
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
| `NarrativeRecord` | Ordered attributed passages, message IDs, public speaker identity, audience, source events, submitted-player linkage, delivery revision |
| `Checkpoint` | Snapshot revision/hash, pending decision, memory references, combat state, RNG cursor |
| `MemorySummary` | Source event interval, source revision, audience, text, covered entities, freshness status |
| `AiSession` | Logical role, private thread reference, generation number, counters, source checkpoint hash |
| `AssetRecord` | Category, content hash, path/object reference, rights metadata, entity links, audience |
| `VisualIdentity` | Stable subject/definition identity, appearance/style revision, category, accepted asset reference and generation status |
| `AdventureWorkspace` | Stable adventure ID, title/genre/locale, specifications, admitted content revisions, managed media scope and owned campaigns |
| `AiJob` | Adventure/campaign/role/audience, source snapshot, dedup key, priority/budget reservation, attempt/deadline, lease/fence, terminal result and admission reference |
| `PlanningSnapshot` | Immutable selected facts, read/negative/query dependencies, author constraints and content/rules/policy hashes; not a full database copy |
| `QuestPlanArtifact` | Immutable versioned candidate/validated plan, branch predicates, dependencies, provenance and disposition; not a quest state transition |
| `PlanDependency` | Typed entity/field/source/query-epoch observation and expected version, visibility and branch relevance |
| `VoiceIdentity` | Scoped actor/narrator identity, approved voice recipe/model/reference rights and revision; no gameplay authority |

Definition entities are immutable per revision. Campaign instances may extend them using validated namespaced data. New generated definitions are campaign-scoped unless explicitly exported by the owner into a pack.

## 3. Database organization

Proposed table families: `content_*`, `campaign_*`, `actor_*`, `inventory_*`, `quest_*`, `world_*`, `knowledge_*`, `combat_*`, `memory_*`, `ai_*`, `operation_*`, and `presentation_*`. Use explicit foreign keys and campaign-scoped unique constraints.

P03.1 implements the first migration, `V1__canonical_foundations.sql`, with ten foundation tables: `identity_owner_profile`, `adventure_workspace`, `content_revision`, `campaign`, `presentation_campaign_settings`, `actor`, `operation_journal`, `campaign_event`, `reward_settlement` and `campaign_checkpoint`. Campaign-owned relationships use composite `(campaign_id, id)` foreign keys, and a campaign pins a content revision of its own adventure, so cross-campaign and cross-adventure references fail in PostgreSQL. The canonical counters of section 1 live only on the campaign row; locale and presentation generations live in `presentation_campaign_settings`. Events, content revisions, checkpoints and reward settlements are immutable rows.

P03.2 adds `V2__campaign_bounds_and_versions.sql` without changing V1. The seven mutable tables (owner, workspace, campaign, presentation settings, actor, operation and new `actor_resource`) have a nonnegative `row_version`, initialized to zero and mapped with JPA `@Version`. Each UPDATE must advance it exactly once. This storage token is separate from every counter in section 1: Hibernate compares the expected version; raw SQL must likewise include `WHERE row_version = :expected` and reject a zero affected-row count. Incrementing alone is not a stale-read check. Owner profiles and workspaces are administrative scope roots; campaign-owned mutable entities derive owner/adventure scope through their campaign.

V2 freezes row identities, owner/adventure/campaign associations and creation provenance after insert; campaign content revision and seed are pinned. Resource identity, actor and content key are fixed. `actor_resource` has a composite actor/campaign foreign key, uniqueness on `(campaign_id, actor_id, resource_key)` and inclusive `minimum_value <= current_value <= maximum_value` bounds. Integer values and declared bounds use BIGINT; adjustments reject overflow or an out-of-range result without clamping, and unknown values are never inserted as zero. Resource revision cannot rewind. Rules/content determine the declared bounds and available resource keys; debt and inventory ownership are separate later entities.

Canonical counters and presentation generations cannot decrease, and a campaign state revision advances by at most one per row update. Committed journal statuses require both revision and receipt; other statuses require neither. Commit outcomes are write-once, terminal statuses cannot reopen, and `(campaign_id, committed_revision)` is unique while uncommitted NULL revisions remain distinct. Checkpoints and settlements also expose composite campaign/identity unique keys for later relationships. These database checks do not implement the atomic command transaction, expected canonical revision, full lifecycle transition validation, leases or outbox; those remain P03.3–P03.6. Scoped repository methods are persistence primitives; future application boundaries must authorize owner/adventure/campaign before calling them, and must not expose inherited unscoped repository methods.

JSONB object bounds use UTF-8 bytes of PostgreSQL's normalized text rendering: pending decision and reward payload 16,384 bytes, receipt and event payload 65,536 bytes, checkpoint snapshot 8,388,608 bytes. Java rejects oversized input early; JSONB whitespace/escape normalization can change the byte count, so the database remains authoritative. Larger exports/media belong outside these payloads. The constraints, boundary values, races and populated-upgrade checks pass in the full backend verification on PostgreSQL 18.6 (24 October 2026); see the [evidence scope](15-quality-and-acceptance.md#1-evidence-policy). The other table families above remain later work.

Core state is relational. Immutable compiled content and event payloads may use JSONB with an explicit schema version. Store large media on disk under managed identifiers, keeping metadata and hashes in PostgreSQL. Do not store arbitrary user-supplied paths as trusted URLs.

Minimum indexes include campaign/revision, campaign/event sequence, operation idempotency key, current actor and encounter, knowledge audience/entity, quest source settlement, source-summary event range, and owned item/container. Full-text indexes support lore and event retrieval. Indexes must be justified by measured query plans later.

## 4. Mandatory invariants

### Campaign and operations

- A transaction cannot mix campaign IDs or content hashes from another campaign.
- Each campaign belongs to one adventure workspace; entity, media, memory and job ownership checks include it. Equal display names never justify cross-workspace reuse; see [workspace isolation](25-adventure-workspaces.md).
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
- Item rarity is a validated tier reference; art, color, purchase and inspection cannot change it. Shop and owned-instance views resolve the same visual identity unless an explicit visible variant is admitted.

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

## 9. Derived planning and presentation records

Plan artifacts and job status belong to durable `ai_*`/presentation records,
separate from canonical world state. Background completion can update their
administrative revision and emit a safe status outbox record without incrementing
`stateRevision`, `gameTurn`, time or RNG. Admission is not activation; any new
actionable entity/fact or pending world event is created only by its validated
ordinary canonical operation. Track branch dependency versions and predicate
query epochs to catch inserts/removals and negative-read phantoms, not just
existing-row updates. Enforce ownership in every unique/FK relationship.

Owner `uiLocale`, campaign `narrativeLocale`/`localeRevision`, immutable
`NarrativeRecord.passageLocale`, glossary revision and media configuration
generation are presentation data. They cannot change mechanical definitions or
overwrite old text. Cache keys and background consumers bind these generations
as well as adventure/campaign/audience. See [planning](28-asynchronous-narrative-planning.md),
[localization](29-localization.md) and [speech](30-local-speech-synthesis.md).

Exports preserve admitted plan/voice provenance according to audience and rights;
restore invalidates active job leases, process/thread IDs and delivery cursors,
remaps ownership and revalidates derived artifacts against the restored snapshot.
Do not import another person's live queue, tokens or account-bound state.
