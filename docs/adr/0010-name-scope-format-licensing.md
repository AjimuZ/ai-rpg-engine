# ADR 0010: Product Name, v1 Boundaries, Initial Pack File Format, and Licensing Plan

**Status:** Accepted (P00.5). The name is an owner decision; the v1 boundaries confirm the requirements baseline; the file format and licensing plan are design decisions. Nothing here implies implemented code. **Date:** 23 October 2026.

## Context

P00 must leave no unowned pre-scaffold decision. Before P02 scaffolds the workspace and P04 writes the pack parser, four points need one authoritative record: the product name and the identifiers derived from it, the v1 scope line, the concrete syntax of adventure-pack files, and how the project, sample content, third-party packs, and dependencies are licensed. Earlier documents stated working defaults ([risks and open decisions](../17-risks-and-open-decisions.md#2-open-decisions-with-safe-working-defaults), [ADR 0004](0004-declarative-adventures.md), [publication](../18-github-publication.md#4-license-and-content-rights)), but left choices open, such as "YAML/JSON", "front matter, if supported", and "bound aliases".

## Decision: name and identifiers

The owner selected **AI RPG Engine**. The repository is [AjimuZ/ai-rpg-engine](https://github.com/AjimuZ/ai-rpg-engine), with private visibility. Prose may shorten the name to "the engine". Derived identifiers:

| Use | Identifier |
| --- | --- |
| Repository slug, Compose project name, npm package and Angular project name | `ai-rpg-engine` |
| Maven `groupId` / backend `artifactId` | `io.github.ajimuz` / `ai-rpg-engine-backend` |
| Java base package | `io.github.ajimuz.rpgengine` |
| Default ruleset | AI RPG Skirmish, ruleset ID `skirmish` ([ADR 0006](0006-original-combat.md)) |

The `io.github.ajimuz` namespace follows the repository owner's GitHub account, so it needs no domain registration. The name has had no trademark search or clearance. That review stays an owner publication task in P19.1, and so does deciding whether any public release keeps the name. The engine claims no affiliation with any provider, tabletop publisher, or ruleset.

## Decision: v1 boundaries

The boundaries in [requirements and scope](../03-requirements-and-scope.md) are confirmed without change:

- **MVP:** sections 1 and 4. A local, single-player engine with pack import, campaign creation, narration, typed state changes, layered memory, thread renewal, inventory, skills, one simple quest flow, and a complete bounded combat loop with rewards and resume.
- **v1.0:** sections 1 and 5. The MVP plus authoring validation, multiple isolated campaigns, the supported modules, dynamic encounters, configurable progression, export/restore, knowledge-safe projections, accessibility verification, operational documentation, and release gates. It has one combat scheduler, one zone-based spatial model, declarative content, bounded expressions, a small effect vocabulary, and two neutral sample packs.
- **Outside v1:** multiplayer, public hosting, executable plugins or pack scripts, a visual editor, semantic retrieval, voice, live image generation, compatibility with an external tabletop ruleset, and any provider other than the packaged narration runtime. Two more items are also outside v1 unless a later decision adds them: support claims for hosts beyond those verified under [ADR 0009](0009-toolchain-baseline.md), and publication of prebuilt container images.

A change to these lines follows the [scope-change procedure](../17-risks-and-open-decisions.md#3-scope-change-procedure).

## Decision: initial pack file format (pack contract 1)

This refines ADR 0004 and the [pack specification](../09-adventure-packs.md); both remain authoritative for everything else.

- **Contract version:** the manifest's `schemaVersion` is the integer `1`. The compiler rejects any other value with a diagnostic. A breaking change to the file contract increments it and needs an explicit migration path.
- **Encoding:** every text file is UTF-8 without a byte-order mark. LF and CRLF line endings are both accepted. The content digest covers the raw bytes, so line endings are not normalized.
- **Structured files:** YAML 1.2 with the core schema, extension `.yaml`. Each file holds one document. Anchors, aliases, merge keys, explicit or custom tags, and duplicate keys are blocking errors, and mapping keys must be strings. Scalars that YAML 1.1 parsers read differently (such as `yes`, `no`, `on`, `off`, `0777`, and `1:30`) must parse with YAML 1.2 core semantics, or the parser must reject them. A parser that silently applies YAML 1.1 typing does not conform. Banning aliases outright meets the alias bound in the pack and security specifications more simply than counting expansions.
- **JSON equivalent:** any structured file, including the manifest, may instead be RFC 8259 JSON with extension `.json`. It is validated against the same schema, and duplicate keys are again errors. A pack root holds exactly one manifest, either `adventure.yaml` or `adventure.json`. `.yml` and other extensions are not recognized.
- **Prose files:** CommonMark Markdown, extension `.md`. Raw HTML is shown as text and never rendered. Front matter is not part of contract 1: a leading `---` metadata block is a blocking error, and metadata belongs in structured files. Remote images are never fetched, and images may refer only to registered pack assets.
- **Machine-readable schemas:** P04.1 publishes the manifest and definition schemas as JSON Schema draft 2020-12. YAML and JSON files are validated against the same schemas.
- **Distribution:** a pack is a directory or a `.zip` archive, with stored or deflate entries and no encryption. The manifest sits at the archive root or inside one top-level directory. Paths are relative and use `/`. The containment rules and limits in [the pack specification](../09-adventure-packs.md#9-import-and-compilation-pipeline) apply.

The campaign export and backup archive format is a different contract and belongs to P16.

## Decision: licensing plan

- **Engine software and documentation:** the [MIT License](../../LICENSE) (owner decision, 22 October 2026). The upstream notice stays in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). Contributions come in under MIT ([CONTRIBUTING.md](../../CONTRIBUTING.md)). There is no separate contributor agreement.
- **Neutral sample packs and fixtures** (P04.5, P14, and the v1 samples): written for this project and licensed MIT. Each one ships `LICENSE.md` and `CREDITS.md`, declares the SPDX identifier `MIT` in its manifest `license` field, and contains no third-party prose, art, or stat blocks.
- **Third-party packs:** authors keep their rights. Running a pack does not place it under MIT. A distributable pack declares an SPDX identifier or a license-file reference, and its assets carry the provenance metadata required in [publication](../18-github-publication.md#4-license-and-content-rights).
- **Dependencies:** libraries bundled into the frontend build or the backend jar use permissive licenses: MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, ISC, 0BSD, CC0-1.0, or Unicode. MPL-2.0, EPL-2.0, LGPL, and dual-licensed components need a recorded review before adoption. GPL, AGPL, SSPL, and non-commercial or source-available licenses are excluded from bundled artifacts. P02.4 adds the dependency license inventory gate, and P19.1 reviews it.
- **Container images:** `start.sh` builds the images locally from upstream base images, so the project does not redistribute them. The operating-system packages inside base images keep their own licenses. Publishing prebuilt images would need a new review covering those packages and any source obligations.
- **Narration runtime:** the client's license and the account terms are upstream obligations. P01.1 records them with the runtime image, and the user accepts the account terms personally.

## Alternatives

- **YAML 1.1 parsing, or allowing aliases with expansion counting:** both are common in Java parsers. They bring ambiguous scalar typing and alias-amplification risk in exchange for little authoring benefit.
- **TOML or JSON only:** TOML handles deep definitions poorly, and JSON alone is unfriendly to authors. Keeping JSON as an equivalent costs little because it shares the same schema.
- **Front matter in Markdown:** it creates a second place for metadata and could hide instructions in prose files. It can be added in a later contract version if authors need it.
- **A copyleft or content license for the samples (such as CC BY-SA):** this would split the repository's licensing for no benefit. MIT keeps the samples reusable as starter material.

## Consequences

- P02.2 uses the identifiers above.
- P04.1 needs a YAML 1.2-conforming parser configuration, or a strict wrapper, and tests for every rejected construct.
- Doc 09 and doc 14 now point to this record instead of open alternatives.
- The "content authoring syntax" open decision is closed. The name's trademark review remains open for P19.1.

## Verification

- **P04.1 and P04.4:** parser corpus tests for BOMs, aliases, merge keys, tags, duplicate keys, YAML 1.1 scalars, front matter, unrecognized extensions, two manifests in one pack, and ZIP limits.
- **P04.5:** two packs, one using YAML and one including JSON files, import without source edits.
- **P19.1** (moved from P02.4, which delivered credential-free CI without an inventory): the dependency license inventory passes the allowlist, and every bundled asset has a provenance record.
