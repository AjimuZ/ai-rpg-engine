# Changelog

## Unreleased — Isolation proof for the second narration client (P20.1-B), 22 November 2026

- Nothing changes for players: the second narration runtime still cannot be selected. For developers, a new runtime-probe
  test runs the pinned client (Claude Code 2.1.323) against a scripted upstream with no account. It plants synthetic
  home and project settings, hooks, MCP servers, instruction files, skills and credentials. A positive control shows
  that each probe detects its surface. The test then requires the isolation profile to expose none of them, refuse
  scripted tool calls and leak no sentinel value.
- The first container run found that the client loads three bundled plugins even with every setting source off. The
  isolation profile now turns off each of the 14 bundled plugins of 2.1.323 through the documented `enabledPlugins`
  setting. The positive control now trusts its own workspace, so its scripted host command can run.
- Checked: the 9 offline fixture tests pass. The supervisor's container suite rerun on the fixes passed
  (runtime-probe-docker `Ran 137 tests`, `OK`; container-packaging `Ran 357 tests`, `OK (skipped=6)`).
- Not done: subscription sign-in and hostile launch flags were not exercised, and the client itself does not refuse a foreign API key, so the
  later startup guard must.

## Unreleased — Second narration client pinned with a registry integrity check (P20.1-A), 22 November 2026

- Nothing changes for players: the first narration runtime is still the only one, and the second one cannot be selected
  yet. For developers, a probe image now pins the second narration client (Claude Code) at exactly version 2.1.323. Its
  two npm registry archives must match the SHA-512 integrity that the registry publishes, and the client is unpacked
  without Node.js or npm, so no install script runs.
- Checked: the probe image builds, and its container checks pass (`verify.sh containers`, 22 November 2026). The
  installed binary matches the verified archive, and `claude --version` runs offline as an unprivileged user.
- Not done: signing in, the launch profile, the isolation proof and the terms review are later steps.

## Unreleased — Voice input checked as off, with typed play complete (P17.10-A), 22 November 2026

- Voice input stays off in this release, and the playthrough does not change. The voice acceptance checks were run for
  the "off" case: in English and Italian, silence, noise, negations, words like "confirm" or "annulla" inside a spoken
  line, repeated results, reconnecting and two adventures with the same place name never turn on the microphone, never
  create a draft and never queue an action.
- The engine offers no way to send audio, and the screen has no microphone code, while typed commands in exploration and
  combat work as before.
- Not done: no microphone, recognizer or audio was run, and there is no English or Italian accuracy, echo, interruption
  or speed figure. Turning voice on needs a qualified recognizer first.

## Unreleased — Voice input stays off with typed play complete (P17.9-A), 22 November 2026

- Voice input is still not available, and the playthrough does not change: there is no microphone button, the engine never
  asks for microphone permission, and you type in exploration and combat as before. If a later version asks the engine to
  listen, it now answers "unavailable" with the reasons (no recognizer qualified yet) and records nothing.
- A speech result that reaches the engine anyway is thrown away. It never becomes a draft and never queues an action, even
  if it contains a word like "confirm", so nothing can run out of turn.
- Not done: listening and mute controls, spoken confirmation and correction, and echo suppression need a qualified recognizer
  first. No microphone or audio was run.

## Unreleased — Speech recognition recorded as not qualified and shipped disabled (P17.8-A), 22 November 2026

- Voice input is not available in this release, and nothing in the playthrough changes: you type, and the engine never asks
  for the microphone. The engine now records why: no speech recognizer has been checked for its license and none has been
  measured for speed or memory, so none is named or selected. Capture can turn on only when the owner asks for it, six
  qualification checks have passed and one recognizer is qualified; none of that holds today.
- The limits any future recognizer must fit are written down (one utterance at most 30 seconds and 2 MiB, one waiting
  utterance, one attempt, one capture session per adventure, a cancel that always releases the audio), together with the
  offline behaviour it must show. These limits are design choices, not measurements.
- Not proven: no recognizer, microphone or audio was run, no license or revision was read, and no speed, memory or English
  and Italian accuracy figure exists. Research and measurement are a later owner step.

## Unreleased — Optional speech qualified and shipped disabled (P17.7-A), 22 November 2026

- Spoken dialogue is not available in this release, and nothing in the playthrough changes: every line is read as text. The
  engine now records why. For each candidate voice model (Qwen3-TTS, Kokoro, Chatterbox, Piper, XTTS-v2) it keeps the declared
  code and weight licenses and states that no voice recording is cleared for use; XTTS-v2 is excluded because its terms are
  noncommercial. Speech can turn on only when the owner asks for it, all five rollout checks have passed and one model is
  cleared for both English and Italian; none of that holds today.
- Any line handed to the speech step comes back unchanged and silent, with the reason. A language other than English or
  Italian is never read in another language's voice.
- Not proven: no model, voice or audio was downloaded or run, nothing was measured on the RTX 4070, and no English or Italian
  listening review took place. The licenses are the ones read on 23 October 2026.

## Unreleased — Durable image jobs with pack policy, review and provenance (P17.6-D), 22 November 2026

- Image jobs and their budget now survive an engine restart. Queued images are made after the restart, in order. An
  image that was being made when the engine stopped shows as failed and can be asked for again; it is never generated
  twice, and a result that arrives late is not used. The budget period is not reset by a restart.
- An adventure can say per category whether the engine may make scene, portrait or item images for it
  (`imageGeneration` in its presentation file: `bounded`, `catalogOnly` or `disabled`). Categories it keeps to its own art
  are not offered under "New artwork". Adventures that say nothing behave as before.
- Each image is checked before it is kept. It must match what was asked for, be the requested size and not be blank. A
  failed check shows as a failed image. What the image shows is not judged.
- Each kept image records where it came from (backend, model hash, settings and hashes, never the description text).
  Its credit line in "Artwork credits" names the backend that made it.
- With a managed data folder, kept images are also written to their own playthrough's `assets/generated/` folder. Equal
  names in two adventures never share an image, including after switching adventures and after a playthrough copy is
  restored.
- Database migration V38 adds `runtime_image_job`, and the backup tool now covers schema 38. A restored playthrough copy
  does not take over the source's jobs. Not proven: a real image backend, the packaged browser, and a live V38 backup and
  restore.

## Unreleased — Ask for scene, portrait and item images during play (P17.6-C), 21 November 2026

- The playthrough screen has a "New artwork" section. It lists the current place, the people you have seen there and the items
  you carry, each marked as having no image yet, being made, ready, or failed with a reason, plus how many images were asked
  for in the current budget period. A button asks for one image; nothing is requested on its own. You can keep reading and
  sending while it is made, and a ready image replaces the built-in fallback in the story and the panels.
- The engine builds each image description itself from the adventure's public text only: a place's description, a person's
  role and description, an item's appearance summary. A hidden place or person is never listed, and the browser cannot send
  its own description. Art the adventure already ships is reused, never regenerated.
- Image creation stays off by default. When it is off, when no image service is set up or when the budget is used up, the
  section says so and play goes on with the fallback art. The text-focused layout shows no section.
- New routes `GET` and `POST /api/v1/campaigns/{campaignId}/artwork/requests`. Not proven: a real image backend and a run in
  the packaged browser.

## Unreleased — Bounded runtime image jobs (P17.6-A), 21 November 2026

- The backend can now turn a request for a missing scene, portrait or item image into at most one generation, store the
  validated result as the subject's artwork and reuse it afterwards. It is off by default (`engine.presentation.images.enabled`),
  needs a configured image port, and no game flow, route or screen calls it yet, so players see no change.
- Limits the owner sets: queued jobs per playthrough, generations per playthrough per rolling window, and jobs running at once.
  A playthrough has at most one job running at a time. Equal requests join one job. A subject that already has art, including
  art from the adventure's own package, is never generated. A queued request for an older appearance is replaced by a newer one.
- A late image is refused if a newer appearance or scene is in force, if the playthrough was restored, or if the job was
  cancelled or timed out. Jobs are kept in memory only, so a restart forgets queued work and resets the window.

## Unreleased — Asset routes never expose hidden or foreign media (P17.5-C), 21 November 2026

- The campaign asset route now serves a file from an adventure's own package only when its presentation file binds that file as
  artwork or as a music track. A file the package ships without declaring it for display, such as an image kept for a later
  reveal, is refused like an unknown hash, even when its exact SHA-256 is requested. Before, every sealed asset file of the pinned
  version was served to a holder of its hash. Nothing the interface shows changes, because it only requests what the artwork and
  music listings name.
- New backend tests show that the artwork listing, the asset route and the music route never list or serve such a file, a file of
  a newer version of the adventure that the playthrough does not use, another playthrough's artwork, or a job result refused as
  stale, and that each refusal looks exactly like the one for something that does not exist. Not covered: whether an entity a
  package binds art to has been met in play, because a binding is a public declaration.

## Unreleased — Missing media proven in a real browser (P17.5-B), 21 November 2026

- No product change. New `frontend/tools/media-fallback-journey.mjs` drives the live playthrough screen in headless Chrome while a
  loopback stand-in engine really answers every image, line-audio and music request with 404 or with corrupt bytes. In both cases
  the scene shows its text label, the portrait its initial, the item its built-in icon, a failed line or track is reported in text
  and not retried by itself, and a quick reply plus Send still reaches the server (50 checks: 14 in the control pass and 18 in
  each of the two failing passes). Not proven: the packaged installation, the backend never serving hidden assets (P17.5-C), audible playback.

## Unreleased — Missing art and audio never block play (P17.5-A), 21 November 2026

- No product change. New spec `frontend/src/app/campaign/media-fallback.spec.ts` shows that on the playthrough screen a failed
  scene, portrait or item image, or an artwork or music listing the server cannot read, leaves the story, the panels and Send working,
  and that images are referred to only by the campaign's hash route, with no path, URL, prompt or note from the server in the page.
  Proof is at component level; see the roadmap for what was not run (real browser with failing assets, the packaged installation,
  the backend).

## Unreleased — Real accessibility and viewport checks (P17.4-A), 21 November 2026

- "Skip to content" now moves focus to the page content. Before, pressing it on any screen reloaded the application at its start
  page, so keyboard users lost their place.
- New `frontend/tools/accessibility-check.mjs` runs 363 browser checks over the 11 routed screens at desktop, 390 px, the 320 px
  reflow width, with reduced motion and with enlarged text and text spacing: no axe violation, no horizontal overflow or clipped
  text, a visible focus indicator at every Tab stop and no keyboard trap. It is not a screen-reader review, and screen-reader
  coverage is none.

## Unreleased — Layouts and quick replies on the live screen (P17.3-B), 21 November 2026

- The playthrough screen now shows the narration the engine published as an attributed story: the newest passage with the
  speaker's name and role (Narrator, Character), a two-line "Previously" read-back, and a chronicle of every passage. It follows
  the visual-novel or text-focused layout, which you can switch on the screen ("Story layout") and which is kept for the tab.
  The visual novel shows the place's scene and the speaker's portrait when the adventure has them and a text label when it does
  not or when an image fails; the text-focused layout draws no image anywhere on the screen, panels included.
- After a character has spoken, up to three suggestions (speech, question, action) can appear. Choosing one only fills an empty
  draft, which you can edit. Nothing is sent until you press Send; the words then go to the character who spoke last. If the
  reply is lost, pressing Send again repeats the same request, so the words are not counted twice. Suggestions disappear when the
  world has moved on, during a fight and while a playthrough is paused.
- The browser now reads the `presentation` of delivered operations (passages and suggestions) and refuses a malformed one.
  Six new interface messages have an Italian text from a single author pass.
- `frontend/tools/reading-journey.mjs` now also drives the live screen against a scripted narration source (32 checks), and the
  new `frontend/tools/live-screen-check.mjs` checks the packaged installation without a signed-in runtime. QA-29 with narration
  from a real runtime is not claimed; it belongs to the owner gate P17.3-O.

## Unreleased — Reading layouts and tokens (P17.3-A), 21 November 2026

- The story screen of the local preview now has two finished layouts. The visual novel gives the scene about three fifths
  of the window height with the speaker's portrait overlapping its lower edge; a scene that fails to load keeps its place
  with a dashed edge. The text-focused layout loads no image at all and shows the scene as a text description.
- Both layouts show a short "Previously" line with the name and role (Narration, Character or Player) of the passage before
  the current one; the chronicle still holds the full attributed history. Advancing announces only the new passage.
- Settings adds two reading choices next to text size: line spacing (compact, comfortable, relaxed) and reading width
  (narrow, standard, wide). They are kept for the tab and survive a reload; saved preferences from before still load.
  Nine new interface messages have an Italian text from a single author pass.
- New repeatable check `frontend/tools/reading-journey.mjs` runs the reading and quick-reply journey (QA-29) in Chrome for
  both layouts. It covers the local preview story only; the live screen followed in P17.3-B (above), and full QA-29 with live
  narration is not claimed.

## Unreleased — Adventure mood policies and sample music (P17.2-D), 21 November 2026

- An adventure pack can declare its own music in its `presentation` file. It maps the six public situations (quiet,
  fight offered, fight, won, lost, settled) to moods and lists at most one credited track per mood, bound to an audio
  file in the pack. A pack that names anything else does not import, so music cannot react to anything the player has
  not seen. See docs/09 section 3.3 and ADR 0054.
- New read route `GET /api/v1/campaigns/{campaignId}/music?adventureId=` delivers a playthrough's pack policy and
  tracks. The browser plays the tracks through the existing asset route by their hash and refuses any listing outside
  the allowlist.
- The sample adventure "Lantern Ford" now has music: two short original loops, "Ford at Dusk" (exploration) and "Stones
  in the Current" (tension when a fight is offered or under way). They are made by `node
  tools/sample-music/make-sample-music.mjs` and covered by the sample's MIT license. Turning music on in the sample
  therefore no longer needs the internet.
- Music fixes found on a packaged installation:
  - switching playthroughs no longer starts an online track for a moment before the adventure's own track;
  - starting a fight no longer restarts the tension track;
  - a crossfade now ends at volume 0 before the old track stops.
- A new installation now starts with a second sample adventure, "Neon Alley": a short city adventure in English, with
  its adventure text also in Italian, and two original loops of its own, "Neon Rain" and "Humming Wires". Both samples
  appear in the library. An installation that already has adventures keeps exactly what it has. See ADR 0055.
- Music stops at once when you switch to a playthrough of the other adventure, and that adventure's own track starts.
- The music player now releases its audio and fade timer when the page is torn down, so the interface's unit tests
  always end by themselves.
- New repeatable check `frontend/tools/music-journey.mjs` runs the music acceptance journey (QA-32) against a packaged
  installation, including both samples and a switch between them.

## Unreleased — Music credits, local tracks and an effects volume (P17.2-C), 21 November 2026

- The current track now shows its notices, disclosed changes and the date its rights were checked, besides creator,
  license and source. A new section lists the complete credits of the tracks that played in this visit, in a text box and
  as a downloadable text file.
- Every campaign export (full-owner and player) carries a `music` section with the complete credits of the reviewed
  recordings the engine may play. The engine does not record which track sounded on a device, so the section lists
  all of them. A restore ignores the section.
- Installed local tracks are supported beside the online ones: a local record needs the same complete, admitted-license
  rights record plus a plain file name and a SHA-256, plays from the installation's own `/assets/music/` folder and follows
  the same loop, crossfade and failure rules. No local track is bundled.
- Music and effects volumes are separate sliders and both are remembered on the device. No sound effect exists yet, so
  the effects slider changes nothing audible today.
- A pack's mood policy can be read and checked (public states to moods only); it is not delivered from a pack yet, so the
  default mapping applies. Delivery of that policy and the QA-32 check are left to a later step (P17.2-D).
- 7 Italian messages are added (776 messages).

## Unreleased — Optional dynamic music (P17.2-B), 21 November 2026

- The playthrough view has a Music section, off by default. Turning it on (a click) starts a looping track chosen from the
  reviewed free-license catalog by the public situation: calm exploration music, tension music when a fight is offered, and
  silence for moods without a reviewed recording. Changes crossfade over about two seconds and repeated or equivalent
  cues do not restart the track.
- Nothing is loaded from the internet before the player turns music on, and a reload needs a new click. Pause, mute, a
  music volume, the current track's creator, license and source links, and a searchable list of all credits are included.
  A browser that refuses playback or a source that cannot be loaded shows a short notice and the story is unchanged.
- 24 Italian messages are added (769 messages).
- Not included: playback verified against the real host, credits in exports, locally installed tracks, an effects volume
  and a pack-defined music policy. The tracks stream from Wikimedia Commons and need a network connection.

## Unreleased — Authored pack artwork, an original sample image and an artwork gallery (P17.1-C), 21 November 2026

- A pack's optional `presentation` file can now bind a listed image file to the item, NPC or scene it depicts, with a
  credit line (`artwork` list, [docs/09](docs/09-adventure-packs.md#33-presentation-declaration)). The artwork listing
  includes those bindings, so an item definition shows the same image in the inventory and the worn equipment.
- The bundled sample adventure ships an original 96x96 sword icon, drawn by `tools/sample-art/make-sample-art.mjs` (no
  download or dependency), and credits it in `CREDITS.md`, `LICENSE.md` and the "Artwork credits" section. On a fresh
  packaged installation the browser showed that image, its frame and its credit line in both lists, unchanged after an
  equip, an unequip and a reload.
- The playthrough panels list scene, map, portrait and interface images by category with a thumbnail (the stored image
  scaled by the browser) when a campaign has any; five Italian messages are added (745 messages).
- Campaign exports now carry the bytes of the campaign's registered images and audio in an `assets` section, and a
  restore stores them back under the same hash. An older export without the section still restores, with its
  associations and no bytes.
- Not included: shop icons (P17.1-D; there is no shop screen), runtime image creation (P17.6), packs declaring their
  own rarity order, and a browser view of the gallery on the packaged installation (the sample has no such artwork).

## Unreleased — Item icons, rarity frames and artwork credits in the playthrough panels (P17.1-B), 21 November 2026

- The inventory and the worn equipment now show an icon for each item inside a frame: the campaign's registered
  artwork for that item when it has one, a neutral built-in icon otherwise. A missing or unreadable image falls back to
  the built-in icon and never stops play.
- The frame follows the item's declared rarity tier only (the default tiers common to legendary get five distinct
  border styles; a pack's own tier gets one more; no tier, none). The tier name is always written beside the frame with
  a symbol, so colour and border never carry it alone. A worn item shows the same tier as in the inventory.
- A new "Artwork credits" section lists the credit line of each image in force, or says that none is credited.
- New route `GET /api/v1/campaigns/{campaignId}/artwork?adventureId=` lists the newest accepted artwork per category
  and subject (hash, media facts, credit; no prompt, seed, path or URL) for the owner's campaign. Italian translations
  for the two new messages are added (740 messages).
- Nothing creates artwork yet: no game flow calls the registry (P17.6), so until it does every item shows the
  built-in icon. There is no shop screen, and portraits, scenes and maps are not displayed.
- A repeatable browser journey for these panels, `frontend/tools/artwork-journey.mjs`, runs against the packaged
  installation. It showed built-in icons, the no-credit note, no accessibility-tool violation (contrast passing) and
  an equip that changes the worn items; a registered image with its credit line was not seen there, because the engine
  has no route that publishes one yet. That view is left to P17.1-C, which binds authored pack artwork and bundles an
  image in the sample. The journey no longer has a phase that prints a database insert; rows are never inserted by hand.
- New automated tests (frontend suite now 253 tests, `campaign-artwork.spec.ts` 18 cases) show that an item's image, frame
  and written tier stay the same when it is equipped and when the page is opened again, and that equivalent items share
  one icon. Equip and reload on the packaged installation were only observed in the browser, with the built-in icon.

## Unreleased — Managed assets, hash serving and stale-result protection (P17.1-A), 21 November 2026

- The engine now has seven separate asset categories (scene, map, portrait, item, UI, music, effect), each holding
  only images or only audio, and a registry of validated media with one accepted asset per campaign, category,
  subject and appearance revision.
- A late job result is refused as stale when a newer appearance is accepted, when it was requested under a pack
  revision the campaign no longer pins, or, for scenes and maps, when it was requested before the accepted one. The
  first accepted result for an appearance stays. A stale result stores nothing.
- New route `GET /api/v1/campaigns/{campaignId}/assets/{sha256}?adventureId=` serves an asset by its SHA-256, only
  to the owner of a campaign that is associated with it or whose pinned adventure version ships it, with the stored
  media type, `nosniff`, a CSP that allows nothing and the hash as the `ETag`.
- Database migration V37 adds `managed_asset` and `asset_association`, and the backup tool now covers schema 37.
- Nothing produces or chooses art during play yet: no game flow calls the registry (P17.6), and the interface does
  not display it.

## Unreleased — Manual file authoring guide (P16.5-A), 21 November 2026

- New section 11.1 of the adventure pack specification (`docs/09-adventure-packs.md`): a step-by-step guide to
  writing, validating and evolving a pack by hand, with a complete neutral example (`Harbor Lamp`) and a
  troubleshooting table of real diagnostic codes.
- The example is the test resource directory `backend/src/test/resources/packs/manual-authoring-example`.
  `ManualAuthoringExampleTest` (4 cases, no database) compiles it and reproduces the troubleshooting rows.
- No production code, route, page or setting changed.

## Unreleased — Campaign restore into a chosen adventure (P16.4-A), 21 November 2026

- The engine can now restore a full-owner campaign export as a new campaign. The owner names the adventure that
  receives it; the engine never guesses one, never matches anything by title and never writes into an existing
  campaign, even one with the same name.
- The restore checks the export's SHA-256, its format, the database schema version and that the chosen adventure
  holds the same adventure version with identical files. That version supplies the original artwork.
- By default every identity in the campaign is new, so the restored copy can sit beside the original. Keeping the
  original identities is an explicit choice for a clean installation; a clash is refused.
- Everything in the export comes back: the world, an active fight, operations whose narration was still owed, memory,
  plans and the random seed. The restored campaign continues from the same state revision. The source scheduler's
  queued model tasks are not restored.
- A refused restore writes nothing.
- Limits:
  - There is no page, route or preview for restores yet.
  - The restored campaign does not record which export it came from.
  - Replacing an existing campaign is not offered.
  - The adventure version must already be installed in the chosen adventure; import its bundle first.

## Unreleased — Previewed and audited canon corrections (P16.3-A), 21 November 2026

- The owner can now correct one recorded fact of a campaign (a knowledge claim): its truth, and optionally its
  wording or, for a property, its value. What the fact is about never changes.
- A preview shows exactly what the correction would do, and writes nothing: the fact before and after, the events it
  came from, which characters' beliefs follow it, which prepared plans become invalid and which story summaries
  become out of date. The preview is produced by running the correction and rolling it back.
- Applying needs the preview's digest. If anything it reported has changed, the correction is refused and the owner
  previews again. Before writing, the engine takes a full-owner export of the campaign as a backup and stores it
  with the approved preview.
- History is kept: every earlier event stays as it was, the old fact and the old beliefs stay recorded and are only
  closed. The correction itself is one internal event that names the old and new fact, the owner's reason and the
  digests of the preview and of the backup. Repeating the same request changes nothing.
- Database migration V36 adds the `canon_correction` table, and the backup tool now covers schema 36.
- Limits:
  - There is no page or route for corrections yet, and nothing restores a correction backup yet.
  - The backup is stored in the same database, so it does not replace an independent backup.
  - Only knowledge claims can be corrected. Previews of moving a campaign to another adventure version are not
    available yet.

## Unreleased — Adventure bundles for sharing between people (P16.2-B), 21 November 2026

- The engine can now export an installed adventure version as one ZIP bundle to give to another person. The bundle
  holds the exact files of that version and of every adventure it depends on, plus a `bundle.json` manifest. For
  each pack the manifest lists its ID, version, content hash, file hashes, dependencies, source language and
  translations, license, authors, provenance, and its license and credit files.
- A bundle never contains saves, campaigns, journals, account data or local identifiers. Exporting the same version
  twice gives the same file.
- A recipient can preview a bundle before importing it: title, version, languages, dependencies, rights, and whether
  each pack is new, already installed or in conflict with what they have.
- Import checks the archive like any adventure import, whoever sent it. A bundle with a dangerous path, a
  decompression bomb, an edited file, an edited manifest, an extra file or a missing dependency is refused before
  anything is written.
- By default an import creates a new adventure. An import into an existing adventure stops when that adventure holds
  the same pack version with different files. Nothing already installed is overwritten, no save is touched, and
  importing the same bundle again changes nothing.
- Limits:
  - There is no page or route for bundles yet.
  - Packs have no per-asset rights records, so a bundle cannot leave out assets that may not be shared.
  - If the recipient's engine rejects a later pack, the packs before it stay installed in the new adventure.

## Unreleased — Full-owner and player campaign exports (P16.2-A), 21 November 2026

- The engine can now export a campaign as a JSON document, and the caller has to choose who it is for.
- A full-owner export is a complete copy of the campaign's saved state, secrets included: hidden quests, internal
  events, the random seed, what every character knows, and job, plan and session records. Keep it private, like a
  backup.
- A player export contains only what the player panels show: the character, resources, conditions, abilities,
  inventory and equipment, discovered quests, the player's own knowledge and promises, and the whole public journal.
  A test plants secrets in a campaign and checks that none of them reaches it.
- Both exports name the adventure content they need (pack, version, content hash, ruleset) and come with a SHA-256
  digest. Neither export includes account data. Exporting changes nothing, and exporting the same state twice gives
  the same file.
- Limits:
  - There is no page or route for exports yet.
  - Nothing can import or restore an export yet.
  - Pack files and media are not included.
  - A player export keeps only the 100 newest discoveries and promises.

## Unreleased — Author validation reports, dependency and version diagnostics (P16.1-A), 21 November 2026

- The engine can now check a candidate adventure pack for an adventure and return a full validation report, instead of
  stopping with an error. A failing pack gets one diagnostic: the step that failed (reading the files, parsing, or
  checking content), a stable code, and the exact file, line, column and field path. A passing pack gets the import
  report that registration would store, and its warnings.
- The report also compares the pack's version with the versions already registered: new pack, new version, identical
  (registering again changes nothing), or same version with different files (a new version is needed). It warns when
  the version is lower than the highest registered one.
- Each declared dependency is reported as available, legacy without stored files, registered with different files, or
  missing. An available dependency also shows whether its own dependencies load.
- The registered versions of a pack can be listed in version order. For each version the list gives its sealed state,
  whether this engine provides its ruleset, and how many campaigns use it.
- Limits: there is no page or route for these reports yet. Each run shows only the first blocking problem. Campaign
  preview and playtest tools are not part of this change.

## Unreleased — Adaptive quest and world direction recorded as disabled (P15.6-A), 21 November 2026

- Adaptive quest and world direction stays switched off. The comparison of player agency, story coherence and cost that
  would justify it was not run. The engine has no world-direction or plan-review task, and optional quest planning stays
  off by default.
- Play is unchanged. The world keeps moving through its fixed rules alone: NPC routines and delayed witness reports,
  relationship changes that each record their cause, and faction clocks that catch up after long waits a bounded number
  of steps at a time.
- New tests check that the engine can schedule no direction or review work, and that these world rules queue no
  planning work while they run.
- Limits: there is still no goal selection for important off-screen characters; their authored goals are read by no rule.

## Unreleased — Module absence and adventure independence checks (P15.5-A), 21 November 2026

- A new installation now takes the bundled sample adventure's title and genre from the adventure's own file, instead of
  from names written into the engine. The sample is therefore listed with the genre "travel" (its first content theme)
  instead of "fantasy". Adventures already in a library keep their labels.
- New tests show that an adventure without the creative-abilities module shows no draft or recipe in any campaign read or
  command result, and that the combat choices only offer what the server lists. A further test checks that the engine's
  code and resources name no identifier or title of the bundled or test adventures.
- Limits: the reads were checked through the engine's services, not over HTTP. The guide module still has no switch, so
  its absence is not tested.

## Unreleased — Using creative drafts in fights, learned recipes and high-power profiles (P15.4-B), 21 November 2026

- An adventure can now enable the optional `creativeAbilities` module and declare one creative profile: how large a player's
  draft may be, which effects it may use, and what the engine charges for it. Without the module the profile is refused
  with a clear error.
- In a fight, a player can now use a draft with the new `COMBAT_USE_DRAFT` command. This is the only way a draft takes
  effect. The engine checks the draft again at that moment against the profile and the fight's current state, charges the
  action points, Focus and cooldown it works out itself, and resolves the draft like any other ability. The result lists
  what was charged.
- After a draft, the next draft waits its own cooldown, counted from the last one, so switching drafts does not avoid it.
- Profiles are `standard` by default. Only a profile marked `power: high` may allow very complex drafts, charge nothing
  or apply effects that last until removed. Even then every draft stays within the engine's limits and costs at least
  one action point.
- An invalid draft, a missing prerequisite, a wrong target, too little Focus, the cooldown, or a fight whose adventure has
  no profile each refuse the command, and nothing changes.
- Narration describes a draft as "a drafted ability".
- The candidate ability's reserved identity is now `#draft` instead of `draft`, so it can never equal an adventure's
  ability ID.
