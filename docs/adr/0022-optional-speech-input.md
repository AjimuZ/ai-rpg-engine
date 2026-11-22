# ADR 0022 — Optional speech input and hands-free gameplay

- Status: Accepted owner-requested design; implementation and backend qualification pending
- Date: 26 October 2026
- Owner direction: immersive spoken exploration and combat, including dialogue and commands, with complete text fallback

## Context

The engine already plans optional local EN/IT speech synthesis in [ADR 0016](0016-optional-local-speech.md). Output audio does not provide microphone input or a safe spoken command path. Misrecognition, playback echo, repeated audio delivery and stale combat decisions must not create unintended world actions.

## Decision

Accept optional EN/IT STT and hands-free play under the [speech-input specification](../35-speech-input-and-hands-free-play.md), disabled by default and independent of MVP/core-v1 completion. Keep recognition separate from TTS and narration. No concrete recognition backend/model/provider is selected; research exact rights, interfaces, EN/IT quality, privacy and actual resource/latency behavior before admission. The initial qualification path is local with no account credentials or inference-service egress; remote recognition needs a separate documented decision and explicit enablement.

Microphone enablement and listening status are explicit and accessible, with independent mute/stop and finite scoped capture sessions. Recognition produces a reviewable draft. A supported hands-free profile supplies audible public previews and voice-only correction, repeat, confirmation and cancellation. Consequential input requires separate confirmation bound to one payload/revision/decision. TTS echo must not trigger gameplay; interruption support is advertised only after qualification.

Confirmed voice input uses the same authoritative command pipeline, durable request identity and receipt reconciliation as typed input. Recognition cannot patch events or bypass combat costs, knowledge boundaries, player agency or the next decision boundary. Out-of-turn input is not queued. Silence/noise, clarification, rejection and stale results do not consume gameplay resources. Cancellation cannot undo a committed result.

Keep raw audio ephemeral and excluded from ordinary exports/logs/backups; isolate capture, drafts, vocabulary and callbacks by adventure/campaign. Reuse bounded shared CPU/RAM/GPU admission with text, image and TTS work. Failed recognition or missing hardware preserves complete typed play, with no silent cloud fallback.

## Update — 22 November 2026 (P17.8-A)

No recognizer is qualified: no upstream rights were read and no budgets measured in this project, so recognition ships disabled and the record names no candidate. The bounded capture/cancel interface, the offline behaviour and six qualification gates are specified in [section 8 of the specification](../35-speech-input-and-hands-free-play.md#8-recorded-disqualification-and-bounded-interfaces-p178-a) and recorded in `speech/recognition-qualification.json`. This changes none of the decisions above; research and measurement remain open for P17.8-O.

## Update — 22 November 2026 (P17.9-A)

Because no recognizer is qualified, P17.9 delivers only the no-capture path: `org.rpgengine.speech.SpeechInput` answers a listening request with `unavailable` and reasons, opens no session, buffer or permission request, and discards any recognition result without a draft or queued input ([section 9 of the specification](../35-speech-input-and-hands-free-play.md#9-no-capture-path-p179-a)). Enabled capture, listening/mute controls, voice-only confirmation and echo suppression are not implemented; they need a qualified recognizer first. This changes none of the decisions above.

## Update — 22 November 2026 (P17.10-A)

QA-45 ran at disabled scope ([section 10 of the specification](../35-speech-input-and-hands-free-play.md#10-qa-45-at-disabled-scope-p1710-a)): `SpeechInputDisabledScopeTest` shows EN/IT silence, noise, negation, control words, duplicated results, repeated starts and two same-name adventure scopes all stay inert, and that no controller maps an audio or speech route while the typed command route stays mapped. The disposition is **disabled**: no profile is enabled and voice input is not claimed. Enabled-profile QA-45 needs a qualified recognizer (P17.8-O). This changes none of the decisions above.

## Consequences and verification

P17.8–P17.10 own research/admission, input/control implementation and QA-45 qualification. No checked implementation task, worker, API, model installation or performance result follows from this ADR. Full hands-free qualification requires admitted audible confirmation output, while basic dictation and text play remain independent of TTS. QA-45 tests both EN/IT, silence/noise/accent, echo/interruption, permission/mute, duplicate delivery, reconnect/cancel, per-adventure isolation and no out-of-turn costs. A disabled profile must pass no-capture and text-fallback checks without being represented as implemented voice gameplay.
