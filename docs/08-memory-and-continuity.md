# Long Sessions, Memory, and Continuity

## 1. Governing principle

The campaign database is the source of truth. An AI thread is disposable working context. A public transcript is a record of player-visible communication. A summary is a lossy, derived retrieval aid. None can silently replace the others.

Versioned narrative plans are another derived layer: hypotheses and future
opportunities with explicit source/dependency references, never past events or
truth merely because the planner wrote them. The [planning specification](28-asynchronous-narrative-planning.md)
extends this memory contract without replacing canonical retrieval.

The primary project supplies the layered-memory design. The demo adds operational discipline: stable identifiers, explicit source precedence, read-before-resume, checkpoint verification, short continuity summaries, renewable threads, immutable history, and no “saved” claim without a successful write.

## 2. Authority and conflict resolution

Apply the following order:

1. Engine permissions, supported schema, and ruleset invariants.
2. Explicit validated administrative corrections recorded in canonical state.
3. Current campaign state and committed events, including actual discoveries.
4. Pinned pack definitions for unchanged world facts and initial conditions.
5. Provenance-linked summaries and retrieved documents.
6. Current uncommitted model proposals.

A correction cannot bypass engine security. An evolving campaign can supersede an initial pack condition through a recorded event. A summary cannot restore an old inventory or resurrect a resolved encounter. Contradictions are resolved from sources and event order; unresolved contradictions are surfaced without inventing a reconciliation.

## 3. Memory layers

| Layer | Contents | Update policy |
| --- | --- | --- |
| Structured state | Resources, inventory, skills, quests, relationships, location, encounter | Every canonical operation |
| Immutable event history | Actions, consequences, rewards, discoveries, corrections | Same transaction as state |
| Fact and knowledge graph | Claims, owners, observations, rumors, truth status, provenance | On discovery or accepted world addition |
| Operational checkpoint | Current situation, next decision, source revision, encounter/RNG cursors | At safe boundaries; regenerate from canonical state |
| Scene/continuity summary | Recent events, tone, commitments, goals, unresolved issues | Scene change or bounded significant-event interval |
| Entity summaries | One NPC, quest, location, faction, ability | Only when relevant source events change |
| Chapter summary | Long arc and consequences with event references | Explicit arc boundary |
| Search indexes | Structured/full-text references; optional later semantic vectors | Asynchronous, rebuildable |

Store important dialogue as an exact quote only if future continuity needs its wording, together with speaker, listeners, event, and time. A general summary must not invent a promise or convert speculation into a quotation.

## 4. Knowledge model

A fact records claim, subject, predicate, object/value, canonical truth status, who knows or believes it, discovery source, observed time, valid interval, and supersession. Useful statuses are confirmed, rumor, belief, hypothesis, planned, and unknown. “Private to the GM” is a visibility scope, not proof of truth.

The player may know a location through a map without visiting it. An NPC may have left its last observed position. A private guide may have no remote senses. A drawing may depict a symbolic interaction without establishing a physical event. These distinctions come directly from the demo's practical continuity problems and must be represented as data.

Secret information must be filtered before retrieval and prompt assembly for a limited audience. Filtering only the final response is insufficient. Asset selection, music, loading messages, tooltips, filenames, tactical suggestions, and search snippets are also disclosure channels.

### P09.1 typed knowledge

Implemented in `org.rpgengine.knowledge` with migration V15. Four records keep truth, belief,
position and obligation apart:

| Record | Holds | Never holds |
| --- | --- | --- |
| `knowledge_claim` | Typed proposition: subject (`actor:`/`item:` row or `location:`/`definition:` content id), predicate (`EXISTS`, `PROPERTY` with key and scalar value, `RELATION` with key and object, `STATEMENT`), optional statement text with its language, disclosure (`COMMON` or `HOLDERS`), privileged truth, provenance, validity interval | An actor's location; an opinion; a plan assumption |
| `knowledge_belief` | One holder's stance (`KNOWS`, `BELIEVES`, `RUMORED`, `SUSPECTS`, `DOUBTS`) on one claim, acquisition (`OBSERVED`, `TOLD` + informant, `READ` + item or definition, `INFERRED`), learned revision/time/event, supersession | Truth |
| `actor_sighting` | Observer's last known location of another actor (`OBSERVED` or `TOLD` + informant), when and from which event, and a later observed absence | The subject's real location (that is `actor.location_id`) |
| `actor_commitment` | `PROMISE`, `OBLIGATION` or `AGREEMENT` from debtor to creditor; exact terms and their language, `quoted` flag, optional subject and deadline; `OPEN` until one terminal resolution | A derived "expired" status |

