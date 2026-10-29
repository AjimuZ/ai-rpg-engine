# ADR 0026: Bounded world clock, travel duration, waiting limits and rest

- Status: Accepted and implemented in P10.4 (29 October 2026). The journal and panels that show the clock (P10.5), per-round combat time (P11), faction clocks and bounded time catch-up (P15.2) and condition ticks over rounds (P11) build on it.
- Date: 29 October 2026
- Builds on: [ADR 0003](0003-canonical-transactions.md) (one canonical commit), [ADR 0023](0023-character-rules-and-equipment-constraints.md) (conditions and resources), [ADR 0025](0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md) (quest predicates).

## Context

World time already existed as a counter (`campaign.world_time_seconds`, copied onto every event), but nothing gave it meaning: `WAIT` added the requested seconds up to a structural ceiling of one day, movement and every other action took no time, and there was no rest. The specification asks for travel with durations, rest as a rules-driven action rather than a session reset, a clock that is bounded, and that "UI reads do not trigger hidden world advancement" (docs/10 sections 2, 7 and 8).

## Decision

1. **A bounded counter in whole seconds.** `WorldClock` (pure) defines the clock. A campaign day is 86,400 seconds, days are numbered from 1, and there are no named months. One operation adds at most one in-world day (`MAX_STEP_SECONDS`), and the counter never passes 3,155,760,000 seconds (one hundred 365.25-day years, `END_SECONDS`). A step that does not fit is refused before anything is written (`TIME_ILLEGAL`).
2. **Time moves only through a committed command.** The clock changes in exactly one place, `CanonicalCommitService`, from the plan's `WorldChange.elapsedSeconds`. Queries, projections, resume cards, streams, workers and model output never call it. V21 enforces this against any writer: a `campaign` update may change `world_time_seconds` only in the same update that advances `state_revision`, by at most 86,400 seconds, never backwards (the older V2 monotonic trigger) and never past the end. `campaign_event` carries the same upper bound. Real (wall-clock) time is unrelated: a campaign left open for a week has the same world time.
3. **Travel duration is authored on the link.** A location link has optional `minutes` (0..1,440, default 0 for an instantaneous link). A move adds the duration of the link it follows, which `CommandRuleCatalog` projects per accessible destination. `ACTOR_MOVED` carries `seconds` and its narration statement states the trip only when it took time.
4. **Waiting is tighter than the wire bound.** `WAIT` still decodes 1..86,400 seconds (a structural limit that keeps old requests parseable) but the rule gate allows at most four hours (`MAX_WAIT_SECONDS`). Longer time passes only through a journey or a rest, which declare their own duration. A longer wait is `TIME_ILLEGAL`.
5. **Rest is a pack rule, not a payload.** A `rule` definition of type `rest` lists policies: `minutes` (1..1,440), an optional required `locationTag` carried by the player's location, an `interruptionChancePercent` (0..99), `restores` (resource, amount) and `removesConditions`. A policy must restore or remove something. The `REST` command names only the policy; the player cannot supply a duration, an amount or a condition. A rest is refused (`REST_UNAVAILABLE`) when the policy is unknown, the location lacks the tag, or the policy restores resources of a vocabulary other than the player's (resource keys are unqualified and belong to the declaring pack, exactly as for ability costs).
6. **Interruption is one recorded draw.** A policy with a chance reserves one durable percentile draw (1..100, the existing HMAC draw stream, ordinal after any earlier draws). A draw at or below the chance interrupts: half the time passes (at least one minute), each restoration is halved rounding down, and no condition is removed. Any other draw, and any policy without a chance, runs to the end. A retry replays the same recorded draw; a policy that cannot be interrupted reserves nothing.
7. **Restoration and removal are exact.** Each restored pool rises by the policy amount, clamped to its maximum. Each listed condition the player has is removed; one that is absent is not an error. The rest is an action even when it changes nothing (it still takes its time and advances the turn). Events: `REST_COMPLETED` or `REST_INTERRUPTED`, then one `RESOURCE_RECOVERED` per changed pool and one `CONDITION_REMOVED` per ended condition. The receipt gains a `rest` summary only for a rest.
8. **Condition removal is a typed effect.** `ResolvedCommit` carries `ConditionRemoval`s, bounded to 16, attributed to the intention's participants and applied by `CharacterService.removeCondition` inside the commit; a condition that vanished between resolution and commit is a stale plan and rolls everything back.
9. **A clock threshold in quests.** The world-scope atom `{"worldTime": "<unit>"}` (`seconds`, `minutes`, `hours`, `days`) evaluates to whole elapsed units, rounded down, with the clock's own proven interval, and is compared with the existing operators. The value is the clock after the command's own duration, so a wait, a journey or a rest can complete a quest in the commit that moves the clock. `WAIT` therefore loads the pinned catalog when one is available (it stays legal without one).
10. **Defence in depth.** The bound is checked at four places: the rule gate (refusal), the resolver (`WorldClock.advance`), `WorldChange` and `Campaign.commitWorld` (programming errors) and the database (V21).
11. **Not decided here.** Condition durations are in combat rounds, so rest and waiting do not tick them (P11 owns rounds and the partial-round time boundary); faction and event clocks, bounded catch-up over many due boundaries (docs/10 section 7, P15.2) and calendar names are later work. A journey is one link; a multi-link route is several commands.

## Alternatives considered

- Interrupting a rest with an authored encounter: rejected; encounters need P11. A percentile draw is a deterministic, recorded placeholder that a later encounter can replace without changing the player's command.
- Letting the player choose a rest length: rejected; the duration is a rule, otherwise it is a second `WAIT` with a free amount of recovery.
- A pack-wide default travel time: rejected; a hidden default would change existing content. Zero stays the explicit "instantaneous".
- A real-time clock or server-side ticking: rejected; it contradicts "no real-time wall-clock world advancement" and would make state depend on when it is read.
- Storing the clock reading as a calendar date: rejected; the counter is simpler to bound and compare, and the calendar is a presentation of it (`WorldClock.read`).
- A per-campaign clock row: rejected; the counter already lives on `campaign`, copied onto events and checkpoints.

## Consequences

- V21 adds a check constraint on `campaign` and `campaign_event`, and the `campaign_world_time_step` trigger; no table is added, and an old save keeps its values. The backup tool covers schema version 21.
- `CommandRuleCatalog.Location` gains `travelMinutes` and `tags`, and the catalog gains `rests`. `ResolvedCommit` gains `conditions`; `WorldChange` rejects a step above one day.
- Two new refusal codes (HTTP 422): `COMMAND_TIME_ILLEGAL` and `COMMAND_REST_UNAVAILABLE`, with reviewed English and Italian wording (the Italian text still needs human review). One new command (`REST`), four new event types and a changed `ACTOR_MOVED` payload (a `seconds` field, additive within version 1).
- Existing packs are unchanged in meaning: a link without `minutes` takes no time. A wait between four hours and one day that used to commit is now refused.