- Outside a fight, a player can now save a draft as a learned recipe with the new `LEARN_DRAFT` command, under a recipe
  name of their choice. The engine checks the draft again against the adventure's profile and stores it with the costs it
  worked out, the profile and content it was checked against, and the event that announced it. Learning spends nothing,
  and saving the same name again adds the next version; older versions are kept unchanged.
- When a fight starts, the latest version of each recipe is checked again and becomes one of the player's abilities in
  that fight, named `#recipe:<name>`, with its own cooldown. A recipe that no longer fits the profile, or whose
  prerequisite is no longer learned, is left out of that fight.
- A player can hold at most 8 recipes and 99 versions of one. Learning during a fight, a draft outside the profile, a
  missing prerequisite or an adventure without a profile is refused, and nothing changes.
- Narration says "learns a recipe" and "uses a learned recipe", without the player's own recipe name or label.
- Database migration V35 adds the `learned_recipe` table, and the backup tool now covers schema 35.
- Limits: drafts and recipes work only in fights. No screen shows a preview, sends a draft or learns one, and a recipe
  cannot be forgotten or renamed. Drafts cannot be written from a description yet. The cost formula, the tier limits and
  the recipe limit are provisional.

## Unreleased — Creative ability drafts and preview (P15.4-A), 21 November 2026

- The engine can now check a player's draft of a new combat ability: a name, whom it takes, how far it reaches and up to a
  few steps (damage, healing, applying or removing an effect the adventure defines). Every problem is reported with a fixed
  code and the part of the draft it concerns, for example too many targets, too many dice, an unknown effect or an effect
  that lasts too long.
- A valid draft is shown as the ability it would become, with its cost worked out by the engine, never by the draft: action
  points, Focus and a cooldown that grow with the draft's complexity. The preview also lists how long each effect lasts and
  what stops the player from using it now, such as too little Focus or a missing prerequisite.
- A preview never uses the draft: it spends nothing, moves nobody, records no event and teaches no ability. Using or
  learning a draft will need its own explicit player action, checked again at that moment.
- Limits: this is engine logic only. No adventure can define a creative profile yet, no screen or command shows a preview,
  and drafts cannot be used, learned or written from a description yet.

## Unreleased — Guide persona answers (P15.3-A), 21 November 2026

- The engine can now ask a guide persona for advice on a player's question. The guide runs in its own conversation,
  separate from the narrator, from other personas and from non-player characters, and it works only from the facts and
  actions its caller gives it.
- An answer is text, the facts it relies on and the identities of actions the player could take. It has no way to spend
  resources, move the player, complete a quest or execute a draft. A recommended action must be one that was offered, and
  the player still submits it as usual. An answer that cites nothing must say it is unsure, and an answer that repeats a
  protected secret or a number not in its sources is rejected.
- Limits: this is the engine contract only. No adventure can define a guide yet, no screen or command asks one, and
  there is no switch to enable or disable the module. Rephrased secrets and wording that claims an action was taken
  cannot be detected automatically.

## Unreleased — Faction clocks and time catch-up (P15.2-B), 21 November 2026

- A faction can now declare up to four clocks, each filling from 0 to 1..12 segments. A clock fills as in-world time
  passes (one segment every 15 minutes to one week, counted from the campaign's start or from the clock's first
  advance), when a named quest of the same adventure ends (optionally only with a given outcome), or when another clock
  completes. The pack checker reports out-of-range values, a clock nothing can advance, a clock that names itself, two
  causes that would count the same fact and unknown quests, outcomes or clocks, at the field.
- Quest conditions can read a clock with `{"clock": {"faction": "<faction>", "id": "<clock>"}}`, so a quest can open,
  progress or end because a faction's plan moved on. A quest sees a clock change from the command after it.
