# Inference Backends and Model Selection

## 1. Status and scope

**Design specification only.** [ADR 0021](adr/0021-inference-backends-and-model-selection.md) adds free local models, API-key backends and owner-chosen model slots to the engine. Nothing here is implemented. Package P21 delivers it, and until then every speed, memory and quality figure is an estimate or an upper bound.

The goal is to **recommend** a good default for the detected hardware while leaving the owner **maximum freedom** to choose any other model. The engine therefore fixes the protocols it can speak and leaves the models and endpoints open. It does not fix the models.

This document covers the backends, slots, catalog, local servers, gateway and shared admission. It builds on [Claude runtime integration](33-claude-runtime-integration.md) and [Codex integration](06-codex-integration.md) for the agent runtimes. It also builds on [local speech](30-local-speech-synthesis.md) for the shared GPU policy and on [presentation assets](23-presentation-assets.md) for image jobs.

## 2. Verified facts and sources

*Measured* means read from the reference host on 25 October 2026. *Source* means read from the linked official page or API the same day. *Estimate* means computed or assumed and not measured.

| Fact | Basis |
| --- | --- |
| Reference host: Intel Core i5-14600KF (14 cores, 20 threads); one 16 GB DDR5-5600 module in 1 of 4 slots (15.8 GB usable, single channel, 3 slots free); NVIDIA GeForce RTX 4070 with 12,282 MiB VRAM, compute capability 8.9, driver 617.42; 1 TB SSD with 655 GB free; Windows 11 Pro build 26300. | Measured |
| It is a physical machine (MSI PRO B760-P II board, vendor firmware) and no virtual machine was running. The "hypervisor present" flag is the virtualization layer WSL 2 uses. | Measured |
| The desktop alone held about 1.3 GB of VRAM. Windows reports only 4 GB of adapter memory through its management interface (a 32-bit field), so the hardware probe must read VRAM from the GPU driver tool, not from that field. | Measured |
| WSL 2 has no `.wslconfig`, so its VM defaults to half the RAM (about 8 GB). Docker Desktop was stopped, and container GPU passthrough is unverified on this host. | Measured |
| [Gemma 4 12B](https://huggingface.co/google/gemma-4-12B-it): Apache-2.0; 11.95B dense parameters; 256K context; text, image and audio input with text output; system role; structured tool use; a switchable thinking mode; "35+ languages" out of the box and "140+" in pre-training (Italian is not named); MMMLU 83.4% and MMLU Pro 77.2% as reported on the card; no memory table. | Source |
| [Google's 4-bit build](https://huggingface.co/google/gemma-4-12B-it-qat-q4_0-gguf), updated 2026-07-17: `gemma-4-12b-it-qat-q4_0.gguf`, 6,975,879,296 bytes, SHA-256 `93567e57a8fe10b23569b9d9ec38cd005deedf71e29477c421a4b83f418a538b`, Apache-2.0. A separate 0.2 GB projector file serves multimodal input and is not needed for text. | Source |
| Other Gemma 4 builds from Google, all Apache-2.0: [E2B](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf) 3,349,516,256 bytes (SHA-256 `fa401b55…ec6634`); [E4B](https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf) 5,154,941,280 bytes (`676c3507…fbaee`); [26B-A4B](https://huggingface.co/google/gemma-4-26B-A4B-it-qat-q4_0-gguf) 14.4 GB. | Source |
| [Qwen3.5](https://huggingface.co/Qwen/Qwen3.5-9B) is Apache-2.0 in 0.8B, 2B, 4B, 9B, 27B, 35B-A3B and larger sizes. No GGUF is published by Qwen. Community builds such as [unsloth/Qwen3.5-9B-GGUF](https://huggingface.co/unsloth/Qwen3.5-9B-GGUF) exist. | Source |
| [WAI-illustrious-SDXL](https://civitai.com/models/827184) v17.0 (2026-04-23): one fp16 safetensors file of 6,775,430 KiB with the VAE integrated, SHA-256 `f116b0c78ff441467b0cdc8f1936e1ed18ea31e9997c7b132b1b8db533f0bd04`. The page recommends Euler a, 15–30 steps, CFG 5–7 and at least 1024 px. Its flags allow commercial use of generated images, derivatives and no credit. It is marked NSFW-capable (rating tags general, sensitive, nsfw, explicit). | Source |
| [Pony Diffusion V6 XL](https://civitai.com/models/257749): file SHA-256 `67ab2fd8ec439a89b3fedb15cc65f54336af163c7eb5e4f2acc98f090a29b0b3`, with a separate SDXL VAE. Its "modified Fair AI Public License 1.0-SD" forbids running inference on websites or applications that allow any form of monetization and requires credit. | Source |
| [FAIPL-1.0-SD](https://freedevproject.org/faipl-1.0-sd/): outputs are not covered by the license; copies must carry the license and the source (models included); modifications stay under the same or a more permissive license; a list of prohibited uses applies to the model and derived models; the license calls itself not a free software license. | Source |
| [SDXL base 1.0](https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0): `openrail++` license, ungated, `sd_xl_base_1.0.safetensors` 6,938,078,334 bytes, SHA-256 `31e35c80fc4829d14f90153f4c74cd59c90b779f6afe05a74cd6120b893f7e5b`. | Source |
| [llama.cpp server](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md): router mode (`--models-dir`, `--models-preset`, `--models-max`, `--models-autoload`); `POST /models/load` and `/models/unload`; per-model status including `sleeping`; `response_format`, `json_schema` and `grammar` for constrained output; `cache_prompt`, `--cache-reuse` and slot save/restore; draft-model speculative decoding; `--api-key-file`; reasoning controls; OpenAI-compatible routes. | Source |
| [llama.cpp Docker guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/docker.md): images `ghcr.io/ggml-org/llama.cpp:server-cuda` (CUDA 12.8.1) and `:server-cuda13`. "WSL support has not been verified yet." | Source |
| Memory bandwidth bounds: RTX 4070 GDDR6X 504 GB/s (NVIDIA specification); single-channel DDR5-5600 44.8 GB/s (5600 MT/s × 8 bytes). | Estimate |

## 3. Reference host and recommended defaults

VRAM budget: 12,282 MiB minus about 1,270 MiB for the desktop minus a 1 GiB safety margin leaves roughly 10 GiB for one model plus its context and buffers. The 12B build has 6.5 GiB of weights. A decode upper bound is bandwidth divided by weight size: 504 ÷ 6.98 ≈ **72 tokens/s** (estimate, an unreachable ceiling). Real speed is measured in P21.2.

| Slot | Recommended default | Why | Alternatives |
| --- | --- | --- | --- |
| `text.standard` | Gemma 4 12B-it QAT Q4_0 (Google GGUF) | Apache-2.0; official 4-bit training build; fits with room for context; 256K window; system role, tool-use format and thinking mode on the card; strong reported multilingual score | Qwen3.5-9B (community GGUF, to pin in P21.1); any compatible GGUF |
| `text.light` | The same resident model, short outputs, no thinking | Two models do not fit together (7.0 + 3.3 GB plus buffers exceed the free VRAM), and swapping costs seconds | Gemma 4 E2B on CPU if RAM allows; a hosted small model |
| `text.deliberate` | The same model with thinking on and a larger budget | Same reason | An agent runtime or an API model chosen by the owner |
| `image` | WAI-illustrious-SDXL v17.0 (the owner's choice in the predecessor project) | Fits alone in VRAM; anime and fantasy styles; fp16 single file; VAE integrated | Pony Diffusion V6 XL (owner's secondary choice); SDXL base 1.0 as a neutral baseline; the owner's own Forge server |

Does not fit this host for local use: Gemma 4 26B-A4B (14.4 GB), Qwen3.6 27B and 35B-A3B. A 16 GB single-channel memory system makes CPU offload slow and tight. The advisor lists them as "needs more VRAM or RAM" and still allows them.

Presets the advisor can propose for this host (the owner picks):

- **Local-only:** every text slot uses the resident local model, images use the local worker. It is free and offline once the files are fetched.
- **Hybrid:** `light` and `standard` use the local model, `deliberate` uses an agent runtime or an API model.
- **Cloud-first (current default):** Codex for text and the local worker for images, because no agent runtime supplies images.

Expectations to set honestly: a 12B local model is weaker than the agent runtimes at long-range consistency and strict formats. The design absorbs this with constrained decoding, typed validation, one repair and the deterministic receipt fallback. Narration quality in English and Italian is evaluated in P21.6 and is not assumed.

## 4. Backends and slots

| Slot | Weight class and tasks (engine-defined; the owner picks the model) |
| --- | --- |
| `text.light` | Fast, short, mostly structured tasks: intent interpretation, quick replies, simple tactics |
| `text.standard` | Ordinary narration, dialogue and clarifications |
| `text.deliberate` | Encounter proposals, summaries, quest plans and other high-impact or long tasks |
| `image` | Scene, portrait and item artwork through the asset jobs of [presentation assets](23-presentation-assets.md) |

| Protocol adapter | Serves | Class | Notes |
| --- | --- | --- | --- |
| `codex`, `claude` | Text slots | subscription | Agent runtimes of [ADR 0020](adr/0020-selectable-narration-runtime.md) with their own sign-in and terms gates |
| `local` | Text slots | local | Managed llama.cpp server, section 7 |
| `openai-compatible` | Text slots | local or metered | OpenAI's API, hosted providers, or the owner's own server (Ollama, LM Studio, vLLM and similar) through the gateway |
| `anthropic-api` | Text slots | metered | Messages API with the owner's key through the gateway. This is the route Anthropic recommends for products |
| `local-image` | `image` | local | Managed image worker, section 8 |
| `a1111-http` | `image` | local | The owner's Forge, A1111 or compatible server, through the gateway |
| `openai-images` | `image` | metered | Hosted images API with the owner's key |

A slot assignment is the backend, a model identifier, parameters (context size, sampling, thinking) and an optional same-class fallback list. The engine sets the effective model and parameters explicitly on every task and records the backend, model, file hash (local) and parameters with each AI task and each generated asset. Capability levels are recorded per model: `schema-enforced` (grammar or native schema output), `prompted-json` (works through validation and repair) and `prose-only` (usable for narration slots that need no structure).

## 5. Catalog and hardware advisor

- **Catalog.** A versioned data file in the repository. Each entry has: id, kind (text or image), source URL and revision, files with size and SHA-256, license identifier and URL, notice and attribution text, requirements (VRAM, RAM, disk, context), languages, capabilities, intended slots, content notes, and a measured profile once P21 records one. Unverified or non-redistributable entries are excluded from defaults.
- **Hardware profile.** The launcher writes `.local/hardware.json` from the GPU driver tool, the operating system and the container engine: GPU, VRAM, RAM and module layout, disk, and whether a container can see the GPU. The owner can read it, and it contains no identifiers beyond hardware models.
- **Advisor rules.** A text model fits when its weights plus the KV cache for the chosen context plus compute buffers stay within free VRAM minus the margin. An image model fits when checkpoint plus activations at the chosen resolution do. The advisor prefers entries with a measured profile, then Apache-2.0, MIT or OpenRAIL-style licenses, then language coverage. It ranks, explains and never blocks.
- **Synthetic profiles** (no GPU, 6 GB, 8 GB, 12 GB with 16 GB RAM, 24 GB) are test fixtures for QA-43.

## 6. Freedom, probes and hard limits

- **Open by default.** The owner may drop any GGUF or safetensors file into the model directory, name a Hugging Face file with its hash, point to a compatible endpoint, or enter an API key. The engine runs the readiness probe, shows the result, records the hash as "trust on first use" when none is pinned, and labels the model unverified.
- **Readiness probe.** A neutral task set checks the output contract for the slot, English and Italian text, a context-length sample and latency. A failed contract blocks `standard` and `deliberate` for structured tasks, and the owner can still use a prose-only model for narration. Probe results never certify quality.
- **Hard limits.** No pickle checkpoints, remote code, custom nodes or extensions in managed backends. Model servers have no network egress. Endpoints must be on the owner's allowlist (HTTPS, or loopback and private addresses after explicit confirmation), and redirects to other hosts are refused. Keys, endpoints and downloads come only from owner actions, never from packs, prompts or model output. No backend is offered tools.
- **Not delegated to the model.** Schema validation, audience isolation, content policy and authority rules stay engine-side, whatever the model's own tuning.

## 7. Local text server profile

- **Image and runtime.** The pinned llama.cpp `server-cuda` image, chosen by digest in P21.1. Non-root, read-only root filesystem, dropped capabilities, `no-new-privileges`, a private network and no route to the Internet or PostgreSQL. Weights are on a read-only volume.
- **Candidate launch profile.** Router mode with `--models-dir`; `--models-max 1` on this host; all layers on the GPU; flash attention; quantized KV cache and a context cap taken from the measured profile; `--cache-reuse` and a slot-save path for prompt-cache reuse; `--api-key-file` for the engine-to-server hop; `--jinja` and per-request reasoning controls.
- **Constrained output.** Structured tasks send a JSON Schema as `response_format`. Constrained decoding guarantees syntax, not meaning, so the engine's semantic validators still run.
- **Thinking.** Off for `light` and `standard`, on with a token budget for `deliberate`. Recommended sampling for Gemma 4 is temperature 1.0, top-p 0.95 and top-k 64, adjustable per slot.
- **Open points for P21.1 and P21.2.** GPU passthrough through Docker Desktop and WSL 2; whether unloading frees VRAM completely; Gemma 4 support in the pinned build; structured output together with thinking.

## 8. Local image worker profile

- **Worker.** A pinned container with PyTorch for CUDA and diffusers (Apache-2.0), loading single-file SDXL safetensors checkpoints and LoRA files from the read-only model volume. Same hardening as section 7, no network. It follows the pattern of the speech worker in [local speech](30-local-speech-synthesis.md).
- **Generation.** Euler ancestral sampling, 20–25 steps and CFG 5–7 for the recommended checkpoint, with 832×1216 or 1024×1344 presets. Tag-style prompts exceed 77 tokens, so P21.5 pins a prompt-chunking helper. Cancellation is checked between steps. Output is validated and hashed by the asset pipeline.
- **Content policy.** The recommended checkpoint can render explicit content. Default prompts add the `general` rating tag and the quality prefix, and the negative prompt includes explicit-content terms. The owner may change them for private use. The repository ships no generated art without review ([publication](18-github-publication.md)).
- **Attribution and licenses.** Source, hash, license and notice are stored and shown. The engine never redistributes weights. CivitAI may require the owner's sign-in or an API token for the WAI-illustrious download. P21.2 verifies this, and the fetch command takes any token from the owner's environment for that one command and never stores it.
- **Bring your own server.** The `a1111-http` adapter drives an owner-run Forge or A1111 server over its documented HTTP API through the gateway. Its VRAM use is not coordinated, so the engine only serializes its own requests and reports contention. Forge itself is not packaged.

## 9. Gateway: hosted APIs and custom endpoints

- A single `gateway` container joins an egress network and a private network shared only with the engine. Keys are runtime-only secret files, set with a launcher command that never echoes them. They never enter the database, the frontend, logs or images.
- It speaks `openai-compatible`, `anthropic-api`, `openai-images` and `a1111-http`. It enforces the destination allowlist, request and response bounds, time budgets, no cookies and no cross-host redirects. It sets the model and parameters on every call and applies the retry and quota rules of [asynchronous planning](28-asynchronous-narrative-planning.md).
- **Budgets.** A metered slot has a daily and a monthly cap kept by the gateway. Reaching a cap stops that slot with a clear status. It never moves the work to another class.
- **Starting points** for hosted text, to be validated by the probe: Anthropic Haiku 5.5, Sonnet 5.5 and Opus 5.5 for light, standard and deliberate; OpenAI models as the owner configures. Identifiers are owner settings, not product constants.

## 10. Shared admission and speed

On the 12 GB host one heavy model is GPU-resident at a time. The coordinator of [local speech §8](30-local-speech-synthesis.md) is extended to text.

- **Priorities.** Interactive text (intent, narration, tactics) first; image for the scene being read; speech; background summaries and planning; pre-generation last.
- **Lease.** An image or speech job asks for a lease. The coordinator saves the narration slot's prompt cache, unloads the text model with `POST /models/unload`, confirms the VRAM was freed, runs the batch, reloads with `POST /models/load` and restores the cache. Image batches are grouped to pay the swap once, and a lease has a time cap.
- **Interruption.** A diffusion job checks for interactive demand between steps and yields. No lease starts while a text request is queued or expected within the reading window.
- **Profiles.** Admission needs a measured VRAM and load-time profile for the exact model and precision. An unprofiled model is admitted only alone, with extra headroom.
- **Unowned contention.** The owner's games or other applications reduce free VRAM. The coordinator rechecks, delays or falls back to text-only play or catalog art. It never terminates another process.
- **Memory.** Keep models fully on the GPU; CPU offload is slow here. The launcher advises on WSL memory and never changes WSL, Docker or driver settings.

Techniques to evaluate in P21 (none is a promised gain): prompt-cache reuse across turns with stable prefixes; constrained JSON with short `max_tokens` for light tasks; thinking only where it pays; quantized KV cache and flash attention; optional draft-model speculative decoding where spare VRAM exists; a warm-up request after reload; image step and resolution presets; idle-time pre-generation of likely portraits; skipping AI entirely for structured player actions.

Optional [STT/hands-free input](35-speech-input-and-hands-free-play.md) also uses
this shared CPU/RAM/GPU admission; it adds no selected backend or model slot here.
P17.8 must qualify its exact recognition profile and finite audio buffers. Treat
capture/confirmation work as bounded interactive demand alongside interactive
text, without starving canonical work; suspend lower-priority media or disable
recognition when measured headroom/latency cannot be met. TTS readback priority,
echo suppression and any narration interruption require joint QA-45 evidence.

## 11. Privacy, classes and licenses

| Class | Where data goes | Cost |
| --- | --- | --- |
| `local` | Nowhere beyond the owner's machine (or LAN for a custom server) | Free, uses the owner's hardware |
| `subscription` | The agent runtime vendor | The owner's plan; limits apply |
| `metered` | The API vendor | Per token or per image, capped by the gateway |

The Settings panel shows the class of every slot. Changing a slot's class needs an acknowledgement, and fallback never crosses a class. Planner roles may see secret canon, so a slot that serves them shows when its class sends data off the machine. Model licenses are shown before a fetch, and non-permissive or custom licenses need confirmation. Attribution goes to the credits registry of [presentation assets](23-presentation-assets.md).

## 12. Configuration surfaces

- **`.env`:** `NARRATION_RUNTIME` (`codex`, `claude` or `local`) is the default backend of the text slots. Slot assignments live in private application settings. Keys live in `.local/secrets/`.
- **Text slot settings (P08.1 and P08.4, implemented):** `engine.inference.slots.<light|standard|deliberate>.model` and `.effort` (for example `ENGINE_INFERENCE_SLOTS_STANDARD_MODEL`) assign each text slot, with an optional `.backend` (default `engine.inference.backend`, itself `codex`) and optional `.alternatives` (owner-approved same-backend `model@effort` list). `engine.inference.allowlist.<backend>` is the owner's approved list and `engine.inference.fixed-quality` selects the `fixedQuality` slot. No model name is shipped as a default. A half-assigned slot or a malformed value fails startup without echoing it. For every task `ProfileResolver` applies the order of [Codex integration section 9](06-codex-integration.md#p084-profile-resolution): it refuses `slot-unassigned`, `slot-backend-mismatch`, `catalog-unavailable` or `slot-unsupported` before anything is sent, and otherwise sends the effective model and effort explicitly on every turn. The settings API, advisor and Settings → Models panel (P21.3) and the `image` slot are not part of this port yet; the backend and class badges, same-class fallback lists as a settings surface and probes also belong to P21.3.
- **Launcher (planned):** `./start.sh --models advise|list|fetch|verify|remove` and a secret-setting command. Fetch is the only download path, and it verifies SHA-256.
- **Compose (planned):** services `models`, `images` and `gateway`, started only when a slot needs them, on internal networks. Only the gateway and the one-off fetch container have egress.
- **API and UI:** `GET /api/v1/models/status`, the owner-only `PUT /api/v1/settings/models`, and the Settings → Models panel with advisor presets, per-slot assignment, probe results, class badges and budgets ([API and UI](13-api-and-ui.md)).
- **Model directory:** `.local/models/`, ignored by Git, mounted read-only into the model containers.

## 13. Delivery and verification

| Item | Content | Evidence |
| --- | --- | --- |
| P21.1 | Reference-host qualification, catalog schema, advisor | Hardware profile, GPU in container, verified catalog entries |
| P21.2 | Local text backend and measurements | Probes, bilingual probe set, recorded speed and memory |
| P21.3 | Slots, adapters, gateway, settings API and panel | QA-43 |
| P21.4 | Shared GPU and RAM admission | No engine-caused out-of-memory on the host |
| P21.5 | Local image backend and `a1111-http` | QA-44 |
| P21.6 | Parity, fixtures, bilingual evaluation, supported-defaults table | QA-43, QA-44 |

## 14. References

- Model pages and APIs linked in section 2 (Hugging Face, CivitAI), the FAIPL-1.0-SD text, and the llama.cpp server and Docker pages.
- [Docker GPU support for Windows](https://docs.docker.com/desktop/features/gpu/), already used by the speech specification.
- These sources describe other parties' models and software. The selection, profiles and policies here are this project's design, not a guarantee from any of them.
