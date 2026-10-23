# Later Adventure Editor and Bounded Customization

## 1. Delivery order

The owner requests an integrated graphical/text adventure editor **after the playable engine and core v1 work**. First deliver file-based authoring, validation, isolated workspaces, narrative play, combat and media. This separate E-series checklist is outside the current P00–P19 execution queue and initial v1 release gate. No editor or completion service is implemented by this document.

## 2. Author intent before automatic completion

The editor works inside the selected adventure. Combine text sections for world history, tone, places, requirements and special requests with structured entity/rule inspectors. An author can define a particular character's identity, appearance, role and traits and require that character or another element to exist or be introduced under explicit conditions.

Distinguish three states per field/entity:

- **Author-fixed:** supplied facts, appearance, required entities, exclusions and supported rules. Automatic completion must not overwrite them.
- **Open to generation:** missing/optional details within the world's explicit generation policy and budgets. Proposals require validation before becoming canon.
- **Unresolved/invalid:** contradictory requests, missing required technical data or unsupported mechanics. Show diagnostics or request an author decision instead of silently inventing a resolution.

Use stable IDs and supported, testable predicates. “This character must exist,” “introduce them at this scene” and “keep them in every scene” differ; show the interpreted requirement before publication when free text must be structured. Required presence does not silently imply immortality, forced player agreement or an impossible appearance. Private author/GM notes remain outside player-facing context.

Assistance may propose missing characters, scenery, items and story details within those gaps. Preserve author-versus-completion provenance, preview the diff and require explicit adoption/publication. Runtime narration must preserve admitted fixed facts, reuse the specified NPC's identity and saved portrait, and never replace them with a newly invented duplicate.

## 3. Genre-independent authoring

Support fantasy, cyberpunk, science fiction and other compatible settings through data: terminology, world history, character choices, abilities, equipment, currencies, factions, visual style and enabled modules. Require no magic, fantasy species, medieval shop or mandatory protagonist. Demonstrate fantasy and cyberpunk workspaces on the same executable. A genre label does not authorize unsupported mechanics or bypass validation.

## 4. Combat as a customizable mode

Combat has a dedicated tactical screen and returns to visual-novel play after settlement. It shares the campaign's actors, inventory, artwork and canonical resources; mode changes never create a second save or resolve an opening action twice.

The accepted **AI RPG Skirmish** profile is the default, using original tabletop-inspired rules rather than importing Dungeons & Dragons text/stat blocks. Authors may customize supported stat/resource labels and ranges, bounded derived formulas, equipment, damage/armor, action costs, abilities/effects, condition duration/stacking, predeclared reactions, enemy templates, zones, objectives, rewards and presentation. Every field remains subject to typed references, supported primitives, numerical bounds, ownership and settlement invariants.

The initial kernel retains one turn scheduler and zone-based spatial model. Real-time combat, a new scheduler/grid algorithm, arbitrary scripts or new effect primitives require reviewed engine extensions. Offer only supported controls, explain unsupported requests, compare changes with the default and run a deterministic encounter preview. Parsing alone does not prove balance.

Publish a new immutable content/rules revision. Existing campaigns keep their pinned revision until an explicit migration preview; active combat retains frozen rules and never hot-reloads an edited formula mid-round.

## 5. Later execution checklist

- [ ] E01 Build the selected-adventure editor and world/history/requirements text sections over the existing file contract.
- [ ] E02 Add entity/appearance inspectors, fixed/open fields, mandatory-character predicates and conflict diagnostics.
- [ ] E03 Add bounded gap-completion drafts, provenance and review/adoption without overwriting authored facts.
- [ ] E04 Add supported combat controls, default comparison and deterministic preview; reject unsupported algorithms/code.
- [ ] E05 Add validate/publish/version/migration previews, isolated workspace persistence and import/export of portable story bundles for sharing between people, using the existing pack contract with dependencies, locales, hashes, licensed assets and credits.
- [ ] E06 Verify fantasy/cyberpunk authoring, required NPC/locked-field preservation, image identity, player/GM separation, frozen combat, keyboard use and export/import on another owner's clean installation without account/save leakage or silent ID/version overwrite.

Activate this as a separate implementation project after the core is complete. It must write the same manifests/schemas as manual files, not a second incompatible format.

## 6. Sharing stories between people

The owner explicitly requires shareable stories. Export the reusable world,
characters, supported rules, authored constraints, EN/IT text and redistributable
resources as a versioned directory/ZIP under the [adventure bundle contract](09-adventure-packs.md#14-portable-stories-and-person-to-person-sharing).
Another owner can preview and import that bundle and create their own isolated
adventure/playthrough. The editor does not need an online marketplace, hosting
account or direct file-transfer service; users can exchange the resulting file.

Show compatibility, missing dependencies, locale coverage, permissions/credits
and omitted assets before export/import. Preserve original author provenance;
forks receive a deliberate new content identity. Default exports exclude private
playthrough history, credentials, live jobs and account/model state. Full editable
story bundles may contain GM plot secrets and are labeled accordingly. Existing
recipient adventures and saves are never overwritten implicitly. Graphical
editing remains post-core; manual pack exchange and P16 author exports establish
portability earlier without pulling the editor into the MVP.
