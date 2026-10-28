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
  assembly (P09.2) and panels (P10.5) move to the typed views. Retiring the old table is decided
  then.
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

The triggers themselves (the turn ceiling, context pressure, owner and idle renewal), durable session records with counters and checkpoint hashes, and rehydration are P09.3/P09.4. The registry is in memory, so a backend restart already starts every scope afresh.

## 8. Summary lifecycle

Refresh a scene summary after each scene boundary and initially every five significant canonical actions, with a hard ceiling of ten. Keep a compact operational checkpoint current independently of AI-generated summaries. A background summary stores the source interval and revision, visibility, covered entities, and referenced event IDs.

Summarization may use Codex, but mechanical fields are generated from state. Validate source references, entity identity, unresolved commitments, and audience scope. Reject fabricated facts. Summary jobs are idempotent by audience and source range. If new events arrive during a job, keep its coverage precise rather than falsely marking it current.

Corrections mark affected summaries stale. Current structured facts always win during assembly. A failed summary job does not invalidate a committed game action or block deterministic inspections; retrieval can use a bounded recent-event window and checkpoint until repair succeeds.

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
