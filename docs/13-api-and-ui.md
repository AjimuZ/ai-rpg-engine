# Application API and Player Interface

## 1. Contract status

The following endpoints and DTO fields are proposed v1 contracts. They are not running services or generated OpenAPI definitions. API and content versions are separate. Use an `/api/v1` namespace and explicit schema versions for persisted/imported data.

## 2. Proposed endpoint families

| Method and path | Purpose | State behavior |
| --- | --- | --- |
| `GET /api/v1/health` | Application liveness | No AI call |
| `GET /api/v1/readiness` | Database, migrations, adapter support, configured AI readiness | Sanitized diagnostics |
| `POST /api/v1/packs/validate` | Stage and validate a selected pack | No campaign effects |
| `POST /api/v1/packs/import` | Register a successfully validated immutable revision | Owner action |
| `GET /api/v1/packs` | List installed revisions and diagnostics | Public author metadata only |
| `POST /api/v1/campaigns` | Create from pack/ruleset revision and creation choices | Transactional and idempotent |
| `GET /api/v1/campaigns` | Owner's campaigns | Scope to local owner |
| `GET /api/v1/campaigns/{id}/state` | Player projection at one revision | No advancement |
| `GET /api/v1/campaigns/{id}/journal` | Known events and quests with cursor | No secret notes |
| `GET /api/v1/campaigns/{id}/actions` | Legal current actions and known reasons | No hidden-information hints |
| `POST /api/v1/campaigns/{id}/commands` | Submit typed action or free-text intent | Returns durable operation |
| `GET /api/v1/campaigns/{id}/operations/{operationId}` | Operation progress, receipt, or clarification | Safe retry/reconnect path |
| `POST /api/v1/campaigns/{id}/operations/{operationId}/cancel` | Request cancellation | Receipt determines whether effects already committed |
| `GET /api/v1/campaigns/{id}/stream` | Resumable authorized event stream | Transport delivery only |
| `GET /api/v1/campaigns/{id}/encounter` | Public current combat projection | No raw enemy secrets |
| `POST /api/v1/campaigns/{id}/ai-session/renew` | Renew idle role contexts safely | Does not advance world |
| `GET /api/v1/codex/status` | Supported runtime and account readiness | No credentials or raw RPC identifiers |
| `PUT /api/v1/preferences` | Reading, sound, routing, renewal preferences | No game effects |
| `POST /api/v1/campaigns/{id}/exports` | Create full-owner or player-facing export | Explicit audience selection |
| `POST /api/v1/restore/preview` | Validate archive compatibility and integrity | No overwrite |
| `POST /api/v1/restore/apply` | Restore reviewed snapshot to a new campaign identity by default | Owner action with concrete preview token |
| `POST /api/v1/campaigns/{id}/corrections/preview` | Show proposed administrative correction | No mutation |
| `POST /api/v1/campaigns/{id}/corrections/apply` | Apply validated correction | Audit, backup, revision increment |
| `GET /api/v1/assets/{assetId}` | Serve one registered authorized asset | Path never supplied directly by client |
| `POST /api/v1/application/shutdown` | Stop local application gracefully | Owner action; owned resources only |

No endpoint proxies arbitrary Codex methods, SQL, filesystem paths, shell commands, or general-purpose AI tools.

## 3. Command envelope

Required fields: `requestId` (idempotency identity), `expectedRevision`, `commandType`, and bounded `payload`. Include command locale where it cannot be derived from the campaign. Typed combat payloads include actor ID, action/ability ID, targets, and destination if applicable. Free text is explicit input, never a replacement for validation.

The backend returns operation ID, accepted status, base revision, and a query/stream reference. The final receipt includes status, committed revision if any, source event IDs, known mechanical consequences, pending decision, and narrative delivery state. Do not return provider thread IDs or internal prompt text.

Use HTTP 202 for accepted asynchronous work, 200 for current/final queries, 201 for created resources, 400 for malformed input, 403 for denied origin/owner/capability, 404 for inaccessible identities, 409 for busy/stale/idempotency conflicts, 422 for valid-shaped but illegal content/action, 429 for local admission limits, and 503 for unavailable required runtime dependencies. Keep detailed safe error codes stable across localized messages.

## 4. Stream contract

Use durable public message IDs and `Last-Event-ID` resumption. Proposed message types: `operation.accepted`, `operation.progress`, `operation.clarification`, `operation.rejected`, `state.committed`, `narrative.ready`, `narrative.pending`, `encounter.updated`, `media.ready`, and `application.closing`.

Only committed state events carry mechanical consequences. Progress text is application-generated. The client deduplicates delivery IDs and ignores messages from an obsolete campaign or revision. If a cursor is outside retention, return a reset instruction and retrieve a complete current projection. Reconnect never auto-submits an action.

The SSE stream is at-least-once delivery, not exactly-once gameplay. Durable operation idempotency and transactional settlements provide the latter guarantee for canonical effects.

