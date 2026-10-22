# Security Policy

The repository currently contains design documentation, not a runnable application. No AI RPG Engine runtime release is supported yet.

The intended security model is documented in [Security and Operations](docs/14-security-and-operations.md) and [Codex Integration](docs/06-codex-integration.md). Future releases must verify their effective process/tool isolation and state-validation boundaries on each supported platform.

## Sensitive reports

Do not put credentials, private campaign content, raw provider traces, or exploit details containing personal data in a public issue. Before public publication, the owner must enable an appropriate private reporting route, such as GitHub private vulnerability reporting, and identify supported release versions. No unverified email address or private contact information is supplied here.

When a private reporting route is configured, include affected versions, conditions, impact, redacted reproduction steps, and whether canonical data or host capabilities were exposed.

## Release expectations

- Gameplay AI has no direct canonical write path or broad host automation privileges.
- Imported packs and model outputs are validated as untrusted data.
- Player projections exclude unrevealed secrets and privileged planner context.
- Local API mutation requests require owner/session and origin protection.
- Canonical state, operation receipts, and reward settlements survive retries and failures.
- Backups are independently restorable and verified.
- Credentials and private data are never public repository fixtures.

Public deployment, multiplayer accounts, and arbitrary executable plugins require separate reviewed designs and are outside the initial release.
