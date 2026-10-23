# ADR 0013 — Narration runtime isolation profile

- Status: Accepted; implemented and verified for the pinned runtime 0.164.0 (P01.3)
- Date: 23 October 2026

## Context

The narration runtime is a general coding agent. Out of the box it offers the model host commands, patching, image reads, extensions, search, delegation and project instructions, and it loads configuration from its home directory, the project and launch flags. The engine needs text in and structured text out. Bubblewrap is not present in the image, so the runtime's own command sandbox is unavailable: `command/exec` fails closed instead of running unsandboxed. P01.3 must show restricted roots, denied canonical writes, unavailable host tools and isolation from inherited configuration.

## Decision

The container is the isolation boundary, and the image carries a fixed engine profile that removes tools instead of refusing them later.

- **Container:** read-only root filesystem, all capabilities dropped, `no-new-privileges`, UID 10001, private tmpfs or volumes for home, workspace and IPC, and no route to PostgreSQL. "Restricted roots" means the runtime can see only these paths and has no tool that reaches them.
- **Managed layer** (`/etc/codex/managed_config.toml`, root-owned, 0444): it overrides user config, project config, `--enable` and `-c`. It names an engine model catalog and an engine instruction file, empties developer instructions, disables search and project instruction files, removes environment, collaboration and app prompt text, and turns off every host-capability feature.
- **Engine model catalog:** the same models and efforts as the pinned bundled catalog, but with direct tool mode, shell type `disabled`, no patch tool, no experimental tools, text-only input and neutral base instructions. Without it the bundled catalog forces code mode, which offers commands and patching whatever the features say.
- **Requirements** (`/etc/codex/requirements.toml`): `mcp_servers = {}` removes inherited extension servers, which managed tables cannot do because tables merge. Approval is pinned to `never`, sandbox to `read-only` and search to `disabled`. A `danger-full-access` thread start is rejected. Other wider `thread/start` requests (`workspace-write`, with or without network, and `on-request`/`untrusted` approvals) are accepted but clamped to read-only/never. The engine checks the effective policy in every response.
- **Startup guard** (`runtime-entrypoint`, the image ENTRYPOINT): the runtime always loads `CODEX_HOME/AGENTS.md` and `AGENTS.override.md`, and no key disables them. The guard therefore refuses to start, with exit 78, if either file or a home `config.toml` exists. It checks only that the files exist and never reads them.
- **Engine side:** `await_turn` accepts only user message, agent message, reasoning and compaction items. Any command, file change, tool call, search or unknown item fails the turn with `unexpected-turn-item`.

## Evidence

All evidence is from `tools/runtime_probe/test_isolation.py` against the rebuilt pinned image, on synthetic fixtures with no account:

- **Tool surface:** every upstream request has `tools: []`.
- **Instructions:** the only developer texts are the engine instructions and the runtime's generated read-only permissions statement. No `<environment_context>` block appears.
- **Host tool calls:** a scripted model calls shell, exec, both patch forms (writing a canonical sentinel), an extension resource and an image read. Each call is answered "unsupported", and the sentinel file never appears.
- **Inherited configuration:** a synthetic home config, project config, project `AGENTS.md` and hostile launch flags are mounted with the guard bypassed. The neutral turn still completes, and no sentinel text reaches the request.
- **Positive controls:** each denial is paired with a control, either a completed neutral turn or a normal `--version` start.

## Consequences

- The managed layer silently ignores unknown keys. Any runtime upgrade must re-run `test_isolation.py`, and must refresh the catalog from the new bundled catalog.
- The runtime still adds its own permissions developer message. The tests accept exactly that message.
- The runtime logs a warning that the untrusted workspace's `.codex/config.toml` is ignored. This is expected.
- An account volume that contains `config.toml` or home instruction files stops the runtime until the owner removes those files. This is intended: account volumes hold sign-in state only.
- Network egress for inference is still needed in production. The no-network property applies to the model's tools, not to the runtime process.
