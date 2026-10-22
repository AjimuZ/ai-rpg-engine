# Adventure Pack Specification

## 1. Purpose and contract status

An adventure pack is a versioned set of declarative files describing playable content and supported rules configuration. It is not a script that can command the host computer. This document defines the proposed v1 authoring contract; no parser, validator, JSON Schema file, or pack compiler exists yet.

The engine must not need a fixed plot. A minimal pack supplies world principles, a starting location/situation, character creation, a ruleset, and generation boundaries. Quests, endings, a prologue, companions, and a guide are optional.

## 2. Proposed file tree

```text
my-adventure/
  adventure.yaml
  world/
    overview.md
    locations.yaml
    factions.yaml
    timeline.yaml
    secrets.yaml
  characters/
    creation.yaml
    npcs.yaml
  gameplay/
    items.yaml
    abilities.yaml
    conditions.yaml
    progression.yaml
    encounters.yaml
    quests.yaml
  narrative/
    style.md
    generation.yaml
    anchors.yaml
  presentation/
    theme.yaml
    assets.yaml
    audio.yaml
  extensions/
    guide.yaml
    creative-abilities.yaml
  assets/
  LICENSE.md
  CREDITS.md
```

Only `adventure.yaml` and the files it requires are mandatory. Files can be split into subdirectories through explicit manifest references. There is no unrestricted include, script loader, or recursive remote fetch.

## 3. Manifest fields

| Field | Required | Meaning |
| --- | --- | --- |
| `schemaVersion` | Yes | Version of the pack contract |
| `id` | Yes | Stable namespaced pack identifier |
| `version` | Yes | Semantic content release version |
| `title`, `summary` | Yes | Display information |
| `authors` | Yes | Credited people/organizations, without required personal contact details |
| `license` | Yes for distributable packs | SPDX identifier or explicit license-file reference |
| `engineCompatibility` | Yes | Supported engine version range |
| `ruleset` | Yes | Supported ruleset ID, version range, selected policy profile |
| `contentLocale` | Yes | Language tag for story content |
| `narrativeMode` | Yes | Authored, emergent, or hybrid |
| `entrypoint` | Yes | Starting situation/location and creation profile reference |
| `files` | Yes | Explicit content file references and categories |
| `modules` | Yes | Enabled supported modules and configuration references |
| `generationPolicy` | Yes | Allowed content categories, budgets, canon constraints |
| `presentationProfile` | No | Theme, panels, scene/map/audio behavior |
| `contentGuidance` | No | Tone, themes, exclusions, audience guidance |
| `dependencies` | No | Locally installed, explicitly versioned content packages |
| `provenance` | No | Source/authoring information and inherited material |

The compiler produces a normalized internal manifest and a content digest covering referenced files and asset hashes in sorted relative-path order. Import timestamps and installation paths are metadata outside that digest. A changed byte in the distributed pack produces a different digest even if the author forgot to bump its version; registration reports the conflict.

## 4. Definition model

| Definition | Required design data |
| --- | --- |
| Location | ID, name, public description, links, accessibility, tags, initial conditions, knowledge visibility |
| NPC | ID, identity, role, personality constraints, goals, initial location, actor profile, private/public facts |
| Faction | ID, public identity, goals, relationships, relevant clocks, initial knowledge |
| Item | ID, type, stack/slot policy, validated mechanical properties, description, provenance |
| Ability | ID, display text, prerequisites, action/resource costs, target policy, effect graph, tags |
| Condition | ID, timing, duration, stacking policy, supported modifiers/effects, removal conditions |
| Quest | ID, discoverability, objectives as predicates, transitions, outcomes, settlement policy |
| Encounter template | Eligible scene tags, roles, roster bounds, threat weights, zones, objectives, escape policy |
| Narrative anchor | Optional situation or potential development, eligibility, flexible outcomes; never an event already completed |
| Character creation | Allowed names/backgrounds/roles, attribute budget, skills, starting items and abilities |
| Progression profile | Thresholds, rewards, purchase choices, unlock requirements, caps |

Definitions reference stable IDs, never display-name matching. A label can be localized. Unknown optional data must be in a namespaced extension block validated by an enabled module; arbitrary unknown mechanical fields are errors.

## 5. Markdown versus structured data

Markdown holds atmosphere, lore descriptions, personality notes, and author guidance. YAML or JSON holds mechanics, references, conditions, starting state, and generation policies. Front matter, if supported, follows one documented schema and cannot hide operational instructions.

Prose may say that a sword is remarkable, but a numerical bonus requires a structured effect. A lore note saying “this always succeeds” cannot override target legality, resource bounds, or engine permissions. Import diagnostics should point to this mismatch rather than relying on the model to reconcile it.

Secrets use separate files and explicit visibility. They may be shipped in a full author pack but are not sent to player projections. A full author export inherently contains secrets; a player-facing campaign export is a separate product action.

## 6. Supported customization levels

