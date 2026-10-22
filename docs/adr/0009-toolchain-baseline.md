# ADR 0009: Supported Toolchain Baseline

**Status:** Accepted for the frontend toolchain (P00.1). Backend, database, and container sections are pending P00.2 and P00.3. **Date:** 22 October 2026.

## Context

[ADR 0001](0001-modular-stack.md) retains Angular, TypeScript, Spring Boot, Java 21, Maven, and PostgreSQL, and leaves exact versions to P00. The primary source project records Angular 22 and TypeScript 6. Resolving packages through `latest` tags is neither reproducible nor safe: on 22 October 2026 the npm `latest` TypeScript release is 7.0.2, which Angular 22 does not support.

## Decision: frontend toolchain (P00.1)

| Component | Pinned version | Allowed updates | Evidence on 22 October 2026 |
| --- | --- | --- | --- |
| Angular framework, CLI, and build (`@angular/*`) | 22.2.3 | Reviewed patches within 22.2; later 22.x minors after a compatibility review | npm `latest` is 22.2.3, published 14 October 2026. Angular 22 has been active since 3 June 2026, with active support to 2027-06 and LTS to 2028-06 |
| TypeScript | 6.0.3 | 6.0.x patches only | `@angular/compiler-cli` and `@angular/build` 22.2.3 require `>=6.0 <6.1`; TypeScript 7 is incompatible |
| Node.js | 24.22.1 (LTS "Krypton") | 24.x patches; moving to Node.js 26 needs a matrix update with build and test evidence | Angular 22.2.3 engines: `^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0`. Node.js 24 entered maintenance LTS on 20 October 2026; end of life 30 April 2028 |
| npm | 11.19.1, bundled with Node.js 24.22.1 | The version bundled with the pinned Node.js release | Angular CLI engines: npm `>=8.0.0` |
| RxJS | 7.8.2 | 7.x patches | `@angular/core` peer: `^6.5.3 \|\| ^7.4.0`; RxJS 9 is still a beta |
| tslib | 2.8.1 | 2.x | `@angular/core` dependency: `^2.3.0` |
| Vitest | 5.0.5 | 5.x within the Angular build peer range | `@angular/build` peer: `^4.0.8 \|\| ^5.0.0`; Vitest engines include Node.js 24 |
| zone.js | Not used | — | Zoneless change detection follows the architecture baseline; zone.js is only an optional peer |

Rules for applying the baseline:

- Commit `package-lock.json` and install with `npm ci` in CI and container builds. Never resolve a `latest` tag or an open range during an image build.
- Record the toolchain in `package.json` (`engines` and `packageManager`) when the workspace is scaffolded in P02.2.
- Packaged builds use an official Node.js 24.22.1 image pinned by digest (P00.3), so hosts need no Node.js installation.
- Select the browser acceptance runner (Playwright or equivalent) when browser tests are introduced in P05, under the same pinning rules.

Node.js 26 becomes LTS on 28 October 2026, with end of life on 30 April 2029. It is not selected today because it is not yet LTS and has not been verified with this workspace. Reconsider it before the v1 release candidate (P19) or as Node.js 24 approaches end of life.

## Verification

- Queried npm registry metadata for `@angular/core`, `@angular/compiler-cli`, `@angular/cli`, and `@angular/build` 22.2.3, plus `typescript`, `rxjs`, `tslib`, and `vitest` 5.0.5.
- Checked Node.js release data from `nodejs.org/dist/index.json` and the Node.js Release schedule, and the Angular support window from the Angular releases page.
- Ran `npm install --package-lock-only --ignore-scripts` for the pinned set in a temporary directory outside the repository with Node.js 24.22.1 and npm 11.19.1: resolution succeeded without conflicts.
- Ran the same resolution with TypeScript 7.0.2: it failed with `ERESOLVE` on peer `typescript@">=6.0 <6.1"` from `@angular/build@22.2.3`.

These are compatibility checks, not a build of the engine; no frontend workspace exists yet.

## Tool availability inventory

Owner workstation, Windows 11, 22 October 2026:

| Tool | Version | Relevance |
| --- | --- | --- |
| Node.js / npm | 24.22.1 / 11.19.1 | Matches the pinned frontend toolchain for host development |
| Java (Oracle JDK) | 27.0.1 | Not the Java 21 LTS target; resolved by P00.2 |
| Maven | 3.10.0 | Backend build decision in P00.2, including the Maven Wrapper |
| Docker Engine / Compose | 29.9.2 / v5.6.1 (Docker Desktop with WSL2) | Container baseline in P00.3 |
| Git | 2.56.0 | Repository workflow |
| Python | 3.15.1 | Local repository checks only; not an engine dependency |

Host versions describe one development machine. The packaged profile must not depend on them.

## Consequences

Frontend scaffolding in P02.2 has an unambiguous, reproducible baseline. TypeScript upgrades follow Angular's supported range rather than the npm `latest` tag. Remaining version ambiguity is limited to the backend, database, and container toolchain, which P00.2 and P00.3 add to this record.

## References

- [Angular releases and support policy](https://angular.dev/reference/releases)
- [Angular version compatibility](https://angular.dev/reference/versions)
- [Node.js release schedule](https://github.com/nodejs/Release)
- [Node.js release index](https://nodejs.org/dist/index.json)
