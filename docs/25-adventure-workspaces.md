# Independent Adventure Workspaces

Story exchange uses the [portable adventure bundle](09-adventure-packs.md#14-portable-stories-and-person-to-person-sharing)
contract: export reusable authored content and permitted assets, then preview
and import on another person's installation to create fresh local adventure and
campaign identities. Default bundles exclude private playthrough data, live jobs,
provider threads and credentials. The [later editor](26-adventure-editor-roadmap.md)
uses the same format; sharing does not merge worlds by title or transfer account
access. Voice, locale and planning artifacts retain the same scoped identity
rules as images and memory.

## 1. Meaning and status

The owner requires personalized adventures in parallel, each with its own specifications, content and media folders. An **adventure workspace** is not a quick-save slot. It owns an authored world/configuration and can contain multiple **campaigns** (playthroughs); each campaign can have checkpoints/backups. A reusable imported **pack** is a versioned content source, not the active adventure or a saved campaign. P03.1 delivers the storage foundation only: `adventure_workspace`, adventure-owned `content_revision`, `campaign` rows pinned to a revision of the same adventure, and `campaign_checkpoint` rows. Managed folders, the library UI, backups and workspace commands are not delivered.

The library's primary cards represent adventures. “New adventure” creates a workspace; “New playthrough” creates a campaign inside it; “Continue” resumes its selected campaign. Installed reusable packs are managed separately. Switching adventures never clones, resets or merges state.

## 2. Separate managed folders

Proposed layout under the owner's managed data root, outside the source checkout/container writable layer:

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

`AdventureWorkspace` owns ID, title/genre/locale, authored specifications, admitted content revisions and presentation/generation policy. Each campaign belongs to exactly one adventure. Every mutable query, job, thread, summary, image association and export verifies adventure/campaign ownership. Existing campaign routes can derive the adventure server-side but reject mismatched selected-adventure context.

The P03.2 database foundations freeze the workspace owner, campaign adventure and
content revision, and actor/resource/operation campaign associations after insert.
Resources additionally reference an actor in the same campaign. A reassignment
cannot silently move existing state between adventures, even under a direct SQL
UPDATE. Scoped repository queries have negative isolation tests; application
authorization, routes, media and job isolation remain later work. These storage
checks alone do not complete QA-33.

Proposed additions are `GET /api/v1/adventures`, `POST /api/v1/adventures` and `GET /api/v1/adventures/{adventureId}`. The existing campaign-creation envelope requires `adventureId` and an admitted pack revision belonging to it. Continue/list operations expose only its campaigns. These are design contracts, not running endpoints.

Scope client projections, drafts, quick replies, reading cursor and media to adventure/campaign. Switching detaches old subscriptions and rejects stale callbacks in the new view, without submitting an old draft. The header identifies the selected adventure/playthrough. Memory and narrative threads never cross adventures because character names match. Generated images stay in their originating folders; no global search-by-name cache is allowed. Public engine UI assets may be shared read-only; reuse of authored/private material requires explicit rights-aware export/import and identity remapping.

Each campaign retains one state-changing operation at a time. Global owner/provider budgets also bound concurrent narrative/image work across adventures. Reloading or switching neither regenerates accepted art nor resets generation limits. Late results can be stored only in their valid originating scope and must not update the newly selected world's view.

## 4. Restore and verification

Full-owner backups preserve adventure configuration, admitted revisions, campaign data and artwork. Player-facing exports omit private specifications/notes. Restore previews show destination adventure/campaign identities and asset remapping; default to new identities instead of overwriting or merging by equal titles. Deliberate recovery validates existing associations/hashes. Deleting an adventure is a separate explicit owner action, never startup/retry behavior. Stopping one campaign must not kill another's unrelated work.

QA-33 creates fantasy and cyberpunk adventures with equal NPC/item names but distinct secrets, inventory and art. Switch with pending replies, open tabs and late media events; verify no cross-world text, media, draft, memory, file or canonical effect. Purchase/equip in one leaves the other unchanged. Reboot/restore retains the correct directories and associations. These checks remain unimplemented.
