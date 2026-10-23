# Quality Strategy and Acceptance Criteria

## 1. Evidence policy

AI RPG Engine has no runtime tests yet because this phase contains documentation only. This chapter defines future verification. Predecessor test reports are references, not evidence that AI RPG Engine works. Every release claim must identify a fresh result, environment, scope, and limitation.

## 2. Verification layers

| Layer | Purpose | Proposed tools |
| --- | --- | --- |
| Domain unit/property tests | Invariants, formulas, turn/effect ordering, budgets, progression | JUnit and a suitable property-testing library |
| Persistence integration | PostgreSQL transactions, unique constraints, leases, recovery, migrations | Real PostgreSQL test instances; Testcontainers if suitable |
| Adapter contract tests | Framing, ordering, schema parsing, cancellation, EOF, compatibility | Recorded neutral fixtures and a controllable fake process |
| Application/API tests | Commands, projections, status, idempotency, import/export | Spring test infrastructure |
| Frontend tests | Revision handling, forms, panel state, duplicate delivery | Angular/Vitest and HTTP test support |
| Browser acceptance | Full interaction, responsive layout, focus, reconnect, shutdown | Playwright or equivalent browser runner |
| Real Codex smoke | Supported runtime/account/policy and a minimal neutral task | Opt-in, isolated context, separately reported |
| AI quality evaluations | Continuity, agency, scope, narration consistency, tactics | Fixed scenarios, graders, human review |
| Simulation and playtest | Balance and pacing | Seeded deterministic simulations plus actual play |

Do not require live inference for ordinary CI. Test fixtures must not contain private saves or credentials.

## 3. Mandatory scenario register

| ID | Scenario | Pass condition | Requirements |
| --- | --- | --- | --- |
| QA-01 | Two genres | Two packs with distinct vocabulary/modules run on the same build | FR-001–003, FR-028 |
| QA-02 | Invalid pack | Missing references, cycles, unknown effects, traversal, and oversized aliases fail precisely | FR-001, NFR-007 |
| QA-03 | Duplicate action | Same request retried before/after restart consumes one item and awards once | FR-006, FR-009, FR-023 |
| QA-04 | Changed duplicate | Same key with changed payload returns conflict | NFR-003 |
| QA-05 | Illegal AI proposal | Unknown ability, forged item, negative cost, and raw field patch cannot commit | FR-006 |
| QA-06 | Read-only inspection | Sheet/journal/guide reads leave revision, turn, time, events, and RNG unchanged | FR-008 |
| QA-07 | Player agency | Narration adds no unchosen travel, promise, attack, or resource use | FR-007 |
| QA-08 | Fresh thread resume | New role thread knows current pending decision without replaying old scene | FR-011–012 |
| QA-09 | Combat renewal | Mid-fight renewal preserves initiative, AP, reactions, effects, resources, RNG cursor | FR-012, FR-015 |
| QA-10 | Inventory coupling | Potion quantity and capped healing commit together; equipment cannot duplicate ownership | FR-009 |
| QA-11 | Combat arithmetic | Miss costs, armor absorption, strong hits, cover, and Guard/Brace stacking match rules | FR-015 |
| QA-12 | Effect clocks | Start/end effects and durations tick exactly once through defeat and renewal | FR-015 |
| QA-13 | Terminal outcomes | Victory, defeat, escape, surrender, agreement, and objective success settle once | FR-016–017 |
| QA-14 | Dynamic encounter bounds | Invalid roster, unreachable objective, hidden hard counter, and over-budget phase rejected | FR-014 |
| QA-15 | Progression | Crossing multiple thresholds grants exact entitlements with no automatic refill | FR-017 |
| QA-16 | Knowledge isolation | Secret sentinels absent from player API, narrative, guide, media metadata, and UI diagnostics | FR-013 |
| QA-17 | Crash before commit | No world effects; operation recoverable and draw identity retained | FR-023 |
| QA-18 | Crash after commit | Effects preserved; missing narration resumes without repeating action | FR-023 |
| QA-19 | Delivery reconnect | Duplicate/out-of-order SSE cannot replay sounds or revert state | FR-022–023 |
| QA-20 | Cross-campaign references | Another campaign's entity/operation ID cannot be read or mutated | NFR-004 |
| QA-21 | NPC schedule | World time and witnesses determine presence/knowledge; inspections advance neither | FR-018–019 |
| QA-22 | Guide and creative abilities | Disabled modules absent; enabled guide cannot execute a draft or reveal secrets | FR-020–021 |
| QA-23 | Restore rehearsal | Full state, active combat, content hashes, assets, and pending operation recovered in fresh storage | FR-024 |
| QA-24 | Process shutdown | Only owned bridge exits; committed save remains coherent | FR-023, FR-025 |
| QA-25 | Accessibility | Keyboard-only creation/play/combat, focus return, text zoom, reduced motion, no audio dependency | FR-027 |
| QA-26 | Publication | Fresh clone, accurate status, rights inventory, no private data or dead local links | FR-028 |
| QA-27 | Canon correction | Concrete preview, backup, source-linked audit, dependency revalidation, and summary invalidation; historical events preserved | FR-026 |
| QA-28 | Containerized startup | Fresh clone starts through root `start.sh` without host build toolchains; prerequisites/auth failures are clear; repeated launch, stop, image recreation, and restore preserve campaigns and combat; secrets and runtime access remain isolated | FR-029, FR-024–025 |
| QA-29 | Visual-novel reading and quick replies | Dominant responsive scene, explicit narrator/NPC/player labels and free input; Continue/history are read-only; selecting/editing/dismissing suggestions preserves drafts and does not submit; stale/duplicate suggestions cannot replay actions; keyboard and no-image modes remain usable | FR-005, FR-007–008, FR-027 |
| QA-30 | Complete graphical UI kit | All required controls/bars/icons/frames and interaction/fallback states exist as separate usable assets/components, with live text/focus, accessible contrast/labels, responsive review and complete license/provenance inventory | FR-022, FR-027–028 |
| QA-31 | Persistent generated portraits and item art | A returning character and a unique sword preserve identity across shop/purchase/equip/reload/restore; equivalent items reuse icons; rarity remains validated data; stale/duplicate jobs, limits, unavailable capability and secret sentinels cannot corrupt state or block play | FR-022–024, FR-030–031 |
| QA-32 | Dynamic music and lawful credits | Explicit opt-in/autoplay handling, public-state transitions, stable loops/crossfades, stale/duplicate cue rejection, source/offline fallback, separated volume controls and complete per-recording versioned credits; no hidden-state leak or unreviewed URL | FR-022, FR-027–028, NFR-009, NFR-012 |

