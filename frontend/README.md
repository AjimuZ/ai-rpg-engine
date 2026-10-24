# Browser interface previews

This is a standalone Angular interface with SCSS, strict TypeScript/templates,
zoneless change detection, and no runtime calls or credentials. P05.1 provides
library, character-creation, story, sheet, inventory, journal and settings shells.
The library contains explicitly local Mosslight Trail and Silent Relay samples,
inspired by the neutral import fixtures; these are hand-authored display previews,
not registered workspaces or compiled pack projections. P05.2 binds these shells
to typed public metadata in `src/app/preview/content-metadata.ts`: terminology,
attribute/resource names and optional guide/creative-ability panel configuration.
Mosslight uses Awareness/Vigor and a Trail guide; Relay uses Logic/Charge, Crew
profile/Cargo/Mission log and Field techniques. Names are literal text; missing
labels fall back to neutral English (or a definition's stable ID). Navigation and
direct routes both respect optional module presence, including after scope changes.
This preview contract does not change the pack schema or execute pack modules;
registered pack/campaign projections remain P06. Current resource values are never
invented from definitions.

Open `/library`, `/settings`, or `/adventures/mosslight/story` (also `relay`).
P05.5 adds `/interface-kit`, linked from Settings, for the shared original MIT
SVG/CSS theme: control states, 20 icons, bounded numeric meters, progress,
dialogue/portrait/item frames, empty slots and independent sample item artwork.
Navigation/story controls, sheet placeholders and inventory use these primitives.
The [public source inventory](public/ui/README.md) and full MIT notice ship with
the build; no external asset/font dependencies or raster generation are used.
Review-page values/art are explicitly examples, never campaign data. Persistent
help disclosure provides keyboard/touch access without hover-only tooltips.

Each adventure has `create`, `story`, `sheet`, `inventory` and `journal` routes;
`guide` and `creative` render only when their public configuration is present.
Unknown adventure IDs show an unavailable screen. Creation validates a 1–60
character trimmed name and previews it without saving. Changing adventures clears
both the form and unsaved summary, including when returning to an earlier preview.
Sheet/inventory/journal
show explicit unconnected states, never invented authoritative statistics.
New adventure, pack import, campaign creation and Send are disabled until their
services exist. There is no playable campaign or continue-saved-game claim.

The default story uses separate original [vector scene assets](public/scenes/README.md)
and attributed public sample passages. Continue reveals only supplied text;
Chronicle shows already revealed passages. Reading positions remain independent
per sample while the tab is open. Settings adjusts story text from 15–24 px;
reload resets all preferences/positions. UI resources are currently English-only;
full localization, the complete graphic kit and safe presentation events remain
P05.5–P05.7. No submitted player dialogue is fabricated.

P05.3 adds a Settings layout choice: default scene-led visual novel or a narrow
text-focused column with a live scene description and no artwork requests.
Original [speaker portraits](public/portraits/README.md) resolve by scoped public
identity and retain live names/roles on failure. Chronicle lists revealed text
with attribution; text-size preferences apply to both current text and history.
Native keyboard controls keep visible focus, transferring it to the dialogue
when the last available passage disables Continue. No global shortcuts or motion
are used. Acceptance passes all 18 frontend tests, typecheck, production build
(325.63 kB), and audit (zero vulnerabilities). Chrome 155 checks Enter/Space,
final-passage focus/Tab, portrait/scene failures, text mode without artwork loads,
24px text at 320px and landscape sizes, and reduced-motion operation. Desktop,
mobile and text-mobile screenshots were visually reviewed. All 22 repository
tests pass. This is local reading acceptance; full QA-29 input/reconnect behavior
and an assistive-technology audit remain pending.

P05.4 adds an editable composer with up to three typed contextual quick replies.
Selecting requires an empty draft; explicit Clear draft permits replacement.
Selecting, editing, dismissing and pressing Enter in the textarea never submit,
advance reading or mutate a world. Dismissal restores focus to the composer.
The separate `StoryInputState` service guards adventure/campaign IDs, callback
epochs and monotonic canonical/presentation revisions; stale/duplicate deliveries
cannot overwrite context or drafts. Reading changes only presentation context.

Unsent text (4,000 UTF-16 code units) and selection offsets persist in versioned
same-origin localStorage, keyed by adventure/campaign ID. Samples use the reserved
`local-preview` campaign ID. Reload/navigation restores drafts; reading/layout
preferences remain tab-lifetime. Storage denial/quota/corruption shows a visible
fallback and preserves current edits in memory. Clear draft saves an empty value.
This device-local convenience has no cross-tab merge, backup or encryption claim;
latest device write wins. It stores no operations, credentials or hidden state.

Specific loading, offline/reconnect, refresh error, stale, clarification,
cancelled, narration-pending and restore-required states preserve last context
and editing. Browser online is only a hint and never enables Send/retry or replays
text. Campaign operation status/idempotency wiring belongs to P06/P07. P05.6
retains bilingual language switching and complete reload/reading-cursor acceptance.
Full QA-29/33/35 and assistive-technology review remain pending.

Use Node.js 24.22.1 and npm 11.19.1 from [ADR 0009](../docs/adr/0009-toolchain-baseline.md).

From `frontend/`:

```sh
npm ci
npm run typecheck
npm test
npm run build
npm start
```

The development server binds to `127.0.0.1:4200`. The default test command runs once
using Angular's Vitest builder and jsdom; it needs no browser or account.
The production output is `dist/engine-ui/browser/`. The backend's opt-in
[`packaged-ui` profile](../backend/README.md#single-origin-jar-assembly-p023)
embeds this output and the adjacent extracted license notices in one executable
jar; engine container integration remains P02.3.
No lint target exists; CI runs `npm ci`, `typecheck`, `test`, `build` and `npm audit` ([workflow](../.github/workflows/ci.yml)).

After building, run `node tools/browser-smoke.mjs` for a production-browser smoke
check. It uses installed Chrome on Windows by default; set `BROWSER_PATH` to an
installed Chromium executable elsewhere. No browser download or owner profile is
used. An ephemeral loopback server and fresh profile are stopped/removed on exit;
desktop/mobile screenshots stay under ignored `.local/scratch/`. The check covers
navigation, keyboard Continue, chronicle, character preview, reading settings and
unknown identities, and rejects page exceptions and nonlocal/action requests.
The P05.1 acceptance run passed all 11 tests, typecheck, build and audit, plus this
smoke check in Chrome 155.0.8059.85 at 1440×1100 and 390×844; screenshots were
visually reviewed. These checks do not establish full QA-29/30/35 acceptance.

P05.2 passes 15 tests, typecheck, production build and audit (zero vulnerabilities).
The browser smoke now also checks metadata terminology/resources, configured panels
and disabled direct routes, including responsive navigation at the same sizes;
all three screenshots were visually reviewed. Tests cover empty metadata, literal
markup, blank-label fallbacks and scope changes without fabricated resource values.
Repository tests pass 22/22 and all four policy scans report zero findings.

P05.4 passes 26 tests, strict typecheck/build and a zero-vulnerability audit.
Chrome desktop/mobile also checks keyboard suggestion editing/dismissal, native
multiline input, draft/selection reload, adventure isolation, offline/reconnecting
states and explicit clear, with no page exceptions or nonlocal/action requests.
The mobile composer screenshot was visually reviewed. All 22 repository tests
pass; campaign submission/reconciliation and full accessibility acceptance remain
later gates.

Vite is overridden to 8.3.5 to fix the development-server advisories in Angular
build's 8.3.0 dependency while preserving Angular 22.2.3; see ADR 0009.

All direct dependencies are exact pins and the npm lockfile is committed. Install
scripts are enabled for the reviewed build dependencies (including esbuild's native
binary validation); use `npm ci` only with the reviewed lockfile. npm 11.19.1 reports uncovered scripts in `@parcel/watcher`, `esbuild`, `lmdb`,
and `msgpackr-extract`; this workspace retains the default execution policy and
does not add a broad `allowScripts` grant. No blanket
`--ignore-scripts` policy is claimed. Review lockfile and lifecycle-script changes
when upgrading; optional platform binaries must remain in the lockfile for Windows
and Linux builds. No test-library helper images or browser downloads are used.
