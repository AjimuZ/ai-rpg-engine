# Dynamic Encounters, Difficulty, and Progression

## 1. Generation responsibilities

Codex composes context-sensitive candidates. The engine provides allowed templates, supported mechanics, current capabilities, and a threat budget; it validates the result and owns every final calculation. “AI-generated combat” means generated composition and tactics inside enforceable rules, not AI-selected victory or arbitrary statistics.

Separate **encounter planning**, **encounter execution**, and **outcome settlement**. Generation occurs before entry, not repeatedly before every enemy action.

## 2. Encounter input snapshot

Capture pack/ruleset hashes, source revision, scene tags, known threats, participating actors, health and resource ratios, conditions, equipped and accessible items, ammunition, learned abilities, skill ranks, party size, mobility, damage/defense coverage, escape options, objective, recent challenge history, and selected difficulty policy.

Do not assume an item in storage is equipped. Do not count an unknown ability, a depleted charge, an absent companion, or an unaccepted reward. Consider encumbrance, injuries, exhaustion if enabled, and terrain restrictions. Private world facts may constrain planning, but an enemy's tactical knowledge is separately filtered.

The snapshot has an identifier/hash. If a participating actor's mechanical state changes before entry, revalidate or regenerate; never start from stale equipment assumptions.

## 3. Planning pipeline

1. Determine why an encounter is eligible: authored trigger, world clock, player hostility, exploration complication, or explicitly allowed emergent event.
2. Establish objective and stakes: survive, protect, retrieve, escape, disable, negotiate, or eliminate.
3. Calculate the permitted threat band and roster/action-economy limits.
4. Select compatible environment and creature/archetype templates.
5. Ask Codex for a bounded candidate: roster references or permitted variants, zones, tactics, motivations, initial visibility, retreat behavior, and potential reward source.
6. Validate schemas, IDs, ability graphs, numerical ranges, threat weights, knowledge, solvability, spatial connectivity, reward limits, and canon.
7. Allow at most one proposal repair. If still invalid, use a deterministic safe eligible template or report no valid encounter; do not invent an unavoidable fight.
8. Assign stable campaign-scoped identities to admitted generated content.
9. Freeze the encounter and show observable stakes before the first irreversible action where possible.
10. Execute using the combat module and settle exactly once.

## 4. Initial threat heuristic