## 4. Long-campaign evaluation

Run at least one **500-turn seeded campaign scenario** with 25 forced context renewals, at least 20 encounters, 50 recurring entities, 30 delayed promises/clues, multiple quest outcomes, and administrative corrections affecting old summaries. The exact turn schedule is a test fixture, not a forced story for users.

Hard gates: zero invalid resource balances; zero duplicate rewards; zero secret-sentinel leaks; zero missing committed operations; every checkpoint resumes the correct pending decision; identical recorded combat inputs reproduce their results. Retrieval correctness targets: at least 95% of scripted relevant-fact probes retrieve their required sources within budget, with failures listed and investigated. Narrative quality has a human rubric rather than a fabricated all-or-nothing guarantee.

A short Codex smoke cannot substitute for this soak. Normal CI can run a smaller deterministic subset; the long evaluation belongs in a release/nightly job with controlled AI usage or recorded responses as appropriate.

## 5. AI evaluation rubric

Score continuity, character consistency, grounded knowledge, preserved agency, rule-result fidelity, clarity of next decision, and nonrepetitive prose. Use adversarial input that asks the narrator to override rules, reveal hidden notes, grant items, edit engine files, or act for the player. Evaluate both proposals and final narration.

Automated checks cover IDs, prohibited markers, numeric references, expected predicates, and response schema. Human reviewers assess implications, misleading prose, unfair tactics, and unnecessary forced decisions. Do not claim a sanitizer can prove that arbitrary natural language contains no spoiler or contradiction.

## 6. Statistical combat checks

Enumerate or simulate 2d10 outcomes and verify probability distributions and strong-hit thresholds. Test min/max builds and extreme legal modifier combinations. Sweep target defenses and armor so balance changes have visible consequences. Record seeds and sample sizes for stochastic sweeps.

Specific properties: damage and resources remain in bounds; an invalid action costs nothing; a valid miss consumes declared costs; same draw references yield same result; action points never increase unexpectedly; reaction chains obey the cap; terminal encounters cannot accept new actions; settlement uniqueness survives concurrent calls.

Balance targets are provisional. Establish reference build and encounter suites before setting acceptance ranges for expected combat duration or win rate. Present observed distributions rather than declaring every budget-compliant fight balanced.

## 7. Fault injection

Exercise malformed JSON, huge frames, truncated stdout, stderr floods, RPC timeout, early turn events, process death, stale callbacks, duplicate notifications, lost leases, database deadlock, rollback, browser reload, expired stream cursor, missing assets, broken remote media, stale summaries, incompatible pack upgrades, and corrupted archives.

For each fault, define the expected operation state and the safe player message. No scenario may require “retry and hope” when a canonical receipt can determine the outcome.

## 8. Release gates

MVP requires QA-01–20, QA-23–26, and QA-28–30 at the supported reduced feature scope, a verified safe Codex profile, the default visual-novel flow and basic graphical kit, a complete simple combat flow, and a restart/renewal demonstration. Runtime image generation under QA-31 belongs to v1, not the MVP. v1 requires every scenario, the long-campaign evaluation, backup/restore rehearsal, actual browser/accessibility review, calibrated default rules, publication review, and all required roadmap tasks complete.

Failures are documented with severity and disposition. Secret leakage, canonical corruption, duplicate rewards, broken restore, or uncontrolled AI host capability block release. Cosmetic or optional-media issues can ship only with explicit scope/limitation notes and usable fallback.

## 9. Documentation verification

Before publishing this design set, verify internal relative links, required topic coverage, consistent terminology/formulas, Markdown fences, public language, truthful status, and absence of private campaign content. This is documentation QA, not implementation testing. Record the actual checks performed in the handoff rather than marking planned runtime criteria as passed.
