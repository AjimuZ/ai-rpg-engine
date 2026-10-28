# English and Italian Localization

## 1. Status and supported release

P04.6 implements pack text catalogs and EN/IT fixture resources; P05.6 provides
separate EN/IT review variants for all local preview screens, with persisted device
language and verified safe local-preview reload. The fourth checkpoint adds localized
jar/container assembly and finite preview routes, verified against the actual image.
Operation/cursor reconciliation and narration remain later work. P03.1 stores
the owner's UI locale, each campaign's narrative locale and the locale, glossary
and media generations outside the canonical counters. English is
the default; English and Italian are both required for the first playable MVP
and v1. UI, errors, help, combat, inventory, quests, narration and accessibility
must work in both. No additional languages are required now. This supersedes the
English-only initial UI assumption in the earlier vision, under [ADR 0015](adr/0015-bilingual-first-release.md).

## 2. A small preference model

P05.2's typed local presentation metadata supplies scoped names/terminology and
neutral fallbacks. P05.6 localizes the local preview metadata and neutral terms
through compiled catalogs. Compiled review variants expose an English/Italian
selector with origin-local device persistence; pack catalog bindings remain P06.
Stable definition IDs remain separate from their displayed names.

| Value | Scope and purpose | Change behavior |
| --- | --- | --- |
| `uiLocale` | Owner preference, allowlist `en`, `it`; default `en` | Settings Language selector persists; safe UI reload |
| `narrativeLocale` | Campaign presentation preference, initialized from `uiLocale` | Defaults to the same language; optional campaign override, applied at the next idle decision boundary |
| `contentLocale` | Existing immutable pack source-language tag | Author metadata, not a player setting |
| `passageLocale` | Stored language of each accepted narrative passage | Immutable history; speech derives from this value |
| `localeRevision` | Monotonic campaign presentation configuration generation | Invalidates old narration/suggestion/audio jobs, not canonical world revision |

Expose one Language choice by default. Explain any active-campaign difference
and offer to apply the new language to future narration at the safe boundary.
The optional campaign override serves bilingual users; do not expose separate
NPC spoken-language, translator and TTS-language controls. In-world languages
are narrative content with comprehension rules, not UI locale identifiers.