Rules (each enforced in the ledger and repeated by V15 triggers or constraints):

- **Truth versus belief.** Truth is set when the claim is registered and an `UNDETERMINED` claim
  may be resolved to `TRUE` or `FALSE` once, at a later revision. A determined truth never
  changes. A world change registers a superseding claim about the same subject, and the old
  claim keeps its truth for its interval `[established, superseded)`. `KNOWS` needs a current
  `TRUE` claim when written. Every other stance may concern a false or undetermined claim,
  because a character can be wrong.
- **Scopes.** Disclosure decides who sees a proposition without holding a belief. `COMMON` is
  common knowledge of every actor. `HOLDERS` reaches an audience only through its own belief.
  A secret no one holds is a `HOLDERS` claim without beliefs: privacy says nothing about truth.
  Commitments are `PARTIES` (debtor and creditor) or `COMMON`.
- **Provenance.** Each record cites an event of the operation that wrote it, by index into that
  operation's events. The database checks that the cited event belongs to the recorded revision.
  Beliefs and claims are replaced, never edited: the predecessor is closed at the committing
  revision and the successor names it. A belief successor keeps the holder but may concern
  another claim, and a claim successor keeps the subject.
- **Current versus last known location.** After every committed registered intention, the
  player observes the other actors at its resulting location who pass the `SPEAK` perception
  rule. An `OBSERVED` sighting is written for each, and an absence for each earlier sighting at
  that place whose subject is no longer perceived. No observation is recorded without a pinned
  catalog, without a current location, or with more than 64 actors present, so an absence is
  never inferred without a perception basis. An explicit `OBSERVED` sighting must match the
  subject's real location at commit. A `TOLD` sighting records what the observer was told,
  even if it is wrong.
- **Commitments.** Lateness is derived at read time (`overdue` when world time is past the
  deadline). Reading never resolves, expires or advances anything.
- **Reveals.** Rules disclose a `knowledge: secret` location or NPC origin to the player only
  through a current `TRUE` `EXISTS` claim about that location or actor. The claim must be
  `COMMON`, or the player must currently `KNOWS` or `BELIEVES` it. Rumors, suspicions, doubts,
  superseded beliefs, false or undetermined claims, another actor's knowledge and free-text
  `campaign_known_fact` rows never reveal anything. Refusals keep the P07.3 categories, so they
  still do not confirm that a hidden location or actor exists.
- **Attribution.** A registered player intention may only write knowledge records of its
  participants (holder, observer, informant, debtor, creditor). Being the subject of a claim or
  sighting is not an effect.
- **Invalidation.** Writers bump `knowledge:claims`, `knowledge:<holder>`, `sightings:<observer>`
  and `commitments:<party>`, and step row versions. `plan_reference_version` evaluates `claim:`,
  `belief:` and `commitment:` references, so a plan that read such a row becomes stale.

`KnowledgeView.forActor` is the audience reader. It returns, in one repeatable-read snapshot:

- the actor's current beliefs, with claim content but without truth or disclosure;
- current `COMMON` claims it holds no belief about;
- its own current location;
- its last known locations of others;
- the open commitments it is party to or that are common knowledge.

`KnowledgeView.privileged` adds truth, intervals, every holder's beliefs, event sequences, real
current locations and resolved commitments, for privileged roles only. Each list is bounded at
1,000 rows; exceeding the bound fails instead of truncating.

Decisions and limits:

- The P06.2 free-text `campaign_known_fact` projection stays unchanged and has no writer. It is
  not a rule source, and the quest planner's `fact-known-to` predicate still reads it. Context
  assembly (P09.2) reads only the typed views. Panels move in P10.5, which also retires the old
  table.
