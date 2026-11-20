# ADR 0045: Level entitlements recorded once and spent only by purchase

- Status: Accepted; implemented in P13.4-B (20 November 2026) as pure code that no award or purchase path calls yet. Changes no decision of ADR 0044.
- Date: 20 November 2026
- Builds on: [ADR 0044](0044-challenge-keyed-rewards-and-claimed-loot.md) (challenge-keyed rewards).

## Context

Docs/12 section 9 defines the default level-and-training profile: cumulative XP `50 × L × (L - 1)` reaches level `L`; each gained level grants one training point and every even level one attribute point; attributes cap at six; a skill's rank cap is `min(5, level + 1)` and its next rank costs that rank in training points. Health maximum rises by two and Focus maximum by one per level, and current pools do not rise or refill. An award crossing several thresholds records every level and entitlement exactly once, unspent choices stay pending, and a new ability is unusable until its purchase succeeds (QA-15).

Facts the design relies on:

- `ChallengeRewards` (ADR 0044) already settles each XP source once, so advancement only has to count settled XP once per award.
- Derived-statistic formulas read attributes and skills, not a level, so a level cannot raise a maximum through the pack's formula today.
- `CharacterRuleCatalog` holds each skill's declared maximum rank and each ability's skill-rank and ability prerequisites. Skills declare no prerequisite, and no pack declares a teacher, discovery or ability cost.

## Decision

1. **The standing is the record.** `Advancement.Standing` holds cumulative XP, the level reached and the pending training and attribute points. An award adds XP and grants a `Gain` for each level above the recorded one up to the level the new total reaches, so the same total, a zero award or a replay grants nothing. A standing whose level is not the one its XP reaches is refused.
2. **No default maximum level.** A profile may declare one; XP still accumulates past it and grants nothing more.
3. **Maxima rise, current values stay.** The award returns Health and Focus with their maxima raised by the profile's per-level gains and their current values unchanged. Until formulas can read a level, this per-level bonus is the engine's own and not part of the pack formula.
4. **Spending is only by purchase.** `Advancement.purchase` accepts a skill rank, a +1 base attribute or an ability unlock only when every rule holds, and spends the points of that one choice. A refusal returns the inputs unchanged with one reason, checked in a fixed order: definition, cap or learned state, prerequisites, points.
5. **An unlock costs one training point by default.** Docs/12 lists an advancement choice as one way to gain an ability without a price. One training point is a provisional default of this engine, declared per profile and calibrated in P18.3.

## Alternatives considered

- Storing only XP and deriving entitlements from it: rejected, because spent points could not be told from pending ones and a lower maximum level or a profile change would silently regrant them.
- Refilling pools on level-up: rejected by docs/12 section 9 and QA-15.
- Free unlocks once prerequisites hold: rejected for the default, because unlocks would then not compete with ranks for training points. A profile can set the cost to zero.

## Consequences

- New `progression/Advancement`. No migration, pack schema, command, route or page changed.
- Not covered: nothing stores a standing or calls the award after settlement; no pack declares a profile, a level-aware formula, a skill prerequisite, a teacher or a discovery requirement; purchases do not write `character_state` or `learned_ability`, and no safe-boundary or preview logic exists (P13.5). `CapabilitySnapshot` still accepts levels 1–20 only.

## Verification

`AdvancementTest` (4). See the [roadmap entry](../16-development-roadmap.md#17-p13--dynamic-encounter-and-advancement-pipeline) for the recorded results and what was not proven.
