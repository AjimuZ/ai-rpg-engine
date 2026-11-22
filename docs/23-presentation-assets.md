# Presentation Assets and Persistent Generated Artwork

Research extension: [competitive source comparisons](27-competitive-analysis-and-gaps.md),
[typed presentation events](13-api-and-ui.md#12-bilingual-presentation-event-contract)
and [optional local speech](30-local-speech-synthesis.md) refine this pipeline.
These are planned contracts, not installed generators, downloaded models or
measured appearance/voice consistency.

## 1. Status and intent

This specification is partially implemented: P05.1 delivers preview shells and two
original vector environments with live passage text and a missing-image fallback.
The [asset inventory](../frontend/public/scenes/README.md) records editable sources
and MIT licensing; no external art or service is used. P05.5 adds the original
[UI kit inventory](../frontend/public/ui/README.md), reusable SVG/CSS graphics and
an isolated browser-reviewed route under QA-30. Runtime image-generation
code remains pending. The default story screen is the visual novel
described in [API and UI](13-api-and-ui.md#6-interface-direction). The owner requires
a complete graphical UI kit and persistent character/item artwork, including
supported generation during play. The [concept image](mockups/visual-novel-ui-v1.png)
demonstrates composition only; do not slice its baked-in labels into production controls.

## 2. Development-time UI asset kit

P05.3 adds two original static [vector portraits](../frontend/public/portraits/README.md)
under MIT with editable sources and no third-party inputs. Scoped public IDs bind
them to live character labels; absent/failed images retain a neutral initial and
text attribution. Text-focused mode omits scene and portrait images entirely.
P05.5 completes the local UI kit described below; P17 persistence remains pending.

**Reading layouts and tokens (P17.3-A, 21 November 2026; implemented for the local preview story only).** The story
screen has two finished layouts. *Visual novel* (default): the scene is about 62% of the viewport height (at least 18 rem,
at most 46 rem; 34% on screens up to 40 rem wide, where text has priority), the speaker's portrait overlaps its lower left
edge while the name and the role label (Narration, Character or Player) stay as text in the passage below, and a failed
scene keeps its footprint with a dashed edge. *Text focused*: no image element, no scene or portrait request, a scene
description as text, one reading column. Both show a two-line read-back ("Previously", name, role, text) of the passage
before the current one, outside the live region so advancing announces only the new passage; the full attributed
history stays in the chronicle. The section exposes `data-layout` and `data-scene` (`loading`, `ready`, `failed`,
`hidden`). Three configurable reading tokens live in Settings and in the tab's `engine.preview-reading.v1` record: text
size (15–24 px), line spacing (`compact` 1.45, `comfortable` 1.65 default, `relaxed` 1.9) and reading width (`narrow` 36
rem, `standard` 48 rem default, `wide` 64 rem). A token is chosen by its ID from a closed table, so a stored or typed value
never reaches CSS unchecked, and a snapshot written before the tokens existed stays valid. QA-29 was run on this local preview
story only; full QA-29 is not claimed. Not delivered: reading backwards one passage at a time (only the read-back and the
chronicle); optional text-reveal animation; a pack's own tokens.

**Live screen (P17.3-B, 21 November 2026).** The playthrough screen shows the published narration in the same two layouts
with the same tokens and a "Story layout" select. `data-scene` is `hidden` (text layout), `none` (no registered scene image),
`loading`, `ready` or `failed`; a missing or failed scene or portrait leaves a text label (and the speaker's initial for a
portrait). The scene is looked up in the artwork listing by the newest scene cue, else the resume card's location id; the
portrait by the speaking actor's id (not proven against a real pack's art). The text-focused layout also withholds the panels'
gallery and item icons, so the whole screen requests no image. Verified with a scripted narration source in
`frontend/tools/reading-journey.mjs` and, without a runtime, on the packaged installation with
`frontend/tools/live-screen-check.mjs`; narration from a real signed-in runtime is owner gate P17.3-O.

**Missing media and secrets (P17.5-A, 21 November 2026; component-level proof).** On the playthrough screen a failed scene,
portrait or item image, and an artwork or music listing that cannot be read, change only the decoration: text label, speaker
initial, built-in item icon and an honest credits note stay, and reading and Send keep working. The browser keeps only the
contract fields of a listing row (category, subject, appearance revision, hash, media type, size, credit) and addresses an image
only by the campaign's hash route; a scene image is described by the public place name, portraits and icons are decorative.
`frontend/src/app/campaign/media-fallback.spec.ts` checks this with a simulated server. It does not prove that the server never
lists or serves a hidden asset, and no real browser with failing asset responses was run for it.

**Failing media in a real browser (P17.5-B, 21 November 2026).** `frontend/tools/media-fallback-journey.mjs` repeats the check in
headless Chrome against a loopback stand-in engine that really answers image, line-audio and music requests with 404 or with
undecodable bytes. The documented fallbacks held in both cases: scene text label, speaker initial, built-in item icon, a text notice
for a failed line, an "unavailable" music panel with an explicit retry and no automatic retry, and Send still working. A stand-in
answered, not the packaged engine, and the server's side of secrecy (P17.5-C) is still unproven.

**What the server lists and serves (P17.5-C, 21 November 2026; implemented and tested).** The artwork listing names only the
campaign's own accepted associations and the artwork bindings of its pinned pack revision; the music route names only that
revision's declared tracks. The asset route serves a hash only when the campaign holds an association with it, or when the pinned
revision's presentation binds the file as artwork or as a music track. A sealed file the pack declares nowhere (an image kept for a
later reveal, the lore, the encounters and every other data file), a file of a newer revision the campaign does not pin, another
campaign's artwork and a job result refused as stale are never listed or served. Each refusal is the same 404 bytes as for a hash
or campaign that does not exist. `ManagedAssetsTest` proves this. Its contract: a binding in the presentation file is a public
declaration. The routes do not track whether the player has met the entity a binding depicts, so a pack author must not bind a
spoiler image to a hidden subject, and its subject reference must not name a secret.