Use BCP 47 identifiers, normalized against this allowlist: supported `en-*`
maps to `en`, `it-*` to `it`; other UI preferences fall back to `en` with a
visible notice. Do not silently change existing campaign language from browser
headers. Formatting profiles initially use `en-US` and `it-IT`, with an explicit
owner time-zone preference; stored timestamps are UTC and numeric/identifier
API values remain locale-independent. World time is a separate game value.
[Unicode CLDR identifiers and formatting](https://www.unicode.org/reports/tr35/tr35-79/tr35.html)
inform this mapping; supporting every CLDR locale is outside the release scope.

Changing these preferences never advances world time, RNG, game turn or
`stateRevision`. Finish or cancel/reconcile current narration before changing
`localeRevision`. Keep the accepted operation receipt and original transcript;
do not replay the action or translate historical records silently. A new,
language-scoped thread/context generation handles future output.

## 3. UI resources and deployment

Choose Angular's built-in extraction and compile-time localized variants with
stable semantic message IDs. Keep source English and reviewed Italian XLIFF
resources, ICU plurals/selects and placeholder types. P05.6 pins `@angular/localize`
to the existing Angular 22.2.3 baseline. Localized packaging/recreation checks pass;
local preview language-switch restoration is browser-verified. Mark missing required translations as build failures. Avoid string
concatenation, inline hardcoded error prose and HTML returned by a model.

The second P05.6 checkpoint reviews 212 unique messages covering every local
preview screen, public sample passages/metadata/suggestions, neutral terminology,
validation, unsaved receipts, recovery states, kit, fallbacks and accessibility.
Adventure titles, stable IDs, asset paths, technical icon inventory identifiers
and user drafts remain unchanged. Chronicle counts use reviewed ICU zero/one/many
branches, and numeric meter text uses the compiled locale without changing numeric
ARIA/protocol values. `npm run build:locales` checks fresh extracted IDs, exact source freshness,
nonempty Italian targets and placeholder occurrence parity before compiling review
variants into `dist/engine-ui-localized/browser/{en,it}/`. Missing/duplicate
translations are build errors. [Resource instructions](../frontend/src/locale/README.md)
describe the review scope and tests. Campaign error codes, operation statuses,
receipts, stream notices and the source-pack fallback disclosure are bound to the
real campaign endpoints (see [the integration status](#p056-campaign-integration-status-28-october-2026)).
The `packaged-ui` Maven profile and
engine image now consume this localized production output. The optional default
`npm run build` remains an unlocalized development preview; full QA-35 stays open.

Build `/en/` and `/it/` static variants into the same Spring application artifact.
`/api/v1` and authorized asset routes remain unlocalized. Root entry chooses the
saved locale or English, never redirects POST/API requests and never sends a
missing resource to a misleading success page. Preserve notice files in both
variants and test exact resource hashes/404s. P05.6 extends the P02 single-origin
packaging checks. The root selector reads only the allowlisted device locale,
defaults to English on missing/invalid/denied storage and preserves query/hash.
It never reads browser language headers or changes persisted drafts. Native EN/IT
links remain available without scripting. Known unprefixed preview GET routes use
the same selector; direct locale links override the saved preference for that visit.
Spring serves only the finite preview route grammar and leaves missing assets,
unsupported locale prefixes and API routes as 404s; POST requests never select a locale.
Locale indexes are sent with `no-store`; content-hashed bundles remain static.
Angular copies `src/index.html` into each variant changing only `lang` and the base href, so
`npm run build:locales` finishes with `tools/localize-index.mjs`, which writes each variant's
reviewed `shell.brand` message into its `<title>` (`Motore di avventure` for `it`). The Italian
page therefore never opens with an English tab title or first screen-reader announcement,
before the router sets a route title or when scripting is off. The step validates both indexes
(`lang`, exactly one plain title) before writing either and is idempotent.
Shared artwork and project notices retain stable root URLs and are also available
under both locale prefixes. Extracted dependency notices ship at all three roots.

Angular documents [compiled variants](https://angular.dev/guide/i18n/merge) and
[locale subdirectories](https://angular.dev/guide/i18n/deploy). Its
[`loadTranslations` API](https://angular.dev/api/localize/loadTranslations)
does not retarget already-processed messages without a page refresh. Therefore
the selected design uses an explicit safe reload, not a promised instantaneous
runtime language switch. A third-party runtime translation framework is deferred
unless a measured requirement makes this approach inadequate.

P05.4 supplies the local foundation: versioned adventure/campaign
scoped draft text and selection persistence across reload, without submission.
Reading preferences/positions now persist in validated, versioned tab storage.
Only known preview IDs, bounded passage indexes, supported layouts and integral
15–24 px sizes restore. Corrupt snapshots leave defaults and a visible notice;
storage failure keeps the current in-memory view and blocks language reload.
No operation is created or persisted by the preview composer.

The compiled review variants offer an explicit Settings reload that verifies
all edited scopes' exact draft bytes (including selection), persists reading
state and a one-use equivalent-control focus marker, then saves `engine.ui-locale.v1`
on the device. Unsupported stored tags produce a visible English-default notice;
supported region tags normalize to `en`/`it`. Persisted data never supplies a URL.
The trusted same-origin `/en/` or `/it/` prefix changes while route/query/hash stay
intact. Direct locale links remain explicit; packaged root entry selects the saved locale.
Old callbacks and suggestion objects are invalidated before navigation; drafts
are never translated or submitted. Reload refuses unsaved/conflicting drafts,
unresolved local recovery even after detachment, a required campaign restore, and any
real campaign context whose operation/cursor record cannot be written and read back
exactly (P05.6 integration status below). The unlocalized default build explains that
switching requires the compiled review variants.
Nine reload/storage tests and native Chrome EN→IT→EN journeys verify these local
guarantees, including unsupported saved values, storage denial, two scoped drafts,
selection, restored native text-size selection, layout, reading position and focus.
The catalog now contains 317 messages: the earlier 218 plus 77 campaign messages
whose Italian targets are first drafts awaiting independent human review (the
integration status below). The second, packaged-origin checkpoint
is described there as well.

The fourth checkpoint runs the same bilingual journeys against the real Spring
container, including root missing/Italian-region/unsupported/denied preferences,
explicit locale links and opaque query/hash preservation. Four packaged HTTP tests
compare locale bundles, shared artwork and notices to actual classpath bytes,
probe deep links and reject POST redirects/missing-resource fallbacks. The full
PostgreSQL-backed backend suite and 22 container/context/launcher checks pass;
the separate opt-in launcher lifecycle test skips. Real resource SHA-256 maps match
before/after service recreation. No account or narration request is involved.

P05.5 adds the graphic-kit review and credits/source inventory.
Its control, meter, rarity and fallback labels remain live text beside decorative
SVG geometry; no translated text is embedded in artwork. P05.6 review variants
localize those labels, review examples and accessible names with the other screens.

Before reload, persist the adventure/campaign-scoped input draft, operation ID,
last stream cursor and reading position. Wait for or reconcile an outstanding
operation, restore by IDs and deduplicate messages. Do not submit the draft.
Stop active speech and invalidate quick replies from the previous locale. Keep
keyboard focus on the equivalent settings control after reload. Engine buttons,
graphics labels, alt text, ARIA labels, tutorials, setup/status/error guidance
and credits navigation are localized; artwork contains no essential baked text.

API errors carry stable `code`, typed parameters and optional field reference.
The client resolves reviewed translations. Unknown codes show a safe translated
generic message and correlation ID, not raw exception/RPC text. Backend-generated
receipts use the same versioned terminology catalog; language is not inferred
from an error string. Format counts, dates, decimal separators and units with
locale tools without changing numeric command payloads or mechanics.

## 4. Pack localization contract

Pack contract 1 retains `contentLocale`; compilation supports exact `en` and `it`.
Both additions are optional: `localizations` maps `en`/`it` to arrays of explicit
manifest-relative overlay paths, and `textResources` names one source-owned text
and policy file. List these files under the `localization` and `textResources`
categories, respectively. Every such file must be bound, and an overlay path
cannot be reused across locales. Each locale permits 1–64 distinct paths. Existing
single-language inventories retain their original hashes. Added or edited files
produce a new revision through the existing raw-byte hash protocol; no digest
normalization change or hot reload is introduced. YAML/JSON/path/staging budgets
continue to apply. The closed [overlay schema](../backend/src/main/resources/pack-contract/1/localization.schema.json)
and [source-resource schema](../backend/src/main/resources/pack-contract/1/text-resources.schema.json)
are bundled with the backend.

An overlay has `{schemaVersion: 1, entries: [{id, field, text}]}`. Its `(id, field)`
keys must be unique across all files for one locale and resolve to existing local
source text. External/dependency IDs cannot be patched. Each file permits at most
10,000 entries; strings are nonblank and at most 4,096 characters. Named `{token}`
placeholder identities and occurrence counts must match source; the compiler does
not execute templates or implement ICU parsing.

Approved fields are `$pack` `/title`, `/summary`, `/entrypoint/situation`;
existing definition `/name`, `/description`, `/summary`; NPC `/role`; and item
`/appearance/summary`. Nested fields use child IDs, never numeric offsets:
rule `/attributes/<id>/name`, `/resources/<id>/name`, `/tiers/<id>/name`;
creation `/archetypes/<id>/name`; quest `/objectives/<id>/description` and
`/outcomes/<id>/description`. Only existing source leaves qualify. Overlay text
cannot change IDs, costs, formulas, rarity tokens, references, predicates,
objectives, consequences, visibility, owner or policy. Markdown prose and text
inside arrays without stable child IDs (facts, goals, backgrounds, settlement
consequences) remain source-only. Coverage does not claim these are translated.

The optional source resource has `schemaVersion: 1` and optional `texts`,
`glossary`, `quotes` arrays (each at most 10,000 entries):

- `texts`: `{id, owner, kind, visibility, text}`. A unique local text ID, distinct
  from definition IDs, binds `/text` to an existing local definition owner.
  Kinds are `dialogue`, `tutorial`, `quote`; visibility is `public` or `secret`.
  A secret owner cannot acquire public text. Localized text inherits the source
  owner, visibility and kind without altering the compiled definition.
- `glossary`: `{id, field, kind, grammar?, pronunciation?}`. Kinds are `properName`
  or `term`; optional hint maps accept only `en` and `it` bounded nonblank text.
  A proper name must remain exactly identical to source in every overlay.
- `quotes`: `{id, field, policy}`. Policies are `verbatim` and `translate`; every
  source text of kind `quote` requires one. Policy targets must be unique existing
  text leaves. A proper name cannot require translation. `verbatim` preserves the
  exact source string and source language even if an overlay repeats it.

`CompiledPack.localization()` returns an immutable catalog beside the unchanged
mechanical definitions. `resolve(id, field, locale)` selects an exact overlay,
then declared source text. It returns text, actual language, fallback flag,
source owner/visibility/kind and quote policy. Missing keys produce `MISSING_TEXT`;
unsupported selected locale arguments fail. There is no invented text or silent
locale normalization. Translation syntax cannot establish semantic truth: human
review remains necessary to catch misleading prose.

This catalog is internal content, **not an audience projection**. A consumer must
authorize the source owner and field before lookup; access to a localized key or
glossary never grants knowledge. Import reports add privileged `localization`
metadata in report version 2: source locale, sorted EN/IT totals, overlay counts, source-fallback keys,
and source-only Markdown paths. V8 accepts report versions 1 and 2 without rewriting
old seals; identical re-imports retain their originally stored report. Newly
serialized report object keys have stable lexical order within localization.
Reports contain no translated/source private
prose or glossary hints, but key inventories themselves remain import metadata.

Both neutral fixtures now contain reviewed EN/IT entries for every catalog leaf,
including a tutorial, terminology hints and a protected quote. Italian coverage
explicitly reports the preserved English quote as source fallback. The resource
review checks meaning, terminology, IDs, numeric claims and quote preservation;
it is not independent human bilingual playtest or narrative-quality evidence.
External partial/source-only packs remain valid; future import/play screens must
show their source language and fallback coverage before play. UI localization,
ICU/formatting, runtime narration and those screens remain later gates.

Generated prose is created **directly in the selected narrative language** from
authorized source facts and a stable glossary. This avoids a second translation
call for every turn. A localized rendition cannot add/remove a fact or change
quest logic. Protected quotations, passwords, inscriptions and language puzzles
follow the pack's explicit verbatim/translation policy; an untranslated protected
quote is marked as source text, not silently normalized. Do not translate
speaker/entity IDs, event keys or formulas. Explain unavoidable original terms
without silently changing the narration language.

Review the default ruleset glossary as separate semantic keys: attribute Force
→ Forza, Finesse → Destrezza, Insight → Intuito, Resolve → Volontà; defense stat
Guard → Difesa while the Guard action has its own verb label; Action Points →
Punti azione. These are initial translation proposals for human review, not
changes to the original mechanics or finalized pack terminology. A pack may
provide compatible localized names under the same IDs. Tests distinguish action
names from stat names and validate placeholders/plurals without concatenation.

## 5. Narrative, memory and speech

Task envelopes include `outputLocale`, `localeRevision`, glossary revision and
language policy. Human-facing suggestions, character dialogue and narrator
passages share that locale, unless an explicitly tagged in-world quote requires
another language. Planner roles may use a fixed internal language with stable
IDs; only audience-filtered permitted facts reach the public narrator. Language
is not a way around knowledge authorization.

Validate locale echo, glossary IDs, permitted quotes, output bounds and source
references. A statistical language detector is only a warning signal on short
or mixed-name passages. One narration-only repair may correct wrong-language
output; on failure show the already committed localized deterministic receipt
and leave narration pending. Do not charge a new game action or perform an
unbounded translation loop. EN/IT quality requires human review, not just a
detector score.

P08.2 implements these output checks for the intent clarification and narration contracts ([docs/21 section 14](21-ai-task-contracts-and-prompts.md#14-implemented-intent-and-narration-contracts-p082)):

- **Locale and generations.** Every task binds `outputLocale`, `localeRevision` and `glossaryRevision`. The output must echo the locale, and `TaskBinding.presentationCurrent` rejects output after a locale switch or glossary edit.
- **Glossary.** Proper names keep their exact capitalization. Terms must not use an avoided form, such as `Force` in Italian.
- **Protected quotes.** Declared quotes must appear verbatim and are excluded from the locale signal.
- **Locale signal.** It counts function words: a string, or all strings together, fails only with at least 12 words, at least 4 other-language function words and more than twice as many as selected-language ones. Weaker evidence only warns (`locale-uncertain`).
- **Repair.** A mismatch uses the single repair; there is no translation chain.

Human EN/IT quality review is still required. Since P08.5 accepted narration is stored immutably with its `passage_locale` and the locale and glossary revisions it was bound to; settlement re-checks both revisions under the job locks and discards narration generated before a locale switch or glossary edit, so it is never shown ([docs/07 section 6](07-turn-protocol.md#p085-publication-receipt-fallback-and-narration-only-retry)). The deterministic event statements given to the narrator exist in English and Italian; the Italian wording still needs independent human review.

Store exact accepted text and `passageLocale`; summaries retain source IDs and
their language. Retrieval filters audience/freshness before rendering into a
language-scoped context. Include locale/glossary/policy versions in context and
media cache keys. Optional [speech](30-local-speech-synthesis.md) uses the accepted
passage language and approved voice capability; unavailable Italian speech
falls back to Italian subtitles/text, never an English voice reading Italian.

## 6. Acceptance and ownership

### P05.6 campaign integration status (28 October 2026)

The 24 October audit below named four prerequisites. P06.1–P06.4 and P07.1–P07.5 are now
complete, so the integration was built against the real endpoints. This section records
what is delivered, what was actually run and what is still open. Nothing here claims
independent bilingual or screen-reader acceptance.

| Requirement | Delivered | Evidence | Still open |
| --- | --- | --- | --- |
| Operation/cursor-safe reload | `CampaignSession` persists, per adventure+campaign scope, the stream cursor and each seen operation's identity, newest version and status (`engine.campaign-sync.v1`, device storage). `UiLanguage.change` runs `verified(scope)` for every real campaign context: the record is merged with any other tab's newer cursor, written and read back byte for byte. Storage denial, a reverted write, a required restore or an unsaved draft refuse the reload. After the reload `requery` fetches unfinished (and recently saved) operations by identity and `poll` resumes the stream after the saved cursor. Messages are applied only when strictly after the cursor in the same generation (duplicates counted, not applied); `stream.reset` adopts the server head and re-queries; a generation mismatch is reported, never merged. The draft stays in `StoryInputState`; nothing is ever submitted (`Send` is disabled and no `/commands` request exists in tests). | 8 session, 3 view and 2 language-reload tests (`campaign-session.spec.ts`, `campaign-view.spec.ts`, `ui-language.spec.ts`) on a simulated server and storages; since the sixth checkpoint also a replay of real-server bytes through the routed view across EN→IT→EN with an operation in flight at reload time (`campaign-contract.spec.ts`, recorded by `CampaignInterfaceContractTest`) | Chrome EN→IT→EN journey against the packaged origin (no browser is installed in the Linux build container); a real submit path (P05.7/P08 composer) |
| Localized API errors and receipts | `campaign-messages.ts` maps every public `COMMAND_*`, `CAMPAIGN_*`, `STREAM_*` and `LOCAL_*` code to a `$localize` sentence; unknown, hostile or prototype-named codes fall back to one generic sentence and are never displayed. Statuses, narration reasons, reset reasons and command types are closed enumerations. Receipts render revisions, moves, elapsed seconds, own-resource changes, calculations and draws from the validated public projection; only digit grouping follows the locale, so EN and IT show identical mechanics. `tools/repository_checks` `localization` fails when a controller code has no message. | `campaign-messages.spec.ts`, `campaign-protocol.spec.ts`, `LocalizationCodeTest`; `npm run build:locales` parity (317 messages); the recorded real `CAMPAIGN_SCOPE_UNAVAILABLE` reply and real MOVE receipt in `campaign-contract.spec.ts`; since the seventh checkpoint the same reply and receipt rendered with the reviewed Italian catalog, and Italian receipts with identical digit sequences (`italian-variant.spec.ts`) | Independent human review of the Italian wording (an author review pass corrected 11 targets, see below); per-pack display names for resource keys and destinations (shown as literal IDs) |
| Visible source-pack fallback | The state projection's `startingSituation.{text,locale,fallback}` is rendered with a `lang` attribute and, when `fallback` is true, a text note naming the original language. A matching-language text shows no note. Hidden catalog keys and private text are not part of the projection. | `campaign-view.spec.ts` | Fallback for later narrated passages (P08) and quest/fact text |
| Packaged single origin (QA-35) | `/saved/<uuid>` and `/{en,it}/saved/<uuid>` serve the fixed locale indexes (canonical lowercase UUID only; deeper, uppercase, malformed and resource-like paths stay 404); opaque query/hash survive. | `PackagedInterfaceTest` (deep-link and 404 additions; since the seventh checkpoint each locale index's `lang` and reviewed pre-bootstrap title) | Container recreation hash comparison for the new route; Chrome journeys on the packaged origin |
| Italian variant rendering and accessible names | The routed preview screens, shell and saved playthrough rendered with the reviewed Italian catalog show no English-only catalog text and no uncatalogued English phrase; every control, link and image has a name; ARIA ID references resolve; author titles and genres carry the adventure's `sourceLocale` as `lang` | `italian-variant.spec.ts` (jsdom, reviewed catalog loaded at run time; detectors self-tested and mutation-checked) | Layout, focus rings, contrast and real screen-reader output in a browser; independent review of the Italian wording |
| Full QA-35 acceptance | — | — | Independent bilingual and screen-reader review of both variants; the gate cannot close without it |

Design decisions: reconciliation state is a small allowlisted record (no receipts, text or
drafts), bounded to 50 operations per campaign with finished work evicted first; the
receipts of recently finished operations are re-read after a reload instead of being
stored. A pending, cancelled or narration-pending operation does not by itself block
a language reload, because it is re-queried by identity; this is the only change to the
earlier blanket refusal. The stream is consumed with `fetch` rather than `EventSource` so
the saved cursor can be sent explicitly on the first request after a reload and frames can
be validated before use.

Two rules were added at the sixth checkpoint. A `stream.reset` makes the server's head the
stored cursor even when the stored value is larger: the reset declares that value unknown or
expired, so the usual "never regress another tab's newer cursor" merge must not bring it
back. Without this rule a cursor ahead of the head (for example after restoring an older
database copy in the same generation) was merged back on every write and each later poll was
reset again. And when an applied message or a reset names a newer `stateRevision` than the
displayed state, the view re-reads the state before polling again, so an answered starting
decision does not stay on screen after a commit.

### Sixth checkpoint: recorded real-server contract (28 October 2026)

Earlier evidence for reload safety came from hand-written simulated servers. This checkpoint
adds a consumer-driven contract that uses the real backend's bytes:

- **Producer.** `CampaignInterfaceContractTest` (backend, real servlet stack, PostgreSQL and the
  background presentation worker) creates a fantasy-fixture campaign whose narrative language
  is Italian and records every GET the interface makes, in five phases: first English load;
  another client's MOVE plus a second command admitted but left in flight; delivery of the six
  stream messages and the state re-read; cancellation of the in-flight command while the page
  reloads; and the reloaded page's operation queries, resumed stream, an unknown cursor and an
  unknown campaign. UUIDs, instants and the CSRF token are normalized; all other bytes, key
  order, SSE framing and Italian text are the server's own. The normalized transcript must equal
  `backend/src/test/resources/contracts/campaign-reload-transcript.json`.
- **Consumer.** `frontend/src/app/campaign/campaign-contract.spec.ts` replays an identical copy:
  every recorded body passes the browser validators unchanged, then the routed playthrough view,
  `CampaignSession` and `UiLanguage` run an EN→IT→EN reload against a replaying origin that
  answers only requests the real server answered at or before the current phase and never
  serves the fixture's POSTs. The draft text and selection, both operation identities and the
  cursor (`0:6` at reload, `0:7` after the cancellation) survive; the reloaded page re-queries
  the in-flight and the receipt-less operations by identity and resumes after the saved cursor;
  no message is applied twice and no request writes.
- **Lock.** The repository `contracts` check fails when the two copies differ, so the suites
  cannot test different protocol versions. After an intended protocol change, rerun the backend
  test with `-Dengine.contract.update=true` and review both diffs.

The replay found the two defects described above; each new test failed before its fix and
passes after it. The Italian targets of the 77 campaign messages also received an author review
pass that corrected 11 of them: `Ricevuta` (also "receipt") became `Presa in carico`; the two
distinct narration reasons "stopped" and "interrupted" no longer share one sentence; "world
time" is no longer the ambiguous `tempo di gioco` (play time); dice use `Tiro`, not `Estrazione`;
command labels are consistently nouns (`Dialogo`); the move receipt reads `Destinazione
raggiunta` and the revision line names the action (`Azione salvata`); and four sentences were
made more idiomatic. This is not the independent bilingual
review that QA-35 requires.

What this does not establish: it runs in jsdom, not Chrome, so layout, focus rings, screen-reader
output and the compiled Italian bundle are not exercised; wording assertions use the English
source catalog; the replay cannot reproduce timing races beyond the recorded phases; and the page
still has no submit path, so the in-flight operation was admitted by the fixture.

### Seventh checkpoint: the Italian variant rendered with the reviewed catalog (28 October 2026)

The sixth checkpoint's replay asserted English wording only, and the compiled Italian bundle is an
ES-module build that jsdom cannot load. `frontend/src/app/italian-variant.spec.ts` closes the
automatable part of that gap without a browser:

- **Same catalog, run time.** The spec imports `src/locale/messages.it.xlf` as text, serializes each
  unit the way Angular's `Xliff2TranslationParser` does for the inline elements the catalog uses
  (`ph` → `{$NAME}`, `pc` → start/close placeholders, `{NAME}` inside ICU expressions) and calls
  `loadTranslations` before importing any application module, so template `i18n` attributes,
  module-level route titles and `$localize` calls all resolve to the reviewed targets with
  `LOCALE_ID` `it`. A wrong placeholder name makes `$localize` throw rather than fall back silently;
  the first run found exactly that for the chronicle plural (ICU placeholders), and the serializer
  was corrected before any assertion was relaxed.
- **Every routed screen.** The library (with the recorded adventure as a card), Settings, the
  interface kit, all scoped preview panels of both sample adventures, an unknown adventure and the
  saved playthrough are rendered inside the real navigation shell. Each must show no English-only
  catalog text (whole messages or literal runs of two or more words, including ICU branches), no
  string containing an English function word outside `code` identifiers or elements whose `lang`
  declares another language, an Italian document title, a name for every control, link and image,
  resolvable `aria-labelledby`/`aria-describedby` references, non-empty `aria-label`s and alerts,
  and only `en`/`it` `lang` values. An exploratory pass listing every rendered string not taken from
  an Italian target found only data: sample adventure titles, the recorded adventure title and
  genre, meter values such as `18 / 24`, icon identifiers in `code` and the `Italiano` endonym.
- **Real replies in Italian.** The recorded real-server transcript renders with the reviewed
  statuses (`Presa in carico`, the narration-pending status), narration reason, receipt lines and
  their list label, connection notices, composer label, placeholder and help, `Invia` (still
  disabled) and the playthrough title line. The recorded `CAMPAIGN_SCOPE_UNAVAILABLE` 404 shows the
  Italian sentence and never the code; unknown and prototype-named codes use the Italian generic
  sentence; a foreign playthrough ID shows the Italian refusal. Italian receipts for the richer
  simulated receipt (resource, check and die roll) contain exactly the committed numbers, and their
  digit sequences equal those of the English source sentences; only grouping differs (`12.000`).
- **Author metadata language.** Adventure titles and genres are free-form author text, never
  translated. They now carry the adventure's validated `sourceLocale` as `lang` on the library card
  and the saved-adventure heading, so a screen reader pronounces an English title correctly inside
  the Italian interface (WCAG 3.1.2). The playthrough title is player text of unknown language and
  stays untagged.
- **Pre-bootstrap title.** The compiled `it` index carried `<title>Adventure engine</title>`; it now
  carries the reviewed brand (see section 3). `PackagedInterfaceTest` asserts each locale's `lang`
  and title through the real server; with the English title restored in the `it` output, that test
  failed at the Italian assertion before the fix.

The detectors were checked against deliberate defects: removing `i18n` from the Send button, the
library title's `lang` binding or the textarea's label association, or serializing without the ICU
placeholder rule, each made the suite fail, and the accessibility rules report each defect class on a synthetic page. A missing
`aria-label` on the single navigation landmark is deliberately not reported, because one unlabeled
landmark is valid.

What this does not establish: it is still jsdom, so layout, focus visibility, contrast, speech
output and the compiled bundle's own execution are not exercised; the English-word detector is a
heuristic for uncatalogued English, not a translation-quality measure; and nothing here is the
independent Italian or screen-reader review that QA-35 requires.

### P05.6 integration prerequisites (24 October 2026 audit)

This audit is retained as history; the matrix above supersedes it. The remaining transport work could not be verified against the application of that date.
`InterfaceRoutes` is the only application MVC controller and serves preview GETs;
the campaign, operation and stream endpoints in [the API contract](13-api-and-ui.md)
remain proposed. Internal operation journals and compiled localization catalogs
do not supply an authorized browser transport. The frontend uses hand-authored
public previews, with no campaign HTTP or event-stream connection.

Resume P05.6 integration after these concrete prerequisites are available:

| Remaining requirement | Prerequisite | Required integration evidence |
| --- | --- | --- |
| Real campaign reload | P06.1–P06.2 campaign creation and authorized projections; P07.1 durable operation queries; P07.5 resumable public stream | EN→IT→EN retains operation identity and cursor, reconciles pending/committed work, deduplicates delivery and never submits the draft |
| Localized API errors and receipts | The same campaign/command transport with stable codes, typed parameters and public receipts | Known errors localize in both variants; unknown codes use safe generic guidance; numeric effects and committed identities remain unchanged |
| Visible source-pack fallback | P06 public pack/campaign projection of authorized resolved text and actual source language | Partial/source-only packs disclose fallback and protected quotations without exposing private catalog keys or text |
| Full QA-35 acceptance | Integrated journeys plus independent bilingual and screen-reader review | Record reviewer evidence for both languages; resource/unit/browser automation alone cannot close this gate |

The original P06 dependency on the whole P05 package prevented developing the
transport required by P05.6. Scheduling was corrected on 24 October 2026: P06
requires P03/P04 and the completed P05.5 foundation; P05.6 explicitly requires
P06.2 and P07.5, whose preceding tasks include creation and operation queries.
Develop P06/P07 first, then return to the pending integration task in roadmap
order. P05.7 still follows P05.6. Full P05.6/QA-35 remains unchecked until its
actual checks pass, including the required human reviews. Repeating preview-only
sessions cannot close it. Do not invent operations/cursors, expose an internal
pack catalog or relax the real-campaign reload guard to simulate acceptance.

This audit reran all 42 frontend tests, strict type checks and four resource
checker tests. Existing tests confirm that a real campaign or unresolved recovery
still refuses language reload after route detachment. No new transport or human
acceptance evidence is claimed.

P03.1 persists preferences and generations (`identity_owner_profile.ui_locale`,
`presentation_campaign_settings`); P04.6 validates overlays; P05.6 owns
UI resources, locale serving/reload and bilingual forms; P08.2/P08.4 enforce
per-task language/routing; P09 preserves multilingual provenance. P14 runs both
genres in both languages. P17 speech is optional. The editor remains later
E01–E06 and writes the same localization contract.

P03.6 verifies that changing EN→IT preferences leaves revision, turn, time and
event counters unchanged. Jobs retain locale/glossary/media configuration tokens;
immutable snapshots bind the assembler's locale/glossary revisions. Public snapshot
capture/admission locks an existing settings row and rejects stale presentation
tokens. Private language-neutral planning can survive that change. These are
persistence/admission checks, not translated UI resources or narrative quality.

QA-35 covers complete message/placeholder parity, missing-key failure, format
round-trips, same numeric mechanics in EN/IT, protected names, source-pack
fallback, drafts/cursor/operation preservation on switch, wrong-locale stale
callbacks, translated errors and keyboard/screen-reader labels. QA-39 and the
[evaluation protocol](31-research-validation-and-evaluations.md) assess narrative
quality separately in both languages. P04.6 supplies compiler/import localization
tests, not UI coverage, human narrative-quality measurements or full QA-35.
