# Quality Strategy and Acceptance Criteria

P10.2 verification (29 October 2026): the full backend suite passes 817 tests with zero failures on
PostgreSQL 18.6, and `./mvnw --batch-mode verify` passes 817 with 4 skipped packaged-interface tests.
Toward QA-10, `ItemCommandsTest` commits a potion's quantity and its capped healing together, moves
and trades items with unit conservation, refuses a worn or foreign instance, keeps a unique item's
identity across owners, links every created unit to its operation, event and source in the append-only
ledger, rolls back rows, ledger, events and resources after a fault injected once the item rows were
written, and replays a lost response without repeating the effect. Eleven deliberate weakenings of an
exported copy each failed a test. Not claimed: a browser journey, inventory panels, durations,
quest rewards, concurrency beyond the campaign lease, or a live V19 dump and restore.

P09.5 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**11 new backend cases and all 724 packaged backend cases in 74 suites**, with zero failures,
errors or skips, and the jar is packaged; 79 frontend tests in 13 files pass. The new backend cases
are `MemoryContinuityTest` (4 real PostgreSQL), `PackRegistryPersistenceTest` (1),
`CampaignCreationHttpTest` (1), `CampaignCommandHttpTest` (1), `MemoryStartupReconciliationTest`
(3 pure) and `MemoryStartupPassTest` (1 real context); `CampaignInterfaceContractTest` records the
resume card in the browser reload transcript. This extends QA-08, QA-17 and QA-18 across a restart:

- **Restart narration (QA-18 with QA-08):** a crash during narration leaves the action's effects;
  recovery requeues the attempt, the narration is published exactly once without re-running the
  action, and the narrator's first session after the restart resumes at the free-input decision
  with the pending narration counted and no opening text.
- **Checkpoints:** corrupt, diverged (at its own revision or ahead) and outdated checkpoints are
  replaced from canonical state; a divergence invalidates earlier summaries; an unsafe boundary
  waits; canonical state is unchanged.
- **Stale summaries:** a stale history gets one replacement request; summaries and queued work of a
  rolled-back history are retired and stay stale after the campaign grows past them.
- **Missing dependencies:** a lost dependency, changed bytes and an upgraded ruleset are diagnosed by
  pack coordinates without writing; fresh commands are refused with 409
  `COMMAND_DEPENDENCIES_MISSING` while replays still work; restored content lifts the pause without a
  restart; a legacy unsealed campaign is not paused.
- **Resume card:** read-only over HTTP (whole-database snapshot), English and Italian location names,
  the player's knowledge of who is present rather than real positions, receipts of actions awaiting
  narration, and fixed error codes; the interface shows it in both languages.
- **Probes:** 22 deliberate weakening probes, applied only to an exported copy of the backend, each
  made the suites fail and were reverted byte-identically.

Every command of the project check plan passed on the implementation commit `3935422`.

Not verified:
- live model behavior after a restart and the QA-08 human rubric;
- a UI control for narration retry or session renewal, and a real browser run of the card;
- a live dump and restore.

P09.4 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**11 new cases and all 713 packaged backend cases in 72 suites**, with zero failures, errors or
skips, and the jar is packaged. The new cases are `ScopedSessionsTest` (6 with doubles),
`MemoryContinuityTest` (4 real PostgreSQL with the real scheduler, session journal and
rehydration and a scripted runtime) and `CampaignCommandHttpTest` (1);
`CanonicalMigrationUpgradeTest` gains the V17 step. This is the deterministic part of QA-08:

- **Fresh thread resume:** after the turn ceiling, the new session's first task carries a block
  that names the pending decision (`starting-situation`, then `free-input` after a command) at the
  current revision with a verified checkpoint, contains no opening text, and keeps the task's
  own instructions after it.
- **Preservation:** renewal leaves the campaign row, its events and the transcript unchanged;
  owner renewal leaves the revision unchanged.
- **Triggers:** an unreliable turn retires its session at once; context pressure renews before
  the window fills; a live lease defers a due renewal; a restart rehydrates the line from the
  session records.
- **Isolation:** the public block carries the public summary and the open promise but never the
  privileged secret; the checkpoint hash is never rendered; the summarizer gets no block.
- **Owner renewal:** refused while busy (an operation being validated, or a live lease alone) and
  for a foreign owner; the HTTP route answers counts only.
- **Probes:** 21 deliberate weakening probes, applied only to an exported copy of the backend,
  each made the suites fail and were reverted byte-identically.

Every command of the project check plan passed on the implementation commit `ff22ae6`.

Not verified:
- whether a live model follows the continuity rules (no replayed opening, no invented history)
  and the QA-08 human rubric;
- a UI control for renewal; restart reconciliation, missing dependencies and the resume card
  (P09.5);
- live model runs and a live V17 dump and restore.

P09.3 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**14 new cases and all 702 packaged backend cases in 72 suites**, with zero failures, errors or
skips, and the jar is packaged. The new cases are `SummaryContractTest` (8 pure, one parameterized
over English and Italian), `ContractSchemaTest.summarySchemaMatchesTheValidatorFieldSets` and
`MemoryContinuityTest` (5 real PostgreSQL); `CanonicalMigrationUpgradeTest` gains the V16 step.
The suite uses sealed packs, production campaign creation, ordinary commands, the real canonical
commit and the real fair scheduler with only the summarizer runtime scripted:

- **Checkpoints:** a capture equals the SHA-256 of the canonical `jsonb` text, names the player
  and every actor, has a null encounter and no seed; a repeat is `EXISTING`; a later command makes
  it `OUTDATED`; a live mutation lease gives `UNSAFE_BOUNDARY`; retention keeps the newest; a
  forged hash is `CORRUPT`, a forged snapshot `DIVERGED`, and capture then reports `MISMATCH`.
- **Policy:** four waits are `NOT_DUE`, the fifth is `REQUESTED`, a second request is the same
  `PENDING` job, a recorded range is `UP_TO_DATE`, and one player move (a scene boundary) is due.
- **Recording:** coverage equals the five public events, every supplied source carries its row
  version, canonical state is unchanged, the public summarizer never sees the secret, and the
  summary enters the public context as an optional `summary` item (`current`, then `behind`
  after one more event) and never the privileged one. A privileged summary sees the secret
  `fact` and enters only privileged contexts; a character audience is refused.
- **Freshness:** a promise kept during summarization is caught at recording (`STALE`, retried,
  and the retry no longer sees it); a revised belief makes the summary `STALE` with
  `source:belief:<id>` and removes it from contexts; an unrelated invalidation leaves a summary
  current while one naming its first event makes it stale; a locale switch and a generation
  change make it stale, and recovery cancels the old generation's queued job.
- **Bounds:** a leak and an invented coverage use the single repair, then the backoff holds the
  retry; a failed runtime spends the second attempt; the failed range is `GAVE_UP`; an expired
  lease is requeued with a new fence and the old worker can neither fail nor record; the
  installation cap throttles; disabled summaries write nothing; terminal jobs and a second
  unfinished job per audience are refused by the database.
- **Probes:** 24 deliberate weakening probes, applied only to an exported copy of the backend,
  each made the suites fail and were reverted byte-identically (SHA-256).

All 26 repository-policy tests pass with zero findings, and the nine backup tests pass with the
V16 scope. Frontend sources are unchanged.

Not verified:
- consumers of summaries and checkpoints in renewal and rehydration (delivered by P09.4 above)
  and restart reconciliation and the resume card (P09.5);
- the intent, narration and planner callers switched to envelopes (P09.4 onward);
- entity and chapter summaries, and narration as summary input;
- summary quality, paraphrased secrets and prose that calls a promise kept (human rubric);
- live model runs and a live V16 dump and restore.

P09.2 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**13 new cases and all 687 packaged backend cases in 70 suites**, with zero failures, errors or
skips; the executable jar is packaged. The new cases are `ContextSelectionTest` (7, pure) and
`ContextAssemblerTest` (6, real PostgreSQL). The packs are sealed and registered, campaigns are
created by production code, history comes from real commands and commits, knowledge from
trusted internal operations, and the plan from the real planning pipeline with only the planner
runtime scripted.

- **Audience filtering and standings:**
  - A public context holds the player's `rumored` belief (informant, language, `text` tier for
    the stemmed query), the common `wary` mood, its own state, the present warden (mandatory
    `last-known`) and its promise (mandatory `obligation`).
  - It holds no secret the player never learned, no other pair's commitment, no truth, no
    `realLocation` and no assumption.
  - Its stamp names the holder, revision, locale, policy hash and knowledge epochs.
  - `withheldPhrases` returns the unseen secret and the foreign terms, never the held statement,
    and none of them occurs in the context.
  - The warden's own context holds its `knows` belief and its obligation, without the player's
    belief or history.
  - A narrator cannot request a privileged context.
- **Privileged separation:** a planner context labels both claims as `fact` with `true` and
  `undetermined` truth, the warden's belief as `belief`, and the plan's private assumption as a
  fresh, validated `assumption` in `planning` with its branch. The public context of the same
  state has no assumption, fact or secret. After a recovery-generation change the plan's
  assumptions leave the context.
- **Overflow:** a 1,000-token budget returns `CONTEXT_TOO_LARGE` with the exact required size.
  At exactly that size every item is mandatory, history is omitted and counted, `partial()` is
  true, and the overhead, items and glossary entries are all charged. One token less overflows
  again.
- **Retrieval and language:**
  - After 69 commands, the old move and speech are outside the recent window.
  - An `event:` focus reference fetches the move in the `id` tier.
  - The English stemmer finds "keys" for "key" in the `text` tier.
  - The trailhead binds as a proper name and the grove as a term avoiding its Italian form.
  - The digest is stable.
  - An Italian campaign states events in Italian, binds the Italian grove term avoiding the
    English one, and finds "pietra" for "pietre" only with the Italian stemmer.
- **Staleness:** a context is current until the next command, which reports `revision` and the
  player's `sightings:` epoch. Uncommitted focus operations and malformed references are refused.
- **Probes:** 13 deliberate weakening probes each made the suites fail and were reverted
  byte-identically (SHA-256). In the first round, removing the glossary charge was not detected,
  so the overflow test now asserts that every rendered part is charged. The full run then
  caught a plan-storage boundary violation (ADR 0014), and the plan read moved to the
  preparation-owned `PlanAssumptions`. The retired-generation probe was added with it, and all
  13 probes were detected on the final code.

All 26 repository-policy tests pass with zero findings. Frontend sources are unchanged. The
frontend gates were not part of this first record and ran in the re-verification below.

Not verified:
- callers switched to envelopes: the intent, narration and planner contracts still build their
  own contexts (P09.3 onward);
- QA-08 and delayed-promise recall over renewal (P09.3–P09.5);
- NPC-side history for character contexts (P11/P15);
- dependency-pack glossaries;
- paraphrase leakage;
- live model runs.

The implementation owner commit is `2e6e32f`. Every check above passed on its exact sources,
and only P09.2 then gains a completion mark (55 of 131 roadmap entries).

P09.2 re-verification (28 October 2026). The completion was returned for missing checks, so
every command of the project check plan was run on the clean tree at `801c8e4`. Backend and
frontend sources are unchanged since `2e6e32f`. An interrupted later probe run had left the
`assumption-unlabelled` weakening in `ContextEnvelope.java`. It was restored to the committed
bytes before any check ran. Probes now mutate only an exported copy of HEAD.

- **Passed:**
  - `git diff --check HEAD`;
  - repository checks (0 findings) and their 26 tests;
  - frontend install, typecheck, 77 tests in 13 files, 5 localization checks and the localized
    build;
  - the full packaged backend `verify`: 687 tests in 70 suites with zero failures, errors or
    skips, and the jar packaged;
  - 9 backup tests;
  - the runtime probe: 116 cases with 18 opt-in skips, and each of its eight modules separately.
- **Focused:** the 13 context cases pass on the unmutated copy, and all 13 weakening probes are
  detected again.
- **Environment limit:** `tools/container_packaging` runs 23 cases with 2 opt-in skips. It
  reports 4 errors: two BuildKit context cases and two Compose render cases. Each error is
  `FileNotFoundError: 'docker'` because the development container has no Docker. P09.2 changes
  no packaging input, and the hosted packaging job enforces these cases.

P09.2 second re-verification (28 October 2026). The completion was returned because the
plan's `tools/container_packaging` command failed on the four Docker cases above. Two fixes
followed (owner commits `d88ee64` and `5ca1899`). Every command of the project check plan then
passed on the clean tree of `5ca1899`.

- **Packaging on hosts without Docker:**
  - The BuildKit context and Compose render cases now follow
    `tools/container_packaging/docker_requirement.py`. They are skipped, with an explicit
    reason, only when no `docker` executable exists and nothing requires Docker.
  - `PACKAGING_REQUIRE_DOCKER=1` (set by the CI packaging step) or `GITHUB_ACTIONS=true`
    makes them run, and a missing tool then fails. An installed but unusable Docker always
    fails.
  - The suite runs 27 cases (4 new policy cases) with 6 skips: 4 because Docker is missing
    and 2 opt-in.
  - The required mode was checked by hand: the same 4 cases error with `FileNotFoundError`.
    A stand-in `docker` that exits 1 makes them fail.
  - The skips are not evidence for build-context filtering or Compose boundaries. The hosted
    packaging job remains their gate.
- **Early server requests (P08.1 race):**
  - A full backend run once ended `CodexTaskAdapterTest`'s approval scenario `TIMED_OUT`
    instead of `FAILED`. A runtime request read before the caller registered the
    acknowledged turn was denied but did not fail the turn.
  - `RpcGeneration` now buffers that failure with the turn's early events.
  - `RpcGenerationTest.serverRequestBeforeTheTurnIsRegisteredStillFailsThatTurn` fails on an
    exported copy of the unfixed code and passes on the fix.
