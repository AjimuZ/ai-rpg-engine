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
neither stored nor delivered. Not implemented yet: a producer during a real turn, the pack-catalog
source of the allowlist, and display in the browser, which only validates the frames.

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

**Local capability (ADR 0021, planned in P21.5):** neither agent runtime supplies images, so the supported capability for P17.6 is a local image worker with the owner's chosen checkpoint (recommended default WAI-illustrious-SDXL v17.0), the owner's own A1111-compatible server, or a hosted images API with an owner key. All of them follow this pipeline's authority, budget, provenance and fallback rules ([inference backends](34-inference-backends-and-model-selection.md)).

Pack/owner policy chooses `disabled`, `catalogOnly` or `bounded` by category. The owner enables generation explicitly and sets request/concurrency/budget limits before calls. Default to one active image job per campaign, coalesce duplicate requests and enforce a finite queue; the actual limits and provider behavior require implementation evidence. Do not generate a shop's entire unseen catalog merely because a panel opened.

After an entity is admitted and its appearance is public, a job may request one missing identifying image. Scene art changes only for meaningful observable visual changes or an explicit request. Its input contains only visible appearance, permitted style references and the authorized audience projection. Image output cannot create equipment, change rarity, establish stats or award objects.

Jobs carry stable request identity, category, subject identity, appearance/style revision and source campaign revision. Capture the response, validate MIME/dimensions/byte limits, save to a temporary managed location, hash it, then publish the accepted registry association atomically. A delayed scene result cannot replace the current scene; an old portrait/item result cannot overwrite a newer appearance. Repeated delivery, job retry and save renewal reuse the same accepted association. After an ambiguous provider timeout, reconcile the existing request before spending another generation request; do not claim exactly-once provider billing without supported evidence.

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