## 5. Screens

| Screen | Responsibilities |
| --- | --- |
| Library | Installed adventures, validation results, import, create/continue campaign |
| Character creation | Pack-defined choices, budgets, preview, validation, explicit create |
| Narrative play | Current scene, narration, composer, legal suggestions, compact state |
| Character/inventory | Authoritative stats, skills, equipment, resources, conditions, item actions |
| Journal/world | Known quests, discoveries, map knowledge, promises, event history |
| Combat | Initiative, active actor, AP/reactions, zones, legal actions, targets, consequences |
| Advancement | Pending entitlements, legal purchases, cost/effect preview |
| Settings | Reading, theme, sound, Codex readiness, routing, context renewal |
| Author/owner tools | Validation, export, restore/correction previews, credits and diagnostics |

## 6. Interface direction

Retain the first project's structured RPG panels and readable dark-theme option. Its obsidian, violet, gold, and cyan palette can become the default theme. It is not mandatory for all packs; support neutral and high-contrast profiles. Content terminology and visible panels derive from enabled modules.

The demo's visual-novel arrangement becomes an optional scene-focused layout: large scene image, compact latest exchange, expandable full chronicle, map thumbnail, persistent composer, and character dialog. A text-focused layout remains fully functional without any artwork. The original three-column panel arrangement is another layout option, not a fixed requirement for a small screen.

Technical details such as model ID, reasoning level, session counters, and latency belong in settings/diagnostics. Ordinary play displays clear progress and save status. A guide panel is shown only if configured. A creative-ability panel is not labeled Rune unless the pack chooses that label.

## 7. Combat interaction

Show active actor, initiative order, remaining action points, available reaction policy, visible positions, known conditions, and objective. Actions expose known costs and legal targets before submission. Highlight reachability and cover with text/icon equivalents. A target card shows only revealed enemy statistics; the engine may know exact hidden values without returning them.

Free text can express intent, but ambiguous targets or resource spending produce a clarification. Selecting a suggestion only prepares input unless the control is explicitly a named action button with a clear cost and submit behavior. No auto-attack, automatic protagonist dialogue, or automatic build choice occurs merely because Codex proposes it.

During NPC activations, show resolved public consequences. Maintain a pause/interrupt control for AI work, with accurate wording after a state commit. Rule-only NPC fallback may finish a bounded activation; the next player decision must remain explicit.

## 8. Loading, empty, error, and recovery states

Every panel defines loading, empty, unavailable, and error states. Last committed state remains inspectable while AI works. Avoid replacing a valid inventory with an empty one when a request times out.

Distinguish no installed pack, invalid pack, no campaign, missing asset, disconnected Codex, stale revision, awaiting clarification, cancelled before commit, committed with narration pending, and restore required. These are actionable states, not one generic “AI error.”

After reconnect, fetch operation status before enabling a retry. Reusing the original request identity is a retry; editing the action creates a new request. Keep an unsent draft separate from submitted text so a refresh cannot accidentally replay it.

## 9. Accessibility and responsive behavior

- All controls support keyboard navigation and visible focus.
- Dialogs trap/restore focus correctly and support escape where safe.
- Narrative text size initially supports 15–24 px, following the demo; allow zoom and future wider ranges without layout loss.
- Respect reduced motion and avoid flashing effects.
- Do not encode health, rarity, threat, faction, or target legality by color alone.
- Provide meaningful accessible names, scene descriptions, and media alternatives.
- Keep composer, submit, and interruption controls available on narrow screens.
- Preserve reading position unless the player is following the newest output.
- Announce concise operation status, not every streamed token, to assistive technology.
- Audio is optional, user-activated, independently adjustable, and never required for clues.

These are targets requiring actual browser and assistive-technology review. A stylesheet or DOM unit test does not certify accessibility.

## 10. Frontend state ownership

Signals hold UI selections, drafts, dialog state, and the latest server projection. RxJS handles transport streams, cancellation, and retries. The backend owns rules and canonical state. Client-side previews are labeled estimates and must be replaced by the accepted receipt. Do not locally decrement inventory as if an optimistic display change were a saved game effect.

Use revision-aware feature services and presentational components. Avoid a single application component that directly owns chat, combat, inventory, audio, settings, and model routing. This preserves the modular design intended by the primary project.

## 11. Illustrative advanced-gameplay concept

![Concept of the advanced AI RPG Engine combat interface with character sheet, party, equipment, scene, tactical zones, target details, and action controls](mockups/advanced-gameplay-ui-v1.png)

This generated design concept illustrates an evolved campaign during a turn-based encounter. It is not implemented software. The example adventure, names, portraits, inventory, and numerical values are illustrative and do not change the generic engine or mechanical rules. Character and item images here are baked into one concept image; this does not implement dynamic asset generation. [Final prompt and provenance](mockups/advanced-gameplay-ui-v1.prompt.md).
