# Architecture Decision Records

Records 0001–0008 are **proposed for the AI RPG Engine implementation baseline**. Record 0009 is accepted for its completed sections only. Owner-supplied constraints are identified separately from design choices. No “accepted” status implies implemented code or completed validation.

| Record | Decision |
| --- | --- |
| [0001](0001-modular-stack.md) | Retain the Angular/Java/PostgreSQL stack and a modular backend |
| [0002](0002-codex-only.md) | Use Codex app-server as the sole initial AI integration |
| [0003](0003-canonical-transactions.md) | Backend-owned canonical transactions and durable operation receipts |
| [0004](0004-declarative-adventures.md) | Declarative immutable adventure packs and bounded extension points |
| [0005](0005-renewable-memory.md) | Canonical memory independent of renewable AI sessions |
| [0006](0006-original-combat.md) | Original zone-based deterministic combat with constrained AI proposals |
| [0007](0007-audience-isolation.md) | Knowledge filtering and role/audience thread isolation |
| [0008](0008-containerized-startup.md) | Containerized packaged installation and root `start.sh`; supersedes the deployment assumptions in 0001 and 0002 |
| [0009](0009-toolchain-baseline.md) | Pinned supported toolchain versions; frontend, backend, build, and database sections accepted; OS, container image, Compose, and shell sections pending |

When a decision changes, preserve this record and create a superseding one with rationale, compatibility implications, and verification. Do not retroactively edit historical status to make a previous decision disappear.
