# Repository checks

Credential-free checks used by the ordinary CI workflow
([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)) and runnable locally
before a push. They use only the Python standard library and local Git, need no
network, account, environment variable or secret, and print only a file, line and
rule name for each finding, never the matching text.

```sh
py -B tools/repository_checks/checks.py all        # Windows launcher; use python3 -B elsewhere
py -B tools/repository_checks/checks.py secrets    # or: docs | workflows | images
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
| `images` | Every external Dockerfile `FROM`, `# syntax=` line and Compose `image:` carries an exact tag and an index digest ([ADR 0009](../../docs/adr/0009-toolchain-baseline.md)). Earlier build stages and locally built Compose images are exempt. |

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
- The workflow has not been executed on GitHub from the development environment; the
  same commands were run locally (see the roadmap entry for P02.4).
