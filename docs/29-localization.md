# English and Italian Localization

## 1. Status and supported release

P04.6 implements pack text catalogs and EN/IT fixture resources; localized UI and
narration remain later work. P03.1 stores
the owner's UI locale, each campaign's narrative locale and the locale, glossary
and media generations outside the canonical counters. English is
the default; English and Italian are both required for the first playable MVP
and v1. UI, errors, help, combat, inventory, quests, narration and accessibility
must work in both. No additional languages are required now. This supersedes the
English-only initial UI assumption in the earlier vision, under [ADR 0015](adr/0015-bilingual-first-release.md).

## 2. A small preference model

P05.2's typed local presentation metadata supplies scoped names/terminology and
neutral English fallbacks only. It does not resolve compiled text catalogs, select
a language or persist preferences; those UI/catalog bindings remain P05.6/P06.
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
resources, ICU plurals/selects and placeholder types. Pin `@angular/localize` to
the existing Angular baseline when P05 implements it; this document installs
nothing. Mark missing required translations as build failures. Avoid string
concatenation, inline hardcoded error prose and HTML returned by a model.

Build `/en/` and `/it/` static variants into the same Spring application artifact.
`/api/v1` and authorized asset routes remain unlocalized. Root entry chooses the
saved locale or English, never redirects POST/API requests and never sends a
missing resource to a misleading success page. Preserve notice files in both
variants and test exact resource hashes/404s. P05.6 extends the P02 single-origin
packaging checks rather than claiming the current jar already has both variants.

Angular documents [compiled variants](https://angular.dev/guide/i18n/merge) and
[locale subdirectories](https://angular.dev/guide/i18n/deploy). Its
[`loadTranslations` API](https://angular.dev/api/localize/loadTranslations)
does not retarget already-processed messages without a page refresh. Therefore
the selected design uses an explicit safe reload, not a promised instantaneous
runtime language switch. A third-party runtime translation framework is deferred
unless a measured requirement makes this approach inadequate.

P05.4 supplies the English-only local foundation: versioned adventure/campaign
scoped draft text and selection persistence across reload, without submission.
Reading preferences/positions remain tab-lifetime; no operation is created or
persisted by the preview composer. Language resources, locale-generation guards,
operation identity and full language-switch restoration remain P05.6/P06/P07.

P05.5 adds the English-only graphic-kit review and credits/source inventory.
Its control, meter, rarity and fallback labels remain live text beside decorative
SVG geometry; no translated text is embedded in artwork. Localizing those labels,
review examples and accessible names is part of P05.6, with the existing screens.

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

Store exact accepted text and `passageLocale`; summaries retain source IDs and
their language. Retrieval filters audience/freshness before rendering into a
language-scoped context. Include locale/glossary/policy versions in context and
media cache keys. Optional [speech](30-local-speech-synthesis.md) uses the accepted
passage language and approved voice capability; unavailable Italian speech
falls back to Italian subtitles/text, never an English voice reading Italian.

## 6. Acceptance and ownership

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
