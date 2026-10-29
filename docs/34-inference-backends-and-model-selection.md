# Inference Backends and Model Selection

## 1. Status and scope

**Design specification, partly implemented.** [ADR 0021](adr/0021-inference-backends-and-model-selection.md) adds free local models, API-key backends and owner-chosen model slots to the engine. Package P21 delivers it, and until it records measurements every speed, memory and quality figure is an estimate or an upper bound.

Implemented in P21.1 (28 October 2026, task still open): the launcher's hardware profile (`./start.sh --models profile [--gpu-check]`), the pinned model-server image reference, the catalog format with eight entries whose files were downloaded and hashed, the strict catalog and profile parsers, the advisor rules and the synthetic-profile fixtures (section 5). Not yet done: running the profile and the container GPU check on the reference host itself, so Docker GPU passthrough on WSL 2 and the free-VRAM figure seen by a container remain unproven. No model server, fetch command, slot setting, gateway or image worker exists yet.

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
| Pinned model-server image (28 October 2026): `ghcr.io/ggml-org/llama.cpp:server-cuda-b11960@sha256:4ccae195bf542f7844301ae06891baa32a7551cb16de8428af6e929403d4292c`, the multi-platform index (amd64 `sha256:ae676f2e…`, arm64 `sha256:1cb45220…`) that the moving `server-cuda` tag also pointed to that day. Its configuration: entrypoint `/app/llama-server`, CUDA 12.8.1, `NVIDIA_DRIVER_CAPABILITIES=compute,utility`, no `USER` (root by default), a `curl` health check, revision `8642541b`, built 28 October 2026; 13 layers, 2,599,012,436 bytes compressed. | Source (registry API) |
| At tag `b11960`, `--list-devices` prints `Available devices:` and one `  NAME: DESCRIPTION (TOTAL MiB, FREE MiB free)` line per non-CPU device, or `  (none)`, then exits 0 (`common/arg.cpp`). The architecture table names `gemma4` and `qwen35` (`src/llama-arch.cpp`). Loading these models in that build is not yet tested. | Source |
| GGUF headers (first bytes of each pinned file, 28 October 2026): Gemma 4 12B has 48 layers, 8 with global attention (1 KV head, key and value length 512) and 40 sliding-window layers (8 KV heads, length 256, window 1,024); E2B and E4B share the KV cache of their last 20 and 18 layers; 26B-A4B has 30 layers (5 global with 2 KV heads, 25 sliding-window with 8), 128 experts with 8 active; Qwen3.5-9B has 32 layers with full attention every fourth layer (4 KV heads, length 256) and a recurrent state elsewhere. All declare `apache-2.0`. | Source (file headers) |
| [unsloth/Qwen3.5-9B-GGUF](https://huggingface.co/unsloth/Qwen3.5-9B-GGUF) at commit `3885219b`: `Qwen3.5-9B-Q4_K_M.gguf`, 5,680,522,464 bytes, SHA-256 `03b74727…b7e8`, Apache-2.0. Neither the Gemma 4 nor the Qwen3.5 card names Italian or English as a supported language; both claim broad multilingual coverage. | Source |
| Every catalog file (nine files, 56,749,024,108 bytes) was downloaded on 28 October 2026 and its SHA-256 recomputed; each matched the publisher's digest and size. The two CivitAI checkpoints and the SDXL VAE downloaded without sign-in or token that day. | Measured (download) |
| WAI-illustrious-SDXL is an Illustrious XL derivative (CivitAI base model "Illustrious"); the [Illustrious XL v0.1 card](https://huggingface.co/OnomaAIResearch/Illustrious-xl-early-release-v0) declares FAIPL-1.0-SD. The WAI page's own license text could not be read through the API, so the catalog records the license as inherited from the base model. | Source |
| A development container on the reference host's WSL 2 VM (kernel `6.18.54.1-microsoft-standard-WSL2`, Intel Core i5-14600KF, 20 threads) saw 7,823 MiB of total memory and 5,825 MiB available, and no GPU device or driver tool. Its `./start.sh --models profile` run recorded exactly that. | Measured (28 October 2026) |

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

### P21.1 catalog format

The catalog is [`backend/src/main/resources/inference/model-catalog.json`](../backend/src/main/resources/inference/model-catalog.json), read by `org.rpgengine.inference.catalog.ModelCatalog`. The format `rpg-engine.model-catalog/1` is closed: an unknown, missing or duplicated field, a wrong type or an out-of-range value rejects the whole file with a rule code and a JSON Pointer.

| Field | Content and rule |
| --- | --- |
| `schema`, `revision`, `reviewed` | Format name, a revision raised with every content change, the date the source facts were checked |
| `entries[]` | In preference order; the advisor's last tie-break. `id` is lower case and unique |
| `kind`, `format` | `text` with `gguf`, or `image` with `safetensors`. No pickle or other executable format is admitted (ADR 0021 decision 9) |
| `source` | `host` (`huggingface` or `civitai`), `page`, `repository`, `revision` (a 40-digit commit, or a CivitAI model-version number) |
| `files[]` | `role` (`weights` exactly once, `vae`, `projector`), plain `name` with the format's extension, `url` on the source's own HTTPS host and pinned to the revision, exact `bytes`, lower-case `sha256`, `verification` (`download` = recomputed from the downloaded bytes, or `source-api` = publisher's digest only) with its date |
| `license` | `id`, `class`, `url`, `basis` (`model-card`, `model-page` or `base-model`), `confirmationRequired` (mandatory for every class except `permissive`) |
| `attribution` | Credit text for the credits registry |
| `requirements` | Text: `kvBytesPerToken`, `kvFixedBytes`, `computeBytes`, `contextMax`, `contextDefault`. Image: `activationBytes` at `resolution`. Both carry `basis` (`estimate` or `measured`) |
| `languages` | ISO 639-1 code to `unnamed` (broad multilingual claim), `named` (the publisher names the language) or `evaluated` (this project's P21.6 evaluation passed). `en` is required |
| `capabilities` | Text: `system-role`, `thinking`, `tool-use-format`, `schema-enforced-output` (through the local server's JSON-Schema grammar; still to be probed in P21.2). Image: `sdxl`, `vae-integrated`, `negative-prompt`, `lora` |
| `slots` | Text slots, or `image` |
| `contentNotes` | `nsfw-capable`, `sign-in-may-be-required`, `monetized-use-forbidden`, `third-party-quantization` |
| `measured` | `null` until a host measurement exists; then `date`, `host`, `peakVramBytes` and, for text, `context` |

License classes: `permissive` (Apache-2.0, MIT); `use-restricted` (open weights with a list of prohibited uses: OpenRAIL++ and FAIPL-1.0-SD); `restricted` (further conditions, such as Pony's ban on inference in monetized applications); `unknown`. `permissive` and `use-restricted` rank equally, as "Apache-2.0, MIT or OpenRAIL-style" above. Only those two classes can supply a default.

Catalog revision 1 (reviewed 28 October 2026). Every file was downloaded and hashed (section 2); no entry has a measured profile.

| Entry | Files | License (class) | Estimate inputs |
| --- | --- | --- | --- |
| `gemma-4-26b-a4b-it-qat-q4_0` | 14,439,363,584 bytes | Apache-2.0 (permissive) | 20,480 B/token, 314,572,800 fixed, 768 MiB compute, context 262,144 |
| `gemma-4-12b-it-qat-q4_0` | 6,975,879,296 | Apache-2.0 (permissive) | 16,384 B/token, 503,316,480 fixed, 768 MiB compute, context 262,144 |
| `qwen3.5-9b-q4_k_m` | 5,680,522,464 | Apache-2.0 (permissive), third-party quantization | 32,768 B/token, 52,690,944 fixed, 640 MiB compute, context 262,144 |
| `gemma-4-e4b-it-qat-q4_0` | 5,154,941,280 | Apache-2.0 (permissive) | 16,384 B/token, 41,943,040 fixed, 640 MiB compute, context 131,072 |
| `gemma-4-e2b-it-qat-q4_0` | 3,349,516,256 | Apache-2.0 (permissive) | 6,144 B/token, 12,582,912 fixed, 640 MiB compute, context 131,072 |
| `wai-illustrious-sdxl-v17` | 6,938,040,682 | FAIPL-1.0-SD via base model (use-restricted); NSFW-capable | 2,560 MiB activations at 1024x1024 |
| `sdxl-base-1.0` | 6,938,078,334 | OpenRAIL++ (use-restricted) | 2,560 MiB activations at 1024x1024 |
| `pony-diffusion-v6-xl` | 6,938,041,050 + VAE 334,641,162 | Modified FAIPL-1.0-SD (restricted); NSFW-capable | 2,560 MiB activations at 1024x1024 |

How the text estimates are derived from the GGUF headers (f16 cache): bytes per token = global-attention layers × KV heads × (key + value length) × 2. Fixed cache = sliding-window layers × KV heads × (key + value length) × 2 × (window + 512-token batch), or the recurrent state for Qwen3.5. Layers that reuse another layer's cache are not counted. Compute buffers are rough allowances dominated by the 262,144-token (Gemma) and 248,320-token (Qwen) vocabularies at a 512-token batch. Contexts default to 32,768. The image activation figure is a single rough allowance for SDXL at about one megapixel. P21.2 and P21.5 replace every one of these with measurements.

### P21.1 hardware profile

`./start.sh --models profile` writes `.local/hardware.json` (format `rpg-engine.hardware-profile/1`) and prints a two-line summary. `--gpu-check` adds the container check. It uses only Bash, the ADR 0009 utilities, `docker` and three optional probes: `nvidia-smi` (on `PATH`, or WSL's `/usr/lib/wsl/lib/nvidia-smi`), `powershell.exe` (Windows only) and `df`. A missing or failing probe is recorded and never aborts the command.

| Field | Source |
| --- | --- |
| `generatedAt`, `platform` | UTC time; `windows` (Git Bash), `wsl`, `linux`, `macos` or `other` |
| `cpu` | `model name` and the `processor` count in `/proc/cpuinfo` |
| `memory` | `MemTotal` and `MemAvailable` in MiB; `scope` is `vm` under WSL, where the kernel sees only the VM. On Windows, `modules` (capacity, configured speed) and `moduleSlots` come from the CIM classes `Win32_PhysicalMemory` and `Win32_PhysicalMemoryArray`; elsewhere both are `null` |
| `gpuSource`, `gpus[]` | `nvidia-smi --query-gpu=index,name,memory.total,memory.used,memory.free,driver_version,compute_cap --format=csv,noheader,nounits`. Never the Windows adapter field, which reports about 4 GB on the 12 GB card. `[N/A]` becomes `null` |
| `disk` | Free space under `.local` (`df -Pk`) |
| `containerEngine` | `docker info`: OS type, version, operating-system label, memory (the WSL 2 VM's under Docker Desktop) and CPUs |
| `containerGpu` | `not-checked` without `--gpu-check`. With it: the pinned image runs `--list-devices` with `--gpus all`, no network, a read-only root, no capabilities, `no-new-privileges` and user 65534, and is removed afterwards. A missing image is first pulled by digest (about 2.6 GB). `visible` = a CUDA device was listed (with its total and free memory as CUDA reports them inside the container); `not-visible` = the image ran and listed none, or Docker could not attach a GPU; `failed` = the pull or run failed otherwise; `engine-unavailable` = no Linux container engine answered |

The profile records hardware models, sizes and versions only: no host or user name, serial number, GPU UUID, address or absolute path. Six fixtures in `backend/src/test/resources/inference/hardware-profiles/` are generated by the launcher from recorded tool output, and a test keeps them byte-identical: `reference-host` replays the 25 October measurements, with free VRAM taken as total minus used (11,012 MiB), and with the WSL VM memory measured on 28 October; `reference-host-gpu-visible` adds a synthetic container answer (10,776 MiB free); `no-gpu`, `gpu-6gb`, `gpu-8gb` and `gpu-24gb` are synthetic. None of them is evidence about a real machine.

### P21.1 advisor rules

`org.rpgengine.inference.catalog.ModelAdvisor` is pure and deterministic. Defaults: 1,024 MiB VRAM margin, 3,072 MiB memory reserve for the engine, database and operating system, and a minimum text context of 16,384 tokens.

1. **GPU budget.** Free VRAM of the first GPU, taken from the container's CUDA view when `--gpu-check` saw one, otherwise from the driver tool (free, or total minus used), minus the margin. Free VRAM already excludes the desktop and other applications at profiling time. Several GPUs are not scheduled (ADR 0021, "Not decided").
2. **Memory budget.** The container engine's memory, otherwise system memory, minus the reserve.
3. **Text fit.** Files + fixed cache + compute + bytes per token × context. The largest context that fits, in steps of 1,024 tokens and at most `contextMax`, must reach the minimum. The proposed context is the smaller of that and `contextDefault`. The GPU is tried first (`GPU`), then memory (`CPU`, slow), otherwise `TOO_LARGE`.
4. **Image fit.** Files + activations within the GPU budget, otherwise `TOO_LARGE`. No CPU fallback.
5. **Ranking per kind.** Fit (`GPU`, `CPU`, `TOO_LARGE`); a measured profile first; license (`permissive` = `use-restricted`, then `restricted`, then `unknown`); English and Italian coverage (`evaluated` 3, `named` 2, `unnamed` 1, absent 0, summed); for `CPU` fits only, smaller files first, because decoding speed on the CPU is bounded by memory bandwidth divided by model size; then catalog order.
6. **Recommended default.** The first ranked entry that fits, has every digest verified from downloaded bytes and a `permissive` or `use-restricted` license. There may be none.
7. **Notes.** Every assessment explains itself: `CONTAINER_GPU_UNVERIFIED`, `CONTAINER_GPU_NOT_VISIBLE`, `CONTEXT_REDUCED`, `CPU_ONLY_SLOW`, `EXCEEDS_VRAM`, `EXCEEDS_MEMORY`, `ESTIMATE_ONLY`, `HASH_UNVERIFIED`, `LICENSE_CONFIRMATION`, `LICENSE_RESTRICTED`, `ITALIAN_UNCONFIRMED`, `NSFW_CAPABLE`. Host notes: `NO_GPU`, `GPU_TOOL_FAILED`, the container notes and `SINGLE_MEMORY_MODULE` (one module on a multi-slot board).
8. **Presets.** Local-only (the recommended text model in all three text slots, as one resident model, and the recommended image model or none), hybrid (local `light` and `standard`, `deliberate` on the agent runtime or an owner-chosen API) and cloud-first (all text on the runtime). Local-only and hybrid are unavailable without a recommended text model. Every entry is still listed, and the owner may choose any of them.

Results on the fixtures (all estimates):

| Profile | GPU budget | Recommended text | Recommended image | Notes |
| --- | --- | --- | --- | --- |
| `reference-host` | 9,988 MiB | Gemma 4 12B on the GPU, 32,768 tokens (8,821,373,056 bytes) | WAI-illustrious v17.0 | 26B-A4B too large; container GPU unverified; single module |
| `reference-host-gpu-visible` | 9,752 MiB | Gemma 4 12B | WAI-illustrious v17.0 | Single module |
| `no-gpu` | 0 | Gemma 4 E2B on the CPU | None | Every CPU fit, smallest first |
| `gpu-6gb` | 4,776 MiB | Gemma 4 E2B on the GPU | None | E4B, Qwen and 12B fit only on the CPU |
| `gpu-8gb` | 6,086 MiB | Gemma 4 E4B on the GPU, context reduced to 30,720 | None | |
| `gpu-24gb` | 22,616 MiB | Gemma 4 26B-A4B on the GPU | WAI-illustrious v17.0 | |

On the reference host the advisor therefore reproduces the ADR 0021 defaults. The 8 GB result marks no image checkpoint as fitting. That follows from the rough 2.5 GiB activation allowance, and P21.5 measures it.

## 6. Freedom, probes and hard limits

- **Open by default.** The owner may drop any GGUF or safetensors file into the model directory, name a Hugging Face file with its hash, point to a compatible endpoint, or enter an API key. The engine runs the readiness probe, shows the result, records the hash as "trust on first use" when none is pinned, and labels the model unverified.
- **Readiness probe.** A neutral task set checks the output contract for the slot, English and Italian text, a context-length sample and latency. A failed contract blocks `standard` and `deliberate` for structured tasks, and the owner can still use a prose-only model for narration. Probe results never certify quality.
- **Hard limits.** No pickle checkpoints, remote code, custom nodes or extensions in managed backends. Model servers have no network egress. Endpoints must be on the owner's allowlist (HTTPS, or loopback and private addresses after explicit confirmation), and redirects to other hosts are refused. Keys, endpoints and downloads come only from owner actions, never from packs, prompts or model output. No backend is offered tools.
- **Not delegated to the model.** Schema validation, audience isolation, content policy and authority rules stay engine-side, whatever the model's own tuning.

## 7. Local text server profile

- **Image and runtime.** The pinned llama.cpp `server-cuda` image: `server-cuda-b11960` by index digest since P21.1 (section 2). The image itself runs as root and has no `USER`, so P21.2 sets the user in Compose. Non-root, read-only root filesystem, dropped capabilities, `no-new-privileges`, a private network and no route to the Internet or PostgreSQL. Weights are on a read-only volume.
- **Candidate launch profile.** Router mode with `--models-dir`; `--models-max 1` on this host; all layers on the GPU; flash attention; quantized KV cache and a context cap taken from the measured profile; `--cache-reuse` and a slot-save path for prompt-cache reuse; `--api-key-file` for the engine-to-server hop; `--jinja` and per-request reasoning controls.
- **Constrained output.** Structured tasks send a JSON Schema as `response_format`. Constrained decoding guarantees syntax, not meaning, so the engine's semantic validators still run.
- **Thinking.** Off for `light` and `standard`, on with a token budget for `deliberate`. Recommended sampling for Gemma 4 is temperature 1.0, top-p 0.95 and top-k 64, adjustable per slot.
- **Open points.** GPU passthrough through Docker Desktop and WSL 2 (P21.1: the check exists, the reference-host run is pending); whether unloading frees VRAM completely; loading Gemma 4 and Qwen3.5 in the pinned build (their architectures are in its source); structured output together with thinking (P21.2).

## 8. Local image worker profile

- **Worker.** A pinned container with PyTorch for CUDA and diffusers (Apache-2.0), loading single-file SDXL safetensors checkpoints and LoRA files from the read-only model volume. Same hardening as section 7, no network. It follows the pattern of the speech worker in [local speech](30-local-speech-synthesis.md).
- **Generation.** Euler ancestral sampling, 20–25 steps and CFG 5–7 for the recommended checkpoint, with 832×1216 or 1024×1344 presets. Tag-style prompts exceed 77 tokens, so P21.5 pins a prompt-chunking helper. Cancellation is checked between steps. Output is validated and hashed by the asset pipeline.
- **Content policy.** The recommended checkpoint can render explicit content. Default prompts add the `general` rating tag and the quality prefix, and the negative prompt includes explicit-content terms. The owner may change them for private use. The repository ships no generated art without review ([publication](18-github-publication.md)).
- **Attribution and licenses.** Source, hash, license and notice are stored and shown. The engine never redistributes weights. CivitAI may require the owner's sign-in or an API token for the WAI-illustrious download. On 28 October 2026 both CivitAI checkpoints and the VAE downloaded anonymously (section 2), which can change. The fetch command (P21.2) takes any token from the owner's environment for that one command and never stores it.
- **Bring your own server.** The `a1111-http` adapter drives an owner-run Forge or A1111 server over its documented HTTP API through the gateway. Its VRAM use is not coordinated, so the engine only serializes its own requests and reports contention. Forge itself is not packaged.

## 9. Gateway: hosted APIs and custom endpoints

- A single `gateway` container joins an egress network and a private network shared only with the engine. Keys are runtime-only secret files, set with a launcher command that never echoes them. They never enter the database, the frontend, logs or images.
- It speaks `openai-compatible`, `anthropic-api`, `openai-images` and `a1111-http`. It enforces the destination allowlist, request and response bounds, time budgets, no cookies and no cross-host redirects. It sets the model and parameters on every call and applies the retry and quota rules of [asynchronous planning](28-asynchronous-narrative-planning.md).
- **Budgets.** A metered slot has a daily and a monthly cap kept by the gateway. Reaching a cap stops that slot with a clear status. It never moves the work to another class.
- **Starting points** for hosted text, to be validated by the probe: Anthropic Haiku 5.5, Sonnet 5.5 and Opus 5.5 for light, standard and deliberate; OpenAI models as the owner configures. Identifiers are owner settings, not product constants.

## 10. Shared admission and speed

On the 12 GB host one heavy model is GPU-resident at a time. The coordinator of [local speech §8](30-local-speech-synthesis.md) is extended to text.

- **Priorities.** Interactive text (intent, narration, tactics) first; image for the scene being read; speech; background summaries and planning; pre-generation last.
- **Text queue.** Text tasks already pass through the P08.6 fair scheduler, which owns their priority classes, the single text turn permit, quotas and restart reconciliation ([docs/06 section 12](06-codex-integration.md#p086-durable-fair-scheduling)). The coordinator sits behind that permit for text (a text task holds a GPU lease only while it holds the scheduler's permit) and adds image and speech leases beside it; it never creates a second text queue.
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
- **Launcher:** `./start.sh --models profile [--gpu-check]` is implemented (P21.1, section 5). Planned: `--models advise|list|fetch|verify|remove` and a secret-setting command. Fetch is the only model download path, and it verifies SHA-256. The `--gpu-check` pull of the pinned image is the only other download.
- **Compose (planned):** services `models`, `images` and `gateway`, started only when a slot needs them, on internal networks. Only the gateway and the one-off fetch container have egress.
- **API and UI:** `GET /api/v1/models/status`, the owner-only `PUT /api/v1/settings/models`, and the Settings → Models panel with advisor presets, per-slot assignment, probe results, class badges and budgets ([API and UI](13-api-and-ui.md)).
- **Model directory:** `.local/models/`, ignored by Git, mounted read-only into the model containers.

## 13. Delivery and verification

| Item | Content | Evidence |
| --- | --- | --- |
| P21.1 | Reference-host qualification, catalog schema, advisor | Hardware profile, GPU in container, verified catalog entries. Done: profile command and fixtures, catalog with downloaded-and-hashed files, advisor tests. Pending: the profile and GPU check run on the reference host |
| P21.2 | Local text backend and measurements | Probes, bilingual probe set, recorded speed and memory |
| P21.3 | Slots, adapters, gateway, settings API and panel | QA-43 |
| P21.4 | Shared GPU and RAM admission | No engine-caused out-of-memory on the host |
| P21.5 | Local image backend and `a1111-http` | QA-44 |
| P21.6 | Parity, fixtures, bilingual evaluation, supported-defaults table | QA-43, QA-44 |

## 14. References

- Model pages and APIs linked in section 2 (Hugging Face, CivitAI), the FAIPL-1.0-SD text, and the llama.cpp server and Docker pages.
- [Docker GPU support for Windows](https://docs.docker.com/desktop/features/gpu/), already used by the speech specification.
- These sources describe other parties' models and software. The selection, profiles and policies here are this project's design, not a guarantee from any of them.
