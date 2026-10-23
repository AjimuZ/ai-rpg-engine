# Research Validation and Evaluation Protocol

## 1. Evidence levels

This is a prospective protocol, not benchmark results. The verified shell/probe
checks in the roadmap retain their original scope. Repository source inspection
does not prove another product's quality, speed, reliability or supported local
hardware. The [competitive matrix](27-competitive-analysis-and-gaps.md) records
what was inspected; [planning](28-asynchronous-narrative-planning.md),
[localization](29-localization.md) and [speech](30-local-speech-synthesis.md)
define the proposed changes. No third-party application or speech model was run
for this documentation update.

Keep a run manifest: engine Git revision, runtime/profile and model identities,
resolved settings, pack/rules/prompt/glossary hashes, locale, enabled modules,
owner-approved budget, host/GPU/driver/Compose versions, fixture version, seeds
for deterministic mechanics, execution time and actual outcomes. A model catalog
entry is not an entitlement. Unavailable or unexecuted cells say so explicitly.
Provider randomness and remote changes limit reproducibility even with identical
prompts; preserve sanitized outputs and exact validation outcomes for review.

## 2. Baseline and experiments

| Arm | Same common foundations | Additional work | Question |
| --- | --- | --- | --- |
| A: baseline | Author constraints, deterministic rules, current state, scoped retrieval, language contract | Immediate intent/narration only; ordinary deterministic quest flow | Is the baseline already adequate? |
| B: planner | Identical facts, player choices, routing class and output limits | Bounded background quest plans; safe admission and fallback | Does planning improve coherence enough to justify work and latency? |
| C: reviewer | Same as B | One targeted optional narrative review within a fixed budget | Does review find real defects beyond deterministic checks? |

Do not deliberately weaken A's memory or add privileged context only to a public
narrator in B/C. Report total task calls and token estimates/observations, not
only final answer length. Use paired scenarios and rotate presentation order;
blind human reviewers to arm when feasible. Match output-length budgets and
separate any profile/model changes as additional experimental factors. The same
recorded mechanics/choices permit comparison, but do not claim identical remote
generations. A successful optional plan is not evidence that all quests need it.

Start with neutral bilingual short scenarios and strict spend/time caps. Before
enabling an advanced mode by default, propose a sampling plan with at least ten
paired situations per language, covering two genres and materially different
solutions; repeat stochastic conditions and report counts and uncertainty.
This is a proposed minimum, not an executed study or a statistical-power claim.
If the owner budget cannot support it, retain the baseline/default-off mode.

## 3. Scenario corpus

| Scenario | Objective assertions | Human review focus |
| --- | --- | --- |
| Missing dock workers / cyberpunk data disappearances | Accept quest and save action while planner pending; do not invent a clue before admission | Motivations, clue causality, varied possible resolutions |
| Rev100 plan, rev108 world | Relevant NPC dies/item destroyed/author correction invalidates affected branches; unrelated coin or UI-locale changes do not discard independent structure | No resurrection, retcon or contrived replacement |
| Immediate unexpected solution | Player resolves or abandons quest before plan completes; late result cannot reopen it or award twice | Agency and acceptance of unplanned solutions |
| NPC trust and rivalry | Cause-linked promises, lies, witnesses, false beliefs and boundaries remain distinct at delayed recall | Believable motivation beyond an affinity number |
| Two adventures, same names | No memory, voice, portrait, draft or job-result mixing after switch/restore | Identity continuity |
| Public narrator / secret planner | Hidden sentinel absent from text, suggestions, progress, audio, asset labels and error metadata | Indirect implication/spoiler review |
| English and Italian play | Localized UI/errors/receipts; stable IDs, names and numeric mechanics; direct selected-language narration | Natural grammar, register, terminology and dialogue |
| Player input under quota | Interactive work prioritized; finite backoff; committed receipt remains usable; no implicit billing/provider substitution | Clear useful status and no misleading scene |
| Interrupted job and restart | Expired lease/fence result rejected, duplicate completion harmless, canonical receipt not replayed | Readable coherent resume |
| Deterministic combat and trade | Same draws/actions produce same resources; one reward/owner; media/planning irrelevant to arithmetic | Explanations match actual consequences |

Add query-phantom, negative dependency, branch prerequisite and world-clock
boundary cases. Test concurrent candidate admissions against the same namespace:
only compatible ID/constraint results survive the second transactional check.
Cancellation acknowledgment alone is insufficient; require confirmed terminal
status or owned-runtime recovery before reusing a turn slot. Do not cancel an
already committed game action merely to preempt its optional narration.

## 4. Long-campaign protocol

