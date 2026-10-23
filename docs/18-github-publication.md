# GitHub Publication and Project Governance

## 1. Publication intent

The documentation repository exists at [AjimuZ/ai-rpg-engine](https://github.com/AjimuZ/ai-rpg-engine), initially with private visibility and a `main` branch. It contains English design documentation, repository setup, and neutral development shells, not a playable engine or a runtime release. Making the repository public remains a separate owner visibility decision.

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
  start.sh                  # future packaged local entry point
  compose.yaml              # engine/database/runtime shell; launcher pending
  docker/                   # pinned engine and isolated runtime images
  frontend/                 # neutral Angular development shell
  backend/                  # Spring Boot shell; runtime bridge remains future work
  adventures/               # future neutral sample packs
  prompts/                  # future versioned runtime prompt templates
  schemas/                  # future pack and output contracts
  tests/                    # future cross-module neutral fixtures/evaluations
  docs/
  .github/                  # future issue/PR templates and CI
```

The repository currently contains design documentation, labeled UI concepts, repository policy files, a runtime image/probe, neutral [frontend](../frontend/README.md) and [backend](../backend/README.md) workspaces, and a partial root Compose database/runtime infrastructure. Its database labels are documented in the sanitized [`.env.example`](../.env.example). [CONTRIBUTING.md](../CONTRIBUTING.md) documents the shared editor and line-ending rules. The backend can optionally embed compiled browser assets and notices in a
single-origin executable jar. There is no runnable game application, engine
container service or packaged startup launcher yet. Runtime data, private campaigns, generated personal images, local database volumes, credentials, temporary workspaces, and raw traces live outside the tracked source tree and are ignored where applicable.

The intended local installation is the [containerized application with root `start.sh`](22-container-deployment-and-startup.md). Release setup instructions must exercise that packaged profile, including first sign-in, readiness, persistent restart, and safe stop; a host-only development setup does not complete that requirement.

## 3. Language and presentation

Use English for README, documentation, public prompts, source identifiers, code comments, issues, commits, ADRs, and release notes. Adventure story content can use its declared locale. Keep technical product claims concrete and distinguish supported, experimental, planned, and deferred capabilities.

Screenshots must show actual software and neutral content, with no tokens or private identifiers. A concept diagram is labeled as a design diagram. Do not show fictional “build passing” badges, fabricated performance numbers, or a finished-UI mockup as proof of implementation.

## 4. License and content rights

Project software and documentation use the [MIT License](../LICENSE), copyright 2026 Azim Ben Othman, consistent with the principal source project. MIT permits private and commercial use, modification, distribution, sublicensing, and sale, provided the copyright and permission notices are retained in copies or substantial portions. The license includes its standard warranty and liability disclaimers. [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) preserves the primary source notice. The [Open Source Initiative's MIT text](https://opensource.org/license/mit) is the reference for the standard terms.

Authors retain their rights in their own adventure packs and content; running a pack with the engine does not impose MIT on it. Separately supplied images, audio, fonts, icons, model-related assets, and external reference material can have different licenses. Their metadata must identify origin, author, license/version, required attribution, modifications, and distribution permission. The bundled UI concept is a documentation illustration whose provenance and distribution terms are recorded [alongside the image](mockups/advanced-gameplay-ui-v1.prompt.md). The demo's missing standalone license remains a reuse question; its private content and implementation are not copied here.

[ADR 0010](adr/0010-name-scope-format-licensing.md#decision-licensing-plan) holds the full licensing plan. Neutral sample packs are MIT and ship `LICENSE.md` and `CREDITS.md`. Bundled dependencies must use a permissive license from an allowlist; weak-copyleft and dual licenses need a recorded review, and strong-copyleft, source-available, and non-commercial licenses are excluded. The project builds container images locally and does not redistribute them.

## 5. Combat inspiration and rights

The product should describe AI RPG Skirmish as an original configurable turn-based ruleset. Do not copy external rulebook text, distinctive settings, creature descriptions, illustrations, stat blocks, or branded presentation. Renaming a copied system is not a sufficient publication process.

The U.S. Copyright Office distinguishes a game's idea/methods from protectable literary or pictorial expression. That distinction is useful background, not blanket clearance for a particular release or a statement about all jurisdictions or trademark rights. [U.S. Copyright Office: Games](https://www.copyright.gov/register/tx-games.html).

Wizards of the Coast publishes specifically licensed System Reference Documents. The existence of an SRD is not permission to reuse every D&D asset or product. If a future pack intentionally incorporates SRD material, record the exact version, licensed material, attribution, and license obligations separately. This design requires no SRD-derived content. [Official SRD page](https://www.dndbeyond.com/srd).

Name, trademark, and any commercial distribution review remain owner publication tasks. No affiliation or official compatibility claim is implied by the engine design.

## 6. Contribution model

Start with small coherent changes on the owner's chosen workflow. The owner confirmed on 22 October 2026 the single-`main` workflow inherited from the primary source: verified implementation commits are pushed directly to `main`, and no other long-lived branch is used. Force pushes and history rewrites are excluded. Contributor PRs can be adopted without changing runtime architecture.

A change includes its intent, user-visible behavior, affected contracts, relevant tests, documentation changes, and migration implications. A bug report includes engine/content/ruleset/adapter versions and redacted reproduction steps. Do not require sharing private campaign archives publicly.

Separate engine bug reports, content bugs, balance feedback, and feature requests. A pack-specific story preference is not automatically an engine defect.

## 7. Proposed CI gates

Documentation links and formatting; secret/private-path scanning; backend unit/integration checks; frontend build and relevant tests; pack-schema validation; deterministic combat regression; neutral adapter fixtures; dependency/license inventory; and selected browser paths. Add each when the corresponding code exists.

A local build-context regression suite now exists at [tools/container_packaging](../tools/container_packaging/README.md): it checks actual BuildKit filtering using neutral fixtures, without reading credentials or starting application services. Rendered Compose checks also verify infrastructure boundaries; full application packaging remains pending. Ordinary CI ([.github/workflows/ci.yml](../.github/workflows/ci.yml), P02.4) runs that suite plus the [repository checks](../tools/repository_checks/README.md) (secret/private-path scan, Markdown links, workflow policy, image pins), the frontend typecheck/test/build/audit, and the backend `verify` against a job-local PostgreSQL 18. The workflow uses `push`/`pull_request` only, a read-only token, no secrets, SHA-pinned actions and no runtime account, so a pull request from an untrusted fork cannot reach credentials. It has not yet run on GitHub; the first hosted run after the next push is the remaining evidence. Dependency license inventory and pack-schema/combat/browser gates are not part of it and stay with P19.1 and later code.

Live Codex tests are opt-in and separated from untrusted pull requests. Never expose credentials to arbitrary contributed code or imported packs. A live account-dependent test failure should not be confused with a deterministic rules regression.

## 8. Versioning

Version engine releases, API contracts, pack schema, ruleset revisions, prompt contracts, and adapter compatibility separately. Public semantic-versioning policy must explain what is considered compatible. A change to combat outcomes or resource costs is a ruleset change even if no API shape changes.

Each release records supported dependencies/OS, migration requirements, changed gameplay rules, fixed defects, known limitations, verified tests, and backup/restore instructions. Engine upgrades do not silently update running campaigns' content/ruleset hashes.

## 9. Publication checklist

For the current documentation repository, the remaining practical preparation is a public-content review, a private security-reporting route, and documentation checks in CI. Issue forms and a pull-request template are useful when accepting external contributions. Branch protection becomes useful when collaborators or automated checks are introduced. These items do not require implementing the engine first.

The checklist below also includes gates for a later playable release; those runtime gates do not block sharing an accurately labeled design repository.

- [x] Verify repository availability and location for the selected name AI RPG Engine and slug `ai-rpg-engine`.
- [x] Root MIT license is added; upstream notices retained.
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