- **Passed:**
  - `git diff --check HEAD`;
  - repository checks (0 findings) and their 26 tests;
  - frontend install, typecheck, 77 tests in 13 files, 5 localization checks and the localized
    build;
  - the full packaged backend `verify`: 688 tests in 70 suites with zero failures, errors or skips, and the jar packaged;
  - 9 backup tests;
  - the runtime probe: 116 cases with 18 opt-in skips, and each module separately.

P09.1 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**8 new cases and all 674 packaged backend cases in 68 suites**, with zero failures, errors
or skips; the executable jar is packaged. The new cases are in `KnowledgeLedgerTest`. The V15
step of `CanonicalMigrationUpgradeTest` shows an upgraded save gains empty knowledge tables,
exact canonical rows, unchanged P07.6 reference values and null (stale) `claim:`, `belief:` and
`commitment:` references. The pack is sealed and registered, the campaign is created by
production code, and admission, rules, resolver, commit and database are real; trusted internal
operations stand in for the writers that later packages add.

- **Truth versus belief:**
  - The player's view of a rumored statement has its content, `RUMORED`, `TOLD` and the
    informant, but no truth or disclosure.
  - The warden's knowledge of the secret cellar stays out of the player's view, and a common
    property claim appears once.
  - Determining the statement `FALSE` and the player's `DOUBTS` revision step the claim and
    belief row versions, close the old belief at that revision and bump `knowledge:claims` and
    `knowledge:<player>` only.
  - A superseding claim keeps the old one's truth and closes its interval.
- **Rollback:** ten refused premises each leave every world and knowledge table exact, including
  a valid claim written earlier in the same operation:
  - `KNOWS` on an undetermined claim;
  - a second current belief about one claim;
  - a stale determination;
  - an unrelated claim predecessor;
  - a foreign-holder belief predecessor;
  - foreign subject, holder and unknown item references;
  - an observed sighting elsewhere than the real location;
  - a second resolution.

  Constructors refuse impossible shapes, and a change citing an event outside its operation is
  refused.
- **Reveals:** neither of these discloses the secret cellar or lurker: a rumor, a common
  `FALSE` claim, an undetermined common claim, the warden's `KNOWS` or a free-text
  `campaign_known_fact`. A believed `TRUE` claim admits and commits `MOVE cellar`, a determined
  common claim admits `SPEAK` to the lurker, and a later `DOUBTS` closes the route again. Every
  refusal writes nothing.
- **Perception:** a committed `WAIT` sights the public warden and not the concealed lurker.
  When the warden really moves away, the player's view still says trailhead, while the
  privileged view shows both places. The next look records the absence, and moving to the grove
  replaces it, citing the operation's first event. A reported sighting keeps the informant's
  wrong place.
- **Commitments:** each party sees its own and common commitments, not the warden's obligation
  to the lurker. Waiting past the deadline makes the promise overdue without any write. It
  resolves once with its revision and leaves the audience view. Reopening, rewriting and
  deletion are refused.
- **Attribution and guards:** a `SPEAK` to the warden cannot plant a belief in the lurker but
  can teach the speaker. Direct SQL is refused for:
  - wrong-revision provenance;
  - shape and grammar violations;
  - a changed determined truth;
  - a frozen subject;
  - deletes;
  - `KNOWS` on an undetermined claim;
  - a changed stance;
  - a backward sighting.

  Views refuse foreign audiences and ambient transactions.
- **Probes:** 21 deliberate weakening probes each made `KnowledgeLedgerTest` fail and were
  reverted byte-identically (SHA-256). The first run detected 20. Removing the ledger's
  duplicate-belief check was then still refused by the database index, so the test now asserts
  the specific exception per case, and the rerun detected it.
- **Scheduler test:** `twoProcessesSharingTheDatabaseNeverRunTwoTurnsAtOnce` failed in the first
  full run and in 1 of 3 isolated reruns, because it completed a task before the port double
  registered it. It now waits for the registration and passed 8 of 8 reruns and the final full
  run.

All 26 repository-policy tests pass with zero findings. Frontend sources are unchanged, so the
frontend gates were not rerun.

Not verified:
- context envelopes and retrieval over these views (P09.2);
- writers from play: the `reveal` effect, plan consumers and dialogue promises (P10, P12,
  P15);
- NPC-side observation (P11/P15); the player panels read these views since P10.5;
- QA-08 and delayed-promise recall, which need P09.2–P09.5.

The implementation owner commit is `24dba76`. Every check above passed on its exact sources,
and only P09.1 then gains a completion mark (54 of 131 roadmap entries).

P08.7 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**52 new cases and all 666 packaged backend cases in 67 suites**, with zero failures, errors
or skips; the executable jar is packaged. The new cases are 38 in `QuestPlanContractTest`, one
schema case in `ContractSchemaTest`, 7 in `QuestPlanningTest`, one in `PlanAdmissionGateTest`,
3 in `PlanningBoundaryTest` and 2 in `PlanningSettingsTest`; those six suites have 62 cases in
total. This is deterministic evidence for QA-36 and for docs/28 AP-01, AP-02, AP-04, AP-07,
AP-08 and the constraint and agency part of AP-11. Only the planner runtime is a double
(`PlannerTaskPort`). The packs are sealed and registered, the campaign is created by
production code, and the assembler, snapshot service, preparation jobs, scheduler ledger and
scheduler, contract runner, admission gate, canonical commands and database are real.

- **Contract:** the plan is accepted in English and Italian, with backend-written validation
  metadata. Its citation map gives the quest root reliance, gives the witness rows only the
  interview branch (the witness is also a participant) and the kit only the document branch,
  and leaves the player's own rows uncited. A participant that no predicate reads is root.
  Declared assumptions only add reliance.
- **Selective validation:** a witness elsewhere rejects only the interview (`guard-false`).
  An exhausted witness drops the supporting node and its descendant (`missing-support`).
  No feasible branch is `no-viable-branch`, and a false root is `root-assumption-false`.
- **Invalid-output corpus:** 28 single-aspect corruptions each fail with their own code.
  They cover unknown and unevaluated operators, deep and oversized predicates, a wrong
  reference kind, an unknown entity, a free-text condition and a mandatory player-action
  field. They also cover too many nodes or branches, a causal cycle, too deep a chain,
  model-written metadata, a foreign plan id or version, a wrong locale, a dropped or unknown
  author constraint, malformed secrets, unknown participant, content or dependency
  references, an event outside the horizon, too long a horizon, markup in prose, a missing
  fallback and a reason on a plan. Malformed and foreign envelopes and cannot-plan rules are
  checked as well.
- **Real planner path:**
  - The default is disabled and writes nothing while commands commit. Play continues while a
    plan is pending.
  - The planner task runs as `quest-planner`/`privileged`/`PREPARATION` through the ledger,
    with a `PLANNER_PRIVATE` snapshot.
  - Stored severities match the citation map, with the player's rows observed only.
    Admission changes no canonical row, and the secret proposal reaches no event, outbox,
    journal, stream, publication or presentation row.
  - Eight unrelated commands keep the plan `VALIDATED` with nothing to replan, and a
    generation change returns play to the baseline.
  - A `soothe` on the witness makes the plan `PARTIALLY_INVALIDATED`, with only the document
    branch usable. Exactly one refinement is requested (none for repeats or unrelated
    revisions). It runs as `quest-plan-refine` with stale `[interview]` and usable
    `[document]` branches and supersedes version 1, and a spent allowance requests nothing
    more.
  - A quest change invalidates the whole plan and requests one regeneration.
  - An unavailable planner, then output still invalid after its repair, end the job `FAILED`
    within two attempts. A declined plan ends `PLANNER_DECLINED`. No artifact exists after
    either.
  - A world change during generation is rejected by the gate (`STALE_QUERY_EPOCH`), and the
    regeneration is admitted. An unknown quest fails `UNSUPPORTED_TARGET` before reaching
    the planner. A cancellation requested during generation discards the result.
  - One worker pass sweeps the stale campaign, requests the refinement and runs it.
- **Gate narrowing:** a dropped observation, an altered observation, a cited entity narrowed
  to observed only and a weakened captured branch guard are refused without writing. Valid
  narrowing is stored, and an observed-only change alters no disposition.
- **Boundaries:** no package depends on planning; only the preparation and planning packages
  read plan storage; narration and presentation never read preparation.
- **Probes:** 21 deliberate weakening probes each made their targeted suites fail and were
  reverted byte-identically (SHA-256): trusted unevaluated vocabulary, unbounded predicate
  depth, an accepted false guard, kept dropped-node descendants, an accepted false root,
  mentions not treated as root, a droppable author constraint, trusted model metadata, an
  accepted causal cycle, a reused stale branch id, planner work run as interactive, no
  selective narrowing, uncited reads guarding every branch, an ignored replan allowance, an
  ignored cancellation, a disabled service reporting plans, planning enabled by default,
  unchecked manifest narrowing, observed-only rows flipping dispositions, unchecked
  observation changes and a worker sweep that skips partly invalidated plans.

Frontend sources and the recorded interface transcript are unchanged, so the frontend gates
were not rerun. All 26 repository-policy tests pass with zero findings.

Not verified:
- a live planner run or any model-quality, latency or usage result;
- the paired AP-12 investigation comparison, deferred to the optional P14 demonstration and
  P18.6;
- consumers that build commands from plans (P10);
- location-membership phantoms (no writer tags that scope yet);
- secret authored definitions with text;
- speculative preparation, world direction and plan review.

The implementation owner commit is `a984b05`. Every check above passed on its exact
sources, and only P08.7 then gains a completion mark (53 of 131 roadmap entries).

P08.6 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**31 new cases (10 in `FairQueueTest`, 15 in `InferenceSchedulerTest`, 3 in
`SchedulerBoundaryTest`, 2 in `CodexTaskAdapterTest`, one in `TaskPortSelectionTest`) and all
614 packaged backend cases in 63 suites**, with zero failures, errors or skips; with the
adapted contract, narration, session-boundary, presentation, schema and V14 migration suites the
focused set has 92 cases. The executable jar is packaged. This is the deterministic part of
QA-37 (AP-05/AP-06/AP-10 scheduling assertions); only the runtime is a double (`ControlledPort`,
or the scripted app-server behind the real Codex adapter), while the ledger, V14 guards, session
registry, scheduler and database are real:

- every admission, dispatch and terminal transition is written to `inference_task`, one task
  holds the turn permit at a time, the forwarded budget is the task's remaining budget, and two
  scheduler processes on one database never run two turns (the second waits on the single-turn
  index and dispatches after the first settles);
- an interactive task preempts a running background task by its exact handle, does not start
  until the interrupted turn's terminal result is settled, and the background caller sees
  `CANCELLED`/`preempted` with its charge kept; campaigns take turns (A, B, C, then A again)
  and background work waits for interactive work;
- interactive and background queue places are separate and bounded installation-wide and per
  campaign, and refused tasks leave no row; rolling quotas charge only started turns, refuse
  background and then all-class work over the limit, survive a restart and roll after 24 hours;
- `usageLimitExceeded` and `rateLimitExceeded` suspend background work for 30 and then 60
  seconds (durably, across a restart) while interactive work continues, and a completed task
  clears the backoff;
- cancellation ends waiting work unsent and interrupts running work by its exact handle; a
  waiting task whose budget falls below one second ends `queue-timeout` without dispatch;
- an `UNRESOLVED` outcome keeps the permit in `RECONCILING` while the port reports activity,
  ends waiting work `UNAVAILABLE` while the port cannot run anything, and releases only when the
  port confirms; a restart abandons earlier queued rows, takes over the dispatched row with its
  write-ahead reference, hands it to the port and starts new work only after confirmation, while
  the old process's late result and stale queue entry are fenced;
- admission refuses a campaign of another adventure, a stale generation and a repeated task
  identity (also after completion and after a restart), and a restore while a task waits fences
  it before dispatch; a start the journal cannot record runs no turn; shutdown cancels waiting
  work and interrupts running work; the database refuses a second permit holder, illegal
  transitions, charge and reference rewrites, identity or owner changes, deletions and changes
  to terminal rows;
- the real Codex adapter records the thread before `turn/start`, starts no turn when the record
  fails or throws, and reconciles a thread left running by an earlier process (interrupting its
  turn) before its first new turn, counting it as active until then; the Spring context wires
  the scheduler with one permit and refuses `max-active=2` and malformed or out-of-range
  settings without echoing values; `SchedulerBoundaryTest` proves that only the scheduler calls
  the session registry and that contract callers take the scheduler;
- twenty-four deliberate weakening probes each made their targeted suites fail and were
  reverted byte-identically (SHA-256): unisolated lines, no rotation, no interactive permit
  reservation, background before interactive, no aging, aging into the interactive class, the
  permit freed at the preemption request, no preemption, shared queue places, no background
  quota, released charges for started turns, a backoff forgotten on restart, suspended
  interactive work, a reconciliation released while the port is active, restart references not
  handed over, unenforced queue deadlines, an ignored waiting cancellation, earlier queued rows
  not abandoned, a busy permit treated as dispatched, no dispatch generation fence, no admission
  scope check, a skipped adapter journal, ignored restart threads and an adapter status that
  ignores ambiguity. The first definition of the abandonment probe still executed the update
  (it only zeroed the returned count) and was not detected; the corrected probe, which skips
  the update, was detected.

Frontend sources and the recorded interface transcript are unchanged, so the frontend gates
were not rerun. All 26 repository-policy tests pass with zero findings.

