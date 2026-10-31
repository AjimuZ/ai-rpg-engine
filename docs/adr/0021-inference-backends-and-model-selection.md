# ADR 0021: Inference backends, free local models and owner-chosen model slots

- Status: Accepted design (owner decision, 25 October 2026); implementation and evidence pending. Defaults are recommendations, never restrictions.
- Date: 25 October 2026
- Builds on: [ADR 0020](0020-selectable-narration-runtime.md) (default runtime, extended here), [ADR 0013](0013-narration-runtime-isolation.md) (isolation profile), [ADR 0016](0016-optional-local-speech.md) (shared media admission), [ADR 0011](0011-visual-novel-and-persistent-artwork.md) (artwork) and [ADR 0007](0007-audience-isolation.md) (audiences).

## Context

The owner asks for four things:

- free local models, for narration and for image generation;
- hosted use through an API key;
- the best recommended models for the reference PC, an RTX 4070 with 12 GB of VRAM;
- maximum freedom to choose other models, including a different one for each weight class and for images.

The need is concrete. Neither agent runtime supplies images: the managed Codex profile reports image generation unavailable (P01.6), and the Claude Code profile claims none. A local image backend is therefore the only way to deliver the runtime artwork of ADR 0011 and P17.6. A free local text backend lets the owner develop, test and play without spending plan quota.