1. **Presentation:** names, terminology, text, art, panel visibility, theme tokens, music mappings.
2. **Content:** NPCs, factions, locations, objects, quests, abilities, encounter templates.
3. **Rules data:** attribute/skill definitions and limits, costs, progression, approved formulas, allowed condition combinations.
4. **Policy selection:** difficulty bands, defeat/recovery policy, retreat behavior, loot policy, authored/emergent mode.
5. **Engine extension:** new effect primitive, scheduler, spatial model, or custom execution. Requires reviewed engine code in a future implementation phase; it cannot be achieved safely by arbitrary prose or JavaScript in a pack.

## 7. Bounded expressions and effects

The proposed expression language permits constants, allowed stat/resource references, arithmetic, bounded comparisons, boolean combinations, min/max/clamp, and references to supported dice expressions resolved by the backend. No loops, recursion, reflection, file access, network access, evaluation of host-language code, or imported functions.

Validate expression depth, node count, numeric ranges, division by zero, overflow, missing references, and evaluation cost. Recommended initial limits are depth 12, 200 nodes per formula, 20 effect nodes per ability, and eight targets per normal action. Ruleset-specific larger actions require explicit bounded support.

Effects are declarative nodes such as damage, heal, move, apply/remove condition, modify a supported resource, reveal a permitted fact, or create a bounded item instance from an admitted definition. Each has target, timing, source, and validation rules. Conditional effects are acyclic and use pre-resolution or post-resolution timing explicitly.

## 8. AI generation policy

Per category, select `disabled`, `catalogOnly`, or `bounded`. A bounded policy defines eligible tags, existing templates, numerical ranges, maximum new entities per scene, allowed novelty, forbidden contradictions, knowledge scope, budget, and approval behavior.

Generated additions remain proposals until validated and committed. Record generation profile, content source, base revision, references, and assigned campaign ID. The same named NPC must resolve to an existing identity when appropriate. A later scene must retrieve the admitted definition rather than generate the person again.

In combat, generated mechanics must use supported templates/effects and fit the frozen budget. Codex cannot invent an ability that bypasses the player's only available defense or retroactively increase a monster's HP. Secret content cannot be hinted by a public media cue.

## 9. Import and compilation pipeline

1. Stage the owner-selected directory/archive in a non-executable area.
2. Reject absolute paths, `..` traversal, symlinks/reparse points escaping the root, case collisions, executable payload types, oversized or explosive archives, and remote includes.
3. Parse with safe YAML/JSON settings; prohibit custom tags and object construction; bound aliases, nesting, and file count.
4. Validate manifest and individual definition schemas, preserving filename/line/field locations.
5. Resolve dependencies and cross-file references; detect duplicate IDs and unsupported versions.
6. Validate rules, effect graphs, expressions, creation budgets, quest cycles, and initial invariants.
7. Validate asset paths/types/hashes and required rights metadata.
8. Detect direct mechanical contradictions and issue warnings for unverifiable narrative ambiguity.
9. Produce a normalized immutable pack revision and an import report.
10. Register only if no blocking errors remain. Preview is read-only; activation requires the owner's import action.

Initial archive limits: 100 MB compressed, 250 MB expanded, 2,000 files, 1 MB per structured text file, and 50:1 maximum expansion ratio. These are configurable product limits to validate against actual sample packs; images can use a separate per-file asset limit. Allowlisted media types and decompression budgets are mandatory.

## 10. Diagnostic examples

| Condition | Required diagnostic |
| --- | --- |
| Ability references an unknown resource | File, ability ID, field path, missing resource ID, supported alternatives |
| Item requires an undefined equipment slot | Item ID and missing slot reference |
| Starting loadout violates hand capacity | Actor profile, conflicting items, source definitions |
| Quest completion depends on itself | Cycle path and affected quest IDs |
| Mandatory scene requires an impossible capability | Entrypoint/anchor path and unsatisfied prerequisite |
| Unsupported module | Module ID, required engine contract version |
| Image path escapes pack root | Path rejected; no host content included in output |
| Same ID/version has changed bytes | Existing/new hashes; require a new release or explicit local development revision |

Do not label a pack valid merely because YAML parses. Narrative reachability cannot always be proved statically; distinguish warnings from guaranteed checks.

## 11. Author workflow

Write a minimal manifest and world overview. Define character creation and starting situation. Select the default ruleset. Add only content needed for the first scene and set generation boundaries. Run validation, inspect diagnostics, preview a campaign, and perform a short playtest. Revise files, bump version, and import a new revision. Existing campaigns continue on their pinned snapshot.

A future editor must write this same contract. Its visual form cannot become a second incompatible authoring model.

## 12. Engine-generic acceptance

One neutral fantasy exploration pack and one science-fiction salvage pack must differ in vocabulary, world structure, skills, resources, assets, and optional guide/creative-ability settings. Both must run with the same built engine. Neither needs a full authored plot. Shared mechanics can remain AI RPG Skirmish while their presentation and supported numerical configuration differ.

## 13. Export and migration

Export an admitted campaign-generated NPC or ability only through a review that removes private runtime identifiers, selects rights/provenance, resolves dependencies, and creates stable content IDs. Do not copy the live save into an adventure pack and call it reusable content.

Pack migration is explicit, previewed, backed up, and reference-mapped. Removing an ability used by an active encounter blocks migration until a supported resolution is selected. The engine must never hot-reload changed combat formulas into a fight already in progress.