Not verified: more than one active turn (refused by configuration and by the database), live
quota, rate-limit and service-priority behavior and live cancellation latency (no account or
runtime was used; the QA-37 live part stays open), observed token usage (the estimates are
labelled as such), background task kinds that do not exist yet (P08.7, P09.3, P12, P15), and an
owner-facing scheduler status (P21.3).

The implementation owner commit is `e2d0ffc`. Every check above passed on its exact
sources, and only P08.6 then gains a completion mark (52 of 131 roadmap entries).

P08.5 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**9 new cases (8 in `GroundedNarrationTest`, one in `CampaignCommandHttpTest`) and all 583
packaged backend cases in 60 suites**, with zero failures, errors or skips; with the adapted
`PresentationJobTest`, `PublicStreamTest`, `CommandCancellationTest`, `EngineApplicationTest`
and the V13 step of `CanonicalMigrationUpgradeTest` the focused set has 36 cases. The executable
jar is packaged. Only the runtime is a deterministic double (`NarratorTaskPort`); the job
service, context assembler, contract runner, scoped sessions, narration contract and database
are real:

- validated narration of a speech is published once, with speaker names from the trusted
  context and backend-assigned message identities, after a task in the public `narrator`
  session whose context holds only the deterministic statement of the public event; the
  publication row is immutable;
- output still ungrounded after its single repair is never published: two submissions in one
  attempt, then the receipt with `NARRATION_FAILED` and three retries remaining, an unchanged
  receipt and world, and one `narrative.pending` message; a repaired valid output is published
  and flagged;
- a declined answer and an unnarratable bundle fall back at once; an unavailable runtime ends
  the round `NARRATION_UNAVAILABLE` without any submission; a sentinel in an `INTERNAL` event
  never appears in an instruction;
- a locale switch while narrating discards the English narration and the next attempt is
  published in Italian with its passage locale and revision;
- narration-only retry queues one round, is idempotent while it is in flight, publishes
  without changing the journal, world rows, receipts, draws or stream, then reports
  `ALREADY_NARRATED`; rounds stop at three retries (`EXHAUSTED`, nothing written) and a queued
  round is stopped by cancellation; the database refuses a second active round, a stray
  publication and a rewritten round;
- over HTTP the `presentation` view and the retry endpoint answer 202/200 with the documented
  outcomes, refuse malformed bodies and foreign scopes, and journal no new operation;
- fifteen deliberate weakening probes (published stale locale, rejected output retried in the
  round, a retry reopening the journal, unbounded or non-idempotent retries, retry after
  publication, publication not stored, view ignoring the publication, internal events in the
  context, unavailable runtime not short-circuited, declined output retried, speaker names not
  from the context, the stream joining every round, cancellation ignoring a retry round, a
  wrong HTTP status) each made the focused run fail and were reverted byte-identically.

The recorded interface transcript changed only by the new `presentation` field and was
re-recorded through `-Dengine.contract.update=true`; frontend installation, typechecking, 77
tests, the localization test and both locale builds (317-message parity) pass with it. All 26
repository-policy tests pass with zero findings.

Not verified: narration from a live runtime, browser rendering of published passages and a
Retry control (P05.6/P05.7), a stream message for retry rounds, and independent human review
of the Italian event statements and of EN/IT narration quality (QA-07).

The implementation owner commit is `32edd5e`. Every check above passed on its exact
sources, and only P08.5 then gains a completion mark (51 of 131 roadmap entries).

P08.4 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**26 new cases (14 in `ProfileResolverTest`, 5 in `ProfileSettingsTest`, 6 in
`CodexProfileResolutionTest`, one in the Spring selection suite) and all 574 packaged backend
cases in 59 suites**, with zero failures, errors or skips; with the existing adapter, wire,
boundary and session suites the focused set has 85 cases. The executable jar is packaged.
All use deterministic doubles, including the real Codex adapter against the scripted
app-server:

- the resolver is exercised without a runtime: per-slot resolution with no inherited setting
  after an expensive task, an unassigned slot, a slot of another backend, the discovered
  catalog and allowlist gates (each alone, together, and an allowlist that cannot make an
  undiscovered pair usable), an allowlist acting as the catalog of a backend without one,
  ordered owner-approved degradation with its provenance, `fixedQuality`, reassignment taking
  effect on the next call, and value-free validation of notation, backends and counts;
- settings parsing and Spring wiring: slot backend defaults, alternatives, allowlists and
  `fixed-quality`, with malformed values failing startup without echoing them;
- through the real adapter: an owner reassignment between two turns of one thread is sent
  explicitly on the next `turn/start`, an approved alternative runs and is recorded in
  provenance, and unsupported, allowlist-narrowed and other-backend slots are refused with no
  `thread/start` or `turn/start`;
- thirteen deliberate weakening probes (unchecked backend, ignored `fixedQuality`, ignored
  allowlist, ignored discovered catalog, unreported degradation, skipped alternatives,
  accepted missing catalog, reversed preference, adapter catalog bypass, dropped provenance,
  wrong adapter backend name, ignored slot backend and ignored alternatives setting) each made
  the focused run fail and were reverted byte-identically.

Not verified: a live runtime or account catalog, the Claude and local backends (P20, P21),
the owner settings API and panel, the `image` slot and per-slot readiness probes (P21.3).
Profile resolution is configuration-only until then.

The implementation owner commit is `fb77cf0`. Every check above passed on its exact
sources, and only P08.4 then gains a completion mark (50 of 131 roadmap entries).

P08.3 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**23 new cases (22 in five new suites, one in the Spring selection suite) and all 548
packaged backend cases in 56 suites**, with zero failures, errors or skips. Rerun with the
existing contract, boundary, Spring-selection and Codex adapter suites, the focused set has
79 cases. The executable jar is packaged.

The new cases use deterministic doubles only:

- `SessionScopeTest` (4) covers the role matrix and the scope key:
  - every player-facing role (`interpreter`, `narrator`, `guide`) refuses the privileged
    audience;
  - the planning and proposal roles refuse the public and character audiences;
  - character audiences need a valid subject;
  - each scope component (adventure, campaign, generation, role, pack hash, policy hash)
    distinguishes scopes, and malformed values are rejected.
- `ScopedSessionsTest` (8) covers the registry:
  - an equal scope reuses its session, and other roles, audiences, characters and
    campaigns each get their own without retiring anything;
  - a pack or policy change supersedes only that line;
  - a new generation retires every older session of its campaign, and stale-generation
    tasks are `REJECTED` without being sent;
  - a campaign under another adventure is refused;
  - explicit, campaign and installation retirement each reach the port;
  - least-recently-used eviction respects diagnostic reads;
  - bad capacities and a repeated identity fail loudly;
  - 1,600 concurrent submissions over four scopes use exactly four sessions.
- `SessionBoundaryTest` (3) checks the code structure: only `ScopedSessions` builds a
  `TaskRequest`, no other main code that names the port calls `submit` or `retireSession`,
  and `ContractRunner` exposes no port or session-identifier parameter.
- `ContractSessionScopeTest` (5) covers the runner:
  - interpretation and narration get separate sessions, and the envelope names the role,
    `public` audience and campaign generation;
  - a scope keeps its session, and another campaign gets its own;
  - a policy or generation change renews the session, and a stale generation ends
    `NotCompleted(REJECTED, stale-campaign-generation)` without a request;
  - a privileged planner session of the same campaign is never used to narrate;
  - renewing the narrator leaves the interpreter alone.
- `ScopedThreadIsolationTest` (2) runs the real Codex adapter against the scripted
  app-server:
  - a planner, narrator, interpreter and NPC tactician get four distinct threads, with
    continuity inside each scope;
  - a secret sentinel given to the planner reaches the runtime exactly once, on the
    planner's thread;
  - renewal, a policy change, campaign retirement and a restore start fresh threads only
    for the affected scopes, and a late generation-1 task sends no frame.
- `TaskPortSelectionTest` (+1) checks that the Spring context wires one registry around the
  selected port and that an out-of-range capacity fails startup.

Fifteen deliberate weakening probes were run. Each made the focused run fail and was
reverted byte-identically, with the checksum verified. The weakened rules were:

- a campaign-wide session;
- a narrator allowed in the privileged audience;
- an unchecked role matrix;
- no supersession;
- no generation fence;
- an unchecked adventure conflict;
- an unforwarded explicit retirement;
- an unforwarded supersession;
- an unforwarded eviction;
- an over-broad campaign retirement;
- a diagnostic read that reorders eviction;
- interpretation and narration sharing a role;
- the generation outside the scope;
- the policy outside the scope;
- a request built outside the registry.

These were intentional probes, not gate outcomes. All 26 repository-policy tests pass with
zero scan findings, and `git diff --check` is clean.

Frontend sources are unchanged, and the frontend gates were not rerun. This is isolation
evidence for the inference path against deterministic doubles. It is not:

- a live runtime or account run;
- a gameplay path (P08.5);
- QA-16 secret-sentinel coverage of the player API, media and diagnostics;
- proof that a caller's public context is free of secrets (P08.5/P09.2 filtering; the
  P08.2 guard refuses only verbatim markers);
- detection of paraphrased secrets.

The registry is in memory, so a backend restart costs continuity, never isolation.

The neutral implementation owner commit is `a55319f`. Every check above passed on its
exact sources, and only P08.3 then gains a completion mark (49 of 131 roadmap entries).

P08.2 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**35 focused cases in four new suites and all 525 packaged backend cases in 51 suites**,
with zero failures, errors or skips. The executable jar is packaged.

The new suites use the deterministic `ScriptedTaskPort` and a manual clock:

- `IntentContractTest` (10) accepts each typed intention and a bounded Italian
  clarification. It rejects:
  - authored or misaddressed speech;
  - unknown destinations, facts and abilities;
  - unpermitted, excess or duplicate targets;
  - another actor, a disallowed kind or an excessive wait;
  - a second intention or effect fields;
  - unbounded, unknown, privileged, wrong-language and wrong-term clarifications;
  - malformed, duplicate-key, oversized and misbound envelopes.

  It also proves that instructions carry the locale directive and glossary but no
  privileged marker, and that the digest is deterministic.
- `NarrationContractTest` (13) accepts attributed EN and IT narration with quick replies.
  It rejects:
  - player voice, unknown speakers and speaker-bearing narration;
  - unknown references, uncited events and unpermitted numbers;
  - a moved pending decision, four suggestions, unavailable actions and replies with
    actions;
  - privileged markers in passages and drafts;
  - wrong glossary forms and altered capitalization;
  - altered or unknown protected quotes;
  - long wrong-language passages;
  - effect fields, markup, and bound or duplicate violations;
  - cannot-complete results with content.

  It accepts short wrong-language text with a warning. It refuses to render a context that
  contains a marker, and it binds output to the exact context and presentation generation.
- `ContractRunnerTest` (9) covers:
  - first-attempt acceptance with explicit request fields;
  - one repair with the same context, session and slot, the remaining budget and only
    violation codes (the rejected text is absent);
  - rejection after the single repair;
  - no repair for six transport statuses;
  - a repair that did not complete;
  - an exhausted budget;
  - cancellation that reaches the attempt and prevents repair;
  - scope refusal without submission;
  - budget bounds.
- `ContractSchemaTest` (3) keeps every schema object's properties, required list and closed
  `additionalProperties` equal to the validators' field sets. It also checks the hashed
  English templates.

Fifteen deliberate weakening probes were run, and each was reverted byte-identically with
the checksum verified. Each made the focused run fail. The weakened rules were:

- non-verbatim speech;
- a second intention;
- unchecked targets;
- an unchecked output leak;
- an unchecked context scope;
- unchecked glossary terms;
- an unchecked locale;
- a dropped digest echo;
- unchecked numbers;
- unchecked event coverage;
- an unchecked pending decision;
- player voice allowed;
- unbounded repair;
- repaired transport outcomes;
- echoing the rejected output into the repair.

Disabling the number check also failed the runner cases, whose invalid fixture relies on
it. These were intentional probes, not gate outcomes. All 26 repository-policy tests pass
with zero scan findings.

Frontend sources are unchanged, and the frontend gates were not rerun. This is contract
evidence against deterministic doubles. It is not:

- live structured output from a runtime;
- a gameplay path (P08.5 publishes);
- human EN/IT narrative-quality review.

Paraphrased secrets and implied consequences are beyond the mechanical checks (docs/21
section 14, docs/17).

The neutral implementation owner commit is `3caa39b`. Every check above passed on its
exact sources, and only P08.2 then gains a completion mark (48 of 131 roadmap entries).

P08.1 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks pass
**46 focused cases in seven new suites and all 490 packaged backend cases in 47 suites**,
with zero failures, errors or skips. Rerun with the application, packaged-secret and
packaged-interface suites, the focused set has 57 cases. The executable jar is packaged.

The new suites:

- `CodexTaskAdapterTest` (14 cases) drives the adapter against a scripted app-server whose
  shapes follow the 0.164.0 neutral fixture and whose thread/turn state survives across
  connections. It covers:
  - explicit model, effort, `never` approval, read-only sandbox and schema on every turn,
    with a light turn after a deliberate one inheriting nothing;
  - session-to-thread reuse;
  - early and interleaved foreign, late, unscoped and delta events;
  - a full queue, duplicate identities, queued and active cancellation with exact-turn
    interruption, deadline interruption and queue expiry;
  - fixed failure codes with no runtime text;
  - tool-item, reroute and approval violations, each reconciled and retired before the
    next turn, with the server request denied `-32601`;
  - a lost connection reported `UNRESOLVED`, then resume/list/interrupt reconciliation
    on the next generation, and resume of a session bound in an earlier generation;
  - an unconfirmed interrupt keeping the permit until a second interrupt succeeds;
  - unassigned and uncataloged slots, a non-object schema and an unsafe effective policy;
  - sign-in, version and reachability gating, including recovery after sign-in;
  - shutdown.
