# Gameplay Modules

## 1. Shared rule model

Every module uses typed intentions, deterministic prerequisites, effects, domain events, and knowledge-aware projections. An action in exploration and the same ability in combat share mechanical definitions; only scheduling, targeting, and timing differ. Descriptions never replace executable rule data.

The engine distinguishes **attributes** (broad capacities), **skills** (trained competencies), **derived statistics** (calculated values), **resources** (spendable pools), **abilities** (performable actions), **traits** (passive rules), and **conditions** (temporary effects). Pack terminology can differ without collapsing these concepts into an unvalidated generic number map.

## 2. Exploration and action resolution

Locations form a directed graph with traversal requirements and durations. A character's actual position is separate from map discovery. Moving checks accessibility, presence, hazards, and equipment. Travel can produce a proposed encounter, but the engine must admit that encounter before reporting an attack.

Use a deterministic result for an obviously possible or impossible action. An uncertain action uses the configured check formula and difficulty. The default AI RPG Skirmish noncombat check is `2d10 + relevant attribute + trained skill + bounded situational modifier` against 8 routine, 12 demanding, 16 difficult, 20 severe, or 24 exceptional. Do not roll a routine action solely to manufacture failure. Skill/attribute selection and difficulty are disclosed before a player commits a consequential attempt when the character can reasonably judge them.

The default situational modifier range is -3 to +3 after combining supported sources. Success meets or exceeds difficulty. A margin of six or more may activate a rule-defined additional benefit; failure can advance a complication clock or consume the attempted action's time/resources according to its definition. The narrator cannot invent permanent injury from an unrelated minor failure.

## 3. Inventory and economy

Support stackable consumables, unique equipment, containers, quest objects, materials, and currency. Pack-defined equipment slots include capacity and compatibility tags. Inspecting an item is a query; drawing, equipping, trading, consuming, or discarding is an action when the rules assign time/cost.

Trades commit the offered items, prices, currency, ownership, and event together. A price rumor is not an accepted offer. Currency cannot become negative unless an explicit debt system is enabled. Unique objects cannot be held by two actors. A generated reward must be admitted as a valid definition/instance with a recorded source.

Weight/capacity and durability are supported configurable policies in v1, with clear disabled behavior. Inventory displays quantities and current equipment from the backend. The UI must not infer magical bonuses or resource restoration from text.

## 4. Character creation

A creation profile specifies an attribute budget, skill choices, optional archetypes, trait exclusions, initial resources, known abilities, and starting loadout. The backend validates the total rather than trusting UI restrictions. Archetypes are data presets, not mandatory classes.

The default proposal uses four attributes, Force, Finesse, Insight, and Resolve; each begins in the range 0–4 with a creation budget of eight points. Skills begin 0–2 with six total skill points across the pack's allowed skill list. These numbers belong to the default ruleset, not the engine kernel, and are provisional ([combat register](11-combat-system.md#17-provisional-numerical-defaults)). A pack may supply a compatible profile within validated limits.

Creation previews are read-only. Campaign creation commits actor, loadout, initial world state, content hashes, initial event, and pending decision together. Reopening a campaign never recreates its starting inventory.

## 5. Quests and objectives

Supported lifecycle: undiscovered → available → active → completed or failed; active may become suspended and return to active. Availability and discovery are distinct. A journal shows only discovered information. Quest objective predicates include acquired fact, visited location, possession/transfer, defeated encounter, completed interaction, and a bounded clock threshold.

Progress is derived from committed events and checked state. Avoid natural-language completion tests as the sole authority. A model can propose that an objective appears satisfied, but the engine evaluates its predicate. Completed quests cannot be re-awarded under a new paraphrase of the same source. Alternative outcomes have explicit settlement policies and consequences.

An emergent quest is validated content: it needs an identity, achievable conditions, issuer/source, known or hidden scope, reward budget, and failure/expiry behavior. It is not simply an AI sentence ending in “quest completed.”

## 6. NPCs, companions, and relationships

NPCs have stable identity, goals, personality constraints, current or last known position, beliefs, possessions, and remembered interactions. Background schedules advance only through world-time rules. The engine need not simulate every NPC each minute; evaluate relevant scheduled boundaries lazily and preserve event order.

