# ADR 0016 — Optional local speech with stable voices and shared resource admission

- Status: Accepted design; implementation and model qualification pending
- Date: 23 October 2026
- Owner direction: optional local English/Italian speech, persistent character voices, RTX 4070 12 GB / 32 GB Windows 11 target, and complete text-only fallback

## Context

Earlier scope deferred voice generation beyond v1. The owner now requests a researched optional speech path, without delaying the playable engine, replacing Codex narration or granting a media worker canonical authority. English and Italian are both initial text requirements. An enabled supported speech profile must also qualify both languages; a catalog's language label is insufficient evidence of Italian quality.

The [speech specification](../30-local-speech-synthesis.md) compares actual source, downloadable artifacts, maintenance and separate code/weight/voice rights. Qwen Base and Chatterbox Multilingual expose reusable voice conditioning; Kokoro offers a smaller fixed cast. Maintained Piper is GPL-3.0 with per-voice rights; XTTS-v2's CPML restricts weights and outputs to noncommercial use. No local inference or listening benchmark has run.

## Decision

Accept optional local speech as **v1 optional**, disabled by default. This supersedes only the previous blanket deferral of voice generation; it does not make speech a prerequisite for MVP/core v1. Retain Spring Boot's state authority, existing gameplay/knowledge isolation, and Codex as the narrative runtime. TTS consumes already validated public text and produces presentation assets only.

Use a separately isolated optional Python media worker behind the backend, without a published host port, outbound network, account/database access, source checkout or arbitrary host mounts. Use narrow bounded text/locale/voice requests and validated audio output, not upstream demo servers or arbitrary SSML, model IDs, file paths, URLs or generation options. Reuse the [shared durable job/admission design](../28-asynchronous-narrative-planning.md); do not introduce a mandatory runtime pool or second gameplay service.

Select **Qwen3-TTS-12Hz-0.6B-Base as the provisional primary candidate** and **Kokoro-82M v1.0 CPU/validated ONNX as the provisional lightweight fallback**. Selection depends on exact dependency/weight/voice rights, reproducible Windows/WSL operation, resource measurements and native English/Italian listening. Chatterbox Multilingual explicitly selected V3 remains the main challenger; a measured 1.7B Qwen Base upgrade is possible. No quality or RTX 4070 fit claim is accepted without those checks. Failed qualification leaves speech disabled and text play complete.

Persist scoped voice identities, admitted reference/provenance/consent records and source-linked audio associations independently of narration threads and worker lifetime. Derive speech locale from [localization](../29-localization.md), with versioned pronunciation normalization and no silent cross-language fallback. Cache keys bind scope/audience, public line/text revision, locale, voice/reference/model/settings revisions and normalizer/lexicon hashes. Backups/exports preserve permitted assets and remap identities; private references and clone data are excluded from player exports by default.

Start with complete bounded lines/segments, explicit speaker/subtitle identities, deliberate playback, independent volume, pause/skip/cancel and stale-result rejection. Qwen's inspected Python wrapper does not provide true incremental generation merely through its streaming-mode flag. Streaming, word alignment and lip sync remain deferred pending an actual supported-interface proof. Audio completion never submits a command or advances game time.

Default to one heavy local GPU job across speech and local images, measured memory admission with headroom, bounded queues/deadlines and optional primary-model unloading. Respect external GPU workloads without terminating them. OOM, missing GPU/weights, queue pressure, worker failure or denied voice rights choose an independently admitted same-locale CPU fallback or text. No implicit cloud/paid fallback or local gameplay LLM enters scope.

## Alternatives and tradeoffs

- Embedding PyTorch/native synthesis in Spring or the Codex runtime would couple unrelated dependencies and authority; reject it for this optional feature. Direct Java ONNX can be reconsidered for a proven fixed catalog, after phonemizer/native packaging evidence.
- Using only Kokoro/Piper simplifies resources but limits cast diversity and requires independent Italian/rights evaluation. It is a fallback experiment, not proof of cloned-voice equivalence.
- Defaulting to XTTS-v2 would conflict with the requested free commercial-compatible path despite maintained MPL inference code; exclude its CPML weights from the default.
- Browser/OS speech, a generic inference server and multiple resident models cannot provide the required stable qualified cast by assumption; defer them. Text is the unconditional fallback.

## Consequences and verification

P03/P05/P16 provide reusable persistence, presentation and export foundations without requiring a speech installation. P17.7 owns optional capability qualification/rollout; P18 verifies QA-38 using the unexecuted benchmark in the [speech specification](../30-local-speech-synthesis.md) and [paired evaluation protocol](../31-research-validation-and-evaluations.md). Required evidence includes actual cold/warm latency, RTF, total/peak VRAM and RAM, Italian/English fidelity and human quality scores, rights/consent records, isolation, GPU/image contention, cancellation, restore, stale/duplicate playback and accessible text fallback.

The additional worker, model storage, native dependencies and rights inventory increase maintenance. No dependency is installed or artifact bundled by this ADR. Requalify changed checkpoints, voice references, conversions/precision and supported platforms; preserve historical bytes and identity mappings instead of silently rerolling voices on upgrade. Accepted design does not imply shipped or verified speech.
