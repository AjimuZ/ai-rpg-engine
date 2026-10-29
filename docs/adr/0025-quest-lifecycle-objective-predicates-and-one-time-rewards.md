# ADR 0025: Quest lifecycle, objective predicates and one-time reward settlement

- Status: Accepted and implemented in P10.3 (29 October 2026). Quest panels and the journal (P10.5), world-time and bounded clock predicates (P10.4), encounter and fact predicates (P11, P09 follow-ups) and challenge-source rewards (P13.4) build on it.
- Date: 29 October 2026
- Builds on: [ADR 0003](0003-canonical-transactions.md) (one canonical commit), [ADR 0017](0017-bounded-pack-semantics.md) (bounded expressions), [ADR 0024](0024-atomic-item-commands-and-source-linked-creation.md) (item plan and creation ledger).

## Context

Pack quests were compile-time data: visibility, an initial status, an optional `availableWhen` predicate, objectives with `when` predicates, outcomes with item rewards. Nothing evaluated them. The specification requires that progress is derived from committed events and checked state, never from prose; that a completed quest cannot be re-awarded under a new paraphrase of the same source; and that a reward is a valid item with a recorded source (docs/10 section 5, docs/12 section 8).

## Decision

1. **A pure transition engine.** `QuestPlan` runs after the effects of every fact-changing command are known. Given the pinned quest rules, the stored quest state and the committed facts those effects produce, it returns the ordered steps that follow. It does no I/O, reads no clock, rolls no dice and never reads prose: the same inputs give identical steps.
2. **Lifecycle.** `undiscovered -> available -> active -> completed | failed`, and `active <-> suspended`. The player requests only `ACCEPT_QUEST` (`available -> active`), `SUSPEND_QUEST` and `RESUME_QUEST`; everything else is derived. An `undiscovered` quest becomes `available` in the first evaluation where its `availableWhen` holds (always, when it has none). An `active` quest marks each objective, in authored order, when the objective's predicate holds; the first outcome, in authored order, whose predicate holds ends the quest (`success` and `alternative` complete it, `failure` fails it). Suspended quests track nothing; completed and failed quests never change again.
3. **Predicates are the compiled world grammar.** `visited` (a location the player has been recorded at; the current location counts), `possesses` (units of a definition held across all the player's stacks, worn or not), `questStatus` and `objective`, combined by the existing boolean and integer operators. No atom reads randomness or the clock. Objective satisfaction is sticky and recorded: giving the proof away later does not undo it.
4. **Cascades in one commit.** Passes over the quests, in ascending identity order, repeat until nothing changes. A quest that ends can open or finish another, and reward units granted by an ending are visible to later predicates in the same evaluation. Every transition only moves a quest forward, which bounds the passes (64, defensive).
5. **Resolved with the command, committed with it.** The resolver runs the engine over the working inventory model and the destination of a move, then turns the steps into events, quest writes, reward settlements and reward-sourced item creations in the same `ResolvedCommit`. Quest events carry versions and bilingual narration statements; the player-safe receipt gains a `quests` list (`DISCOVERED`, `ACCEPTED`, `SUSPENDED`, `RESUMED`, `OBJECTIVE`, `COMPLETED`, `FAILED`).
6. **One-time reward accounting, three independent guards.** (a) The terminal `quest_state` row is immutable (trigger) and a quest in a terminal status is never evaluated again. (b) An ending with rewards writes one `reward_settlement` for the player under the source key `quest:<quest id>:outcome`; the table is unique per campaign, source and recipient, so even a defect that bypassed (a) cannot pay twice. The key names the quest, not the outcome, so alternative outcomes can never both pay. (c) Reward units are created through the shared `ItemPlan` with a `REWARD` source that must name that settlement, so the `item_creation` ledger of ADR 0024 stays the single record of created units and is unique per source, recipient and definition. Rewards naming the same definition are merged when the rules are projected.
7. **Storage (V20).** `quest_state` (a row per quest that has left its declared initial status, with the outcome of a terminal one, the announcing event and a storage version guarded by the writer), `quest_objective` (immutable satisfied objectives) and `location_visit` (immutable first visits; a move records both its origin and its destination, and the current location counts without a row). A quest with no row reads at its pack-declared initial status, so existing saves gain empty tables and keep their behavior. A trigger refuses leaving a terminal status and returning to `available`.
8. **Budgets defer, never drop.** An evaluation may add only as many events as the command left in the plan and may grant at most 32 reward units; the pack compiler rejects an outcome that rewards more than 32 units. A transition that would exceed a budget is deferred: its predicates still hold, so the next evaluating command settles it.
9. **Hidden background quests.** A quest declared `visibility: secret` is discovered and started by the engine together (no command can address it, and its identity answers like an unknown quest), its events stay engine-internal and out of receipts, and its ending is public: it is the moment the player learns of it. Its rewards are ordinary rewards.
10. **One refusal category.** `QUEST_UNAVAILABLE` (HTTP 422, `COMMAND_QUEST_UNAVAILABLE`) covers an unknown, undiscovered, hidden or ended quest and any status the transition does not leave. An undiscovered quest therefore cannot be told apart from a quest that does not exist.
11. **Recipient and attribution.** Rewards go to the acting player. The commit re-binds every settlement whose recipient names an actor, and every recorded visit, to the intention's participants, like every other effect; recipient keys that do not name an actor (a group policy for later encounter rewards) are not attributed.
12. **When the engine runs.** After every command that can change a fact a predicate reads: movement (visits), every item command and ability use (possessions), and the three quest commands. Waiting and speech change no such fact and skip it. The campaign's starting state is therefore evaluated at the first such command. Query scopes `quests` and `quest:<id>` (for local quest identities) are bumped for plans that read quest state.

## Alternatives considered

- Re-evaluating quests inside the commit, over the stored rows: rejected; events are counted into the campaign counters before any row is written, so a transition discovered late could not be announced, and the pure plan is far easier to test.
- Evaluating on every read or by a background worker: rejected; UI reads and workers must not advance the world, and progress would depend on timing.
- Keying the settlement by outcome (`quest:<id>:<outcome>`): rejected; two alternative outcomes could then both pay if the terminal guard failed. The quest is the source.
- Letting the model mark objectives complete: rejected; a model can only suggest that something looks satisfied, the predicate decides.
- Materializing a row for every quest at campaign creation: rejected; it would change creation and every existing save, and a lazy read at the initial status is equivalent.
- A foreign key from `reward_settlement` to `quest_state`: rejected; settlements are shared with encounters and milestones (P13).

## Consequences

- V20 adds `quest_state`, `quest_objective` and `location_visit`; the backup tool covers them at schema version 20. Existing packs and content hashes are unchanged, except that the compiler now rejects an outcome rewarding more than 32 units.
- `CommandRuleCatalog` carries a prose-free `QuestRules` projection, so no authored text is cached. `MOVE` now loads the catalog during preparation (it already did for rule validation).
- `ResolvedCommit` gains `QuestChanges`; `Resolution` gains rewards and quest changes. Receipts gain a `quests` list only when a public transition happened.
- One new refusal code, three new command types, seven new event types, each with reviewed English and Italian wording (the Italian wording still needs human review).

## Limits

- No journal, panel or quest screen (P10.5); `campaign_known_quest` (V10) is still the free-text player knowledge record and is not written by this engine.
- Predicates cover visits, possessions and quest state. Acquired-fact, defeated-encounter, completed-interaction and bounded-clock predicates are not in the compiled grammar yet (P09 follow-ups, P10.4, P11).
- `availableWhen` merges eligibility and discovery: becoming available is the discovery event. Rumor-style discovery by an issuer, and issuer proximity for accepting, belong with the knowledge integration (P10.5, P15).
- A quest cannot be abandoned by a command; failure is an authored outcome. Rewards are item units for the protagonist; XP, currency as a separate concept, reputation and party shares belong to progression (P13.4, P15.2).
- Deferred transitions (budgets) settle at the next command that runs the engine, not on a timer.
- No live V20 dump and restore rehearsal ran.

## Verification

Recorded with the P10.3 task in the [development roadmap](../16-development-roadmap.md).