- Only trusted resolvers write knowledge. No player command, effect node or HTTP route creates
  claims or commitments yet: the pack `reveal` effect, plan consumers (opportunities, adopted
  pending events) and item creation stay P10. NPC-side observation, witnesses of a commitment
  and relationship episodes are P11/P15. Statements and terms keep the language they were
  written in. They are not translated.

## 5. Context assembly

Each task receives a stamped context envelope:

- Campaign, operation, role, audience, base revision, ruleset hash, pack hash, and context digest.
- Current scene, present actors, legal options, pending player decision, world time, and active hazards.
- Relevant character resources, abilities, equipment, conditions, and tactical position.
- Related quest predicates, unfinished promises, known clues, and relevant faction clocks.
- A short fresh scene summary and selected recent events.
- Retrieved older facts and dialogue with provenance.
- Task-specific output constraints and forbidden authority.

Retrieve by explicit entity IDs and causal links first, then recency/importance, then full-text search. Later semantic candidates must pass the same campaign/audience/freshness filters. A semantically similar old event cannot override current state.

Ranking should favor unresolved obligations, current NPC relationships, active effects, quest conditions, and direct references in player intent. Deduplicate by source event/fact. Include contradictory evidence with labels when a character is uncertain; do not flatten it into a confident statement.

## 6. Context budget

Compute a task input budget from the supported model's effective context allowance, output reserve, protocol overhead, and application cap. For the initial standard profile, a proposed cap is **12,000 input tokens** with **2,000 output tokens** and an additional safety reserve. These are application defaults, not claims about a model's maximum.

An illustrative 12,000-token allocation is 2,000 for task/rules, 3,000 for current state, 2,000 for relevant objectives and commitments, 3,000 for selected memory, and 2,000 of flexible headroom. Measure with an appropriate tokenizer or conservative estimator and record estimation uncertainty.

Mandatory state is never silently truncated. If it cannot fit, reduce task scope, use an approved higher-capacity profile, or return a controlled context-too-large error. Remove low-ranked optional history first. No full campaign transcript is appended by default.

### P09.2 context envelopes, budgets and retrieval

Implemented in `org.rpgengine.memory` (`ContextAssembler`, `ContextEnvelope`, `ContextItem`,
`ContextSelection`, `ContextBudget`, `TokenEstimator`, `PackGlossaries`). No migration is needed.
Contract callers have not been switched to the envelope yet (see limits).

**Audience first.** Candidates are read for exactly one audience, inside one read-only
repeatable-read snapshot, before anything is ranked. A record the audience may not see never
reaches ranking, selection, rendering or a digest.

| Audience (role must permit it) | Knowledge | History | Planning |
| --- | --- | --- | --- |
| `public` (player character) | `KnowledgeView.forActorWithin`: own beliefs without truth, current `COMMON` claims, last known locations, open commitments it is party to or that are common | `PUBLIC` events stated by `EventStatements` in the output locale, published narration | none |
| `character:actor:<uuid>` | the same view for that actor | none (no record yet of what an NPC witnessed) | none |
| `privileged` | `KnowledgeView.privilegedWithin`: current claims with truth and disclosure, every current belief, sightings with the real location beside the last known one, open commitments | as public | assumptions of the newest version of each usable quest plan: gate-admitted in the current recovery generation, latest disposition `VALIDATED` or `PARTIALLY_INVALIDATED`, at most 32 artifacts, with `fresh` (every whole-plan dependency still current) |

**Standings.** Every item is rendered with a `standing` label, and the envelope carries a fixed
English legend for the labels present. Admitted canonical records are `state`, `event`, `fact`
(privileged: truth and interval), `common-knowledge` and `obligation`. Perspectives are
`belief` and `last-known`, which may be wrong. `narration` is text already told to the player,
not a source of truth. `assumption` is a planner's private, unverified hypothesis, never
admitted and never public. `fact` and `assumption` are privileged-only. Assumptions live only
in the `planning` section. A limited-audience envelope containing either is refused at
selection and again at construction: an assembler defect throws, it is never filtered silently.

