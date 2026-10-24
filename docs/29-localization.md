# English and Italian Localization

## 1. Status and supported release

Accepted design requirement, not implemented translation resources. P03.1 stores
the owner's UI locale, each campaign's narrative locale and the locale, glossary
and media generations outside the canonical counters. English is
the default; English and Italian are both required for the first playable MVP
and v1. UI, errors, help, combat, inventory, quests, narration and accessibility
must work in both. No additional languages are required now. This supersedes the
English-only initial UI assumption in the earlier vision, under [ADR 0015](adr/0015-bilingual-first-release.md).

## 2. A small preference model

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

Retain pack-contract-1 `contentLocale`. Add an optional `localizations` map keyed
by supported locale, each naming explicit manifest-relative overlay files; the
P04.6 schema defines allowed fields before any import accepts them. Existing
single-language packs stay interpretable and retain their pinned content hashes.
New overlays are included in a new normalized revision digest, never hot-loaded
into a running campaign. The existing YAML/JSON/path/size restrictions apply.

An overlay references stable definition IDs and approved text fields only:
display labels, public descriptions, authored dialogue, tutorials and terminology.
It cannot change ability costs, IDs, rarity, objectives, visibility or links.
Private text remains private in every locale. Do not use localized names as
database keys. Pack glossary entries distinguish proper names, translatable
terms, grammatical hints and pronunciation; one entity has one identity.

Lookup order is exact selected locale, declared pack source text, then an
explicit missing-content diagnostic. Required engine UI never relies on a
partially translated pack. Import reports list language coverage and source
fallbacks. Both bundled neutral genre fixtures must have reviewed English and Italian
text for P04.6. P04.5 supplies English source inventories only; overlays, coverage
reports and fallback validation remain unimplemented. External untranslated packs
are allowed with a clear source-language
badge before play; no claim of full authored translation is made. The owner may
review/publish an explicit translation revision later through ordinary authoring.

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
quality separately in both languages. No localization tests or translation
quality measurements have been run by creating this specification.
