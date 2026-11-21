# ADR 0052: Faction clocks record each caused change, with bounded catch-up

- Status: Accepted; implemented in P15.2-B (21 November 2026).
- Date: 21 November 2026
- Changes: a faction definition gains an optional `clocks` list, and world-scope predicates gain the numeric `clock`
  atom. Migration V34 adds the immutable `faction_clock_change` table. The canonical commit gains one participant,
  `org.rpgengine.world.FactionClocks`. No route, panel, event type or player command was added.

## Context

docs/10 section 7 asks for faction clocks with an owner, a value, a target, a tick policy, a trigger and a settlement
identity. It also asks that a long time jump process due boundaries in order with a cap, pausing for bounded catch-up
instead of blocking or skipping consequential events, and that reads never advance the world. Before this change:

- the world clock moved only with a committed revision, by at most one in-world day (P10.4, ADR 0026);
- quest endings were terminal rows that cite their ending event (P10.3), and reputation was derived from them (ADR 0051);
- relationship changes were immutable rows that cite their cause (ADR 0050).

Nothing counted time boundaries, and no world rule reacted to a quest ending except reputation.

## Decision

1. **Clocks are faction content.** A faction declares 0..4 `clocks`. Each has an `id`, a target of 1..12 `segments`,
   an optional time tick `every: {minutes, from}` and 0..8 `causes`:
   - `minutes` is 15..10,080 (one week). `from` is `start` (world time zero, the default) or `advance` (the world time of
     the clock's first caused change). The tick adds one segment per boundary.
   - A cause names exactly one quest of the same pack, with an optional `outcome`, or one clock of a faction of the same
     pack, and adds 1..segments `segments` (default 1).
   - The compiler requires a tick or a cause, a cause for `from: advance`, no self-cause, and at most one cause that can
     match any one ending or completion. A pack that declares no clock is unchanged.
2. **Values only rise; a full clock is complete and final.** A change adds its segments up to the target. A clock
   reaching its target completes, once, and takes no later cause. So a clock changes at most `segments` times.
3. **Due causes are derived, not queued.** At every commit that carries the pinned catalog, the participant derives
   the due causes from durable facts. They are every tick boundary at or before the committed world time after the
   clock's latest recorded tick, every terminal quest that a cause names with a matching outcome, and every completed
   clock that a cause names. A cause already recorded is not due again. Nothing reads the wall clock, and reads never
   call the participant.
4. **One total order, one row per change.** Due causes are applied by due world time, then tick before quest ending
   before completion, then clock identity, then the cause's identity. A completion makes the causes naming that clock
   due at the same world time, so a cascade runs in the same commit. Each change is one immutable `faction_clock_change`
   row with the cause kind, its due time, the cited quest and ending event or the cited clock, the values before and
   after, the target and a completion flag.
5. **Catch-up is bounded per commit.** At most 32 changes (`FactionClocks.MAX_CHANGES`) are recorded per commit.
   Because nothing marks a cause consumed except its row, the causes beyond the bound stay due. They are the oldest, so
   the next commit, whatever its command, applies them first. Nothing is skipped or counted twice, and two commits give
   the same rows as one unbounded commit would.
6. **The database checks provenance.** Check constraints require exactly the columns of the named cause and a due time
   no later than the commit. Unique indexes allow each value, each tick boundary, each quest and each source clock once
   per clock. A trigger refuses updates and deletes. An insert trigger requires the campaign's current revision and
   world time, a `value_before` equal to the clock's current value, a cited quest that ended at that event and time,
   and a cited clock that completed at that due time.
7. **Quests read clocks from the next command on.** `{"clock": {"faction": "<faction>", "id": "<clock>"}}` is a numeric
   world-scope atom with the proven interval 0..target. It may only name factions of the same pack. The quest engine
   runs in the resolver, before the commit records this command's clock changes. So it reads the values recorded by
   earlier commits, and a change caused by one command opens, advances or ends quests from the next command on.

## Alternatives

- **A derived clock value (like reputation).** A closed form over world time and quest endings needs no table, but it
  records no change, so a completion has no stored cause or settlement identity. It also cannot express causes that
  depend on another clock's completion time without recomputing every clock's history on each read.
- **Clock changes as journal events.** Events are immutable and ordered, but the commit's event count and receipt are
  fixed before the world participants run, and the database could not check the cause's shape or uniqueness.
- **Planning clocks in the resolver.** Quests in the same command would see this command's ticks. But quest endings
  advance clocks and clocks gate quests, so the resolver would need a loop between the two engines. It would also need a
  new resolved-commit part, written under a row-version guard. The one-command delay is simpler and stays causal.
- **Dropping or merging ticks after a long jump.** This is simpler, but it skips consequential completions or changes
  their order, which docs/10 rules out.

## Consequences

- Existing packs and campaigns are unchanged. A campaign whose content declares no clock reads and writes nothing for
  clocks.
- Every test cleanup that truncates `campaign` must also truncate `faction_clock_change`. The backup tool covers schema
  34 and the new table.
- A tactical command commits round time and carries the pinned catalog, so clocks also tick during a fight. Quests are
  not evaluated in a fight, so they react after the fight's next exploration command.
- No view, panel, receipt, journal event or narration context shows a clock. Clocks are hidden from every audience, and
  the only visible effect is a quest that a clock opens, advances or ends.
- Clocks do not change reputation, NPC placement or any other state. A clock that should do so needs a later decision.
- Clocks never run backwards, reset or repeat, and causes cannot name dependency-pack quests or factions.