The first implementation needs an understandable heuristic, not a false claim of exact win probability. The following is a **proposed calibration model** to test and adjust; its values are listed in the [provisional numerical defaults register](11-combat-system.md#17-provisional-numerical-defaults).

For each participating player/companion actor:

`baseCapacity = 8 + 2 × (level - 1)`

`preparedness = clamp(0.55 + 0.30 × healthRatio + 0.15 × relevantResourceRatio, 0.55, 1.00)`

If the actor does not rely on a spendable combat resource, use a resource ratio of 1. If an actor is defeated, excluded, or absent, contribute zero. Sum `baseCapacity × preparedness` and round down to obtain party capacity, with a minimum of one for an otherwise active party. Rare equipment and combinations may require an explicit bounded capacity adjustment from a reviewed rules profile, not an AI guess.

| Difficulty | Enemy threat target relative to capacity | Intended use |
| --- | --- | --- |
| Gentle | 60–80% | Teaching and low-stakes scenes |
| Standard | 90–110% | Default meaningful challenge |
| Hard | 120–140% | Communicated risk and stronger tactics |
| Severe | 150–170% | Explicitly selected dangerous content with known stakes |

Default base enemy threat costs at level 1: minor 4, standard 8, elite 12, anchor 20. For higher-level templates use `ceil(baseCost × (1 + 0.20 × (level - 1)))` as an initial scaling candidate. Catalog profiles assign bounded health, attack, damage, defense, and ability ranges for each cost and level; changing a profile outside those ranges requires a new reviewed cost. AI cannot declare an arbitrarily strong enemy “minor” to fit the budget.

Threat totals are necessary but insufficient. Also limit hostile active bodies to at most party size plus two by default, prevent unsupported repeated control effects, cap area damage and burst combinations, and reject inaccessible invulnerable targets. Boss phases count their complete expected action economy and resources. A solo anchor with extra activations must pay for those activations explicitly.

## 5. Feasibility and fairness checks

- At least one known legal action can advance the objective, or an escape/negotiation solution exists and is discoverable.
- A required damage type or traversal capability is available or can be obtained through an admitted action.
- A permanently unreachable enemy cannot force an unwinnable elimination objective.
- Terrain does not seal all exits unless the authored stakes explicitly allow entrapment and the encounter remains valid.
- Consecutive control abilities cannot deny all player turns indefinitely; impose duration, immunity-window, and stacking caps.
- Randomness cannot create a mechanical infinite loop. A round/action/effect budget stops unexpected cycles.
- NPC knowledge cannot target undisclosed weaknesses, hidden inventory, or the player's unexpressed plans.
- Threat escalation must not secretly scale upward whenever the player gains an advantage.

These checks bound obvious failures; they do not prove balance or narrative fairness. Run simulations and human playtests to calibrate damage, survivability, encounter length, and tactic quality.

## 6. Dynamic variation without arbitrary mutation

Allowed variation: roster composition within a threat band, original visual/name variants, motivations, zone layout, cover, environmental objectives, legal ability combinations, and tactical priorities. All mechanics freeze at entry.

Disallowed adaptation: increasing HP because the player hits hard; adding an immunity after an attack; changing initiative order to save an enemy; inventing a counterspell after seeing the chosen ability; reducing loot after victory without a rule; or secretly spending player resources to raise tension.

Reinforcements and transformations are authored/validated phases with triggers and pre-accounted threat budgets. They may be hidden as information, but they are not unbudgeted retroactive corrections.

## 7. Tactics and optimization

Build legal candidate actions locally and rank them by declared behavior: protect objective, survive, pursue target, support ally, or retreat. Codex may choose a candidate and targets using the actor's observations. Deterministic fallback guarantees progress when AI is unavailable.

Optimize runtime by caching compiled definitions, calculating reachable zones once per relevant revision, batching public state reads, using local arithmetic, and avoiding AI calls for UI inspection or every effect. Batch narrative descriptions after a resolved enemy activation where this preserves player decision points. Do not batch past a reaction policy change window, defeat, new revelation, or player turn.

Progression “optimization” means evaluating balance and offering legal build comparisons. The engine never reallocates the player's skills or equipment automatically. A guide can explain costs and choices without selecting advancement for the player.

## 8. Rewards

Each encounter declares a reward source, eligibility, outcome-dependent policy, and unique settlement key. Rewards may include XP, currency, discovered facts, items, reputation, quest progress, access, or rescued actors. Surrender or clever avoidance can earn a challenge reward without requiring death.

Since P10.2 the creation primitive exists: units that enter a campaign are written to the append-only `item_creation` ledger with their operation, announcing event and a source key (a `REWARD` source must name a reward settlement of the same commit), and a transfer moves an existing instance without creating units. Loot ownership, container rules and reward tables remain P13.4. Loot derives from admitted inventories, containers, or an authored/generated reward table. An item cannot both remain equipped on a living enemy and appear in the player's pack unless a transfer event explains it. Default defeated-enemy loot is available to inspect/collect, not automatically owned.

A quest and its associated fight may refer to the same `challengeSourceId`. The default policy awards that challenge XP once; a separate quest milestone can reward an additional distinct objective. Do not duplicate rewards through aliases, repeated journal views, respawn abuse, or restore/retry delivery.

## 9. Default advancement proposal

AI RPG Skirmish uses a simple optional level-and-training profile. Packs may instead select supported milestone advancement. Cumulative XP required to reach level `L` is:

`50 × L × (L - 1)`

| Level | Total XP threshold |
| --- | --- |
| 1 | 0 |
| 2 | 100 |
| 3 | 300 |
| 4 | 600 |
| 5 | 1,000 |

Default challenge awards are 10 for a minor meaningful challenge, 20 for a standard one, and 35 for a major one. Awards depend on source/outcome policy, not every attack or enemy token. Repeating a trivial safe action is not a new meaningful challenge.

Each gained level grants one training point. Every even level grants one attribute point. Attributes cap at six. A skill's supported rank cap is `min(5, level + 1)`; buying the next rank costs that new rank in training points and requires its declared prerequisite. Initial creation uses its own six-point skill allocation with ranks 0–2. Abilities may require skill ranks, a teacher/discovery, or an advancement choice, but their requirements are structured.

Health maximum increases by two per gained level through its formula; Focus maximum increases by one. Current pools do **not** automatically increase or refill when their maxima rise. Attribute changes may alter maxima through formulas; current values are clamped only if a maximum decreases. Award XP after encounter settlement, open advancement choices at the next safe boundary, and do not interrupt a frozen encounter to change its mechanical profile.

If an award crosses multiple thresholds, record every gained level and entitlement exactly once. Unspent choices remain pending. The UI previews effects before a purchase and commits a validated purchase operation. A new ability is not usable until that operation succeeds.

## 10. Milestones and alternative profiles

A milestone profile advances through named predicates such as completing a chapter, resolving a faction conflict, or discovering a technique. It can grant skill points or abilities without levels, provided the encounter-capacity profile has an explicit power tier mapping. Do not feed an undefined level into the default threat formula.

v1 supports selecting and configuring supplied advancement policies. Entirely different advancement algorithms require a compatible ruleset implementation. Changes during an active campaign use an audited migration with a before/after preview.

## 11. Balance evaluation

Measure expected damage per activation, effective durability, action denial, resource consumption, average rounds, escape success, encounter outcome distribution, dominant ability combinations, and reward pacing. Test characters at low and high allowed builds, injured parties, no-ammunition states, and asymmetric party roles.

Use deterministic simulations for large sweeps and recorded seeds for regressions. Use human playtests for clarity and perceived fairness. Publish observed ranges with sample size; a heuristic score is not a certified probability or a promise that every generated fight is fair.