- `RpcGenerationTest` (10) checks:
  - monotonic IDs with out-of-order responses;
  - correlated errors that keep the generation usable;
  - unresolved requests that close it;
  - server-request denial scoped to the named turn;
  - early buffering and handover;
  - early and inbox overflow;
  - seven protocol violations, wire end and the pending-request bound.
- `CodexFixtureReplayTest` (4) checks the retained fixture:
  - byte identity of the backend copy with the probe fixture;
  - byte-equal rebuilt thread/turn/interrupt/resume/account requests;
  - validation of the recorded results (eight-model catalog; the account-free fixture is
    valid but not ready);
  - the recorded terminal statuses from the full notification stream routed through a
    real generation.
- `StdioFrameWireTest` (6) runs a real child process. It checks a completed task with an
  `input-closed` exit, an allowlisted environment, stderr reduced to fixed labels with no
  private marker, `terminated` escalation, sticky partial-frame failure and the
  absolute-executable rule.
- `UnixSocketFrameWireTest` (5) uses a real Unix-socket WebSocket server. It checks a
  completed task through fragmented messages with a ping answered, close-frame shutdown,
  the strict handshake (accept, extension, status), handshake timeout, an unreachable
  socket and binary or masked server frames.
- `InferenceBoundaryTest` (4) and `TaskPortSelectionTest` (3) prove:
  - no package outside the adapter names it;
  - the adapter imports only the neutral package;
  - every adapter type is package-private;
  - the neutral API exposes only JDK and neutral types;
  - the unavailable default with `UNKNOWN` gameplay health;
  - Codex selection for `unix`;
  - startup refusal, without echoing values, for an unknown transport, a relative command
    or a half-assigned slot.

Nine deliberate sensitivity probes were run and each was reverted byte-identically,
with the checksum verified. Each made exactly the intended cases fail. The weakened rules
were:

- dropping the effort from `turn/start`;
- correlating by thread only;
- allowing any item type;
- ignoring server requests;
- an unbounded queue;
- trusting the interrupt acknowledgment;
- skipping reconciliation;
- inheriting the environment;
- skipping the effective-policy check.

These were intentional probes, not gate outcomes. All 26 repository-policy tests pass
with zero scan findings.

Frontend sources are unchanged, and the frontend gates were not rerun. No live runtime,
account, Docker CLI or packaged socket was available, so this is protocol and transport
evidence against deterministic doubles. It is not live inference, packaged gameplay
readiness or the opt-in real smoke (QA-42, P08 package exit). The packaged profile keeps
the transport disabled until P08.5.

The neutral implementation owner commit is `4c46d9f`. Every check above passed on its
exact sources, and only P08.1 then gains a completion mark (47 of 131 roadmap entries).

P07.6 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks
pass **155 focused cases in 13 suites and all 444 packaged backend cases in 40 suites**,
with zero failures, errors or skips; the executable jar is packaged. The focused set is
the seven-case `PlanAdmissionGateTest`, 17 preparation cases (now pinned to a sealed
sample pack), two resolved-commit cases (one new), the V12 migration upgrade, three
application cases, 49 canonical commit, six execution, six cancellation, seven
presentation-job, four stream, eight rule, 19 typed-validation and 26 admission cases.

`PlanAdmissionGateTest` creates its campaign through production code from sealed
registered packs and changes the world only through ordinary WAIT, MOVE and
USE_ABILITY commands or trusted internal commits standing in for later writers. Eight
unrelated commits leave a plan admissible at revision r+8 with its sorted checked
references, and admission, duplicate delivery and invalidation leave every world row
byte-identical (AP-01, AP-08). An ability that changes the witness's pool appends
`PARTIALLY_INVALIDATED`/`DEPENDENCY_CHANGED`; using the witness branch fails
`PLAN_DEPENDENCY_STALE` with no world change, while the independent branch is used once
by an ordinary internal command whose counters advance only by its own revision and
event, whose receipt never names the plan and whose replay returns the same receipt;
a second use, a rejected branch, an unknown artifact and a retired generation are
refused (AP-02, AP-05, AP-08). A writer that bypassed invalidation leaves the
disposition `VALIDATED`, but the live recheck at use wins and rolls the command back,
and an admission racing a move never leaves a usable stale artifact (AP-04). A deleted
unique object and a knowledge-scope phantom from a reveal commit reject pending results
(`STALE_DEPENDENCY`, `STALE_QUERY_EPOCH`) and invalidate an admitted plan, which can no
longer be used (AP-02/AP-03). Foreign, unrecorded, absent, unknown-content,
unconstrained-author, constraint, predicate and mismatched-kind inputs, a secret
definition or private read in public input and an assembler author digest that differs
from the sealed pack are refused with no artifact or receipt and unchanged world rows;
crafted legacy snapshots are refused `STALE_AUTHOR_CONSTRAINTS`, `STALE_CONTENT`,
`AUDIENCE_SCOPE` and, for a metadata-only campaign, `CONTENT_UNAVAILABLE`. A deferred
failure at COMMIT of an activating command leaves no effect, receipt or activation and
keeps the permit; the same command then commits once. Activation rows reject UPDATE,
DELETE and insertion without their committed command. The V12 upgrade preserves
populated canonical rows and backfills nothing.

Seven deliberate sensitivity probes were run and reverted byte-identically (checksum
verified): skipping the typed-version comparison, the coverage rule, the author digest,
the secret-definition audience rule, the activation call in the canonical commit, the
live dependency recheck and the second-use check each made exactly the intended cases
fail (for the last, the activation primary key still refused the duplicate, as a
different error). These were intentional probes, not gate outcomes. Configured frontend
installation, both typechecks, 77 tests in 13 files, five localization tests,
317-message parity and both locale builds pass, as do all 26 repository-policy tests,
zero-finding scans and the nine offline database-backup tests for the V12 catalog. The
container-packaging suite ran 23 tests: 17 pass, two opt-in skips and four errors that
are all `FileNotFoundError: 'docker'` (no Docker CLI in this container; nothing in its
scope changed). No live V12 dump/restore rehearsal was run. This adds stale-plan,
phantom, fencing/idempotency, transaction fault-injection and no-canonical-change
evidence toward QA-36 (AP-01–AP-05, AP-08) and the P07 package exit; it does not claim
the semantic quest-plan contract, predicate or constraint evaluators, consumers that
build commands from plans (P08.7, P09.1, P10), dispatch and budgets (QA-37, P08.6),
narration agency (QA-07, P08) or full acceptance of those scenarios. The neutral
implementation owner commit is `c598429`; every check above passed on its exact Java and
SQL sources, and only P07.6 then gains a completion mark (46 of 131 roadmap entries).

P07.5 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks
pass **173 focused cases in 16 suites and all 435 packaged backend cases in 38 suites**,
with zero failures, errors or skips; the executable jar is packaged. The focused set is
six cancellation, four public-stream, seven presentation-job, three stream/cancel HTTP
cases and the one-case V11 migration-upgrade suite, plus the P07.4 command suites: 15
typed-contract (one new attempt-payload case), 19 typed-validation, eight command-rule,
ten canonical-form, 26 admission, six command HTTP, 49 canonical commit, five lifecycle,
one resolved-plan, seven local-resolver and six command-execution cases.

`CommandCancellationTest` uses sealed packs and production campaign creation and holds
the executing request at exact boundaries: after durable admission (catalog-load seam),
after its first durable draw (draw seam) and inside the canonical commit (an event-insert
trigger waiting on an advisory lock while `pg_stat_activity` confirms the cancellation is
queued on the campaign lock). Cancellation wins at the first two boundaries: `CANCELLED`,
a fence step on the permit, retained draws, no world change, idempotent repetition and
no recovery change. The commit wins at the third: every effect and the receipt are kept,
the cancellation reports `ALREADY_COMMITTED` and the queued narration ends
`NARRATION_CANCELLED`. An equivalent request after a cancelled or failed attempt reuses
its draw stream and values; a 2^31−1-sided reservation proves exact sharing and a
different intention's independent stream; a revision change opens a fresh stream.
Finished, foreign, unknown and ambient-transaction cancellations write nothing.
`PublicStreamTest` proves one message per status change for the eleven persisted
statuses (all but transaction-internal `COMMITTING`; none for a same-status update), ordered `generation:sequence` identities, identical rendering
on re-read, the allowlist (no speech text, recipient or receipt details), `stream.ready`,
resets for an unknown head, a changed recovery generation and an expired cursor after
1001 further messages (exactly 1000 retained), and read-only snapshots.
`PresentationJobTest` proves one port call per attempt and one settlement for delivered,
unavailable and thrice-failing narration, a late worker after lease loss writing nothing
while the replacement attempt runs, cancellation during narration discarding its result,
startup reconciliation (queued kept, expired requeued, live preserved, older generation
retired) and database guards (one job per operation, committed origin at transaction end,
fence/attempt steps, final terminal rows, no deletion), all with unchanged world rows,
receipts and draws. `CampaignStreamHttpTest` runs the background worker in its own
context: a submitted speech is presented automatically, SSE replays and resumes after
`Last-Event-ID`, and the cancel route and stream reject malformed, ambiguous, foreign or
unauthenticated requests with stable codes.

Five deliberate sensitivity probes were run during development and reverted
byte-identically (checksum verified): deriving draws from the operation identity,
omitting the permit retirement on cancellation, dropping the settle fence/holder/expiry
check, ignoring a cancellation request at settle and removing journal recovery's
presentation-job deferral each made exactly the intended cases fail. These were
intentional probes, not gate outcomes. Configured frontend installation (285 packages),
both typechecks, 49 tests, four localization tests, 240-message parity and both locale
builds pass, as do all 22 repository-policy tests, zero-finding scans and the nine
offline database-backup tests for the V11 catalog. This adds cancellation-race,
resumable-delivery and post-commit presentation evidence toward QA-03, QA-17, QA-18,
QA-19 and QA-20 and the docs/07 interrupt matrix; it does not claim narration (P08),
browser stream/cancel binding (P05.6/P05.7), a live V11 dump/restore rehearsal or full
acceptance of those scenarios. The neutral implementation owner commit is `dd122c0`;
every check above passed on its exact Java and SQL sources, and only P07.5 then gains a
completion mark (45 of 131 roadmap entries).

