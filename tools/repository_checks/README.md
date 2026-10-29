# Repository checks

Credential-free checks used by the ordinary CI workflow
([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)) and runnable locally
before a push. They use only the Python standard library and local Git, need no
network, account, environment variable or secret, and print only a file, line and
rule name for each finding, never the matching text.

```sh
py -B tools/repository_checks/checks.py all        # Windows launcher; use python3 -B elsewhere
py -B tools/repository_checks/checks.py secrets    # or: docs | workflows | images | localization | contracts
py -B -m unittest discover -s tools/repository_checks -v
```

Exit status is 0 when clean, 1 when a finding exists and 2 for a bad argument.
The checks inspect tracked files plus untracked files that `.gitignore` does not
hide, which is exactly what a careless `git add .` could publish. A Git failure
raises an error instead of passing.

| Check | What it enforces |
|---|---|
| `secrets` | No private path is publishable (`.env*` except `.env.example`, `.local/`, assistant configuration and instruction files, account state, runtime data, backups, keys, logs). No credential-shaped content (private key blocks, cloud/repository/chat tokens, signed tokens, token fields, literal `password`/`token` assignments) and no developer-specific home path. `.gitignore` must still cover every private location. |
| `docs` | Balanced Markdown fences; every relative link and `#anchor` resolves, using GitHub's heading-slug rules; links may not escape the repository. |
| `workflows` | Top-level `permissions: contents: read` only; no `secrets` expression or `secrets: inherit`; no `pull_request_target`/`workflow_run`; no write scopes; no runtime-account names; every action pinned to a full commit SHA; checkout sets `persist-credentials: false`; every workflow `image:` has a digest; at least one workflow exists. |
| `localization` | Every `COMMAND_*`, `CAMPAIGN_*`, `STREAM_*` and `LOCAL_*` error code in the campaign controllers is a key of the browser's localized error table (`frontend/src/app/campaign/campaign-messages.ts`); a missing controller or message module is a finding. Findings name file and line, never the code. |
| `contracts` | Each recorded real-server transcript that frontend specs replay is byte-identical to the golden file a backend test regenerates and compares (`backend/src/test/resources/contracts/campaign-reload-transcript.json` and `frontend/src/app/campaign/contract/campaign-reload-transcript.json`, produced by `CampaignInterfaceContractTest`). A missing file on either side is a finding; findings name the file, never its content. Refresh both with `mvn test -Dtest=CampaignInterfaceContractTest -Dengine.contract.update=true` after an intended protocol change. |
| `images` | Every external Dockerfile `FROM`, `# syntax=` line, Compose `image:` and root-launcher `readonly *_IMAGE="…"` constant carries an exact tag and an index digest ([ADR 0009](../../docs/adr/0009-toolchain-baseline.md)). Earlier build stages and locally built Compose images are exempt. |

## Limits

- The scanners are pattern based. They block known regression shapes and cannot
  prove absence of secrets or a safe workflow; review remains required.
- Workflow and Compose checks are textual (no YAML parser dependency), written for
  this repository's formatting.
- A line may opt out of the content scan with the marker `secret-scan: allow`.
  Use it only for documented placeholders and never for a real value. Test fixtures
  build credential-like strings from fragments so this suite passes its own scan.
- `npm audit` in CI contacts the public advisory database and sends the dependency
  list, not credentials; an advisory published after a commit can fail a later run.
- [Hosted CI run 40028086153](https://github.com/AjimuZ/ai-rpg-engine/actions/runs/40028086153) completed successfully on commit `09a6311d9557be01f2fee9abeb119206f0b02e34` with all four jobs passing. Read-only run/jobs queries confirmed that evidence on 24 October 2026; local replay remains separate evidence (see P02.4 in the roadmap).
