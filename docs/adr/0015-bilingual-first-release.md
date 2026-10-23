# ADR 0015 — English and Italian from the first playable release

- Status: Accepted design requirement; implementation and quality verification pending
- Date: 23 October 2026

## Context

The earlier vision assumed an English initial UI and merely localizable story
text. The owner now requires high-quality English and Italian from the initial
supported release. Existing shell and packaging work contains no verified
bilingual UI. Reopening completed scaffold tasks would misrepresent their scope.

## Decision

Extend the MVP/v1 contract with English as default and Italian as a first-class
language. Use reviewed Angular localized variants and a safe settings reload;
keep a small owner-UI/campaign-narrative preference model. Pack source language
is author metadata; speech follows each accepted passage. Generate selected-
language narration directly with stable IDs/glossaries and bounded validation.
Preserve original transcript language and game mechanics when preferences change.

The detailed contract, official Angular evidence, alternatives and QA mapping
are in [localization](../29-localization.md). This supersedes the initial
English-only UI assumption in the vision and the related presentation example
wording. It extends ADR 0010's first-release boundaries without replacing pack
contract 1, the stack or existing verified runtime evidence.

## Alternatives and consequences

- English-only then Italian later: rejected against the owner's requirement.
- Translate every completed response with a second AI call: rejected as default
  because it adds latency/cost and another opportunity to alter meaning.
- Custom runtime translation framework: deferred; built-in locale variants and
  explicit draft-safe reload meet the requirement with less new infrastructure.
- Five independent language settings: rejected; keep UI/narration separate only
  where useful, deriving source and speech language from content/accepted text.

P04/P05/P08/P09 add the foundations and P14 verifies both languages. QA-35 and
EN/IT human evaluations are release gates; optional TTS is not an MVP gate.
Current task completion boxes stay unchanged; new work is separately scheduled.
