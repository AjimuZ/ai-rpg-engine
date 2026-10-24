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
Real API errors/receipts, explicit source-pack fallback,
operation/stream-cursor reconciliation and independent release acceptance
remain open. These samples create no operation/cursor; they do not establish
full QA-35 or independent human bilingual/screen-reader acceptance.
