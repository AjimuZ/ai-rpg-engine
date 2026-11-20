# ADR 0047: Installed-pack listing and browser playthrough creation

- Status: Accepted (conservative choices of open details, 20 November 2026); implemented in P14.1-C (20 November 2026).
- Date: 20 November 2026
- Builds on: [ADR 0019](0019-local-campaign-admission.md) (local admission) and [ADR 0046](0046-automatic-first-start-provisioning-of-the-owner-and-a-sample-adventure.md) (provisioned sample adventure).

## Context

`POST /api/v1/campaigns` (P06.1) needs the `contentRevisionId` of a sealed revision of the adventure. No read API exposed one: `GET /api/v1/adventures` returns workspace metadata only, `GET /api/v1/packs` answered 404, and the browser's `/adventures/:id/create` screen was a preview that sent nothing and only knew preview identities. So no client of the packaged application could create a playthrough, not even from the sample that ADR 0046 provisions. docs/13 section 2 already proposed `GET /api/v1/packs` ("list installed revisions and diagnostics; public author metadata only") and `POST /api/v1/campaigns` ("create from pack/ruleset revision and creation choices").

## Decision

1. **Implement the documented route, scoped to the owner.** `GET /api/v1/packs[?adventureId=<uuid>][&after=<uuid>][&limit=1..100]` returns `{packs, nextAfter}`, ordered by revision UUID, default page 50. It lists only **sealed** revisions (with an import report), because only those can pin a campaign. With `adventureId`, a missing or foreign adventure answers `404 PACK_SCOPE_UNAVAILABLE` rather than an empty page, so the creation screen can tell "no such adventure" from "no content yet". The adventure read model is not changed: the route alone serves the screen.
2. **Public author metadata only.** A row has exactly `contentRevisionId`, `adventureId`, `packId`, `packVersion`, `sourceLocale`, `admittedAt` and the manifest's `title`, `summary`, `authors` and `license`, read from the sealed manifest in the pack's source language. Hashes, file lists, rules, lore, secrets, import reports and the owner never leave the server. The browser parser rejects any other field. Import diagnostics, which docs/13 also names, are not part of this listing yet.
3. **The whole `/api/v1/packs` subtree is admitted** like `/api/v1/adventures`, so the future validate and import routes inherit the same session, Origin and CSRF rules.
4. **Saved-adventure creation has its own route.** A URL matcher sends `/adventures/<canonical uuid>/create` to a new `PlaythroughCreation` screen. Preview identities are never UUIDs, so they keep their preview workspace. The packaged jar already served that path.
5. **Choices are conservative.** The screen asks for a playthrough title (1–200 characters) and a character name (1–60, below the server's 120), both trimmed. It sends no archetype and empty attribute and skill maps, which the engine resolves to the profile's minima. This is always within budget, and it needs no creation metadata, which no read API exposes yet. When an adventure has one sealed revision, it is preselected. With several, the player must pick one.
6. **Narration language is the interface language.** `narrativeLocale` is `it` in the Italian build and `en` otherwise. A pack without that language falls back explicitly to its source language, as the state projection already reports.
7. **Command identity follows the narrative view.** The request identity is kept per body. A lost reply, or one that cannot be verified, keeps it, so pressing "Create playthrough" again with the same choices repeats the same request, and the server returns the stored playthrough. A refusal, or changed choices, starts a new identity. Nothing is sent without that explicit press. The identity is held in memory only.
8. **Success opens the live view** at `/saved/<adventureId>?playthrough=<campaignId>`.

## Alternatives considered

- **Add a revision field to the adventure read model.** Rejected: docs/13 already reserves `GET /api/v1/packs` for this, and one adventure can hold several revisions.
- **An empty page for a foreign adventure**, like the campaign listing. Rejected for this route: the screen needs to tell a missing adventure from an adventure with no content.
- **Expose creation profiles and archetypes now.** Deferred: that is new projection work, and default allocations already make a valid character.
- **Persist the pending request identity across reloads.** Deferred: the narrative view does not do this either. After a reload, a lost creation may show as an existing playthrough in the saved adventure. A new press creates a second one.

## Consequences

- The packaged application has every route a browser needs to create and open a playthrough from the bundled sample, with no test-only route. This is verified in parts only: real-database tests create one from the provisioned revision and read its state, resume card and panels, and a Vitest journey against a simulated server renders the live view after creation. No browser has yet done this end to end on a packaged origin (see P14.1-C in the roadmap).
- The character always starts with default allocations until creation metadata is projected.
- The installed-pack listing has no diagnostics yet. The listing and creation screens are English and Italian, and their 22 new Italian targets have had one author pass only.
- Verification and limits are recorded under P14.1-C in `docs/16-development-roadmap.md`.
