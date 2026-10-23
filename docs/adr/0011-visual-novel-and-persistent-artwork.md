# ADR 0011 — Visual-novel presentation and persistent artwork

- Status: Accepted design requirement; implementation pending
- Date: 23 October 2026
- Owner direction: immersive visual-novel play, editable quick replies, a complete graphic asset kit, and persistent generated character/item images

## Context

The previous interface direction treated scene-focused play as optional and retained a dense tactical illustration as its only mockup. Runtime image generation was deferred beyond v1. The owner clarified that the central story experience should resemble a visual novel, while keeping dynamic narrative and explicit protagonist agency, and that recurring characters and individual objects should have saved identifying artwork.

## Decision

Use a dominant scene, attributed narrator/character/player passages, an expandable chronicle and a persistent free-input composer as the default story layout. Contextual quick replies prepare editable drafts; explicit submission and existing validation remain authoritative. Keep text-focused play as an equivalent media-free alternative, and expose tactical controls during combat.

Deliver the UI graphics as part of development, using accessible native/vector controls and original generated or properly licensed illustration assets. Full v1 includes bounded asynchronous scene/portrait/item generation through a verified supported capability, with stable visual identity, durable storage, strict public-appearance scope, generation budgets and stale-result protection. The MVP can use static/catalog assets. Text-account access does not imply image access, and no paid fallback or broader host privilege is enabled implicitly.

Known item rarity is explicit validated data with accessible labels and shared presentation across shop/inventory/equipment. Art never determines mechanics or rarity. Preserve existing campaign, rules, authority and role isolation boundaries.

## Consequences and verification

Update P01 capability proof, P04 item data, P05 UI/graphics, P08 narration contracts, P10/P13 rarity semantics, P16 asset recovery and P17 generation. UI and image code remain unimplemented. Verify QA-29–31 and the linked [interface](../13-api-and-ui.md) and [asset specification](../23-presentation-assets.md). No third-party runtime art is bundled by this decision.
