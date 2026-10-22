# ADR 0001: Modular Angular, Java, and PostgreSQL Application

**Status:** Proposed. **Date:** 22 October 2026.

**Deployment update:** [ADR 0008](0008-containerized-startup.md) supersedes the database-only Compose assumption below with a complete packaged Compose installation. The stack and modular backend decision remain in force.

## Context

The owner explicitly requests that the new engine primarily follow The World I Can Develop's technology stack. The source has an Angular scaffold and a planned Java/Spring Boot/PostgreSQL backend; the demo's Python application is functional reference material with campaign-specific coupling.

## Decision

Retain Angular/TypeScript, Spring Boot/Java 21, Maven, and PostgreSQL. Build one modular backend with logical boundaries for content, campaigns, rules, combat, memory, knowledge, and Codex. Use Docker Compose initially for the database. Do not introduce a Python/Node AI service, broker, or distributed architecture without demonstrated need.

## Alternatives

Extending the Python demo would reduce early implementation work but contradict the requested primary stack and preserve its parsing/write limitations. Starting with microservices would add operations before a playable core exists.

## Consequences

The Codex transport needs a Java implementation or a later explicitly justified bridge. Canonical operations can use local database transactions. The backend remains explainable and independently testable. Exact supported dependency versions must be resolved at P00 rather than assumed from stale source prose.

## Verification

Fresh-clone shell startup, module dependency checks, real PostgreSQL transactions, and a reproducible version matrix. See P00–P03.
