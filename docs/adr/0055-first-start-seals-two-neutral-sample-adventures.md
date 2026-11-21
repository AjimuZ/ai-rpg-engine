# ADR 0055: First start seals two neutral sample adventures

- Status: Accepted (owner decision, 21 November 2026); implemented in P17.2-D (21 November 2026).
- Date: 21 November 2026
- Supersedes in part: [ADR 0046](0046-automatic-first-start-provisioning-of-the-owner-and-a-sample-adventure.md),
  decision 4 (one bundled sample) and the sample part of decision 3. Its owner, launcher, idempotence and refusal rules
  are unchanged.

## Context

QA-32 asks for an adventure switch: music of one adventure must stop and the other adventure's own music must start,
with nothing shared. A packaged installation held one adventure, and no route creates another
(`AdventureBundles` has no route), so the switch could not be shown on a real installation. The owner chose to bundle a
second sample. The cyberpunk slice of P14.1-B (`backend/src/test/resources/packs/cyberpunk-neon-alley`, "Neon Alley",
English with a reviewed Italian overlay) already proves a second genre on the same build.

## Decision

1. **Two bundled samples.** `FirstStartProvisioning.SAMPLE_ROOTS` lists `sample-adventure/` (`sample:lantern-ford`,
   unchanged) and `sample-adventure-2/` (`sample:cyberpunk-neon-alley` 1.0.0, "Neon Alley"). The second is a copy of the
   P14.1-B fixture's text and data, with English source text and the fixture's reviewed Italian overlay
   (`locales/it.json`). It adds a `presentation.json` with a mood policy and two original generated loops,
   "Neon Rain" (exploration, 6 s) and "Humming Wires" (tension, 4 s), written by
   `tools/sample-music/make-sample-music.mjs`. All of it is original project work under MIT.
2. **Neutral folder names.** The folders are numbered, not named after their adventures, so engine sources and
   non-sample resources still name no adventure (P15.5-A). `AdventureIndependenceTest` treats both folders as samples.
3. **One workspace per sample, created together.** The first sample keeps the workspace identity of ADR 0046
   (`engine.sample-workspace:<owner>`), so an installation provisioned earlier still finds it. The second derives
   `engine.sample-workspace:<owner>:sample-adventure-2/`. Whether the owner is fresh (has no workspace at all) is
   decided once, before any insert, and both workspaces are inserted in the same transaction. Each pack is then sealed
   into its own workspace only while that workspace holds no revision.
4. **Only fresh installations receive the second sample.** An installation that already has any workspace, including
   one provisioned with only Lantern Ford by an earlier image, keeps exactly what it has: its empty sample workspace is
   still completed, but no second workspace is added. This is the conservative reading of ADR 0046's rule that an owner
   with adventures never receives a sample.
5. **`FirstStartProvisioning.Result`** now lists one `Sample` (workspace, created, sealed) per sample workspace that
   exists after the call. `workspaceId()` still names the first sample's workspace, or null.

## Alternatives

- Add a route that imports a pack: rejected for this task, because pack import through the browser is a separate
  product feature with its own review.
- Load the test fixture from the test classpath at runtime: impossible, because test resources are not in the image.
- Add the second sample to existing installations as well: rejected, because it would give an owner who already has
  adventures a new one, which ADR 0046 forbids.

## Consequences

- A fresh installation shows two adventures in the library, in English and Italian screens. Neon Alley's pack text
  is reviewed in Italian; Lantern Ford still has none.
- The bundled Neon Alley copy and the P14.1-B fixture now hold the same text; a change to one should be made to the other,
  or the fixture's tests no longer describe the bundled sample. Both declare `sample:cyberpunk-neon-alley` 1.0.0, in
  different workspaces.
- The image grows by the second sample (about 320 KB of WAV audio and 10 KB of text).
- Backup and restore rehearsals that count provisioned rows now expect two workspaces and two revisions
  (`RebootRecoveryTest`, `SampleRestoreCheck`).

## Verification

`FirstStartProvisioningTest` (`provisioningCreatesOneOwnerAndBothSamplesAndRepeatsAsNothing`,
`emptySampleWorkspacesOfAFreshStartAreCompletedOnTheNextStart`, `anEmptySampleWorkspaceIsCompletedOnTheNextStart`,
`theSecondSampleIsListedAndOpensInItalian`), `CyberpunkSliceTest`, `AdventureIndependenceTest`, and on a packaged
installation `node frontend/tools/music-journey.mjs adventures [en|it]` and `... switch` (roadmap P17.2-D entry).