Use 100-turn scenarios for development diagnosis and the existing 500-turn
release soak in [QA](15-quality-and-acceptance.md#4-long-campaign-evaluation),
retaining its 25 renewals, 20 encounters, 50 recurring entities and 30 delayed
promises/clues. Extend with planned and no-plan paths, language-scoped renewal,
separate adventures and relevant/irrelevant changes while jobs run. Deterministic
recorded-response/fault suites exercise all 100/500 turns cheaply in CI; they do
not measure live narrative quality. Budgeted live evaluation and human review
must be reported separately, including incomplete runs and quota pauses.

Sample recall at 1, 10, 50, 100 and 500 turns where applicable. Record required
source IDs, retrieved sources, contradiction dispositions, stale summaries,
unresolved commitments and pending decisions. Compare refusal/repair/fallback
frequency, not only successful prose. No silent deletion of failed scenarios.

## 5. Measurements and decision gates

| Dimension | Measurement / proposed gate |
| --- | --- |
| Canonical correctness | Zero duplicate rewards/commits, invalid balances, missing receipts, unauthorized changes and secret-sentinel leaks; any failure blocks release |
| Dependency admission | Changed read/negative/query dependencies rejected or selectively replanned; unrelated changes preserve valid plan portions; zero blind stale admissions |
| Recovery | Every forced restart resumes a valid pending decision; no active foreign leases/provider threads imported from backup |
| Language correctness | Complete EN/IT engine resources/placeholders; wrong-language output repaired or held, never presented as valid; no extra game action |
| Retrieval | Existing at-least-95% scripted source-recall target, listing every miss; human semantic review remains independent |
| Narrative quality | Paired scores per language/genre with sample size, disagreement and confidence intervals where justified; no unsupported aggregate superiority score |
| Responsiveness | p50/p95 interactive enqueue-to-completion and validation-to-publication, queue age, local receipt time; compare A/B/C and concurrency1/qualified2 separately |
| Efficiency | Calls, observed input/output/cached usage where exposed, separately labeled estimates, retries, optional review, planning overhead, utilization/admission and stale-discard ratio |
| Wasted work | Unused/superseded/invalidated preparation usage divided by all preparation usage over a declared window; report job counts separately, observed/estimated costs and missing telemetry explicitly |
| Fairness | Maximum wait and served-job share across active/inactive campaigns; starvation tests under bounded load and constrained quota |

Predeclare tolerable latency/cost overhead and minimum useful quality difference
after measuring A; do not invent universal millisecond or euro promises. If B
adds overhead without reproducible player benefit, leave deep planning disabled
or narrow its triggers. If C finds no useful additional defects, omit the model
reviewer. Hard semantic/security checks are never replaced by average scores.

Usage counters are not monetary invoices. Record provider/account restrictions,
unknown accounting, reserved maximum work and local estimates independently.
Cloud quota exhaustion must not start an API key path or another paid service.

## 6. Human narrative rubric

Two reviewers competent in each evaluated language independently score 1–5:
coherence/causality, character motivations and remembered causes, agency and
alternative solutions, grounded knowledge/no implicit spoilers, quest feasibility,
nonrepetition/pacing, natural grammar/register, terminology/proper-name stability,
and fidelity to mechanical receipts. Anchor 1 as a clear consequential failure,
3 as adequate with visible weaknesses, and 5 as consistently strong on the
specific scenario. Keep concrete example passages and source facts, not private
chain-of-thought. Report agreement and adjudicate large disagreements.

Automatic IDs/constraints/sentinels and independent validation are stronger for
objective invariants. LLM judges are optional exploratory signals: use a
different judged-output context without hidden private data, log judge version,
check position/verbosity bias and spot-check against humans. A judge score does
not certify lack of secrets, natural Italian or narrative quality.

## 7. Optional speech and GPU protocol

On Windows 11, RTX 4070 12 GB, 32 GB RAM and Docker Desktop/WSL2, record exact
candidate/voice/tokenizer/adapter revisions and rights, CUDA/driver support,
artifact sizes and installation result before inference. Until run, every
latency/VRAM/quality cell is **unmeasured**. Compare primary and lightweight
fallback on matched EN/IT passages, short/long dialogue, proper names, numbers,
emotion and recurring characters. Include multiple speakers and repeat visits.

Measure cold model load separately from warm synthesis, first playable complete
segment, end-to-end time, audio duration, real-time factor, peak/resident VRAM,
RAM/CPU, disk/cache size and cache-hit behavior. Do not call simulated input
streaming real audio output streaming. Human raters assess intelligibility,
naturalness, Italian pronunciation, speaker stability and comfort; cloning
requires explicit rights/consent and private reference handling.

Inject OOM, absent GPU, missing/invalid model hash, unsupported language, corrupt
audio, unavailable voice, worker death, queue saturation, skip/replay and stale
line/campaign callbacks. Compare image+TTS contention using the shared resource
admission policy; no game action can depend on model loading or synthesis.
Text/subtitles and controls remain complete when speech is disabled. Enabling
speech for release requires both language gates; lack of speech does not block
text-only MVP/v1. See QA-38 and [speech capability gates](30-local-speech-synthesis.md).

## 8. Report and reproducibility checklist

Publish a sanitized result table with manifest, measured/estimated/unrun status,
samples, failures, baseline comparison, rights restrictions, budget cutoffs and
the resulting enable/defer/reject decision. Strip credentials, host-specific
paths, private saves, full GM plans, reference voice recordings and account IDs.
Fixtures use invented neutral content. Store public results separately from
full-owner private diagnostics; hashes do not make private audio safe to publish.
Roadmap completion requires its actual checks, not this prospective protocol.
