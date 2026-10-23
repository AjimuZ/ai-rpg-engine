# ADR 0004: Declarative, Immutable Adventure Packs

**Status:** Proposed format; adventure independence is an owner requirement. [ADR 0010](0010-name-scope-format-licensing.md) fixes the concrete file syntax of pack contract 1. **Date:** 22 October 2026.

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
