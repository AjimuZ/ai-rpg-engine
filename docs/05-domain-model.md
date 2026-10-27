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

P03.4 adds V4 `campaign_random_seed` and `operation_random_draw`. Seeds are created
lazily from JDK `SecureRandom`, retained as private database bytes and resolved by
campaign; `Campaign.seedIdentity` remains an opaque pinned label, never key material.
No seeds or historical draws are backfilled on upgrade. Both tables reject UPDATE
and DELETE. Draws have a unique `(campaign_id, operation_id, ordinal)`, a scoped
journal foreign key, bounded ordinal/value and a seed algorithm/version foreign key.
Reservation changes neither world counters nor journal versions. No mutable RNG
cursor is needed: the durable operation/ordinal set is the reservation ledger.

The protocol `HMAC-SHA-256`, version 1, maps a private 32-byte campaign seed
and operation UUID/ordinal/bound to an unbiased integer in `1..sides`. The message
is ASCII `engine-draw-v1` followed by NUL, UUID high/low 64-bit words, ordinal,
sides and rejection counter (three 32-bit integers), all big endian. Counter starts
at zero; take the first unsigned 32 bits of each HMAC and reject samples at or above
`2^32 - (2^32 mod sides)` before modulo mapping. Ordinals are 0..127; sides are
1..2,147,483,647. Identity/version changes require a new protocol, never silent drift.

Proposed table families: `content_*`, `campaign_*`, `actor_*`, `inventory_*`, `quest_*`, `world_*`, `knowledge_*`, `combat_*`, `memory_*`, `ai_*`, `operation_*`, and `presentation_*`. Use explicit foreign keys and campaign-scoped unique constraints.

P03.1 implements the first migration, `V1__canonical_foundations.sql`, with ten foundation tables: `identity_owner_profile`, `adventure_workspace`, `content_revision`, `campaign`, `presentation_campaign_settings`, `actor`, `operation_journal`, `campaign_event`, `reward_settlement` and `campaign_checkpoint`. Campaign-owned relationships use composite `(campaign_id, id)` foreign keys, and a campaign pins a content revision of its own adventure, so cross-campaign and cross-adventure references fail in PostgreSQL. The canonical counters of section 1 live only on the campaign row; locale and presentation generations live in `presentation_campaign_settings`. Events, content revisions, checkpoints and reward settlements are immutable rows.

P03.2 adds `V2__campaign_bounds_and_versions.sql` without changing V1. The seven mutable tables (owner, workspace, campaign, presentation settings, actor, operation and new `actor_resource`) have a nonnegative `row_version`, initialized to zero and mapped with JPA `@Version`. Each UPDATE must advance it exactly once. This storage token is separate from every counter in section 1: Hibernate compares the expected version; raw SQL must likewise include `WHERE row_version = :expected` and reject a zero affected-row count. Incrementing alone is not a stale-read check. Owner profiles and workspaces are administrative scope roots; campaign-owned mutable entities derive owner/adventure scope through their campaign.

V2 freezes row identities, owner/adventure/campaign associations and creation provenance after insert; campaign content revision and seed are pinned. Resource identity, actor and content key are fixed. `actor_resource` has a composite actor/campaign foreign key, uniqueness on `(campaign_id, actor_id, resource_key)` and inclusive `minimum_value <= current_value <= maximum_value` bounds. Integer values and declared bounds use BIGINT; adjustments reject overflow or an out-of-range result without clamping, and unknown values are never inserted as zero. Resource revision cannot rewind. Rules/content determine the declared bounds and available resource keys; debt and inventory ownership are separate later entities.

