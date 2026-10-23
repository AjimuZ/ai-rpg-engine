# Dynamic Music and Credits

## 1. Status and scope

This chapter specifies future P17.2 work. No music player, streaming integration, downloaded tracks or active soundtrack is delivered by this documentation change. Music is optional and must never block narration, combat, saves or the text-focused interface.

The owner wants a soundtrack that follows the situation and can play from a free online library without a required manual download. Support a curated allowlisted streaming catalog and optional locally installed tracks. Browser streaming still transfers/buffers audio; it does not mean offline playback. Do not require background music files in an adventure export unless the owner has chosen a permitted local copy.

## 2. Event-driven soundtrack

Use an adventure's public presentation policy to map registered cue IDs to exploration, dialogue, rest, observed tension, active combat and resolved victory/defeat states. Public events and known scene metadata drive transitions; hidden enemies, private motives and future encounters must never leak through a change of music. No extra narrative call or live web search is required for each transition.

The controller maintains one current cue, its campaign/source identity and playback state. The same cue does not restart on every dialogue passage, UI render or duplicate stream event. A new valid cue can crossfade with a configurable transition (initial target about two seconds). Bound looping and avoid an overlap of more than the two transition sources. Reading Continue, opening inventory or selecting an unsent quick reply does not advance musical/gameplay state. Discard stale cues after navigation or campaign change; reconnect must not replay one-shot stingers.

Playback requires a deliberate user gesture and an explicit music-enabled preference. Provide pause/mute, music/effect volumes, current title/creator/license and an accessible Credits link. Browser autoplay denial is an understandable inactive state, not a reason to retry silently. No spoken narration/TTS implementation is required here.

## 3. Online catalog and failure behavior

Keep a finite reviewed catalog in registered metadata; the runtime selects entries, not arbitrary generated URLs. Require HTTPS and explicit owner-approved origins, MIME/size/redirect checks, a restrictive media policy and appropriate referrer handling. Opening a story must not contact a third-party audio host before online music is enabled. Do not introduce an unrestricted backend URL proxy.

[Wikimedia Commons permits hotlinking](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia/technical#Hotlinking) while recommending controlled copies for reliability; license obligations still apply. Its policy also rules out redirecting every user search to its servers as a live search service. Use reviewed individual file references, not a scraped/live-search soundtrack. Hosting availability, file replacement, redirects, browser decoding and any Web Audio/CORS behavior must be verified in P17 before claiming streaming support.

On timeout, unavailable network, rejected MIME, removed media or missing permission, keep the current valid permitted cue where appropriate or fade to silence. Never substitute unreviewed music. Display a quiet retry/source action; the story remains playable. Local optional copies require the same rights records and import containment as other assets. Do not download a remote file merely to bypass an embedding or access restriction.

## 4. Rights inventory and credits

The initial admissible catalog is CC0 or a specifically identified CC BY 3.0/4.0 work with compatible terms. Exclude unknown rights, noncommercial/no-derivatives restrictions and merely “free to listen” or “royalty-free” labels from automatic admission. Other licenses need a deliberate compatibility review. Check both composition and the actual performance/recording; a public-domain composition does not establish a recording's rights.

Each registered track must carry title, credited creator and other requested attribution parties, original/source page, exact media reference/version, license name/version/URL, supplied notices, modifications, verification date and local hash when copied. Credits must preserve required notices and disclose edits such as trimming, re-encoding or remixing when applicable. The project's MIT license does not replace a track's license. Link the source and exact license from the current-track view and a persistent, searchable Music Credits screen; include selected-track credits in portable manifests/exports.

[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) and [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) permit reuse subject to their attribution and other terms; preserve the correct version instead of relabeling an older recording. [CC0](https://creativecommons.org/publicdomain/zero/1.0/) does not impose an attribution condition, but retain source/creator credit as project policy. Follow the [Creative Commons attribution guidance](https://creativecommons.org/reusing-cc-licensed-content/) and retain supplied title, creator, source, license and change notices.

## 5. Researched candidate references

Checked on 23 October 2026. These are candidate source/credit records, not bundled audio, an installed playlist, a completed recording-rights audit or verified browser playback. Recheck the selected recording and hosting terms at admission; missing evidence prevents activation.

| Possible use | Work and creator | Declared license on the linked file page | Reference status |
| --- | --- | --- | --- |
| Calm exploration | [Wholesome](https://commons.wikimedia.org/wiki/File:Wholesome_by_Kevin_MacLeod.ogg), Kevin MacLeod (incompetech.com) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | Source and file-page declaration checked; no audio copied or playback tested |
| Observed mystery/tension | [Darkest Child, ISRC USUAN1100783](https://commons.wikimedia.org/wiki/File:Darkest_Child_%28ISRC_USUAN1100783%29.mp3), Kevin MacLeod (incompetech.com) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | Source and file-page declaration checked; no audio copied or playback tested |

Candidate credit templates (references only; add the actual selected media version and any processing before activation):

- “Wholesome” — Kevin MacLeod (incompetech.com). [Source recording page](https://commons.wikimedia.org/wiki/File:Wholesome_by_Kevin_MacLeod.ogg). [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/). No audio file is included or modified in this repository.
- “Darkest Child” (ISRC USUAN1100783) — Kevin MacLeod (incompetech.com). [Source recording page](https://commons.wikimedia.org/wiki/File:Darkest_Child_%28ISRC_USUAN1100783%29.mp3). [Creative Commons Attribution 3.0 Unported](https://creativecommons.org/licenses/by/3.0/). No audio file is included or modified in this repository.

No battle/victory cue is admitted merely to fill a category; silence remains valid until a suitable licensed recording is reviewed. See [third-party notices](../THIRD_PARTY_NOTICES.md) for the reference-only boundary.

## 6. Verification

QA-32 covers deliberate activation/autoplay denial, pause/volumes, scene → observed tension → combat → post-combat transitions, stable loops during dialogue, deduplicated/stale cues, adventure switching, offline/removed media, readable complete credits, license-version validation and absence of secret-sentinel leakage. Test ordinary CI with local fixtures; an optional real-host smoke records the exact environment and recording without claiming host reliability. These are future acceptance criteria, not passed tests.
