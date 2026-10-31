# Third-Party Notices and Heritage

Project software and documentation are distributed under the [MIT License](LICENSE), copyright 2026 Azim Ben Othman. The notices below preserve upstream attribution and do not override separate terms for third-party material.

## The World I Can Develop

AI RPG Engine's design builds primarily on the architecture and product analysis of [The World I Can Develop](https://github.com/AjimuZ/the-world-i-can-develop), supplied locally by the project owner. Preserve the following notice with distributions that reuse its licensed material. The notice applies to that upstream material and is retained alongside the project's root MIT license.

```text
MIT License

Copyright (c) 2026 Azim Ben Othman

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Local demo

The owner-supplied `the-world-i-can-develop-demo` informed the analysis of Codex lifecycle, memory renewal, request handling, knowledge projections, and media behavior. Its code, private campaign, images, audio catalog, runtime logs, and backup files are not bundled here. No standalone license file was observed in its root. Establish explicit rights before copying any of those materials into a future public release.

## Apache Maven Wrapper

`backend/mvnw` and `backend/mvnw.cmd` come from the official Apache Maven Wrapper 3.3.4
only-script distribution, produced by the Apache Maven Wrapper plugin. `mvnw.cmd` is
unchanged. `backend/mvnw` is a **modified copy** (29 October 2026): without `unzip` it
still downloads and verifies the checksum-pinned ZIP and extracts it with the JDK `jar`
tool, it names the reason when no download can run, and it runs an installed launcher
through its `#!` interpreter when the launcher cannot be executed directly (for example
on a `noexec` mount). Its header lists the changes
and each changed block is marked "Local change", as section 4(b) of the license
requires; the rationale is in [ADR 0009](docs/adr/0009-toolchain-baseline.md).
Preserve the upstream headers and the accompanying [Apache License 2.0](backend/.mvn/wrapper/LICENSE)
and [NOTICE](backend/.mvn/wrapper/NOTICE). The scripts are build tooling, separate
from the project's MIT-licensed application code. Source:
[Apache Maven Wrapper 3.3.4](https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4).

The Angular CLI 22.2.3 scaffold supplies the initial workspace configuration under
its MIT license (Copyright Google LLC). The application template has been replaced
with the neutral project shell. Downloaded npm/Maven dependencies retain their
individual licenses in their distributions; the Angular production build also
emits its third-party license inventory. The backend `packaged-ui` profile retains
that inventory as `/3rdpartylicenses.txt` alongside the compiled browser assets
in the executable jar. Preserve it with distributions. No dependency binary is
committed here.

## Other references

Official documentation is linked for technical and rights background in [the reference register](docs/19-glossary-and-references.md). No OpenAI SDK, Codex binary, D&D/SRD rule text, external stat block, third-party image, audio file, font, or model checkpoint is distributed in the tracked source tree.

Future dependencies and assets require their own license/provenance inventory. Mentioning a technology or an inspiration does not imply affiliation or transfer of rights.

## Local inference models and runtimes — nothing bundled

