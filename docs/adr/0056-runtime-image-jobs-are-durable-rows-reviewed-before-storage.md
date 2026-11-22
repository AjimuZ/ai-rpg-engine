# ADR 0056: Runtime image jobs are durable rows, reviewed before storage, per pack category policy

- Status: Accepted (22 November 2026), implemented in P17.6-D
- Context: P17.6, docs/23 sections 3, 4 and 6, docs/25 section 2, QA-31, QA-33

## Context

P17.6-A kept image jobs and budget charges in memory, so a restart forgot queued work, reset the budget window and lost
the pending and failed states the playthrough screen shows (P17.6-C). docs/23 also asks for a per-category pack policy
(`disabled`, `catalogOnly`, `bounded`), an acceptance review and recorded provenance, and docs/25 plans a per-campaign
`assets/generated/` folder. Several details were open; the supervisor notes ask for conservative choices recorded here.

## Decision

1. **One row per job (V38 `runtime_image_job`).** A job is inserted `QUEUED` with its charge before it can reach the
   port, and claimed (`QUEUED` to `RUNNING` with the instance's random runner identity) before it is sent. Only the
   claiming instance can end a `RUNNING` row. A partial unique index allows one open job per campaign, category,
   subject and appearance, so equal requests coalesce across a restart.
2. **Charges are the rows' `charged_at`.** The budget counts rows charged inside the window, so the window survives a
   restart. A charge is given back (set to null) only when the job ends unsent.
3. **Restart recovery runs once the application is ready.** Open rows of a campaign whose recovery generation changed, or
   that no longer exists, end `STALE_GENERATION`. A row the previous process had sent ends `FAILED`
   `image-job-interrupted` and keeps its charge: the backend may have done the work, and sending it again could generate
   twice. Nothing reconciles with the backend, so its image is lost and the player may ask again. Queued rows resume in
   their order when generation is enabled and available; otherwise they end `CANCELLED` with their charge back.
   Stopping the service ends tickets but leaves rows open for the next start.
4. **A late result is dropped.** A completed image is published in the same transaction that ends its row, after
   locking that row and checking that this instance still holds it. A result whose row already ended (after a restart
   or another instance's recovery) stores nothing.
5. **The appearance text is stored in the row.** A queued job must resume after a restart, and the game flow builds
   that text from public pack fields only. No other prompt text, seed, path, URL or credential is stored.
6. **Acceptance review `automatic-v1`.** Before storage, the provenance must describe exactly the bytes (hash and size)
   and the request sent (size, seed, steps, guidance, both prompt hashes). The bytes must decode to a picture of the
   requested size that is not one flat colour. A refusal is `FAILED` `image-review-rejected` and keeps its charge. This
   is a technical check. It does not judge what the picture shows, and no human or visual-consistency review exists.
7. **Provenance.** The accepted row keeps the hash and a JSON object: what the adapter reported (backend, model,
   checkpoint hash, sampler, seed, steps, guidance, size, prompt hashes, output hash and size), the review and the
   credit line. The credit line, shown in "Artwork credits", reads `Generated image for this campaign (<backend>[, model
   <first 12 hex of the checkpoint hash>])`. A backend name outside plain characters reads `image backend`, and nothing
   the adapter did not report is invented.
8. **Pack policy.** The presentation file may declare `imageGeneration` with `scene`, `portrait` and `item` each
   `bounded`, `catalogOnly` or `disabled`. An undeclared category is `bounded`, so existing packs keep working; the
   owner switch stays off by default. For runtime jobs `catalogOnly` and `disabled` both refuse generation (`DISABLED`
   verdict), and the listing omits that category's subjects. Neither hides the pack's own authored art yet.
   A pack that cannot be loaded allows nothing.
9. **Managed folder copy.** When `engine.workspaces.root` is set, each accepted image is also written to
   `adventures/<adventure>/campaigns/<campaign>/assets/generated/<scenes|portraits|items>/<sha256>.<ext>`, privately and
   by atomic rename. Startup rewrites missing copies of every stored association and fails on a changed one, like the
   revision projection. PostgreSQL stays the authority. A failed write after acceptance is logged, and the next start
   retries it.
10. **Campaign restore does not restore job rows.** `runtime_image_job` joins `inference_task` in `NOT_RESTORED`: a
    copy must not resume or be charged for the source's jobs. The accepted images travel as associations and bytes. The
    installation backup tool dumps the table whole, and its generation bump makes the next start end the open rows.

## Alternatives

- Keep memory-only jobs: fails the restart criterion.
- Resend interrupted jobs: could generate and charge twice, and without a backend request identity it cannot be
  reconciled.
- An identity column for ordering: a campaign restore refuses any identity column it does not know, so a plain
  sequence is used.
- Store a full prompt: unnecessary, since the framing is fixed by category.
- A foreign key to `campaign`: test and maintenance truncations of the campaign tables would then depend on this
  table, as V37 already avoided.

## Consequences

- A sent job interrupted by a restart costs its charge and produces nothing; the screen shows it failed and offers a
  retry.
- Rows accumulate as job history; no retention is defined yet.
- The review cannot catch a wrong or unsafe picture that is otherwise well formed.
- A campaign restored through the import route gets its folder copies only at the next start.
- Not proven: a real image backend, a packaged browser run, concurrent requests from many threads, and a live V38
  dump/restore rehearsal.
