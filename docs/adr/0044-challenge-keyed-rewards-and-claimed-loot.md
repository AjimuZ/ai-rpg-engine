# ADR 0044: Challenge-keyed rewards and claimed loot

- Status: Accepted; implemented in P13.4-A (20 November 2026) as pure planning code that no settlement path calls yet. Changes no decision of ADRs 0024, 0025 or 0036.
- Date: 20 November 2026
- Builds on: [ADR 0024](0024-atomic-item-commands-and-source-linked-creation.md) (source-linked creation), [ADR 0025](0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md) (one-time quest rewards), [ADR 0032](0032-terminal-predicates-from-committed-state.md) (verdicts from committed state), [ADR 0036](0036-explicit-settlement-and-the-return-to-narrative.md) (explicit settlement).

## Context

Docs/12 section 8 asks each encounter to declare a reward source, an outcome-dependent policy and a unique settlement key. A quest and its fight may refer to the same `challengeSourceId`, whose XP is awarded once; a separate quest milestone may pay in addition. Rewards must not repeat through aliases, repeated journal views, respawns or restore and retry. Defeated-enemy loot is available to collect, not owned automatically, and an item cannot stay equipped on a living enemy and also appear in the player's pack.

Facts the design relies on:

- `reward_settlement` is unique per (campaign, source key, recipient key), and `ResolvedCommit` refuses two settlements of one source for one recipient in one commit (P10.3).
- Quest endings already settle under `quest:<questId>:outcome`.
- A fight's verdict is a pure function of its committed state, and settlement finalizes the verdict decided at `OUTCOME_PENDING` (ADR 0032, ADR 0036).
- Each fight has a fresh frozen encounter identity derived from its entering operation, so a key built from that identity would pay again when the same template is entered again.

## Decision

1. **The challenge is the reward unit.** A challenge's XP and reward-table items settle under `challenge:<challengeSourceId>`. The key comes from the declared source, not from the encounter, template or quest, so a re-entered template, an alias template declaring the same source and a quest naming it all hit one ledger row.
2. **First earning claim wins.** `ChallengeRewards.plan(recipient, settled, claims)` settles a challenge for the first claim that earns it: a fight whose verdict is in the source's policy, or a completed quest naming it. Every later claim on the key, in the same plan or after it in the ledger, is skipped as `ALREADY_SETTLED`. A claim that does not earn it (`NOT_EARNED`) leaves the challenge open for a later win.
3. **Default policy.** `VICTORY`, `HOSTILE_SURRENDER`, `OBJECTIVE` and `AGREEMENT` earn a challenge, so surrender and a truce pay without a death. An escape earns it only when declared. `DEFEAT` and `PLAYER_SURRENDER` can never be declared, because they meet no objective.
4. **The quest milestone is a distinct source.** Its own XP and items settle under the existing `quest:<questId>:outcome` key, so a quest can pay a separate objective besides the fight's XP.
5. **Loot is moved, never created.** `EncounterLoot.open` turns every holding carried into the fight into exactly one lot. Only lots of a hostile left `DEFEATED` become `AVAILABLE`, owned by nobody. Nothing opens after `DEFEAT` or `PLAYER_SURRENDER`. A claim moves a whole lot once with source key `loot:<encounterId>:<lotId>` and records the unequip. Every other claim is refused (`NOT_AVAILABLE`, `ALREADY_CLAIMED`, `UNKNOWN_LOT`), and the units of each definition never change.

## Alternatives considered

- Keying rewards by encounter identity (`encounter:<id>`): rejected, because each entry has a fresh identity, so a respawned or re-entered fight would pay again.
- Letting the quest carry the challenge XP under its own key: rejected, because the fight and the quest would then be two sources and could both pay the challenge.
- Making defeated-enemy loot owned by the victor at settlement: rejected by docs/12 section 8, which requires an explicit collection.
- Splitting a lot on claim: deferred. Whole-lot claims keep every unit's holder unambiguous with one source key per lot.

## Consequences

- New `progression/ChallengeRewards` and `progression/EncounterLoot`. No migration, pack schema, command, route or page changed.
- A plan read from a stale ledger cannot pay twice: its commit fails on the ledger's unique key. The caller must plan again.
- Not covered: no pack declares a reward source or a participant's holdings, `SETTLE_ENCOUNTER` and quest endings do not call the planner, XP is recorded but not accumulated into a level, and loot of a re-entered template is only exact once its holdings come from campaign-scoped actors.

## Verification

`ChallengeRewardsTest` (5) and `EncounterLootTest` (4). See the [roadmap entry](../16-development-roadmap.md#17-p13--dynamic-encounter-and-advancement-pipeline) for the recorded results and what was not proven.
