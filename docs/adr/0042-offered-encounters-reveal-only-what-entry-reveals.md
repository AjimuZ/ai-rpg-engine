# ADR 0042: Offered encounters reveal only what entry reveals

- Status: Accepted; implemented in P13.2-B (20 November 2026) for authored templates entered with `ENGAGE`. Changes no decision of ADRs 0028, 0032 or 0041.
- Date: 20 November 2026
- Builds on: [ADR 0007](0007-audience-isolation.md) (player-safe projections), [ADR 0028](0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md) (authored templates and `ENGAGE`), [ADR 0041](0041-template-bounded-encounter-compositions-and-an-authored-fallback.md) (encounter proposals).

## Context

The player can start an authored fight only with `ENGAGE`, which names a template ID. P12.5-B found that no projection the page reads names such an ID, and that a refused `ENGAGE` deliberately does not confirm whether a template exists elsewhere. Docs/11 section 3 says entry reveals "only observable participants, positions, threats, escape options, and stakes". It does not say whether a threat is a tier, a statistic or only a side, nor whether the page may read the list by itself.

The encounter proposals of ADR 0041 are not called by any planning path yet, so the only encounters a player can start today are the authored templates at the player's location.

## Decision

1. **The offers are the admission gate's own answer.** Outside a fight the combat projection (`GET /api/v1/campaigns/{id}/encounter`) gains `offers`: every pinned template at the player's current location that a plain `ENGAGE` would admit on the same committed state, in template ID order. `EncounterOffers` applies the command's own mode, decision and player gates and the `ENGAGE` rule (`CommandRules.engageable` calls the same `Check#engage`), not a copy, so a template elsewhere, of another vocabulary, for a defeated player, under a paused campaign or during a fight is never named. `offers` is always empty during a fight.
2. **The most conservative disclosure.** An offer holds the template ID, the player's starting zone, the observable participants (roster ID, `ally` or `hostile`, starting zone), the escape zones, the objectives as stakes (ID, kind, and the zone, protected participant or rounds the kind needs) and the opening-action policy with its lawful opening targets (the hostile participants, none under `forbidden`). "Threats" is read as the hostile side only. An offer never holds statistics, threat tiers, attacks, reactions, conditions, the portrayed NPC, the zone graph, cover, capacity, tags, declared social or scene actions, or any display text.
3. **The page reads offers only when asked.** Outside a fight the page does not read the combat projection by itself; a "Look for fights you can start here" button reads it once. This keeps the exploration page's requests unchanged, and the offers describe only the revision that read saw: any newer revision clears them.
4. **`ENGAGE` only from a confirmed review.** The page builds `ENGAGE` only for an offered template, and an `openingStrike` only for one of its listed targets. Choosing an entry opens a review; only its confirm button sends. A lost reply keeps the request identity for the same entry at the same revision, so a retry cannot start the fight twice.

## Alternatives considered

- Add the offers to the resume card: rejected, because the card is read on every load and the recorded real-server transcript would change for every playthrough, not only those that look for a fight.
- Read the offers on every exploration load: rejected for the same request cost and transcript change, and because a list shown without being asked for invites starting a fight by accident.
- Show the template's authored display name or threat tiers: rejected until a knowledge rule says which authored text a player has observed. Identifiers alone confirm nothing that entry would not reveal.
- Offer templates elsewhere as "rumored" fights: rejected, because it would confirm templates that entry refuses without confirming.

## Consequences

- `CombatPanels.CombatPanel` gains `offers` (records `Offer`, `OfferParticipant`, `Stake`). The page gains `combat-entry.ts` and the `app-encounter-offers` list. No migration, command or route changed.
- Offers are still unbudgeted authored templates: an offered template is not checked by `ThreatBudget`, and ADR 0041's proposals are not offered until a planning path calls them (P13.2, P13.3).
- After `ENGAGE` the fight waits at round 0 for `ROLL_INITIATIVE`, which the page cannot send yet.

## Verification

`CombatPanelTest` (offers and a defeated player) and `combat-entry.spec.ts`. See the [roadmap entry](../16-development-roadmap.md#17-p13--dynamic-encounter-and-advancement-pipeline) for the recorded results and what was not proven.
