# GitHub Publication and Project Governance

## 1. Publication intent

The project is intended for later GitHub publication. This phase prepares English documentation only. No remote repository, issue, pull request, commit, push, or release was created as part of this deliverable.

A documentation-only repository can be published honestly after the owner chooses its name, license, and public-content scope. A playable release must additionally satisfy runtime release gates. Do not describe a design repository as a working game engine.

## 2. Proposed future repository structure

```text
ai-rpg-engine/
  README.md
  LICENSE
  THIRD_PARTY_NOTICES.md
  CHANGELOG.md
  CONTRIBUTING.md
  SECURITY.md
  frontend/                 # future Angular application
  backend/                  # future Spring Boot application and Codex bridge
  adventures/               # future neutral sample packs
  prompts/                  # future versioned runtime prompt templates
  schemas/                  # future pack and output contracts
  tests/                    # future cross-module neutral fixtures/evaluations
  docs/
  .github/                  # future issue/PR templates and CI
```

The repository currently contains design documentation, a labeled UI concept, and repository housekeeping files. Application folders and executable runtime components do not exist yet. Runtime data, private campaigns, generated personal images, local database volumes, credentials, temporary workspaces, and raw traces live outside the tracked source tree and are ignored where applicable.

## 3. Language and presentation

Use English for README, documentation, public prompts, source identifiers, code comments, issues, commits, ADRs, and release notes. Adventure story content can use its declared locale. Keep technical product claims concrete and distinguish supported, experimental, planned, and deferred capabilities.

Screenshots must show actual software and neutral content, with no tokens or private identifiers. A concept diagram is labeled as a design diagram. Do not show fictional “build passing” badges, fabricated performance numbers, or a finished-UI mockup as proof of implementation.

## 4. Licensing plan

MIT is the recommended software/documentation license because the principal source project uses it. The owner must select and add the new repository's root license; this documentation does not make that decision silently. Retain upstream copyright and permission notices when distributing reused licensed material. [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) preserves the primary source notice.

Adventure packs, images, audio, fonts, icons, model-related assets, and external reference material can have different licenses. Their metadata must identify origin, author, license/version, required attribution, modifications, and distribution permission. An MIT code license does not automatically apply to them. The demo's missing standalone license is an explicit reuse question; its private content and implementation are not copied here.

## 5. Combat inspiration and rights

The product should describe AI RPG Skirmish as an original configurable turn-based ruleset. Do not copy external rulebook text, distinctive settings, creature descriptions, illustrations, stat blocks, or branded presentation. Renaming a copied system is not a sufficient publication process.

The U.S. Copyright Office distinguishes a game's idea/methods from protectable literary or pictorial expression. That distinction is useful background, not blanket clearance for a particular release or a statement about all jurisdictions or trademark rights. [U.S. Copyright Office: Games](https://www.copyright.gov/register/tx-games.html).

Wizards of the Coast publishes specifically licensed System Reference Documents. The existence of an SRD is not permission to reuse every D&D asset or product. If a future pack intentionally incorporates SRD material, record the exact version, licensed material, attribution, and license obligations separately. This design requires no SRD-derived content. [Official SRD page](https://www.dndbeyond.com/srd).

Name, trademark, and any commercial distribution review remain owner publication tasks. No affiliation or official compatibility claim is implied by the engine design.

## 6. Contribution model

Start with small coherent changes on the owner's chosen workflow. The primary source preferred a single `main` branch and manual commit/push approval; retain that as the initial solo-work default unless the owner chooses a different GitHub collaboration policy. Contributor PRs can be adopted without changing runtime architecture.

A change includes its intent, user-visible behavior, affected contracts, relevant tests, documentation changes, and migration implications. A bug report includes engine/content/ruleset/adapter versions and redacted reproduction steps. Do not require sharing private campaign archives publicly.

Separate engine bug reports, content bugs, balance feedback, and feature requests. A pack-specific story preference is not automatically an engine defect.

## 7. Proposed CI gates

Documentation links and formatting; secret/private-path scanning; backend unit/integration checks; frontend build and relevant tests; pack-schema validation; deterministic combat regression; neutral adapter fixtures; dependency/license inventory; and selected browser paths. Add each when the corresponding code exists.

Live Codex tests are opt-in and separated from untrusted pull requests. Never expose credentials to arbitrary contributed code or imported packs. A live account-dependent test failure should not be confused with a deterministic rules regression.

## 8. Versioning

Version engine releases, API contracts, pack schema, ruleset revisions, prompt contracts, and adapter compatibility separately. Public semantic-versioning policy must explain what is considered compatible. A change to combat outcomes or resource costs is a ruleset change even if no API shape changes.

Each release records supported dependencies/OS, migration requirements, changed gameplay rules, fixed defects, known limitations, verified tests, and backup/restore instructions. Engine upgrades do not silently update running campaigns' content/ruleset hashes.

## 9. Publication checklist

- [ ] Verify repository availability and location for the selected name AI RPG Engine and slug `ai-rpg-engine`.
- [ ] Root license is selected and added; upstream notices retained.
- [ ] All bundled content/assets have a rights/provenance record.
- [ ] No private source-demo campaign, credentials, logs, or machine-specific paths are included.
- [ ] README accurately states design/MVP/v1 status.
- [ ] Internal links and public external references are reviewed.
- [ ] Actual setup commands match the selected release and work from a clean clone, if runtime is claimed.
- [ ] Security contact/reporting route is configured without publishing private contact information by assumption.
- [ ] CI and screenshots reflect the actual release scope.
- [ ] Known limitations, compatibility, account requirements, and outbound data flow are visible.
- [ ] Owner authorizes the publication action.

## 10. Release notes outline

State the problem the release solves, delivered capabilities, supported environment, content/ruleset versions, setup/migration steps, verification evidence, known limitations, and credits. Keep future plans in the roadmap rather than presenting them as already available.
