# Glossary and References

## 1. Glossary

| Term | Meaning |
| --- | --- |
| Engine | Reusable runtime software, independent of any one story |
| Ruleset | Versioned mechanical definitions and supported policies |
| Adventure pack | Immutable versioned authoring content imported into the engine |
| Campaign | One persistent playthrough of a pinned pack/ruleset |
| Canon | Committed world truth and explicit validated corrections |
| Actor | Runtime participant: protagonist, NPC, companion, creature, or another supported agent |
| Attribute | Broad capability such as Force |
| Skill | Trained competency referenced by supported checks |
| Statistic | Base or derived numerical property such as Guard |
| Resource | Bounded pool such as Health, Focus, ammunition, or currency |
| Ability | Performable action with costs, targets, prerequisites, and effect graph |
| Trait | Passive or triggered rule attached to an actor or object |
| Condition | Persistent or temporary effect with explicit timing and stacking |
| Operation | Durable application unit with idempotency identity and result receipt |
| Game turn | One resolved in-world player action, distinct from AI activity |
| State revision | Monotonic canonical mutation version |
| Event sequence | Campaign-scoped append-only ordering of domain events |
| AI session | Renewable role/audience working context backed by a private Codex thread |
| Codex turn | One submitted task and the agent work that follows; not necessarily a game turn |
| Checkpoint | Consistent snapshot and resume metadata derived from canonical state |
| Summary | Lossy source-linked retrieval aid, never authoritative state |
| Knowledge scope | Who knows, believes, or is allowed to receive a claim |
| Zone | Node in the default tactical spatial graph |
| Activation | One actor's scheduled opportunity to spend action points |
| Round | One pass through the encounter's initiative roster |
| Settlement | Idempotent application of a terminal encounter/quest outcome |
| Outbox | Durable committed messages awaiting delivery or background work |
| Fencing token | Monotonic lease identity that rejects stale workers at commit |
| Creative abilities | Optional generalization of the source project's Rune system |
| Guide persona | Optional generalization of the source project's Lyra assistant |

## 2. Local primary evidence

The two owner-supplied directories are `the-world-i-can-develop` and `the-world-i-can-develop-demo`. Their roles, relevant paths, limitations, and maturity are described in [the source analysis](02-source-analysis.md). They are intentionally not bundled into this new project.

The primary source's public repository is [The World I Can Develop](https://github.com/AjimuZ/the-world-i-can-develop). Local files were inspected; no claim is made that a particular remote commit has identical contents. The demo's live/private state is not public reference material.

## 3. External primary references

Checked during documentation preparation on **22 October 2026**. URLs can redirect; implementation must recheck the selected runtime version.

| Reference | Supports | Boundary |
| --- | --- | --- |
| [OpenAI: Codex app-server](https://learn.chatgpt.com/docs/app-server) | Official integration surface and protocol | Proposed Java adapter and engine architecture are this project's design, not an OpenAI guarantee |
| [OpenAI: authentication](https://learn.chatgpt.com/docs/auth) | Supported sign-in categories and account-dependent behavior | Does not prove any particular owner's entitlement or zero cost |
| [OpenAI: Codex SDK](https://learn.chatgpt.com/docs/codex-sdk) | Alternative server-side TypeScript integration | Not a Java SDK and not selected for the initial backend |
| [OpenAI: noninteractive mode](https://learn.chatgpt.com/docs/non-interactive-mode) | Alternative bounded-job integration | Not the main gameplay transport |
| [U.S. Copyright Office: Games](https://www.copyright.gov/register/tx-games.html) | Distinction between game methods and expressive material in its guidance | Not worldwide rights/trademark clearance |
| [Official D&D SRD page](https://www.dndbeyond.com/srd) | Existence and scope of separately licensed reference documents | No SRD text or stat blocks imported into AI RPG Skirmish |

## 4. Source versus proposal

The 23 October research revision adds the revision-pinned
[competitive/source and gap matrix](27-competitive-analysis-and-gaps.md), primary
planning references in [asynchronous narrative planning](28-asynchronous-narrative-planning.md),
official Angular/CLDR references in [localization](29-localization.md) and separate
code/weight/voice rights in [local speech](30-local-speech-synthesis.md).
`Plan artifact` means noncanonical preparation; `plan admission` validates its
eligibility; `activation` remains a canonical command. `Locale revision` is
presentation state, not world advancement. A `portable story bundle` contains
reusable authored content, not the sender's private playthrough or account.

Observed source facts include package metadata, existing Python behavior, documented memory procedures, and predecessor test definitions. AI RPG Engine is the name selected by the owner. Proposed technical decisions include the Java Codex adapter, operation protocol, adventure schema, zone combat, 2d10 formulas, threat heuristic, advancement tables, and roadmap sequencing.

Numerical gameplay defaults require simulation/playtesting. Performance figures are acceptance targets. Security isolation and Codex compatibility require implementation evidence. No runnable product, account capability, successful runtime test, legal clearance, or public publication is implied by writing this documentation.
