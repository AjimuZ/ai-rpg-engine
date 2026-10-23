# Browser development shell

This is a neutral standalone Angular shell, with SCSS, strict TypeScript/templates,
zoneless change detection, and no adventure data, runtime calls, or credentials.
Use Node.js 24.22.1 and npm 11.19.1 from [ADR 0009](../docs/adr/0009-toolchain-baseline.md).

From `frontend/`:

```sh
npm ci
npm run typecheck
npm test
npm run build
npm start
```

The development server binds to `127.0.0.1:4200`. The default test command runs once
using Angular's Vitest builder and jsdom; it needs no browser or account.
The production output is `dist/engine-ui/browser/`. Backend asset packaging is P02.3.
No lint target exists yet; P02.4 owns lint/CI policy.

Vite is overridden to 8.3.5 to fix the development-server advisories in Angular
build's 8.3.0 dependency while preserving Angular 22.2.3; see ADR 0009.

All direct dependencies are exact pins and the npm lockfile is committed. Install
scripts are enabled for the reviewed build dependencies (including esbuild's native
binary validation); use `npm ci` only with the reviewed lockfile. npm 11.19.1 reports uncovered scripts in `@parcel/watcher`, `esbuild`, `lmdb`,
and `msgpackr-extract`; this workspace retains the default execution policy and
does not add a broad `allowScripts` grant. No blanket
`--ignore-scripts` policy is claimed. Review lockfile and lifecycle-script changes
when upgrading; optional platform binaries must remain in the lockfile for Windows
and Linux builds. No test-library helper images or browser downloads are used.