Canonical counters and presentation generations cannot decrease, and a campaign state revision advances by at most one per row update. Committed journal statuses require both revision and receipt; other statuses require neither. Commit outcomes are write-once, terminal statuses cannot reopen, and `(campaign_id, committed_revision)` is unique while uncommitted NULL revisions remain distinct. Checkpoints and settlements also expose composite campaign/identity unique keys for later relationships. P03.3 adds the internal atomic commit/revision/outbox boundary described below; P03.4 adds draw records above. P03.5 adds the V5 campaign mutation lease/fence and startup journal recovery ([turn protocol §8](07-turn-protocol.md#8-interrupt-and-recovery-matrix)). P07.1 now supplies owner-scoped admission/status APIs. Java owns the full lifecycle transition table. Scoped repository methods are persistence primitives; future application boundaries must authorize owner/adventure/campaign before calling them, and must not expose inherited unscoped repository methods.

JSONB object bounds use UTF-8 bytes of PostgreSQL's normalized text rendering: pending decision and reward payload 16,384 bytes, receipt and event payload 65,536 bytes, checkpoint snapshot 8,388,608 bytes. Java rejects oversized input early; JSONB whitespace/escape normalization can change the byte count, so the database remains authoritative. Larger exports/media belong outside these payloads. The constraints, boundary values, races and populated-upgrade checks pass in the full backend verification on PostgreSQL 18.6 (24 October 2026); see the [evidence scope](15-quality-and-acceptance.md#1-evidence-policy). The other table families above remain later work, except the P03.6 preparation
foundation verified on 24 October 2026. Its V6 rows store immutable snapshots,
plan versions/dependencies/admission receipts, fenced jobs/attempts and query epochs.
Snapshot inputs carry assembler revision, recovery and presentation tokens; admission
must retain their complete manifest. Non-query semantic dependencies fail closed
until typed evaluators exist. These internal services expose no gameplay endpoint.

V6 adds `ai_job`, `ai_job_attempt`, `planning_snapshot`, `query_epoch`,
`quest_plan_artifact`, `plan_dependency`, `plan_admission` and `plan_disposition`.
Composite keys bind jobs to their adventure/campaign, snapshots to the campaign's
pinned content, and plans/dependencies/receipts to scoped attempts. Snapshot digest
inputs include the full selected state, source references, typed dependency manifest,
audience, source counters and policy/content hashes. Selected state and plan bodies
are bounded to 65,536 normalized JSONB bytes; manifests allow at most 512 typed
dependencies and 65,536 bytes. Artifact versions, snapshots, dependencies and
admissions cannot be rewritten; dispositions append a new sequence. Job attempts
retain write-once runtime/result/outcome history, a finite retry budget and a fence
that increases on claim/release. `campaign.recovery_generation` is independent of
canonical revision and advances on database restore. Usage counters are durable
accounting primitives; global budget admission remains P08.6.

P03.3 adds `V3__canonical_outbox.sql` and the internal `CanonicalCommitService`. A bounded immutable resolved plan commits world counters/mode/decision, typed resource adjustments, ordered events, settlements, a backend-built receipt and outbox intents together. The service serializes by campaign, rechecks snapshot tokens, rejects ambient transactions and returns only after COMMIT. Outbox kinds are `PUBLIC_STATE` and optional `NARRATION_REQUIRED`; envelopes contain only identity, campaign, operation, committed revision, kind and creation time. `(campaign_id, operation_id, kind)` is unique; a deferred composite foreign key requires the referenced journal outcome to be committed at the same revision. Rows cannot be updated/deleted. Historic operations receive no fabricated outbox records on upgrade. Delivery state/retention and workers are later work. See [commit contract and bounds](07-turn-protocol.md#5-canonical-commit).

Core state is relational. Immutable compiled content and event payloads may use JSONB with an explicit schema version. Store large media on disk under managed identifiers, keeping metadata and hashes in PostgreSQL. Do not store arbitrary user-supplied paths as trusted URLs.

Minimum indexes include campaign/revision, campaign/event sequence, operation idempotency key, current actor and encounter, knowledge audience/entity, quest source settlement, source-summary event range, and owned item/container. Full-text indexes support lore and event retrieval. Indexes must be justified by measured query plans later.

### Durable command admission (P07.1)

No new migration is needed: `operation_journal` already stores the campaign/request
unique key, payload hash, base revision and lifecycle/outcome, while V5 stores the
campaign permit and monotonically increasing fence. Admission uses these existing
rows in one transaction, locking owner before campaign and inserting the journal
before its lease foreign key. A matching identity/hash replays without a new permit;
a different hash conflicts, and a fresh command must target the exact revision.

The two-live-permit owner cap spans adventures and applies equally to new admission,
internal acquisition/reacquisition and renewal serialization. Committed release and
startup retirement preserve the fence high-water mark. Scope never derives from
client owner fields. Read-only status exposes a detached allowlist, excluding raw
receipt/payload/hash and worker capabilities. The service retains the hash, not
unfinished intent: restart does not automatically rerun a command. See the
[admission contract](07-turn-protocol.md#p071-durable-admission) for normalization,
bounds, expiry classification and the later semantic/worker boundary.

### Campaign creation (P06.1)

V9 introduces `character_state`, `item_instance`, `learned_ability` and immutable
`campaign_creation`. The internal creation service authorizes owner/adventure,
loads sealed original bytes, checks the pinned ruleset and validates the manifest's
profile before inserting the campaign, player actor, allocations, full resources,
starting stacks/abilities and presentation locale in one transaction. Request IDs
are scoped to the adventure; exact retries return stored identities and changed
payloads fail. Creation starts at revision/turn/time/event zero and makes no draw,
job or narration call. Legacy campaigns receive no invented character rows.

The full backend packaged verification passes 290 tests on PostgreSQL 18.6,
including creation/rollback/retry, populated upgrade, protected HTTP and separate-JVM
restart coverage. HTTP ownership is server-configured, never selected by a client;
see [local admission](14-security-and-operations.md#4-application-http-boundary).
P06.3 stores a versioned `STARTING_SITUATION` pending marker atomically at creation.
It contains no prose, selected action or branching script. State/continue resolve
only the pinned public entrypoint in the saved narrative locale, retaining actual
language/fallback metadata and typed free-input decision metadata. Cleared or
unsupported decisions and legacy saves receive no invented starting presentation.
Mutable inventory/progression commands and equipment remain later work.
Creation verifies the complete pinned sealed dependency closure before writing;
external profiles, skills, locations and grants resolve in their declaring pack.
Dependency IDs persist as `pack#definition`; root IDs retain their local form.
An external profile uses its own pack's attribute/resource rules. Closure bounds
and conflicting-version rejection are defined in the [pack contract](09-adventure-packs.md).
Initial grants are explicit authored grants, not ability
use. Attribute cost is above declared minima, skill cost is total ranks; omitted
values use minima/zero and unspent points are allowed. Repeated starting item
grants aggregate into one bounded stack, so duplication cannot bypass `maxStack`.

### Player read projections (P06.2)

`CampaignProjectionService` authorizes owner/adventure/campaign before reading
children and resolves the player through immutable `campaign_creation`, never
by display name or a client-supplied actor. Reads use an independent read-only
repeatable-read transaction. The response allowlist excludes raw pending
decisions, internal events, seed references, planning artifacts and other actors.
V10 adds bounded `campaign_known_fact` and `campaign_known_quest` records with
composite actor/campaign foreign keys, audience indexes and version guards.
These hold only explicitly revealed player text and confidence/objective/status;
secret authored truth and quest graphs stay elsewhere. No legacy knowledge is
backfilled. Canonical reveal/progression writers remain later command work and
must persist their evidence and these audience records atomically.

### Local consultation presentation (P06.4)

`LocalConsultation` parses a bounded exact alias and transforms an authorized
committed player snapshot into a detached typed passage. Its source scope/revision
identifies the facts read, not a new world or delivery revision. No narrative
record, operation, event, draw or presentation timestamp is persisted by this
read adapter. Optional durable transcript storage must be a separate presentation
writer with its own delivery identity; recording a read must never use a
canonical commit to increment revision, turn, time or event sequence.

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