P05.5 integrates original MIT geometry from `frontend/src/app/preview/ui-kit.ts`
and `frontend/src/_ui-kit.scss` with the existing theme: 20 allowlisted line icons,
finite/clamped resource meters, an independent crystal illustration, item/empty
slot frames, control states and help surfaces. Workspace navigation and story
actions use decorative icons beside live labels; sheets render unavailable bars
until real values exist, and inventories retain explicit empty slots. The review
route `/interface-kit`, linked from Settings, demonstrates health/focus/XP and
progress values, selected/disabled/busy/invalid states, frames and all icons using
clearly labeled examples. It neither creates items nor changes a campaign.

Native persistent disclosure is used for tooltip/help content so keyboard and
touch users retain the same explanation without hover timing. CSS/SVG remains
editable runtime geometry; no raster generation, third-party library or external
reference is needed. The public inventory records these decisions and all source
families, including existing scenes/portraits. Settings credits and the bundled
`/ui/LICENSE.txt` preserve the complete project MIT notice. No mockup is sliced
or shipped as production controls. P05.6 review builds translate kit labels,
validation, rarity text and accessible names into Italian; packaged localized
delivery remains open.

QA-30 local-kit verification passes 30 frontend tests, strict typecheck/build and
Chrome 155 desktop/mobile review, plus 320px/landscape overflow checks, keyboard
and pointer states, rendered contrast measurements, forced colours, increased
contrast, reduced motion and same-origin notice delivery. Resource values reject
missing/nonfinite/invalid bounds instead of inventing zero; unknown icon names
cannot inject markup or URLs. Text-only story reading still omits story artwork.
Runtime generation, entity artwork persistence and assistive-technology review
remain their separate acceptance gates.

P05.7-A adds the backend admission gate for typed public presentation events (scene,
pose/expression and line-audio hook) in `org.rpgengine.presentation`. An event holds stable
references only; a reference is accepted when it is plain (no URL, path, markup or script) and
on the audience's allowlist of public scenes, lines and assets and visible actors' poses and
expressions. Hidden and unknown actors are refused identically.

