# Container build-context checks

Run from the repository root with Python and the Linux Docker/BuildKit baseline
in [ADR 0009](../../docs/adr/0009-toolchain-baseline.md):

```powershell
py -B -m unittest discover -s tools/container_packaging -v
```

The two tests copy only the ignore policies into isolated neutral fixture trees
under `.local/scratch/`, then use a pinned Dockerfile frontend and a scratch-stage
`COPY` to export the actual context. Exact exported-file comparisons verify both
required build inputs and exclusion of root/nested private paths. They never send
the checkout to Docker, inspect account files, start services, or create volumes.
Missing Docker/BuildKit or build failures fail the suite rather than skip checks.
Fixture directories are removed automatically; BuildKit cache may remain.

The root policy admits only frontend/backend build inputs and the future engine
Dockerfile. The independent runtime policy admits only its Dockerfile, since that
image downloads its checksum-verified executable. New build inputs must update
both policy and fixtures; root policy changes do not affect independent contexts.
These tests cover path filtering, not secret detection inside otherwise admitted
source files, nor packaged service isolation, runtime readiness or persistence.
CI wiring belongs to P02.4. The engine image and Compose profile remain pending
P02.3 work, subject to the mandatory official Java image refresh in ADR 0009.
