# ADR 0002: Codex-Only Initial AI Runtime

**Status:** Proposed integration design; Codex-only scope is an owner requirement. **Date:** 22 October 2026.

**Deployment update:** [ADR 0008](0008-containerized-startup.md) supersedes the mandatory child-process/stdio deployment assumption below for the packaged installation. Codex remains the sole initial integration; stdio remains a local compatibility-probe profile.

**Scope update (25 October 2026):** [ADR 0020](0020-selectable-narration-runtime.md) adds Claude as a selectable alternative narration runtime, with Codex remaining the default. The exclusion of generic providers, local models and automatic provider switching below is unchanged.

## Context

The original project planned local models through Ollama. The owner supersedes that direction and requests the demo's Codex approach for the new engine.

## Decision

Use a backend-managed Codex app-server process through private stdio, behind a task-oriented internal port. Discover supported account/model capabilities and set task configuration explicitly. Keep generic providers, local LLMs, and desktop UI automation outside v1. Use deterministic test doubles without presenting them as production AI options.

## Alternatives

A TypeScript Codex SDK bridge would introduce another server runtime. Noninteractive Codex execution may suit bounded maintenance jobs. A generic chat API would miss the explicitly requested integration and is not an authorized fallback.

## Consequences

The application needs service connectivity and supported account access despite local storage. Protocol maturity, tool restriction, authentication, and platform support are release risks. Failures preserve state and may leave narration pending; they do not cause silent provider switching.

## Verification

P01 compatibility/isolation evidence, adapter fixtures, model/effort routing, interruption, early events, shutdown, and one neutral opt-in real smoke. See the [integration specification](../06-codex-integration.md).
