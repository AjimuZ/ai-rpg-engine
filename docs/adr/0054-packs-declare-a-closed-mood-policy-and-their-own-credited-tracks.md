# ADR 0054: Packs declare a closed mood policy and their own credited tracks, delivered by one read route

- Status: Accepted; implemented in P17.2-D (21 November 2026).
- Date: 21 November 2026
- Changes: the pack `presentation` file gains an optional `music` object (`PackPresentation.Music`). The engine gains
  `GET /api/v1/campaigns/{campaignId}/music?adventureId=`. The client gains a strict parser (`music-pack.ts`) and pack
  tracks in `MusicPlayer`. No migration.

## Context

docs/24 section 2 asks an adventure's public presentation policy to map public states to moods, and no change of music
may reveal hidden state. P17.2-C built the policy format and parser on the client (`MoodPolicy`, `parseMoodPolicy`, six
closed public states derived from the player-safe combat projection). No pack field held a policy and no route carried
one. QA-32 must be run on a packaged installation, where no reviewed online recording is known to play, so the sample
needs a short local track of its own. Several details were open: the declaration format, which licenses a pack track may
carry, how the client addresses a track's bytes, and what the player does before it knows a playthrough's tracks.

## Decision

1. **The policy is closed.** `music.moods` may name only `quiet`, `offered`, `fight`, `won`, `lost` and `settled`
   (`PackPresentation.PUBLIC_STATES`, equal to the client list). Any other key is an unknown field and the pack does not
   compile, so a policy cannot react to an entity, an event or a hidden value. Values are the seven catalog moods.
2. **Pack tracks follow the artwork chain** (P17.1-C): `music.tracks` binds a listed `asset` file with an audio
   extension, one track per mood, with complete credit text. The policy and tracks are part of the sealed revision. The
   route recompiles the campaign's pinned revision, like the artwork listing does, and gives each track the SHA-256 of
   the sealed file. The client builds the address on the existing asset route from the scope and that hash. A pack never
   supplies a URL, and `source` is plain text.
3. **Licenses.** A pack track may carry the reviewed music licenses (`CC0-1.0`, `CC-BY-3.0`, `CC-BY-4.0`) or `MIT`.
   MIT is added because it is the license the project and its bundled sample already use. It permits reuse and
   modification and requires the copyright and permission notice, which the track's `notices` text and the pack's
   `LICENSE.md` carry. Other licenses still need their own review. The online catalog's admission rules are unchanged.
4. **The client parser is strict.** It requires exact keys, the route's scope, a closed policy, admitted licenses with
   their exact URL, SHA-256 hex and an audio media type. Anything else drops the whole listing, and the playthrough
   keeps the default policy and the reviewed catalog.
5. **Pack tracks come first, per scope, and only once known.** For the current playthrough, the player prefers the pack's
   track for a mood over local and online records. While a playthrough's listing is still being read, the player chooses
   nothing for it. This rule comes from the packaged run, where a switch otherwise started the online catalog track for a
   moment. A refresh of the same playthrough keeps the listing, and its momentary unknown mood does not cue anything.
6. **The sample's music is generated.** `tools/sample-music/make-sample-music.mjs` writes two short WAV loops from note
   lists in the file (no download, no randomness). Two runs give identical bytes. They are covered by the sample's MIT
   license.

## Alternatives

- Put the policy in the artwork listing: rejected, because one route per concern keeps each body's allowlist simple to test.
- Let a policy name events or entities: rejected, because that is the hidden-state channel QA-32 forbids.
- Admit only CC0/CC BY for pack tracks and relicense the sample audio as CC0: rejected for now, because relicensing
  the owner's work is the owner's decision. MIT already covers the rest of the sample.
- Bundle the sample track in `frontend/public/assets/music/` as a `MUSIC_LOCAL` record: rejected, because that record
  type requires a source page on an approved online host. A pack-scoped track also lets another pack ship its own music.

## Consequences

- A pack can change which public states sound tense or calm, for example mapping `fight` to `tension` when it has no
  combat track. It cannot make music depend on anything the player has not observed.
- The route recompiles the pinned pack on each read, like the artwork listing, and is not cached. The browser reads it once
  per playthrough.
- WAV files are large: the two sample loops are 448,088 bytes together. A later pack may prefer OGG.
- Only the player's tracks are covered: effects and spoken dialogue have no pack declaration yet.

## Verification

`PackPresentationTest` (`readsAMoodPolicyAndCreditedTracks`, `refusesMusicThatCouldNameHiddenStateOrLacksCredits`),
`ManagedAssetsTest.deliversThePackMoodPolicyAndItsTracks`, `music-pack.spec.ts`, and the packaged-installation journey
`frontend/tools/music-journey.mjs` (roadmap P17.2-D entry).
