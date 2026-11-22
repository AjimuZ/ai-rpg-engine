# Optional Speech Input and Hands-Free Play

## 1. Status and scope

Owner-requested design, 26 October 2026, under [ADR 0022](adr/0022-optional-speech-input.md). **Not implemented; disabled by default.** No microphone capture, recognizer, model installation, voice-command API, latency measurement or gameplay acceptance is delivered by these documents. The concrete speech-to-text (STT) backend, model and capture transport remain open pending qualification; [section 8](#8-recorded-disqualification-and-bounded-interfaces-p178-a) records that no recognizer is qualified today and specifies the interfaces a later qualification is measured against, [section 9](#9-no-capture-path-p179-a) describes the implemented no-capture path, and [section 10](#10-qa-45-at-disabled-scope-p1710-a) records QA-45 run at disabled scope. No browser, operating-system or model capability is assumed.

The target is optional English/Italian dictation and hands-free exploration and turn-based combat, including speaking to characters, negotiations and tactical commands. Complete typed input, contextual replies, tactical controls and readable receipts remain available with voice disabled, denied, unavailable or interrupted. Voice is optional v1 work in P17.8–P17.10; it cannot delay text-only MVP/core-v1 completion.

STT converts player audio into a proposed input draft. [Local speech synthesis](30-local-speech-synthesis.md) is the separate text-to-speech (TTS) output feature with its own admission gates; this specification neither selects its models for recognition nor changes ADR 0016. Dictation can work without TTS. A supported fully hands-free profile must also provide qualified audible readback of public action previews, clarification and receipts; if that output is unavailable, show that hands-free confirmation is unavailable and preserve dictation/text controls. Do not advertise a profile requiring a mouse for correction as fully hands-free.

## 2. Explicit microphone control and session states

The player deliberately enables microphone access and chooses bounded push-to-talk or hands-free listening. Starting the application, loading a campaign, playing narration or reconnecting never enables capture. Request permission only for the explicit enable action. Present the selected input device, capture mode and current adventure/campaign. Keep microphone mute/stop distinct from speaker/TTS mute.

Expose visible and accessible states: `off`, `permission-required`, `listening`, `transcribing`, `draft-ready`, `awaiting-confirmation`, `submitting`, `muted` and `unavailable`. Show when audio is actually being captured, when recognition is pending and why command submission is blocked. Provide keyboard, pointer and voice controls for stop listening, cancel the draft, repeat the preview and correct/replace the draft. A voice stop command is supplementary: a recognizer failure must not remove the independent mute control. Announce transitions without reading hidden state or drowning out dialogue.

Hands-free capture is opt-in for the current scoped session, with an inactivity limit and an explicit resume action. A scope switch, permission revocation, device loss, disconnect, reload or session expiry stops capture, clears buffered audio and invalidates unsubmitted confirmations. Never resume listening or submit a retained draft automatically. Any retained text draft follows the existing private scoped-draft policy, clearly marked for review. An open microphone is not an identity/authentication mechanism and is not represented as speaker verification.

## 3. Recognition, draft review and voice-only control

Treat partial and final transcripts as untrusted player input. Partial text may update a labelled recognition draft but cannot submit commands. End-of-utterance detection, silence, noise or a confidence score cannot imply consent. Silence/empty/noise-only recognition creates no command, runtime narration request, world event, cost or random draw. Bound recording duration, bytes, queue depth and recognition attempts; report timeout/unavailable without repeatedly retrying in the background.

Separate spoken interface controls from literal character speech. A clear control phrase or explicit control mode selects “confirm”, “correct”, “cancel”, “repeat” and “stop listening”; a quoted NPC sentence containing those words must not trigger them. Recognizer text, NPC audio and imported story content cannot configure the application or bypass a confirmation. If intent, speaker addressee, target, number, negation or control-vs-dialogue meaning is ambiguous, ask for clarification at no world cost. Confidence is advisory and cannot grant rule authority.

All recognized gameplay input first becomes a reviewable draft. Read back the public interpreted action and exact dialogue as appropriate. Provide a voice-only path to replace the entire draft or correct its target/terms, hear the new preview, cancel it, or explicitly confirm it. Confirming must bind to one immutable preview identity, pending decision, encounter/scene revision and payload. Any correction creates a new preview and invalidates earlier confirmations. Consequential actions (attacks, movement, item/resource use, purchases, promises, surrender, agreements or ending an activation) require this separate explicit confirmation, disclosing known costs and targets. No automatic send-on-silence. A fixed bounded interface acknowledgement need not invoke narration.

Unsubmitted cancellation removes the draft without game effects. Once submitted, “cancel” requests ordinary operation cancellation; it cannot promise to undo a committed action. Read back the authoritative status/receipt before allowing a new attempt. Distinguish “read the next available passage” from “end my activation” and from a new world action; reading and replay remain read-only.

## 4. One authoritative command pipeline, including combat

Confirmed voice input uses the same [command envelope](13-api-and-ui.md#3-command-envelope), admission, interpretation, deterministic validation, durable operation receipt and [turn protocol](07-turn-protocol.md) as typed input. Capture/recognition and draft confirmation are not canonical world events. No direct recognizer-to-database, encounter patch, event injection or arbitrary host action is allowed. A voice preview is not a committed result.

Generate one gameplay `requestId` for the confirmed payload and preserve it across uncertain delivery/retries. A retransmitted audio chunk or recognition result cannot create a fresh gameplay request. Deduplicate capture session/utterance/chunk/result identities separately from the operation identity. Same request and payload retrieves the original result; a changed payload with that request conflicts. After an uncertain submit, reconcile the receipt before making another request. Late results from a different adventure, campaign, locale, capture generation or pending decision are discarded.

During combat, [combat chat](13-api-and-ui.md#71-combat-chat) and the [dialogue/action rules](11-combat-system.md#71-dialogue-and-scene-changing-intent-during-combat) still apply. “Ask why you are attacking”, “negotiate”, “Mira, cover the exit” and “attack the guard” are intents for the normal pipeline. An ally's spoken answer does not execute its movement or attack. Bounded speech uses the declared speech policy; negotiation, social checks and scene-changing actions use supported eligibility, costs and validation. Words cannot arbitrarily change an encounter or force NPC consent. Agreement/counteroffer acceptance must be a new explicit player decision with a preview.

Only the legal current player decision can accept a gameplay command. During an NPC activation or pending operation, show/announce the restriction; drafting is allowed, but no utterance is queued for automatic execution next turn. Stale, ambiguous, unsupported, rejected or out-of-turn input consumes no AP, item, world time, speech allowance or RNG. Confirmation does not override legality. Reconnect, thread renewal and replay cannot repeat speech costs, checks, effects or settlement.

## 5. Narration playback, echo and interruption

The initial safe mode mutes soundtrack/effects during listening windows, unless a separately tested isolation profile admits them, and suspends recognition during engine dialogue/TTS and confirmation readback. Discard contaminated buffers and visibly explain the pause. After a bounded readback, return to listening only if the player still has that capture mode enabled in the same opted-in scope; playback never enables a disabled microphone. Continuous music must not prevent the player from obtaining a listening window. Test soundtrack and effect audio as well as speech. Headphones are an optional aid, not proof that echo cannot submit a command.

Hands-free interruption of narration is a separate qualification gate. Enable it only after demonstrating that the admitted profile reliably distinguishes the player's control utterance from engine playback and background voices. If not qualified, provide stop/skip controls and advertise the limitation; do not claim uninterrupted hands-free interaction. Pausing/skipping TTS or saying a listening control changes presentation only. Interruption after submit reconciles the operation receipt; it never silently resubmits or rolls back gameplay.

## 6. EN/IT, privacy and bounded local resources

Use an explicit EN/IT input locale independent of immutable historical transcript locale; derive its default from the active campaign preference. Display the active language and allow deliberate correction. Preserve proper names, glossary terms, punctuation, numbers and negation; never silently translate player words or invent a recipient. Language switching invalidates unfinished audio/preview generations. Mixed-language speech must clarify or declare its qualified limits; a language label alone proves no recognition quality. Both EN and IT need human-reviewed dialogue and tactical command fixtures with accents and varied speaking rates before bilingual support is claimed.

The first qualification path is local recognition with no account credentials or inference-service egress. Do not silently substitute browser/OS remote recognition, paid/cloud transcription or another privacy class. Any proposed outbound recognition requires a separately documented owner decision and explicit disclosure/enablement. Capture and recognizer workers receive only bounded audio, selected locale and authorized public vocabulary needed for the current input, without hidden NPC facts, narration-runtime credentials, database access, arbitrary paths or host automation. Admit transport using existing owner/session/origin protections and bounded content validation; an audio upload is never a command endpoint.

Keep raw audio ephemeral, clear it on completion/cancel/scope switch, and exclude it from ordinary logs, saves, story bundles and backups. Do not retain microphone recordings or train/adapt on them by default. A deliberate diagnostic recording needs a separate opt-in, finite retention/delete controls and a redacted reporting path. Confirmed public player words may join the normal scoped campaign transcript; unconfirmed recognition drafts follow private draft retention. Same-name entities in different adventures must never share audio, vocabulary caches, transcripts or callbacks. Do not persist voiceprints or assume a right to clone a speaker from microphone input.

Recognition shares finite CPU, RAM and optional GPU capacity with [text/image/TTS admission](34-inference-backends-and-model-selection.md). Measure the actual reference host rather than infer fit from parameter count. Give bounded interactive capture/confirmation priority, cap audio buffers and resident models, and avoid starving canonical work or narration. Record cold/warm recognition latency, utterance-end-to-draft latency, confirmation/receipt latency, peak RAM/VRAM, contention and cancellation time separately. No latency, memory or accuracy target is claimed achieved here; set and publish profile acceptance budgets before qualification. Missing hardware, OOM, unavailable files or a failed worker stop recognition, release resources and preserve typed play without terminating unrelated processes or choosing an implicit remote fallback.

## 7. Qualification and acceptance

P17.8 must research actual interfaces and exact redistributable code/weights/dependencies before choosing a concrete backend. Record supported capture/streaming modes, EN/IT evidence, license/notice obligations, maintenance/version/hash pins, offline behavior, target-platform packaging, bounded cancellation and measured hardware needs. Compare candidates against the same neutral corpus; no engine/model/provider is selected by this specification. Pin and requalify any admitted profile revision.

[QA-45](15-quality-and-acceptance.md#3-mandatory-scenario-register) covers at least the following, separately reporting automated invariants and human recognition review:

- Permission refusal/revocation, missing microphone, mute, inactive tab/session, device loss and scope switch show accurate capture status and no unintended recording/submission; text-only play remains complete.
- Silence, noise, accents, fast/slow speech, Italian/English names, numbers and negation cause safe drafts or clarification; false recognition cannot spend resources. Report command/target correctness and false activation/confirmation rates, not only aggregate transcription error.
- Voice-only preview, correction, repeat, confirmation and cancel work in both languages; old/duplicate confirmations and words embedded in character dialogue cannot execute a changed payload.
- Engine TTS/readback/music, overlapping voices and interrupts cannot self-submit a command. Report interruption unavailable when unqualified; distinguish skip from end activation.
- Exploration and combat cover speech, ally requests, attack/item costs, refused negotiation, counteroffers and explicit acceptance. Out-of-turn/ambiguous/stale/unsupported input changes no canonical counter, time, RNG or AP.
- Lost recognition output, duplicate chunks/results, disconnect before/after confirmation, lost operation response, restart and renewal preserve at-most-one committed effect through normal receipts. No reconnect auto-listens or auto-submits; cancellation after commit reports the committed outcome.
- Same-name two-adventure fixtures reject cross-scope audio/text/vocabulary/callbacks; raw recordings and secrets are absent from logs, backups and exported story bundles by default.
- Under concurrent local text/image/TTS work, bounded queues/cancellation and measured resource/latency budgets pass or the profile stays disabled with a verified typed fallback. Recognition failure never changes committed gameplay.

QA-45's disabled/no-capture/text fallback is mandatory. Full STT and hands-free claims require the enabled-profile evidence above; an honest disabled outcome closes only its fallback/qualification disposition, not an implementation claim. The disabled-scope checks ran in P17.10-A ([section 10](#10-qa-45-at-disabled-scope-p1710-a)); every enabled-profile gameplay/audio check above remains unrun.

## 8. Recorded disqualification and bounded interfaces (P17.8-A)

Recorded 22 November 2026 in `backend/src/main/resources/speech/recognition-qualification.json`, loaded by `org.rpgengine.speech.SpeechInputQualification`.

### 8.1 Disposition: no recognizer is qualified, recognition ships disabled

No recognizer is qualified. The reason is lack of evidence, not a finding about any product: no upstream license, notice or revision was read in this project, because sessions have no network access, and nothing was measured on the reference host. Therefore this record names no candidate, no license and no revision, and none is taken from memory. Research and measurement belong to the owner gate P17.8-O. Until then the record has `qualifiedRecognizer: null` and passes no gate, so `decide(ownerRequested)` answers `captureAllowed=false` with `not-requested` (when applicable), one `gate:<id>:<status>` reason per open gate and `no-qualified-recognizer`, even if the owner asks. Disabled means no permission request, no capture buffer, no worker and no background retry; typed play is the complete path. P17.9 implements this no-capture path and cannot rely on any recognizer.

### 8.2 Bounded capture and cancel interface

Any recognizer is measured against this interface; a recognizer that cannot meet it is not qualified. The ceilings below are design limits set by the engine, not measurements, and are exposed by the record as `limits` (P17.8-O may tighten them with evidence, never relax them silently):

| Ceiling | Value | Meaning |
| --- | --- | --- |
| `maxUtteranceSeconds` | 30 | One utterance ends at this duration; the rest is discarded, not recognized later. |
| `maxUtteranceBytes` | 2 097 152 | Raw audio of one utterance, rejected beyond this before reaching a recognizer. |
| `maxQueuedUtterances` | 1 | A second utterance while one is pending is refused, never queued. |
| `maxRecognitionAttempts` | 1 | One attempt per utterance; a failure is reported, never retried in the background. |
| `maxCaptureSessionsPerScope` | 1 | One capture session per adventure/campaign scope at a time. |

Operations, each bound to a capture session identity, an utterance identity and the active adventure, campaign, locale and capture generation:

- `start(scope, locale, mode)`: opens one session only after the player's explicit enable action; `mode` is push-to-talk or hands-free with the inactivity limit of section 2; `locale` is `en` or `it`, never inferred.
- `append(utterance, chunk)`: bounded, idempotent per chunk identity; a duplicate chunk changes nothing.
- `finish(utterance)`: requests one recognition and returns at most one final draft text plus advisory confidence. Partial text, if the recognizer streams it, is a labelled draft only.
- `cancel(session | utterance)`: idempotent, callable at any state including while recognition runs; it stops the worker's work for that identity, releases the audio buffer and model memory it held, and yields no later result. A result that arrives after cancel, a scope switch, a locale switch or a generation change is discarded. Cancel time is measured separately and its budget is set in P17.8-O.
- `close(session)`: ends capture, clears every buffer and invalidates unsubmitted confirmations (section 2); device loss, permission revocation, disconnect, reload and expiry behave as `close`.

Every failure (timeout, unavailable worker, out of memory, missing files, unsupported locale) is reported as `unavailable` with a stable reason, releases resources, leaves unrelated processes alone and never selects a remote or browser/OS recognizer. Audio, vocabulary and results never cross adventure scope. Only bounded audio, the locale and authorized public vocabulary reach the worker (section 6).

### 8.3 Qualification gates

All six gates must be `passed`, each with evidence, before a recognizer may be named; the loader refuses a record that names one past an open gate. Current status, all from the record:

| Gate | Status | Open evidence |
| --- | --- | --- |
| `rights-inventory` | `pending` | Exact code, weight and dependency declarations with revisions and hashes, read from upstream (P17.8-O). |
| `capture-cancel-interface` | `unexecuted` | A recognizer run against section 8.2 including duplicate chunks, cancel during recognition and late results. |
| `offline-behavior` | `unexecuted` | Recognition in a container without network access, with a startup that fetches nothing. |
| `bilingual-recognition` | `unexecuted` | Section 7 EN/IT fixtures (accents, rates, names, numbers, negation) with human review. |
| `packaging` | `unexecuted` | Distributable inventory and notice obligations for the target platforms. |
| `resources` | `unexecuted` | Cold/warm latency, utterance-end-to-draft, peak RAM/VRAM, contention and cancel time on the reference host, against budgets published before the run. |

### 8.4 Offline behaviour a recognizer must meet

After installation it must start, recognize and cancel with no network access and no account, fetch no weights, vocabulary or telemetry at runtime, and write nothing outside its own bounded scratch space, which it clears on completion, cancel and scope switch. A missing network is not a failure; a missing local file is `unavailable`. No part of this is measured yet.

### 8.5 Not proven

No recognizer, model, microphone, audio or browser capture API was run; no latency, memory or accuracy figure exists; no EN/IT fixture was recognized; no QA-45 scenario ran. The section 8.2 ceilings are unmeasured design limits.

## 9. No-capture path (P17.9-A)

Implemented 22 November 2026 in `org.rpgengine.speech.SpeechInput`, on the section 8 decision. Because no recognizer is qualified, this is the disabled disposition of P17.9, not an implementation of voice input.

- `start(decision, scope, locale, mode)` answers an explicit listening request with state `unavailable`, `capturing=false`, `permissionRequested=false`, no session identity and `typedInputAvailable=true`. Its reasons are the decision's reasons, then `scope-missing`, `locale-unsupported` (only `en` and `it`, never inferred) and `mode-unsupported` (only `push-to-talk` and `hands-free`) when applicable. No capture worker exists, so an enabled decision still yields `unavailable` with `worker-unavailable`.
- `receive(status, text)` discards any recognition result, for example a late or duplicated one: no draft, `queued=false`, reason `no-capture-session`. The text is not read, so a control phrase such as "confirm" inside it changes nothing, and nothing is queued out of turn.
- No HTTP route, setting, permission prompt, microphone control or frontend change exists; the player interface offers no microphone, and typed exploration and combat input are unchanged.

Not implemented, because each needs a qualified recognizer: enabled capture sessions, the listening/transcribing/muted states and their controls, draft review, voice-only preview/correction/repeat/confirmation/cancel, routing of confirmed voice input through the command envelope and playback echo suppression. No microphone, browser capture API or audio was run, and no QA-45 scenario ran.

## 10. QA-45 at disabled scope (P17.10-A)

Run 22 November 2026. **Disposition: speech input is disabled.** No recognizer is qualified (section 8), so no profile is enabled and QA-45 ran only at disabled scope. This closes the disabled/no-capture/typed-fallback disposition; it is not voice support.

| QA-45 area | Disabled-scope evidence | Result |
| --- | --- | --- |
| Explicit permission, listening, mute | `SpeechInputDisabledScopeTest`: every start, requested or not, in `en`/`it` and both modes, is `unavailable` with no permission request, no capture and no session | passed |
| Silence, noise, accent, negation, control words in dialogue | Empty, blank, noise, NUL, EN/IT negations, "Mira, cover the exit"/"copri l'uscita", "confirm"/"conferma", "cancel"/"annulla", "stop listening" inside a quoted line: no draft, nothing queued, `no-capture-session` | passed |
| Duplicate results and confirmations | Each result delivered twice: same discard, nothing queued | passed |
| Reconnect, restart, renewal | Three repeated rounds of starts answer exactly as the first; nothing resumes listening | passed |
| Same-name adventure isolation | `adventure-a/the-harbour` and `adventure-b/the-harbour`: each status echoes only its own scope; no session, buffer or vocabulary exists to share | passed |
| No audio input surface | The test scans every `@Controller` under `org.rpgengine`: no route path names speech, voice, audio, microphone, recognition, transcription, dictation, listening or utterances, and no mapping consumes `audio/*` | passed |
| Complete typed fallback | The same scan finds `POST /api/v1/campaigns/{campaignId}/commands`; `CampaignCommandHttpTest` (typed exploration and combat, including a negotiated ending) passed in the same run | passed |
| Frontend capture | A case-insensitive search of `frontend/src` for `getUserMedia`, `SpeechRecognition`, `webkitSpeech`, `MediaRecorder`, `AudioContext` and `microphone` found nothing | passed |
| TTS echo and interruption | Not applicable: no capture exists and speech output is disabled (P17.7-A); interruption is reported unavailable | not run |
| Recognition quality (EN/IT accuracy, false activation), latency, shared-resource load, cancel time | Needs a qualified recognizer (P17.8-O) | not run |

Not proven: no microphone, recognizer, browser capture API or audio was run; no human recognition review, no browser session and no measurement took place. The frontend evidence is a source search, not a browser test. The controller scan covers Spring MVC request mappings only; any endpoint registered another way is outside it.