P05.7-B.1.1 stores admitted events in `presentation_event` (V26) and delivers them as
`presentation.event` messages of the [public stream](13-api-and-ui.md#p075-stream-and-cancellation),
which render only the allowlisted typed fields and carry a stable `deliveryId`. Rejected drafts are
neither stored nor delivered. That step had no producer during a real turn, did not build the
audience's allowlist from the pack, and did not display cues: the browser only validated the frames. The
paragraphs below add the allowlist and the display; a producer during a real turn is still missing.

P05.7-B.1.2.1 lets a pack declare the public references that allowlist draws on: scenes, lines,
assets and the poses and expressions each NPC definition may take, in one optional `presentation`
file ([format](09-adventure-packs.md#33-presentation-declaration)). Admission refuses a URL,
markup, over-long or over-bounded reference without echoing it, and a backdrop or audio that names
an asset the file does not list. The compiled declaration is part of the compiled pack and the
campaign snapshot; nothing reads it during play yet.

P05.7-B.1.2.2 adds `PublicPresentationScopeBuilder`, a pure function that turns the actors the
audience-filtered projection exposes plus that declaration into the audience's
`PublicPresentationScope`. It adds no visibility model: an actor the caller does not pass, or whose
definition is undeclared, is absent. More than 256 visible declared actors is rejected. Nothing calls
it during a turn yet.

P05.7-B.1.2.3.1 adds `PresentationScopeResolver`, the read-only caller of that builder for one
committed operation. In its own repeatable-read snapshot it reads the campaign's pinned pack
declaration through the pack registry and the actors narration may let speak: the creation-bound
player plus the actors the operation's `PUBLIC` events reference (`INTERNAL` events are never read).
It passes each actor with its `actor.definition_id`, so hidden, unknown and undeclared actors are
absent and a pack without the declaration gives an empty scope. An unknown or foreign campaign or
operation, an uncommitted operation, a revision mismatch or more than 32 public events returns no
scope at all, which a caller treats as "show nothing" and which differs from an empty scope. The pack
closure is recompiled on every call and not cached.

P05.7-B.1.2.3.2 connects the narration path to that gate and adds no second event path. A
`NarrationPort.Publication` may carry at most 16 untrusted cue drafts, empty by default. A worker
resolves the scope between narrating and settling, and only for a publication with cues; the settling
transaction that stores the publication then calls `PresentationEventStore.record` once. Cues are
therefore announced before `narrative.ready`, roll back with the publication, and are never recorded
for a publication discarded as stale, cancelled or late. A refused list, cues naming another operation
or revision, and a missing or failed scope all drop the whole list with a warning that names the
operation and the reason, never the value, and the narration is still published. A storage failure is
not a refusal: it rolls back the settlement, and the lease decides the next attempt. The grounded
publisher produces no cues yet, so no real turn announces one.

P05.7-B.2.1 shows the cues a playthrough receives as labelled text lines in a "Scene and voices" list,
kept in memory only, so a reload forgets them. Line audio is opt-in: a native button requests
`/api/v1/assets/<assetId>` only after it is activated, and a failure leaves a text notice beside the
unchanged text. Scene and pose cues show their references as text, and no image is drawn.

P05.7-B.2.2 checked this in headless Chrome, in English and in Italian, on the packaged interface with a
scripted API and stream: duplicate and reconnect delivery, refused hostile frames, text-only reading,
Tab, Enter and Space, and reduced motion. The [roadmap](16-development-roadmap.md) entry of that name maps
each QA-40 point to its evidence. No real turn produces cues, so no frame from the real backend reached a
browser. The asset route is not served, so a stub supplied the audio and nobody heard it. Persistent
visual and voice identities remain P17 work.

P04.5 includes original MIT-licensed 48×32 woodland/station PNG schematics in the
[neutral import fixtures](../backend/src/test/resources/packs/README.md), with
per-pack license and credit inventories. Integration checks preserve their exact
bytes and verify admitted image metadata. These are import corpus assets;
production campaign scene art, catalogs and delivery routes remain open. Local
preview UI-kit acceptance is recorded above.

P04.4 delivers authored-pack media admission only: PNG/JPEG/WebP image and
Ogg/MP3/WAV audio signatures, byte/dimension ceilings, original immutable bytes
and hashes in PostgreSQL, and an import report. It does not decode or serve media,
generate artwork, prove provenance/rights or validate codec frame/duration budgets.
Those remain gates for the playback/generation/export implementations below;
see [pack admission](09-adventure-packs.md#9-import-and-compilation-pipeline).

P05 must inventory and deliver every asset needed by its screens. Use one coherent theme and icon system. Create missing original illustrations with the development client's supported image-generation tool, or select free assets after checking their actual license. Do not assume a free preview, search result or marketplace listing permits reuse.

| Family | Required deliverable |
| --- | --- |
| Controls | Buttons, reply chips, tabs, fields and toggles with normal, hover, pressed, selected, focus, disabled and loading states as applicable |
| Indicators | Health/focus/XP/progress bars with accessible labels and live numeric values; save/loading/error states |
| Containers | Dialogue box, speaker nameplate, portrait frame, item frame, tooltip and inventory-slot treatment |
| Icons | Consistent navigation, actions, item categories, rarity and status symbols, with text alternatives |
| Story assets | Neutral sample scenes and portraits; independent item artwork, not a flattened interface screenshot |
| Variants | Responsive sizes, high contrast/reduced motion behavior and fallback states |

Prefer CSS/SVG for scalable control geometry, bars and focus rings. Generate or source raster assets where illustration, ornament or texture is useful; retain separate transparent cutouts when needed. Never bake translated labels, stats or interactive hit areas into an image. Keep editable originals or reproducible source/prompt records and export optimized runtime variants.

[Tabler Icons](https://tabler.io/icons) is a candidate free icon set under its [MIT license](https://github.com/tabler/tabler-icons/blob/main/LICENSE), checked on 23 October 2026. It is not installed or bundled by this design change. If selected, pin the package/version and preserve its complete copyright/license notice; the free icon source and paid illustration/template bundles are different offerings. Other libraries require the same per-asset review. Use system fonts unless a separately reviewed font is bundled.

## 3. Stable visual identities

Keep categories separate: scene, map, character portrait, item art, UI/frame, music and effect. A scene change never overwrites a map or portrait. Introduce a proposed `VisualIdentity`/asset association keyed by campaign or admitted pack scope, entity definition revision, category, appearance revision and style profile. Associate item instances with their definition's visual identity unless an admitted visible variant needs its own artwork.

- A recurring NPC reuses the same portrait across dialogue, shop, party and journal views. Preserve visible identity/style through owned or licensed reference art when a supported tool can use it.
- A particular admitted sword has identifying artwork used in shop listing, inspect view, inventory and equipment. Buying it changes ownership, not its image (since P10.2 a whole-stack transfer or trade reassigns the same item instance, so a unique item keeps its identity and therefore its artwork; units split or merged from a stack are equivalent copies that share the definition's icon). Stackable or otherwise equivalent copies can share an icon.
- A visible customization or unique item variation can create an explicit appearance revision. Cosmetic revisions do not silently replace historical artwork or change mechanics.
- Decorative portrait/item frames belong to the theme and are stored/reused independently from the depicted character/object. Rarity badges can select frame styling without generating the entire frame again per entity.
- Unrevealed curses, hidden occupants, identity twists and secret rarity must not enter prompts, filenames, player-facing metadata or images.

Store original accepted images under managed IDs in durable asset storage, with byte hash, MIME/dimensions, thumbnail variants and entity links in the registry. Persist the association, source/appearance revision, generation status and provenance. Include originals and metadata in backup/export/restore according to audience policy. Reloading, renewing narration, revisiting a shop or restoring a save must reuse existing accepted assets, not trigger fresh generation.

## 4. Bounded generation during play

Originals and associations belong to their [adventure workspace/campaign](25-adventure-workspaces.md), with separate generated scene/portrait/item subdirectories. Equal names cannot resolve to shared private art; only public engine assets or deliberate export/import can cross scopes.

Full v1 includes a bounded asynchronous generation job pipeline; static/catalog assets remain sufficient for the MVP. P01 must verify a supported image capability, its authentication/account/cost boundaries and restricted invocation. Codex text access alone is not proof of image access. Do not enable paid API credentials, another service or broader host tools implicitly. When unavailable, report the capability accurately and use a category fallback while preserving play.

**P01.6 evidence:** the managed 0.164.0 narration profile explicitly reports
[unavailable image generation](../tools/runtime_probe/IMAGE_CAPABILITY.md), no
supported invocation/output formats, and unverified account entitlement/cost.
Its developer admission gate always refuses generation while text discovery
remains usable. This settles the P01 capability record; P17.6 must qualify a
separate restricted invocation before enabling generation. The production
pipeline, catalog/category UI fallback and QA-31 remain undelivered.

**Local capability (ADR 0021, worker packaged in P21.5-A, the rest planned in P21.5):** neither agent runtime supplies images, so the supported capability for P17.6 is a local image worker with the owner's chosen checkpoint (recommended default WAI-illustrious-SDXL v17.0), the owner's own A1111-compatible server, or a hosted images API with an owner key. All of them follow this pipeline's authority, budget, provenance and fallback rules ([inference backends](34-inference-backends-and-model-selection.md)).

**Image-generation port (P21.5-B.2, implemented; no runtime job uses it yet):** P17.6 builds the runtime image jobs on one backend-neutral port, `ImageGenerationPort` in `org.rpgengine.inference`. A job submits an `ImageRequest` and gets an `ImageHandle` with a result stage and `cancel`. The request holds the prompt, negative prompt, an optional checkpoint name, the size, steps, guidance, an explicit seed and an optional sampler, and nothing else: no session, actor, scene, entity or canonical state ever reaches a backend, and the port changes no authority rule. The result is `COMPLETED`, `CANCELLED` or `FAILED` with a fixed code, and never completes exceptionally. A completed result carries a PNG that the adapter checked against the request (signature, size, chunk CRCs, pixel stream, text and EXIF chunks removed) and an `ImageProvenance` with the backend, the settings sent, SHA-256 hashes of both prompts and of the output, the output size and the checkpoint hash the server reported, never prompt text. The `a1111-http` adapter implements it, and since P21.4-D so does the `local-image` adapter over the packaged worker, which exists only behind its address and the admission switch. Since P17.6-A the runtime image jobs below provide the one-active-job-per-campaign limit, coalescing and storage in the asset registry, and since P17.6-C the playthrough screen requests images through them; acceptance review remains P17.6 work. Details and limits are in [docs/34 section 8](34-inference-backends-and-model-selection.md#8-local-image-worker-profile).

**Runtime image jobs (P17.6-A, 21 November 2026; implemented in the backend, no caller yet):** `RuntimeImageJobs` in
`org.rpgengine.assets` takes a request for one subject's identifying image (category `scene`, `portrait` or `item`, stable
subject reference, appearance revision and the caller's public appearance text) and answers at once: `QUEUED`, `COALESCED`
(an equal job is queued or running; the ticket follows it), `REUSED` (the subject already shows art for that appearance,
accepted or authored by the pinned pack), `STALE_APPEARANCE`, `DISABLED` (owner switch off, or another category),
`UNAVAILABLE` (no image port), `QUEUE_FULL` or `BUDGET_EXHAUSTED`. Owner settings `engine.presentation.images.*` (see
`application.properties`) enable it (off by default) and bound it: jobs waiting per campaign, generations charged per
campaign per rolling window, and jobs with the port over all campaigns. A campaign always has at most one job with the port.
A charge is taken when a job is queued and kept once the port received it, whatever the result. It is given back when the
job ends unsent: cancelled, superseded by a newer appearance of the same subject, or found already illustrated when its
turn came. The port receives a fixed category framing plus the appearance text, fixed sizes (scene 1216×832, portrait
832×1216, item 1024×1024), 28 steps, guidance 6 and a seed derived from campaign, category, subject and appearance; no
campaign identity, world row or hidden fact. A completed image goes through `ManagedAssets.publish` with the state
revision, pinned pack revision and recovery generation read at request time. So a newer appearance or a newer scene
request in force, another pack revision, or an installation restore (new `STALE_GENERATION` verdict) keeps it out of
storage. An image that arrives after a cancel or after the running timeout (600 s by default) is dropped. Jobs run through
`ImageAdmission` as `SPECULATIVE` work. Limits at P17.6-A (lifted by P17.6-D below): jobs and charges were in memory, so a
restart forgot queued work and reset the window. The service itself does not check that the appearance text is public; that is the caller's contract, which
the game flow below keeps. P17.6-A had no pack policy per category, no acceptance review and no stored provenance; P17.6-D
adds them. Tests: `RuntimeImageJobsTest` on a scripted port.

**Requesting images during play (P17.6-C, 21 November 2026; implemented, scripted port and simulated screen only):**
`RuntimeImageRequests` is the game flow that calls the jobs above. It lists what the player sees now:
- the scene of the current location;
- the portrait of each actor the player saw there and has not since found absent (subject = actor ID, the story's portrait
  lookup);
- the art of each carried item definition.

Only definitions of the pinned root pack qualify, and a location or NPC with `knowledge: secret` never does. The server builds
the appearance text from public fields only: a location's `description`, an NPC's `role` and `description`, an item's
`appearance.summary`. Item descriptions, facts, goals, names and campaign rows never enter it. Every subject is appearance
revision 1, the pinned revision's own, so authored pack art is reused and never regenerated.

`GET /api/v1/campaigns/{campaignId}/artwork/requests?adventureId=` returns the subjects with `missing`, `pending`, `ready` or
`failed` (and a fixed code), the capability (`enabled`, `disabled`, `unavailable`) and the budget. A `POST` on the same path
with exactly `{"category","subject"}` asks for one image. The client cannot send appearance text. A hidden, unknown or
out-of-view subject and a foreign scope all get the same 404. A refusal by the job service is a 200 naming it (see
[docs/13](13-api-and-ui.md)).

The playthrough screen's "New artwork" section lists these subjects and their budget. Each missing or failed subject has a
button; nothing is requested automatically. While a job is pending the section re-reads the list every 3 s (at most 400 times
per revision), and a ready image replaces the fallback in the story and the panels. Disabled or unavailable generation, a spent
budget and a failed read only change that section's text, while reading, Send and the fallbacks stay as they are. The
text-focused layout shows no section and reads nothing.

Limits:
- labels and prompts are in the pack's source language;
- a revealed secret NPC never gets a runtime portrait;
- job states were in memory (at most 2048 subjects) until P17.6-D made them durable rows;
- no shop flow, no change-art action, no real backend or packaged browser run.

Tests: `RuntimeImageRequestsTest` and `frontend/src/app/campaign/runtime-artwork.spec.ts`.

**Durable jobs, pack policy, review and provenance (P17.6-D, 22 November 2026; implemented, scripted port only; [ADR
0056](adr/0056-runtime-image-jobs-are-durable-rows-reviewed-before-storage.md)):**
- Jobs and charges are rows of `runtime_image_job` (V38), so they survive a restart, and so does the budget window. The next
  start resumes queued jobs in order. A job that was already sent ends `image-job-interrupted` and keeps its charge: it is
  never sent twice, and an image that still arrives for it is dropped. Rows of a restored recovery generation end stale.
  Pending and failed states on the screen come from these rows, which replaces the in-memory limit above.
- A pack's presentation file may declare `imageGeneration` per category (`scene`, `portrait`, `item`): `bounded` (the
  default), `catalogOnly` or `disabled`. Only `bounded` generates; the other two leave the pack's own art and the fallback, and
  the "New artwork" listing omits that category.
- Every image passes the automatic review `automatic-v1` before storage. Its provenance must match the bytes and the request
  sent (size, seed, steps, guidance, prompt hashes), and it must decode to the requested size and not be one flat colour.
  A refusal is `image-review-rejected` and keeps its charge. This review does not judge what the picture shows.
- The accepted job row records the image hash and its provenance: backend, model, checkpoint hash, sampler, settings,
  prompt and output hashes, the review and the credit. Prompt text is never recorded. The credit line in "Artwork credits"
  names the backend, for example `Generated image for this campaign (local-image, model 1a2b3c4d5e6f)`.
- With a managed-folder root, each accepted image is also written to its own campaign's
  `assets/generated/<scenes|portraits|items>/` folder ([docs/25](25-adventure-workspaces.md#2-separate-managed-folders)).
- QA-33 for generated images: two adventures generate a portrait for the same subject name, and each image is stored,
  listed, served and written only for its own campaign. That holds when switching between them and after a full-owner
  export is restored into a third adventure. Job rows are not restored with a campaign copy.

Tests: `RuntimeImageJobsTest` (restart, review and policy, QA-33), `RuntimeImageRequestsTest` (policy listing),
`PackPresentationTest`. Not proven: a real image backend, a packaged browser run, a live V38 dump/restore rehearsal.

Pack/owner policy chooses `disabled`, `catalogOnly` or `bounded` by category. The owner enables generation explicitly and sets request/concurrency/budget limits before calls. Default to one active image job per campaign, coalesce duplicate requests and enforce a finite queue; the actual limits and provider behavior require implementation evidence. Do not generate a shop's entire unseen catalog merely because a panel opened.

After an entity is admitted and its appearance is public, a job may request one missing identifying image. Scene art changes only for meaningful observable visual changes or an explicit request. Its input contains only visible appearance, permitted style references and the authorized audience projection. Image output cannot create equipment, change rarity, establish stats or award objects.

Jobs carry stable request identity, category, subject identity, appearance/style revision and source campaign revision. Capture the response, validate MIME/dimensions/byte limits, save to a temporary managed location, hash it, then publish the accepted registry association atomically. A delayed scene result cannot replace the current scene; an old portrait/item result cannot overwrite a newer appearance. Repeated delivery, job retry and save renewal reuse the same accepted association. After an ambiguous provider timeout, reconcile the existing request before spending another generation request; do not claim exactly-once provider billing without supported evidence.

**Asset registry (P17.1-A, 21 November 2026; implemented in the backend, no caller yet):** `org.rpgengine.assets` holds
the seven categories above (`scene`, `map`, `portrait`, `item`, `ui`, `music`, `effect`; images or audio, never mixed in
one category) and the registry of V37. `managed_asset` stores validated bytes under their SHA-256, once, with media
type and declared dimensions; `asset_association` records which hash a campaign shows for one subject (a stable public
reference) in one category at one appearance revision, with the campaign revision the job was requested at, the pinned
pack revision and a credit line. Both tables are immutable. `ManagedAssets.publish` gives the rules of the paragraph
above a tested form: a result for an appearance that is already accepted is `REUSED` and stores nothing, a result for an
older appearance is `STALE_APPEARANCE`, a result requested under a pack revision the campaign no longer pins is
`STALE_CONTENT`, and for scenes and maps a result requested before the accepted one is `STALE_SOURCE`. A request newer
than the campaign is invalid. The media type comes from the bytes' signature and must fit the category, so SVG, markup
and unknown formats are refused. The route `GET /api/v1/campaigns/{campaignId}/assets/{sha256}` serves by hash and
only within the campaign (see [docs/13](13-api-and-ui.md#2-proposed-endpoint-families)). The metadata is exactly the hash, media
facts, public subject reference, revisions and credit; there is no prompt, seed, path or URL column. Not delivered by P17.1-A
(binding, gallery and byte carriage came with P17.1-C below; separate thumbnail variants are still not made): binding
authored `asset` files to categories and subjects, the thumbnails, and the carriage of the bytes through export and restore.

**Item icons, frames and credits (P17.1-B, 21 November 2026; implemented in the playthrough panels):** `GET
/api/v1/campaigns/{campaignId}/artwork?adventureId=` lists the newest accepted association of each category and subject, so
the browser finds an entity's stable artwork by category and stable reference (never a display name) and builds the hash
URL from it. The inventory and the worn-equipment lists show each item's icon, `category: item` keyed by its definition
ID, so every instance and a unique item keep the same image wherever they are shown, and a worn item reads its tier from
the same carried instance as the inventory. Without art, or when the image does not load, the list shows a neutral
built-in icon (a weapon glyph for a hand slot, the bag glyph otherwise); art is decorative (`alt=""`) because the item name
is always live text beside it. The frame is independent of the image and follows only the declared rarity tier: `common`
to `legendary` give five border styles of different style and weight (solid, dashed, double, ridge, double with an
outline), a pack's own tier gives one more, an item without a tier gets a dotted one; the tier name is written beside the
frame with a diamond symbol, so colour is never the carrier, and forced-colors mode keeps the borders. A section "Artwork
credits" lists the credit line of each image in force once, or says that none is credited. A failed listing leaves the
built-in icons and no credits and never stops the page. Not delivered: any shop screen (none exists), portrait, scene and
map display, thumbnails, packs declaring their own tier order or frame tokens (only the five default tier IDs rank), a
human contrast review, and anything that creates art (P17.6). Browser evidence (21 November 2026): a scripted stand-in
run (not the packaged installation) measured seven distinct frames and reload identity with registered images; a run
against the packaged installation (`frontend/tools/artwork-journey.mjs`, after `start.sh`) showed the credits note, built-in
icons in the `unranked` frame, no axe violation with `color-contrast` passing, a frame border ratio of 9.79, and an equip that
changed the worn items through the engine's command route. Not observed in the packaged installation: a registered
image and its credit line (the engine has no route that publishes one, and rows are never inserted by hand) and a restored
playthrough (there is no restore route). The browser view of a registered image, its credit line and the same image after
an equip and a reload is left to P17.1-C, which binds authored pack artwork and bundles an image in the sample; until then
registered-image identity is shown only by the backend tests (`ManagedAssetsTest`, `CampaignRestoresTest`), the frontend
tests and the scripted stand-in. Automated in `campaign-artwork.spec.ts`: equivalent items reuse one icon, and a carried
item with registered art keeps the same image address, frame token and written tier when it becomes worn (the panels are
re-read) and when the page is opened again; the restore half is `CampaignRestoresTest`. Only browser-observed, with the
built-in icon: an equip and a reload on the packaged installation. The journey script has no phase that inserts rows; its
former `sql` phase was removed. The built-in icons are the original MIT UI-kit paths inventoried in
`frontend/public/ui/README.md`.

**Authored artwork, gallery and byte carriage (P17.1-C, 21 November 2026; implemented):** a pack binds authored image
files to entities in the `artwork` list of its `presentation` file ([docs/09 section
3.3](09-adventure-packs.md#33-presentation-declaration)): category (`scene`, `map`, `portrait`, `item` or `ui`), stable
subject reference, a listed `asset` file and a credit line. The artwork listing adds each binding the pinned pack revision
declares, as appearance 1, unless the campaign already holds an accepted association for that category and subject, which
wins. The bytes are the sealed pack file, served by hash through the same route (since P17.5-C only for files the presentation
binds); a pack that cannot be loaded binds
nothing and never hides the associations. The bundled sample binds `assets/sword.png`, an original icon made by
`tools/sample-art/make-sample-art.mjs`, to item `sword`. The playthrough panels show scene, map, portrait and UI images in
a gallery by category with a 64 px thumbnail, which is the stored image scaled by the browser: separate stored thumbnail
variants are not made. A full-owner export carries the bytes of the images its associations name in a top-level `assets`
array (base64, one entry per hash), and a restore stores them back under the same SHA-256 after checking digest, media
admission and that an association names each one; authored pack artwork is not repeated because a restore matches the sealed
pack revision by content hash. Packaged-browser evidence and gaps are in the [roadmap](16-development-roadmap.md) entry
P17.1-C; no shop icon exists yet (P17.1-D).

Use a neutral category icon/portrait and visible pending/error state until art is available. Save, purchase, dialogue and combat must remain usable during failure, quota exhaustion, offline periods or policy disablement. Allow explicit retry/change-art actions with budget visibility; do not reroll art on every inspection.

## 5. Rarity and item presentation

Rarity is validated item data, independent from image quality or price. A pack may define supported tier IDs, localized names and presentation tokens. Proposed default labels are common, uncommon, rare, epic and legendary; they are presentation/authoring defaults, not automatic stat multipliers, price formulas or drop chances. A ruleset must explicitly define any supported mechanical consequences. Do not promise every genre uses those labels.

Since P10.1 the backend rule catalog stores only the declared tier ID of an item; it has no appearance, color or image field, and tests prove that an ornate appearance leaves tier, slot, hands and tags unchanged ([ADR 0023](adr/0023-character-rules-and-equipment-constraints.md)).

Show the known tier as text plus an icon/frame treatment; color is supplementary. Shop, tooltip, inventory and equipment use the same admitted tier and visual identity. Unknown rarity stays unknown. A generated ornate sword is not upgraded to legendary because of its art. Rarity changes, if supported, require a validated, source-linked content or progression operation; they cannot be a media job side effect.

## 6. Provenance and acceptance

The [competitive source review](27-competitive-analysis-and-gaps.md) motivates
reusing admitted layered body/face/clothing/expression assets for deterministic
variation, where available, without importing competitor code or artwork.
Repeated prompts or seeds alone are not a guarantee of identity across model
versions. Keep approved original portraits/reference sheets, appearance/style
versions and reference-asset rights; pass those references only to a capability
that supports them. Clothing/equipment variants must refer to observable admitted
changes. Validate association and review visual consistency before replacing an
accepted face/item image; retain the prior compatible asset on uncertainty.

Treat reference-conditioned generation, deterministic layered compositing and
fully new generation as distinct techniques with different verification. A
model/version change invalidates unsupported assumptions, not the saved original
asset. Caches bind subject/appearance/style/model/reference digests and audience,
not a character display name. Restored copies keep original bytes and remapped
metadata; they do not regenerate a similar-looking approximation.

Image and optional speech jobs use the shared media-resource admission policy
in [local speech](30-local-speech-synthesis.md). Local generation cannot reserve
GPU memory indefinitely, starve currently read dialogue or start while another
heavy model exceeds the admitted memory envelope. Cloud image generation still
needs its own supported capability and account budget. No GPU worker receives
gameplay credentials, hidden planner notes or authority to change canon.

Each adopted asset records ID/category, creator/source, exact license and URL, required notice/attribution, modifications, verification date, and the saved artifact hash. Generated original art also records tool/version where exposed, prompt/style/reference provenance and any known restrictions. Do not invent a seed, model identifier or copyright ownership guarantee the tool does not supply. Only use reference material with appropriate rights. Imported third-party assets retain their licenses; the repository MIT license does not replace them.

P05 checks the complete asset/state inventory in a real responsive UI. P17 checks a returning NPC portrait, a unique sword moving shop → inventory → equipment, equivalent-instance icon reuse, saved/reloaded/restored visual identity, stale job completion, hidden-information sentinels, unavailable image capability, duplicate requests and generation limits. No placeholder-only asset inventory or concept screenshot counts as shipped UI. See QA-29–31 in [quality and acceptance](15-quality-and-acceptance.md).
