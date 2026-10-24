# Compile-time localization resources

P05.6 now reviews 212 unique semantic messages across all local preview screens,
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
document languages and base paths. It is deliberately separate from the existing
`npm run build` output consumed by the backend. `node tools/browser-smoke.mjs
--locales` checks both review variants as well as the original English journeys;
it requires both builds. The test server exposes known preview routes only,
compares actual HTTP resource hashes and checks missing-resource 404s. It does
not establish Spring routing or jar/container localization.

Remaining P05.6 work: integrate real API errors/receipts and explicit source-pack
fallback when campaign transport is available; persist locale and safe reload with draft/selection, reading
position, operation/cursor reconciliation, focus and stale-language guards; and
integrate both variants and notices into real single-origin packaging checks.
Language selection remains unavailable until those requirements pass. The review
variants cover the local preview but are not packaged release variants.
