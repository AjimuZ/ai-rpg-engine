# Adventure Pack Specification

## 1. Purpose and contract status

An adventure pack is a versioned set of declarative files describing playable content and supported rules configuration. It is not a script that can command the host computer. P04.1 implements the in-memory manifest/parsing/hash stage and publishes structural JSON Schemas; semantic compilation, protected directory/archive import and immutable registration remain P04.2–P04.4. A `ParsedPack` is not a valid playable pack or an admitted `ContentRevision`. The concrete file syntax of pack contract 1 (`schemaVersion: 1`) is fixed by [ADR 0010](adr/0010-name-scope-format-licensing.md#decision-initial-pack-file-format-pack-contract-1). It covers UTF-8 encoding, YAML 1.2 core-schema `.yaml` files without anchors, aliases, tags, or duplicate keys, `.json` as an equivalent, CommonMark `.md` prose without front matter or rendered HTML, and directory or `.zip` distribution.

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

The parser produces a deeply immutable JSON-shaped manifest and a content digest covering the root manifest and every explicitly listed file, including asset bytes, in sorted relative-path order. Import timestamps and installation paths are metadata outside that digest. A changed byte in the distributed pack produces a different digest even if the author forgot to bump its version; future registration must report that conflict. YAML/JSON equivalence means the same schema and values, not the same raw-byte digest.

### 3.1 Concrete manifest shape

The bundled [manifest schema](../backend/src/main/resources/pack-contract/1/manifest.schema.json)
is JSON Schema draft 2020-12. Unknown fields are errors, including inside nested
manifest records. This stage requires a nonempty license declaration for every
pack; checking SPDX/file rights is part of later registration. Version is a
SemVer release label. Engine/ruleset range strings and policy/module IDs are
structurally required; support and compatibility are checked in P04.2–P04.3.
`contentLocale` retains its authored language tag; this stage checks its lexical
shape without mapping it to a player language or claiming locale coverage.

```yaml
schemaVersion: 1
id: example:harbor
version: 1.0.0
title: Harbor
summary: A quiet harbor.
authors: [Example Authors]
license: MIT
engineCompatibility: '>=0.1.0 <1.0.0'
ruleset: {id: skirmish, version: '>=1.0.0 <2.0.0', policyProfile: standard}
contentLocale: en
narrativeMode: emergent
entrypoint: {situation: arrival, location: harbor, creationProfile: visitor}
files:
  - {path: world.yaml, category: locations}
  - {path: generation.json, category: generationPolicy}
modules: []
generationPolicy: generation.json
```

This illustrates manifest syntax, not a complete playable fixture. `files` is an
array of unique `{path, category}` records. The root is exactly one of
`adventure.yaml`/`adventure.json`, excluded from `files`. Every other supplied file
must be listed; hidden/unreferenced extra bytes are rejected. Logical paths use
portable ASCII names, `/`, at most 240 characters, and no empty, `.` or `..`
segments. Absolute paths, backslashes and case collisions fail. Filesystem
containment, reparse points, Windows reserved names, ZIP handling and media
type/size checks still require P04.4; this API receives bytes and opens no paths.

Categories `lore`, `narrativeStyle`, `license`, `credits` require `.md`;
`asset` holds opaque binary bytes. Structured `.yaml`/`.json` categories are
`locations`, `factions`, `timeline`, `secrets`, `creation`, `npcs`, `items`,
`skills`, `abilities`, `conditions`, `progression`, `encounters`, `quests`,
`rules`, `generationPolicy`, `anchors`, `theme`, `assetCatalog`, `audio`, `guide`,
`creativeAbilities`. Category never comes from a filename guess.
`generationPolicy` names a listed file of that category; optional
`presentationProfile` names a listed `theme`. Modules are `{id, configuration?}`
records with unique IDs and optional listed structured-file paths. Dependencies
are `{id, version, hash}` records, where `hash` is lowercase hexadecimal SHA-256;
resolution/conflicts remain later compiler work. `contentGuidance` accepts
`tone`, `themes`, `exclusions`, `audience`; `provenance` accepts `source`, `notice`.
The optional localization map is reserved for P04.6 and rejected until its schema
and semantic checks exist.

The [definition-envelope schema](../backend/src/main/resources/pack-contract/1/definitions.schema.json)
publishes `{schemaVersion: 1, definitions: [{id, kind, data}]}`. It checks only
the envelope; `data` intentionally remains an object awaiting category-specific
P04.2–P04.3 schemas. Passing this schema cannot validate mechanics, visibility,
references or author constraints and cannot authorize registration.
The parser applies it to `locations`, `factions`, `creation`, `npcs`, `items`,
`skills`, `abilities`, `conditions`, `progression`, `encounters`, `quests`,
`rules`, `anchors`. Other structured categories currently require an object
root and safe parsing; their category-specific semantics are still unavailable.
Whole-value schema patterns require the actual end of input, including rejection
of a final line terminator in identifiers, versions, locales, paths and hashes.
They use an ECMAScript-compatible negative any-character assertion because `$`
alone can match before a final newline.

### 3.2 Normalized hash protocol

Pack raw protocol 1 computes SHA-256 over the UTF-8 domain
`rpgengine.pack.raw.v1` followed by one NUL byte, then a four-byte unsigned
big-endian file count. For each ASCII relative path in ascending ordinal order,
append its four-byte big-endian UTF-8 byte length, its UTF-8 bytes, the eight-byte
big-endian raw file byte length, and the 32 raw SHA-256 digest bytes of that file.
The root manifest participates exactly like other files. The result and per-file
digests use lowercase hexadecimal. No JSON reserialization, whitespace/comment,
Unicode, BOM or line-ending normalization occurs. Identical inventories have
identical hashes regardless of caller map order or host root; renamed files,
changed comments, assets, LF/CRLF or YAML/JSON source representations change them.
The immutable output does not expose caller-owned byte arrays. Future revisions
must preserve this framing or introduce an explicit new digest protocol.

## 4. Definition model

| Definition | Required design data |
| --- | --- |
| Location | ID, name, public description, links, accessibility, tags, initial conditions, knowledge visibility |
| NPC | ID, identity, role, personality constraints, goals, initial location, actor profile, private/public facts |
| Faction | ID, public identity, goals, relationships, relevant clocks, initial knowledge |
| Item | ID, type, stack/slot policy, validated rarity tier and mechanical properties, public appearance/visual identity, description, provenance |
| Ability | ID, display text, prerequisites, action/resource costs, target policy, effect graph, tags |
| Condition | ID, timing, duration, stacking policy, supported modifiers/effects, removal conditions |
| Quest | ID, discoverability, objectives as predicates, transitions, outcomes, settlement policy |
| Encounter template | Eligible scene tags, roles, roster bounds, threat weights, zones, objectives, escape policy |
| Narrative anchor | Optional situation or potential development, eligibility, flexible outcomes; never an event already completed |
| Character creation | Allowed names/backgrounds/roles, attribute budget, skills, starting items and abilities |
| Progression profile | Thresholds, rewards, purchase choices, unlock requirements, caps |

Definitions reference stable IDs, never display-name matching. A label can be localized. Unknown optional data must be in a namespaced extension block validated by an enabled module; arbitrary unknown mechanical fields are errors.

## 5. Markdown versus structured data

Markdown holds atmosphere, lore descriptions, personality notes, and author guidance. YAML or JSON holds mechanics, references, conditions, starting state, and generation policies. Front matter is not part of pack contract 1: a leading metadata block in a Markdown file is a blocking error, so prose cannot hide operational instructions or a second set of metadata. A later contract version may add one documented front-matter schema.

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

Presentation policy separately selects `disabled`, `catalogOnly` or `bounded` scene/portrait/item art, authorized style/reference assets and generation budgets. Artwork belongs to the admitted entity/appearance identity, is durably saved and reused, and never changes mechanical definitions. Pack-defined rarity tiers carry stable IDs, localized names and accessible presentation tokens; any mechanical effect or pricing rule must be explicit supported rules data. See [presentation assets](23-presentation-assets.md).

In combat, generated mechanics must use supported templates/effects and fit the frozen budget. Codex cannot invent an ability that bypasses the player's only available defense or retroactively increase a monster's HP. Secret content cannot be hinted by a public media cue.

## 9. Import and compilation pipeline

1. Stage the owner-selected directory/archive in a non-executable area.
2. Reject absolute paths, `..` traversal, symlinks/reparse points escaping the root, case collisions, executable payload types, oversized or explosive archives, and remote includes.
3. Parse with safe YAML 1.2 core-schema and JSON settings. Prohibit tags and object construction, reject anchors, aliases, merge keys, and duplicate keys (contract 1 allows no aliases), and bound nesting and file count.
4. Validate manifest and individual definition schemas, preserving filename/line/field locations.
5. Resolve dependencies and cross-file references; detect duplicate IDs and unsupported versions.
6. Validate rules, effect graphs, expressions, creation budgets, quest cycles, and initial invariants.
7. Validate asset paths/types/hashes and required rights metadata.
8. Detect direct mechanical contradictions and issue warnings for unverifiable narrative ambiguity.
9. Produce a normalized immutable pack revision and an import report.
10. Register only if no blocking errors remain. Preview is read-only; activation requires the owner's import action.

Initial archive limits: 100 MB compressed, 250 MB expanded, 2,000 files, 1 MB per structured text file, and 50:1 maximum expansion ratio. These are configurable product limits to validate against actual sample packs; images can use a separate per-file asset limit. Allowlisted media types and decompression budgets are mandatory.

The P04.1 in-memory boundary enforces at most 2,000 files/250 MiB total,
1 MiB per text file (including Markdown), depth 32, 20,000 nodes including mapping
keys, 65,536 UTF-16 units per scalar, 128 source characters per number and exact
decimal scale magnitude at most 1,000. YAML uses parser events and an explicit
[YAML 1.2 core resolver](https://yaml.org/spec/1.2.2/#1032-tag-resolution), never
the library's YAML 1.1 scalar resolution or object constructors. `yes/no/on/off`,
timestamps and `1:30` remain strings; `0777` is decimal 777, `0o17` is 15.
Nonfinite numbers and YAML directives are rejected as contract restrictions;
quoted scalars stay strings. JSON is strict RFC syntax with one root, no comments,
trailing commas, duplicate/merge keys or unpaired Unicode surrogates. Diagnostics
carry a stable code, relative filename, one-based line/column and RFC 6901 pointer;
raw parser messages and source snippets are not returned. Markdown is returned as
unrendered text; future rendering must preserve the HTML/remote-fetch restrictions.

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

Author-specified facts, required entities and supported constraints are fixed inputs, not suggestions the narrator can replace. Use stable IDs and supported requirement predicates; generate only permitted missing details within policy/budgets. Missing required technical fields and contradictory constraints are diagnostics, not permission to invent rules. The integrated text/graphical editor, genre examples and fixed/open workflow are in [the later editor roadmap](26-adventure-editor-roadmap.md).

## 12. Engine-generic acceptance

One neutral fantasy exploration pack and one science-fiction salvage pack must differ in vocabulary, world structure, skills, resources, assets, and optional guide/creative-ability settings. Both must run with the same built engine. Neither needs a full authored plot. Shared mechanics can remain AI RPG Skirmish while their presentation and supported numerical configuration differ.

## 13. Export and migration

Export an admitted campaign-generated NPC or ability only through a review that removes private runtime identifiers, selects rights/provenance, resolves dependencies, and creates stable content IDs. Do not copy the live save into an adventure pack and call it reusable content.

Pack migration is explicit, previewed, backed up, and reference-mapped. Removing an ability used by an active encounter blocks migration until a supported resolution is selected. The engine must never hot-reload changed combat formulas into a fight already in progress.

## 14. Portable stories and person-to-person sharing

An adventure bundle is the reusable story/world definition, distributed as the
existing contract-1 directory or `.zip`: manifest, explicit lore/entity/rule
files, authored anchors/generation boundaries, locale overlays/glossary, and
licensed presentation assets. An editor export uses this same contract; manual
authors can exchange it before the later editor exists. A finished plot is
optional and an exported story can remain authored, emergent or hybrid.

Export a new immutable pack revision with stable pack/content IDs, exact
supported schemas/engine/ruleset ranges, dependency versions/hashes, file/asset
hash manifest, provenance and per-component license/credit records. Bundle
permitted local dependencies explicitly or list required separately installable
revisions; no silent remote include or account-bound runtime dependency.
Nonredistributable assets are omitted with a diagnostic and usable fallback;
never claim that free playback or local generation automatically grants sharing
rights. Optional model weights/reference voice recordings are not part of an
ordinary story bundle.

Default export includes authored public and clearly marked GM/secret content
needed to run the story, not private campaign transcripts, player choices,
generated private memories/plans, job leases, provider IDs, credentials, machine
paths or active saves. Warn authors that a complete editable adventure can
contain plot secrets; a player-facing journal export is a separate audience.
Promoting a campaign-generated character/artifact into reusable authored content
requires an explicit reviewed content-export operation, rights and dependency
checks; do not export the whole playthrough implicitly.

Recipients preview origin, title, version, supported locales, dependencies,
rights, required modules and warnings before explicit import. Apply the existing
hostile-archive/parser limits regardless of trusted sender. An existing ID/version
with different hashes is a conflict; never overwrite silently. Duplicate imports
are idempotent, and a deliberate fork receives explicit new identity/provenance.
Install content immutably, then create a new local adventure/campaign with local
IDs. No recipient account, active world or save is replaced by import.

P16.2/P16.5 verify export followed by import on a separate clean installation,
both locales/genres, missing dependencies/assets, incompatible revisions,
malicious files and unchanged sender/recipient saves. The later editor E05/E06
adds graphical preview and round-trip tests over exactly this interchange format.

## 15. Localized content and narrative plans

The optional `localizations` map and text-only overlays are specified in
[localization](29-localization.md). Stable IDs and mechanical values remain
language-independent; the compiler reports coverage, glossary and source-text
fallbacks and includes overlays in revision hashes. Both neutral fixtures ship
reviewed EN/IT text; untranslated external content is labeled explicitly.

Authors may define flexible quest premises, allowed planning categories, protected
facts/required entities, forbidden developments and supported conditional beats.
The [plan contract](28-asynchronous-narrative-planning.md) references compiled IDs
and predicates, not arbitrary executable scripts. Generated plans cannot override
author-fixed inputs, invent guaranteed future events or authorize player choices.
