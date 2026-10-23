# Architecture Decision Records

Records 0001–0005, 0007, and 0008 are **proposed for the AI RPG Engine implementation baseline**. Record 0006 is accepted as the implementable default ruleset, with provisional numerical values. Record 0009 is accepted for its completed sections only. Record 0010 is accepted: it records the product name, v1 boundaries, initial pack file format, and licensing plan. Owner-supplied constraints are identified separately from design choices. No “accepted” status implies implemented code or completed validation.

| Record | Decision |
| --- | --- |
| [0001](0001-modular-stack.md) | Retain the Angular/Java/PostgreSQL stack and a modular backend |
| [0002](0002-codex-only.md) | Use Codex app-server as the sole initial AI integration |
| [0003](0003-canonical-transactions.md) | Backend-owned canonical transactions and durable operation receipts |
| [0004](0004-declarative-adventures.md) | Declarative immutable adventure packs and bounded extension points |
| [0005](0005-renewable-memory.md) | Canonical memory independent of renewable AI sessions |
| [0006](0006-original-combat.md) | Original zone-based deterministic combat with constrained AI proposals; accepted as the implementable default, numbers provisional |
| [0007](0007-audience-isolation.md) | Knowledge filtering and role/audience thread isolation |
| [0008](0008-containerized-startup.md) | Containerized packaged installation and root `start.sh`; supersedes the deployment assumptions in 0001 and 0002 |
| [0009](0009-toolchain-baseline.md) | Pinned supported toolchain versions; frontend, backend, build, database, host platform, container image, Compose, and shell sections accepted; the narration runtime image belongs to P01.1 |
| [0010](0010-name-scope-format-licensing.md) | Product name AI RPG Engine and derived identifiers, initial v1 boundaries, pack contract 1 file format and licensing plan; accepted; later ADR 0011/0015/0016 explicitly extend presentation, bilingual and optional-speech scope |
| [0011](0011-visual-novel-and-persistent-artwork.md) | Default visual-novel play, editable quick replies, complete graphic kit, persistent character/item artwork and bounded runtime image generation; accepted design, implementation pending |
| [0012](0012-adventure-workspace-isolation.md) | Independent adventure specifications, campaigns and media directories with explicit identity/isolation; accepted design, implementation pending |
| [0013](0013-narration-runtime-isolation.md) | Narration runtime isolation: container boundary, managed profile, engine catalog, requirements and startup guard with no model tools; accepted, verified for runtime 0.164.0 |
| [0014](0014-bounded-asynchronous-planning.md) | Shared-runtime durable priority jobs, private versioned plans and dependency-sensitive admission; accepted design, implementation and benefit unverified |
| [0015](0015-bilingual-first-release.md) | English-default and first-class Italian from MVP, small locale model, compiled UI variants and direct selected-language narration; supersedes English-only assumption |
| [0016](0016-optional-local-speech.md) | Optional local bilingual speech, conditionally selected primary/fallback, rights/voice/GPU/cache boundaries; no model installed or performance verified |

When a decision changes, preserve this record and create a superseding one with rationale, compatibility implications, and verification. Do not retroactively edit historical status to make a previous decision disappear.