**Stamp and digest.** The stamp records adventure, campaign, recovery generation, state
revision, world time, role, audience, holder, focus operation, pack, ruleset and policy hashes,
output locale with its locale and glossary revisions, and the query-scope epochs read. A public
or character stamp records `knowledge:claims`, `knowledge:<holder>`, `sightings:<holder>`,
`commitments:<holder>` and `actor:<holder>`; a privileged stamp records `knowledge:claims`
(the revision covers the rest). The digest is the SHA-256 of the exact compact JSON.
`ContextAssembler.staleness(stamp)` lists what changed since: `generation`, `revision`,
`locale`, `glossary` and `epoch:<scope>`.

**Retrieval tiers** (best first; a record found twice keeps its best copy):

1. `required`: mandatory material (below).
2. `id`: the record, or one of its subjects, objects or parties, is an explicit focus
   reference. An `event:<sequence>` or `narration:<operation>` focus reference also fetches
   that public record when it is older than the recent window. Actors named by the focus
   operation's events join the focus.
3. `causal`: written at the focus operation's revision, or told by a focus actor.
4. `text`: PostgreSQL full-text match of the task query (`websearch_to_tsquery`). Each text is
   searched with the stemming configuration of its own language (`english` or `italian`):
   claim statements and commitment terms by their recorded language, published narration by its
   passage language, and public event payload strings by the campaign output locale. Only rows
   the audience may already read are searched: claim and commitment ids from its own view, and
   public events and narration over the whole campaign history, at most 16 matches per source.
5. `recent`: everything else, by importance (for example overdue commitments, firm stances and
   fresh assumptions first), then recency.

The recent window is the latest 64 public events and 16 narration publications outside the
focus operation. Older history is reachable through `id` and `text` retrieval.

**Mandatory material** is the audience's own state, the actors it currently sees at its
location (a sighting at its location without a later absence), the open commitments it is party
to, and the focus operation's public events (at most 64; more is a programming error). For a
privileged audience it is the player character's state and the focus events.

**Budget** (`ContextBudget`). The standard profile follows section 6: 12,000 input tokens, a
2,000-token output reserve and a 1,000-token safety reserve kept outside the input.
`forModel(effectiveContext)` takes the smaller of 12,000 and the context left after the
reserves and 500 tokens of protocol overhead, and scales the allowances proportionally. Section
allowances bound optional material only:

- 3,000 for `state`;
- 2,000 for `obligations`;
- 1,500 for `knowledge` and 1,500 for `history` (the 3,000 of selected memory);
- 1,000 for `planning`, privileged audiences only, taken from the headroom.

The caller's instruction estimate is charged first. The rest is shared headroom.

**Estimation** (`TokenEstimator`, method `utf8-bytes-div-2-v1`, recorded in every envelope):
one token per two UTF-8 bytes, rounded up. No runtime tokenizer is available to the backend,
and identifier-heavy JSON can approach two bytes per token, so this is an intentional upper
bound rather than an average. It is subadditive. The envelope overhead is measured with
worst-case omission counts and every legend entry. Each item is charged its own rendering
plus one separator and the glossary entries it brings. After rendering, the assembler checks
that the real envelope does not exceed the charged total, and throws on a violation. P21
measurements may replace the method.

**Selection** (`ContextSelection`, pure and deterministic):

1. Charge the fixed cost and every mandatory item. If they exceed the input budget, the result
   is `TooLarge` (`CONTEXT_TOO_LARGE`) with the required tokens and the mandatory cost of each
   section. Nothing is truncated, and the caller must narrow the scope, choose an approved
   larger profile or report the error.
2. Each section's allowance is reduced by its mandatory cost. When the room cannot cover every
   allowance, later sections (`planning`, then `history`, `knowledge`, `obligations`) lose
   allowance first.
3. Optional items are taken in rank order, first within the allowances, then from the shared
   headroom. A section stops at its first item that does not fit, so a lower-ranked item is never
   kept while a higher-ranked one is dropped.