- Every change is stored once, with what caused it (the time boundary, the quest ending and its event, or the other
  clock's completion), the time it was due and the values before and after. A full clock never changes again. After a
  long wait or rest, at most 32 changes are recorded per command. The rest are recorded, oldest first, by the next
  commands, so nothing is skipped or reordered. Looking at the game or real time passing never moves a clock.
  Adventures without clocks behave exactly as before.
- Database migration V34 adds the `faction_clock_change` table, and the backup tool now covers schema 34.
- Limits: no screen shows clocks yet. Clocks only fill, never reset or repeat, and change nothing directly but quest
  conditions. Clock causes cannot name quests or factions of other packs.

## Unreleased — Quest reputation (P15.2-A), 21 November 2026

- A quest outcome can now change the player's standing with factions: its `settlement` may list up to eight
  `reputation` entries `{faction, delta}`, with a non-zero delta from -25 to 25. The pack checker reports a zero or
  out-of-range delta, a repeated faction and a reference that is not a faction at the entry.
- Quest conditions can read the standing with `{"reputation": "<faction>"}`, a number from -100 to 100. A quest can
  therefore open, progress or end because of how a faction regards the player. An ending counts at once, so it can
  open a faction's quest in the same command.
- The standing is computed from the quests that have ended, so it needs no new storage, and an ending never counts
  twice. Existing adventures and campaigns behave exactly as before.
- Limits: only quest endings change reputation. No screen shows it yet. Faction clocks and time catch-up are still to
  come.

## Unreleased — Relationship causes and companions (P15.1-B), 21 November 2026

- Adventures can enable the first optional module, `relationships`. Its NPCs may then declare bounded attitudes such
  as trust, the typed events that change them (being spoken to, a gift, a trade, a promise kept or broken), and a
  threshold from which the NPC joins the player as a companion. A pack that does not enable the module cannot declare
  these rules: the pack checker reports `MODULE_DISABLED` at the field instead of ignoring it.
- During play, each change is stored once with the event that caused it and the value before and after. A change that
  cannot move the value (already at its limit) stores nothing. A companion arrives with the player when the player
  moves, and its daily schedule pauses while it travels with the player. Looking at the game changes nothing.
  Adventures without the module behave exactly as before.
- Database migration V33 adds the `relationship_change` table, and the backup tool now covers schema 33.
- Limits: new campaigns do not yet create NPCs from the adventure's definitions. No screen shows these values yet. No
  player command makes promises yet. Companions do not fight alongside the player.

## Unreleased — NPC schedules, witnesses and reports (P15.1-A), 21 November 2026

- Adventure authors can give an NPC a daily `schedule` of places by time of day. They can also mark it as a `witness`
  that remembers where it saw the player, and declare `reports` that pass what it saw to other NPCs a set number of
  in-world minutes later. The pack checker refuses badly ordered schedules and schedules that do not start the NPC at
  its own location. It also refuses reports from an NPC that is not a witness, and reports to the NPC itself.
- During play these rules follow only committed world time. A command that moves the clock moves scheduled NPCs, a
  witness beside the player notes where it saw the player, and a report arrives once enough in-world time has passed.
  Looking at the game, or real time passing, changes nothing.
- Limits: new campaigns do not yet create NPCs from the adventure's definitions, so packaged play has no NPC these
  rules can move. NPC goals are still plain text, and witnesses record only where the player was, not what they saw
  the player do.

## Unreleased — Setup steps and MVP capability table (P14.5-A), 21 November 2026

- The README now opens with numbered setup steps for a packaged installation and a table of what works, what is only
  partly proven and what is open. It states that the first-milestone exit is not met and that narration during play
  needs the owner's sign-in. No behavior changed. The check, start, status and stop steps were run on a fresh export of
  the working tree (not a git clone) and passed; Linux, macOS and a cold first build were not tried.

## Unreleased — Baseline latency, context use and open defects (P14.4-A), 21 November 2026

- A routine one-event narration request was measured at about 2,800 estimated tokens, under a quarter of the standard
  12,000-token input budget. A new test fails when such a request grows past half of the budget.
- Two short live checks of the narration runtime on the owner's account took 5.7 s and 5.2 s each, from container
  start to a resumed thread. Narration latency during play was not measured, because the packaged installation has no
  runtime sign-in.
- The planner comparison stays deferred: planning is off by default and every quest plays from its authored or
  procedural baseline. The unresolved defects are listed in the roadmap.

## Unreleased — First-milestone QA matrix rerun (P14.3-A), 21 November 2026

- The checks required for the first playable milestone were run again, and every result is recorded in the roadmap:
  904 backend tests, 234 interface tests, two backup-restore rehearsals and the runtime probe suite. A packaged start,
  stop and restart through `./start.sh` kept the saved playthroughs. An English and Italian fight on the Lantern Ford
  sample in Chrome passed all 80 checks. One short live check of the narration runtime on the owner's account passed
  and resumed its thread.
- No behavior changed. The milestone is not yet met. Still open: progression applied after a fight, author-fixed
  characters and facts, live narration during play, the opt-in Docker lifecycle checks, and the human accessibility,
  publication and Italian reviews.

## Unreleased — Backup restore checked in the middle of a fight (P14.2-B), 21 November 2026

- A new automated rehearsal backs up the Lantern Ford sample with the shipped backup tool in the middle of a fight. At
  that point a roll had been cut off before it was saved. It restores the backup into a new, empty database and starts
  the engine on it. Everything comes back: the campaigns, the fight, the adventure files with their hashes, and an image
  asset. The interrupted roll changes nothing, and asking again uses the same dice, so the fight goes on.
- In the same rehearsal, a used item and a quest reward were each repeated after a lost reply, once before the backup
  and once after the restore. The item was used up only once, and the reward was paid only once.
- No behavior changed. Limits: the restored fight had not yet started its first round. No narration runtime, browser
  or `./start.sh` restore was involved, and the item and reward come from a test-only variant of the sample, which
  has neither.

## Unreleased — A whole fight checked in a real browser (P12.5-A), 21 November 2026

- Fixed: after a speech in a fight was accepted, the chat could show the words you had just said again, ready to be
  sent a second time. The page reloads the fight after each action, and that reload could rebuild the chat before it
  had seen that the words were said. Your sent words are now cleared from the device copy as soon as the server accepts
  them. Words you were still editing, and your choice of recipient, are kept.
- A complete fight on the Lantern Ford sample was played in headless Chrome against a fresh packaged installation. It
  used the keyboard at 1280 px and touch at 320 px. The fight was started, initiative rolled and a speech sent, with
  switching between chat and tactics. The browser session was renewed, the engine was stopped and started again in
  the middle of the fight, and the connection was lost and came back. The raider's truce was accepted, and settling
  the fight returned to exploration.
- In that run, both drafts survived every step, and nothing was sent unless you confirmed it. When the reply to a
  speech, the acceptance or the settlement was lost, repeating the action played it only once.
- Limits: no narration runtime account was signed in, so narration and the renewal of a narration session inside a
  fight were not checked. There was no screen reader, no Italian run and no real phone.

## Unreleased — Recovery checked on the sample adventure (P14.2-A), 21 November 2026

- An automated test now checks, on the bundled Lantern Ford sample, that stopping the engine and starting it again does
  not lose or repeat anything. If a roll is cut off before it is saved, it changes nothing, and asking again uses the
  same dice. If an action is saved but its narration is cut off, it keeps its effects, and its narration is written
  once after the restart. The narrator then resumes at your current choice without replaying the opening scene. A
  lost reply is answered again without acting twice.
- No behavior changed. Limits: the narrator in the test is scripted rather than a live model, the restart happens
  inside one test process rather than through `./start.sh`, and backup and restore were not part of this check.

## Unreleased — A second genre, played in English and Italian (P14.1-B), 21 November 2026

- A cyberpunk fixture adventure, Neon Alley (Italian: "Vicolo al neon"), now runs on the same engine build as the
  fantasy sample, with its own places, attributes, resource, skills, items, quest and encounter. It is a test fixture
  and is not installed at first start: the packaged installation still provisions only Lantern Ford.
- Its Italian overlay covers the opening text, the names the character sheet and map panels show, and the encounter's
  zones, opponent, objectives, offer and terms. An Italian playthrough therefore reads Italian with no fallback notice
  for that text, unlike the English-only fantasy sample.
- An automated test plays the same path in both languages: create a playthrough, enter the fight, roll initiative, then
  either pay a toll (an agreement) or climb to the rooftop (an escape), settle, and return to exploration. A lost reply
  to the acceptance or the settlement is retried without writing twice.
- Limits: only the cyberpunk fixture has Italian adventure text. The bundled fantasy sample stays English, with the
  fallback notice in an Italian playthrough. The cyberpunk slice was not run in a browser, and the combat panel's own
  encounter strings were not checked in Italian. The Italian overlay has had one author pass and no independent review.

## Unreleased — A negotiated ending in the sample adventure (P14.1-D), 21 November 2026

- The raider at the ford in Lantern Ford can now be talked to. The fight offers "Offer a fair crossing": the raider
  answers with a shared crossing (a truce at no cost). Nothing ends until you accept those terms explicitly, and words
  such as "I accept" change nothing. Accepting ends the fight with everyone standing, and settling it returns you to
  exploration at the camp. You can still fight or slip away instead.
- A lost reply to the acceptance or to the settlement can be retried with the same request: it returns the same result
  and does not pay, settle or write twice.
- Only a fresh installation gets this. An installation that already holds the sample keeps the version it has; a newer
  sample is not delivered to an existing workspace. The new texts are English only, like the rest of the sample.
- Covered by an automated test of the provisioned sample, from the engagement to the settlement. Not run live in a
  browser.

## Unreleased — A fight you can play from the browser (P14.1-A.2), 20 November 2026

- After you start a fight, the combat panel now offers "Roll initiative" (Italian: "Tira l’iniziativa"). Before this, a
  fight started from the page stopped at "Before the first round" and could not go on. The roll is sent only after you
  review it and press "Confirm and send". Then you can move, attack, end your turn or slip away, and settle the verdict
  to return to exploration.
- The fight's conversation log no longer breaks an accessibility rule (the log role sat on a list). The screen looks the
  same.
- In Chrome against a packaged installation, one playthrough was created from the Lantern Ford screens and played in
  English, with the language changed to Italian and back in the middle of the fight. A second was played in Italian from the
  first screen. Both fights ended and returned to exploration. The draft, its selection, the actions list and the update
  position survived each reload.
- The sample's own text is English only, so an Italian playthrough shows it in English with a visible notice. Narration
  was not available in these runs, so each action stayed "saved; narration is pending".

## Unreleased — Create a playthrough from the browser (P14.1-C), 20 November 2026

- A saved adventure now has a "Create a new playthrough" link. The new screen lists the adventure's installed content
  with its title, summary, authors and licence, and asks for a playthrough title and a character name. Nothing is
  saved until you press "Create playthrough". The new playthrough then opens in the live campaign view.
- Your character starts with the adventure's default attributes and skills. Choosing an archetype or spending points
  at creation is not offered yet. The story is narrated in the interface language when the adventure provides it, and
  otherwise in the adventure's own language.
- If the connection drops while creating, press "Create playthrough" again without changing anything. The same request
  is repeated, and no second playthrough is created. Changing the choices starts a new request.
- `GET /api/v1/packs` lists the installed content of your adventures with public author metadata only, behind the same
  local admission as the other saved reads. Import diagnostics are not part of it yet.
- Creating a playthrough this way has passed automated tests, including one from the bundled Lantern Ford sample. No live
  packaged start in a browser has been recorded yet.

## Unreleased — A first adventure ready at the first start (P14.1-A.1), 20 November 2026

- A packaged installation started with `./start.sh` now sets itself up. The first start creates your owner identity in
  the private file `.local/owner-id`, and the engine creates you and a sample adventure, "Lantern Ford". Saved adventures
  no longer answer "owner not configured".
- Lantern Ford is a short neutral English adventure: a camp beside a river ford, a raider waiting at the crossing (a fight
  you can enter from the start) and a far bank to reach.
- Restarting creates nothing new: still one owner, one workspace and one sample. If you already have adventures, no sample
  is added, and a newer image never replaces or adds to the sample you have.
- Keep `.local/owner-id` with your backups. If the database belongs to an owner other than the one in that file, the engine
  refuses to start until the original file is restored, so it never hides your adventures behind a new empty owner.
- Starting with `docker compose up` alone, without the launcher, still configures no owner. The sample is English only.
  Owner creation, idempotent restarts and entering the sample's fight have passed automated tests, but no live packaged
  start in a browser has been recorded yet.

## Unreleased — Advancement choices previewed and compared before you commit (P13.5-A), 20 November 2026

- The engine can now show what a level-up choice would do before you make it. For each skill rank, attribute increase or
  ability you could buy, it lists the cost, the exact change to your character, or the reason it is refused.
- It can also line up two plans of several purchases side by side. Each plan is applied in order, stops at the first choice
  that breaks a rule, and the comparison shows how the two resulting characters differ and how many points each leaves.
- A preview never changes your character: your skills, attributes, abilities and pending points stay as they were until a
  purchase is committed on its own.
- Nothing changes in play yet: no screen shows these previews, and no database migration, route or page changed.

## Unreleased — Levels, training points and purchases counted exactly (P13.4-B), 20 November 2026

- The engine can now turn experience into levels. Experience adds up, and you reach level 2 at 100, level 3 at 300, level 4
  at 600 and level 5 at 1,000. There is no top level unless an adventure sets one.
- Every level you gain gives one training point, and every even level also gives one attribute point. Gaining several levels
  at once gives each of them exactly once. Getting the same experience again gives nothing more.
- A level raises your maximum Health by 2 and your maximum Focus by 1, but it does not heal you or restore Focus.
- Your points wait until you spend them. Nothing is chosen for you. You can buy the next rank of a skill (it costs as many
  training points as the new rank, up to rank 5 and at most one above your level), add one to an attribute (up to 6) or
  learn an ability whose requirements you meet (one training point). A purchase that breaks a rule spends nothing and
  changes nothing.
- Nothing changes in play yet: no experience is added after a fight or a quest, no screen offers these purchases, and no
  database migration, route or page changed.

## Unreleased — Fight and quest rewards paid once, and loot collected explicitly (P13.4-A), 20 November 2026

- The engine can now work out the rewards a finished fight or quest owes, so that each challenge pays its experience and
  reward items only once. This holds whether you win the fight, make the opponents surrender, reach the fight's goal, agree a
  truce, or finish a quest about the same challenge. Fighting the same opponents again, a second fight that counts as the
  same challenge, re-reading the journal or retrying a request pays nothing more.
- Losing or surrendering earns nothing, and the challenge stays open for a later win. A quest can still pay its own separate
  reward on top of the fight's.
- What a defeated opponent carried, worn items included, is left to collect. Nobody owns it until someone picks it up, and
  each item can be picked up only once. Opponents that are still fighting, fled or surrendered keep their belongings, so an
  item a living opponent still wears never ends up in your pack. Nothing is left to collect when you lose the fight.
- Nothing changes in play yet: adventures do not declare these rewards or what opponents carry, and finishing a fight or a
  quest does not use these rules yet. No database migration, route or page changed.

## Unreleased — Planned fights frozen once and refused when unwinnable or unfair (P13.3-A), 20 November 2026

- A fight the engine plans is now fixed once, exactly as it would start, and only that fixed version can start. If the party
  or the offered fights change before it starts, it is planned again instead of being quietly rebuilt.
- A planned fight is refused when one of its goals can never be met: a place no path reaches (also when opponents fill the
  only doorway, since a fallen opponent still blocks it), an escape route that is closed, or a "survive" goal longer than the
  fight's round limit.
- It is also refused when an opponent secretly counters you: when you must defeat opponents but your weapons and abilities can
  never hurt one of them or never reach it, or when one can take away all your remaining turns. A way to make an opponent
  surrender counts as a way to defeat it.
- When the proposed fight is refused, the engine uses the first fight the author wrote for the place that fits the
  difficulty and passes these checks, unchanged. If none does, no fight starts.
- Nothing changes in play yet: no fight is planned this way, and fights you start yourself are not checked. No database
  migration, route or page changed.

## Unreleased — Starting an offered fight from the page (P13.2-B), 20 November 2026

- Outside a fight, the playthrough page has a "Look for fights you can start here" button. It lists only the authored fights
  that the game would let you start at your current place right now. A fight elsewhere, or one you could not start (during
  another fight, when defeated or while the playthrough is paused), is never listed or hinted at.
- Each listed fight shows only what you could see on entering it: who is there, which side they are on and where they stand,
  where you start, the ways out and what the fight is about, plus whether it may open with your attack. It never shows an
  opponent's statistics, strength, attacks or the place's layout. Names are shown as the content's identifiers.
- Choosing "Start the fight", or "Start the fight … by attacking …", only opens a review; the fight starts only when you
  confirm. If the reply is lost, confirming again cannot start the fight twice. A refusal says that nothing changed.
- Nothing is read or sent by itself: the list is read only when you ask, and it is cleared as soon as the playthrough moves on.
- Known gap: after the fight starts, initiative cannot yet be rolled from the page.
- The combat projection gains an `offers` list (empty during a fight). No database migration or new route; 13 new messages
  with Italian translations.

## Unreleased — Bounded encounter proposals with one repair and a safe fallback (P13.2-A), 14 November 2026

- New `encounter` task contract for the narration runtime: given the party's capabilities, the chosen difficulty and the
  encounters the author wrote for a place, it can propose which of an encounter's opponents take part and where each starts.
  It cannot invent opponents, change their statistics, remove allies or objectives, or start an opponent at an escape point.
- A proposal is refused when it names someone who is not an opponent of that encounter, repeats one, overcrowds a zone, has
  too many opponents, or is harder or easier than the chosen difficulty allows. It gets one correction attempt.
- When the proposal is still unusable, the runtime is unavailable or interrupted, or the party changed in the meantime, the
  engine uses the first authored encounter whose own opponents fit the difficulty, unchanged. If none fits, no fight starts:
  the engine never invents one to fill the gap.
- The request runs in its own private planning conversation, separate from narration, and its output is never shown.
- Nothing changes in play yet: no fight is planned this way, and fights you start from the story are unchanged. No database
  migration, route or page changed.

## Unreleased — Capability snapshot and threat limits for encounter planning (P13.1-A), 14 November 2026

- The engine can now record a fingerprint of what the party can do before a planned fight: each member's level, Health,
  resources, worn equipment, conditions, learned abilities, skills and ammunition. From it the engine derives how much
  opposition the party can face at the chosen difficulty (gentle, standard, hard or severe).
- A proposed group of opponents is refused when it costs more or less than that difficulty allows, or has too many bodies
  (more than the party size plus two). It is also refused when it is malformed: no opponent, a duplicate or reserved name,
  or a level outside 1–20. Every reason is reported at once.
- Members who are defeated, absent, excluded or at zero Health do not count, and the same facts always give the same answer.
- Nothing changes in play yet: no fight is planned or checked this way, and fights you start from the story are unchanged.
  No database migration, route or page changed.

## Unreleased — Settling a decided fight and keeping the combat chat draft (P12.5-B), 14 November 2026

- When a fight is decided, the combat panel lists one action, "Settle the fight". Choosing it opens a review that says settling
  confirms the verdict, ends the fight and returns you to the story, and cannot be taken back; only "Confirm and send" sends it.
  If the reply is lost, confirming again repeats the same request instead of settling twice. Nothing settles by itself.
- The recipient and words you write in the combat chat are now kept on this device as you type, so they are still there after a
  reload or a reconnect. They are emptied only after the game accepted them, or when you clear them; a refused or lost
  send keeps them. Another fight never shows them.
- Starting a fight from the page is not connected: the page does not yet know which encounters you can start.
- New Italian messages bring the reviewed catalog to 701. No backend, database migration or route changed.

## Unreleased — Knowledge-scoped combat replies (P12.4-B), 14 November 2026

- New `combat-reply` task contract for the narration runtime: an opponent or companion you spoke to, or whose terms were just
  resolved, can be asked for a short in-character reply. A reply is at most two short lines (its words and a visible reaction,
  300 characters each and 450 together), must cite what you said and every resolved result, and may only use numbers already
  in what it was given.
- The request holds only what that participant knows: its own zone and standing, the participants it can see, the words it
  heard and the public results. A reply that names someone hidden from it or another participant's campaign identity is
  rejected, as is one naming any other secret listed for it (such as your private attack or items), and a request holding
  such a name is never sent.
- Each participant answers in its own runtime conversation, separate from the narrator, the planner, other participants and
  its own tactic choices.
- A reply cannot change anything: it has no field to accept, refuse or alter terms, pay, move or end the fight. When it is
  invalid after the one correction attempt, declined, unavailable, interrupted or late, simply no reply is shown, and the
  saved fight, the open offer and your available actions stay exactly as they were.
- Nothing changes in play yet: no turn asks for a reply, so none is shown. No database migration, route or page changed.

## Unreleased — Standard tactic when a tactic choice cannot be used (P12.4-A), 14 November 2026

- When the choice for an opponent or companion cannot be used, its turn is played by the standard tactic instead, so the
  fight keeps moving. That covers an answer that stays invalid after the one correction attempt, no answer to use, a choice
  the saved fight no longer allows, no runtime being available, a timeout, and an interruption.
- Each such turn carries a short public notice in the campaign language (English or Italian): the tactic choice could not
  be used, assistance is unavailable, or assistance was interrupted. The notice names no participant, action or reason, so
  it reveals no hidden plan.
- Nothing changes in play yet: no turn asks for a choice, so no notice is shown. This is the decision and the notice that a
  later task connects. No database migration, route or page changed.

## Unreleased — Knowledge-scoped tactic choices for opponents and companions (P12.3-A), 14 November 2026

- New `tactic` task contract for the narration runtime: an opponent or companion whose turn it is can be asked to pick one of
  its legal choices (move one zone, strike a target in reach, take the Guard action or end its turn). The request holds only
  what that participant knows: its own Health, Focus and action points, plus the zone, side, standing and stance of each
  participant it can see. Anyone hidden in an obscured zone, other participants' hidden values, the player's items and
  ammunition and the encounter's plans are left out. If a secret listed for that participant somehow appears in its context,
  nothing is sent.
- Each participant gets its own runtime conversation, separate from the narrator, the planner and the other participants.
- An answer naming a choice, target or zone that was not offered, or one that adds an extra action, is rejected. The runtime
  gets one correction attempt, and a choice is accepted only if it is still on the list rebuilt from the saved fight.
- Nothing changes in play yet: opponents still follow the fixed tactic, and the choice will be connected, with its fallback,
  in later tasks. No database migration, route or page changed.

## Unreleased — A persistent combat conversation (P12.2-B), 14 November 2026

- During a fight the page now has a "Conversation" section under the combat panel. It lists what has been said and answered
  in this fight, each line labelled Said, Attempt, Reply, Decision or Result, so the speaker's role never depends on color.
- You can choose who you are speaking to (nobody is chosen for you), write words of up to 500 characters and use a separate
  "Review and send". The review shows your exact words, that speech spends no action points and how many speeches remain.
  Nothing is sent until you press "Confirm and send". Outside your turn you can keep writing, but review is disabled with the
  reason and nothing is queued for later.
- Up to three suggestions for the chosen participant only fill your words; a draft you wrote is replaced only after you press
  "Replace my words". A refused message keeps your words and says "Nothing changed".
- Social actions for the chosen participant and scene actions open the same cost review as other actions. An offer on the table
  can be accepted or declined from the chat: accepting says that it binds you and cannot be taken back, and words alone never
  accept anything.
- 44 new reviewed messages in English and Italian (698 in the catalog). Not yet: speech input, a separate mobile switch,
  generated replies, older conversation paging, keeping the draft across a reload, and settling a decided fight.

## Unreleased — Named combat actions with a cost review (P12.2-A), 14 November 2026

- During your turn in a fight the combat panel now has a "Send an action" section with a button for each move, attack,
  ability, social or scene action, guarded stance and "End your turn" that the game currently allows. Unavailable ones stay
  listed with their reason and cannot be chosen.
- Choosing an action only opens a review: its name and target, the action points and Focus it would spend and what remains, a
  declared check or terms, what ending the turn forfeits, and that a sent action cannot be undone. Nothing is sent until you press
  "Confirm and send"; "Do not send" closes the review. A review disappears when the fight moves on.
- Out of turn, unavailable or unaffordable actions are refused on the page without a request, and a refusal by the game says
  "Nothing changed". Repeating an action after a lost reply cannot play it twice.
- 19 new reviewed messages in English and Italian (654 in the catalog); the panel's footer sentence changed. Not offered yet:
  items, readying, assessing, withdrawing, speech, reactions, offers, settling a verdict and the action chat. Typing free
  text is still not sent.

## Unreleased — A combat panel in English and Italian (P12.1-B), 14 November 2026

- During a fight the playthrough page now shows a read-only combat panel: whose turn it is with the round and the action
  points left, the initiative order, the zones with cover, obscuring and capacity, each passage with its state and crossing
  price, the escape zones, every participant's side, standing, zone and effects, the objectives with their status, your own
  Health, Focus, Guard, Resistance, armor, stance and reactions, what you can do now with each cost, target and the reason
  something is unavailable, and the verdict waiting for you to settle the fight.
- What you learned about another participant by assessing it is shown with the round and revision it was learned at, or as
  "not assessed yet". Nothing else about it is shown. The panel has no button: it cannot send anything.
- The resume card's "Encounter" line now says "In progress" during a fight instead of always "None".
- The panel is available in English and Italian (119 new reviewed messages, 635 in the catalog). Sections are native
  `details` elements, reachable and toggled by keyboard, and the layout reflows at 320 px.
- Outside a fight the page makes no extra request. The panel's code is a separate lazy chunk. Sending combat actions is still
  not connected (P12.2).

## Unreleased — A player-safe view of the fight for the combat panel (P12.1-A), 14 November 2026

- New read-only route `GET /api/v1/campaigns/{id}/encounter?adventureId=…` describes the active fight at one revision: the
  initiative order, whose activation it is with the points left, the zones with cover, sight and capacity, each passage with
  its current state and crossing price, every participant's side, zone, standing and active effects, the player's own Health,
  Focus, Guard, Resistance, armor, stance and reactions, the objectives with their status, the legal actions with their costs,
  targets and the reasons they are unavailable (exactly as a command result lists them), and the outcome waiting for the
  player's settlement. Outside a fight its `encounter` field is null.
- Another participant's Health and defenses appear only as a successful assessment published them, labelled with the round
  and revision they were learned at, since Health may have changed afterwards. Its attacks, reactions, reaction policies,
  stance, Focus and effect clocks never appear, nor a check's difficulty, a counteroffer not yet made or anything from an
  internal event. Reading it changes nothing.
- No migration and no change to receipts, events or commands. The interface does not show this view yet (P12.1).

## Unreleased — The resume card shows the fight in progress (P11.6-B), 14 November 2026

- The resume card (`GET /api/v1/campaigns/{id}/resume`) now fills its `encounter` field during a fight, so a client that
  reconnects can see where the fight stands instead of guessing or resending what it had typed. The field shows the encounter
  and its template, the phase and round, whose activation it is with the points left, whether the player may act now, the
  speech left in this activation, any offer waiting for an explicit `ANSWER_OFFER`, and, once the fight has ended, the outcome
  waiting for an explicit `SETTLE_ENCOUNTER`. It never shows another participant's Health, defenses, effect clocks or
  reaction policies, or a check's difficulty. Reading the card still changes nothing, and outside a fight the field is still null.
- Reconnecting during a negotiation or after an accepted agreement cannot repeat what was said or done. The server keeps no
  draft, so a reconnect has nothing to send. Retrying a lost response returns the original result. A request still held from
  before the reconnect is refused as out of date, or because the speech limit was reached. A negotiation interrupted before it
  was saved rolls the same dice when it is asked again, and the agreement and its settlement each happen once. This was
  already the behavior; it is now tested through restores, session renewals and reconnects.
- No migration and no change to receipts or events. The interface does not read the new field yet: its card still shows
  "None" for the encounter, during a fight too, as before (P12.1). See
  [ADR 0038](docs/adr/0038-combat-dialogue-recovery-and-the-resume-card-encounter.md).

## Unreleased — Checkpoints of a fight in progress (P11.6-A), 14 November 2026

- The operational checkpoint now records the fight in progress: its phase, round, whose activation it is, the action points
  left, the speech count, the world time at which the round in progress ends, any offer or truce, and every participant's
  initiative, reactions, local Health and Focus, Guard stance, effect clocks, ability uses and readied equipment. A fight
  changed outside a command is therefore detected at restart, and a renewed narrator session is prepared from a checkpoint
  that includes the fight. Nothing in the fight itself changes: renewals still write no game state, and a fight that ended
  inside a round still counts that round's seconds once when it is settled.
- Migration V32 replaces the snapshot function and adds no table. Checkpoints taken by earlier versions are replaced at the next
  start without marking any summary stale. Backups require schema 32, so an archive made by an earlier version is refused by this
  tool. See [ADR 0037](docs/adr/0037-the-operational-checkpoint-records-the-active-encounter.md) and
  [tools/database_backup/README.md](tools/database_backup/README.md).

## Unreleased — Settling a fight and returning to the story (P11.5-C.3), 13 November 2026

- A fight that has ended no longer leaves the campaign stuck in combat. Once the outcome is shown, the player confirms it with
  `SETTLE_ENCOUNTER` {encounterId}, and the campaign returns to exploration: waiting, travelling, resting and starting another
  fight work again. The outcome is the one the fight reached; the command cannot change it. Every ending settles this way:
  victory, defeat, escape, either side's surrender, an accepted agreement, a met objective, a stalemate and the round limit's escape.
- Settlement happens once. Retrying a lost response with the same request returns the same receipt and changes nothing, also
  for the answer that accepted an agreement just before; a new settlement request, or any combat command, is then refused.
- A fight that ended in the middle of a round counts that round's 8 seconds (by default) once, with no extra effect tick. Combat
  effects and Guard stances end with the fight. Nothing is refilled: a defeated character stays at minimum Health and must
  recover before fighting again. Nobody is moved, no exploration condition is added or removed, and no reward is given yet.
- The settlement has a public statement in English and Italian. No migration, and nothing changes for receipts written
  earlier. No frontend control sends the command yet. See
  [ADR 0036](docs/adr/0036-explicit-settlement-and-the-return-to-narrative.md) and
  [docs/11](docs/11-combat-system.md#14-settlement-and-return-to-narrative).

## Unreleased — Withdraw and the round cap in a fight (P11.5-C.2), 13 November 2026

- During their own activation the player can withdraw with `WITHDRAW` {zoneId}: one edge toward an escape zone, for
  `withdrawCost` (2 by default) or the edge's Move price when that is higher. Nobody's "leaves the zone" reaction answers it, and
  the receipt lists the zones the player can withdraw to with their price. A destination that does not get closer to an escape
  zone, or a fight without one, is refused before anything is spent. Reaching an escape zone this way ends the fight as an escape
  exactly as walking there does.
- A fight that nothing has decided by the end of its last allowed round (30 by default) now ends there instead of waiting forever:
  as a stalemate, or as an escape where the encounter declares `"disengagement": "escape"`. The outcome says the round limit decided
  it, and nobody's Health, standing, position or effects change. Any other `disengagement` value is refused when the pack is
  compiled.
- Both have public statements in English and Italian. No migration, and nothing changes for receipts written earlier. No frontend
  control uses these commands yet, and a fight that has ended still waits for settlement. A fight stored at the end of its last
  round by an earlier version stays there. See [ADR 0035](docs/adr/0035-withdraw-and-the-round-cap.md) and
  [docs/11](docs/11-combat-system.md#13-terminal-conditions).

## Unreleased — Ready/equip and Assess in a fight (P11.5-C.1), 13 November 2026

- During their own activation the player can ready or stow one held item with `READY_ITEM` {itemInstanceId, change: `equip` or
  `unequip`} for `readyCost` (1 by default). It changes the real equipment under the same slot, tag, capacity and hand rules as
  outside a fight, never takes another item off for the player, and from then on the fight uses the new Guard, armor and attacks.
  A weapon whose hit applies an effect the fight did not prepare is refused.
- `ASSESS` {targetId} for `assessCost` (1 by default) looks at a standing participant in range and in sight and rolls
  `2d10 + insight` against its effective Resistance. A success shows its current and maximum Health, Guard, Resistance and armor;
  a failure shows nothing.
- Either action is refused before anything is spent when it is not legal or not affordable, and any `change` other than `equip`
  or `unequip` is an invalid command. Both have public statements in English and Italian. PostgreSQL stores the readied values
  (migration V31), and backups require schema 31. No frontend control uses these commands yet. See
  [ADR 0034](docs/adr/0034-ready-equip-and-assess-in-a-fight.md) and [docs/11](docs/11-combat-system.md#7-action-economy).

## Unreleased — Contention harness can drive its load through the engine's entry point (P21.4-F.2), 2 November 2026

- `tools/gpu_contention/contention.py --via-entry-point` sends its load through the owner-only
  `POST /api/v1/models/admission-probe` instead of to model servers, using the owner session file, and every output file
  now has a `load_path` field (`executor-entry-point` or `direct-to-model-servers`) and a `load_path=` summary line. The
  README states that load sent straight to a model server proves nothing about admission and documents a trusted-owner
  competitor started with `docker exec` in the packaged model server's container. The no-GPU self-test runs against a
  stand-in engine. No GPU was available, so no competitor run and no run against the real engine happened. See
  [tools/gpu_contention/README.md](tools/gpu_contention/README.md).

## Unreleased — Owner-only probe of the text and image admission paths (P21.4-F.1), 1 November 2026

- With `engine.inference.admission.enabled` on, the owner can call `POST /api/v1/models/admission-probe`. It sends one fixed,
  neutral text task through the scheduler and one fixed image job through the image admission, accepts no text from the caller
  and answers only a fixed `status` and `code` for each. With the switch off the route does not exist; anyone but the owner is
  refused. Waits are bounded and a second probe during one is refused. The text task is recorded in the scheduler's ledger
  under the first campaign. This is the first caller of the image admission. The tests use stand-ins: no real scheduler, model
  server, image worker or GPU ran. See
  [docs/34](docs/34-inference-backends-and-model-selection.md#p214-f1-owner-only-probe-of-the-text-and-image-paths).

## Unreleased — A chat request no longer loads a model beside a batch (P21.4-E.3), 1 November 2026

- The packaged model server now starts with `--no-models-autoload`, so a chat request loads nothing unless it asks. The engine
  asks only with `engine.inference.admission.enabled` off, where a local turn still loads its model as before. With the switch
  on, only a lease loads, and a task whose model is not loaded ends `UNAVAILABLE` with `model-not-loaded`, without a turn, and
  keeps its session. `model_probe.py` and `tools/gpu_contention` now need their model loaded first with `POST /models/load`.
  The pinned b11960 image was shown to refuse such a request and to start no load, using a file that is not a model. No real
  weights, batch or engine took part. See
  [docs/34](docs/34-inference-backends-and-model-selection.md#p214-e3-no-model-loads-from-a-chat-request-beside-a-batch).

## Unreleased — Image batches wait while the player reads (P21.4-E.2), 1 November 2026

- With `engine.inference.admission.enabled` on, an image batch no longer starts within a reading window after a player's text
  turn on a local model ends, so the next turn finds its model loaded. The new setting
  `engine.inference.admission.reading-window` sets its length (0 to 10 minutes, default 1 minute, 0 turns it off); an invalid
  value stops startup without being echoed. A turn ended by yielding, background work and a turn on another backend do not open
  it. A batch already running goes on. An image whose wait ends inside the window falls back to catalog art with
  `image-admission-deferred`. The default is a placeholder, tested with a stepped clock and scripted servers only: no real
  player, card, server or engine run took part. See
  [docs/34](docs/34-inference-backends-and-model-selection.md#p214-e2-reading-window-as-expected-text-demand).

## Unreleased — Per-model sizes and reference-host settings for admission (P21.4-E.1), 1 November 2026

- With `engine.inference.admission.enabled` on, each of the five measured catalog text models is now admitted with its own size
  from the recorded host measurements (the GPU memory its load added and its container RAM peak), not the whole budget. Any
  other model, including every image checkpoint, still runs alone (it takes the whole budget unless `text-gpu-demand-mib` or
  `image-gpu-demand-mib` says otherwise) and must now also leave the new `engine.inference.admission.unmeasured-extra-headroom-mib`
  (default 1,024) free on top of `headroom-mib` before it loads. A measured model larger than the owner's budget ends its task
  `UNAVAILABLE` (`residency-unavailable`). The `text-*` and `image-*` demand settings now size only models without a record.
- [docs/34](docs/34-inference-backends-and-model-selection.md#p214-e1-per-model-sizes-and-reference-host-settings) gives one
  settings combination for the reference host and the arithmetic showing the default text model's cold load is admitted under
  it. The sizes are arithmetic on one sample each, tested against scripted servers and readings only: no real card, server or
  engine run took part.

## Unreleased — Packaged image worker behind the lease executor (P21.4-D), 1 November 2026

- New setting `engine.inference.image-worker.url`. With it and `engine.inference.admission.enabled` on, an image batch can
  run on the packaged worker: a control loads and unloads its checkpoint through `/v1/load` and `/v1/unload` and confirms
  the result from the worker's own answer, and an image port submits, waits for and cancels a job through `/v1/generate` and
  `/v1/cancel`. With either setting off, no bean is added and behaviour is unchanged. An `a1111-http` server still gets no
  image control, because it cannot release its checkpoint. Tested against a double of the worker only: no real worker,
  checkpoint or GPU has answered, no Compose file sets the address and nothing submits an image yet. See
  [docs/34](docs/34-inference-backends-and-model-selection.md#p214-d-packaged-image-worker-behind-the-lease-executor).

## Unreleased — GPU contention harness (P21.4-C.1.3.2), 1 November 2026

- New `tools/gpu_contention/contention.py`: one command drives text, image and speech load against the owner's endpoints
  beside an owner-supplied GPU competitor and writes peak GPU memory (`nvidia-smi` sampling), out-of-memory events (server,
  engine and container logs, counts only) and the lease executor's metrics (from `GET /api/v1/models/admission-metrics`) to a
  new JSON file. Anything it cannot observe, such as a missing image or speech endpoint, route or tool, is recorded as
  `unavailable` with a reason, never as zero. The owner's session value is read from a file, sent only to a loopback URL and
  never written. A no-GPU self-test with stand-in servers runs in CI. No run on the reference host or any GPU has been made.
  See [tools/gpu_contention](tools/gpu_contention/README.md) and
  [docs/34](docs/34-inference-backends-and-model-selection.md#10-shared-admission-and-speed).

## Unreleased — Lease executor metrics route (P21.4-C.1.3.1), 1 November 2026

- With `engine.inference.admission.enabled` on, the owner can read the lease executor's counters at
  `GET /api/v1/models/admission-metrics` as sorted JSON of fixed names and numbers. With the switch off the route does not
  exist. A request without the owner is refused with 403 `LOCAL_ADMISSION_DENIED`. Tested without a real HTTP request or
  GPU. See [docs/34](docs/34-inference-backends-and-model-selection.md#10-shared-admission-and-speed).

## Unreleased — Image requests through the lease executor (P21.4-C.1.2.2.2), 1 November 2026

- With `engine.inference.admission.enabled` on, a new `ImageAdmission` path runs an image request on a lease, as a stepped job
  through the lease executor. A running job can be cancelled or interrupted between steps, and the backend is told to stop
  before the model is unloaded. When the application stops, a running job is stopped the same way within a bounded wait and
  ends `image-shutdown`. With the switch off, the adapter is called directly.
- A job that cannot run ends with a fixed `image-admission-*` failure code, which the caller answers with catalog art. Each
  job's ticket says how long it may wait for its model.
- New optional settings `engine.inference.admission.image-gpu-demand-mib` and `image-ram-demand-mib` give the size any image
  model is taken to need. Both default to the whole budget.
- Nothing visible changes yet: no code calls this path and no image control is registered, so a batch in the application would
  end `LOAD_FAILED`. Tested with a recording port and no GPU only. See
  [docs/34](docs/34-inference-backends-and-model-selection.md#p214-c1222-image-requests-through-the-lease-executor).

## Unreleased — Scheduler text permit linked to the lease executor (P21.4-C.1.2.2.1), 1 November 2026

- With `engine.inference.admission.enabled` on, a task on a local slot that holds the scheduler's permit now loads its model
  through the lease executor before its turn and releases it afterwards. A task on another backend is unaffected. The switch
  now really loads and unloads models. With it off, or with no executor, the scheduler behaves as before.
- A task whose model cannot be made resident ends `UNAVAILABLE` with `residency-contention`, `residency-load-failed`,
  `residency-lost` or `residency-unavailable`, without a turn or a charge. Waiting for a model can be cancelled, preempted or
  timed out at once. A turn whose lease is lost or taken back ends `CANCELLED` with `preempted`.
- New optional settings `engine.inference.admission.text-gpu-demand-mib` and `text-ram-demand-mib` give the size any text model
  is taken to need. Both default to the whole budget.
- Two fixed placeholder values changed, neither measured as a setting: the lease lifetime is now 5 minutes (was 2), and a load
  or unload is checked 240 times (was 30), because the slowest recorded load took 164 s.
- Tested without a GPU or a model server only. The default headroom may refuse every cold load on the reference host, and no
  working combination of settings has been tried. See
  [docs/34](docs/34-inference-backends-and-model-selection.md#p214-c1221-link-from-the-schedulers-text-permit-to-the-lease-executor).

## Unreleased — Opt-in construction of the lease executor (P21.4-C.1.2.1), 1 November 2026

- A new setting, `engine.inference.admission.enabled` (off by default), makes the application build the admission coordinator
  and the lease executor over the local model server's control. It needs `engine.inference.local.url` and
  `engine.inference.admission.gpu-budget-mib`; optional are `ram-budget-mib`, `headroom-mib` (default 1024) and `patience`
  (default 2 minutes). An invalid value stops startup with a message that does not repeat the value.
- With the switch off nothing is built and behaviour is unchanged. With it on, this change alone called nothing on the executor, so
  no model was loaded or unloaded; the scheduler link above adds the caller. The fixed lease and swap values are unmeasured
  placeholders, and the wiring was tested without a GPU.

## Unreleased — Host GPU memory reading (P21.4-C.1.1), 1 November 2026

- A reading of free GPU memory from `nvidia-smi` now exists for the admission coordinator. It answers unknown when the tool is
  missing, fails, times out or prints something unexpected, and uses the first GPU on a multi-GPU host.
- It is not wired into the application yet and was tested only with a stand-in command, not on a real GPU.

## Unreleased — Narration probes pass on the default and alternate local models (P21.2-G), 1 November 2026

- The repaired model probe was re-run on the reference host: `gemma-4-12b-it-qat-q4_0` and `Qwen3.5-9B-Q4_K_M` each exited 0
  with 8 of 8 probes passed (English and Italian). A pass means the reply matched its schema, not that narration is good.
- The other three catalog models were not re-run. The server was started by hand, not with `./start.sh --with-models`, and
  no engine ran against it.
- The local text backend entry (P21.2) is closed on these two models; a real `--with-models` run with the engine is a
  follow-up.

## Unreleased — Launcher selection of the local model server (P21.2-F), 1 November 2026

- `./start.sh --with-models` starts the engine as usual and also the optional local model server, and gives the engine its
  address (`engine.inference.local.url`, through the new `compose.models.yaml`). A plain `./start.sh` is unchanged: it neither
  pulls nor starts the server, and the engine has no local backend.
- The model documentation now states that a text slot is assigned the model server's own ID, which is the catalog file name
  without `.gguf` and differs from the catalog ID for four of the five text models.
- The readiness probe of a slot now fails with the code `model-not-listed` when the model server does not list the assigned
  model, instead of reporting it ready. The Models panel shows its generic reason text for this code for now.
- The engine and the model server were not run together in this change, and the launcher was tested with a Docker stand-in.

## Unreleased — Narration probes independent of the thinking default (P21.2-E), 1 November 2026

- The model probe tool now turns thinking off in each request, so a model that thinks by default no longer spends the whole
  reply budget before answering.
- A reply cut off at the token cap with no content now fails with a message saying so, instead of "reply is not valid JSON".
- No real model was probed in this change; a host re-run is still needed before any probe pass is claimed.

## Unreleased — Local text backend measurements (P21.2-D), 1 November 2026

- The documentation now records the first measurements of the local text backend on the reference host: load time, prefill and
  decode speed, peak VRAM and RAM, default context and VRAM released by unload, for the default model, the alternate and three
  more catalog models, each measured once.
- The readiness probes were run against all five models and none passed (0 of 8 each), because the thinking mode used the whole
  reply budget. The harness repair is still to do, and the model catalog and advisor are unchanged.
- No code changed. Router model IDs differ from catalog IDs for four of the five models, which is documented and not yet handled.

## Unreleased — Negotiation, surrender and agreement in a fight (P11.5-B), 1 November 2026

- An encounter can now declare a check for a social action and, for a negotiation, the terms it proposes and a counteroffer. An
  attempt pays its declared cost and draws its declared check once, with recorded dice. The opponent's response follows from those
  dice and the pack's data alone: it refuses, makes a counteroffer or is open to the proposed terms.
- A response never ends a fight or binds the player, and neither do words. Terms take effect only through the new `ANSWER_OFFER`
  command, which names the offer and says `accept` or `decline`; any other decision is refused. An offer lapses when the turn ends or
  when the player does anything else but speak, so nobody can strike first and accept afterwards.
- Accepted terms can be a truce, which ends the fight as an agreement with everyone standing, the addressed opponent's surrender, or
  the player's own surrender, which ends the fight and fails its objectives. When no opponent is left and any of them surrendered, the
  fight ends as their surrender and no longer reads as a victory. A surrendered participant keeps its Health, takes no turn, cannot be
  attacked and can still be spoken to.
- A retry after a lost response replays the same receipt, with no second draw, cost or effect. Refusals write nothing. The new refusal
  `COMMAND_OFFER_UNAVAILABLE` has reviewed English and Italian wording (the Italian still needs independent human review), and the record
  gains public statements in both languages. PostgreSQL stores the offer and the truce (migration V30), and backups require schema 30.
- A fight that has ended still cannot be resumed, because settlement does not exist yet. The stalemate policy, Withdraw and moving items
  through terms are not included. A check on an action other than a negotiation records its result and changes nothing else, and no
  opponent proposes terms by itself. No interface control uses any of this yet.

## Unreleased — A fight stops when it is won, lost or left (P11.5-A), 1 November 2026

- A fight now ends where it is decided: when the last opponent falls, when the player falls, when the player reaches an escape
  zone, or when a reach-zone, survive or protect objective is met. It no longer runs on to the round cap or waits at a round end.
- Only committed game state decides. Speech and social attempts cannot win, lose or end a fight.
- The record gains one public event in English and Italian, and the command receipt gains the outcome with each objective's status.
- A fight that has ended cannot be resumed yet: settlement does not exist, so the campaign stays in encounter mode and refuses every
  command after the outcome. Surrender, negotiated ends, the stalemate policy and Withdraw are not included.
- A round left unfinished adds no world time for now.

## Unreleased — Fixture evidence for the inference backends and licence records (P21.6-A), 31 October 2026

- [Inference backends and model selection](docs/34-inference-backends-and-model-selection.md) now maps each clause of the backend,
  slot and advisor acceptance check (QA-43) and the shared-admission acceptance check (QA-44) to the tests that cover it, and states
  which clauses are shown only on scripted fixtures and which are not proven: no model was loaded, no image was generated, the
  executor's yield is not connected to the image worker, and the asset rules and text-only fallback belong to later work.
- `THIRD_PARTY_NOTICES.md` records the terms of the text and image models, the model-server image, the image worker packages and an
  owner-run image server. Nothing is bundled; the licences of the llama.cpp image and the image worker packages are listed as not yet read.
- No behavior changed, and no reference-host measurement, bilingual evaluation or supported-defaults table exists yet.

## Unreleased — Image-generation port and `a1111-http` adapter (P21.5-B.2), 31 October 2026

- The engine now has one backend-neutral image-generation port: a request with a prompt, size, steps, guidance and seed (no game
  state), a cancel, and a result that is either a validated PNG with provenance, a cancellation, or a fixed failure code. P17.6 builds
  the runtime image jobs on it.
- An adapter for an owner-run Forge or A1111 server implements the port through the gateway. It runs the engine's own jobs one at a
  time, reports a server that is busy with someone else's job instead of queuing behind it, and cancels through the server's interrupt
  route only while its own job is running. A returned image must be strict base64 and a well-formed PNG of the requested size, and
  its text chunks, which can hold the prompt, are removed.
- The gateway gained the optional destination protocol `a1111-http`: only the txt2img, interrupt and progress routes with their own
  methods are reachable, with image-sized size and time bounds and no redirect by default. The server's settings, reload and shutdown
  routes are refused.
- Nothing selects the adapter yet, so players see no change. The tests use scripted fixtures: no real Forge or A1111 server was run.

## Unreleased — Packaged local image worker (P21.5-A), 31 October 2026

- The project now defines an optional local image worker, selected with `--profile images`: a confined container with no route out and
  no published port, reading the owner's model directory read-only. It loads single-file SDXL safetensors checkpoints and LoRAs, with
  WAI-illustrious-SDXL v17.0 as the recommended default and Pony Diffusion V6 XL and SDXL base 1.0 as alternatives.
- Prompts beyond CLIP's 77 tokens are split into chunks, with no token lost, and a prompt needing more than 8 chunks is refused rather
  than cut. Safe-content tags are added to every job. Only the owner's `IMAGE_CONTENT_POLICY=owner-private` setting changes them, never a request.
- `./start.sh --stop` also stops this service, and the engine joins one more empty internal network. A plain start builds and starts nothing new.
- Nothing calls the worker yet, so players see no change. Its image has not been built: no checkpoint has been loaded, no image
  generated, and memory and speed are unmeasured.

## Unreleased — Image worker cancellation, output checks and provenance (P21.5-B.1), 31 October 2026

- The local image worker can now cancel a running job: `POST /v1/cancel` stops it at the next denoising step, answers the job's own
  request with `cancelled` and no image, and frees the worker and its cached memory for the next job. A client that merely disconnects
  does not cancel.
- An image is returned only after it passes a check: a well-formed PNG within the size limits whose dimensions equal the request.
  Anything else answers `invalid-output` and is dropped.
- Every result carries a provenance record: checkpoint, VAE and LoRA names and SHA-256, seed, steps, sampler, prompt hashes (never the
  prompt), the output hash, and the worker and library versions.
- Nothing calls the worker yet, so players see no change. The tests use stand-ins: no real model, GPU or library was run.

## Unreleased — Lease executor over the model server (P21.4-B), 31 October 2026

- The engine has an executor that can turn the coordinator's leases into real residency: it saves the text slot's prompt cache, unloads
  the text model, confirms the memory came free, runs an image or speech batch, unloads that model, reloads the text model and restores
  the cache. A failure at any step leaves the text model usable or retried, and capacity stays held until a model is confirmed gone,
  including after a lease expires. A swapped-out text model comes back on its own only if it fits beside what is resident.
- A request that waits or was put back can be withdrawn and is then never granted again. A running batch yields between steps when interactive text needs the room. Low free GPU memory delays a batch and then falls back to
  text-only play or catalog art; no other process is ever stopped. Counters and gauges are available in the process, as fixed names
  and numbers only.
- Nothing in the running application used it when this entry was written, so players saw no change: it was tested against scripted
  servers only, with no real model server, worker or GPU reading, and no out-of-memory protection is claimed. The opt-in scheduler
  link above now uses it.

## Unreleased — GPU and RAM admission coordinator core (P21.4-A), 31 October 2026

- The engine has a coordinator that decides which local text, image or speech model may be resident next within a fixed GPU and RAM
  budget: interactive work first, background work ageing but never overtaking it, equal work rotating across modalities and
  campaigns, compatible requests sharing one resident model, and background leases named for taking back when interactive work waits.
- Leases expire unless renewed and are recorded in the new `resource_lease` table (migration V29). After a restart the leases of the
  earlier process stay reserved until a caller confirms their model is gone. The backup tool now expects schema version 29 and the
  new table.
- Nothing called the coordinator when this entry was written: by itself it loads and unloads no model, interrupts no image run,
  measures nothing and is not tied to the text scheduler or the adapters. No out-of-memory protection is claimed.

## Unreleased — Settings → Models panel (P21.3-D), 31 October 2026

- Settings now has a Models section for the installation owner. For each of the four slots (light, standard and deliberate text, and
  images) it shows where the assignment comes from, the backend, model and effort, the billing and privacy class with what each means,
  the configured fallbacks and the last readiness check, and lets the owner change the assignment, edit up to four same-class
  fallbacks and run a readiness check.
- A change that moves a slot to another class is held until the owner acknowledges it; the panel names both classes and says who
  would receive the prompts and who bills. Cancelling stores nothing. The panel shows names only: it has no field for a key or an
  address.
- Every control is a native form field or button, so the panel works by keyboard alone. The panel and its 82 new strings exist in
  English and Italian; the Italian wording has had one author pass and no independent review.
- Most backends the panel offers have no adapter yet, so checking them answers that no adapter is installed. The initial interface
  bundle is now 515 kB, above its 500 kB warning limit. The backend routes are those of P21.3-A; one test was added for refused
  writes.

## Unreleased — Gateway budgets for metered destinations (P21.3-C.2), 31 October 2026

- A destination in the gateway's allowlist can now have a `budget` with a daily and a monthly cap. A reached cap answers 429 with
  `budget-exhausted-daily` or `budget-exhausted-monthly` and a `Retry-After`, contacts nothing, and never moves the call to another
  destination. Destinations without a budget behave as before.
- A cap counts relayed calls per UTC day and UTC calendar month, not tokens or images. A call that fails after being sent still
  counts, because the gateway cannot tell what the provider billed.
- Counts survive restarts and `./start.sh --stop` in the new `gateway_state` volume, which holds no key. A damaged count file stops
  metered destinations with 503 `budget-unavailable` and is not reset; the owner must remove it, and no launcher command does that yet.
- Each call's time budget is the existing `timeout_seconds`. Tested against a scripted upstream only. Nothing in the engine calls the
  gateway yet, so nothing reads these answers.

## Unreleased — Gateway container for hosted APIs and custom endpoints (P21.3-C.1), 31 October 2026

- Add the optional `gateway` service behind `--profile gateway`. It is the only service that holds API keys and the only one
  with this route out: the engine reaches it over a private network, and a plain start neither builds nor runs it.
- It contacts only destinations in the owner's allowlist file (`.local/gateway/destinations.json`) and reads keys from read-only
  files (`.local/secrets/gateway/`). Keys stay out of images, logs, responses and the engine.
- It bounds request and response sizes, time, requests in flight and redirects, refuses redirects to another host, and neither
  stores nor forwards cookies.
- `./start.sh --stop` now also stops a selected gateway and removes its networks.
- The engine has a new internal network that stays empty unless the gateway is selected. Nothing calls the gateway yet, no
  launcher command sets a key, and budgets do not exist. It has been tested against a scripted upstream only, never a real
  provider.

## Unreleased — Local text backend adapter and per-slot session retirement (P21.3-B), 31 October 2026

- Add the `local` text backend, which talks to the packaged model server over its HTTP chat API. `engine.inference.local.url`
  turns it on; without that setting nothing changes. Every request names its model and parameters explicitly, and the output
  goes through the same schema, disclosure and text checks as any other backend.
- With the local backend on, each task goes to the backend assigned to its slot. A slot whose backend is not installed ends
  with a clear error and never moves to another backend.
- Saving a changed slot assignment now retires only that slot's AI sessions, so its next task starts a fresh conversation and
  other slots keep theirs. Saving the same assignment again retires nothing.
- Migration V28 lets session records store this retirement reason. The backup tool now expects schema version 28.
- Tested against a scripted HTTP server only: no real model has answered, and Compose and the launcher do not select the
  backend yet. A readiness probe of a `local` slot can now answer `ready` when the server lists its models, which does not show
  that a model loads or answers.

## Unreleased — Model slots with class badges and readiness probes (P21.3-A), 31 October 2026

- Add owner-only backend routes for the four model slots (`text.light`, `text.standard`, `text.deliberate`, `image`):
  `GET /api/v1/models/status`, `PUT /api/v1/settings/models` and `POST /api/v1/models/probe`. Assignments are stored and
  survive a restart; a stored text assignment overrides the startup setting of the same slot.
- Every assignment shows a billing and privacy class (`local`, `subscription` or `metered`). Changing a slot's class is refused
  until the new class is acknowledged, and the acknowledgement is recorded.
- Fallbacks apply only when listed, and only inside the class of the primary assignment; when nothing of that class is available
  the slot stays unavailable instead of moving to another class.
- A readiness probe records `ready`, `failed` or `unreachable` with a reason code and shows it in the status.
- Backend only: the Settings → Models panel and the gateway are not built, only the Codex backend can probe `ready`, and no
  model has been run through these routes.

## Unreleased — Readiness probes and bilingual narration probe set (P21.2-C), 31 October 2026

- Add `tools/container_packaging/model_probe.py`, a developer tool that checks a local text server's `/health` and sends eight
  JSON-Schema probes, four tasks in English and Italian, printing a `PASS` or `FAIL` line for each. It exits non-zero on any failure.
- Define the model measurement record format (load time, prefill and decode speed, peak VRAM and RAM, context limit, VRAM released by
  unload) with a validator and a sample that carries no values.
- Tested against a scripted fixture server only. No real model has been probed, no measurement is recorded, and no engine
  feature runs the probes yet.

## Unreleased — Hash-verified model fetch with license confirmation (P21.2-B), 31 October 2026

- Add `./start.sh --models fetch [--accept-license ID[,ID...]] ID...`. It prints each named catalog model's license, source and
  files, asks for the exact answer `yes` on a terminal, and only then starts a download container. There is no default model.
- `--accept-license` applies a recorded owner decision without a prompt. It must name each accepted model ID, among those
  requested; a wildcard, an empty list or another ID is refused. Without a terminal and without that option, nothing is downloaded.
- Files are installed in `MODELS_DIR` (default `./.local/models`) only after every requested file matches the catalog's exact
  size and SHA-256. Any failure installs nothing from that command, leaves no unfinished file behind, and never replaces an existing
  different file.
- The first fetch pulls a small pinned helper image by digest. Download and hashing run in containers, so the host still needs
  only Bash, its basic utilities and Docker. No token is sent, so a source that asks for a sign-in fails with its HTTP status.
- This is tested on fixtures only. No real model has been downloaded with it, and no engine feature uses a fetched model yet.

## Unreleased — Pinned model server packaged in Compose (P21.2-A), 31 October 2026

- Add the optional Compose service `models`: the pinned llama.cpp CUDA image by digest, in router mode, as a non-root user with
  a read-only root filesystem, a read-only model directory (`MODELS_DIR`, default `./.local/models`) and no published port.
- It starts only with `docker compose --profile models up --detach models`. A plain `./start.sh` neither pulls nor starts it.
  Its only network is internal and shared with the engine alone, so it has no route to the Internet, PostgreSQL or the runtime.
- The engine joins the new internal network, so its container is recreated once at the next start. Volumes are kept.
  `./start.sh --stop` now also stops a selected model server.
- No model can be fetched or used yet: the fetch command, probes, measurements and the engine adapter are still to come.

## Unreleased — Italian and screen-reader review kit prepared (P05.6-C), 31 October 2026

- Add `docs/36-italian-and-screen-reader-review-kit.md`: all 433 Italian strings with their English source and context, a
  screen-reader script for the English and Italian variants, and empty tables for the reviewer's evidence.
- Fix two Italian errors found on a first read by the author line (not an independent review): an overdue promise, obligation
  or agreement no longer reads "Scaduta" (feminine only) but "Scadenza superata", and the audio-cue texts use the same
  typographic apostrophe as the rest of the interface. A new Vitest test pins both fixes (132 frontend tests).
- Axe (0 violations on 13 routes in each language) and accessibility-tree reports are recorded as tool evidence only. No screen
  reader was run and no independent Italian review has happened, so P05.6 stays open.

## Unreleased — Hardware profile and GPU check run on the reference host (P21.1-A), 31 October 2026

- Run `./start.sh --models profile --gpu-check` on the reference host. The driver tool reports the RTX 4070 with
  12,282 MiB, the WSL 2 VM has 7,823 MiB, and the pinned llama.cpp CUDA image sees the device (`visible`, 12,281 MiB
  total, 11,090 MiB free). The sanitized profile is recorded in the roadmap.
- No product behavior changed. This run completes the P21.1 exit checks; loading and measuring a model belongs to P21.2.

## Unreleased — Browser drafts, cursors and switching proven separate for two same-name adventures (P06.5-B.2), 31 October 2026

- Add Vitest cases with two adventures that share their title, playthrough title, NPC and item names. A draft typed in one
  adventure never appears in the other, stream cursors and saved actions stay under their own adventure, switching aborts the
  pending reads, and a late reply from the old adventure cannot change the other adventure's screen or draft.
- Players see no change. A saved playthrough was already reachable at `/saved/<adventureId>?playthrough=<campaignId>`; no
  route, backend change or submit path was added, and Send stays disabled.
- QA-33 stays open for late media events, open tabs and playing a saved campaign. Generated-image isolation is P17.6 scope.

## Unreleased — Two same-name genres proven separate across restart and real restore (P06.5-B.1), 30 October 2026

- Add a backend test with two equal-title adventures whose NPCs and items share names. Commands, reads, memory, job rows,
  owner-wide limits and authored folders stay separate, also after a restart in a separate process.
- Add a rehearsal that runs the shipped backup and restore tool on that world and proves every scope again from a new
  database and an empty folder root. It needs Docker and runs in the packaging suite, including hosted CI's packaging job.
- Players see no change, and QA-33 stays open: no browser took part, and generated-image isolation is P17.6 scope.

## Unreleased — Presentation cues checked in a real browser (P05.7-B.2.2), 30 October 2026

- A spoken-line cue no longer runs its words together ("asideNo audio for this line."), and the audio button now stands
  clear of the text beside it, so its focus ring covers nothing.
- The QA-40 points now have recorded evidence, including a headless Chrome check of duplicate and reconnect delivery,
  text-only reading, Tab, Enter and Space on the audio buttons, and reduced motion, run once in English and once in Italian.
- That check used the real packaged interface with a scripted API and stream. No real turn produces cues and the audio
  route is not served, so players still see no cues. Persistent visual and voice identities remain P17 work.

## Unreleased — Playthrough screen shows presentation cues as text with opt-in line audio (P05.7-B.2.1), 30 October 2026

- A playthrough now lists the scene, pose and expression, and spoken-line cues the server announces in a "Scene and voices"
  section, in order and without repeats. Nothing is added to the screen when there are none, and the written result and the
  starting situation are the same with or without them.
- A spoken line that has an audio asset gets a "Play line audio" button. Audio never starts by itself, one line plays at a
  time, the button works with the keyboard, and a failed load shows a short notice while the text stays.
- Cue fades are off when the browser asks for reduced motion or does not say. Italian text for the new labels is included.
- Players see little change yet: no turn produces cues and the audio route is not served, so the section stays empty.

## Unreleased — Narration can carry checked presentation cues (P05.7-B.1.2.3.2), 30 October 2026

- A narration result may now carry up to 16 presentation cues. They are checked against what the audience may see, stored with
  the narration and announced once on the public stream, however many times the job is retried.
- A list with a hidden character, an undeclared reference, a URL or executable content is dropped whole and logged by reason,
  and the narration is still published.
- Players see no difference yet: the narration publisher produces no cues, and the browser displays none.

## Unreleased — Audience presentation scope of a committed operation (P05.7-B.1.2.3.1), 30 October 2026

- The engine can now work out, for one committed turn, which scenes, lines, assets, actors, poses and expressions the audience
  may be shown. It reads the campaign's pinned pack and shows only characters the turn's public events mention and the pack
  declares. Hidden, unknown and undeclared characters are left out, and a pack that declares nothing gives an empty list.
- Players see no difference yet: nothing calls this during play.

## Unreleased — Audience presentation scope builder (P05.7-B.1.2.2), 30 October 2026

- The engine can now build the list of scenes, lines, assets, actors, poses and expressions one audience may be shown, from the
  actors that audience can see and the pack's declared references. A hidden or undeclared actor is simply absent.
- Players see no difference yet: nothing calls the builder during play.

## Unreleased — Packs can declare public presentation references (P05.7-B.1.2.1), 30 October 2026

- An adventure pack may now list the scenes, lines and assets it can show and the poses and expressions each of its NPCs may
  take, in one optional `presentation` file named by the manifest. The declaration compiles into the pack and the campaign
  snapshot.
- A pack that declares a URL, markup, an over-long reference, more than 256 entries in a list, or a backdrop or audio asset
  it did not list is refused when it is imported, with a diagnostic that points at the field and does not repeat the value.
- Packs without the declaration compile exactly as before. Players see no difference yet: nothing uses the declaration
  during play. The [pack format](docs/09-adventure-packs.md#33-presentation-declaration) describes the file.

## Unreleased — Durable presentation events on the public stream (P05.7-B.1.1), 30 October 2026

- The engine can now store admitted scene, pose and line-audio events (migration V26) and deliver them on the campaign
  stream as `presentation.event` messages with only their allowlisted fields. A rejected draft is neither stored nor
  delivered, and its whole batch stops.
- Each event is announced once. A repeated insert adds nothing, and skip, reconnect, replay and resets follow the stream's
  existing cursor rules, with a `deliveryId` that never changes.
- Players see no difference yet: no turn produces these events, and the browser validates the new frames without displaying
  them. Backups now include the new table and require schema 26.

## Unreleased — Saved playthrough fixes from the packaged browser journey (P05.6-A), 30 October 2026

- The playthrough list now separates a title from its turn and pending badge, which ran together on screen.
- The Actions list keeps its oldest-first order after a reload; a re-read receipt used to move an action to the end.
- Chrome ran the English-Italian-English journey on the packaged interface with recorded server replies. A packaged
  installation still has no owner setting or pack import, so the same journey against its live API has not run; the
  [roadmap entry](docs/16-development-roadmap.md) records the results and open findings.

## Unreleased — Packaging recreation check without a skipped backend test (P02.3-A), 30 October 2026

- The packaging verification image now contains the one probe fixture the backend's drift check compares, and that check
  fails rather than skips when the file is missing. The zero-skip rule of the recreation check is unchanged, and the final
  engine image still receives only the jar.
- A timing race in a scheduler test double could fail the backend suite at random; it now waits for a submission to finish.
- Eight deliberate weakenings on exported copies fail as intended, including skips added elsewhere in the backend suite, and
  the containers check passes on the final code. An interrupted weakening run had left the retained fixture database in a
  recovery that outlasted the check's wait; it was repaired outside the check, which is unchanged. The
  [roadmap entry](docs/16-development-roadmap.md#5-p02--establish-repository-and-quality-infrastructure) records the results.

## Unreleased — Predeclared reactions, effect phases, durations and cooldowns (P11.4), 30 October 2026

- Everyone in a fight now braces by default: against a visible attack, one reaction adds +2 Guard for that attack. It shares
  its stacking key with the Guard action, so the two never add up, and a Brace that would not help is skipped and spends
  nothing. The player can turn it off or make it depend on Health or Focus with `SET_REACTION_POLICY`, for free, before
  the first round or on their own turn, never after seeing a roll. The worked fight of the documentation now plays exactly
  as written.
- Adventures can declare more reactions (a Guard bonus, absorption, a counter strike, applying an effect) on encounter
  participants and learned abilities. A reaction never provokes another one, and a move provokes a blow only from someone who
  declared that.
- Conditions can now matter in a fight through a closed `combat` block: modifiers to Guard, Resistance and attacks, shields,
  damage or healing at one of eight named phases, what they prevent, how they stack and how long they last. Attacks can apply
  them on a hit, and conditions the player already has come along.
- `COMBAT_USE_ABILITY` uses a learned ability that declares a combat form, with its action points, Focus, cooldown, targets
  and operations. A free ability always has an explicit limit, and nothing can make one available again early.
- New refusals `COMMAND_REACTION_UNAVAILABLE` and `COMMAND_ACTION_PREVENTED` have reviewed English and Italian wording (the
  Italian still needs independent human review). Receipts list the player's abilities, reaction policies and reactions left,
  and the effects each participant holds. Other participants' Health, armor and shields stay out of the public record.
- PostgreSQL stores active effects, policies and ability uses (migration V25) and repeats their bounds. A fight already in
  progress keeps its behaviour: nobody reacts until the player declares a policy. The backup tool covers schema version 25.
- Not included yet: items that apply or remove effects, multi-target items, and forced movement; other participants using
  abilities (P12); converting remaining effects when a fight ends (P11.5) and recovery from the checkpoint (P11.6); a frontend
  control or panel (P12). Ready and Assess still have no combat form. Balance remains unproven. See
  [ADR 0031](docs/adr/0031-predeclared-reactions-effect-phases-and-cooldowns.md).
- Verified: 192 deliberate weakenings of these rules on exported copies led to new tests and no change in behaviour, and clocks,
  cooldowns and Guard with Brace now also run through real commits on PostgreSQL. Effects across context renewal and restore
  remain unproven until P11.6. The [roadmap entry](docs/16-development-roadmap.md#15-p11--deterministic-combat-foundation) has the results.

## Unreleased — Attacks, defense, damage and healing with recorded draws (P11.3), 30 October 2026

- Fights can now hurt. On the player's turn `STRIKE` makes one attack of a worn weapon against a participant within its
  reach: two ten-sided dice plus attribute and skill against the target's Guard or Resistance. Meeting the defense hits, a
  margin of six or more adds a strong-hit die, armor reduces the damage (penetration reduces armor) and Health stops at
  zero, which defeats a participant and never kills it. A miss still costs its points and ammunition.
- Every roll is recorded. The receipt lists the dice, a public event states the outcome, and an internal event keeps the
  complete calculation with its draw numbers; a retried request reuses the same dice. Another participant's defense, armor
  and Health stay out of the public record.
- `GUARD` takes a guarded stance for one point, once per turn: +2 Guard until that participant's next turn, together with
  partial cover up to the adventure's cap. Cover, line of sight and weapon range decide who can be attacked from where.
- `COMBAT_USE_ITEM` uses a held item for two points, on oneself or on an ally beside the player. A `heal` effect restores
  at most the missing Health and never revives; it now works outside fights too.
- Adventures can declare weapons, armor and Guard on equippable items and attacks on encounter participants. Worn items are
  read once, when the fight starts. A weapon can consume an item per attempt, as ammunition or charges.
- Other participants now act: armed ones walk toward the nearest opponent, strike and guard, by a small fixed rule until
  smarter tactics arrive. A declared opening strike now resolves once, at the slot its encounter allows, and costs the
  player's own points; if it cannot be made at that moment it lapses at no cost with a public reason.
- New refusals `COMMAND_ATTACK_UNAVAILABLE` and `COMMAND_GUARD_UNAVAILABLE` have reviewed English and Italian wording (the
  Italian still needs independent human review). Receipts show every participant's public standing, the operation's strikes
  and the attack, Guard and item options with their costs and the reason an attack cannot be used now.
- PostgreSQL stores the stance (migration V24) and refuses overhealing, standing at zero Health and returning from defeat.
  The backup tool covers schema version 24.
- Not included yet: Brace, condition modifiers, absorption and abilities in a fight (P11.4); escape, outcomes and settlement
  (P11.5), so a fight whose player fell waits at a round end and a won fight runs on; recovery of a fight from the checkpoint
  (P11.6); a frontend control or panel for encounters and smarter tactics (P12). Balance remains unproven. See
  [ADR 0030](docs/adr/0030-attacks-defense-damage-and-healing-with-recorded-draws.md).

## Unreleased — Initiative, rounds, activations and bounded combat dialogue (P11.2), 30 October 2026

- An entered encounter can now be played turn by turn. `ROLL_INITIATIVE` rolls two ten-sided dice for every
  participant once, adds their initiative modifiers and fixes the order (higher total first, then higher finesse,
  then participant identity); the dice appear in the receipt and a retried request reuses them. Rounds then run in
  that order: each active participant's turn grants the adventure's action points (3 by default), unused points
  expire when the turn ends, and every completed round adds its in-world seconds (8 by default) to the clock.
- On the player's turn the player can move to an adjacent zone (`MOVE_ZONE`, one point per edge, two across
  difficult terrain, never through a blocked edge or into a full zone), say one short thing to a participant in
  speaking range (`COMBAT_SPEAK`, at most 500 characters, free and limited to one utterance per turn by default),
  attempt one of the encounter's declared social or scene actions (`INTERACT`, for example demanding passage or
  unbarring a gate, at the declared cost) and end the turn explicitly (`END_ACTIVATION`). Points left at zero do
  not end the turn by themselves. Encounter templates gain optional `actions` for these social and scene actions.
- Nothing can be submitted or queued out of turn: other participants' turns run until the player's next decision.
  Refusals change nothing and answer new codes (`COMMAND_TURN_UNAVAILABLE`, `COMMAND_ACTION_POINTS_INSUFFICIENT`,
  `COMMAND_ZONE_UNAVAILABLE`, `COMMAND_SPEECH_LIMIT_REACHED`, `COMMAND_INTERACTION_UNAVAILABLE`) with reviewed English
  and Italian wording (the Italian still needs independent human review). Every tactical receipt shows the round,
  whose turn it is, the remaining points, the initiative order and the player's legal options.
- PostgreSQL stores the turn cursor, the speech count, passage changes, initiative and reaction budgets (migration
  V23) and refuses a re-roll, points granted in the middle of a turn, points beyond the adventure's allowance and
  extra speech. The backup tool covers schema version 23.
- A fight that has started can always roll initiative. An adventure can no longer map a combat role to an attribute
  whose range goes beyond the supported 0–6 (a located pack error), and `ENGAGE` refuses a character whose stored role
  value is outside that range or not a whole number (`COMMAND_ENCOUNTER_UNAVAILABLE`). Such a character could
  previously enter and then fail every roll, leaving the playthrough stuck in encounter mode.
- A command whose text contains a NUL character is now refused as invalid before anything is written, in exploration
  and combat alike, instead of ending as a failed operation.
- Not included yet: attacks and the opening strike (P11.3) — other participants therefore pass their turns —;
  reactions (P11.4); responses to social attempts, outcomes and settlement (P11.5); recovery of a fight from the
  checkpoint (P11.6); a frontend control or panel for encounters (P12). See
  [ADR 0029](docs/adr/0029-initiative-rounds-activations-and-bounded-combat-dialogue.md).

## Unreleased — Encounter lifecycle, combat profiles, zones and opening policy (P11.1), 29 October 2026

- Adventures can now define fights. A pack declares one combat profile (`ruleType: combat`) around the AI RPG
  Skirmish default: it maps the four ruleset roles to its own attributes, names its Health (and optional Focus)
  resource and may tune the provisional numbers (action points, round length, costs, caps, bonuses) inside
  documented bounds. The scheduler, the zone space, the dice and the reaction depth cannot be changed by a pack;
  naming another scheduler or space is a located pack error.
- Packs can define encounter templates (`encounters` files): the location where the fight can start, a small zone
  graph with cover, visibility, capacity and difficult or blocked edges, the player's starting zone and escape
  zones, allies and opponents with explicit statistics and a threat tier, objectives (defeat the opposition,
  escape, reach a zone, survive a number of rounds, protect an ally) and an opening policy. Import checks that the
  space is connected, that escape and objective zones can be reached, that zones are not overfilled and that there
  are at most two more opponents than the party has members.
- The new `ENGAGE` command enters an encounter at the player's location, optionally declaring the opening strike.
  Entry freezes the profile, zones, objectives, opening policy and every participant's statistics (the player's
  defenses come from the same character that explores, with their current Health, worn items and conditions),
  writes one public event and switches the playthrough to encounter mode. It takes no time, rolls no die and
  changes no Health: the opening strike is recorded once and resolves later at the slot the policy allows
  (the initiator's turn, or an opening slot before the first round). A refused entry changes nothing and answers
  `COMMAND_ENCOUNTER_UNAVAILABLE`, with reviewed English and Italian wording (the Italian still needs
  independent human review).
- PostgreSQL stores the frozen encounter and its roster (migration V22: `encounter`, `encounter_participant`):
  frozen facts never change, the lifecycle follows the combat state machine, an opening strike resolves at most
  once and a playthrough has at most one active encounter. The backup tool covers schema version 22.
- Not included yet: initiative, turns and every tactical action (P11.2), so an entered encounter cannot progress
  or end; attack resolution (P11.3); a frontend control or panel for encounters (P12); hidden participants and
  hazards; equipment Guard and armor values. See [ADR 0028](docs/adr/0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md).

## Unreleased — Reliable state changes in panels and journal (P10.5), 29 October 2026

- A playthrough now shows what its commits stored. The playthrough page reads one new snapshot,
  `GET /api/v1/campaigns/{id}/panels`, and displays the in-world clock, the character with attributes (the
  base value is shown beside an effective value that a condition changed), skills and derived statistics,
  resources, active conditions with their remaining rounds, learned abilities, the inventory with the slot of
  each worn item and the worn equipment, the discovered quests with their objectives and outcome, what the
  player knows (with how sure they are: knows, believes, rumor, suspects, doubts, common knowledge) and open
  promises, and an event history worded in the campaign's language.
- Every list comes from one committed revision and is refreshed when the world revision advances. Reading
  changes nothing: no row, no clock, no draw. A quest that is hidden background content stays out of the panels
  and the history until its ending; undiscovered quests, internal events, truth values and secret names are
  never sent. When pack names are unavailable the panels say so and show the stable identifiers with exact
  numbers. The history is paged newest first with a stable cursor and an "older events" control.
- Quests no longer come from the old free-text tables. Those tables keep their planner reader and have no panel
  reader; they are retired once the planner no longer needs them.
- Frontend: 57 new reviewed English and Italian messages (the Italian wording still needs independent human
  review), a strict parser for the new reply and an updated recorded reload transcript. No migration, command
  or table was added.
- Not included: encounter, shop, map, rumor and relationship panels, journal search and filters, names of
  dependency-pack definitions, quest and location names inside history lines (they use identifiers), item
  weight, capacity and durability. See [ADR 0027](docs/adr/0027-player-panels-and-journal-projection.md).

## Unreleased — Bounded world clock, travel duration and rest (P10.4), 29 October 2026

- World time now has rules. It is a counter of whole seconds that only a committed command moves: a journey
  adds the time its location link declares (`minutes`, default none), a wait adds the seconds asked for (now at
  most four hours), and the new `REST` command adds its rest policy's length. One command can add at most one
  in-world day and the clock stops at one hundred years. Looking at the game never moves it: state, resume
  card, stream and every other read leave the clock and every row alone, and so does real time passing.
- PostgreSQL enforces the same limits against any writer (migration V21, no new table): the clock is bounded
  on `campaign` and `campaign_event`, and it can change only in the update that also advances the state
  revision, by at most one day. The backup tool covers schema version 21.
- Packs can declare rest policies (`ruleType: rest`): duration, an optional location tag, an interruption
  chance, resources to restore (up to their maximum) and conditions to remove. The player only names the
  policy. One recorded percentile draw decides interruption; an interrupted rest passes half the time,
  restores half and removes nothing. Quests can wait for the clock with the `worldTime` atom, so a wait, a
  journey or a rest can finish a quest in the commit that moves the time.
- Two new refusal codes (`COMMAND_TIME_ILLEGAL`, `COMMAND_REST_UNAVAILABLE`) with reviewed English and Italian
  wording, four new events (`REST_COMPLETED`, `REST_INTERRUPTED`, `RESOURCE_RECOVERED`, `CONDITION_REMOVED`) and
  a `rest` summary in the player-safe receipt. A move event now carries its duration. A wait longer than four
  hours, which used to commit, is refused.
- Not included: any screen that shows the clock (added by P10.5), encounters that interrupt a rest (P11), condition ticks
  over world time, faction or event clocks and bounded catch-up (P15.2). See
  [ADR 0026](docs/adr/0026-bounded-world-clock-travel-duration-and-rest.md).

## Unreleased — Quest transitions, objective predicates and one-time rewards (P10.3), 29 October 2026

- Pack quests now run. After every command that can change a fact a quest predicate reads (movement,
  item commands, ability use and the new quest commands) the engine re-evaluates the pinned quests over
  committed state and writes the result in the same commit as the command: an undiscovered quest
  becomes available when its `availableWhen` holds, an active quest marks objectives satisfied
  (`visited`, `possesses`, quest status and objective predicates) and the first outcome that holds ends
  it. Nothing reads prose, dice or the clock.
- The player can accept an available quest and suspend and resume an active one
  (`ACCEPT_QUEST`, `SUSPEND_QUEST`, `RESUME_QUEST`). A suspended quest tracks nothing; an ended quest
  never changes again. A quest declared `secret` is hidden background content: it starts by itself and
  only its ending is announced.
- An ending quest settles its item rewards once, to the protagonist, under the source key
  `quest:<id>:outcome`. Three independent guards stop a second payment: ended quests are immutable and
  never re-evaluated, the reward settlement is unique per source and recipient, and the units are
  created through the shared creation ledger. One command can finish a chain of quests, because granted
  reward units count for later predicates in the same commit.
- Migration V20 adds `quest_state`, `quest_objective` and `location_visit`. Old saves gain empty tables
  and keep reading every quest at its pack-declared initial status. The backup tool covers schema
  version 20. The pack compiler now rejects an outcome that rewards more than 32 item units.
- One new refusal code (`COMMAND_QUEST_UNAVAILABLE`) with reviewed English and Italian wording, three
  command labels, seven quest events with bilingual narration statements, and a `quests` list in the
  player-safe receipt when a public transition happened.
- Not included: a journal or quest screen (P10.5), world-time and clock predicates (P10.4), predicates
  for facts, encounters and interactions, rumor-style discovery, abandoning a quest by command, XP and
  reputation rewards. See [ADR 0025](docs/adr/0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md).

## Unreleased — Atomic item commands and source-linked item creation (P10.2), 29 October 2026

- The player can now use, equip, take off, give and trade items through the ordinary command route
  (`USE_ITEM`, `EQUIP_ITEM`, `UNEQUIP_ITEM`, `TRANSFER_ITEM`, `TRADE_ITEMS`). Each is one atomic
  commit: item rows, resources, events, receipt and journal outcome succeed or fail together, a
  matching retry replays the receipt without repeating the effect, and a fault after the item rows
  were written rolls everything back. Unit conservation is proved before the commit and every
  update is a compare-and-set on owner, quantity and storage version.
- Moving a whole stack hands over the same item instance, so a unique item keeps its identity and
  artwork; other units merge into the receiver's open stacks. A worn item cannot be given, traded or
  used up until it is taken off. Equip and unequip reuse the P10.1 slot, tag, capacity and hand rules.
- Pack items may declare a trade `value` and a `use` contract (consumption, targets and effects in the
  ability grammar); `createItem` effects take an optional `quantity` and now execute, in items and in
  abilities. A trade is accepted by rule: every item needs a value and the counterparty must receive at
  least the value it gives. Existing packs and their hashes are unchanged.
- Migration V19 adds the append-only `item_creation` ledger. Every created unit is linked to its
  operation, the announcing `ITEM_CREATED` event and a source (an effect node, or a settled reward),
  and a replayed source cannot create twice. The database backup tool covers schema version 19.
- Four new refusal codes (`COMMAND_ITEM_UNAVAILABLE`, `COMMAND_EQUIPMENT_ILLEGAL`,
  `COMMAND_QUANTITY_ILLEGAL`, `COMMAND_TRADE_REFUSED`) with reviewed English and Italian messages and
  command labels; receipts list the player's own item lines; narration states the six new events in
  both languages.
- Not included: inventory and equipment panels and journal entries (P10.5), world-time costs (P10.4),
  quest rewards (P10.3), NPC stock, weight, capacity and durability. See
  [ADR 0024](docs/adr/0024-atomic-item-commands-and-source-linked-creation.md).

## Unreleased — Backend tests read a private local datasource file, 29 October 2026

- A checkout can keep its test database connection in `backend/config/application.properties`
  instead of exporting `SPRING_DATASOURCE_*`. Spring Boot already reads that location when Maven runs
  from `backend/`; exported variables still take precedence. The tests still require a real
  PostgreSQL database: there is no default URL, no H2 and no skipped test.
- `.gitignore` ignores `/backend/config/`. The `secrets` check rejects any file there that Git could
  stage and fails if the ignore rule is removed. Its tests cover the new private path, a tracked test
  resource with a similar name and an ignored file that contains a password.
- Why: `./mvnw --batch-mode verify` run without the datasource variables stopped with 303 test
  errors ("Failed to determine a suitable driver class"). The backend guide and ADR 0009 describe
  the file, its precedence and its limits.

## Unreleased — Maven Wrapper runs from a `noexec` home, 29 October 2026

- `./mvnw` now works when `~/.m2` is on a filesystem mounted `noexec`, such as a tmpfs home in a
  hardened container. The kernel refuses to execute the installed `bin/mvn` there even with mode
  0755, so a run stopped with `./mvnw: 163: exec: …/bin/mvn: Permission denied` (status 126). The
  wrapper now runs a launcher that cannot be executed directly through the interpreter on its `#!`
  line. Maven logs JLine native-library warnings on such a mount and falls back to its plain
  terminal; builds are unaffected. Setting `MAVEN_USER_HOME` to an executable location avoids them.
- The wrapper regression test has two more cases: a launcher whose execute bits were cleared, and an
  installation on a real `noexec` mount, which runs where the host has a writable one. Both fail
  against the previous script. The backend guide, ADR 0009 and the third-party notices describe
  the change.

## Unreleased — Maven Wrapper works without `unzip`, 29 October 2026

- `./mvnw` no longer needs `unzip`. The upstream wrapper fetched the `.tar.gz` archive when
  `unzip` was missing, and that archive can never match the pinned ZIP checksum. A first run then
  stopped with "Failed to validate Maven distribution SHA-256, your Maven distribution might be
  compromised", although nothing was compromised. The committed script is now a marked, modified
  copy of Maven Wrapper 3.3.4. It always downloads and verifies the pinned ZIP and, without `unzip`,
  extracts it with the JDK's `jar` tool. No setup or cache seeding is needed. The checksum, the
  Maven version and `mvnw.cmd` are unchanged.
- The wrapper now says what is missing when it cannot download or extract Maven (no `unzip` and
  no JDK, or no downloader at all), instead of reporting a checksum error for a file it never
  downloaded. A failed download names its URL.
- A new repository test runs the wrapper against a local fixture distribution, with and without
  `unzip`, including a checksum mismatch and a failed download. The backend guide, ADR 0009 and the
  third-party notices describe the change.

## Unreleased — Character rules: derived stats, conditions, rarity and equipment (P10.1), 29 October 2026

- Packs can declare **equipment slots** (capacity, accepted tags, the number of hands) and **derived
  statistics** (a formula over attributes and skills with declared bounds). Items name their slot,
  the hands they occupy and tags; conditions can stack up to a cap. Invalid combinations, such as an
  unknown slot, a stackable equippable item, a formula that reads dice, or a starting loadout that
  needs more hands than the character has, are rejected when the pack is checked, with the item and
  slot in the diagnostic.
- A new character service computes the **effective sheet** (attributes after conditions, skills,
  derived statistics) from saved state, applies and expires **conditions** by their stacking policy,
  records **learned abilities** only when their skill and ability prerequisites hold, and checks
  **equipment** by slot, tags, capacity and hands together, reporting every violated rule.
- A starting item can be marked `equipped`; nothing is ever equipped implicitly. Rarity is the tier
  the pack declares: artwork, color, price or description never change it, a slot or a bonus.
- Database migration V18 adds `actor_condition` and `equipment_assignment`; an equipped item cannot be
  transferred, a stack cannot be worn and the backup tool covers both tables (schema version 18).
- Not done yet: play commands that use, transfer and equip items, advancement of ranks and levels,
  condition ticks and rest, and the inventory, equipment and condition panels (P10.2–P10.5).

## Unreleased — Hardware profile, pinned model server and model catalog (P21.1 checkpoint), 29 October 2026

- New `./start.sh --models profile [--gpu-check]` writes `.local/hardware.json`, a profile of the
  machine for choosing local models: the NVIDIA GPU's name, total, used and free memory, driver and
  compute capability as the NVIDIA driver tool reports them (never the Windows adapter field, which
  shows about 4 GB on a 12 GB card), the CPU, the memory (the WSL 2 VM's under WSL, and the module
  layout on Windows), free disk space and the container engine's memory. `--gpu-check` also runs
  the pinned model-server image once with GPU access and no network, and records whether a CUDA
  device is visible inside a container. A missing tool is recorded, never fatal, and the profile
  contains no host, user, serial or address identifiers.
- The local text model server is pinned to `ghcr.io/ggml-org/llama.cpp:server-cuda-b11960` by
  digest (CUDA 12.8.1). The repository image check now also covers images named by the launcher.
- A bundled model catalog lists Gemma 4 26B-A4B, 12B, E4B and E2B, Qwen3.5-9B,
  WAI-illustrious-SDXL v17.0, SDXL base 1.0 and Pony Diffusion V6 XL with source revision, exact
  file sizes and SHA-256 digests (all nine files downloaded and re-hashed on 28 October 2026),
  license class, attribution, languages, capabilities and slots. A strict reader rejects any
  malformed catalog or hardware profile.
- A model advisor ranks the catalog for a hardware profile and proposes a default with a context
  size. On the reference PC's recorded figures it proposes Gemma 4 12B with a 32,768-token context
  and WAI-illustrious v17.0. All sizes are estimates until measured.
- Not done yet: the profile and the container GPU check have not been run on the reference PC
  (Windows 11 with Docker Desktop); no model is downloaded, served or selectable in the engine.

## Unreleased — Restart reconciliation, missing content and the resume card (P09.5), 28 October 2026

- A playthrough whose adventure content is missing or damaged is paused instead of half-working:
  when a pinned pack or one of its pinned dependencies is no longer installed in the adventure, its
  stored files no longer match their pinned hash, or it was built for rules this engine version does
  not provide, every new action is refused with 409 `COMMAND_DEPENDENCIES_MISSING` and nothing is
  recorded. Retrying an earlier request still returns its saved result. No other pack or version is
  ever used instead; restoring or re-importing the same content lifts the pause without a restart.
  Legacy saves whose content was never stored in sealed form keep working as before.
- New `GET /api/v1/campaigns/{id}/resume`: a short factual resume card read from the last saved
  state without advancing time: the player's location with its name in the playthrough's
  language, who the player knows to be there, resources, the next decision, saved actions still
  awaiting narration with their receipts, the content diagnosis and whether the playthrough is
  paused. The playthrough page shows it in English and Italian, with an alert naming each missing
  pack.
- At startup, after the existing recovery of actions, narration jobs and AI sessions and before
  the web server accepts requests, a memory reconciliation pass checks every playthrough's content,
  replaces damaged or diverging memory checkpoints with fresh ones taken from saved state, retires
  summary work and summaries that describe a history the save no longer has, requests a
  replacement for stale summaries when summaries are enabled, and counts narration still owed.
  It never changes saved game state and never blocks startup (`engine.memory.reconciliation.enabled`,
  default on).
- A continuity summary built from a revision the save no longer has is now stale (`rolled-back`)
  and stays stale after play continues past it.
- Narration interrupted by a restart resumes exactly once without repeating the action, and the
  first narrator conversation after the restart resumes at the pending decision without replaying
  the opening.

## Unreleased — Renewable AI sessions that resume where play stands (P09.4), 28 October 2026

- AI sessions are renewed automatically: after ten completed tasks of one role and audience, or
  before the estimated conversation would pass 85% of its context window (a warning is counted
  at 75%). A task that ran but did not complete (failed, timed out, interrupted or unconfirmed)
  retires its conversation at once. Renewal happens at the line's next task and waits while a
  game action is being committed. Limits are configurable under `engine.inference.sessions.*`.
- The owner can renew every AI session of an idle campaign with
  `POST /api/v1/campaigns/{id}/ai-session/renew`; a busy campaign answers 409 and nothing changes.
- The first task of a renewed session starts with a continuity block: where play stands (state
  revision, turn, world time, mode and the pending decision), the verified checkpoint's revision
  and a compact context for the same audience, including the latest usable continuity summary.
  Its rules forbid replaying the opening or earlier scenes and inventing history. The block is
  built only from what that audience may know; summary tasks never receive one.
- Renewal never changes the campaign, its events, the public transcript or a pending decision.
- Every AI session is recorded in the new `ai_session_record` table (migration V17): its
  predecessor, why it opened and ended, its turn and context counters, what it was rehydrated
  from and the verified checkpoint hash. Records left open by a previous process are closed at
  startup. The database backup tool now covers the V17 scope.
- Continuity summaries stay off by default; without them, renewal rests on the verified
  checkpoint and recent events.

## Unreleased — Checkpoints, source-linked summaries and bounded memory jobs (P09.3), 28 October 2026

- Operational checkpoints: a deterministic snapshot of canonical state (counters, pending
  decision, actors and real locations, resources, items, abilities, quests, open commitments,
  operations awaiting narration, and the RNG cursor without its seed) with a hash computed in
  PostgreSQL. A checkpoint is taken only at a safe boundary, never during a commit, is verified
  against storage and current state, and at most eight are kept per campaign. A background
  maintenance pass keeps them current.
- Continuity summaries for the public and privileged audiences, written by a new `summary`
  task contract in the campaign's language. Each summary records exactly which events it
  covers, every knowledge record it was given with that record's version, the entities it
  names, and the generation, locale and glossary it was written under. The contract rejects
  invented coverage, dropped promises, unlabelled rumors, ungrounded numbers or quotations and
  leaked secrets.
- Freshness: a summary becomes stale after a restore, a language or glossary change, a change
  to any record it used (a revised belief, a determined claim, a resolved promise) or an
  explicit invalidation such as a future correction. A stale summary never enters an AI
  context; a usable one enters only as an optional, labelled `summary` item that current state
  overrides.
- Bounded background jobs: one pending summary job per campaign and audience, an
  installation-wide cap, fenced leases, limited retries with backoff, recovery of expired work,
  cancellation after a restore, and no automatic retry of a range that already failed. Summaries
  are off by default (`engine.memory.summaries.enabled`); a missing or failed summary never
  blocks play.
- Migration V16 adds `memory_summary_job`, `memory_summary`, `memory_invalidation` and the
  snapshot function; the database backup tool now covers the V16 scope.

## Unreleased — Early runtime approval requests fail their turn at once, 28 October 2026

- The AI runtime can ask for an approval or a tool call right after it acknowledges a new
  turn, before the engine has registered that turn. The request was always denied. Until
  now, though, the turn was not failed: the task waited for its whole deadline and ended
  `TIMED_OUT` instead of `FAILED` with `unexpected-server-request`.
- The failure is now held with the turn's other early events, within the same 256-event
  bound. It fails the turn as soon as the acknowledgment registers it, so the isolation
  violation is reported and reconciled immediately.
- A deterministic regression case covers the ordering. It fails on the previous code.
  The race had shown up once as an intermittent `CodexTaskAdapterTest` failure in a full
  backend run.

## Unreleased — Packaging checks on hosts without Docker, 28 October 2026

- On a development host with no `docker` executable, such as the Linux toolchain
  container, the BuildKit build-context and rendered Compose boundary checks are now skipped.
  The skip reason says that hosted CI enforces them. Previously each of the four cases
  reported a `FileNotFoundError` that described the host, not the packaging inputs.
- These checks stay mandatory where they matter. The CI packaging step sets
  `PACKAGING_REQUIRE_DOCKER=1`, and any hosted GitHub Actions job counts as required too, so
  a missing Docker still fails there. An installed but unusable Docker (no Buildx or Compose,
  no daemon, a failed build) still fails everywhere. The generic `CI=true` does not count.
- The policy lives in one tested module, `tools/container_packaging/docker_requirement.py`,
  with four new cases. A skip on a Docker-less host proves nothing about build-context
  filtering or Compose boundaries. It is reported as not run there.

## Unreleased — Database backup scope for narration, scheduling and typed knowledge, 28 October 2026

- Extend the strict database backup schema/catalog allowlist to migration V15 and the seven
  tables added by V13–V15: published narration, the inference scheduler ledger and backoff
  state, and typed knowledge claims, beliefs, sightings and commitments. Backups of the
  current schema are accepted again; older schemas and unknown tables remain rejected.
- Restore needs no new retirement step: engine startup already abandons or takes over
  restored inference tasks, and the advanced recovery generation retires presentation jobs.
- Reproduce the hosted CI backup-scope failure locally before the repair; all nine offline
  backup tests pass afterwards. No live V15 dump/restore rehearsal is claimed and no roadmap
  completion mark changes.

## Unreleased — Stamped AI contexts with budgets, retrieval and audience-safe standings, 28 October 2026

- Build each AI task's context from the saved campaign, not from chat history. A context is
  stamped with exactly what it was built from: campaign, recovery generation, state revision,
  world time, role and audience, pack, rules and policy hashes, output language with its
  language and glossary versions, and the knowledge versions it read. A fingerprint of the
  exact content makes stale or changed input detectable, and the engine can list what changed
  since a context was built.
- Filter by audience before anything is searched or ranked. The player's narrator sees only
  what the player character believes, common knowledge, where it last saw people, its own
  promises and public history. A character's context holds only that character's view. Hidden
  truth, other characters' secrets, real locations and planning hypotheses reach only
  privileged planning roles.
- Label every piece of context with what it is: current state, a past event, a hidden fact
  with its truth, common knowledge, an open promise, a belief that may be wrong, a last known
  location, text already told to the player, or a planner's private assumption. Beliefs,
  narration and assumptions are never presented as established facts.
- Keep every context within a bounded input budget, by default 12,000 tokens with room for the
  answer and a safety margin, sized down for smaller models. Required material (where the
  character is, who is present, open promises, the action being narrated) is never cut. If it
  does not fit, the engine reports that the context is too large instead of sending a
  truncated one. Optional history is dropped from the least relevant end and counted, so a
  partial context says it is partial.
- Find what matters first: records named explicitly by the task, then what the current action
  changed or who informed the character, then English or Italian full-text matches over the
  whole public history (older dialogue included), then recent and important records.
- Bind language and terminology: each piece keeps its own language, and the context carries
  the pack's reviewed names for the places it mentions in the campaign's language, with the
  other language's form marked to avoid.
- Keep plan storage private to the preparation layer: planning assumptions reach a privileged
  context through a dedicated reader, and only for plans still valid in the current recovery
  generation.
- Pass 13 new cases and all 687 packaged backend tests in 70 suites with zero
  failures/errors/skips; 13 deliberate weakening probes were detected. The narration, intent
  and planning tasks adopt these contexts with checkpoints and renewal (P09.3 onward). Record
  implementation owner commit `2e6e32f` before marking P09.2 complete (55 of 131 roadmap
  entries).
- Re-verify the context work against the complete project check plan on unchanged sources:
  frontend gates, the full backend (687 tests), backup, repository and runtime-probe suites
  pass and all 13 weakening probes are detected again. The Docker-dependent packaging cases
  are left to hosted CI because the development container has no Docker.

## Unreleased — Typed knowledge, last known locations and unresolved commitments, 28 October 2026

- Record what is true separately from what each character believes. A claim about a place,
  character, item or authored definition has a hidden truth (true, false or not yet decided,
  decided at most once) and a scope: common knowledge, or only those who hold it. A
  character's belief records how sure it is (knows, believes, heard a rumor, suspects,
  doubts) and how it learned it (saw it, was told by someone, read it, worked it out). A
  belief can be wrong, and a character's own view never shows whether its beliefs are true.
- Tie every record to the action that produced it: each claim, belief, sighting and promise
  cites an event of the operation that wrote it, and the database refuses any other source.
  Changing the world supersedes a claim instead of editing it, so history stays readable.
- Keep a character's last known location of others apart from where they really are. After
  each player action the character notes the visible people around it; when someone leaves,
  the old sighting stays until the player looks again and finds them gone. Hidden characters
  are not noticed, and a report from someone else is kept as hearsay even when it is wrong.
- Track promises, obligations and agreements between two characters, with their exact words
  and language and an optional in-world deadline. They stay open until fulfilled, broken or
  released, once; a missed deadline shows as overdue without changing anything. Each
  character sees only the commitments it is part of or that everyone knows.
- Let typed knowledge open secrets: a hidden place or character becomes reachable once the
  player knows or believes a true record that it exists, or it is common knowledge. Rumors,
  false records, other characters' knowledge and free-text notes never reveal anything.
- Mark knowledge changes for planning: claims, beliefs, sightings and commitments step their
  versions and query scopes, so prepared quest plans that read them become stale correctly.
- Make the two-process scheduler test wait for the real dispatch instead of a fixed delay, which
  failed intermittently (about one run in three) when the dispatch journal write was slow.
- Pass 8 new cases and all 674 packaged backend tests in 68 suites with zero
  failures/errors/skips; 21 deliberate weakening probes were detected. Writers from play
  (reveal effects, plan consumers, promises made in dialogue) and context assembly over these
  records come later. Record implementation owner commit `24dba76` before marking P09.1
  complete (54 of 131 roadmap entries).

## Unreleased — Bounded quest-plan contract with selective replanning and a playable no-plan baseline, 28 October 2026

- Add optional quest planning, off by default. With planning off, nothing is planned or
  spent, and every quest plays exactly as before from its authored or procedural baseline.
  When an owner enables it (`engine.planning.enabled`), a known quest can receive a small
  private plan. The plan is prepared in the background in its own privileged planner
  conversation and always gives way to the player's turn.
- Define the versioned quest-plan contract: optional opportunities and clues, guarded
  alternative branches, participants, secrets, failure and abandonment rules and candidate
  later events, written only with a small fixed set of typed conditions over real campaign
  rows. A plan cannot order the player to do anything. It must keep every protected author
  constraint, must state what happens if the player ignores it, and stays within fixed
  limits (24 nodes, 8 branches, causal depth 6). Invalid output gets one repair, then the job
  fails within its bounded attempts.
- Check each plan against the world it was prepared from. A branch that no longer fits, for
  example an absent witness, is dropped alone; the other routes stay. Each recorded read
  guards only the branches whose conditions use it. Later changes therefore invalidate only
  the affected branches, and changes to rows the plan never uses invalidate nothing. Every
  plan still passes the existing transactional admission check.
- Replan selectively. When branches go stale, one refinement is requested for that plan
  version, with a per-plan limit (three by default). Unrelated world changes request nothing,
  and the surviving branches stay usable meanwhile.
- Keep play independent of planning. No action, save or narration waits for, requires or
  reads a plan, and plan text never reaches events, the journal, the stream or published
  narration. A pending, failed, declined, rejected or invalidated plan leaves the quest on
  its baseline.
- Pass 52 new cases and all 666 packaged backend tests in 67 suites with zero
  failures/errors/skips; 21 deliberate weakening probes were detected. Planner output is
  scripted in these checks: no live planner ran, and the paired narrative-quality comparison
  is deferred (it does not block the playable MVP). Record implementation owner commit
  `a984b05` before marking P08.7 complete (53 of 131 roadmap entries).

## Unreleased — Durable fair inference scheduling, 28 October 2026

- Put every AI task behind one durable fair priority queue. Interactive work (interpreting the
  player's input and narrating the player's action) always goes first; background work waits,
  ages fairly and is interrupted when the player needs the runtime. Campaigns take turns, and
  each campaign/role/audience conversation runs one task at a time in order.
- Keep one active runtime turn for the whole installation, as the earlier concurrency check
  selected. A higher setting is refused at startup, and the database itself allows only one
  dispatched or unconfirmed task, even with two backend processes.
- Bound the queue: separate places for interactive and background work, rolling daily usage
  estimates for all work and for background work, and a growing, jittered pause of background
  work after a usage-limit, rate-limit, overload or authorization error. Limits and pauses
  survive a restart and never switch runtime, model, account or billing.
- Record every task's admission, dispatch and outcome without storing its text. A task whose
  outcome could not be confirmed keeps the runtime reserved until the runtime confirms that
  nothing still runs. The Codex adapter records its conversation before starting each turn, so
  after a restart the engine first stops a turn the previous process left running, then
  continues; waiting work of the previous process is dropped and re-requested by its owner.
- Pass 92 focused cases, 31 of them new, and all 614 packaged backend tests in 63 suites with
  zero failures/errors/skips; twenty-four deliberate weakening probes were detected. No live
  runtime or account was used, so multi-turn concurrency and live quota behavior remain
  unqualified. Record implementation owner commit `e2d0ffc` before marking P08.6 complete (52
  of 131 roadmap entries).

## Unreleased — Validated narration publication, receipt fallback and narration-only retry, 28 October 2026

- Narrate each committed action from its public events only: the engine states every public
  event in the campaign's language (English or Italian), sends that bundle to the narrator in
  the campaign's public narrator session and publishes the answer only after the narration
  contract accepted it. Engine-internal events, hidden content and planning material are never
  part of the request.
- Publish narration exactly once per action, in the transaction that settles the narration
  job, and only while the campaign's language and glossary are unchanged; narration written
  before a language switch is discarded and narrated again. Published text is stored
  unchanged with its language, and character names come from the engine, not from the model.
- Show the saved receipt instead when narration stays invalid after its single repair, the
  narrator declines, the action cannot be narrated faithfully or no runtime is ready. The
  operation view now reports a `presentation` (`IN_PROGRESS`, `NARRATED` or `RECEIPT` with its
  reason and remaining retries) and the published passages and editable suggestions.
- Add `POST /api/v1/campaigns/{id}/operations/{operationId}/narration/retry`: up to three
  narration-only retries per action. A retry never repeats the action, rolls dice, changes the
  receipt or the operation status, or announces anything on the stream; cancelling the action
  stops a queued retry.
- Raise the narration attempt lease to 120 seconds so the 90-second narration budget fits in
  one attempt. The browser does not yet show the published passages or a Retry control
  (P05.6/P05.7); no live runtime has produced narration through this path.
- Pass 36 focused cases, 9 of them new, and all 583 packaged backend tests in 60 suites with
  zero failures/errors/skips; fifteen deliberate weakening probes were detected, and the
  frontend gates pass with the re-recorded interface transcript. Record implementation owner
  commit `32edd5e` before marking P08.5 complete (51 of 131 roadmap entries).

## Unreleased — Model selection through the owner's slot assignment, 28 October 2026

- Resolve each AI task's logical profile (`light`, `standard`, `deliberate`) through the
  owner's slot assignment: a backend, a preferred model and effort and up to four approved
  alternatives per slot. The engine sets the resolved model and effort explicitly on every
  turn, so a costly earlier task or an owner change between two turns of one conversation
  never leaves a later task with an inherited setting.
- Check the assigned pair against the backend's own model list and the owner's approved list
  (`engine.inference.allowlist.<backend>`). A backend that cannot list models is limited to
  the approved list. An unassigned slot, a slot assigned to another backend, a missing
  catalog or an unsupported pair ends the task with a fixed code before anything is sent, and
  the engine never moves work to another provider or billing class.
- Use an approved alternative only when the preferred pair is unsupported, and record the
  preferred pair with the result. Add the owner-selected fixed-quality setting
  (`engine.inference.fixed-quality`), which sends every task through one slot.
- Pass 85 focused cases, 26 of them new, and all 574 packaged backend tests in 59 suites
  with zero failures/errors/skips; thirteen deliberate weakening probes were detected. No
  live account or runtime was contacted, and the owner settings panel remains P21.3. Record implementation owner commit `fb77cf0` before marking P08.4
  complete (50 of 131 roadmap entries).

## Unreleased — Separate AI sessions per campaign, role, audience and policy, 28 October 2026

- Give every AI task its own session for its campaign, recovery generation, role, audience
  and content and policy hashes. A quest planner, the narrator, the intent interpreter and
  each NPC's tactician of one campaign never share a runtime conversation, and a planner's
  earlier context cannot reach public narration through a reused thread.
- Fix which audiences each role may use. The interpreter, narrator and guide, whose output
  the player sees, can never be scoped to the privileged audience where planners, directors,
  reviewers and content or encounter proposers run.
- Remove the session parameter from the contract runner. It now derives the session from the
  task kind and the task binding, which also records the campaign generation, so callers can
  no longer pass a planner session to narration. Only the new scoped registry may submit to
  the runtime port, and a source-level test enforces this.
- Renew a session automatically when its campaign generation, content hash or policy hash
  changes. A late task from before a restore is refused without sending anything.
  Retirement of one scope, one campaign or the whole installation reaches the runtime
  adapter. Active sessions are bounded by `engine.inference.sessions.capacity` (default 256).
- Pass 79 focused cases, 23 of them new in five new suites plus the Spring selection suite;
  fifteen deliberate weakening probes were detected. The checks use deterministic doubles,
  including the real Codex adapter against the scripted app-server. Gameplay still calls no
  contract (P08.5), and session records stay in memory until P09.
- Pass all 548 packaged backend tests in 56 suites with zero failures/errors/skips; fifteen
  weakening probes were detected and all 26 repository-policy tests pass with zero scan
  findings. Record implementation owner commit `a55319f` before marking P08.3 complete (49
  of 131 roadmap entries).

## Unreleased — Validated intent and narration contracts, 28 October 2026

- Add typed intent interpretation. One free-text declaration becomes exactly one proposed
  move, wait, speech or ability use over known options, a short clarification with two to
  four readings, or a cannot-complete reason. Speech must be the player's own words. Effect
  fields such as damage or state patches are rejected. A proposal is never submitted by
  itself.
- Add attributed narration with quick replies, generated directly in the campaign's English
  or Italian. Passages must:
  - cite the committed public events and narrate every one;
  - give speech only to known non-player characters;
  - use only numbers the receipt allows;
  - stop at the pending decision.

  Up to three editable suggestions refer only to that decision's available actions.
- Reject output that leaks privileged phrases, alters a proper name, uses the other
  language's term, changes a protected quote or is clearly written in the other language.
  Refuse to send a context that already contains a privileged phrase.
- Allow exactly one repair with the same context and only the violation codes. Never repair
  unavailable, failed, timed-out or cancelled tasks. Bind every result to the locale and
  glossary generation so stale text is not shown.
- Pass 35 focused cases in four new suites; deliberate weakening probes were detected.
  Nothing in gameplay calls the contracts yet (publication is P08.5), and no live runtime
  produced output for them.
- Pass all 525 packaged backend tests in 51 suites with zero failures/errors/skips; fifteen
  weakening probes were detected and all 26 repository-policy tests pass with zero scan
  findings. Record implementation owner commit `3caa39b` before marking P08.2 complete (48
  of 131 roadmap entries).

## Unreleased — Narration task port and Codex transport, 28 October 2026

- Add the runtime-neutral narration task port: tasks name a logical `light`, `standard` or
  `deliberate` slot and a logical session, carry bounded instructions and a JSON output
  schema, and always end with a result that has a fixed status and error code and records
  the runtime, version, model and effort. Without a configured runtime every task ends
  "unavailable" and the game keeps working.
- Add the Codex transport behind it: the packaged Unix-socket WebSocket client and an owned
  stdio child with an environment allowlist and redacted diagnostics. Requests and turn
  events are correlated by exact thread and turn identity. The queue is bounded, one turn
  runs at a time, and model and effort are explicit on every turn. Cancellation, deadlines
  and shutdown interrupt the exact turn and wait for its confirmation. Lost or unconfirmed
  turns are reconciled before further work. Server approval requests and tool items fail
  closed.
- Report gameplay readiness as up only when the selected runtime is connected, on the
  pinned version and signed in. The packaged profile keeps the transport disabled until
  narration uses it.
- A boundary test keeps every Codex type inside its adapter package.
- Pass 46 focused cases and all 490 packaged backend tests in 47 suites with zero
  failures/errors/skips, including a replay of the retained runtime fixture and real
  child-process and Unix-socket transports. Nine deliberate weakening probes were detected.
  All 26 repository-policy tests pass with zero scan findings. No live runtime or account
  was used; the opt-in real smoke remains a later check.
- Record implementation owner commit `4c46d9f` before marking P08.1 complete (47 of 131
  roadmap entries).

## Unreleased — Transactional plan admission and activation, 28 October 2026

- Recheck prepared plans inside the campaign lock before admission: current generation,
  no private reads or secret definitions in public input, the campaign's own pinned
  content and cited definitions, author constraints recomputed from the sealed pack, and
  exact row versions of the actors, resource pools, items, character state and
  player-knowledge records a plan read, in addition to query epochs. A plan that cites a
  campaign entity without a recorded read is refused. Snapshot capture now rejects
  private reads in public input and author constraints the pack does not have.
- Invalidate admitted plans in every canonical commit that changes a row or scope they
  read; unrelated changes keep them usable.
- Let only ordinary canonical commands use a plan: a commit that cites admitted branches
  rechecks them against current rows before writing anything, rolls back if any is
  stale, retired, rejected or already used, and records each branch once. A plan never
  supplies an effect, and receipts never name it. No player request can cite a plan.
- Add V12 and extend the database backup scope to `plan_activation`.
- Pass 155 focused cases and all 444 packaged backend tests in 40 suites with zero
  failures/errors/skips, including ordinary-command invalidation, live rechecks at use,
  admission/command races, phantoms and fault injection at COMMIT; seven deliberate
  weakening probes were detected. Pass configured frontend checks, both locale builds,
  all 26 repository-policy tests with zero scan findings and the offline backup tests.
  The quest-plan contract and the consumers that build commands from plans remain later
  work (P08.7, P09.1, P10).
- Record implementation owner commit `c598429` before marking P07.6 complete (46 of
  131 roadmap entries).

## Unreleased — Italian variant rendering checks (partial), 28 October 2026

- Render every routed screen, the navigation shell and the recorded real-server campaign
  replies with the reviewed Italian catalog in the frontend suite, and fail on English-only
  catalog text, uncatalogued English phrases, unnamed controls, links or images, broken ARIA
  references and unsupported `lang` values. Italian receipts are checked to carry exactly the
  committed numbers.
- Give the Italian build its reviewed page title (`Motore di avventure`) before the interface
  starts and without scripting; the packaged server test now checks each locale's title and
  `lang`.
- Mark saved-adventure titles and genres with the adventure's source language so screen
  readers pronounce them correctly in either interface language.
- Not complete: no browser or screen-reader journey ran (no browser in the build container),
  and QA-35 still needs independent bilingual and screen-reader review, so P05.6 remains open.

## Unreleased — Real-server reload contract for the bilingual UI (partial), 28 October 2026

- Record the exact replies of the real servlet stack and PostgreSQL across a language
  reload with an action in flight (`CampaignInterfaceContractTest`), pin them in a golden
  transcript and replay a byte-identical copy in the frontend suite through the
  validators and an EN→IT→EN reload of the playthrough view. A new `contracts`
  repository check (and CI step) keeps the two copies equal.
- Fix a reset loop: after `stream.reset`, the server's head now replaces a stored cursor
  that is ahead of it instead of being merged back over it on the next write.
- Re-read the playthrough state when an applied message or reset names a newer world
  revision, so an answered starting decision no longer stays on screen after a commit.
- Correct 11 Italian campaign messages after an author review (for example
  `Presa in carico` instead of the ambiguous `Ricevuta`, and distinct sentences for
  stopped and interrupted narration). Independent review is still required.
- Not complete: no browser journey ran (no browser in the build container) and QA-35
  needs independent bilingual and screen-reader review, so P05.6 remains open.

## Unreleased — Bilingual campaign integration (partial), 28 October 2026

- Show a selected playthrough's public state in the saved-adventure view: the starting
  situation with an explicit note when it is displayed in the pack's source language,
  operations and receipts re-queried by identity, a resumable stream follower with
  bounded backoff and the scoped persistent draft. The view never submits anything.
- Persist each campaign scope's stream cursor and operation identities
  (`engine.campaign-sync.v1`). Language reload of a real campaign is now allowed only
  after that record merges with other tabs and reads back exactly; after reload,
  delivery resumes after the saved cursor without applying a message twice.
- Localize 26 backend error codes, operation statuses, receipts, narration and
  stream-reset reasons in English and Italian (77 new messages, 317 in total). Unknown
  codes show one generic sentence. The Italian wording is a first draft awaiting
  independent review.
- Serve `/saved/<uuid>` and `/{en,it}/saved/<uuid>` from the packaged jar for reloads and
  direct links; deeper, uppercase or resource-like paths stay 404.
- Add a `localization` repository check that fails when a campaign controller error
  code has no browser message.
- Not complete: no browser journey ran on this build and QA-35 acceptance needs human
  bilingual and screen-reader review, so P05.6 remains open.

## Unreleased — Resumable stream, cancellation and presentation jobs, 28 October 2026

- Add owner cancellation (`POST …/operations/{id}/cancel`): the campaign lock decides the
  race with execution. Pre-commit operations become `CANCELLED` and their permit is
  fenced; a commit that already won is reported as `ALREADY_COMMITTED` with its receipt
  and only its optional narration is stopped.
- Prevent reroll fishing: an equivalent new request at the same revision after a
  cancelled, failed or rejected attempt shares that attempt's draw stream and obtains
  the same values.
- Announce every operation status change on a durable per-campaign public stream and
  serve it as Server-Sent Events (`GET …/stream`) with `Last-Event-ID` resumption,
  allowlisted data, bounded long polls and reset instructions for expired, unknown or
  restored cursors. Command responses include the stream path.
- Create one durable post-commit presentation job per narration-requiring commit and
  settle it in the background with a fenced lease, bounded retries and restart
  recovery; owed narration now survives a restart as queued work. Without a narration
  runtime the action stays saved with narration pending.
- Add V11 and extend the database backup scope to the three new tables.
- Pass 173 focused cases and all 435 packaged backend tests in 38 suites with zero
  failures/errors/skips, including real lock-held cancellation races and late-worker
  fencing. Pass configured frontend checks, both locale builds, all 22
  repository-policy tests with zero scan findings and the offline backup tests.
  Narration and browser binding remain later work.
- Record implementation owner commit `dd122c0` before marking P07.5 complete (45 of
  131 roadmap entries).

## Unreleased — Local command execution, 28 October 2026

- Execute registered exploration intentions locally after admission: re-check rules
  under the campaign lock, calculate effects deterministically, reserve one durable
  draw per die and commit once. Record `REJECTED` or `FAILED_BEFORE_COMMIT` after a
  late refusal or failure, release the permit and keep reserved draws; identical
  retries replay and never re-execute.
- Move the player with a typed row-version-guarded placement, advance exact waiting
  time, record exact speech, and use abilities by paying costs exactly and running
  `modifyResource`/`conditional` effect programs with clamping and vocabulary-bound
  targets. Refuse effect programs that need later systems with
  `COMMAND_EFFECT_UNSUPPORTED`.
- Write versioned events with calculation inputs and draw ordinals, advance one turn
  per action, clear the pending starting decision, bump actor query epochs and return
  an allowlisted player-safe receipt from the command and status routes.
- Pass 151 focused command cases and all 414 packaged backend tests in 34 suites,
  with zero failures/errors/skips, including trigger-injected failures after durable
  draws. Pass configured frontend checks, both locale builds and all 22
  repository-policy tests with zero scan findings. Cancellation, streaming and
  narration remain later work.
- Record implementation owner commit `3174e5a` before marking P07.4 complete (44 of
  131 roadmap entries).

## Unreleased — Current-state command rules, 27 October 2026

- Validate movement against disclosed, accessible authored links from the player's
  current location, and speech recipients against co-presence and authored
  knowledge. Hidden locations, routes and actors share one rejection category.
- Add the closed `USE_ABILITY` intention. Require a learned pinned ability, its
  prerequisite abilities and skill ranks, a legal target count and affordable costs
  in the creation profile's own resource vocabulary; area targeting and item, trade
  and encounter intentions stay unavailable.
- Re-derive every rule premise inside canonical commit and reject resolved effects
  on actors the intention does not involve. Compile prose-free pinned rule facts
  before the campaign lock; content that cannot be loaded fails closed only where
  definitions are needed. Add six stable `COMMAND_*` rejection codes.
- Pass 138 focused command cases and all 401 packaged backend tests in 32 suites,
  with zero failures/errors/skips, including real campaign-lock contention at both
  boundaries. Pass configured frontend checks, both locale builds and all 22
  repository-policy tests with zero scan findings. Calculations and execution
  remain P07.4.
- Record implementation owner commit `1b9b0ee` before marking P07.3 complete;
  correct the roadmap status summary to 43 of 131 entries.

## Unreleased — Typed commands and atomic semantic validation, 27 October 2026

- Admit a closed immutable exploration vocabulary for movement, bounded waiting and
  addressed speech. Reject unknown types, generic/nested field patches, batches,
  effect/cost fields and coercion; hash and validate one frozen canonical snapshot.
- Validate current mode, supported decision and creation-player/recipient scope in
  the admission transaction. Require matching typed intention evidence and repeat
  common semantics inside canonical commit; preserve historical admission/receipt
  replay, durable draws and atomic multi-effect rollback.
- Pass 131 focused command cases and all 394 packaged backend tests in 31 suites,
  with zero failures/errors/skips, including real campaign-lock contention at both
  validation boundaries. Pass configured frontend checks, both locale builds and
  all 22 repository-policy tests with zero scan findings. Keep detailed resource/
  prerequisite/knowledge/causal rules and execution in P07.3–P07.4.
- Record implementation owner commit `05f5ba7` before marking P07.2 complete.
  Preserve all imported task identities/statuses and the pre-existing pending
  external P98 follow-up, synchronizing it into the source roadmap.
- Re-verify P07.2 after its first completion report was rejected for listing
  superseded failed attempts as verification outcomes. With the implementation
  unchanged, fresh focused/backend/frontend/repository gates pass again; record only
  final repaired-gate outcomes as exit evidence.

## Unreleased — Complete durable command admission, 27 October 2026

- Finish P07.1 from the preserved implementation in `174be3e`, retaining versioned
  payload hashes, exact revisions, atomic journal/lease admission, fencing,
  owner-wide limits and scoped read-only operation statuses.
- Add two real PostgreSQL concurrency cases for lease expiry during renewal and
  admission in both owner-lock acquisition orders; reject expired renewal while
  reusing capacity without exceeding the two-permit limit.
- Pass 96 focused command tests and all 359 packaged backend tests, with zero
  failures/errors/skips. Pass frontend installation/typechecking, 49 tests, four
  localization checks, both locale builds and 22 repository-policy tests with
  zero scan findings. Synchronize both roadmaps and preserve prior recovery
  evidence and original drafts. Keep typed execution and delivery in later tasks.

## Unreleased — Restored command verification, 27 October 2026

- Independently verify the owner-restored disposable PostgreSQL fixture: scoped
  DNS/TCP connectivity succeeds, all 94 focused command tests pass, and the full
  packaged backend passes 357 tests with zero failures, errors or skips.
- Preserve the interrupted command implementation and its tests in a verified
  checkpoint. Retain the earlier failed recovery evidence and original migration
  drafts; no source behavior, fixture configuration or test requirement is changed
  during this recovery.
- Keep all 130 roadmap identities/titles/statuses and 40 completion marks intact.
  Return P07.1, still unchecked, for normal independent admission. The owner reports
  restart persistence for the fixture; this session verifies connectivity and
  authenticated database tests, without repeating the host restart experiment.

## Unreleased — Command verification dependency checkpoint, 27 October 2026

- Re-run the scoped packaged backend check: JDK/Maven execute, but the disposable
  PostgreSQL fixture hostname does not resolve. The suite reports 357 tests,
  188 passing and 169 context/connection errors, with no skipped tests. This failed
  check does not supersede the earlier recovery history with a new acceptance pass.
- Verify frontend typechecking, 49 tests, four localization checks and both locale
  builds, plus all 22 repository-policy tests and zero scan findings.
- Retain the reviewed command implementation and original migration drafts without
  marking P07.1 complete. Restore fixture connectivity, then rerun focused and full
  database checks before committing the implementation and checking its roadmap item.

## Unreleased — Durable command admission, 26 October 2026

- Add bounded versioned command hashes, exact revision checks and atomic durable
  journal/lease admission, with matching retry replay and changed-payload conflicts.
- Enforce two live mutation permits per owner across adventures and internal lease
  acquisition/renewal; keep inference-job limits separate. Fence expired workers and
  use one database cutoff for cleanup/busy to prevent stranded active journals.
- Expose owner-scoped admission/status through the existing campaign adapter with
  strict bounded JSON and sanitized responses; keep worker capabilities and raw
  receipts private. Leave typed execution and browser submission to subsequent tasks.
- Pass 94 focused command tests and all 357 packaged backend tests on PostgreSQL
  18.6, without failures/errors/skips. Reproduce and repair the expiry regression;
  pass frontend typechecking, 49 tests, four localization checks, both locale builds
  and 22 repository-policy tests with zero scan findings. Preserve original drafts
  and earlier recovery history.

## Unreleased — Verified development dependency recovery, 26 October 2026

- Verify the owner-provisioned private JDK 21.0.12.1/Maven 3.10.0 environment and
  disposable PostgreSQL 18.6 fixture through the scoped verification helper.
- Install locked frontend dependencies, build both production locales and pass
  typechecking, 49 frontend tests, four localization tests and all 318 packaged
  backend tests without failures, errors or skips. Keep existing checks and pins.
- Preserve earlier recovery failures, all eight unfinished drafts, 130 roadmap
  tasks and 40 completion marks. P07.1 remains unimplemented and unchecked;
  runner configuration activation stays owner-controlled. Document the remaining
  unscoped wrapper ZIP extraction prerequisite separately from helper success.

## Unreleased — Packaged verification prerequisite audit, 26 October 2026

- Clarify that direct Maven verification needs Maven 3.10.0 on its executing PATH
  and that packaged verification needs the compiled bilingual interface and notices.
- Confirm the existing 130-task dependency graph is valid, with all 40 completion
  marks preserved. Toolchain, frontend-build and disposable-database prerequisites
  remain unavailable; this audit does not repair the environment or complete P07.1.

## Unreleased — Backend prerequisite recovery diagnostics, 26 October 2026

- Reproduce the missing-JDK wrapper failure and document the additional `unzip`
  prerequisite and Java download fallback in the backend guide.
- Verify the downloaded Maven 3.10.0 ZIP against its unchanged committed checksum.
  JDK acquisition and PostgreSQL provisioning remain unresolved; no compilation,
  database verification or P07.1 completion is claimed. Preserve all task statuses
  and the unfinished admission drafts.

## Unreleased — Command admission continuation review, 26 October 2026

- Record the P07.1 draft review, candidate admission policy, integration concerns
  and explicit exit checks in the [turn protocol](docs/07-turn-protocol.md#p071-continuation-checkpoint)
  and existing development roadmap.
- Preserve all eight unverified drafts and the 40 checked task statuses. No command
  implementation or controller is added. Backend verification cannot start without
  JDK 21; a disposable PostgreSQL test service is also a required dependency.

## Unreleased — Speech input and hands-free play (documentation), 26 October 2026

- Add [ADR 0022](docs/adr/0022-optional-speech-input.md) and the separate
  [EN/IT STT specification](docs/35-speech-input-and-hands-free-play.md) for
  optional spoken exploration and combat dialogue/commands. Existing local TTS
  remains a distinct feature; the recognition backend/model is not selected.
- Specify explicit microphone enablement/listening/mute, voice-only public
  preview/correction/repeat/confirmation/cancel, normal command authority and
  idempotency, decision boundaries, echo/interruption controls, ephemeral scoped
  audio, shared-resource qualification and complete typed fallback.
- Add FR-041, prospective QA-45, unchecked P17.8–P17.10 (127 to 130 tasks),
  API/UX/security/risk and index/README crosslinks. Preserve all checked statuses.
- No recognizer, capture transport, worker, microphone permission, gameplay
  implementation, installation or performance/acceptance result is delivered;
  optional voice remains disabled and unimplemented.

## Unreleased — Inference backends and model selection (documentation), 25 October 2026

- Record the owner decision for free local models, API-key backends and owner-chosen model slots in [ADR 0021](docs/adr/0021-inference-backends-and-model-selection.md): a closed set of protocol adapters, an open set of models and endpoints, four slots (light, standard and deliberate text, and image), and recommended defaults that never restrict the owner.
- Add the [specification](docs/34-inference-backends-and-model-selection.md): measured reference-host facts (RTX 4070 with 12 GB of VRAM, one 16 GB memory module), sourced model facts, recommended defaults (Gemma 4 12B QAT for text; WAI-illustrious-SDXL v17.0 for images, as chosen in the predecessor project), the hardware advisor, the gateway, the local server and image worker profiles, and shared GPU admission.
- Correct the speech specification, which assumed 32 GB of system RAM and excluded local gameplay text inference.
- Add FR-039, FR-040, QA-43, QA-44, risk and decision rows, a rights-checklist item, and glossary and reference entries.
- Add work package P21 (P21.1–P21.6, 121 to 127 tasks) after P09. P17.6 now requires the local image backend and P18 waits for P21.
- No backend, image worker, launcher option, setting or measurement is delivered by this documentation change.

## Unreleased — Adventure isolation scheduling correction, 25 October 2026

- Let P07 start from the completed P06.1–P06.4 campaign foundations and give P06.5
  explicit prerequisites P05.7, P08.6, P09.3 and P10.2. The earlier whole-package
  P07 dependency made the remaining QA-33 work impossible to schedule without a cycle.
- Keep the MVP QA-33 gate (P14 still depends on all of P06) and assign generated-image
  folder isolation to P17.6 with the v1 QA-31 scope. No requirement, task identity,
  title or checked status changed.

## Unreleased — Selectable narration runtime (documentation), 25 October 2026

- Record the owner decision to allow Claude Code as a selectable alternative to Codex in [ADR 0020](docs/adr/0020-selectable-narration-runtime.md): one runtime at a time, an installation-wide setting, Codex by default, no automatic failover, and switching through the launcher.
- Add the [Claude runtime specification](docs/33-claude-runtime-integration.md): sourced facts, a candidate launch profile, authentication and billing modes with startup guards, limit and error mapping, and the isolation evidence required.
- Make Claude conditional on P20 evidence and a recorded owner review of Anthropic's terms, which restrict routing subscription credentials for users and credential handling by applications.
- Add FR-038, QA-42, risk and decision rows, a rights-checklist item, glossary and reference entries, and runtime-neutral wording in the requirements, architecture, security, API, memory, deployment and publication documents.
- Add work package P20 (P20.1–P20.4, 117 to 121 tasks) before P18 hardening and make P08.1 and P08.4 runtime-neutral. The MVP stays Codex-only.
- No adapter, image, launcher option, setting or Claude test result is delivered by this documentation change.

## Unreleased — Saved library browser binding checkpoint, 25 October 2026

- Show persisted adventures on the library page and add a read-only
  `/saved/<adventureId>` view with playthrough selection scoped to that adventure.
- Validate every library payload strictly and re-bootstrap the local session once
  after a denial. Abort and discard earlier reads when switching adventures.
- Packaged reload of `/saved/...`, saved-campaign play and complete QA-33 remain open.

## Unreleased — Recoverable workspace folders checkpoint, 25 October 2026

- Add opt-in adventure/revision folders separating sealed specifications and authored
  media, with an integrity manifest and atomic publication. Reconcile after committed
  imports and on startup; retain PostgreSQL originals as the sole authority. Reject
  modified/extra files, unsafe paths and links without overwriting private content.
  Real browser switching, generated artwork and complete QA-33 remain unfinished.

## Unreleased — Persisted adventure library checkpoint, 25 October 2026

- Add bounded owner-scoped adventure listing and detail GETs, preserving empty
  workspaces and separate identities for equal titles. Extend local admission
  to the whole adventure subtree; return detached public metadata with no active
  world selection or writes. Managed specification/media storage, browser binding
  and full QA-33 remain unfinished.

## Unreleased — Exact local consultations, 25 October 2026

- Route bounded exact English/Italian status and inventory aliases through admitted
  scoped snapshots. Return typed presentation passages without creating operations,
  runtime calls, transcript writes or world changes; reject prose and unknown commands.
  Keep optional durable transcript history separate from canonical commits.

## Unreleased — Starting presentation, 25 October 2026

- Persist the pack-defined starting decision during campaign creation and project
  only its localized public entrypoint and typed free-input metadata on state/continue.
  Preserve pinned text, explicit source fallback, unknown-decision privacy and
  read-only/retry behavior without injecting a fixed prologue or player choice.

## Unreleased — Backup scope recovery, 24 October 2026

- Extend the strict backup schema/catalog allowlist to V10 and the two explicit
  player knowledge tables after reproducing the exact P06.2 CI failure. Preserve
  rejection of older schemas, unknown tables and unsuccessful migrations.
- Verify the backup suite (eight passes, one existing Windows symlink-privilege
  skip), 22 repository tests and 98 account-free runtime cases (18 opt-in skips).
  Repository scans report zero findings; all 117 roadmap task definitions and
  checked states are preserved. No live V10 restore is claimed.

## Unreleased — Scoped player projections (P06.2), 24 October 2026

- Add bounded owner campaign listing and scoped read-only continue/state,
  inventory, learned ability, known fact and known quest endpoints.
- Add explicit audience knowledge storage and public response allowlists;
  extend local session admission across the campaign API subtree.
- Verify all 296 packaged backend tests on PostgreSQL 18.6, including eleven
  real HTTP tests, concurrent snapshot consistency, whole-database no-write
  checks, process restart and populated migration upgrade; all 22 repository
  policy tests and repository scans pass. Frontend binding, starting decisions and exact
  text consultations remain later tasks.

This changelog records delivered project artifacts. Future functionality belongs in [the roadmap](docs/16-development-roadmap.md), not in completed release notes.

## Unreleased — Atomic campaign creation (P06.1), 24 October 2026

- Add an owner-scoped transactional creation service from sealed pack snapshots,
  validated preset/custom budgets, resource maxima and authored item/ability grants.
  Persist immutable scoped request identities for durable, concurrent safe retries.
- Add V9 character/loadout storage and recovery allowlist coverage; preserve
  populated older schemas without inventing character choices or advancing counters.
- Load bounded transitive sealed dependency snapshots, resolve external creation
  profiles and mechanics in their declaring namespace, and enforce actual pinned caps.
  Reject ambiguous dependency versions and keep existing root-local identities stable.
- Add opt-in local session bootstrap and protected synchronous campaign creation,
  with exact Host/Origin, server-configured ownership, bounded ephemeral sessions,
  independent CSRF proofs, concurrency admission and strict 16 KiB UTF-8 JSON.
  Return only committed identities and stable sanitized errors. Starting narration
  and creation UI integration remain later tasks.
- Verify two genre campaigns and immutable retry results across separate application
  JVM restarts, with old browser sessions rejected and initial counters/grants intact.
  All 290 packaged backend tests and 22 repository policy tests pass; scans are clean.

## Unreleased — Staged interface integration dependencies, 24 October 2026

- Correct the roadmap dependency cycle between localized interface integration
  and campaign/operation APIs. P06 waits for the completed UI foundation;
  P05.6 waits for P06.2 and P07.5 before integration can resume.
- Document exact-task prerequisite syntax, preserving all 117 task identities,
  the 36 completion marks, real-campaign reload protection and human review gates.
  This changes scheduling documentation, not delivered gameplay functionality.

## Unreleased — Localization review foundation (partial P05.6), 24 October 2026

- Assemble both locale variants into the jar/container; select the saved device
  language at root and support explicit localized preview GET routes. Preserve
  shared artwork/notices, opaque query/hash and missing-resource/API 404s.
  Verify bilingual browser journeys against the actual container, exact jar
  resource bytes and resource hashes across recreation; full QA-35 remains open.

- Add persisted English/Italian selection to compiled review Settings, safe reload
  with scoped literal drafts/selections, validated tab reading preferences/positions
  and equivalent-control keyboard focus; invalidate old callbacks and reply objects.
- Block language reload for unsaved/conflicting drafts, unresolved recovery across
  route changes, denied storage and real campaign contexts awaiting reconciliation.
  Review 218 unique messages including storage/fallback guidance; verify real
  EN→IT→EN keyboard reloads, scope isolation and persisted native size selection.
- Extend to 212 unique reviewed messages across every local preview screen,
  public sample passages/metadata, forms, preview receipts, recovery, kit and
  accessibility. Preserve IDs, proper adventure titles and literal player drafts.
- Add chronicle ICU plurals and locale-formatted resource text without changing
  numeric ARIA/protocol values. Verify native localized reading/form journeys.
- Pin Angular localize 22.2.3 and add separate English/Italian review builds with
  26 reviewed navigation/Settings messages, accessible labels and page titles.
- Reject missing, blank, stale, extra or duplicate translations and mismatched
  placeholders before compilation; run checker tests and review builds in CI.
- Verify both Settings variants in Chrome, including narrow layouts, keyboard
  focus, locale bases, same-origin resource hashes, notices and missing-file 404s.
- Keep real transport errors/receipts and operation/cursor reconciliation open;
  the packaged interface now consumes both localized variants.

## Unreleased — Complete local interface graphic kit (P05.5), 24 October 2026

- Integrate original MIT SVG/CSS controls, 20 allowlisted icons, bounded numeric
  resource/progress bars, dialogue/portrait/item frames and independent sample item
  art, with explicit empty/unavailable/invalid/selected/disabled/busy states.
- Add a Settings-linked review surface, editable-source/provenance inventory and
  complete distributed MIT notice; no third-party art or raster generation is used.
- Verify 30 frontend tests, strict typecheck/build, zero-vulnerability audit,
  22 repository tests and Chrome desktop/mobile keyboard/pointer/contrast checks,
  including narrow layouts, forced colours, increased contrast and reduced motion.
  Runtime campaign artwork and Italian resources remain later milestones.

## Unreleased — Revision-aware drafts and quick replies (P05.4), 24 October 2026

- Separate scoped public projection state from unsent drafts, with canonical and
  presentation revision ordering, callback epochs and stale/duplicate rejection.
- Persist bounded device-local draft text/selection per adventure/campaign; retain
  edits with visible fallback on unavailable, corrupt or full storage.
- Add typed contextual speech/question/action suggestions that fill an empty
  editable composer; dismissal preserves text and restores keyboard focus.
- Present specific connection/recovery states without clearing accepted context
  or replaying input. Send stays disabled until campaign operations exist.
- Pass 26 frontend tests, strict typecheck/build, a zero-vulnerability audit,
  22 repository tests and Chrome desktop/mobile draft/reload/connection smoke;
  visually review the mobile composer. Actual operation reconciliation remains
  in P06/P07 and full accessibility acceptance remains pending.

## Unreleased — Responsive reading layouts (P05.3), 24 October 2026

- Add a tab-lifetime text-focused reading preference alongside the default visual
  novel; omit artwork in text mode while retaining public scene descriptions.
- Resolve original vector portraits from scoped public speaker identities with
  live labels and missing-image fallbacks; record editable sources and MIT terms.
- Attribute revealed chronicle entries and keep keyboard focus in the dialogue
  when Continue reaches the end of supplied text. Reading remains local-only.
- Pass strict typecheck, 18 frontend tests, production build, zero-vulnerability
  audit, 22 repository tests and Chrome desktop/mobile keyboard/focus smoke,
  including 320px/landscape no-artwork reading at 24px and reduced motion.
  Visually review three screenshots. Persistent drafts, playable input,
  localization, the complete graphic kit and assistive-technology review remain.

## Unreleased — Content-bound interface metadata (P05.2), 24 October 2026

- Bind navigation, headings, character-name terminology and attribute/resource
  definitions to typed scoped public metadata with stable identities and neutral
  English fallbacks. Render metadata as literal text and leave balances unconnected.
- Configure optional guide/creative panels independently in the local genre samples;
  hide absent navigation and refuse disabled direct routes after adventure switching.
  This adds a frontend projection contract; pack module execution, registered
  pack/campaign APIs and localized UI remain later tasks.
- Verify 15 frontend tests, strict typecheck, production build, dependency audit
  (zero vulnerabilities), 22 repository tests and all four policy scans. Extended
  Chrome desktop/mobile smoke passes with no page exceptions or remote/action
  requests; visually review story and resource-panel screenshots.

## Unreleased — Portable launcher command checks, 24 October 2026

- Make launcher path/port checks independent of the runner's Docker version by
  injecting strict test-only Docker metadata. Check unsupported Engine/Compose,
  non-Linux containers, unreachable daemons and missing Buildx explicitly.
- Verify read-only file preservation recursively and use an available test port.
  Keep production Engine 29/Compose 5 minimums and real BuildKit/Compose checks.
  The Docker 28.0.4 reproduction now passes; local packaging tests pass 21 with
  two existing opt-in skips. Hosted verification is recorded separately.

## Unreleased — Combat dialogue design, 24 October 2026

- Extend the combat/UI, command and interpretation specifications with a persistent
  in-combat composer for participant dialogue, negotiation and supported scene
  interactions, including public speaker/recipient identities and editable replies.
- Specify bounded speech, declared social-action costs, explicit agreement decisions,
  turn eligibility, duplicate-safe recovery and causal event changes. Add a worked
  example, acceptance cases and P11/P12 scope. This delivers documentation only;
  playable combat chat and numerical-policy calibration remain future work.

## Unreleased — Interface preview shells (P05.1), 24 October 2026

- Add routed library, character-creation, visual-novel story, sheet, inventory,
  journal and settings shells with explicit local preview/unconnected states.
- Separate adventure cards from installed packs; validate bounded character-name
  previews without saving. Keep supplied-text reading positions independent per
  sample, attribute narrator/character passages and expose a read-only chronicle.
- Include original MIT-licensed woodland/station SVG scenes, live accessible text,
  responsive styles and bounded tab-local reading size. Real pack/campaign binding,
  playable input, full localization and the complete graphic kit remain later tasks.
- Align name validation with trimmed service input and clear unsaved summaries on
  adventure changes, including return navigation; add regression and settings tests.
- Verify typecheck, 11 form/routing/service tests, production build, dependency
  audit (zero vulnerabilities), 22 repository tests and all four repository scans
  (zero findings). Desktop/mobile Chrome smoke passes navigation, keyboard reading,
  chronicle, forms, settings and unknown-identity handling without remote/action
  requests or page exceptions; visually review both scene layouts.

## Unreleased — Validated pack localization (P04.6), 24 October 2026

- Add optional closed EN/IT overlays and source-owned text resources, stable ID/field bindings, glossary proper-name/term hints, explicit quote policies, placeholder parity and source-language fallback. Resolve text through a separate immutable catalog; mechanics, identities, visibility and dependency exports remain source-authoritative.
- Include reviewed EN/IT resources for all approved catalog leaves in both neutral genre fixtures, with explicit verbatim English quotes and honest coverage of source-only prose. UI resources, narration and independent human bilingual playtests remain later work.
- Seal deterministic localization coverage in report version 2. Add preservation-only V8 support for old/new report versions, extend the strict database-backup allowlist and verify exact recovery of fixture bytes, hashes, exports and both report formats.
- Verification: 45 focused cases pass; full pinned JDK 21/PostgreSQL 18.6 `verify` passes 262 tests with three packaging-profile skips (265 total), builds the jar and checks populated V1→V8 preservation. Backup tests pass eight with one Windows symlink skip; a disposable V8 dump/restore passes. All 22 repository tests pass and docs/secrets/workflows/images have zero findings; jar inspection confirms trusted schemas/V8 and excludes test inventories.

## Unreleased — Neutral genre import fixtures (P04.5), 24 October 2026

- Add standalone fantasy exploration and science-fiction salvage packs with distinct world graphs, vocabulary, skills, resource caps, creation profiles, abilities, quests and original MIT-licensed schematic PNG assets.
- Prove unchanged directory/ZIP imports, read-only preview, exact sealed byte/hash/report preservation, idempotency, recompilation and independent adventure imports on the same engine. Broken starting-item references in both real fixtures fail with source context and no partial rows.
- Keep optional modules disabled under the existing engine contract. These are English import/compiler fixtures; playable campaigns, guide/creative runtime, full adventure isolation and reviewed EN/IT overlays remain later gates.
- Verification: two focused PostgreSQL integration tests pass; full pinned JDK 21/PostgreSQL 18.6 `verify` passes 220 tests with three packaging-profile skips (223 total) and builds the jar; 22 repository-checker tests pass and all four policy checks have zero findings.

## Unreleased — Protected pack registration (P04.4), 24 October 2026

- Add bounded directory/ZIP staging with portable path/device/case guards, link/special-file rejection, consistent archive headers/descriptors, CRC verification and actual inflation limits; retain immutable staged byte copies without disk extraction.
- Validate allowlisted media signatures and image header dimensions. Store original pack bytes, hashes, exports and deterministic reports atomically in adventure-owned revisions. Serialize idempotent imports and sealing; reject conflicts, legacy-unsealed retries, mutations and late file insertion, and verify hashes on reload.
- Add V7 tables/triggers and populated V1→V7 preservation checks. Record the bounded ZIP subset, PostgreSQL storage and provisional ruleset descriptor hash in ADR 0018.
- Verification: 23 focused admission tests; full pinned JDK 21/PostgreSQL 18.6 `verify` passes 218 tests with three packaging-profile skips (221 total) and builds the jar; 22 repository tests pass and all four policy checks have zero findings.
- Directory sources require owner control and a quiescent tree. Header checks do not fully decode media or prove rights. Import UI/endpoints, serving/decoder budgets, genre fixtures and locale overlays remain future work.

## Unreleased — Bounded pack semantics and generation gate (P04.3), 24 October 2026

- Add a statically analyzed, data-only expression language with depth/node/dice/overflow/division limits; typed effects with bounded conditional nesting and inherited timing; condition stacking, duration, modifiers and ticks; and quest definitions with world-scope predicates and outcomes.
- Detect ability-prerequisite, condition-tick and quest-dependency cycles with path diagnostics; check SemVer engine/ruleset ranges, modules and same-version hash conflicts against a local engine contract (ruleset 1.0.0 provisional until P07).
- Compile generation policies with opt-in categories, budgets, tags, templates, ranges and fixed/required/excluded author constraints. A deterministic `GenerationGate` rejects proposals that override them. Add an unreachable-location warning. ADR 0017 records the decisions.
- 19 compiler tests and the full pinned backend verification pass (198 tests, three profile skips). Encounter/anchor/progression kinds, runtime gate callers and import registration remain future work.

## Unreleased — Semantic pack compiler (P04.2), 24 October 2026

- Add `PackCompiler`, `CompiledPack`, `Definition` and a local `DependencyCatalog`: unique IDs, category/kind agreement, typed cross-references (including `packId#id` for declared dependencies), rule-defined attributes/resources/rarity tiers, unique item appearance identities, creation budgets, skills, abilities and initial world definitions, with closed objects and located diagnostics.
- Reject kinds without a semantic schema instead of ignoring them. Expression/effect limits, cycles and version compatibility stay in P04.3; archive protections and registration in P04.4. The 8-test compiler suite and the full pinned backend verification pass; no playable-pack admission is claimed.

## Unreleased — Contract-1 parsing foundations (P04.1), 24 October 2026

- Add a closed versioned manifest and preliminary definition-envelope JSON Schemas, explicit file categories and source-located diagnostics.
- Parse bounded UTF-8 YAML/JSON as immutable data without YAML object constructors, aliases, tags, merge keys or duplicate keys; preserve YAML 1.2 core typing and reject front matter in unrendered Markdown.
- Define sorted path/length/raw-SHA-256 framing covering all distributed bytes, including manifest/comments/assets, independently of map order or host metadata.
- Pass the 63-case parser/schema/hash corpus, including an independent digest vector and final-line-terminator regressions. Full pinned JDK 21/PostgreSQL 18.6 backend verification passes 176 tests with three packaged-interface profile skips (179 total) and builds the jar with both schemas; repository tests pass 22 and policy checks report zero findings. Keep semantic definition validation, archive/filesystem/media protections and immutable registration in P04.2–P04.4; no playable-pack admission is claimed.

## Unreleased — Durable preparation and fenced admission (P03.6), 24 October 2026

- Add V6 scoped jobs/attempts, immutable snapshots, versioned artifacts/dependencies, admission receipts, append-only dispositions, query epochs and campaign recovery generations.
- Persist finite attempt budgets, usage observations, cancellation and lease fences. Gate claims/admissions on startup reconciliation; require confirmed external stop before retrying expired work. Restore retires generations, leases and usable plans while preserving canonical counters and audit bytes.
- Check snapshot assembler freshness and the complete engine manifest; reject query phantoms, stale public locale/glossary tokens and unsupported semantic dependencies. Serialize admission and canonical invalidation by campaign; cancellation races under the job lock. Keep public locale preferences separate from canonical state.
- Commit critical jobs, query-epoch bumps and invalidation with the canonical state/event/receipt/outbox bundle; reject conflicting duplicate intent and roll back injected failures. Admission creates no canonical event, draw, reward or time advance.
- Pass full backend `verify` on pinned JDK 21/PostgreSQL 18.6: 113 tests pass, three packaged-interface profile skips, jar built; includes 17 preparation tests and populated V1→V6 upgrade. A V6 backup/restore and jar-startup rehearsal passes. Repository tests pass 22; backup tests pass 8 with one Windows symlink skip; default packaging tests pass 20 with two opt-in skips.
- Keep dispatch, fairness/global quota enforcement, runtime cancellation transport, full semantic dependency validation, gameplay consumption and full QA-36/37 for later packages. No hosted-CI or narrative-quality result is claimed.

## Unreleased — Leases, startup recovery and database backups (P03.5), 24 October 2026

- Add forward-only V5 campaign mutation leases with a persistent fence that only grows, database guards against rewinds or unfenced owner changes, and a recovery index. `MutationLeaseService` grants, renews and releases leases using PostgreSQL time.
- Require the current lease token for new draws and canonical commits, after immutable replay. Release the lease in the commit transaction, so a late or expired holder cannot commit.
- Reconcile the journal at startup, before the web server starts, in bounded pages: retire expired leases, keep live ones, fail orphaned precommit work and mark interrupted narration pending without repeating effects. A recovery error aborts startup.
- Add `tools/database_backup`, a trusted-owner `pg_dump`/`pg_restore` tool with a version, catalog and checksum manifest. It restores only into a new database and retires saved leases; its offline tests run in CI.
- Pass the full offline backend verify on pinned JDK 21/PostgreSQL 18.6 (95 tests pass, three packaged-interface profile skips, jar built) and 9 backup-tool tests (one symlink skip). A manual backup/tamper/restore/startup rehearsal also passed. Repository-checker tests pass 22/22, default packaging checks pass 20/22 with two opt-in skips, and policy checks report no findings.
- Assets and content in backups, worker cancellation, durable jobs and full QA-17/QA-23 remain later work; hosted CI for this change is unconfirmed.

## Unreleased — Deterministic draw reservations (P03.4), 24 October 2026

- Add private lazy campaign seeds and immutable operation/ordinal draw records in forward-only V4, with scoped foreign keys, uniqueness and bounds. Preserve historical outcomes without backfilling randomness.
- Pin HMAC-SHA-256 v1 binary framing and unbiased rejection sampling. Reserve in a separate bounded transaction without world/journal changes; replay matching retries, reject changed bounds and new stale/cancelled/committed allocations.
- Add backend-owned ordered draw references/values to canonical receipts; keep seeds private and retain reserved values after failed canonical commits. No public draw endpoint or speculative planner access is exposed.
- Pass full offline backend verification on pinned JDK 21/PostgreSQL 18.6: 81 tests pass, three packaged-interface profile tests skip, and the executable jar builds. Cover independent vectors/rejection, maximum receipt capacity, concurrent reuse, cancellation, allocation/commit rollback, fresh-service reuse, database constraints and populated upgrade. Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two opt-in skips, and repository policy checks report zero findings. Dependency pins are unchanged.
- Equivalent-new-attempt reroll policy, gameplay authorization, leases/fencing, backup/restore and full recovery acceptance remain later work; hosted CI for this change is unconfirmed.

## Unreleased — Atomic canonical commits (P03.3), 24 October 2026

- Add an internal bounded commit service for world counters/mode/decision, resource changes, sequenced events, reward settlements, journal receipts and public-state/narration outbox intents in one database transaction.
- Lock scoped campaign and journal rows, recheck revision/content/resource versions, replay the exact stored receipt on duplicate completion, and reject ambient transactions. Accept immutable resolved data without network clients or executable callbacks; use bounded lock, statement and transaction timeouts.
- Add forward-only V3 with immutable reference-only outbox rows, unique operation/kind keys and a deferred campaign/operation/committed-revision foreign key. Preserve historical outcomes without fabricating delivery work on upgrade.
- Pass full backend compilation, tests and jar build against PostgreSQL 18.6: 72 tests pass and three packaged-interface tests skip outside their profile. Cover every write boundary and deferred-commit rollback, duplicate/competing operations, intervening resource writes, scope/version guards and populated upgrades. Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two opt-in checks skipped, and repository policy checks report no findings. Dependency pins are unchanged.
- Command/API authorization and semantic rules, deterministic draws, leases/recovery, outbox delivery and full QA-20/QA-33 acceptance remain later tasks; no gameplay endpoint is exposed.

## Unreleased — Campaign constraints and storage versions (P03.2), 24 October 2026

- Add forward-only V2 with bounded actor resources, campaign/actor foreign keys and unique resource keys; freeze ownership and provenance after insert.
- Add independent optimistic row versions to seven mutable tables, prevent counter rewinds, require complete write-once journal outcomes and one committed operation per campaign revision, and bound stored JSONB payload bytes.
- Verify repository scope, raw SQL constraints, inclusive resource/payload limits, stale/concurrent writer rollback, simultaneous duplicate keys/reward sources and populated V1-to-V2 upgrade preserving canonical counters.
- Pass full backend compilation, tests and jar build against PostgreSQL 18.6: 43 tests pass and three packaged-interface tests skip outside their profile. Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two opt-in checks skipped, and repository policy checks report no findings. Dependency pins are unchanged.
- Application authorization, atomic command/outbox commits, deterministic draws, leases and recovery remain later tasks; this establishes persistence foundations only.

## Unreleased — Canonical foundations (P03.1), 24 October 2026

- Add Spring Data JPA and the first Flyway migration with ten tables for owner profile, adventure workspaces, content revisions, campaigns, presentation settings, actors, the operation journal, campaign events, reward settlements and checkpoints; Hibernate validates the mapping at startup.
- Enforce explicit ownership in PostgreSQL: campaigns pin a content revision of their own adventure, campaign-owned rows use composite campaign foreign keys, idempotency keys and reward sources are unique per campaign, and events, revisions, checkpoints and settlements are immutable.
- Keep canonical counters on the campaign row and narrative locale, glossary and media generations in a separate presentation table; implement the docs/07 operation lifecycle and the en/it locale allowlist.
- Add 21 backend tests against real PostgreSQL 18.6 and in memory. No service, API or UI uses the tables yet; version fields, bounds, atomic commit, random draws, leases and recovery remain P03.2–P03.6.

## Unreleased — Launcher lifecycle verification (P02.7), 24 October 2026

- Add the opt-in `test_lifecycle.py` and a sanitized Windows report. On Windows 11 with Docker Desktop and Git Bash it verified repeated launch (containers and password unchanged), a 3.5 s graceful stop (exit codes 0, 0 and 143 for the engine), restart and image-recreation preservation of neutral database and content markers, and exit 130 after interrupts during build and startup.
- Not verified: an interactive Ctrl+C, Linux/macOS hosts, WSL, owner sign-in, a cold build, real campaign data and engine draining of in-flight commands. The check is partial QA-28 evidence; no application behavior changed.

## Unreleased — Root launcher (P02.6), 24 October 2026

- Add the root `start.sh` (Bash 4.4+, mode 100755): prerequisite checks against the documented Docker floors, one-time `.env` and random database-secret creation, cached builds, health-based startup, and `--check`, `--status`, `--logs`, `--stop` and `--login` commands with nonzero exit codes on failure.
- Never regenerate or print the database secret, refuse to start when it is missing while the installation's database volume exists, redact credential-like values from logs, and stop without removing volumes.
- `--login` only runs the supported device-code flow in an isolated one-off container for the owner; it never reads credential files or enables paid fallback. Gameplay readiness stays unclaimed.
- Add 16 launcher tests. Manual Docker Desktop checks covered first start, repeat start, restart with data kept, busy port and unreachable daemon; Linux/macOS hosts, completed sign-in, interrupt timing and hosted CI for this commit are not verified.

## Unreleased — Hosted CI verification (P02.4), 24 October 2026

- Confirm [hosted run 40028086153](https://github.com/AjimuZ/ai-rpg-engine/actions/runs/40028086153) on commit `09a6311d9557be01f2fee9abeb119206f0b02e34`: all four repository, frontend, backend and container jobs completed successfully. Record its exact source revision and completion time, complete P02.4 and remove the stale manual-confirmation blocker.
- Verify hosted evidence through read-only GitHub run/jobs queries; change no application or workflow code and make no new gameplay, launcher or account-readiness claim.

## Unreleased — Ordinary credential-free CI (P02.4), 23 October 2026

- Add a GitHub Actions workflow with read-only permissions, no secrets, SHA-pinned
  actions and four jobs: repository checks, frontend build/test/audit, backend
  build/test against a job-local PostgreSQL, and container context/Compose checks.
- Add stdlib-only repository checks (secret and private-path scan, Markdown links
  and fences, workflow policy, image pins) with 22 unit tests.
- Make `PackagedSecretsTest` independent of exported `SPRING_DATASOURCE_*` variables, which
  otherwise override the mounted secret file and failed the backend build under CI-style
  environment settings; the job was replayed in JDK 21 and PostgreSQL 18 containers.
- At this implementation checkpoint the hosted run was unconfirmed; the later hosted-verification entry above supplies that evidence. The dependency license inventory remains P19.1.

## Unreleased — Containerized application shell (P02.3), 23 October 2026

- Build the packaged Angular interface and Spring Boot application in pinned
  Node/JDK stages; ship only the executable jar and bundled notices in a
  non-root JRE image.
- Add the engine to Compose with loopback HTTP, private database/runtime
  boundaries, read-only IPC, file-based database secrets, and durable content
  and asset volumes. Keep private development data outside build contexts.
- Verify the three-service shell with five packaging checks, nine backend
  tests, frontend checks and 116 runtime checks. Recreation preserves neutral
  storage markers; missing secrets fail startup. Full launcher/account/campaign
  acceptance (QA-28), native Linux secret permissions and Java release refresh
  remain separate gates.

## Unreleased — Bounded dispatch and serialized inference (P01.7), 23 October 2026

- Add a bounded per-thread/turn inbox fixture using the real managed runtime
  and internal scripted upstream; preserve interleaved events and verify
  targeted interruption, unaffected active work and terminal/stream closure.
- Retain five neutral scripted turns and local cancellation observations;
  exercise interrupt/reconcile/dispatch priority with one active turn.
- Explicitly select one active inference turn installation-wide: live
  simultaneity, headroom, quota behavior and service priority remain unverified.
  Scripted overlap provides no live throughput claim; P08 owns production
  admission/recovery, and P08.6 gates any increase. No account is accessed.

## Unreleased — Explicit image capability boundaries (P01.6), 23 October 2026

- Record managed-runtime image unavailability, no supported invocation/output
  formats, unverified account entitlement/cost and text/catalog fallback scope.
- Include separate image diagnostics in discovery and add an offline report plus
  unconditional generation refusal before runtime/account access. No paid access,
  live image call, account mount or production job/asset pipeline is introduced.
- Test policy/format refusal, account-independent text discovery, CLI isolation,
  managed profile switches and hostile image function calls in the pinned runtime.

## Unreleased — Competitive research and bilingual narrative design, 23 October 2026

- Inspect five competitor repositories at recorded revisions, source/test definitions, dependencies, issue trackers and license texts. Add a traceable comparative/gap register; adopt patterns rather than copying code, media or campaign content, and distinguish observed mechanisms from unmeasured quality claims.
- Specify a durable shared-runtime priority queue, private versioned quest-plan artifacts, complete read/query dependencies, transactional admission, cancellation/recovery, fair budgets and a playable no-plan baseline. Preserve backend authority, tool-free runtime isolation and Codex-only gameplay; higher inference concurrency and narrative review remain evidence-gated.
- Require English-default and first-class Italian from the first playable release. Define localized Angular variants, safe preference reload, text-only pack overlays, direct selected-language generation, stable terminology and immutable historical language.
- Research local speech code/weights/voice rights and languages; conditionally select Qwen3-TTS 0.6B Base with a Kokoro CPU/ONNX fallback, pending exact distributable rights, EN/IT quality and target-hardware tests. Define persistent voice identities, validated-line playback, cache/restore and bounded GPU admission; no speech model is installed or benchmarked.
- Add explicit person-to-person story-bundle import/export with compatibility, locale, dependency, hash and credit manifests; retain private-playthrough/account boundaries and the separate later editor E01–E06.
- Add ADR 0014–0016, FR-033–037, NFR-013–015, QA-35–41, an unexecuted evaluation protocol and an Italian executive report. Integrate eleven unchecked implementation/qualification tasks into P00–P19, preserving all twelve verified completion boxes. Advanced planning/review and speech do not become hidden MVP blockers.
- This change delivers research and documentation only. Validation checks sources/references, links/anchors, traceability, task dependencies/status and the documentation-only diff; it establishes no new gameplay, concurrency, speech or narrative-quality result.

## Unreleased — Protocol fixtures and compatibility records (P01.5), 23 October 2026

- Retain projected account-free 0.164.0 request/result exchanges and terminal events with identifier aliases and neutral error text, plus a bounded-session capture command and offline contract replay.
- Record exact executable/image/client/host scope, service maturity, unsupported architectures/platforms and upgrade checks. Keep live isolation quota, authenticated packaged restart, image capability and full application acceptance gates explicit.
- Verify the documented capture command, all 98 runtime tests and all five packaging tests with Docker enabled and no skips, four Compose configurations, Python syntax/docstrings and local documentation links/anchors. Mark P01.5 complete; retain the remaining package and application gates.

## Unreleased — Runtime process lifecycle (P01.4), 23 October 2026

- Add owned-child shutdown records and bounded escalation scoped to a unique container name; enable runtime Compose init for signal forwarding. Keep Docker control outside the backend.
- Make terminal wire failures sticky, retire unresolved RPC generations, retain ordered early notifications, and classify stderr within fixed byte/line/tail bounds with allowlisted diagnostic labels.
- Add synthetic failure-injection and real pinned-runtime tests for process exit, ignored EOF, service stop, upstream interruption, relay-loss reconciliation and turn deadlines. Results on Windows 11 / Docker Desktop / `linux/amd64`: all 90 runtime tests and 5 packaging tests pass with Docker enabled and no skips; four Compose configurations and documentation links/anchors pass. No account-backed inference or campaign-save acceptance is claimed.

## Unreleased — Narration runtime isolation (P01.3), 23 October 2026

- Bake an engine isolation profile into the 0.164.0 runtime image: a managed configuration layer, an engine model catalog, neutral instructions, requirements pinning `never`/`read-only`/no search with no extension servers, and a startup guard refusing inherited home configuration or instruction files ([ADR 0013](docs/adr/0013-narration-runtime-isolation.md)).
- Add `test_isolation.py` with Docker fixtures. They prove an empty model tool list, six refused host tool calls with no canonical write, rejected or clamped wider thread policies, and ignored inherited configuration and launch flags. The guard refuses each inherited home file. Turns containing non-narration items now fail closed.
- Results: all 59 probe tests pass with Docker enabled, and the packaging tests pass, including recreation. A live re-check on the rebuilt image hit the account usage limit and remains open.

## Unreleased — Device-code login prerequisite, 23 October 2026

- Explain the ChatGPT security-setting/workspace permission required by the project's verified container device-code login flow in setup and integration guidance. Require actionable launcher guidance in P02.6–P02.7; keep other upstream login methods distinct and retain owner-controlled account setup.

## Unreleased — Authenticated neutral lifecycle completion (P01.2), 23 October 2026

- Verify one live neutral task after owner-operated device-code login in a dedicated runtime volume. Explicit `gpt-6-luna` / `low` settings produce a correlated completed envelope accepted by the independent validator; a second stdio process resumes the same completed turn and settings.
- Retain a sanitized runtime 0.164.0 report without account identifiers, credentials or thread/turn IDs. Rerun all 51 protocol tests with Docker enabled: all pass with no skips. Complete P01.2; deeper isolation, packaged startup, gameplay behavior and QA-08/QA-28 remain open.

## Unreleased — Development packaging prerequisite revision, 23 October 2026

- Permit local development image builds on the existing exact official Java 21.0.12.1 pins while the updated official images are unpublished. Retain the same distribution, base, digest checks and packaging acceptance; require the newest published Java update before release.
- Recheck both exact image index digests and the official tag API. This documentation revision unblocks further P02.3 work; it does not deliver an engine image, complete P02.3 or validate signed-in inference. Earlier checkpoint entries below retain their historical gate descriptions.
- Document owner-operated device-code sign-in in a dedicated runtime volume, keeping the development account separate; login and live neutral inference remain distinct checks.

## Unreleased — Packaged file-secret configuration (partial P02.3), 23 October 2026

- Add an opt-in Spring runtime profile requiring a file-secret config tree for the database password, with container HTTP binding and no fallback password; retain loopback host-development defaults.
- Verify actual configuration loading, newline handling, paths with spaces, host defaults and missing mount/file failures using neutral account-free tests. All nine packaged backend tests pass; a non-root executable jar authenticates from a mounted secret and serves exact compiled index/notices bytes. Engine image/service wiring remains gated by the official Java refresh; full packaged startup and QA-28 remain open.

## Unreleased — Single-origin jar assembly (partial P02.3), 23 October 2026

- Add an opt-in Maven profile embedding compiled Angular production assets and extracted third-party notices in the executable backend jar, served at one origin without an additional server.
- Verify the real HTTP index, bundles, notices and missing API/resource 404 responses against disposable PostgreSQL. Guard missing index/notices before packaging; keep ordinary backend builds independent of frontend output. Engine container packaging remains gated on the mandatory official Java image refresh; no launcher or full QA-28 acceptance is claimed.

## Unreleased — Health and readiness contracts (P02.5)

- Add liveness, storage-readiness and separate gameplay-readiness health groups, a schema indicator, and actionable secret-free startup failure descriptions for database and migration problems.

## Unreleased — Isolated infrastructure (partial P02.3), 23 October 2026

- Add root Compose database/runtime services with separate networks, file-based database secrets, durable private storage, tmpfs control IPC, bounded logs, graceful stop and process/socket health checks.
- Add rendered-configuration boundary tests and an opt-in account-free Docker test proving password-authenticated database connectivity, network/UID/socket checks and neutral storage preservation across container recreation. Stop test services while retaining volumes and fixture secrets. Engine packaging and the single-origin interface remain pending the official Java image refresh; full startup and account readiness are unverified.

## Unreleased — Build-context isolation (partial P02.3), 23 October 2026

- Add default-deny root and independent runtime Docker context policies, excluding private configuration, credentials, data, Git history and generated output, including nested private paths.
- Add two real BuildKit export regression tests using neutral isolated fixtures. Engine image/Compose packaging remains pending because ADR 0009 requires official Temurin 21.0.13 or later images that are not yet published; no packaged runtime or persistence claim is made.

## Neutral development shells (P02.2), 23 October 2026

- Add pinned Angular and Spring Boot workspaces without adventure content, with strict frontend compilation, external PostgreSQL/Flyway configuration, and minimal status-only backend health.
- Add default shell tests and reproducible host build commands, checksum-verified Maven Wrapper policy, Central-only resolution, and explicit npm install-script policy. Pin transitive Vite 8.3.5 to resolve development-tool advisories without changing Angular; Windows/Linux locked installs, typechecks, default tests and production builds pass, as do full npm audit and the Java 21/PostgreSQL 18 backend tests and jar build. Packaged startup and canonical migrations remain later tasks.

## Unreleased — Repository policy and environment placeholders (P02.1), 23 October 2026

- Add shared EditorConfig defaults for UTF-8/LF, final newlines and indentation, preserving Markdown hard breaks; retain Git text normalization and binary image handling.
- Keep the owner-approved MIT license and contribution terms, document formatting/configuration policy, and ignore Python probe caches alongside private configuration and build output.
- Add neutral, credential-free environment placeholders with no current consumer. Application scaffolding, packaged startup and CI enforcement remain later P02 work.

## Unreleased — Thread and turn protocol fixture (partial P01.2), 23 October 2026

- Add a test-only scripted upstream on an internal Docker network, exercising seven real runtime turns, two threads and reconnect/resume without credentials. Verify explicit per-turn model/effort and full output schema at the upstream boundary, correlation, terminal outcomes and interruption of active streams.
- Bound and retain early notifications, deny unsolicited privileged requests, validate effective thread policies and redact unknown error variants. Add regression checks for malformed events, missing output, queue overflow, deadlines and the opt-in live profile's account/volume gates.
- Document reproducible checks and an owner-only neutral smoke profile. P01.2 remains open because signed-in inference has not run; production concurrency, deeper isolation and runtime restart remain separate gates.

## Unreleased — Pinned narration runtime and discovery probe (P01.1), 23 October 2026

- Add the narration runtime image `docker/codex-runtime/Dockerfile`. It installs runtime release 0.164.0, verified by checksum, on a digest-pinned Debian 13 base, runs as UID 10001, and listens on a private Unix control socket. ADR 0009 records the pins.
- Add `tools/runtime_probe`, a developer-only probe. It performs the handshake, the account-state read, and paginated catalog discovery over stdio, on the host or in a container, and over the packaged Unix socket through a Java 21 WebSocket relay. It also validates the output contract strictly and offline. It is not engine code and never signs in.
- Record the verified transport facts: WebSocket framing over the socket, the real socket location, and the `0600` UID boundary. Record the remaining gaps too: sign-in, live inference, the sandbox fallback, and `linux/arm64`.

## Unreleased — Later editor and customization plan, 23 October 2026

- Plan the integrated text/graphical adventure editor after core v1 using a separate E01–E06 checklist, with fixed author requirements, mandatory characters and bounded completion of unspecified details.
- Specify genre-independent fantasy/cyberpunk authoring and customizable combat data/policies around the default ruleset, preserving supported kernel boundaries and frozen encounters.
- Add QA-34 and align manual authoring, UI, requirements and roadmap semantics. No editor or new combat algorithm is implemented.

## Unreleased — Independent adventure-workspace design, 23 October 2026

- Separate personalized adventures from playthroughs/checkpoints, with managed folders, scoped specifications, drafts, memory and generated art.
- Add ADR 0012, proposed library/API/storage contracts, FR-032 and QA-33, and explicit implementation/restore gates. No runtime workspace is created by this change.

## Unreleased — Dynamic music design, 23 October 2026

- Specify optional event-driven music, reviewed free-license online/local sources, deliberate activation, stable loops/crossfades, privacy controls and failure fallback.
- Record two reference-only tracks with creator/source and distinct CC BY 4.0/3.0 credit templates; no audio is downloaded, bundled or implemented. Add P17.2/QA-32 acceptance work and third-party reference notices.

## Unreleased — Visual-novel and persistent-artwork design, 23 October 2026

- Make visual-novel play the default interface direction, with attributed narrator/character/player passages, editable contextual quick replies, a persistent free-input composer and a complete text-focused alternative.
- Add a primary narrative-screen concept and its prompt/provenance; retain the older illustration as a combat-screen concept. These are design artifacts, not running UI or separately usable production assets.
- Accept ADR 0011 and specify a complete UI graphic kit, stable saved character/item artwork, accessible item rarity and bounded runtime image generation for v1 with verified capability, budget and fallback boundaries.
- Align requirements, domain/content/narration contracts and P01/P05/P17 implementation tasks with QA-29–31. No interface, image-generation pipeline or rarity mechanics are implemented by this documentation change.

## Unreleased — Name, v1 boundaries, pack file format, and licensing plan (P00.5), 23 October 2026

- Add accepted ADR 0010. It records the product name AI RPG Engine and the identifiers derived from it (slug and Compose project `ai-rpg-engine`, Maven `io.github.ajimuz:ai-rpg-engine-backend`, Java package `io.github.ajimuz.rpgengine`, ruleset ID `skirmish`), and it confirms the MVP and v1 boundaries without change.
- Fix pack contract 1 (`schemaVersion: 1`): UTF-8 without a byte-order mark; YAML 1.2 core-schema `.yaml` files with no anchors, aliases, merge keys, tags, or duplicate keys; equivalent `.json`; CommonMark `.md` prose with no front matter or rendered HTML; JSON Schema draft 2020-12; directory or `.zip` distribution.
- Record the licensing plan: MIT for the engine and the neutral sample packs, third-party packs under their own terms, a permissive allowlist for bundled dependencies, locally built images that are not redistributed, and the name's trademark review left for P19.1.
- Align the pack specification, ADR 0004, the ADR index, risks and open decisions, publication guidance, and the README. No parser or application code is included.

## Unreleased — Default combat ruleset confirmation (P00.4), 23 October 2026

- Accept ADR 0006: AI RPG Skirmish is the implementable default ruleset, and every numerical default stays provisional until calibration in P13 and P18.3.
- Add a provisional numerical defaults register to the combat specification. It separates the confirmed structure (which needs a superseding ADR to change) from tunable values, gives each value its defining section and calibration gate, and requires the values to live in ruleset-profile data with versioned revisions.
- Record that the default advancement profile has no maximum level; a pack may declare one.
- Align the ADR index, risks and open decisions, gameplay, encounter, worked-scenario, and README wording. No rules code is included, and no balance is claimed.

## Unreleased — Host, container image, Compose, and shell baseline (P00.3), 23 October 2026

- Extend ADR 0009 with the first validation target: Windows 11 x64 with Docker Desktop (WSL 2 backend, Linux containers) and Git Bash. Bash in a WSL distribution becomes a candidate; other hosts remain unclaimed.
- Pin by multi-platform index digest: Dockerfile frontend 1.27.1, `node:24.22.1-trixie-slim`, and Eclipse Temurin `21.0.12.1_1-jdk-resolute` / `-jre-resolute`. Require a refresh to the Temurin 21.0.13 images before the first engine image.
- Set the launcher floors (Docker Engine 29.0, Compose 5.0, Buildx, GNU Bash 4.4), the allowed host commands, the Git Bash path-conversion rules, and a digest-refresh policy for every image.
- Record a throwaway Compose probe from Git Bash: an Angular build, a Maven Wrapper build, and a Spring Boot JRE runtime against PostgreSQL 18.6. It covered health-gated startup, secrets, an internal network, and a loopback-only port, and its findings became rules.
- Add the image-refresh risk and align the architecture, deployment, risk, README, and ADR index baselines. No container, launcher, or runtime file is included.

## Unreleased — Backend and database baseline (P00.2), 23 October 2026

- Extend ADR 0009 with Spring Boot 4.1.2 on Java 21, the libraries managed by Spring Boot (including Flyway 12.4.0 and PostgreSQL JDBC 42.7.14), Maven 3.10.0 through an `only-script` Maven Wrapper 3.3.4 with a distribution SHA-256, and PostgreSQL 18.6 (`postgres:18.6-trixie` pinned by digest) with matching `pg_dump`/`pg_restore`.
- Record the evidence: support schedules, Maven Central metadata, and a throwaway wrapper build on a JDK 21 against PostgreSQL 18.6. The build covered checksum and toolchain enforcement, four integration tests, Maven Central artifact origins, and a dump/restore round trip.
- Record the implementation rules found by the probe: `unzip` for Linux wrapper runs, Maven Central-only resolution despite Flyway's extra repository declarations, and the PostgreSQL 18 volume path.
- Add the backend support-window risk, and align the architecture, deployment, risk, and README baselines. No backend workspace or runtime code is included.

## Unreleased — Frontend toolchain baseline (P00.1), 22 October 2026

- Add ADR 0009 pinning Angular 22.2.3, TypeScript 6.0.3, Node.js 24.22.1 LTS, npm 11.19.1, RxJS 7.8.2, tslib 2.8.1, and Vitest 5.0.5.
- Record the compatibility evidence: registry engines and peer ranges, support schedules, a conflict-free lockfile resolution, and the rejection of TypeScript 7.
- Record the workstation tool inventory; the host has Java 27, so the Java 21 baseline remains for P00.2.
- No frontend workspace or runtime code is included.

## Unreleased — Development branch policy, 22 October 2026

- Use `main` as the single project branch: verified implementation commits are pushed directly to `main`. A `develop` branch used briefly the same day was fast-forwarded into `main` and removed.
- Exclude force pushes and published-history rewrites.
- Require implementation commits to name their roadmap item in a final `Roadmap: <item>` body line.

## Unreleased — Container deployment design, 22 October 2026

- Record the owner requirement for a fully containerized local application launched through root `start.sh`.
- Specify the packaged topology, private runtime transport gate, account setup, durable volumes, readiness, and safe lifecycle behavior.
- Add FR-029, QA-28, ADR 0008, and corresponding implementation/release roadmap tasks.
- Align architecture, operations, integration, and publication guidance; no executable container or launcher files are implemented in this milestone.

## Unreleased — Development workflow, 22 October 2026

- Require immediate roadmap and affected-documentation updates after each completed development item or project change.
- Define neutral English commit-message conventions and align the roadmap's suggested messages.
- Record the distinction between local checkout enforcement, semantic review, and future continuous integration; private assistant configuration remains untracked.

## Unreleased — Project licensing, 22 October 2026

- Add the MIT License with copyright 2026 Azim Ben Othman.
- Document license scope, contribution terms, and separate ownership of adventure packs and third-party assets.
- Preserve the upstream MIT notice and resolve the pending repository-license decision.
- Update the publication guide with the private repository status and remaining public-repository preparation.

## Unreleased — Repository preparation, 22 October 2026

- Add Git exclusions for local assistant configuration, environment files, private runtime output, and generated artifacts.
- Normalize text line endings and preserve the UI concept image as binary.
- Document the initial commit scope and pre-commit review commands.

## Unreleased — Documentation baseline, 22 October 2026

- Adopt AI RPG Engine as the owner-selected project name and `ai-rpg-engine` as the project directory and repository slug.
- Document source-project maturity, reusable patterns, conflicts, and improvements.
- Specify Angular/Spring Boot/PostgreSQL architecture and a backend-owned Codex integration.
- Specify immutable adventure packs, canonical state, command transactions, knowledge boundaries, checkpoints, and renewable AI sessions.
- Design the original AI RPG Skirmish turn-based ruleset, bounded encounter generation, and advancement policies.
- Define API/UI behavior, security, recovery, testing targets, worked scenarios, and architectural decisions.
- Provide an ordered development roadmap through a complete initial engine and GitHub release preparation.

No application code, executable configuration, schema implementation, remote repository, or runtime test result is included in this milestone.
