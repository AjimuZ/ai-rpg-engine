# ADR 0046: Automatic first-start provisioning of the owner and a sample adventure

- Status: Accepted (owner decision, 20 November 2026); implemented in P14.1-A.1 (20 November 2026).
- Date: 20 November 2026
- Changes: the consequence of [ADR 0019](0019-local-campaign-admission.md) that "the packaged launcher does not automatically provision an owner or enable this API". The admission rules of ADR 0019 are unchanged.

## Context

A packaged installation started with `./start.sh` configured no owner, so every saved read under `/api/v1/adventures`, `/api/v1/campaigns`, `/api/v1/session`, `/api/v1/models` and `/api/v1/settings` answered `503 LOCAL_OWNER_UNCONFIGURED`. ADR 0019 left provisioning out of scope, and no document chose a method, so P14.1-A was blocked. The owner chose automatic provisioning at first start, with no test-only seeding route in the product.

Facts the design relies on:

- `LocalHttpAdmission` reads `engine.http.owner-id` and `engine.http.origin` and fails startup on an invalid value. Spring resolves both from `ENGINE_HTTP_OWNER_ID` and `ENGINE_HTTP_ORIGIN`.
- `PackRegistry.register` seals a staged pack in one transaction, and repeating identical bytes returns the existing revision.
- The containers check starts the Compose project with `APP_PORT=0` (an ephemeral host port), and it runs the whole backend suite with `SPRING_PROFILES_ACTIVE=packaged`. Several of those tests configure their own owner IDs and seed their own rows.

## Decision

1. **The launcher owns the owner's identity.** At its first start, `start.sh` writes one random version-4 UUID to the ignored `.local/owner-id` (private file, never rewritten). Every start exports it as `ENGINE_HTTP_OWNER_ID`, with `ENGINE_HTTP_ORIGIN=http://127.0.0.1:<APP_PORT>`. A malformed file stops the launcher instead of being replaced. `--check` reports whether the file exists.
2. **Compose forwards, the launcher derives.** `compose.yaml` forwards `ENGINE_HTTP_OWNER_ID` (empty by default) and `ENGINE_HTTP_ORIGIN` (default `http://127.0.0.1:8080`), and sets `ENGINE_PROVISIONING_ENABLED=true`. The origin is computed by the launcher, not by Compose interpolation of `APP_PORT`. Port 0 is a valid Compose value but not a valid admitted origin, and the engine would refuse to start under the containers check.
3. **The engine provisions at first start, idempotently.** `FirstStartProvisioning` runs after persistence initialization and before the web server, only when `engine.provisioning.enabled` is true and an owner is configured. In one transaction, serialized by an advisory lock:
   - it inserts the owner when missing;
   - it inserts the sample workspace when that owner has no workspace at all. The workspace ID is derived from the owner ID, so every start finds the same row without a marker column.
   After the commit, and only while the sample workspace holds no revision, it seals the sample pack bundled in the image through the normal staging and registration path.
4. **The bundled sample.** `sample:lantern-ford` 1.0.0 ("Lantern Ford", English) ships in the engine jar under `sample-adventure/`. It is neutral original content under MIT. Its `ford-ambush` encounter sits at the entry location `ford-camp`, so a new campaign can enter it at once.
5. **Conservative choices for the details left open:**
   - An owner who already has any workspace never receives the sample.
   - A sample workspace that already has a revision never receives another one, even when a later image ships a newer sample.
   - A database holding another owner but not the configured one stops startup with an actionable message instead of creating a second owner. This happens, for example, when `.local/owner-id` was lost.
   - Storage failures, and a bundled pack that does not seal on this build, also stop startup.
   - The owner's display name is `Owner` and the UI locale is English.
   - Since P15.5-A the sample workspace's title and genre label come from the bundled manifest: its `title`, and its first `contentGuidance.themes` entry as genre (`adventure` when none fits). The engine code therefore names no adventure. Before that change they were written as the constants "Lantern Ford" and "fantasy".
   - Provisioning is off by default (`engine.provisioning.enabled=false`) and is not enabled by the packaged Spring profile. Tests that use the packaged profile and their own owners are therefore unaffected. It is enabled only through Compose.

## Alternatives considered

- A setup route or command that creates the owner and sample on request: rejected by the owner. It would also have to be protected against browsers before any owner exists.
- Letting the engine pick the owner when none is configured: rejected, because the owner identity must survive a database restore and stay with the installation's private state, like the database password.
- Deriving the origin in Compose from `APP_PORT`: rejected because of port 0 under the containers check (decision 2).
- Enabling provisioning in `application-packaged.properties`: rejected, because the containers check runs the whole suite under that profile and tests with their own owner IDs would then fail.

## Consequences

- New: `adventure/FirstStartProvisioning`, the bundled pack under `backend/src/main/resources/sample-adventure/`, the launcher's `ensure_owner`/`load_owner`, and three engine variables in `compose.yaml`. No migration, route, pack schema or page changed.
- Starting with `docker compose up` alone, without the launcher, still has no owner and still answers 503, as before.
- Losing `.local/owner-id` with an existing database makes the next start create a new UUID, and the engine then refuses to start until the original file is restored. The launcher cannot tell an upgrade from a lost file without reading the database.
- Update (21 November 2026, P14.1-D): the sample's `ford-ambush` now also declares a negotiated ending, see [ADR 0048](0048-the-bundled-sample-offers-one-unchecked-free-truce.md); an installation that already sealed the sample keeps its revision, as decision 5 says.
- Not covered: the sample is English only (no Italian localization); one genre; no live packaged start was run to show the 503 is gone in a browser.

## Verification

`FirstStartProvisioningTest` (5), the launcher function tests for the owner file, and `test_compose.py` for the forwarded variables. See the [roadmap entry](../16-development-roadmap.md#18-p14--playable-mvp-validation) for the recorded results and what was not proven.
