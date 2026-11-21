# ADR 0049: NPC schedules, witnesses and first-hand reports follow committed world time

- Status: Accepted; implemented in P15.1-A (21 November 2026) for NPC actors that already exist in a campaign.
- Date: 21 November 2026
- Changes: the NPC definition shape (three optional fields), the pinned rule catalog and the canonical commit. No
  migration, table, route or player command was added.

## Context

FR-018 and FR-019 ask the engine to track NPC presence and knowledge and to advance them only through world time
(docs/10 sections 6, 7 and 12). QA-21 checks that world time and witnesses decide who is where and who knows what,
and that inspections advance neither. The bounded world clock (ADR 0026) already moves only in committed commands. The
typed knowledge ledger (P09.1) already records an observer's last known location of another actor (`actor_sighting`,
OBSERVED or TOLD with an informant). Before this change an NPC's location never changed, and no NPC recorded anything
it saw.

## Decision

1. **Schedules are stateless.** An NPC definition may declare `schedule`, 1..24 entries `{from: "HH:MM", location}` in
   strictly increasing order. The entry in force holds from its start until the next one, and the last entry wraps
   past midnight. The entry in force at 00:00 must be the definition's `location` (`CONSTRAINT_CONFLICT`), so that
   location remains where a campaign starts the NPC. Presence is a pure function of the clock (`NpcSchedule`). A long
   jump needs no catch-up, and there is no schedule state to drift.
2. **Schedules apply in the commit.** After the operation's own effects, and before the player's own observation,
   every NPC actor whose definition has a schedule is placed where the schedule puts it at the committed world time.
   The write is guarded by the row version read under the campaign lock. The move bumps `actor:<npc>` and steps the
   row version, so plans that read the actor become stale. No event announces it, because the public journal must not
   reveal where unseen NPCs walk.
3. **Witnesses are opt-in.** `witness: true` marks an NPC that remembers where it saw the acting player. After a
   registered command, every witness at the player's location records an OBSERVED sighting through the knowledge
   ledger. A witness that already holds a current first-hand sighting at the same place keeps it, so the sighting
   dates from when it first saw the player there. Concealment does not matter: a hidden NPC sees whoever stands
   beside it. Crowds over 64 witnesses record nothing, the same bound as the player's perception.
4. **Reports are first-hand and derived.** A witness may declare up to 8 `reports` `{to: <npc>, minutes: 0..1440}`,
   one per recipient and never to itself. Only a witness may declare them (`CONSTRAINT_CONFLICT`). A report of the
   witness's current first-hand sighting arrives `minutes` after that sighting. The first commit whose world time has
   reached the arrival delivers it as a TOLD sighting, with the witness as informant and the arrival time as when the
   recipient learned it. It is delivered only when the recipient learned of that subject before the witness's
   sighting. TOLD sightings are never relayed again, so knowledge moves one declared hop, and two routes cannot echo
   each other. Each commit delivers at most 64 reports, earliest arrival first. The rest stay derivable from the
   witness's sighting and arrive in a later commit.
5. **Reads decide nothing.** All three steps run only inside a committing transaction (`NpcWorld.advanceWithin`). No
   view, projection, panel, stream or wall-clock timer calls them.

## Alternatives considered

- A pending-message table with explicit delivery rows: it would record lost intermediate reports and allow relays, but
  every table that references `actor` or `campaign_event` must be added to about fifty test cleanup statements. Its
  rows would also duplicate what the witness's sighting and the clock already determine.
- Schedule moves as internal events: they would give each move a cause in the journal. The event count of a commit is
  fixed by its plan before the schedule is evaluated, and an internal event adds nothing to `row_version` provenance.
- Every NPC as a witness by default: closer to "everyone sees", but it would change the knowledge of every existing NPC
  without an author's choice, and the existing suites and context digests that place NPCs beside the player.
- Multi-hop relays: these need the original observation time on every TOLD row, which `actor_sighting` does not store.
  Without it, two routes could pass the same news back and forth indefinitely.

## Consequences

- Authors can give NPCs routines, witnesses and message routes, and the compiler checks them. Campaigns whose content
  has none behave exactly as before, because the commit returns before any query.
- An NPC's own audience view (`KnowledgeView.forActor`, the `character` context) now contains what it witnessed or was
  told. It still has no event history.
- A report carries only the latest first-hand sighting. An intermediate position that the witness replaced before the
  arrival is not reported. A recipient who learned something newer after the witness's sighting keeps its own
  knowledge, even when that knowledge is older underneath.
- Not covered:
  - Production campaign creation does not yet create NPC actors from definitions, so packaged play has no NPC these
    rules can move.
  - Typed NPC goals are not included. `goals` remain authored text that no rule reads.
  - Witnesses record only the player's location, not the events they watched.
  - Companions, relationships and commitment causes remain later P15.1 work.

## Verification

`NpcWorldTest#worldTimeAndWitnessesDeterminePresenceAndKnowledge` (QA-21 on PostgreSQL through the production command
path), `NpcScheduleTest` and `PackCompilerTest#validatesNpcWorldRules`. See the
[roadmap entry](../16-development-roadmap.md#19-p15--complete-supported-world-and-optional-modules) for the recorded
results and what was not proven.
