# Independent Adventure Workspaces

Story exchange uses the [portable adventure bundle](09-adventure-packs.md#14-portable-stories-and-person-to-person-sharing)
contract: export reusable authored content and permitted assets, then preview
and import on another person's installation to create fresh local adventure and
campaign identities. Default bundles exclude private playthrough data, live jobs,
provider threads and credentials. The [later editor](26-adventure-editor-roadmap.md)
uses the same format; sharing does not merge worlds by title or transfer account
access. Voice, locale and planning artifacts retain the same scoped identity
rules as images and memory.


P06.1 creation authorizes the owner/adventure before looking up a durable
retry and requires the sealed revision to belong to that adventure. The adventure
row lock serializes registration and creation; same request UUIDs are scoped by
adventure. Separate campaign and actor identities isolate same-ID genre definitions
and initial grants. Protected HTTP creation derives the owner from local server
configuration and verifies the selected adventure before any retry lookup.
P06.2 public reads authorize owner/adventure/campaign together and resolve only
the creation-bound player's items, abilities and audience knowledge. Every
section reply includes the full scope and state revision. Owner listing is
bounded and can filter by adventure. Continue reloads the committed save without
changing an active adventure or world counters. Public workspace
creation/frontend switching remains unfinished. P06.3 starting text uses
that same authorized snapshot and the campaign's pinned root revision/locale;
importing another revision cannot replace an existing starting situation. Typed
free-input metadata never authorizes another adventure's action or reveals lore.

## 1. Meaning and status

P06.5's first checkpoint adds persisted library reads: locally admitted
`GET /api/v1/adventures` returns a bounded owner page and
`GET /api/v1/adventures/{adventureId}` returns public metadata for exactly one
owned workspace. Both include empty workspaces and preserve separate identities
for equal titles. Metadata excludes owner IDs, specifications and child records.
Campaigns remain separately queried with an explicit adventure ID; reading or
switching request scope never writes a process-wide active adventure. Workspace
creation, browser binding and complete QA-33 remain unfinished. The second
checkpoint adds optional managed projections of sealed specifications and authored
media, as described below; generated campaign media is still pending.

The owner requires personalized adventures in parallel, each with its own specifications, content and media folders. An **adventure workspace** is not a quick-save slot. It owns an authored world/configuration and can contain multiple **campaigns** (playthroughs); each campaign can have checkpoints/backups. A reusable imported **pack** is a versioned content source, not the active adventure or a saved campaign. P03.1 delivers the storage foundation only: `adventure_workspace`, adventure-owned `content_revision`, `campaign` rows pinned to a revision of the same adventure, and `campaign_checkpoint` rows. Subsequent checkpoints add library reads, database recovery and the optional folder projection below; workspace commands and real browser binding remain pending.

The library's primary cards represent adventures. “New adventure” creates a workspace; “New playthrough” creates a campaign inside it; “Continue” resumes its selected campaign. Installed reusable packs are managed separately. Switching adventures never clones, resets or merges state.

## 2. Separate managed folders

P05.3 scopes public portrait lookup to each local preview's speaker registry.
Reading/layout changes do not merge positions; switching scenes replaces speaker
artwork and clears asset-failure state. These tab-lifetime reading checks do not prove
persisted workspace isolation or runtime media ownership.

P05.4 persists bounded unsent drafts and selections per stable adventure/campaign
ID pair in versioned same-origin device storage. Preview campaigns use the
reserved `local-preview` identity. Switching scopes restores independent text;
public projection callbacks require the active scope and activation epoch,
including after returning to a previous scope. Accepted revision history stays
in memory and stale/duplicate delivery cannot replace current suggestions.
Storage failure retains edits in memory with visible status. These checks cover
preview draft/reload isolation, not real campaign restart/restore, managed media
folders, cross-tab merging or complete QA-33. Operation reconciliation remains P07.

P05.1 adds a library shell with two local genre preview cards and a separate
installed-packs section. Scoped URLs reject unknown preview identities; reading
positions are independent in memory. These samples are not persisted workspaces
or campaigns. Workspace creation, frontend binding to the P06.2 listing/continue
APIs and full restart/restore isolation remain pending; frontend previews do not
establish QA-33.

P05.2 derives terminology, attribute/resource names and optional panel visibility
from each local preview's typed public metadata. Reused routes replace their
labels/configuration on adventure changes; direct disabled panel routes show an
unavailable state. This is presentation scoping only, not persisted campaign state
or authored-pack module execution; binding real campaign projections and exposing
public pack metadata remain later integration work.

P04.5 imports both [neutral genre fixtures](../backend/src/test/resources/packs/README.md)
into one workspace and independently into fresh workspaces, preserving distinct
revision identities and exact inventories despite shared local definition IDs.
P04.6 also verifies sealed localization reports, original overlay/resource bytes
and changed-translation hash conflicts without replacing existing revisions.
These are registry checks only; campaign/library switching and full QA-33
restart/restore isolation remain unimplemented.

P04.4 stores imported original files and sealed reports in PostgreSQL alongside
their adventure-owned revision. Dependencies cannot resolve through another
adventure. Re-import is idempotent for identical bytes and never replaces an
existing release; campaigns retain their pinned revision. This bounded initial
store remains the authority included in database backups. P06.5 deliberately adds
only a rebuildable private projection, not a migration of that authority. Moving
canonical bytes out of PostgreSQL still requires an explicit migration preserving
hashes, ownership and atomic visibility.

Set `engine.workspaces.root` (environment `ENGINE_WORKSPACES_ROOT`) to an absolute,
normalized private directory on persistent storage outside the checkout. Omitting
the property disables projection and preserves database-only operation. Startup
reconciles sealed revisions in bounded pages, one revision at a time; unsealed
legacy metadata is ignored. Successful registration and identical retries also
reconcile, after the database transaction commits. An I/O failure therefore leaves
the admitted revision intact; repair the storage problem and retry the same bytes
or restart. Neither workflow reads edits back from the projection.

Implemented layout:

```text
<managed-root>/adventures/<adventure-id>/content-revisions/<revision-id>/
  specification/<original-pack-relative-path>
  assets/authored/<original-pack-relative-path>
  integrity.json
```

The sealed file category selects the destination; original relative paths remain
intact (an original `assets/map.png` becomes `assets/authored/assets/map.png`). The
version-1 integrity manifest records adventure/revision IDs, aggregate content hash
and each original path, destination, size and SHA-256. One atomic directory rename
publishes the complete revision; unsupported atomic moves fail rather than degrade.
Retries compare the exact tree and bytes without rewriting it. Missing *whole*
revision directories rebuild; missing/extra/changed files inside a published
revision fail closed, including an altered manifest or empty extra directory.

All ancestors and entries reject static symbolic links, junctions/reparse nodes
and special files. New POSIX directories/files use `0700`/`0600`; existing roots
and Windows ACLs must be private and owner-controlled. Use one engine writer per
root and keep it quiescent from external editors: portable JDK path operations
cannot exclude an adversarial local process replacing an inspected ancestor.
Failed unpublished `.building-*` directories remain private for offline operator
inspection and are neither accepted nor automatically deleted. No power-loss
fsync guarantee is claimed; PostgreSQL originals permit reconstruction.

These files include private lore and are never mounted into the narration runtime,
served as static web resources, or exposed by library GETs. They are not player
exports. A database-only installation recovery with preserved IDs can rebuild an
empty projection root; generated artwork, campaign directories, identity-remapped
restores and complete QA-33 remain later work.

Remaining campaign/specification-editing layout under the owner's managed data
root, outside the source checkout/container writable layer (not yet implemented):

```text
adventures/
  <adventure-id>/
    specification/             # world, constraints, metadata
    content-revisions/         # immutable admitted source/pack revisions
    assets/authored/
    campaigns/
      <campaign-id>/
        assets/generated/
          scenes/
          portraits/
          items/
        exports/
        backups/
```

IDs are stable opaque identifiers, not user-entered paths/display names. Equal world or sword names cannot collide; renaming a title does not move folders. Resolve registered IDs to contained managed paths and reject traversal/reparse escapes. Never accept an arbitrary serving/storage path from narration or the browser.

PostgreSQL remains authoritative for state, events, ownership and saved asset associations; these folders do not create a second mutable JSON-save authority. Use persistent content/asset storage and back up canonical records plus referenced originals with integrity manifests.

## 3. Scope every operation

P03.4 reservations lock the campaign in its requested adventure and then the scoped
journal operation. Draw rows use a composite campaign/operation foreign key; seed
material belongs to exactly one campaign and never appears in receipts/outbox.
Cross-adventure/campaign attempts fail before allocating or reading reservations.

`AdventureWorkspace` owns ID, title/genre/locale, authored specifications, admitted content revisions and presentation/generation policy. Each campaign belongs to exactly one adventure. Every mutable query, job, thread, summary, image association and export verifies adventure/campaign ownership. Existing campaign routes can derive the adventure server-side but reject mismatched selected-adventure context.

The P03.2 database foundations freeze the workspace owner, campaign adventure and
content revision, and actor/resource/operation campaign associations after insert.
Resources additionally reference an actor in the same campaign. A reassignment
cannot silently move existing state between adventures, even under a direct SQL
UPDATE. Scoped repository queries have negative isolation tests; application
authorization, routes and media isolation remain later work. These storage
checks alone do not complete QA-33.

P03.6 adds scoped preparation jobs and snapshots: composite keys prevent a job from
binding another campaign's snapshot/attempt/artifact, and snapshot content must
match its campaign's pinned revision. Enqueue/capture check adventure ownership;
worker mutations require the scoped lease and recovery generation. Internal tests
cover wrong scopes and restored stale callbacks, without claiming UI/media isolation.

P03.3's internal commit service additionally locks the campaign in the requested
adventure and reads the operation/resources in that campaign. Event actor/causation
and outbox committed-operation references retain composite database constraints.
Wrong-adventure and cross-campaign commit tests verify rollback without effects;
outbox envelopes expose no narrative or private event body. These are backend scope
checks, not proof of UI switching, owner authorization or future delivery isolation.

The adventure listing/detail GETs now run under local admission; workspace
creation via `POST /api/v1/adventures` remains proposed. The existing
campaign-creation envelope requires `adventureId` and an admitted pack revision
belonging to it. Campaign listing filters by selected adventure, and continue
requires the exact adventure/campaign pair. No route implicitly changes selection.

Scope client projections, drafts, quick replies, reading cursor and media to adventure/campaign. Switching detaches old subscriptions and rejects stale callbacks in the new view, without submitting an old draft. The header identifies the selected adventure/playthrough. Memory and narrative threads never cross adventures because character names match. Generated images stay in their originating folders; no global search-by-name cache is allowed. Public engine UI assets may be shared read-only; reuse of authored/private material requires explicit rights-aware export/import and identity remapping.

Each campaign retains one state-changing operation at a time. Global owner/provider budgets also bound concurrent narrative/image work across adventures. Reloading or switching neither regenerates accepted art nor resets generation limits. Late results can be stored only in their valid originating scope and must not update the newly selected world's view.

## 4. Restore and verification

The P03.6 database-only recovery tool preserves identities in a new database with
all writers stopped, increments campaign recovery generations and invalidates usable
plans before startup retires old jobs. This is an installation-recovery foundation;
the full workspace restore/remapping contract below remains P16.

Full-owner backups preserve adventure configuration, admitted revisions, campaign data and artwork. Player-facing exports omit private specifications/notes. Restore previews show destination adventure/campaign identities and asset remapping; default to new identities instead of overwriting or merging by equal titles. Deliberate recovery validates existing associations/hashes. Deleting an adventure is a separate explicit owner action, never startup/retry behavior. Stopping one campaign must not kill another's unrelated work.

QA-33 creates fantasy and cyberpunk adventures with equal NPC/item names but distinct secrets, inventory and art. Switch with pending replies, open tabs and late media events; verify no cross-world text, media, draft, memory, file or canonical effect. Purchase/equip in one leaves the other unchanged. Reboot/restore retains the correct directories and associations. The complete scenario remains unverified; the optional P06.5 projection tests cover authored original-file isolation and fresh-JVM reconstruction only, not generated art or combined gameplay/browser/memory acceptance.