Every dropped candidate is counted per section in `omitted`, and `partial()` reports it. A
partial context is never presented as complete. No transcript is appended.

**Language and glossary binding.** Each text item carries its own language (`lang`). Statements
and terms keep the language they were written in, and narration keeps its passage language.
Event statements are rendered in the output locale. The envelope stamps the output locale with
its locale and glossary revisions. `PackGlossaries` binds the pinned root pack's reviewed
glossary entries for the `location:` and `definition:` references the candidates name:

- a rendering resolved for the output locale (overlay, or source text);
- for a `term`, the other locale's text as an avoided form;
- for a `properName`, one spelling.

Only definitions named by audience-filtered items are bound, so a glossary entry never
authorizes knowledge. Terms are charged to the items that name them. The envelope keeps the
selected items' terms in render order up to the contract bound of 128, and counts the rest in
`glossaryOmitted`. A revision that cannot be loaded is rendered as
`glossaryStatus: unavailable` with no terms. Dependency-pack glossaries are not bound yet.

**Disclosure guard input.** `withheldPhrases(campaign, holder)` returns privileged phrases for
the P08.2 `DisclosureGuard` and is never rendered:

- statements of `HOLDERS` claims the holder has never held a belief about;
- terms of `PARTIES` commitments it is not party to.

Newest records come first. A phrase shorter than 12 folded characters is skipped, longer ones
are cut to 200 characters, and at most 256 are returned. A false secret is still a secret.
Paraphrase is not detected.

Decisions and limits:

- Plan storage stays owned by the preparation package (ADR 0014, `PlanningBoundaryTest`).
  The assembler receives assumptions as detached records from
  `org.rpgengine.preparation.PlanAssumptions.usableWithin` and never queries plan tables.
- The assembler never reads the free-text `campaign_known_fact` projection. That table keeps no
  writer and is still read by the player panel and the planner's `fact-known-to` predicate. It
  is retired together with the panel move in P10.5.
- The P08.2 intent and narration contracts and the P08.7 planner still build their own contexts.
  Feeding them from `ContextAssembler` is the job of each caller: P09.4 renewal and rehydration,
  and P10 and later gameplay callers. This task supplies the envelope, the budget, retrieval and
  overflow behaviour they must use. The first caller is the P09.3 continuity summarizer.
- The `causal` tier uses revision equality with the focus operation and the informant. Explicit
  event-to-event causal links do not exist yet.
- A `character` context has no history and no witnessed events until NPC-side observation exists
  (P11/P15).
- Semantic retrieval stays out of scope (section 11).

## 7. Renewal policy

The initial completed-task ceiling is **10 AI turns per logical role/audience session**, configurable from 1–100. Local consultations do not count. Every completed provider task, including read-only questions or repairs in that thread, counts. Errors with uncertain context retire the affected thread.

Renew at the earliest safe boundary when the ceiling is reached, context pressure crosses a configured threshold, an explicit owner command requests it, the selected narration runtime changes ([ADR 0020](adr/0020-selectable-narration-runtime.md)), content/policy hashes change, or a prior failure makes context unreliable. Initial warning and renewal thresholds are 75% and 85% of the effective context budget; tune from measurements. The count ceiling is a fallback, not a guarantee of memory quality.

Renewal steps:

1. Finish or cancel the current task; never renew inside an unresolved commit race.
2. Reconcile the operation receipt and source revision.
3. Validate the canonical checkpoint and its pending decision.
4. Refresh required summaries or use a deterministic checkpoint digest if summarization is unavailable.
5. Mark the previous session retired and create a new session generation.
6. Start a fresh thread or session of the selected runtime with the same role/audience policy and rehydration envelope.
7. Verify the source revision/context digest before accepting its first proposal.
8. Preserve the campaign, public transcript, current encounter, and all committed consequences.

Renewal is not a new campaign, a replay of the prologue, a rest, a round boundary, or an action by any character.

