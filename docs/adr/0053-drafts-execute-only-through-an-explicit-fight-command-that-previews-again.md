# ADR 0053: Drafts execute only through an explicit fight command that previews again, and learned recipes are versioned drafts frozen at entry

- Status: Accepted; implemented in P15.4-B (21 November 2026). Drafts execute in fights only; learned recipes are learned
  in exploration and used in fights.
- Date: 21 November 2026
- Changes: the engine contract supports a second module, `creativeAbilities`. Packs gain one rule type, `creative`, that
  only that module allows. The command vocabulary gains the tactical `COMBAT_USE_DRAFT` and the exploration
  `LEARN_DRAFT`. Migration V35 adds the immutable `learned_recipe` table. No route or panel was added.

## Context

docs/10 section 9 fixes the flow: draft, validate, show cost and targets, the player commits, rules resolve, narration,
and an optional save as a learned ability. Drafting and explaining a recipe never execute it, and successful learning has
provenance and a versioned ability definition. docs/20 scenario 7 adds that a later execution request validates the
then-current state again. P15.4-A made the preview a pure function (`DraftPreview`) that nothing called, under a profile
type with no pack data.

A fight never reads the pinned pack once it started (ADR 0028, ADR 0031), except where pinned item data must be read: using an
item (P11.3) and readying one (P11.5, ADR 0034). The combat layer does not depend on the creative package, and the pack
compiler depends on neither. docs/10 also says a zero-cost profile is an explicit content choice, and "unlimited
creativity" never means unbounded work, targets or state.

## Decision

1. **The module is the gate.** `EngineContract.current()` supports `creativeAbilities`. A `ruleType: creative` rule
   compiles only in a pack whose own manifest lists the module (`MODULE_DISABLED` otherwise), at most once per pack
   (`DUPLICATE`), and only beside the pack's combat rule. The module takes no configuration file. The rule's bounds are
   checked against the engine ceilings in `rules.CreativeBounds`, which both the compiler and `CreativeProfile` use, so
   every compiled profile constructs.
2. **Power tiers bound high-power play.** A profile is `standard` by default: a complexity budget of at most 16, a real
   cost (a Focus or cooldown rate above 0) and no persistent effects. Only `power: high` lifts these three limits, up to
   a budget of 32. No tier lifts a frozen ability's bounds, the Focus and cooldown ceilings, or the rule that every draft
   costs at least one action point.
3. **One explicit command executes a draft.** `COMBAT_USE_DRAFT {draft, targetIds}` carries the draft as written and its
   targets, never a cost, cooldown or limit. It is a tactical intention, so it is legal only in a fight. The rule gate,
   at admission and again at commit under the campaign lock, previews the draft against the creative profile of the
   fight's own pack, the effects the fight froze, the player's current Focus and learned abilities (`DraftUse`). Its
   refusals are `ABILITY_UNAVAILABLE` (no profile), `EFFECT_UNSUPPORTED` (outside the profile) and `PREREQUISITE_UNMET`.
   The candidate then meets the ability rules through `TacticalRules.draftRefusal`. The resolver previews again with the
   same rows and passes the candidate to `CombatScheduler.useDraft`, which resolves it like a frozen ability.
4. **The profile is read from pinned content, its effects are frozen.** The draft-use command is the third tactical
   intention that reads the pinned catalog. Entry freezes the effects the profile permits into every fight of the pack,
   so the scheduler still reads only the frozen encounter. A fight of a pack without a profile freezes exactly what it
   did before.
5. **One recorded use for all drafts.** The candidate's reserved ID `#draft` can never be a canonical definition ID.
   Every draft's use is recorded under it, so a draft waits its own cooldown counted from the player's last draft,
   whichever draft that was. `COMBAT_USE_ABILITY` never finds it. Without a candidate, `TacticalRules.refusal` refuses a
   draft, so no caller can resolve one that was not previewed. Using a draft writes only the fight's own facts: pools,
   the recorded use, effects and events. The receipt's `draft` block lists the derived action points, Focus and cooldown.