The [specification, section 2](../34-inference-backends-and-model-selection.md#2-verified-facts-and-sources) lists the facts checked on 25 October 2026. These shape the decision:

- The reference host has one 16 GB memory module (single channel) and 12 GB of VRAM. Memory, not compute, is the scarce resource, so models must fit in VRAM and only one heavy model can be resident at a time.
- Gemma 4 12B has an official 4-bit build of 7.0 GB under Apache-2.0. The two image checkpoints the owner chose in the predecessor project, WAI-illustrious-SDXL and Pony Diffusion V6 XL, are 6.9 GB single files.
- The llama.cpp server documents a multi-model router with load and unload endpoints, JSON-Schema constrained output and prompt-cache save and restore.

## Decision

1. **Closed set of protocols, open set of models.** The engine supports a fixed set of adapters: the agent runtimes `codex` and `claude`; a managed `local` model server; `openai-compatible` endpoints; `anthropic-api`; and for images a managed `local-image` worker, `a1111-http` (Forge, A1111 and compatible servers) and `openai-images`. Any model or endpoint these protocols can serve may be assigned. A new protocol needs its own decision record.
2. **Four owner-assigned slots.** The slots are `text.light`, `text.standard`, `text.deliberate` and `image`, aligned with the logical profiles of [Codex integration §9](../06-codex-integration.md#9-model-and-reasoning-routing). Each slot has its own backend, model and parameters, so the owner can choose three different models for the three weight classes, or one for all. `NARRATION_RUNTIME` (ADR 0020) stays the default backend of the three text slots, and a per-slot assignment overrides it. Speech remains its own optional service (ADR 0016).
3. **Recommend, never restrict.**
   - A catalog of pinned, hash-verified, licensed entries supplies the recommended defaults.
   - Any other model is allowed when its protocol can serve it. A readiness probe tests the output contract and English and Italian text, and shows the result. The engine warns about unverified hashes, unknown licenses and missing capabilities instead of blocking.
   - It refuses only when a safety property cannot hold: an unreachable or non-allowlisted endpoint, an executable model format, or output that breaks the contract.
4. **Hardware advisor.** The launcher writes a hardware profile (GPU, VRAM, RAM, disk, container GPU support). The advisor ranks the catalog against it and proposes presets (for example local-only, hybrid, cloud-first). Nothing is downloaded or sent without an explicit owner action.
5. **Managed local backends.**
   - A `models` container runs the llama.cpp server in router mode, with a read-only weights volume and no network egress.
   - An `images` container runs a pinned diffusers worker under the same rules.
   - Both follow the ADR 0013 container profile.
   - Weights come only from `./start.sh --models fetch`, which verifies SHA-256, shows the license and asks for confirmation.
6. **One gateway for everything that leaves the machine.** Hosted APIs with the owner's key, and custom or LAN endpoints, are reached only through a `gateway` container. It holds the keys as runtime-only secret files and enforces a destination allowlist, request bounds and per-slot budgets. The engine has no Internet route. Keys and endpoints come only from owner settings, never from packs, prompts or model output.
7. **Shared GPU and RAM admission.** The coordinator of ADR 0016 now covers text, images and speech. On the 12 GB host one heavy model is resident at a time. The text model is resident by default, and images or speech take a lease. A lease unloads the text model through the server's documented endpoint, runs a batch, reloads it and restores the prompt cache. Interactive text has priority, and image jobs yield at step boundaries.
8. **Billing and privacy classes.** Every assignment is `local`, `subscription` or `metered`. The class is always visible, changing it needs an acknowledgement, and there is no automatic crossing between classes. A fallback list is allowed only within one class and only when the owner configures it. The no-failover rule of ADR 0020 stands.
9. **Untrusted weights and outputs.**
   - Only open formats are loaded: GGUF and safetensors.
   - Pickle checkpoints, `trust_remote_code`, custom nodes and extensions are excluded from managed backends.
   - Every output is validated like any proposal.
   - A model's own safety tuning is not part of the engine's boundary.
   - Source, hash, license and attribution are recorded for each model. Image prompts default to the checkpoint's general-rating tag with explicit-content negatives, and the repository ships no unreviewed generated art.
10. **Defaults for the reference host.**
    - **Text:** Gemma 4 12B-it QAT Q4_0 as the single resident model for all three text slots, with thinking enabled only for `deliberate`.
    - **Image:** WAI-illustrious-SDXL v17.0, the owner's choice in the predecessor project, with Pony Diffusion V6 XL and SDXL base 1.0 as alternatives.
    - **Default runtime:** Codex stays the default narration runtime. The local text preset is opt-in. Image generation defaults to the local backend because no agent runtime supplies images.
11. **Schedule.** P08 designs the task port slot-aware from the start. A new package P21 follows the memory work (P09), so P10 to P17 can be built and played with free inference, and P17.6 requires its image backend. P18 hardening waits for P20 and P21.

## Not decided

- Per-task routing or failover between backends.
- A ComfyUI workflow adapter.
- Multi-GPU or remote GPU scheduling.
- Model training, fine-tuning or automatic downloads.
- Making a local model the default narration runtime.

## Alternatives

- **Ollama as the managed server.** Easier model management, but it adds a resident daemon with its own store and update cadence. It remains fully usable as a custom `openai-compatible` endpoint.
- **vLLM.** Built for throughput with many users. It needs more memory headroom than this host has and is unnecessary for a single owner.
- **Bundling Forge or ComfyUI as the image server.** They are AGPL-3.0 and GPL-3.0 applications with extension systems and a large attack surface. They stay available as owner-run servers through `a1111-http`.
- **One fixed model.** Rejected because the owner asked for freedom.
- **Cloud-only, or automatic fallback across classes.** Rejected because of cost, privacy and the rule against silent switching.

## Consequences

- A larger test matrix, hardware-dependent behavior and variable quality. Weaker models meet the typed-proposal design: constrained decoding, validation, one repair and receipt fallback.
- Model licenses, notices and content tags become a documented burden. The image defaults are NSFW-capable checkpoints, so safe-content defaults and review are mandatory.
- The reference host has one 16 GB module and three free slots. A second matching module would restore dual-channel memory. No purchase is assumed.
- Docker GPU passthrough on Windows must be proven, since the llama.cpp Docker guide says WSL support has not been verified.
- Every speed, memory and quality figure is an estimate until P21 records measurements.

## Implementation status

- P21.1 (28 October 2026, task open) implements the catalog and advisor parts of decisions 3 and 4 and the hardware profile. It adds `./start.sh --models profile [--gpu-check]`, the pinned llama.cpp image `server-cuda-b11960` by digest, catalog revision 1 with eight entries whose nine files were downloaded and hashed, strict parsers, the advisor and six launcher-generated profile fixtures. On the reference host profile, the advisor recommends the defaults of decision 10. Docker GPU passthrough on the reference host has not yet been run, so it is still unproven ([specification section 5](../34-inference-backends-and-model-selection.md#5-catalog-and-hardware-advisor)).
- P21.2-B (31 October 2026, task open) implements the fetch part of decision 5, tested on fixtures only. The launcher keeps only the confirmation. Catalog parsing, download and hashing run in one-off Compose services on a pinned Python image, because ADR 0009 allows no JSON parser, HTTP client or hash tool on the host. A second service with no network shows the license, so the service with egress starts only after confirmation. Confirmation applies to every license class; `--accept-license` names the accepted IDs for a recorded owner decision ([specification section 5](../34-inference-backends-and-model-selection.md#p212-model-fetch)).
- P08.4 (28 October 2026) implements decisions 2, 3 and 8 for the text slots against deterministic doubles: per-slot backend, preferred model and effort, the owner's allowlist, same-backend degradation alternatives and `fixedQuality`, resolved for every task ([resolution order](../06-codex-integration.md#p084-profile-resolution)). The `image` slot, class acknowledgements, the settings API and the other backends are not implemented.
- P21.3-A (31 October 2026, task open) implements the slot, class and fallback parts of decisions 2 and 8 in the backend: the four slots are stored (migration V27), each assignment has a billing and privacy class, a class change is refused until acknowledged, a fallback must be listed and stay in the class of the primary, and readiness probes have stored, visible results ([specification section 12](../34-inference-backends-and-model-selection.md#p213-a-slot-settings-class-badges-and-probes)). It adds `GET /api/v1/models/status`, `PUT /api/v1/settings/models` and `POST /api/v1/models/probe`, tested against deterministic doubles and PostgreSQL. No adapter other than Codex is installed, so only that backend can probe `ready`; the panel, the gateway, per-slot session retirement and budgets are not implemented.
- P21.3-B (31 October 2026, task open) implements the `local` adapter of decision 1 and per-slot sessions under decision 2, tested against a scripted HTTP server only. A routing port sends each task to the adapter of its slot's backend and never falls back. The adapter keeps each session's conversation in memory, because the chat API has none, and its output passes through the same contract runner as any other. A changed assignment retires only that slot's sessions, recorded with the new `SLOT_CHANGED` value of migration V28 ([specification section 12](../34-inference-backends-and-model-selection.md#p213-b-local-text-adapter-and-per-slot-session-retirement)). No real model server has been reached, and Compose and the launcher do not select the adapter yet.
- P21.3-C.1 (31 October 2026, task open) implements the container part of decision 6, tested against a scripted upstream only. Destinations and keys are read-only host paths mounted at run time, so the image holds neither. The engine names a destination by identifier and can never name a host. Responses are buffered whole, so an oversized one is a clean refusal, and nothing streams yet. The body of a non-2xx answer is discarded, because providers echo key fragments in errors ([specification section 12](../34-inference-backends-and-model-selection.md#p213-c1-gateway-container)). No adapter calls it, and budgets and the launcher's key command are not implemented. The decision's "the engine has no Internet route" is not yet true of the packaged stack: the engine's `web` network remains an ordinary bridge, because Docker publishes no port from an internal network.
- P21.3-C.2 (31 October 2026, task open) implements the budget part of decision 6, tested against a scripted upstream only. The gateway knows destinations, not slots, so a metered slot is a destination with its own caps. The unit is the relayed call, because counting tokens or images would need the protocol parsing that is not built. A call is written to disk before any contact and counts even if it fails, because the gateway cannot tell what the provider billed. A damaged count file stops metered destinations rather than resetting, because a reset would silently reopen spending. The counts live in a named volume, the service's only writable path. A reached cap answers 429 with a fixed code and contacts nothing ([specification section 12](../34-inference-backends-and-model-selection.md#p213-c2-gateway-budgets)). No adapter reads these answers yet.
- P21.4-A (31 October 2026, task open) implements the policy and the durable lease record of the shared admission in [specification section 10](../34-inference-backends-and-model-selection.md#p214-a-admission-coordinator-core), tested without a model server. Priority reuses the scheduler's work classes rather than a fixed modality order, equal classes rotate across modalities and campaigns, and one lease per resident model is shared by compatible requests. A lease of an earlier process stays reserved as reconciling after a restart until its model is confirmed gone, because the engine cannot see what the model server still holds. Load and unload calls, diffusion interruption, unowned contention, metrics and the out-of-memory proof are not built.
- P21.4-B (31 October 2026, task open) implements the lease sequence of decision 7 in [specification section 10](../34-inference-backends-and-model-selection.md#p214-b-lease-executor-over-the-model-server), tested against scripted fixtures only. The text model stays warm without a lease and is evicted only when a requested model does not fit beside it, so ordinary turns pay no swap. A reload nobody requested fits on paper beside warm models or waits, and never evicts, because two text models that do not fit together would otherwise swap on every pass. Expired capacity is held until an unload is confirmed, because freeing it earlier could load a second model beside the first. An unknown memory reading refuses a batch but not a text load, because text keeps the game playable. Metrics are an in-process map of fixed names and numbers, because no route is needed until something wires the executor and the actuator stays limited to health. The control interface is neutral so the local adapter package keeps its boundary. Nothing constructs the executor yet, and the out-of-memory proof is not done.

## Verification

- P21.1 to P21.6, with QA-43 (slots, backends, advisor, endpoint and secret safety) and QA-44 (local generation under shared admission).
- Evidence is recorded on the reference host. No measured number is claimed before then.
