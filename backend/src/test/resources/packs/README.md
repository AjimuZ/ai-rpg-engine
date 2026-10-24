# Neutral genre import fixtures

These are complete contract-1 source inventories for the existing internal
`PackStaging` → `PackRegistry` import path. Each child directory is a pack root;
this README is outside both inventories and must not be included in their ZIPs.
The fixtures are test resources, excluded from the production jar. Authors can
copy either directory or ZIP its contents without editing engine source.

| Content | fantasy-exploration | science-fiction-salvage |
| --- | --- | --- |
| Title | Mosslight Trail | Silent Relay |
| Structure | Trailhead ↔ grove | Dock ↔ junction ↔ reactor |
| Attribute / skill | Awareness / Trailcraft | Logic / Systems |
| Resource cap | Vigor, 8 | Charge, 12 |
| Creation budgets | 2 attribute, 1 skill | 3 attribute, 2 skill |
| Starting tool | Trail Kit | Insulated Scanner |
| Ability | Catch Breath | Emergency Cell |
| Quest | Find the Waystone | Survey the Core |
| Map | Green woodland schematic | Blue station schematic |

Each pack contains an explicit manifest, rule vocabulary, connected locations,
skill, bounded recovery ability, item/appearance identity, creation preset,
available quest with a visited-location predicate and success settlement,
catalog-only generation policy, opening choice, credits and full MIT license.
Both deliberately reuse local IDs such as `kit`, `fieldwork`, `traveler` and
`survey`; references resolve by pack identity, never by display name. The original
48×32 PNGs are valid schematic import assets, not production artwork. All fixture
bytes, including credits and assets, participate in the immutable content hash.

`NeutralGenreImportTest` reads these directories as authored, previews without
writing, registers each directory and equivalent ZIP, checks idempotency and
stored byte equality, recompiles the stored inventories, and imports each ZIP
into an independent adventure. A separate case corrupts a real starting-item
reference in memory and requires a located rejection with no partial rows.

Run from `backend/` with the pinned JDK 21 and a disposable PostgreSQL 18.6
configured as described in the backend README:

```sh
./mvnw -B -ntp -Dtest=NeutralGenreImportTest test
```

This proves import and compilation on one build. Campaign creation, commands,
UI rendering, restart/restore isolation and playable QA-01/QA-33 remain future
work. The current engine supports no loadable modules: both manifests use
`modules: []`, so guide, creative abilities and romance are disabled. Distinct
optional module behavior must be demonstrated after those runtimes exist;
unvalidated configuration files would not establish it. English is the source
language; reviewed English/Italian overlays and fallback/coverage checks belong
to P04.6. The ruleset descriptor is provisional until P07.
