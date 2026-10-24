# ADR 0004: Declarative, Immutable Adventure Packs

**Status:** Declarative boundary accepted; parsing, semantic compilation and protected immutable registration implemented in P04.1–P04.4. Genre fixtures, locale overlays and gameplay remain pending. Adventure independence is an owner requirement. [ADR 0010](0010-name-scope-format-licensing.md) fixes the concrete file syntax of pack contract 1; [ADR 0018](0018-protected-pack-registration.md) records admission/storage boundaries. **Date:** 22 October 2026 (implementation status updated 24 October 2026).

## Context

The new product must be an engine, not a specific game's rewritten story. Users need a way to describe adventures using files, while mechanics and security remain enforceable.

## Decision

Use versioned manifests, Markdown prose, structured YAML/JSON definitions, bounded expressions, and registered local assets. Compile and validate packs into immutable revisions. Pin each campaign to pack/ruleset hashes. Use explicit generation policies for authored, emergent, and hybrid play. Optional guide and creative-ability modules contain no mandatory source-game names or lore.

File-only customization covers supported content and policy data. New effect types, schedulers, or spatial algorithms require reviewed engine extensions. Arbitrary JavaScript, Python, Java, SQL, or host-command execution is not a pack capability.

## Alternatives

A monolithic world prompt offers low authoring friction but cannot reliably enforce mechanics or references. A visual editor before the file contract stabilizes risks a second content model. Executable plugins expand trust and distribution scope unnecessarily.

## Consequences

Authors need clear diagnostics and examples. Content migration becomes an explicit workflow. Existing campaigns do not hot-reload edits. Two different neutral packs are a mandatory reuse test.

## Verification

Safe parser/archive corpus, semantic reference checks, immutable hashes, migration tests, and genre-independent execution. See [adventure packs](../09-adventure-packs.md).

## P04.1 implementation decisions (24 October 2026)

Use the existing Boot-managed SnakeYAML dependency for events only, with explicit
YAML 1.2 core scalar resolution. No YAML object constructor or default YAML 1.1
resolver processes pack bytes. Jackson streaming tokens enforce the same immutable
JSON-shaped value model. Fixed budgets and source coordinates belong to each call.
This avoids adding a second YAML implementation or a general schema dependency.

Publish a closed manifest and a preliminary definition envelope as draft-2020-12
schemas. A small internal evaluator implements only their assertion vocabulary,
rejects unsupported keywords, loads fixed classpath resources and resolves only
local references; user-supplied schemas and remote resolution are unavailable.
P04.2–P04.3 must add category-specific data constraints before semantic admission.
This deliberately stages ADR 0010's definition-schema delivery rather than
claiming that an envelope validates playable mechanics.

Hash the complete explicit inventory using the versioned raw framing in the pack
specification. Canonical parsed data is for validation, never hash reserialization:
comments, line endings and asset changes must change revision identity. The stage
operates only on caller-staged bytes and produces `ParsedPack`; no API accepts it
as an admitted revision, and no archive/host path is opened.