Recorded 31 October 2026 (P21.6-A). The [model catalog](docs/34-inference-backends-and-model-selection.md#5-catalog-and-hardware-advisor)
and the container profiles name the items below. **No model weight, checkpoint, LoRA, VAE or runtime binary is committed to this
repository.** The owner fetches each model explicitly with `./start.sh --models fetch`, which prints the licence, class, source and
attribution first and needs a confirmation, and the container images are built or pulled on the owner's machine. The project's MIT
license does not relicense any of this. "Source" below means read from the cited page or API on the date in
[docs/34 section 2](docs/34-inference-backends-and-model-selection.md#2-verified-facts-and-sources); a licence file in a built image
was **not** read, because no image has been built.

| Item | Declared terms | Where it appears | Obligation carried |
| --- | --- | --- | --- |
| [Gemma 4](https://huggingface.co/google/gemma-4-12B-it) 12B, E4B, E2B and 26B-A4B, Google's 4-bit `qat-q4_0` GGUF builds | Apache-2.0 (model card and file headers) | Catalog entries; recommended text default is the 12B build | Keep the licence and the Google attribution shown by the fetch command |
| [Qwen3.5-9B](https://huggingface.co/Qwen/Qwen3.5-9B), community quantization [unsloth/Qwen3.5-9B-GGUF](https://huggingface.co/unsloth/Qwen3.5-9B-GGUF) at commit `3885219b` | Apache-2.0 | Catalog alternate text entry | As above; credit the original model and the third-party quantization separately |
| [WAI-illustrious-SDXL](https://civitai.com/models/827184) v17.0, derived from Illustrious XL | [FAIPL-1.0-SD](https://freedevproject.org/faipl-1.0-sd/), **inherited** from the base model's card; the WAI page's own licence text could not be read, so this is an unconfirmed basis | Catalog entry and recommended image default; NSFW-capable, so the safe-content defaults apply | Copies of the model must carry the licence and its source; modifications stay under the same or a more permissive licence; the prohibited-use list applies; generated images are not covered by the licence. Confirm the page's own terms before any public redistribution of a prepared model |
| [Illustrious XL v0.1](https://huggingface.co/OnomaAIResearch/Illustrious-xl-early-release-v0) | FAIPL-1.0-SD (model card) | Basis for the WAI entry only; not in the catalog | Same as above |
| [SDXL base 1.0](https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0) | OpenRAIL++ | Catalog alternate image entry | Carry the licence and its use restrictions to anyone receiving the weights |
| [Pony Diffusion V6 XL](https://civitai.com/models/257749) and a separate SDXL VAE | Modified FAIPL-1.0-SD, class `restricted`: inference on websites or applications that allow any form of monetization is forbidden, and credit is required | Catalog alternate image entry; never a default | Do not enable it in a monetized deployment; the fetch command asks for confirmation whatever the class |
| llama.cpp server image `ghcr.io/ggml-org/llama.cpp:server-cuda-b11960` | Licence **not read in this change**. Confirm it from the pinned image or the upstream repository before distributing the image or a derivative | Model server container pinned by digest in [docs/34 section 2](docs/34-inference-backends-and-model-selection.md#2-verified-facts-and-sources) | Pulled by the owner; nothing is copied into this repository |
| Image worker packages in `docker/image-worker/requirements.lock`: torch 2.15.0+cu132, diffusers 0.42.1, transformers 5.22.1, safetensors 0.8.0, accelerate 1.15.0, peft, pillow and their dependencies, plus CUDA runtime wheels | **Not inventoried.** Each package carries its own licence, and the CUDA wheels are under NVIDIA's own terms. The pinned list exists, but the image has not been built, so no licence file was read | Built on the owner's machine from hash-pinned wheels | Before any image is distributed, produce the full per-package licence inventory from the built image and add it here |
| A user-run Forge or A1111 server reached through the `a1111-http` gateway profile | Owner-installed software; its terms are the owner's to read. The engine only sends HTTP requests to it | Optional destination, no code or binary copied | None for this repository |

Attribution for a model the owner installs goes to the credits registry of [presentation assets](docs/23-presentation-assets.md).
This section is a record of what was known and what was not on 31 October 2026, not a legal opinion.

## Music research references — no bundled recordings

The [dynamic music specification](docs/24-dynamic-music-and-credits.md) records two candidate works by Kevin MacLeod (incompetech.com): [Wholesome](https://commons.wikimedia.org/wiki/File:Wholesome_by_Kevin_MacLeod.ogg), declared [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), and [Darkest Child, ISRC USUAN1100783](https://commons.wikimedia.org/wiki/File:Darkest_Child_%28ISRC_USUAN1100783%29.mp3), declared [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Source/file-page declarations were checked on 23 October 2026. These are reference-only credits: no recording is copied, modified, installed or played by this change. Before runtime admission, retain the selected recording's complete rights evidence, exact license version, source and any processing notice. The project's MIT license does not relicense that music.
