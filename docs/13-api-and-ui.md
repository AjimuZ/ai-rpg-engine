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
| Library | Independent adventure workspaces and create/continue playthroughs; reusable installed packs managed separately |
| Character creation | Pack-defined choices, budgets, preview, validation, explicit create |
| Narrative play | Default visual-novel scene, attributed narrator/character/player passages, editable quick replies, persistent composer and expandable chronicle |
| Character/inventory | Authoritative stats, skills, equipment, resources, conditions, item actions |
| Journal/world | Known quests, discoveries, map knowledge, promises, event history |
| Combat | Initiative, active actor, AP/reactions, zones, legal actions, targets, consequences |
| Advancement | Pending entitlements, legal purchases, cost/effect preview |
| Settings | Reading, theme, sound, Codex readiness, routing, context renewal |
| Author/owner tools | Validation, export, restore/correction previews, credits and diagnostics |

## 6. Interface direction

Retain the first project's structured RPG panels and readable dark-theme option. Its obsidian, violet, gold, and cyan palette can become the default theme. It is not mandatory for all packs; support neutral and high-contrast profiles. Content terminology and visible panels derive from enabled modules.

The **default play experience is a dynamic visual novel**: one dominant scene image, a readable dialogue area, and the player's persistent composer. On desktop the scene should occupy approximately the upper 60–70% of the story workspace when space permits; text size and actual content take precedence over a fixed ratio. Character portraits may overlay the scene without replacing it. Character sheet, inventory, map and journal open through compact navigation or dismissible panels, rather than permanently surrounding the story with dense columns. Combat can expose its dedicated tactical controls when needed. A text-focused alternative remains complete without artwork and is available as a reading/accessibility preference.

### 6.1 Speakers and reading flow

Each passage identifies **narrator**, a known **character**, or **you/the protagonist** through explicit labels, with a portrait when available. Character labels and portraits resolve from stable public identities; unknown people use an appropriate public label. Distinguish narration from quoted speech without relying on color. Player speech comes from the player's submitted words, never newly invented protagonist dialogue. A submitted action can appear as a pending attempt; rejection must not make it look like an action that occurred.

Show the current passage and optionally a compact preceding exchange; the complete attributed transcript is in an expandable chronicle. `Continue` reveals the next already available passage and never starts a new world action, spends time/resources, submits input or requests continuation beyond the pending player decision. Reading back, opening a panel and changing text size are local UI actions. Optional text-reveal animation can be skipped immediately and respects reduced motion. Reconnect preserves reading position and deduplicates messages.

### 6.2 Contextual quick replies and free input

Offer up to three concise, optional replies/questions/actions when useful, especially after an NPC question. They are dynamic suggestions grounded in the current public situation and pending decision, not a fixed branching story script. For example, after a question about investigating a lighthouse: “I'll come with you,” “What do you know about it?” and “Let's look from here first.” The player can always ignore them and write freely.

Selecting a chip **fills an editable draft**; only the separate, explicit Send action submits it. Do not overwrite a nonempty unsent draft without a deliberate replacement action. Label speech, question and action suggestions clearly; consequential actions disclose known costs and targets. All submitted drafts use the same validation/idempotency pipeline as typed input. Suggestions must not reveal hidden information, invent available objects or imply the protagonist has agreed. No suggestions is a valid outcome. Invalidate them when the source scene/revision/pending decision changes, and do not resubmit them on reconnect. Their absence or generation failure never disables the composer.

### 6.3 Graphics, persistent portraits and item identity

Building the UI includes delivering its complete usable asset kit, not leaving the illustrative mockup as a substitute. Produce original graphics through supported image-generation tools when suitable, or obtain them from verified free libraries with preserved notices. Use accessible CSS/SVG for scalable bars and controls; decorative frames, textures, portraits and distinct item artwork can be separate image assets. Text, focus indicators and numerical values remain live UI elements. The inventory, required interaction states, source records and acceptance checks are specified in [presentation assets](23-presentation-assets.md).

During play, supported bounded image-generation jobs can create new scene art, character portraits and identifying item icons. Save and reuse the results by stable entity/appearance identity. A named sword in a shop retains its image when inspected, purchased, equipped, saved or restored. Equivalent copies can share definition artwork; a visually distinct unique variant has its own identity. A reusable decorative portrait frame is distinct from the character portrait. Rarity uses an explicit tier name plus shape/icon/text treatment and the pack's rules, never color or artwork alone.

Technical details such as model ID, reasoning level, session counters, and latency belong in settings/diagnostics. Ordinary play displays clear progress and save status. A guide panel is shown only if configured. A creative-ability panel is not labeled Rune unless the pack chooses that label.

The header identifies the selected adventure/playthrough. Switching restores its drafts, reading position and art while rejecting old callbacks. The adventure-list/create/detail routes and selected-adventure campaign envelope are specified in [workspace isolation](25-adventure-workspaces.md); a world is not merely a quick-save slot.

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
- Dynamic music uses the reviewed catalog and complete current-track/Music Credits treatment in [music and credits](24-dynamic-music-and-credits.md); ordinary play cannot trigger unapproved remote requests.

These are targets requiring actual browser and assistive-technology review. A stylesheet or DOM unit test does not certify accessibility.

## 10. Frontend state ownership

Signals hold UI selections, drafts, dialog state, and the latest server projection. RxJS handles transport streams, cancellation, and retries. The backend owns rules and canonical state. Client-side previews are labeled estimates and must be replaced by the accepted receipt. Do not locally decrement inventory as if an optimistic display change were a saved game effect.

Use revision-aware feature services and presentational components. Avoid a single application component that directly owns chat, combat, inventory, audio, settings, and model routing. This preserves the modular design intended by the primary project.

## 11. Illustrative advanced-gameplay concept

The primary narrative-screen concept is below. It illustrates the owner's visual-novel direction, including speaker separation, quick-suggestion chips and free input. Italian labels illustrate localization; the initial interface-language assumption remains English with localizable strings. This is a design image, not an implemented UI or a runtime asset kit. [Prompt and provenance](mockups/visual-novel-ui-v1.prompt.md).

![Visual-novel concept with a large harbor scene, named character portrait, narrator/player exchanges, dialogue, suggestions and free input](mockups/visual-novel-ui-v1.png)

The earlier image below remains a secondary **combat-screen** concept; it does not define the default story layout.

![Concept of the advanced AI RPG Engine combat interface with character sheet, party, equipment, scene, tactical zones, target details, and action controls](mockups/advanced-gameplay-ui-v1.png)

This generated design concept illustrates an evolved campaign during a turn-based encounter. It is not implemented software. The example adventure, names, portraits, inventory, and numerical values are illustrative and do not change the generic engine or mechanical rules. Character and item images here are baked into one concept image; this does not implement dynamic asset generation. [Final prompt and provenance](mockups/advanced-gameplay-ui-v1.prompt.md).