Companions additionally have party membership, control policy, formation/zone, resource state, and their own knowledge. Control options are player-directed or AI-assisted within legal actions. The owner selects a policy; Codex cannot silently control the protagonist. A guide persona does not become a party combatant without an explicit actor profile and membership event.

Relationship dimensions are pack-defined bounded values, such as trust or respect. Significant changes need an event and cause. Hidden values stay hidden. Commitments and expressed boundaries are independent facts, not automatic consequences of crossing a numeric threshold. Optional romance is a content module and is disabled by default in neutral samples; it is not a condition for engine functionality.

## 7. World state, factions, and clocks

Factions maintain goals, reputation, resources, relationships, and bounded event clocks. A player action can start or advance a clock. Trigger evaluation is causal and deterministic for mechanics; Codex can suggest narrative interpretation or candidate developments.

A clock has owner, current value, target, tick policy, trigger, visibility, and settlement identity. On a long time jump, process due boundaries in order with a cap; if too many accumulate, pause for bounded catch-up rather than blocking indefinitely or skipping consequential events. UI reads do not trigger hidden world advancement.

Knowledge propagates through witnesses, messages, travel, or other declared mechanisms. A remote town does not instantly know a player's private conversation. Off-scene events cannot grant the protagonist knowledge without a recorded discovery.

## 8. Rest, recovery, and defeat

Rest requires a location, duration, interruption policy, resource recovery table, and condition-removal rules. It is an action, not a session reset. Short and extended rest are configurable labels and policies, with no required external ruleset behavior.

Default extended recovery takes eight in-world hours in a safe place and restores supported recoverable resources; injuries or story conditions may need specific treatment. Ending combat does not automatically refill health. Defeat follows the pack's declared recovery, capture, retreat, or permanent-loss policy. Irreversible risks must be communicated through known stakes and rules.

## 9. Creative abilities module

This module generalizes Rune without requiring its lore. A player describes a desired effect or edits a bounded symbolic recipe. Codex may translate intent into an effect graph using supported primitives. The backend validates target, magnitude, resources, complexity, duration, cooldown, prerequisites, and world constraints.

Flow: draft → validate → show cost/targets → player commits → rules resolve → narration → optional save as a learned reusable ability. Drafting and explaining a recipe do not execute it. Successful learning has provenance and a versioned ability definition.

Profiles may support constrained, high-power, or author-controlled creative play. A zero-cost profile is an explicit ruleset/content choice, not the default inherited from the demo's personal retcon. “Unlimited creativity” does not mean unbounded CPU, arbitrary code execution, infinite targets, unvalidated state, or freedom to expose secrets. An authored high-power effect can still use bounded engine operations and bypass ordinary uncertainty through an explicit rule.

## 10. Guide persona module

A guide can provide onboarding, rule explanations, remembered discoveries, risk analysis, and ability-drafting assistance. Its name, voice, visual representation, narrative presence, senses, and knowledge are authored data. It has a separate limited-audience AI session.

Default behavior is reactive assistance. Advice cannot spend resources, complete quests, move the player, or execute a drafted ability. A guide may recommend a legal action but must require the player's ordinary submit flow. It must explain uncertainty rather than pretending to know hidden world truth.

## 11. Media and atmosphere

Assets are registered by category: scene, map, portrait, item, UI, music, or effect. A new scene illustration does not replace the map. A portrait reference is not the current scene. Assets do not establish distances, new entities, or mechanical effects unless separately admitted as canonical data.

Static local assets support the MVP. v1 includes registry, credits, safe delivery, cue deduplication, and fallback behavior. Live AI image or voice generation remains optional future scope; Codex text access does not imply an image or audio generation tool is available.

Generate or request a new illustration only for a meaningful observable visual change or explicit owner/player request. Reuse during minor dialogue and inspections. Media jobs use source revision and scene identity so stale results cannot replace the current scene after the player moves.

Audio follows observable committed state, starts after a user gesture, uses separate volume preferences, and tolerates missing sources. Effects are keyed to a committed event and play at most once per client delivery cursor. Loading a campaign does not replay old combat hits. Remote streams are opt-in with documented availability and privacy implications; no remote catalog from the private demo is copied by default.
