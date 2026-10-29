# Gameplay Modules

## 1. Shared rule model

Every module uses typed intentions, deterministic prerequisites, effects, domain events, and knowledge-aware projections. An action in exploration and the same ability in combat share mechanical definitions; only scheduling, targeting, and timing differ. Descriptions never replace executable rule data.

The engine distinguishes **attributes** (broad capacities), **skills** (trained competencies), **derived statistics** (calculated values), **resources** (spendable pools), **abilities** (performable actions), **traits** (passive rules), and **conditions** (temporary effects). Pack terminology can differ without collapsing these concepts into an unvalidated generic number map.

P04.5's [neutral genre fixtures](../backend/src/test/resources/packs/README.md)
compile Trailcraft/Vigor and Systems/Charge, distinct creation budgets, recovery
abilities and exploration objectives through the same engine. This is content
import evidence only. No loadable module runtime exists yet; both fixtures leave
guide, creative abilities and romance disabled, without claiming gameplay checks.

Since P10.1 the character part of this model is executable: attributes with ranges, skills with a
maximum rank, derived statistics, learned abilities with prerequisites, conditions with stacking,
explicit item rarity tiers and equipment slots with hand limits are pack rules the backend
validates and applies ([ADR 0023](adr/0023-character-rules-and-equipment-constraints.md),
[domain model](05-domain-model.md#character-rules-and-state-p101)). Using them during play
is P10.2 (atomic use, transfer, equip and trade commands and source-linked item creation, done), P10.3 (quest transitions and one-time rewards, done below), P10.4 (movement duration, rest and the bounded world clock, done) and P10.5 (panels and journal, done, [ADR 0027](adr/0027-player-panels-and-journal-projection.md)). Since P11.1 the same actor enters an authored encounter with its current Health, worn items and conditions; tactical mode, per-adventure combat profiles and encounter templates are described in [combat](11-combat-system.md#2-combat-state-machine) and [ADR 0028](adr/0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md). Mechanics are
never derived from artwork or prose: an item's tier, slot, hands, tags and any bonus come from its
definition.

## 2. Exploration and action resolution

Locations form a directed graph with traversal requirements and durations (since P10.4 a link's `minutes` is the time a move along it adds to the world clock, see [ADR 0026](adr/0026-bounded-world-clock-travel-duration-and-rest.md)). A character's actual position is separate from map discovery. Moving checks accessibility, presence, hazards, and equipment. Travel can produce a proposed encounter, but the engine must admit that encounter before reporting an attack.

Use a deterministic result for an obviously possible or impossible action. An uncertain action uses the configured check formula and difficulty. The default AI RPG Skirmish noncombat check is `2d10 + relevant attribute + trained skill + bounded situational modifier` against 8 routine, 12 demanding, 16 difficult, 20 severe, or 24 exceptional. Do not roll a routine action solely to manufacture failure. Skill/attribute selection and difficulty are disclosed before a player commits a consequential attempt when the character can reasonably judge them.

The default situational modifier range is -3 to +3 after combining supported sources. Success meets or exceeds difficulty. A margin of six or more may activate a rule-defined additional benefit; failure can advance a complication clock or consume the attempted action's time/resources according to its definition. The narrator cannot invent permanent injury from an unrelated minor failure.

## 3. Inventory and economy

Support stackable consumables, unique equipment, containers, quest objects, materials, and currency. Pack-defined equipment slots include capacity and compatibility tags. Inspecting an item is a query; drawing, equipping, trading, consuming, or discarding is an action when the rules assign time/cost.

Trades commit the offered items, prices, currency, ownership, and event together. A price rumor is not an accepted offer. Currency cannot become negative unless an explicit debt system is enabled. Unique objects cannot be held by two actors. A generated reward must be admitted as a valid definition/instance with a recorded source.

Since P10.2 these rules are executable. The player can use an item, wear and take off equipment, give units to a co-present actor and trade; each is one atomic commit of the rows, resources, events and receipt ([ADR 0024](adr/0024-atomic-item-commands-and-source-linked-creation.md)). An item's pack data declares whether it can be used (`use`), what it does (the ability effect grammar, with `consumes` deciding whether a unit is used up) and what it is worth (`value`). A trade is accepted by rule, not by wording: every item must have a declared value and the counterparty must receive at least the value it gives. Currency is an item with a value, so it cannot go negative, and the player's whole stack of a unique item keeps its identity and artwork when it changes hands. Units that enter the campaign through an effect are written to an append-only creation ledger with the operation, the announcing event and the source, so a replayed source cannot create them twice. Since P10.5 the inventory panel shows each stack's quantity, public name, rarity tier and the slot of a worn instance, and the equipment list shows what is worn and the hands it occupies, all read from committed rows. Weight, capacity and durability, NPC stock and shop screens remain later work (P13.4).

Weight/capacity and durability are supported configurable policies in v1, with clear disabled behavior. Inventory displays quantities and current equipment from the backend. The UI must not infer magical bonuses or resource restoration from text.

## 4. Character creation

A creation profile specifies an attribute budget, skill choices, optional archetypes, trait exclusions, initial resources, known abilities, and starting loadout. The backend validates the total rather than trusting UI restrictions. Archetypes are data presets, not mandatory classes.

The default proposal uses four attributes, Force, Finesse, Insight, and Resolve; each begins in the range 0–4 with a creation budget of eight points. Skills begin 0–2 with six total skill points across the pack's allowed skill list. These numbers belong to the default ruleset, not the engine kernel, and are provisional ([combat register](11-combat-system.md#17-provisional-numerical-defaults)). A pack may supply a compatible profile within validated limits.

Creation previews are read-only. Campaign creation commits actor, loadout, initial world state, content hashes, initial event, and pending decision together. Reopening a campaign never recreates its starting inventory.

## 5. Quests and objectives

Supported lifecycle: undiscovered → available → active → completed or failed; active may become suspended and return to active. Availability and discovery are distinct. A journal shows only discovered information. Quest objective predicates include acquired fact, visited location, possession/transfer, defeated encounter, completed interaction, and a bounded clock threshold.

Progress is derived from committed events and checked state. Avoid natural-language completion tests as the sole authority. A model can propose that an objective appears satisfied, but the engine evaluates its predicate. Completed quests cannot be re-awarded under a new paraphrase of the same source. Alternative outcomes have explicit settlement policies and consequences.

Since P10.3 this lifecycle is executable ([ADR 0025](adr/0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md)). After every command that can change a fact a predicate reads (movement, item commands, ability use and the quest commands), a deterministic engine re-evaluates the pinned quests over committed state and the commit writes the result with the command's own effects:

- an `undiscovered` quest becomes `available` once its `availableWhen` holds (always, when it has none); becoming available is the discovery event, so eligibility and knowledge are not yet separate (rumors and issuers arrive with P10.5 and P15);
- the player accepts an `available` quest, may suspend an `active` one and resume it; nothing else is a player request, and accepting does not need the issuer nearby yet;
- an `active` quest marks each objective satisfied when its predicate holds (`visited` a location, `possesses` units of an item, another quest's status or objective); a satisfied objective stays satisfied even if the proof is given away;
- the first outcome, in authored order, whose predicate holds ends the quest: `success` and `alternative` complete it, `failure` fails it. Its item rewards are granted to the protagonist at that moment, once, and later predicates in the same commit already count them, so one command can finish a chain of quests;
- suspended quests track nothing, ended quests never change again, and a returning player is never paid twice;
- a quest declared `secret` is hidden background content: it starts by itself, no command addresses it, its progress stays internal and only its ending is public.

Predicates for acquired facts, defeated encounters and completed interactions are not in the compiled grammar yet (the bounded clock is, since P10.4); the engine evaluates what the grammar offers and does not read prose. Since P10.5 the playthrough view shows the result: the quest panel lists every discovered quest from the engine's state with its objectives (done, open or optional) and, once ended, its outcome and the units it paid, while a hidden background quest stays out until its ending is committed. The journal beside it is the public event history, worded in the campaign's language and paged by a stable cursor. Neither panel computes anything; both display what the last commit stored.

An emergent quest is validated content: it needs an identity, achievable conditions, issuer/source, known or hidden scope, reward budget, and failure/expiry behavior. It is not simply an AI sentence ending in “quest completed.”

## 6. NPCs, companions, and relationships

NPCs have stable identity, goals, personality constraints, current or last known position, beliefs, possessions, and remembered interactions. Background schedules advance only through world-time rules. The engine need not simulate every NPC each minute; evaluate relevant scheduled boundaries lazily and preserve event order.

Companions additionally have party membership, control policy, formation/zone, resource state, and their own knowledge. Control options are player-directed or AI-assisted within legal actions. The owner selects a policy; Codex cannot silently control the protagonist. A guide persona does not become a party combatant without an explicit actor profile and membership event.

Relationship dimensions are pack-defined bounded values, such as trust or respect. Significant changes need an event and cause. Hidden values stay hidden. Commitments and expressed boundaries are independent facts, not automatic consequences of crossing a numeric threshold. Optional romance is a content module and is disabled by default in neutral samples; it is not a condition for engine functionality.

## 7. World state, factions, and clocks

Factions maintain goals, reputation, resources, relationships, and bounded event clocks. A player action can start or advance a clock. Trigger evaluation is causal and deterministic for mechanics; Codex can suggest narrative interpretation or candidate developments.

**World clock (P10.4, implemented).** The campaign's in-world time is a counter of whole seconds. It moves only when a committed command resolves a duration (travel along a link, a wait of at most four hours, or a rest), by at most one in-world day per command, and it never passes one hundred years; the database refuses any other change. Looking at the game (state, resume card, stream, any `GET`) never advances it, and neither does real time. Quest predicates can read it with `worldTime`. Faction and event clocks below remain planned (P15.2).

A clock has owner, current value, target, tick policy, trigger, visibility, and settlement identity. On a long time jump, process due boundaries in order with a cap; if too many accumulate, pause for bounded catch-up rather than blocking indefinitely or skipping consequential events. UI reads do not trigger hidden world advancement.

Knowledge propagates through witnesses, messages, travel, or other declared mechanisms. A remote town does not instantly know a player's private conversation. Off-scene events cannot grant the protagonist knowledge without a recorded discovery.

## 8. Rest, recovery, and defeat

Rest requires a location, duration, interruption policy, resource recovery table, and condition-removal rules. It is an action, not a session reset. **Implemented in P10.4:** the `REST` command names a pack rest policy (`ruleType: rest`) with a duration, an optional location tag, an interruption chance, a resource recovery table and a list of conditions it removes. One recorded percentile draw decides interruption; an interrupted rest passes half the time, restores half and removes no condition. The player cannot choose the duration or the amounts. Ending combat still refills nothing (P11). Short and extended rest are configurable labels and policies, with no required external ruleset behavior.

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

Static/catalog assets support the MVP. Full v1 includes bounded asynchronous scene/portrait/item generation, registry, persistent visual identity, credits, safe delivery and fallback, as specified in [presentation assets](23-presentation-assets.md) and [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md). Codex text access alone does not establish image capability; verify it before enabling generation. Local English/Italian voice generation is an optional v1 capability under [its separate contract](30-local-speech-synthesis.md), with text-only fallback and rights/hardware/quality gates.

Each admitted item definition or visible unique variant has an identifying icon shared across shop, inventory, inspection and equipment; equivalent copies can reuse it. A purchase or save reload does not regenerate art. Character portraits and theme frames are separate reusable assets. Rarity is validated pack/ruleset data with an explicit tier label; ornate artwork never grants rarity, bonuses, price or loot odds. Missing art has a readable category fallback while a bounded job is pending.

Generate or request a new illustration only for a meaningful observable visual change or explicit owner/player request. Reuse during minor dialogue and inspections. Media jobs use source revision and scene identity so stale results cannot replace the current scene after the player moves.

Optional dynamic music follows public scene/combat state using reviewed licensed catalog entries, stable loops, bounded crossfades and explicit user activation. Remote playback must not require a manual download, but must respect hosting, privacy and license conditions and degrade to silence. The policy and researched reference-only credit records are in [dynamic music and credits](24-dynamic-music-and-credits.md); no audio integration is implemented here.

Audio follows observable committed state, starts after a user gesture, uses separate volume preferences, and tolerates missing sources. Effects are keyed to a committed event and play at most once per client delivery cursor. Loading a campaign does not replay old combat hits. Remote streams are opt-in with documented availability and privacy implications; no remote catalog from the private demo is copied by default.

## 12. Living-world and narrative preparation refinements

Model NPC intentions as persistent goals with priorities, prerequisites, known
resources, remembered causes and commitments. Relationships include directed
beliefs, rivalries/alliances, expressed boundaries and source-linked milestones;
trust/affection numbers alone cannot create consent, romance or an obligation.
Lies record the claimed fact and audience separately from canonical truth.
Witnesses and causal message routes bound off-screen knowledge propagation.

Use three relevance levels: deterministic/lazy schedule and clock updates for
ordinary actors, bounded event-driven goal selection for relevant off-screen
actors, and occasional deeper planning for significant unresolved situations.
No continuous per-NPC model loop and no real-time wall-clock world advancement.
Time jumps retain due-event order and catch-up limits. Preparatory world/director
output is not a simulation event; mechanics change only through validated
world-time operations.

Quest plans supply flexible motives, clues and optional approaches, including
failure/abandonment; they never prescribe the player's solution. A lightweight
direction policy tracks repetition, unresolved tension and foreshadowing sources
and occasionally requests a scoped planning task. It cannot secretly buff an
enemy, force a fight or erase an advantage for pacing. See [planning](28-asynchronous-narrative-planning.md)
and [paired evaluations](31-research-validation-and-evaluations.md) before
enabling more costly adaptive behavior. Direct narration and system terminology
follow the selected [English/Italian contract](29-localization.md).