P07.4 verification (28 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks
pass **151 focused command cases and all 414 packaged backend cases in 34 suites**,
with zero failures, errors or skips; the executable jar is packaged. The focused
breakdown is 14 typed-contract, 19 typed-validation, eight command-rule, ten
canonical-form, 26 admission, six command HTTP, 49 canonical commit, five lifecycle,
one resolved-plan, seven local-resolver and six command-execution cases.

`LocalActionResolverTest` is pure: it proves the compiler's integer semantics
(truncating division, clamp, min/max, comparisons, short-circuit logic), draw ordinals
in evaluation order with recorded sides, `pre` before `post` timing, single-branch
conditionals, exact cost payment before effects, per-pool net merging with the read
row version, clamping, vocabulary-bound targets with explicit `NO_RESOURCE`, internal
visibility for other actors' values, fail-closed missing attributes/resources, invalid
draws and unpayable costs, movement/wait/speech shapes and byte-identical determinism.
`CommandExecutionTest` registers root and dependency packs through real staging and
sealing and uses the production creation service. It proves committed movement
(placement, revision/turn/time/event counters, cleared starting decision, public event,
both outbox intents, `actor:<uuid>` epoch, lease release, allowlisted receipt), exact
wait time and speech text, ability cost payment with durable draws matching the stored
draw rows and the receipt, internal target effects absent from the projection, and
foreign-vocabulary effects recorded as unaffected. Identical retries replay without
writes before and after startup reconciliation (`COMMITTED_NARRATION_PENDING`).
Damage effects, a 160-die program and a foreign-vocabulary value read are refused as
`EFFECT_UNSUPPORTED` with exact no-write snapshots. Two database triggers inject faults
after durable draws: a failing event insert yields `FAILED_BEFORE_COMMIT` with no world
change, retained draws, a released permit and an unchanged replay, after which a new
command commits; a concurrent move of the target yields `REJECTED` with no effects and
retained draws. Commit-level cases reject forged bystander and stale-version placements;
HTTP cases assert the body and receipt allowlists and identical replay/status receipts.

Test sensitivity was checked deliberately during development: disabling the
effect-support rule and placement attribution together made exactly the
unsupported-program and forged-effect cases fail, and the source was restored
byte-identically (checksum verified) before the passing runs above; this was an
intentional probe, not a gate outcome. Configured frontend installation (285
packages), both typechecks, 49 tests, four localization tests, 240-message parity and
both locale builds pass, as do all 22 repository-policy tests and zero-finding scans. This adds executed-
action, durable-draw, receipt and fault-injection evidence toward QA-03, QA-05, QA-17,
QA-18 and QA-20; it does not claim item intentions, cancellation, streaming,
narration, live inference or the equivalent-new-attempt reroll policy (P07.5). The
neutral implementation owner commit is `3174e5a`; every check above passed on its exact
Java sources, and only P07.4 then gains a completion mark (44 of 131 roadmap entries).

P07.3 verification (27 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks
pass **138 focused command cases and all 401 packaged backend cases in 32 suites**,
with zero failures, errors or skips; the executable jar is packaged. The focused
breakdown is 14 typed-contract, 19 typed-validation, eight command-rule, ten
canonical-form, 26 admission, six command HTTP, 49 canonical commit, five lifecycle
and one resolved-plan case. Typed validation has one case fewer than P07.2 because
`MOVE` now needs pinned content and moved from the content-free fixture to the new
pack-backed `CommandRulesTest`; content-free fixtures use `WAIT` and speech.

`CommandRulesTest` registers a root and a dependency pack through real staging and
sealing and creates the campaign with the production creation service. It proves
movement only along disclosed accessible links (current, blocked, secret, unlinked,
unknown, dependency-namespaced and location-less cases reject alike); speech only to
co-present perceivable actors (self, secret, foreign, unknown-origin, nonexistent,
elsewhere and moved recipients reject alike); ability ownership, prerequisite ability and skill
ranks, every target policy, target perception and exact, insufficient, cross-pack
vocabulary and missing-row costs. Every rejection leaves an exact snapshot of 14
state, journal, permit and effect tables unchanged. At commit, a target that moved
and a cost spent after admission are re-derived and rejected; bystander resources,
bystander event actors and unknown resources are rejected as unattributable; a valid
participant-only plan commits and its receipt replays after the rules change. A
matching admission replays after the player moved. Two real contention cases hold
the campaign row, observe the blocked backend through `pg_blocking_pids`, move the
recipient and commit; admission and final commit both reject with unchanged
production timeouts and no writes. The rule catalog is scope-checked, retains no
prose and is bound to its revision. An unsealed fixture revision makes content-
dependent fresh commands fail with `COMMAND_CONTENT_UNAVAILABLE` over HTTP.

Test sensitivity was checked deliberately during development: one run weakened the
secret-destination, co-presence and resource-vocabulary rules together, and another
disabled commit attribution. The corresponding rule cases failed in both runs, and
the source was restored byte-identically (checksum verified) before the passing
runs above; these were intentional sensitivity probes, not gate outcomes. Configured
frontend installation (285 packages), both typechecks, 49 tests, four localization
tests, 240-message parity and both locale builds pass, as do all 22 repository-policy
tests and zero-finding scans. This adds ownership/prerequisite/knowledge/resource/
causal evidence toward QA-05 and QA-20; it does not claim item authorization (no
item intention exists yet), executed actions, public receipts, delivery or live
inference. Calculations and execution remain P07.4. The neutral implementation
owner commit is `1b9b0ee`; every check above passed on that exact tree, and only
P07.3 then gains a completion mark (43 of 131 roadmap entries).

P07.2 independent re-verification (27 October 2026): the first completion report
listed two superseded compile attempts and one superseded audit run as failed
verification outcomes, so independent review correctly rejected it. The implementation
was not changed. At completion commit `fb6e8b3` a fresh run on the unchanged scoped
JDK 21/PostgreSQL 18.6 fixture again passes 131 focused command cases and all 394
packaged backend cases in 31 suites (zero failures, errors or skips; executable jar
built), locked frontend installation of 285 packages, both TypeScript checks, 49
frontend tests, four localization tests, 240-message parity with both locale builds,
all 22 repository-policy tests, a zero-finding scan and the whitespace check. Report
verification as the final repaired-gate outcome of each command; repaired development
attempts remain history in the paragraph below, never current gate results, and are
never recast as an intentional failing baseline.

P07.2 verification (27 October 2026): fresh scoped JDK 21/PostgreSQL 18.6 checks
pass **131 focused command cases and all 394 packaged backend cases in 31 suites**,
with zero failures, errors or skips. The focused breakdown is 14 typed-contract,
20 typed-validation, ten canonical-form, 26 admission, six command HTTP, 49 canonical
commit, five lifecycle and one resolved-plan case. Full verification packages the
executable application. Two initial compile attempts exposed a related multi-catch
and a test-fixture factory-signature error; both were corrected before the passing
focused/full runs. They were ordinary failed verification attempts, not intentional
failing-baseline evidence.

The closed-shape corpus rejects unknown/unsupported types, generic and nested field
patches, extra effects/costs/actor fields, bad UUIDs, decimal/string integer coercion,
invalid Unicode and bounds violations. PostgreSQL snapshots prove rejection writes
no canonical state, journal, cleanup, permit, event or draw. Current mode, exact
supported pending-decision shape, immutable creation-player binding and recipient
campaign scope are checked in admission; missing legacy players fail closed.

Typed final commit requires matching owner/request/type/revision/hash evidence,
rejects omission through the legacy constructor and repeats current eligibility
before effects. Two real contention cases hold the campaign row, observe a blocked
backend with `pg_blocking_pids`, then commit a mode change before admission or final
commit obtains the lock. Both reject under unchanged production timeouts, without
partial writes. Separate cases cover a mutable caller map after hashing, historical
opaque-request replay/conflicts, eligibility drift, durable reserved-draw retention,
a valid cost followed by an invalid resource effect, and exact stored receipt replay
after current eligibility changes. Existing canonical fault-injection, owner-cap,
lease expiry, restart and idempotency tests remain passing.

Frontend locked installation/typechecking, 49 tests, four localization tests,
parity for 240 messages and both production locale builds pass. All 22 repository
policy tests pass with zero scan findings. This establishes the P07.2 typed/common
semantic boundary toward QA-04/05/17/18/20. It does not claim complete QA-05 item/
ability/knowledge authorization, an executed gameplay action, public receipts,
browser submission, delivery/cancellation, or live inference. Detailed rules remain
P07.3, local calculations/execution P07.4 and stream/cancellation P07.5. The neutral implementation owner commit is `05f5ba7`; only P07.2 now gains a
completion mark after the passing exit checks. Preserve P07.1's verified baseline,
all 130 imported task identities/titles and eight original migration drafts. The
external roadmap already contained one additional pending P98 requested-changes
entry at session start; retain it verbatim in both roadmaps (131 entries total,
42 complete), without checking or rescheduling it.

P07.1 acceptance verification (27 October 2026): the preserved implementation
is committed in `174be3e`; fresh scoped verification passes **96 focused command
cases and all 359 packaged backend cases in 29 suites**, with zero failures,
errors or skips. The focused breakdown is 10 canonical-form, 26 admission,
five HTTP, 49 canonical commit, five lifecycle and one resolved-plan case.
All task-specific duplicate/conflict, exact-revision, rollback, owner-capacity,
fencing, restart, stored-result replay and read-only scope gates pass.

Two added PostgreSQL cases close the previously documented renewal/admission
expiry-interleaving gap. A bounded JDBC gate pauses the first transaction after
its real owner-row lock; `pg_blocking_pids` confirms the competing transaction
is waiting. A separate committed fixture update expires the original permit before
either caller proceeds. Both lock acquisition orders reject the expired renewal,
admit the competing campaign exactly once and retain exactly two live permits,
without adding events or draws or changing the expired operation's status/fence.
Expiry is injected through the database rather than relying on wall-clock scheduling;
production lock timeouts and SQL remain unchanged. The existing tests cover live
renewal at capacity, takeover fencing and immutable old-result replay.

The configured finite independent checks pass frontend installation, typechecking,
49 frontend tests, four localization tests, parity for 240 messages, both production
locales, packaged backend verification and zero-finding repository scans. All
22 repository-policy tests pass. The installed dependency validator accepts all
130 task identities with P07.1 prerequisites satisfied; only P07.1 advances beyond
the preserved 40-task baseline. Original inputs and all eight migration hashes
remain intact. Source/external roadmaps are synchronized and no publication occurs.
The session executes the selected commands directly; final runner admission remains
the runner's responsibility and its configuration/state is not modified.

This completes the P07.1 admission/status boundary. Typed gameplay execution,
public receipt projection, browser submission and streaming/cancellation remain
P07.2–P07.5. The application-context restart evidence is not a separate new JVM-kill
experiment, and fixture persistence across a Docker restart remains owner-reported.
The following dated checkpoints preserve earlier failures and recovery history;
their unchecked statuses and noted expiry coverage gap describe those earlier runs.

P07.1 dependency recovery (27 October 2026): after the owner restored the isolated
PostgreSQL fixture, the unchanged-source DNS reproduction probe succeeds rather
than reproducing the historical failure. A separate TCP probe and fresh real
PostgreSQL verification pass: 94 focused command cases and 357 full packaged backend
cases, with zero failures, errors or skips. Frontend typechecking, all 49 tests,
four localization tests and both EN/IT production builds also pass. These are fresh
results for the preserved implementation, not reuse of the owner's earlier log.
Retain the failed runs below as historical evidence. The host restart-policy test
is owner-reported only. Recovery checkpoints the existing work for normal admission;
it does not mark P07.1 complete or extend the evidence scope described below.

P07.1 continuation check (27 October 2026): fresh scoped packaged verification
fails because the disposable PostgreSQL fixture hostname cannot resolve. Maven
reports 357 tests, 188 passing and 169 database/context errors, zero assertion
failures and zero skips. This includes errors in the 24 admission, five command
HTTP and 49 canonical-commit cases; the ten canonical-form, five lifecycle and one
resolved-plan case pass. No fresh concurrency, rollback or recovery pass is claimed.
Frontend typecheck, 49 tests, four localization checks, both production locales and
22 repository-policy tests pass, with zero repository scan findings. A read-only
review finds no critical or important implementation defect; the previously noted
renewal/admission exact-expiry coverage gap remains minor and unforced. All eight
original migration hashes match. Preserve the prior evidence below, but restore
fixture connectivity and rerun the database gates before P07.1 completion.

P07.1 verification (26 October 2026): the scoped JDK 21.0.12.1/Maven 3.10.0
helper passes all 357 packaged backend tests in 29 suites on real PostgreSQL 18.6,
with zero failures/errors/skips. The focused command corpus passes 94 cases:
10 canonical-form, 24 admission, five HTTP, 49 canonical commit, five lifecycle
and one resolved-plan test. The inherited draft baseline passed 356 cases; review
then exposed expiry between retirement and busy checks. The new deterministic
SQL-boundary regression failed once as expected (93/94 passed), then all 94 passed
after using one captured database cutoff for cleanup and busy classification.

New admission checks cover six simultaneous identical submissions, changed-payload
races, exact past/future revision rejection, busy/no-queue behavior, cross-adventure
owner-cap races and internal reacquisition competing for the last owner slot.
Immediate lease SQL failure and deferred COMMIT failure both restore the exact
journal/lease/world snapshot. Admitted expired workers cannot add draws or commit
after takeover, while stored draw replay remains immutable. A fresh application
context against the same database preserves committed receipt/draw/settlement/outbox
identity, retires expired work and preserves live permits. This is an application
restart test, not a new separate-JVM kill test. Existing foundation restart tests
remain in the full suite. Status/identical replay have no database effects, and
HTTP rejects malformed/foreign requests with stable codes and local browser proofs.

Frontend typechecking, all 49 tests, all four localization checks and the production
EN/IT build pass; 240 reviewed messages retain parity. All 22 repository-policy tests
pass and repository scans have zero findings. The unchanged eight original draft
hashes and original inputs are preserved. This establishes P07.1 service/API
boundaries toward QA-03/04/17/18/20; typed gameplay execution, public mechanical
receipt projection, browser submission, cancellation/stream delivery and the complete
release scenarios remain their later tasks. Review noted one minor coverage gap:
renewal at capacity and after expiry is tested sequentially, but a renewal racing
admission at the exact expiry boundary is not separately forced. Cross-campaign
admission/reacquisition races and the shared owner lock are exercised.


P06.5 library checkpoint (25 October 2026): pinned Java 21/PostgreSQL 18.6
`clean verify -Ppackaged-ui` passes all 305 tests without failures/errors/skips.
The 19 HTTP cases cover equal-title workspace identities, empty workspaces,
owner isolation, bounded UUID pagination, strict inputs, whole-database no-write
comparisons, ambient-transaction rejection and same-title two-genre separate-JVM
restart. Admission tests cover seven HTTP methods on the adventure subtree, including
invalid/future routes. The first two library regressions failed on missing routes
before implementation. Repository policy tests pass all 22 cases; backup tests
pass eight with one Windows symlink-privilege skip. No live restore rehearsal is
claimed by that first checkpoint. The second checkpoint adds optional private
specification/authored-media projections; 13 focused tests pass, including same-title
two-genre original bytes, idempotent retries/renames, post-commit storage failure,
foreign revision scopes, exact inventory corruption, unsafe paths/static links,
abandoned staging and fresh-JVM reconstruction into an empty root. The first new
folder regression failed before implementation. The second full packaged build passes
all 318 tests without failures/errors/skips. A live V10 backup/restore and fresh-JVM
startup preserves all 32 canonical tables and reconstructs 34 byte-identical files
and manifests for two same-title authored-content fixtures into an empty private root.
Browser switching, generated artwork,
draft/memory/job recovery and complete QA-33 remain unfinished.
P06.5-B.1 (30 October 2026) adds the backend part of QA-33 at MVP scope: `AdventureIsolationTest` keeps two
equal-title genres with same-name NPCs and items separate in commands, HTTP reads, memory, job rows, owner-wide
limits and folders across a separate-JVM restart, and `tools/container_packaging/test_restore_isolation.py` runs the
shipped backup tool on that world and proves all of those scopes again from a restored V26 database and an empty root.
The backend part is therefore evidenced, but QA-33 stays open: no browser ran, so open tabs, late media events and
saved-campaign play are unproven. P06.5-B.2 (31 October 2026) adds the browser draft and switch part at Vitest level:
`campaign-isolation.spec.ts` serves two same-name adventures and shows that draft and stream-cursor keys embed the adventure
ID, drafts never cross, switching aborts the pending reads and a late reply cannot reach the other view (the specs ran in
jsdom, not a real browser). Generated-image isolation is P17.6 scope. The
restore rehearsal belongs to the Docker packaging suite, which `verify.sh containers` and hosted CI's packaging job run;
`verify.sh full` does not include that suite.


P06.1 delivers a creation acceptance corpus: pure allocation/minimum/budget/
loadout checks, real PostgreSQL atomic creation, ownership, same-ID genre isolation,
durable and concurrent retries, deferred COMMIT rollback, and populated V8-to-V9
upgrade without backfill. The local HTTP corpus now verifies Host/Origin,
session/CSRF, owner isolation, strict bounded JSON, illegal budgets, sanitized
errors and durable retries. Separate application JVMs preserve two genre campaigns
and exact retry results after restart, reject the prior process session and retain
initial counters/grants. These creation checks alone do not establish full P06
consultation or browser acceptance.
The dependency checkpoint adds real sealed transitive/diamond loading, exact
version pinning after later imports, namespace separation for external profiles,
preset/custom actual skill caps, external stack caps, conflicting-version rejection
and aggregate count/depth bounds. The byte-cap case substitutes only the aggregate
size query to avoid allocating a giant fixture; all other persistence stays real.
Final packaged backend verification passes all 290 tests on PostgreSQL 18.6
(zero failures, errors or skips), including five HTTP and four admission tests; all 22
repository policy tests pass and repository scans report zero findings.

P06.2 extends packaged verification to 296 passing backend tests (zero failures,
errors or skips) on PostgreSQL 18.6, including eleven real HTTP tests. Evidence
covers owner listing/keyset bounds, every section's foreign-owner rejection,
same-name cross-genre and explicit audience isolation, hidden NPC/decision
exclusion, legacy saves, oversized collections and exact whole-database equality
across reads (QA-06's no-world-effect invariant). A competing committed writer
proves revision/data consistency under read-only repeatable-read isolation.
Separate application JVMs preserve both public genre snapshots across restart;
populated V1-to-V10 upgrade invents no player knowledge. All 22 repository policy
tests and zero-finding repository scans pass. This covers backend projections,
not P06.3 starting decisions, P06.4 text/transcript consultations or full QA-33
frontend/media/draft/restore isolation.

P06.3 packaged verification on 25 October 2026 passes all 299 backend tests
with zero failures/errors/skips on pinned Java 21 and PostgreSQL 18.6. The 24
focused campaign cases also pass. Real HTTP coverage checks exact pack-authored
starting text in both genres and EN/IT, source-language fallback, pinned authored
variants, unsupported/cleared marker privacy, no-write reads and separate-JVM
restart preservation. Italian text exposed a test-helper framing defect: removing
byte-counted HTTP chunks before UTF-8 decoding fixes the regression. All 22
repository policy tests pass and repository scans report zero findings. Backup
checks pass eight cases with one Windows symlink-privilege skip. Frontend binding,
command submission, P06.4 consultations and full QA-33 remain later work.

P06.4 verification on 25 October 2026 passes all 300 packaged backend tests
on pinned Java 21/PostgreSQL 18.6 with zero failures/errors/skips, including all
15 HTTP cases. Exact English/Italian status/inventory reads preserve every
stored row on a progressed, depleted save, reject prose/oversized/missing input,
retain admission and foreign-owner isolation, and reconstruct identical detached
passages for both genres across separate-JVM restart. The new regression first
failed with a missing-route 404, then passed after implementation. Final coverage
respects the existing row-version guards after correcting the progressed-save
fixture. The private packaged wrapper was also corrected to expose existing
compiled frontend output; neither issue weakened production checks. All 22
repository policy tests pass and repository scans have zero findings. This adds
QA-06 deterministic-consultation evidence; guide responses, browser binding,
optional durable transcript history and full QA-33 remain later work.

## 1. Evidence policy

Partial P05.6 evidence (24 October 2026, Windows 11, Chrome 155): four resource
checker tests and 42 frontend tests pass, with strict typecheck, production and
separate EN/IT review builds and a zero-vulnerability dependency audit. All 218
unique messages across the local preview have reviewed Italian targets and exact
source/ID/placeholder parity. Browser checks cover localized titles, navigation
and form labels, keyboard focus, four viewport sizes, locale bases, same-origin
script/style/notice hashes and missing resource/route 404s. Bilingual story journeys
also cover one/many passage plurals, recovery guidance, media fallback, native
validation and escaped unsaved receipts while preserving literal input/selection.
Unit tests cover all preview recovery states and locale-formatted meter text with
invariant numeric ARIA values. The loopback test server is a review harness, not
Spring packaging evidence. Native EN→IT→EN keyboard journeys now verify persisted
language, two scoped literal drafts/selections, reading layout/size/position and
equivalent-control focus, plus unsupported saved locale and storage-denial notices.
Nine unit tests cover reload/storage boundaries, corrupt snapshots, recovery across
detachment, conflicting draft writes and old callback/reply invalidation. A browser
failure exposed restored text-size options selecting the first value; option-level
selection and a routed regression assertion correct that behavior. The 28 October
integration binds operation/cursor reconciliation, localized API errors/receipts and
the visible pack-source fallback to the real campaign endpoints, with automated unit,
component and packaged-route coverage listed in the
[integration status](29-localization.md#p056-campaign-integration-status-28-october-2026).
No browser journey was run on this build (no browser in the Linux container), the 77
new Italian messages await independent review, and QA-35 plus the bilingual and
screen-reader playtests stay open.
The sixth P05.6 checkpoint (28 October 2026, Linux build container, pinned JDK 21,
PostgreSQL test service, Node 24.22.1) adds a
[recorded real-server contract](29-localization.md#sixth-checkpoint-recorded-real-server-contract-28-october-2026).
`CampaignInterfaceContractTest` records 19 real HTTP exchanges across five phases and
matches the normalized golden transcript on repeated runs; `campaign-contract.spec.ts`
replays its byte-identical copy through the validators and an EN→IT→EN reload of the
routed view with an operation in flight, and the `contracts` repository check keeps the
copies equal. The replay exposed a reset loop (an unknown cursor merged back over the
adopted head) and a stale starting decision after a commit; both regression tests failed
before and pass after the fixes. Results: 72 frontend tests in 12 files, strict typecheck,
four localization checker tests and the parity build (317 messages); 436 packaged backend
tests in 39 suites with zero failures, errors or skips; 26 repository-checker tests and
zero findings across all six checks. An author review corrected 11 Italian targets. Still
open: Chrome journeys on the packaged origin, independent Italian and screen-reader review
and the QA-35 sign-off.
The seventh P05.6 checkpoint (same container and pins) renders the Italian variant with the
reviewed catalog loaded at run time
([details](29-localization.md#seventh-checkpoint-the-italian-variant-rendered-with-the-reviewed-catalog-28-october-2026)).
`italian-variant.spec.ts` sweeps every routed preview screen, the shell and the saved
playthrough for English-only catalog text, uncatalogued English phrases, unnamed controls,
links and images, broken ARIA references and unsupported `lang` values, and checks the
recorded real replies, the real 404 code and receipts in Italian with identical digit
sequences. Its detectors are self-tested and failed on deliberate defects. Adventure
titles and genres now carry their source `lang`, and each compiled locale index carries
its reviewed pre-bootstrap title, asserted through the real server by
`PackagedInterfaceTest` (which failed with the old English `it` title). Results: 77
frontend tests in 13 files, strict typecheck, five localization tool tests, parity build
(317 messages), 436 packaged backend tests with zero failures, errors or skips. The same
human and browser gates remain open; this is jsdom evidence, not a screen-reader review.
The eighth P05.6 checkpoint (30 October 2026, Windows 11, Chrome 156) ran the EN→IT→EN journey on
the packaged origin
([details](29-localization.md#eighth-checkpoint-chrome-on-the-packaged-origin-30-october-2026)):
51 of 51 checks passed and 25 resource hashes survived real container recreation. The campaign
part replayed recorded real-server replies, because a packaged installation answers saved reads
with 503 `LOCAL_OWNER_UNCONFIGURED`; the live API journey, the screen-reader review and the
QA-35 sign-off stay open.
The 24 October prerequisite audit is retained as history in the
[P05.6 integration prerequisites](29-localization.md#p056-integration-prerequisites-24-october-2026-audit).
These checks preserve the preview safety boundary and do not establish real
operation/cursor reconciliation or independent screen-reader acceptance.
All 22 repository-checker tests pass; docs/secrets/workflows/images scans have
zero findings. Desktop English and mobile Italian Settings screenshots, plus
desktop/mobile Italian story screenshots, were visually reviewed.

The fourth P05.6 checkpoint packages both variants into the jar/container. All 22
packaging/context/launcher checks pass with real recreation; the separate opt-in
launcher lifecycle test skips. The complete PostgreSQL-backed packaged backend
suite passes, including four HTTP tests for both locale trees, exact classpath
resource bytes, legal notices, deep links, POST rejection and missing-resource/API
404s. Chrome 155 runs bilingual story/forms/receipt/reload journeys against that
actual container, plus root preference/default/denied/unsupported storage, explicit
locale links and query/hash preservation without draft changes. Resource SHA-256
maps match before/after recreation. An initial root-artwork 404 exposed a missing
build-stage input; shared artwork now copies only from compiled English resources.
No runtime account or inference request is used, and all owned services stop.

P04.6 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): 45 focused checks pass (40 text-catalog cases, four genre-import
cases and one populated migration case). They cover optional/partial EN/IT
resources, Italian source fallback, stable nested IDs, closed schemas, unknown or
mechanical/audience targets, placeholders, proper names, explicit quote policies,
immutable glossary hints, deterministic coverage, translation-only hash conflicts,
sealed reload and rollback. Both neutral fixtures have reviewed EN/IT resources
for every approved catalog leaf (24 fantasy, 26 salvage), retaining one verbatim
English motto as the Italian fallback. Source-only prose is explicitly excluded.
Full backend `verify` passes 262 tests with three packaged-interface profile skips
(265 total) and builds the jar. V8 permits new version-2 reports while preserving
populated V1→V8 canonical data and old seals exactly. The backup allowlist now
requires V8; eight offline tests pass with one Windows symlink-privilege skip.
A real disposable V8 dump/restore preserves both fixture inventories, raw hashes,
exports, metadata and sealed report versions 1/2 without changing the source.
These are compiler/resource-review and recovery-fixture results toward QA-02 and
the pack portion of QA-35; they do not establish UI coverage, independent human
bilingual playtests, runtime narration quality, public projection authorization,
full QA-23 or full QA-35. All 22 repository-checker tests pass and docs/secrets/workflows/images have zero
findings. Jar inspection confirms the four trusted schemas and V8 are packaged,
with test inventories excluded. No new dependency or toolchain version was introduced.

P04.5 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): `NeutralGenreImportTest` passes both cases using standalone
fantasy exploration and science-fiction salvage resource directories, without
production source edits or replacement engine contracts. The tests cover read-only
preview, directory/ZIP equivalence, idempotency, exact sealed byte/hash/report
preservation, recompile, distinct vocabulary/mechanics/assets despite shared local
IDs, independent adventure imports and located invalid-reference rejection with
no partial rows. This supplies two-pack import evidence toward QA-01 and QA-02.
Both fixtures disable optional modules; gameplay, distinct module behavior,
reviewed bilingual resources and full QA-33 isolation remain later gates.
Full backend `verify` passes 220 tests, skips three separate packaging-profile
tests (223 total), and builds the jar. All 22 repository-checker tests pass;
docs/secrets/workflows/images checks report zero findings.

P04.4 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): all 23 focused staging/registration tests pass. Cases cover
paths/devices/case aliases, links and special entries, conflicting archive views,
descriptors/CRC/truncation/amplification, media types/dimensions, immutable copies,
preview/idempotency/conflicts, adventure-scoped dependencies, reload tampering,
database sealing, concurrent retries/direct insertion and transactional rollback.
Full backend `verify` passes 218 tests with three separate packaged-interface
profile skips (221 total), builds the jar, and verifies populated V1→V7 preservation.
Repository checker tests pass 22; docs/secrets/workflows/images report zero findings.
The first full run exposed a V6-latest assumption in the upgrade fixture; it was
extended to V7 and both the focused upgrade test and full suite then passed.
This supplies the hostile-import/immutable-registration portion of QA-02, not
playable two-genre or bilingual acceptance. Directory tests ran in Linux containers;
native Windows junction races, full media decoding, rights review and future
import/serving UI remain outside these results. See [ADR 0018](adr/0018-protected-pack-registration.md).
Final staged review adds payload-CRC, trailing-deflate and unsigned-descriptor
regressions that reach stream validation independently of header mismatches;
the focused staging rerun passes all 12 tests.

AI RPG Engine has developer runtime compatibility tests in [`tools/runtime_probe`](../tools/runtime_probe/README.md), covering pinned discovery and deterministic thread/turn lifecycles. P01.4 adds owned-process shutdown, terminal wire failures, unresolved-request isolation, bounded/redacted stderr and ordered early events, with synthetic failure injection and the actual pinned runtime. These runtime checks do not establish gameplay/domain behavior or full QA-24 save coherence. The scripted upstream proves protocol behavior without an account and cannot establish live inference quality or account readiness. The separate owner-signed-in neutral smoke passed on 23 October 2026, validating correlated output and resume from a second stdio process; its sanitized report is retained with the probe, closing P01.2. These checks do not satisfy campaign renewal (QA-08) or packaged application startup (QA-28). P01.5 retains [neutral projections and a supported-version matrix](../tools/runtime_probe/COMPATIBILITY.md) for offline replay; replay alone cannot qualify a new runtime or platform. Predecessor test reports are references, not evidence that AI RPG Engine works. Every release claim must identify a fresh result, environment, scope, and limitation.

P02.3 adds Docker Desktop Linux-container evidence for the packaged application
shell: all five packaging checks pass, including actual HTTP delivery of the
Angular entry point, hashed assets and notices, nine backend tests against
PostgreSQL, missing-secret rejection, network/mount/identity isolation and
durable markers across three-service recreation. The 116 runtime checks also
pass without account access. This verifies packaging, not launcher, sign-in,
campaign recovery or full QA-28 acceptance. P02.7 adds an opt-in launcher lifecycle check (Windows 11, Docker Desktop, Git Bash): idempotent launch, graceful stop, interruption exit 130, restart and image-recreation preservation of neutral markers. It is partial QA-28 evidence only: no real campaign or combat data, no interactive Ctrl+C, no Linux/macOS host and no sign-in. Native Linux bind-secret ownership
and the Java release refresh remain separate gates; see the
[packaging checks](../tools/container_packaging/README.md).

P02.4 adds ordinary CI without runtime credentials (`.github/workflows/ci.yml`):
repository checks (secret/private-path scan, Markdown links and fences, workflow
policy, image pins), the account-free runtime probe suite, the packaging
build-context/Compose suite, the frontend typecheck/test/build/audit and the
backend `verify` against a job-local PostgreSQL. The checkers have 22 unit tests
with negative cases; see the [repository checks](../tools/repository_checks/README.md).
The opt-in Docker recreation and live-account checks are not run in CI, and the
workflow has not yet executed on GitHub. The backend job was replayed locally in JDK 21
and PostgreSQL 18 containers; backend tests that read the secret-file contract must not
depend on exported `SPRING_DATASOURCE_*` variables, which CI sets for the database tests.

P03.2 adds persistence evidence on 24 October 2026 (Windows 11, Docker Desktop,
pinned JDK 21 and PostgreSQL 18.6). Backend `verify` passes 43 tests; three
packaged-interface tests skip outside their separate profile. Coverage includes
campaign/adventure foreign keys, positive and negative scoped repository reads,
frozen ownership/provenance, explicit optimistic versions, concurrent stale-writer
rollback, simultaneous duplicate operation/reward insertion, resource and JSONB
byte boundaries, and a populated V1-to-V2 migration with unchanged domain counters.
The repository checker passes 22 unit tests and all policy checks; the default
packaging suite passes 20 checks with two opt-in lifecycle/recreation checks skipped.
This is storage-level evidence toward QA-03/QA-04, QA-10 and QA-20; no command API,
inventory gameplay, owner authorization, full QA-20/QA-33 acceptance, atomic outbox,
draw reuse or recovery is claimed by that checkpoint.

P03.3 adds internal atomic-commit evidence on 24 October 2026 in the same pinned
Windows 11/Docker Desktop/JDK 21/PostgreSQL 18.6 environment. Full backend `verify`
passes 72 tests, with three packaged-interface tests skipped outside their profile.
Its 27 commit integration cases cover state, events, reward settlements, journal
receipt and outbox rollback at each write boundary and at deferred commit; exact
receipt replay after later state changes; simultaneous duplicate and competing
operations; stale revision/content/resource versions; cross-scope references; and
an intervening resource writer after inserts flush. Tests reject ambient transactions,
check the service's database-only dependency surface and immutable input bounds,
and verify populated V3 upgrade without fabricated historical outbox messages.
Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two
opt-in checks skipped, and repository policy checks report no findings. This adds
transaction-level QA-20 and scope-level QA-33 evidence; full acceptance still needs
command/API authorization, draw reuse, lease/recovery and delivery work. No live
inference, gameplay or hosted-CI result is claimed for this checkpoint.

P03.4 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): full offline backend `verify` passes 81 tests, with three
packaged-interface profile skips, and builds the executable jar. Seven added
PostgreSQL cases verify concurrent draw reuse, unchanged world/journal state,
durability across failed canonical commit and a fresh service instance, allocation
rollback including lazy seed creation, cancelled reuse/new-allocation rejection,
128-draw receipt capacity and SQL scope/bounds/identity guards. Two pure protocol
tests pin independently computed HMAC vectors, rejection sampling and invalid inputs.
Populated V1-to-V4 upgrade preserves outcomes/counters without invented seed/draw rows.
Repository checks pass 22 unit tests and zero policy findings; default packaging
passes 20 tests with two opt-in skips. This adds the same-operation draw-retention
part of QA-17; startup reconciliation, backup/restore, lease/fencing, equivalent-new-
attempt reroll policy and full QA-17/QA-20/QA-33 remain later gates. No live runtime,
gameplay or hosted-CI claim is made.

P03.5 (24 October 2026): the full backend verify passes 95 tests, with three
packaged-interface profile skips, on PostgreSQL 18.6. The checks cover exclusive,
monotonic and concurrent lease grants; expiry fencing of late commits and new draws
while replay is kept; database lease guards and bounds; recovery of each precommit
status with no repeated effects; preservation of live leases; pagination past one
page; a restart that keeps a committed receipt with narration pending; recovery
failure that aborts startup; and a populated V4-to-V5 upgrade. The backup tool
passes 9 offline unit tests (one symlink case is skipped on hosts without the
privilege), and a manual backup/restore rehearsal is recorded in
[security §8](14-security-and-operations.md#8-backups-and-restore). This is the
crash-before-commit foundation of QA-17 and a database-only precursor of QA-23, not
their full acceptance.

P03.6 evidence (24 October 2026): pinned JDK 21/PostgreSQL 18.6 full backend
`verify` passes 113 tests with three packaged-interface skips (116 total), including
17 preparation tests and the populated V1→V6 upgrade. Real transactions cover
concurrent enqueue/claim/admission, version conflicts, deferred admission rollback,
critical enqueue/outbox rollback and replay, scoped snapshot constraints, immutable
inputs, omitted-manifest rejection, query phantoms, unrelated revisions, concurrent
epoch invalidation, cancellation, finite retries/expiry, restart gating, generation
retirement and locale separation. Non-query semantics fail closed. A populated V6
backup/restore and jar-startup rehearsal preserves immutable bytes and canonical
counters while retiring old jobs/plans. Repository tests pass 22, backup tests pass
8 with one Windows symlink skip, packaging tests pass 20 with two opt-in skips.
These establish the enabled storage/outbox/admission portions of QA-36/37; they do
not complete semantic plan validation, fair scheduling, runtime cancellation,
player-facing fallback, author/secret checks or narrative-quality evaluation.

P04.2 evidence (24 October 2026): the 8-test `PackCompilerTest` suite compiles a valid
reference pack and rejects duplicate IDs, category/kind mismatch, unknown/wrong-kind/
undeclared references, unresolved dependencies, unknown rarity tiers, shared appearance
identities, creation budget and stack overflows, unknown attributes/resources, rank
overflow, stray or unknown effect fields, unknown fields and unsupported kinds. Full
Maven `verify` on pinned JDK 21/PostgreSQL 18.6 passes 184 tests with three skips (187 total).
Expression limits, cycles and version compatibility remain untested until P04.3.

P04.1 evidence (24 October 2026): the 63-case in-memory parsing corpus covers the
closed contract-1 manifest, YAML 1.2 core resolution, tags/anchors/aliases/merge
rejection, strict JSON, encoding, immutable values, field coordinates, resource
budgets, explicit categories and raw inventory hashes. Whole-value schema patterns
reject trailing line terminators; an independently computed Python digest vector
pins the portable framing. Full Maven `verify` on pinned JDK 21/PostgreSQL 18.6
passes 176 tests, skips three separate packaged-interface profile tests (179 total),
and builds the executable jar containing both schemas. Repository-checker tests pass
22/22 and all repository policy checks report zero findings. This establishes only
the parser/schema/hash portion of QA-02; semantic references, cycles, effects,
archive extraction, media and immutable admission were addressed in P04.2–P04.4.
Playable two-genre QA-01 remains beyond P04.5's import checks. The definition envelope deliberately
does not validate its `data` mechanics. No complete pack-import acceptance is claimed.

## 2. Verification layers

| Layer | Purpose | Proposed tools |
| --- | --- | --- |
| Domain unit/property tests | Invariants, formulas, turn/effect ordering, budgets, progression | JUnit and a suitable property-testing library |
| Persistence integration | PostgreSQL transactions, unique constraints, leases, recovery, migrations | Real PostgreSQL test instances; Testcontainers if suitable |
| Adapter contract tests | Framing, ordering, schema parsing, cancellation, EOF, compatibility | Recorded neutral fixtures and a controllable fake process |
| Application/API tests | Commands, projections, status, idempotency, import/export | Spring test infrastructure |
| Frontend tests | Revision handling, forms, panel state, duplicate delivery | Angular/Vitest and HTTP test support |
| Browser acceptance | Full interaction, responsive layout, focus, reconnect, shutdown | Playwright or equivalent browser runner |
| Real runtime smoke (Codex; Claude when claimed) | Supported runtime/account/policy and a minimal neutral task, per selectable runtime | Opt-in, isolated context, separately reported |
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
| QA-09 | Combat renewal | Mid-fight renewal preserves initiative, AP, reactions, effects, resources, RNG cursor and dialogue operation/transcript; reconnect cannot submit a draft or repeat a social effect | FR-012, FR-015 |
| QA-10 | Inventory coupling | Potion quantity and capped healing commit together; equipment cannot duplicate ownership | FR-009 |
| QA-11 | Combat arithmetic and dialogue | Miss costs, armor absorption, strong hits, cover and Guard/Brace stacking match rules; bounded speech spends no AP/time/RNG, consequential social/scene actions use declared costs/checks, ambiguous/unsupported/out-of-turn input changes nothing | FR-015 |
| QA-12 | Effect clocks | Start/end effects and durations tick exactly once through defeat and renewal | FR-015 |
| QA-13 | Terminal outcomes | Victory, defeat, escape, surrender, agreement and objective success settle once; test refused negotiation, a counteroffer, explicit player acceptance and lost-response retry of a negotiated ending | FR-015–017 |
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
| QA-33 | Independent adventure workspaces | Two genres with same-name NPCs/items retain distinct specifications, drafts, inventories, memory and art folders; switching/stale callbacks cannot mix them; reboot/restore preserves scopes and owner-wide job limits | FR-001–003, FR-022–024, FR-032, NFR-004 |
| QA-34 | Author-fixed content and supported combat variants | Preserve a required named NPC/appearance and fixed facts in fantasy/cyberpunk fixtures; complete only allowed gaps; diagnose conflicts; validate a nondefault supported combat profile, reject unsupported primitives and preserve frozen active encounters | FR-001, FR-006–007, FR-015 |
| QA-35 | First-class English and Italian | Reviewed resource/placeholder parity and ICU/format tests; localized UI/combat/quests/errors/ARIA labels, direct selected-language narration, stable proper names/IDs, explicit source-pack fallback, persisted language and draft/cursor-safe reload; no gameplay change or stale-language result | FR-033, NFR-014 |
| QA-36 | Dependency-safe narrative plans | Revision100→108 unrelated changes retain valid branches; dead NPC/destroyed item/revealed secret/query phantom invalidates affected content; second transactional checks/fences/idempotency prevent stale admission; plan completion creates no canonical fact/time/reward | FR-006–007, FR-013, FR-034, NFR-015 |
| QA-37 | Fair bounded AI jobs and recovery | Durable priority/interactive reservation, scoped contexts, finite quotas/backoff/retries, cancellation-terminal reconciliation, worker/restart/duplicate/outbox tests and no cross-adventure callback; default concurrency1, any higher limit separately qualified | FR-023, FR-032, FR-034, NFR-013 |
| QA-38 | Optional bilingual speech and resource failure | Exact admitted code/weight/voice rights; EN/IT pronunciation/speaker stability, locale-aware cache/restore, text-first controls, explicit audio opt-in; no-GPU/OOM/missing voice/stale line/worker death safely fall back; measure real RTX4070/CPU behavior before enabling | FR-022, FR-036, NFR-009, NFR-014 |
| QA-39 | Bilingual narrative comparisons | Paired baseline/planner/planner+reviewer studies with source-grounded coherence/motivation/agency rubric, 100/500-turn continuity, quota/latency/usage/waste/replanning metrics and explicit unrun/disabled cells; human EN/IT review distinct from objective invariants | FR-007, FR-011–013, FR-033–034, NFR-008, NFR-013 |
| QA-40 | Safe presentation events | Allowlisted public scene/actor/expression/line/asset references only; no executable/raw-URL cues, no hidden-actor or language leak, deduplicated skip/reconnect, persistent visual/voice identities and fallback without world changes | FR-022, FR-030, FR-035–036, NFR-004, NFR-014 |
| QA-41 | Portable stories between owners | Export/import both genres and EN/IT overlays on a clean second installation; IDs/hashes/compatibility/dependencies/credits preserved; missing/unshareable assets handled, hostile ZIP rejected, conflicting revisions previewed; no credentials/private playthrough/live jobs or recipient-save overwrite | FR-001, FR-024, FR-028, FR-037, NFR-012 |
| QA-42 | Narration runtime selection and parity | The same neutral fixtures give identical engine-visible outcomes through the Codex and Claude adapters. Unknown setting values and unsupported versions are refused. No failover or silent switch occurs when the selected runtime is limited or signed out. A switch at restart retires old sessions, resumes the pending decision and regenerates pending narration without repeating the action. Credentials of the other kind stop the runtime. Secret sentinels are absent from both. The terms-review gate blocks a supported claim | FR-005, FR-025, FR-038, NFR-007 |
| QA-43 | Backends, model slots and hardware advisor | Unknown protocols and non-allowlisted endpoints are refused. A model that breaks the output contract cannot serve a structured slot, and the result is shown. The advisor on synthetic hardware profiles (no GPU, 6, 8, 12 and 24 GB) never recommends beyond the admitted memory. Keys never appear in the database, API, frontend or logs. Changing a slot's billing or privacy class needs acknowledgement and fallback never crosses a class. Changing one slot retires only its sessions. A fetch with a wrong hash is rejected and nothing downloads without an explicit action | FR-039, FR-040, NFR-007, NFR-012 |
| QA-44 | Local generation under shared admission | A text lease and an image lease alternate without out-of-memory failures. The text model unloads and reloads and its prompt cache is restored. An interactive request makes an image job yield at a step boundary. Unowned GPU contention delays or degrades work without killing any process. Missing GPU, memory or files fall back to text-only play or catalog art. Image jobs record model hash, parameters and source, use safe-content defaults, and follow the idempotent asset rules. Measurements are recorded for the reference host | FR-022, FR-040, NFR-009 |
| QA-45 | Optional STT and hands-free play | EN/IT voice-only preview/correction/repeat/confirm/cancel; explicit microphone permission/listening/mute; silence/noise/accent/negation ambiguity, TTS echo and interruption safety; exploration/combat target/cost/decision validation; no out-of-turn costs or queued actions; duplicate chunks/results/confirmations, reconnect/renewal and lost receipts commit once; per-adventure ephemeral audio isolation; measured shared-resource/latency bounds or disabled no-capture/typed fallback | FR-004, FR-007, FR-015, FR-023, FR-027, FR-033, FR-041, NFR-003–004, NFR-009, NFR-012–014 |

QA-45 is prospective; no microphone, recognizer, browser voice flow or human
recognition acceptance has run. The [STT acceptance matrix](35-speech-input-and-hands-free-play.md#7-qualification-and-acceptance)
defines the enabled-profile evidence and disabled-profile fallback. STT input
remains separate from QA-38's optional TTS output qualification.

P05.1 shell acceptance passes 11 frontend form/routing/service tests, strict
typecheck and production build. Chrome 155 desktop/mobile smoke verifies both
neutral local previews, route navigation, keyboard Continue, revealed chronicle,
character-name preview, text-size selection and unknown-identity fallback without
page exceptions or nonlocal/action requests. Tests also cover trimmed-name bounds,
literal markup, scope clearing, independent bounded reading and missing-image
recovery. Both scene layouts were visually reviewed. These are shell checks only;
full QA-29 (reading/input), QA-30 (graphic kit), QA-33 (persisted isolation) and
QA-35 (bilingual UI) remain pending. Commands and scope are in the
[frontend guide](../frontend/README.md).

P05.2 extends local preview acceptance to 15 passing frontend tests, strict
typecheck/build and a zero-finding dependency audit. Regressions cover scoped
attribute/resource names, terminology, absent/blank fallback labels, literal markup,
empty metadata and optional-panel visibility/direct routes after adventure changes.
Chrome 155 desktop/mobile smoke additionally checks resource labels and enabled/
disabled panels with no page exceptions or nonlocal/action requests; three screenshots
were visually reviewed. All 22 repository tests and docs/secrets/workflows/images
scans pass. This verifies a typed local public projection contract, not registered
pack/campaign APIs, authored-pack module execution or full QA-29/30/33/35.

P05.3 passes 18 frontend tests, strict typecheck/build, zero-vulnerability audit
and 22 repository tests. Chrome 155 exercises Enter and Space on Continue and
Chronicle, visible focus and Tab after the final advancement, public portrait
identity/failure, scene failure, and navigation-preserved reading/layout choices.
Default desktop/mobile and text-mobile screenshots were visually reviewed.
No-artwork text mode passes 320px and landscape layouts at 24px with reduced
motion; no scene/portrait requests are initiated by that story view. No page
exceptions, nonlocal requests or action requests occur. This covers the local
reading portion of QA-29; playable drafts/suggestions, reconnect deduplication,
assistive-technology review and full QA-29/30/33/35 remain later gates.

P05.4 passes 26 frontend tests, strict typecheck/build, a zero-vulnerability
audit and all 22 repository tests. Service regressions cover revision ordering,
scoped callback epochs (including detach/return), suggestion identity and bounds,
recovery-state preservation, and unavailable/corrupt storage. Routed tests verify
literal rendering, editable suggestions and disabled submission. Chrome 155 on
desktop/mobile verifies keyboard selection/editing/dismissal, multiline input,
draft/selection restoration after reload, scope isolation, offline/reconnecting
states and explicit clearing. The mobile composer screenshot was visually reviewed;
no page exceptions, nonlocal requests or action requests occur. Repository
policy scans cover docs, secrets, workflows and images. This establishes the local
draft/projection portion of QA-29; actual campaign operation reconciliation,
reconnect deduplication, assistive-technology review and full QA-29/30/33/35 remain
later gates. Send remains disabled until P06/P07 provide campaign operations.

P05.5 establishes QA-30 for the local graphic kit: all 30 frontend tests, strict
typecheck, production build (345.91 kB), zero-vulnerability audit and 22 repository
tests pass. Original SVG/CSS geometry covers controls, 20 icons, finite/clamped
numeric resource bars, progress, dialogue/nameplate/portrait/item frames, empty
slots, independent sample item art, status symbols and persistent help disclosure.
Settings ships credits plus source/provenance inventory and complete MIT notice.
Chrome 155 verifies real keyboard and pointer states, disabled/busy/invalid
semantics, live meter labels, measured text/placeholder contrast >=4.5:1 and field boundary/
focus contrast >=3:1, notice delivery and forced-colour/increased-contrast/reduced-
motion rendering. Desktop/mobile full-page screenshots were visually reviewed;
320px/landscape checks have no horizontal overflow. Unknown/inherited icon keys,
literal labels, invalid numeric input and review-state isolation have regression
coverage. An existing browser timing race now waits for the zoneless draft guard
render before checking its unchanged disabled assertion. These checks do not
establish runtime campaign artwork persistence, Italian review, assistive-
technology acceptance or the remaining QA-29/31/33/35 gates.

P05.7-B.2.2 records QA-40 evidence for presentation cues; the point-by-point map to
backend tests, frontend tests and the browser check is in the
[roadmap](16-development-roadmap.md) entry of that name. The browser check ran the
packaged interface in headless Chrome with a scripted API and stream, the same
journeys once in the English and once in the Italian tree. It covers duplicate,
reconnect, reset and skip delivery, refused hostile frames, text-only reading, real
Tab/Enter/Space and reduced motion. QA-40
stays open for persistent visual and voice identities (P17), and no real turn
produces cues, so no frame from the real backend reached a browser. Audio came from
a stub because the asset route is not served; nobody heard it, and no screen-reader
review ran.

Combat-chat acceptance for P11/P12 also includes a real-browser English/Italian
path with visible speaker/recipient, editable suggestions and explicit Send;
desktop/mobile keyboard access to both chat and tactics; draft preservation while
an NPC acts; no hidden submission at the next activation; public-only NPC replies;
and a supported scene interaction versus a rejected attempt to rewrite a resolved
event. Verify the per-activation speech limit, no automatic ally action, clarification
before compound actions or unknown costs, insufficient-AP rejection, provider
unavailability with usable tactical controls, and committed narration recovery.
Renew/reconnect during a social check and after an accepted agreement must reuse
the original operation/draws and settle once. These are planned checks, not results
claimed by the current preview shells.

P11.1 (29 October 2026) adds the first combat-foundation evidence, which is not a
QA-09/QA-11–13 result: pure checks of the combat profile bounds and kernel, the zone
graph, the lifecycle table and objective statuses; compiler diagnostics for the
`combat` rule and encounter templates; and real PostgreSQL checks that `ENGAGE`
freezes the encounter without a draw, time or pool change, records the opening
strike once, replays a lost response, refuses ineligible entries without writes,
blocks exploration in encounter mode, keeps the V22 guards and freezes a customized
profile through the same kernel. Initiative, arithmetic, effects, outcomes and
mid-round recovery remain P11.2–P11.6.

P11.2 (30 October 2026) adds scheduler evidence, still not a full QA-09/QA-11–13
result: pure `CombatSchedulerTest` cases (initiative from recorded dice with the
finesse and identity tie-breaks, one arrow per stored step, points granted at
activation start and expired at its end, other participants passing, one round end
with eight seconds per operation, the round cap, skipped defeated participants, one
edge per move with difficult terrain and capacity, bounded speech, addressing range,
declared social and scene actions, no out-of-turn legality, the receipt options)
and real PostgreSQL `TacticalCommandsTest` cases (the roll's recorded draws match the
stored order and replay on retry; moves, speech and declared actions spend exactly
their costs and no draw; ending the activation closes the round once; every refusal
writes nothing; tactical intentions are refused out of mode and out of turn; a
customized profile drives the same scheduler; the V23 guards; a long seeded fight
in which every candidate intention at every decision is compared with an independent
model of the fixture, across many activations and round boundaries; the largest
supported role values still roll, and unstorable words write nothing), plus the
largest roster within one operation's bound. The QA-09/QA-13
cases for "no out-of-turn submission" and "bounded no-benefit speech" now have
backend evidence; attacks, reactions, outcomes, negotiation responses, transcript
recovery and the browser path remain P11.3–P12.5.

P11.3 (30 October 2026) adds the arithmetic of QA-11, still not a full QA-09/QA-11–13
result, on `CombatMathTest`, `CombatStrikeTest` and `StrikeCommandsTest`: every 2d10
outcome against every stored defense, damage and healing bounds enumerated without
sampling, strong hits, armor absorbing a hit, penetration, cover, the Guard stance under
its cap, what a miss spends, ammunition, no redraw on retry, defeat stopping at zero
Health, and fifty seeded fights (seed 20261030) replaying identically with every invariant
held at each step. Brace stacking and reaction refresh spending wait for P11.4, terminal
outcomes for P11.5, and observed balance for P18.3: these numbers remain provisional.

P11.4 (30 October 2026) adds the Guard/Brace stacking of QA-11 and the effect clocks of QA-12, still not a full
QA-09/QA-11–13 result, on `CombatEffectsTest`, `CombatReactionsTest` and `ReactionCommandsTest`: Brace meeting one attack per
round under one stacking key, the situational cap and Resistance, every stacking mode, each of the eight phases firing once
for its trigger with distinct trigger keys, each clock counting only its unit, a poisoned participant who falls at its
activation start receiving no points, reactions that never chain, and sixty seeded fights keeping every bound. On real
PostgreSQL through commits: Guard and Brace under one key, each clock of an entry effect ending once with exactly two poison
ticks over three round ends, a holder felled by its first tick defeated once and ticking no more, the player's pool reaching
exactly zero with a retry that changes nothing, an effect applied in the holder's own activation waiting for the next, and a
cooldown of two rounds refusing, ending and paying again. Effects through renewal and restore wait for P11.6, terminal
outcomes for P11.5, and observed balance for P18.3, so the renewal half of QA-12 and QA-09 stay open.

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

MVP requires QA-01–20, QA-23–26, QA-28–30, QA-33–35 and QA-40 at the supported reduced feature scope, a verified safe Codex profile, English/Italian visual-novel play and basic graphical kit, independent adventure workspaces, a complete simple combat flow, and a restart/renewal demonstration. QA-36–37 apply to the job/admission foundations actually enabled; an unavailable advanced planner must pass the no-plan baseline rather than block MVP. P14 records an optional bilingual planning comparison or an honest unavailable/deferred outcome. Runtime image generation under QA-31 belongs to v1. v1 requires all mandatory scenario scopes, QA-42 for the selectable runtime (a Claude runtime that fails the terms review ships disabled, with selection refusal still tested), QA-43 and QA-44 for slots, local backends and local generation (a default is marked supported only where reference-host evidence exists), the long-campaign evaluation, backup/restore rehearsal, actual browser/accessibility review, calibrated rules, rights/publication review and required roadmap work complete. QA-38's disabled/text-only fallback is mandatory; full speech/model/GPU qualification is conditional on enabling that optional feature. QA-39 assesses enabled planning/review modes and always reports the bilingual baseline and unrun/disabled cells. It cannot require adopting a mode without measured benefit. The later editor's E-series checks do not become initial-v1 prerequisites.

QA-45's disabled/no-capture/complete typed fallback is mandatory. Full STT and
hands-free qualification is conditional on enabling the optional profile; publish
dictation-only or interruption limits honestly. Optional voice cannot delay MVP
or core-v1 text play and cannot bypass ordinary command/combat acceptance.

Failures are documented with severity and disposition. Secret leakage, canonical corruption, duplicate rewards, broken restore, or uncontrolled AI host capability block release. Cosmetic or optional-media issues can ship only with explicit scope/limitation notes and usable fallback.

## 9. Documentation verification

Before publishing this design set, verify internal relative links, required topic coverage, consistent terminology/formulas, Markdown fences, public language, truthful status, and absence of private campaign content. This is documentation QA, not implementation testing. Record the actual checks performed in the handoff rather than marking planned runtime criteria as passed.

The [research evaluation protocol](31-research-validation-and-evaluations.md)
defines paired A/B/C conditions, quantitative metrics, human rubrics, 100/500-turn
fault/continuity cases, live-cost controls and EN/IT speech/GPU qualification.
Objective integrity/security failures block release; human quality scores and
LLM-judge outputs never override deterministic invariant failures. No new
benchmark or runtime feature is verified by writing that protocol.