Since P08.3 the session-scoping registry ([docs/06 section 7](06-codex-integration.md#p083-session-scoping)) provides the retirement half of these steps, and every retirement reaches the runtime adapter:

- A content or policy hash change, or a new campaign generation, renews the affected line automatically.
- `retire(scope)` renews one role/audience session, for example after a failure made its context unreliable.
- `retireCampaign` and `retireAll` cover restore and a runtime change.

The triggers themselves (the turn ceiling, context pressure, owner and idle renewal), durable session records with counters and checkpoint hashes, and rehydration are P09.4. P09.3 supplies the parts they build on: verified operational checkpoints, audience-scoped continuity summaries with freshness, and the summary fallback rule (section 8). The registry is in memory, so a backend restart already starts every scope afresh.

## 8. Summary lifecycle

Refresh a scene summary after each scene boundary and initially every five significant canonical actions, with a hard ceiling of ten. Keep a compact operational checkpoint current independently of AI-generated summaries. A background summary stores the source interval and revision, visibility, covered entities, and referenced event IDs.

Summarization may use Codex, but mechanical fields are generated from state. Validate source references, entity identity, unresolved commitments, and audience scope. Reject fabricated facts. Summary jobs are idempotent by audience and source range. If new events arrive during a job, keep its coverage precise rather than falsely marking it current.

Corrections mark affected summaries stale. Current structured facts always win during assembly. A failed summary job does not invalidate a committed game action or block deterministic inspections; retrieval can use a bounded recent-event window and checkpoint until repair succeeds.

### P09.3 checkpoints, summaries, freshness and bounded jobs

Implemented in `org.rpgengine.memory` (`OperationalCheckpoints`, `MemorySummaries`,
`ContinuitySummarizer`, `MemoryWorker`, `MemorySettings`) with the `summary` contracts
([docs/21 section 15a](21-ai-task-contracts-and-prompts.md#15a-implemented-continuity-summary-contract-p093))
and migration V16. Nothing here writes canonical state: no checkpoint, summary, job or
invalidation advances the revision, the game turn, world time or the event sequence.

**Operational checkpoint.** `OperationalCheckpoints.capture` stores one row of the existing
`campaign_checkpoint` table per revision. The snapshot is the `jsonb` value of the database
function `memory_operational_snapshot` (V16), built only from canonical rows:

- the counters, mode and pending decision of the campaign;
- the player character and every actor with its real location;
- resources, items, learned abilities and known quests;
- open commitments;
- operations whose narration is still pending (`COMMITTED`, `NARRATING`,
  `COMMITTED_NARRATION_PENDING`);
- the RNG cursor: algorithm, version and number of recorded draws, never the seed;
- `encounter`, always null until P11.

The hash is the SHA-256 of PostgreSQL's canonical `jsonb` text, computed in SQL, so equal state
always gives an equal snapshot and hash. A capture runs in one repeatable-read transaction and
returns `UNSAFE_BOUNDARY` without writing while a mutation lease is live or an operation is
`COMMITTING` (never inside an unresolved commit race, section 7). A second capture of the same
revision returns `EXISTING`, or `MISMATCH` when the stored row differs from canonical state.
`verify` reports `NONE`, `CORRUPT` (snapshot no longer matches its hash), `UNSUPPORTED` (another
layout), `INCOMPATIBLE` (pinned content or ruleset hash changed), `DIVERGED` (ahead of the campaign,
or different at the same revision), `OUTDATED` (valid but older) or `VALID`. The snapshot is
privileged backend data and is never rendered to a limited audience. Retention keeps the newest 8
per campaign (`engine.memory.checkpoint-retention`, 2–64); a checkpoint is a resume aid, not a
backup (section 9).

**Summary record** (`memory_summary`, immutable). One accepted summary of one audience, `public`
or `privileged`; character audiences have no history yet (P09.2). It stores:

| Field | Meaning |
| --- | --- |
| `covered_events` | Exact, increasing event sequences supplied and claimed (1–32) |
| `sources` | Every supplied knowledge record as `{ref, version}`: `belief:`, `claim:` or `commitment:` with its row version |
| `entity_refs` | Actor and location references the summary names |
| `source_revision`, `event_high_water` | Revision the context was assembled at; newest event at that revision |
| `campaign_generation`, `locale`, `locale_revision`, `glossary_revision` | Presentation and recovery binding of the context |
| `invalidation_high_water` | Newest invalidation of the campaign when the summary was recorded |
| `context_digest`, `template_hash`, `body_digest` | Provenance of the context, prompt template and stored body |
| `body` | `summary`, `obligations`, `entities` and `uncertainties` notes |

**Freshness** (`MemorySummaries.freshness`, computed, never stored):

- `STALE` when the recovery generation changed (restore), the output locale or its locale or
  glossary revision changed, a supplied source's row version changed (a belief or claim was
  superseded, a claim's truth was determined, a commitment was resolved), or a later
  `memory_invalidation` names one of its events, sources or entities, or the whole campaign.
  The reasons are `generation`, `locale`, `glossary`, `source:<ref>` and `invalidated:<reason>`.
- `BEHIND` when it is otherwise valid but newer public events exist. Its coverage stays precise.
- `CURRENT` otherwise.

Freshness does not use the broad query-scope epochs of the stamp. The sighting epoch steps on
almost every action, so epochs would make every summary stale without cause; the precise row
versions of the supplied records are the relevant dependencies (ADR 0014). Generation, locale
and glossary are compared as `ContextAssembler.staleness` compares them.

**Assembly.** For public and privileged audiences the newest summary of the current generation
that is not stale (at most 8 newest are examined) is an optional `history` candidate with
standing `summary`. Its legend reads "A derived summary of the listed earlier events; current
state and events win over it". It carries its covered events, freshness (`current` or `behind`),
the count of newer events, the text and the obligation and uncertainty notes, and it ranks above
individual events and narration. It is never mandatory, so state and focus events always win the
budget. A stale summary is never assembled. A public summary never reaches a privileged context
and the reverse.

**Explicit invalidation.** `MemorySummaries.invalidate(campaign, reason, refs)` appends to
`memory_invalidation` with a fixed reason code (for example `CORRECTION`) and up to 64
references (`event:<sequence>`, `actor:`, `location:`, `claim:`, `belief:`, `commitment:`); an
empty list invalidates every earlier summary of the campaign. No correction command exists yet;
future correcting writers must call it inside their canonical transaction, as they bump query
epochs today.

**Jobs** (`memory_summary_job`). `MemorySummaries.request(campaign, audience, force)`:

1. Returns `DISABLED` without writing when summaries are off (`engine.memory.summaries.enabled`,
   default false).
2. Returns `PENDING` while the audience has an unfinished job (one per campaign and audience,
   enforced by a partial unique index).
3. Otherwise looks at the audience's public events after the newest usable summary, at most 32:
   `UP_TO_DATE` when there are none; `NOT_DUE` unless they come from at least
   `engine.memory.summary-interval` (default 5, at most 10) committed operations or include a
   scene boundary (the player character's `ACTOR_MOVED`), or `force` is set.
4. Returns `GAVE_UP` when a job for exactly this range already failed or was cancelled. A failed
   range is not retried automatically until new events extend it, so a failing summarizer cannot
   loop.
5. Returns `THROTTLED` when `engine.memory.max-pending` (default 256) jobs are unfinished
   installation-wide (counted under an advisory lock), otherwise `REQUESTED`.

`ContinuitySummarizer.processNext` first recovers (queued jobs past their window fail `EXPIRED`;
queued jobs of a retired generation are `CANCELLED` with `GENERATION_RETIRED`; a running job whose
lease expired is queued again with a new fence, or fails `LEASE_EXPIRED` when attempts or window
are spent). It then claims one due job of a current generation, which increments its attempt and
fence:

1. It assembles the audience's envelope with `ContextAssembler`: the job's events are explicit
   focus references; earlier summaries are excluded; the budget is the standard 12,000 input tokens
   with 5,000 for history, 2,500 for knowledge, 1,500 for obligations and 500 for state.
2. It supplies the contiguous run of selected events from the range's first event; an event the
   budget dropped ends the run, so coverage never has a gap. Sources are open obligations first,
   then beliefs, common knowledge and, for the privileged audience, facts with truth. Sightings
   and planning assumptions are never summary input.
3. It runs the contract as `CONTINUITY` work through the fair scheduler, with one repair.
4. It records under the campaign memory lock (a per-campaign advisory lock, also taken by
   `invalidate`). The recording rechecks lease, fence and generation, the locale and glossary
   revisions, that no supplied record changed after the assembled revision (each changing write
   records its revision), and that no invalidation since assembly names the summary. A failed
   recheck stores nothing and releases the attempt as `STALE_SOURCE` for a retry.

Failure mapping: invalid output after the repair (`INVALID_SUMMARY`), a runtime that did not
complete (`RUNTIME_UNAVAILABLE`, `RUNTIME_FAILED`) and `STALE_SOURCE` are retryable within
`engine.memory.max-attempts` (default 3) with a 30-second-per-attempt backoff. A declined
summary, a refused scope, an oversized context and an empty range end the job. A released or
expired worker can neither fail nor record again, because the fence moved.

**Worker** (`MemoryWorker`, `engine.memory.worker.enabled`, default true, every 5 seconds). One
bounded pass captures checkpoints for at most 16 campaigns whose newest checkpoint is older than
their revision, prunes them to the retention bound and, when summaries are enabled, requests
public summaries for at most 16 campaigns and runs at most one job. Privileged summaries are
requested by privileged callers. A failing pass is logged by exception class and retried.

Decisions and limits:

- Summaries are off by default. Nothing consumes them until renewal and rehydration (P09.4), and
  every context already falls back to the recent-event window and current state.
- One summary kind exists, `CONTINUITY`. Entity and chapter summaries (section 3) are not
  implemented. Narration passages are not summary input.
- Freshness checks the records supplied to the summary, not every record the prose could imply.
  A summary that paraphrases a secret or calls a promise kept is beyond mechanical validation.
- Durable AI-session records with counters and checkpoint hashes, and switching the intent,
  narration and planner callers to envelopes, are P09.4. Restart reconciliation of stale summaries
  and missing dependencies, and the resume card, are P09.5.

## 9. Archiving and backups

The demo's suggestions of backups every 25 turns and archival after approximately 150 events are useful prototype policies, not database limits. AI RPG Engine should retain all committed events while moving cold ranges out of the interactive query path as needed. Do not delete events merely to shorten an AI prompt.

Initial policy: retain at least five rolling automatic recovery snapshots, create a snapshot every 25 state-changing player turns and before migration/correction/import replacement, and keep daily database backups according to owner retention settings. A checkpoint in the same database is not an independent backup.

Archive indexes preserve event ranges, checksums, schema versions, and discoverability. Never archive away unresolved quest facts, owned items, active conditions, valid promises, or required encounter state.

## 10. Resume protocol

On startup, reconcile unfinished operations and leases. Load the last committed campaign state, not the last chat message. Reconstruct the current location, visible actors, resources, active encounter, pending decision, and any committed action awaiting narration. Show a short factual resume card without advancing time.

If content or ruleset dependencies are missing, pause mutation and offer restore/import diagnostics. Do not substitute another pack. If the checkpoint hash is invalid, recover from canonical state or a verified backup; never fabricate a clean state.

## 11. Long-session quality measures

Extend the existing measures with plan-specific dependency recall, stale branch
rejection, remembered motives/relationship causes and direct EN/IT continuity.
The [evaluation protocol](31-research-validation-and-evaluations.md) separates
100/500-turn fault/replay checks from budgeted live narrative/human evaluation.
No additional vector store is admitted by this design update.

Track unresolved-promise retention, entity consistency, incorrect known-location claims, quest repetition, secret leakage, stale summaries, context size, renewal frequency, and response latency. A 500-turn seeded scenario with frequent context renewal is a required v1 evaluation. Its success criteria are specified in [quality and acceptance](15-quality-and-acceptance.md).

Semantic retrieval with pgvector is optional after v1 evidence shows structured/full-text retrieval is insufficient. An embedding provider introduces a new data and dependency decision; it must not silently reintroduce generic AI models into this Codex-only first release.