6. **Learning is a separate explicit exploration command.** `LEARN_DRAFT {recipeId, draft}` names a recipe
   (`[a-z][a-z0-9-]{0,31}`) and carries the draft as written, never a version or cost. It is an exploration intention, so
   it is refused in a fight (`MODE_ILLEGAL`), and it executes nothing and spends nothing. The rule gate and the resolver
   preview the draft again under the campaign lock against the creative profile of the player's vocabulary pack, which is
   the pack of every fight the player can enter (`ENGAGE` requires it). Focus does not block a learning; the profile's
   prerequisites do. Its refusals are those of item 3, plus `ABILITY_UNAVAILABLE` for a ninth recipe ID or a hundredth
   version.
7. **A learned recipe is an immutable, versioned draft with provenance.** The commit writes one `learned_recipe` row (V35)
   per learning: the next version of that recipe ID, the label and the draft's wire form, the profile's pack and rule ID,
   the pinned content revision, the derived complexity and costs, and the revision, world time and public
   `RECIPE_LEARNED` event that announce it. An insert trigger requires the event to belong to that revision, each version
   to follow its predecessor and at most 8 recipe IDs per actor; rows can be neither updated nor deleted. The receipt's
   `recipe` block lists the recipe ID, version, frozen ability ID, complexity and costs.
8. **Entry freezes the latest version of each recipe.** `ENGAGE` previews the latest version of each recipe again against
   the fight pack's profile and the player's learned abilities. Each one that fits and whose prerequisites are learned is
   frozen into the player's form as an ordinary frozen ability under `#recipe:<id>`, which no canonical definition ID can
   equal, after the learned abilities. A recipe that no longer fits is simply not frozen. In the fight it is used with
   `COMBAT_USE_ABILITY`, has its own cooldown and appears in the legal options like any frozen ability. A player without
   recipes freezes exactly what it did before.

## Alternatives considered

- Freeze the creative profile into the encounter definition: no catalog read during the fight, but the combat layer would
  then depend on the creative types, or store an opaque form that only the commands layer reads. Reading the catalog
  matches the existing item exceptions.
- Give each draft its own cooldown key (for example a hash of the draft): a player could then alternate near-identical
  drafts to avoid every cooldown. Learned recipes do have their own cooldowns, which is why their number is bounded (8).
- A new refusal category for an invalid draft: clearer, but it would need new public codes, frontend messages and
  translations before any screen exists. The preview's issue codes remain available to a future preview route.
- Allow zero cost on any profile with both rates at 0: this was the P15.4-A behavior. Tying it to `power: high` makes it a
  visible content choice that a reviewer can find.
- Store a learned recipe as a `learned_ability` row: every reader of that table expects a canonical pack definition, and
  its key admits one row per ability, so it could hold neither the draft nor its versions.
- Store the frozen candidate instead of the draft: the costs would then outlive a content change. Storing the draft and
  previewing it again at entry keeps the engine, not the stored row, in charge of the price.
- Learn during a fight: the fight froze the player's abilities at entry and must not change them, so a learning would only
  take effect in a later fight anyway.

## Consequences

- An author who enables the module can let players improvise bounded abilities in fights, priced by the engine, and save
  up to 8 of them as recipes that later fights offer as ordinary abilities.
- A campaign whose content does not enable the module behaves as before. Its fights' definitions and hashes do not
  change, and it can learn nothing.
- Every test cleanup that truncates `campaign` must also truncate `learned_recipe`, and the backup tool covers schema 35.
- Not covered:
  - Use of a draft or recipe outside a fight.
  - A route or panel that shows a preview, the legal options listing a draft, a page sending either command, and a
    contract that turns a description into a draft.
  - Forgetting or renaming a recipe, and draft use by non-player participants.
  - The "bypass ordinary uncertainty through an explicit rule" part of docs/10's high-power play.
  - The complexity formula, rates, tier ceilings and the 8-recipe bound are provisional and have not been playtested.

## Verification

`DraftCommandsTest` (PostgreSQL, through the production admission, resolver and commit path), `LearnedRecipesTest`,
`DraftUseTest`, `DraftPreviewTest`, `CreativeRulesTest`, `TypedCommandTest#decodesADraftUseExactly`,
`TypedCommandTest#decodesALearningExactly` and `EventStatementsTest`. See the
[roadmap entry](../16-development-roadmap.md#19-p15--complete-supported-world-and-optional-modules) for the recorded
results and what was not proven.
