# Compile-time localization resources

P05.6 now reviews 218 unique semantic messages across all local preview screens,
public sample metadata/passages/suggestions, neutral terminology, validation,
unsaved preview receipts, recovery states, graphic kit and accessibility text.
Chronicle ICU plurals cover zero/one/many; numeric meters format using the compiled
locale while their numeric ARIA values stay locale-independent. Proper adventure
titles, source icon identifiers, asset paths and user-authored drafts stay literal.
English templates and
`$localize` calls are authoritative; `messages.it.xlf` retains their exact source
alongside reviewed Italian targets. Review preserves meaning, units, identifiers,
proper names and legal attribution. This is resource review, not an independent
human bilingual playtest or screen-reader qualification.

From `frontend/`, run `npm run test:i18n` and `npm run build:locales`.
The latter extracts fresh English into ignored `.angular/i18n/messages.en.xlf`,
checks exact source/target ID parity, stale source text, empty targets, duplicate
IDs and named-placeholder occurrence counts, then compiles EN/IT variants with
missing/duplicate translations configured as errors. Placeholder order may change
to support Italian word order. Keep mixed-content whitespace unchanged; XML
pretty-printers can alter the source text beside a leading placeholder.
Checker fixtures also reject source whitespace drift, including added line breaks.

Use descriptive IDs such as `settings.textSize`. When changing English, update
its reviewed Italian source and target in the same change. Do not copy fresh
English over the catalog without reviewing the translations. Parser warnings
fail the check. Tests use Angular's pinned parser rather than a second XML
implementation or a new dependency.

Review output is `dist/engine-ui-localized/browser/{en,it}/`, with localized
document languages and base paths. The backend `packaged-ui` profile and engine
image consume these variants; `npm run build` remains the unlocalized development
preview. `node tools/browser-smoke.mjs
--locales` checks both review variants as well as the original English journeys;
it requires both builds. The test server exposes known preview routes only,
compares actual HTTP resource hashes and checks missing-resource 404s. It does
not establish Spring routing on its own. Add `--packaged-origin=http://127.0.0.1:PORT`
to run the bilingual journeys against an owned packaged fixture, including root
preference, unsupported/denied storage, direct locale links and query/hash preservation.
The packaging fixture runs this with `PACKAGING_BROWSER=1`; only loopback HTTP
origins are accepted and each browser uses a fresh temporary profile.

Review Settings offers English/Italian language selection. The preference is saved
as `engine.ui-locale.v1` on the device; draft bytes/selection remain under their
existing stable scope keys. `engine.preview-reading.v1` in tab storage restores
validated preferences and known passage positions. A one-use tab focus marker
returns keyboard focus to the equivalent language selector. Literal drafts are
never translated/submitted. All edited or restored nonempty drafts are checked
for storage loss/conflicting writes; unresolved recovery survives detachment and
blocks reload. Previous projection callbacks and reply references are invalidated
before navigation. Denied storage and unsupported saved locale values have
reviewed notices. The unlocalized default build explains this review-only scope.

Browser checks perform native keyboard EN→IT→EN reloads with two independent
draft scopes, selection, reading layout/size/positions, focus, unsupported locale
and storage denial. Unit tests additionally reject corrupt snapshots, pending
recovery, real campaign contexts, conflicting writes and obsolete callbacks.
The campaign view (`src/app/campaign/`) adds 77 messages with `campaign.*` IDs:
error codes (a closed table; unknown codes use one generic sentence), operation
statuses, receipt lines with named placeholders (`path`, `kind`, `resource`, `before`,
`after`, ...), narration and stream-reset reasons, command labels and the source-language
fallback notice. Numbers are formatted for the compiled locale only; resource keys and
destinations stay literal identifiers. `npm run check:i18n` now reports 433 messages. The [review kit](../../../docs/36-italian-and-screen-reader-review-kit.md)
lists every Italian target with its English source for the independent review and holds the screen-reader script; its
first-pass fixes are by the same author line and are not independent.
The Italian targets of those 77 messages (plus the
reworded `saved.readOnly`) had one author review pass on 28 October 2026, which corrected
11 targets (ambiguous `Ricevuta`, identical sentences for two narration reasons, `tempo di
gioco` for world time, `Estrazione` for a die roll, mixed verb/noun command labels and four
less idiomatic sentences). They have **not** had independent human review. `python tools/repository_checks/checks.py localization` fails when a backend
campaign error code has no message. Language reload of a real campaign scope requires
a verified `engine.campaign-sync.v1` record (cursor and operation identities, no text).
No browser journey was run for these screens; full QA-35 and independent
bilingual/screen-reader acceptance remain open.

`npm test` includes `src/app/italian-variant.spec.ts`, which imports this catalog as text,
loads its targets with `loadTranslations` before any application module is evaluated and
renders the routed screens and recorded real campaign replies in Italian. It fails when an
English-only catalog string, an uncatalogued English phrase, an unnamed control or a broken
ARIA reference appears in the Italian render, so a new template string without `i18n` or a
target that silently falls back to English is caught without a browser. Its serializer
mirrors Angular's parser for `ph`, `pc` and ICU text; adding another inline element to the
catalog makes it throw until the serializer is extended. After compiling, `build:locales`
runs `tools/localize-index.mjs`, which writes the reviewed `shell.brand` message into each
variant's `<title>` (Angular does not translate `index.html`); `npm run test:i18n` covers it.
The same spec also pins the two first-pass fixes (the overdue text with its space before the
clock placeholder, and the typographic apostrophe in the four audio-cue texts), because the
parity check and the render checks pass with or without them.
