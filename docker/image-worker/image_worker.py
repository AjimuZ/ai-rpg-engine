"""Generate images from single-file SDXL safetensors checkpoints and LoRA files on the local GPU.

Runs as the Compose service `image-worker` (docs/34-inference-backends-and-model-selection.md
section 8). The engine is its only peer, over an internal network:

    GET  /healthz       the process serves; never a loaded or usable model
    GET  /v1/models     the recommended checkpoint, its alternatives and the installed files
    POST /v1/load       {"checkpoint": catalog ID or file name; omitted means the recommended one}
    POST /v1/unload     {}
    POST /v1/generate   {"prompt", "negativePrompt", "width", "height", "steps", "guidanceScale",
                         "seed", "loras": [{"name", "weight"}]}; only "prompt" is required

Guarantees:

- only regular files inside the model directory are opened. Each one must pass the safetensors
  header check before a library sees it, so pickle or truncated files never reach a loader;
- no library is allowed to go online: configurations and tokenizers come from the directory
  baked into the image, and every load passes `local_files_only`;
- a prompt is never truncated. Tokens are split into chunks that the text encoders each accept,
  every token lands in exactly one chunk in order, and a prompt that needs more than
  `MAX_CHUNKS` chunks is refused with `prompt-too-long`;
- the safe-content defaults are applied to every job. A request has no field that changes them
  and unknown fields are refused; only the owner's `IMAGE_CONTENT_POLICY=owner-private` turns
  them off, and every answer reports the policy in force. Under `safe`, a prompt tag that the
  defaults forbid is refused rather than overridden;
- one job runs at a time. Load, unload and generate answer 409 `busy` during another job, while
  `/healthz` and `/v1/models` keep answering;
- every refusal carries one fixed code. Log lines hold fixed codes and counts, never a prompt.

Limits: the check on prompt tags compares whole comma-separated tags, so it is no content filter;
that stays engine-side (docs/34 section 6). Weight syntax such as `(tag:1.2)` is not interpreted:
its characters reach the encoders as text. Cancellation, output validation and provenance are
P21.5-B: a started job runs to its end.

Exit status: 0 stopped by SIGTERM or a passing `--health`; 1 a failing `--health`; 78 a refused
configuration.

Only the standard library is imported here at startup. `SdxlBackend` imports the heavy libraries
when it first loads a model, so the health check stays fast and the rules below can be tested
without them.
"""

import argparse
import base64
from collections.abc import Callable, Iterator, Mapping
import contextlib
from dataclasses import dataclass
import gc
import http.client
import http.server
import io
import json
import os
from pathlib import Path
import re
import secrets
import signal
import sys
import threading
from typing import NoReturn, Protocol, TextIO


MODELS_DIRECTORY = "/models"
# Configurations and tokenizers of SDXL base 1.0, baked into the image by checksum.
CONFIG_DIRECTORY = "/opt/image-worker/sdxl-config"
LORA_SUBDIRECTORY = "loras"
LISTEN_PORT = 8080
POLICY_VARIABLE = "IMAGE_CONTENT_POLICY"
POLICY_SAFE = "safe"
POLICY_OWNER_PRIVATE = "owner-private"

MAX_REQUEST_BYTES = 1024 * 1024
MAX_DRAINED_BYTES = 4 * MAX_REQUEST_BYTES
MAX_PROMPT_CHARACTERS = 8192
READ_SECONDS = 30
HEALTH_SECONDS = 2
HEALTH_FAILED_STATUS = 1
CONFIG_STATUS = 78

# A CLIP encoder takes 77 tokens: a start token, 75 of content and an end token.
CLIP_TOKEN_LIMIT = 77
CHUNK_TOKENS = CLIP_TOKEN_LIMIT - 2
# A chunk ends after a comma found this close to its end, so that a tag stays whole.
COMMA_BACKTRACK_TOKENS = 20
MAX_CHUNKS = 8

# The recommended presets are 832x1216 and 1024x1344 (docs/34 section 8).
DEFAULT_WIDTH = 832
DEFAULT_HEIGHT = 1216
MIN_SIDE = 512
MAX_SIDE = 1536
SIDE_STEP = 8
DEFAULT_STEPS = 24
MAX_STEPS = 60
DEFAULT_GUIDANCE = 6.0
MAX_GUIDANCE = 12.0
MAX_SEED = 2 ** 32 - 1
MAX_LORAS = 4
MAX_LORA_WEIGHT = 2.0

# Same rule as model_fetch.py, with the only admitted extension: no path separator can pass.
FILE_NAME_RULE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,159}\.safetensors")
MAX_HEADER_BYTES = 64 * 1024 * 1024
HEADER_LENGTH_BYTES = 8
# Every single-file SDXL checkpoint holds its second text encoder under this prefix.
SDXL_CHECKPOINT_PREFIX = "conditioner.embedders.1.model."

GENERATE_FIELDS = {"prompt", "negativePrompt", "width", "height", "steps", "guidanceScale", "seed", "loras"}

SAFE_NEGATIVE_TAGS = ("nsfw", "explicit", "sensitive", "nude", "nudity", "sexual content")
# Compared after `tag_key`, which folds case, underscores and weight syntax.
FORBIDDEN_UNDER_SAFE = frozenset({
    "nsfw", "explicit", "sensitive", "questionable", "nude", "nudity", "sex", "sexual content", "uncensored",
    "rating explicit", "rating questionable", "rating sensitive", "rating:explicit", "rating:questionable",
    "rating:sensitive",
})


class WorkerError(Exception):
    """A refusal or failure with its HTTP status and one fixed code."""

    def __init__(self, status: int, code: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code


@dataclass(frozen=True)
class CheckpointProfile:
    """What the worker knows about one checkpoint: its catalog identity and default tags.

    `catalog_id` and the file names equal `backend/.../inference/model-catalog.json`, which
    `test_image_worker.py` compares. `rating_tags` and `safe_negative` exist only under the safe
    policy; the quality tags belong to the checkpoint and apply under both.
    """

    catalog_id: str | None
    file_name: str | None
    vae_file: str | None
    quality_tags: tuple[str, ...]
    rating_tags: tuple[str, ...]
    quality_negative: tuple[str, ...]
    safe_negative: tuple[str, ...]


WAI_ILLUSTRIOUS = CheckpointProfile(
    "wai-illustrious-sdxl-v17", "waiIllustriousSDXL_v170.safetensors", None,
    ("masterpiece", "best quality", "amazing quality"), ("general",),
    ("bad quality", "worst quality", "worst detail", "sketch"), SAFE_NEGATIVE_TAGS)
# Pony's checkpoint needs its separate VAE, and it has its own score and rating vocabulary.
PONY_V6 = CheckpointProfile(
    "pony-diffusion-v6-xl", "ponyDiffusionV6XL_v6StartWithThisOne.safetensors", "sdxl_vae.safetensors",
    ("score_9", "score_8_up", "score_7_up"), ("rating_safe",),
    ("score_4", "score_5", "score_6"), ("rating_explicit", "rating_questionable", *SAFE_NEGATIVE_TAGS))
# A neutral baseline trained on natural language: rating tags mean nothing to it, so only its
# negative prompt carries the safe defaults.
SDXL_BASE = CheckpointProfile(
    "sdxl-base-1.0", "sd_xl_base_1.0.safetensors", None,
    ("high quality",), (), ("low quality", "blurry"), SAFE_NEGATIVE_TAGS)
# A file the owner placed in the model directory: nothing is known about its vocabulary.
OWNER_CHECKPOINT = CheckpointProfile(None, None, None, (), ("general",), (), SAFE_NEGATIVE_TAGS)

RECOMMENDED = WAI_ILLUSTRIOUS
ALTERNATIVES = (PONY_V6, SDXL_BASE)


def tag_key(tag: str) -> str:
    """Return the form in which two tags compare equal: no weight syntax, case or underscores."""
    bare = re.sub(r":\s*-?[\d.]+\s*(?=[)\]}]*$)", "", tag.strip()).strip("()[]{} ")
    return " ".join(bare.lower().replace("_", " ").split())


def split_tags(text: str) -> list[str]:
    """Return the comma-separated tags of `text`, stripped and without empty ones."""
    return [tag.strip() for tag in text.split(",") if tag.strip()]


def merged_tags(defaults: tuple[str, ...], text: str) -> str:
    """Return the defaults then the caller's tags, without repeats; a default is never displaced.

    The defaults come first so that they fall in the first chunk, whatever the prompt's length.
    """
    seen: set[str] = set()
    kept = []
    for tag in (*defaults, *split_tags(text)):
        key = tag_key(tag)
        if key not in seen:
            seen.add(key)
            kept.append(tag)
    return ", ".join(kept)


def compose_prompts(profile: CheckpointProfile, prompt: str, negative: str, policy: str) -> tuple[str, str]:
    """Return the positive and negative text to encode under `policy`.

    Raises `WorkerError` 422 `prompt-conflicts-with-safe-defaults` when, under the safe policy,
    a positive tag asks for what the defaults forbid, which would otherwise cancel them out.
    """
    if policy != POLICY_SAFE:
        return (merged_tags(profile.quality_tags, prompt), merged_tags(profile.quality_negative, negative))
    if any(tag_key(tag) in FORBIDDEN_UNDER_SAFE for tag in split_tags(prompt)):
        raise WorkerError(422, "prompt-conflicts-with-safe-defaults")
    return (merged_tags((*profile.rating_tags, *profile.quality_tags), prompt),
            merged_tags((*profile.safe_negative, *profile.quality_negative), negative))


def split_tokens(tokens: list[int], comma: int) -> list[list[int]]:
    """Split content tokens into chunks of at most `CHUNK_TOKENS`, without losing or moving one.

    Joining the chunks always gives `tokens` back, and an empty input gives one empty chunk. A
    chunk ends after the last comma of its final `COMMA_BACKTRACK_TOKENS` tokens, or at the limit
    when there is none, so a long list of tags breaks between tags.
    """
    chunks = []
    start = 0
    while len(tokens) - start > CHUNK_TOKENS:
        end = start + CHUNK_TOKENS
        for index in range(end - 1, end - 1 - COMMA_BACKTRACK_TOKENS, -1):
            if tokens[index] == comma:
                end = index + 1
                break
        chunks.append(tokens[start:end])
        start = end
    chunks.append(tokens[start:])
    return chunks


def frame_chunk(chunk: list[int], tokenizer: "Tokenizer") -> list[int]:
    """Return one encoder row of exactly `CLIP_TOKEN_LIMIT` ids with this tokenizer's own padding.

    The padding differs by encoder in SDXL: the first pads with its end token, the second with
    `!` (id 0), so a row is only valid for the tokenizer that framed it.
    """
    padding = [tokenizer.pad_token_id] * (CHUNK_TOKENS - len(chunk))
    return [tokenizer.bos_token_id, *chunk, tokenizer.eos_token_id, *padding]


class Tokenizer(Protocol):
    """The part of a CLIP tokenizer that chunking needs."""

    bos_token_id: int
    eos_token_id: int
    pad_token_id: int

    def __call__(self, text: str, add_special_tokens: bool, truncation: bool): ...


def content_tokens(tokenizer: Tokenizer, text: str) -> list[int]:
    """Return the ids of `text` with no special token and no truncation."""
    return list(tokenizer(text, add_special_tokens=False, truncation=False).input_ids)


@dataclass(frozen=True)
class TokenRows:
    """Encoder rows of one job. All four hold the same number of rows, each `CLIP_TOKEN_LIMIT` long."""

    positive_first: list[list[int]]
    positive_second: list[list[int]]
    negative_first: list[list[int]]
    negative_second: list[list[int]]


def token_rows(first: Tokenizer, second: Tokenizer, positive: str, negative: str) -> TokenRows:
    """Tokenize both prompts for both SDXL encoders, with equal chunk counts.

    Conditional and unconditional embeddings must have equal lengths, so the shorter side gets
    empty chunks. The chunk boundaries come from the first tokenizer, which is why the second
    must produce the same ids; a difference fails with `tokenizers-disagree` instead of pairing
    misaligned chunks. More than `MAX_CHUNKS` chunks fail with `prompt-too-long`.
    """
    comma = content_tokens(first, ",")[0]
    sides = []
    for text in (positive, negative):
        tokens = content_tokens(first, text)
        if content_tokens(second, text) != tokens:
            raise WorkerError(500, "tokenizers-disagree")
        sides.append(split_tokens(tokens, comma))
    count = max(len(chunks) for chunks in sides)
    if count > MAX_CHUNKS:
        raise WorkerError(422, "prompt-too-long")
    positive_chunks, negative_chunks = (chunks + [[]] * (count - len(chunks)) for chunks in sides)
    return TokenRows([frame_chunk(chunk, first) for chunk in positive_chunks],
                     [frame_chunk(chunk, second) for chunk in positive_chunks],
                     [frame_chunk(chunk, first) for chunk in negative_chunks],
                     [frame_chunk(chunk, second) for chunk in negative_chunks])


def embed_rows(torch, text_encoder, text_encoder_2, rows_first: list[list[int]], rows_second: list[list[int]], device: str):
    """Return the `(embeddings, pooled)` tensors of one prompt, as SDXL's own encoding builds them.

    Each chunk is encoded alone. Both encoders' penultimate hidden states join along the feature
    axis and the chunks along the sequence axis, giving shape `[1, 77 * chunks, features]`. The
    pooled vector is the second encoder's for the first chunk, which holds the default tags.
    """
    first = text_encoder(torch.tensor(rows_first, device=device), output_hidden_states=True)
    second = text_encoder_2(torch.tensor(rows_second, device=device), output_hidden_states=True)
    joined = torch.cat([first.hidden_states[-2], second.hidden_states[-2]], dim=-1)
    return joined.reshape(1, -1, joined.shape[-1]), second[0][:1]


def check_safetensors_header(prefix: bytes, file_size: int) -> dict:
    """Return the parsed header of a safetensors file, or raise `WorkerError` 422 `not-safetensors`.

    `prefix` starts at the file's first byte and must hold the whole header. The format is an
    8-byte little-endian length, then a JSON object of tensors with `data_offsets`. The tensors
    must end exactly at `file_size`, which also refuses a truncated download. A pickle file fails
    on its length or its JSON.
    """
    refused = WorkerError(422, "not-safetensors")
    if len(prefix) < HEADER_LENGTH_BYTES:
        raise refused
    length = int.from_bytes(prefix[:HEADER_LENGTH_BYTES], "little")
    if length < 2 or length > MAX_HEADER_BYTES or len(prefix) < HEADER_LENGTH_BYTES + length:
        raise refused
    try:
        header = json.loads(prefix[HEADER_LENGTH_BYTES:HEADER_LENGTH_BYTES + length])
    except ValueError:
        raise refused from None
    if not isinstance(header, dict):
        raise refused
    tensors_end = None
    for name, entry in header.items():
        if name == "__metadata__":
            continue
        offsets = entry.get("data_offsets") if isinstance(entry, dict) else None
        if (not isinstance(offsets, list) or len(offsets) != 2
                or not all(type(offset) is int for offset in offsets) or not 0 <= offsets[0] <= offsets[1]):
            raise refused
        tensors_end = max(tensors_end or 0, offsets[1])
    if tensors_end is None or HEADER_LENGTH_BYTES + length + tensors_end != file_size:
        raise refused
    return header


def read_safetensors_header(path: Path) -> dict:
    """Read and check the header of `path`; see `check_safetensors_header` for what is refused."""
    with path.open("rb") as file:
        prefix = file.read(HEADER_LENGTH_BYTES)
        if len(prefix) == HEADER_LENGTH_BYTES:
            length = int.from_bytes(prefix, "little")
            if length <= MAX_HEADER_BYTES:
                prefix += file.read(length)
        return check_safetensors_header(prefix, os.fstat(file.fileno()).st_size)


@dataclass(frozen=True)
class GenerationSettings:
    """Validated settings of one job."""

    width: int
    height: int
    steps: int
    guidance_scale: float
    seed: int


@dataclass(frozen=True)
class LoraChoice:
    """One checked LoRA file and its strength."""

    path: Path
    weight: float


class Backend(Protocol):
    """The GPU boundary of the worker; `SdxlBackend` is the real one."""

    def load(self, checkpoint: Path, vae: Path | None) -> None: ...

    def unload(self) -> None: ...

    def tokenizers(self) -> tuple[Tokenizer, Tokenizer]: ...

    def generate(self, rows: TokenRows, settings: GenerationSettings, loras: list[LoraChoice]) -> bytes: ...


class SdxlBackend:
    """Run StableDiffusionXLPipeline on the GPU from single-file checkpoints.

    This is the only code that imports torch and diffusers. Requires a CUDA device: a host
    without one gets 503 `gpu-unavailable`, because the CPU would take minutes per step. Other
    failures surface as fixed codes, never as a library's message, which can quote file names.
    """

    def __init__(self, config_directory: str = CONFIG_DIRECTORY, device: str = "cuda") -> None:
        self._config_directory = config_directory
        self._device = device
        self._pipeline = None
        self._torch = None

    def load(self, checkpoint: Path, vae: Path | None) -> None:
        """Make this checkpoint resident, replacing no other: the caller unloads first.

        The configuration directory is what keeps this offline: without it the loader would
        look the configuration up online, and `local_files_only` alone can fall back to that.
        """
        import torch
        from diffusers import AutoencoderKL, EulerAncestralDiscreteScheduler, StableDiffusionXLPipeline

        if self._device == "cuda" and not torch.cuda.is_available():
            raise WorkerError(503, "gpu-unavailable")
        common = {"config": self._config_directory, "local_files_only": True, "torch_dtype": torch.float16}
        try:
            components = {}
            if vae is not None:
                components["vae"] = AutoencoderKL.from_single_file(str(vae), subfolder="vae", **common)
            pipeline = StableDiffusionXLPipeline.from_single_file(str(checkpoint), **common, **components)
            pipeline.scheduler = EulerAncestralDiscreteScheduler.from_config(pipeline.scheduler.config)
            pipeline.set_progress_bar_config(disable=True)
            pipeline.to(self._device)
        except torch.cuda.OutOfMemoryError:
            raise WorkerError(507, "gpu-out-of-memory") from None
        except Exception:
            raise WorkerError(422, "checkpoint-load-failed") from None
        self._torch = torch
        self._pipeline = pipeline

    def unload(self) -> None:
        """Drop the pipeline and return its cached GPU memory; a no-op when nothing is loaded."""
        torch = self._torch
        self._pipeline = None
        self._torch = None
        gc.collect()
        if torch is not None and self._device == "cuda":
            torch.cuda.empty_cache()
            torch.cuda.ipc_collect()

    def tokenizers(self) -> tuple[Tokenizer, Tokenizer]:
        """Return the two tokenizers of the loaded pipeline; the caller ensures one is loaded."""
        return self._pipeline.tokenizer, self._pipeline.tokenizer_2

    def generate(self, rows: TokenRows, settings: GenerationSettings, loras: list[LoraChoice]) -> bytes:
        """Return one PNG. The job's LoRAs are removed afterwards, even after a failure.

        LoRAs are loaded before encoding, because a LoRA can change the text encoders too.
        """
        torch = self._torch
        pipeline = self._pipeline
        adapters = []
        try:
            for index, lora in enumerate(loras):
                adapter = f"job-lora-{index}"
                pipeline.load_lora_weights(str(lora.path.parent), weight_name=lora.path.name, adapter_name=adapter,
                                           local_files_only=True)
                adapters.append(adapter)
            if adapters:
                pipeline.set_adapters(adapters, adapter_weights=[lora.weight for lora in loras])
            with torch.no_grad():
                positive, positive_pooled = embed_rows(torch, pipeline.text_encoder, pipeline.text_encoder_2,
                                                       rows.positive_first, rows.positive_second, self._device)
                negative, negative_pooled = embed_rows(torch, pipeline.text_encoder, pipeline.text_encoder_2,
                                                       rows.negative_first, rows.negative_second, self._device)
            image = pipeline(
                prompt_embeds=positive, pooled_prompt_embeds=positive_pooled,
                negative_prompt_embeds=negative, negative_pooled_prompt_embeds=negative_pooled,
                width=settings.width, height=settings.height, num_inference_steps=settings.steps,
                guidance_scale=settings.guidance_scale,
                generator=torch.Generator(device=self._device).manual_seed(settings.seed),
            ).images[0]
            encoded = io.BytesIO()
            image.save(encoded, format="PNG")
            return encoded.getvalue()
        except torch.cuda.OutOfMemoryError:
            raise WorkerError(507, "gpu-out-of-memory") from None
        except Exception:
            raise WorkerError(500, "generation-failed") from None
        finally:
            if adapters:
                pipeline.delete_adapters(adapters)


def whole_number(body: dict, name: str, default: int, low: int, high: int) -> int:
    """Return an integer field within bounds or its default; anything else is `invalid-<name>`."""
    value = body.get(name, default)
    if type(value) is not int or not low <= value <= high:
        raise WorkerError(400, f"invalid-{name.lower()}")
    return value


def number(value: object, low: float, high: float, code: str) -> float:
    """Return a finite number within bounds as a float; anything else fails with `code`."""
    if type(value) not in (int, float) or not low <= value <= high:
        raise WorkerError(400, code)
    return float(value)


def prompt_text(body: dict, name: str) -> str:
    """Return a bounded string field, or an empty string when absent."""
    value = body.get(name, "")
    if not isinstance(value, str):
        raise WorkerError(400, f"invalid-{name.lower()}")
    if len(value) > MAX_PROMPT_CHARACTERS:
        raise WorkerError(422, "prompt-too-long")
    return value


def generation_settings(body: dict) -> GenerationSettings:
    """Validate the numeric fields of a job; a missing seed gets a random one that is reported back."""
    sides = []
    for name, default in (("width", DEFAULT_WIDTH), ("height", DEFAULT_HEIGHT)):
        side = whole_number(body, name, default, MIN_SIDE, MAX_SIDE)
        if side % SIDE_STEP:
            raise WorkerError(400, f"invalid-{name}")
        sides.append(side)
    seed = body["seed"] if "seed" in body else secrets.randbelow(MAX_SEED + 1)
    if type(seed) is not int or not 0 <= seed <= MAX_SEED:
        raise WorkerError(400, "invalid-seed")
    return GenerationSettings(sides[0], sides[1], whole_number(body, "steps", DEFAULT_STEPS, 1, MAX_STEPS),
                              number(body.get("guidanceScale", DEFAULT_GUIDANCE), 1.0, MAX_GUIDANCE, "invalid-guidancescale"),
                              seed)


def event(stream: TextIO, name: str, **fields: object) -> None:
    """Write one JSON log line. Callers pass fixed codes and counts only, never a prompt."""
    stream.write(json.dumps({"event": name, **fields}) + "\n")
    stream.flush()


class ImageWorker:
    """The worker's rules, behind the HTTP surface and in front of one `Backend`."""

    def __init__(self, models_directory: Path, backend: Backend, policy: str, log: TextIO = sys.stderr) -> None:
        self._models = models_directory
        self._backend = backend
        self._policy = policy
        self._log = log
        self._job = threading.Lock()
        self._loaded: CheckpointProfile | None = None
        self._loaded_file: str | None = None

    @contextlib.contextmanager
    def _exclusive(self) -> Iterator[None]:
        """Hold the single job slot, or refuse with 409 `busy`: a queue belongs to the coordinator."""
        if not self._job.acquire(blocking=False):
            raise WorkerError(409, "busy")
        try:
            yield
        finally:
            self._job.release()

    def _model_file(self, directory: Path, name: str, missing: str) -> Path:
        """Return a checked file of `directory` by bare name, with its header already validated.

        Refuses a name with a separator, a symbolic link leaving the directory and anything but a
        regular safetensors file. `missing` is the code for a file that is not there.
        """
        if not FILE_NAME_RULE.fullmatch(name):
            raise WorkerError(400, "invalid-file-name")
        path = directory / name
        try:
            if not path.is_file():
                raise WorkerError(404, missing)
            if not path.resolve().is_relative_to(self._models.resolve()):
                raise WorkerError(422, "outside-model-directory")
            read_safetensors_header(path)
        except OSError:
            raise WorkerError(422, "not-safetensors") from None
        return path

    def _installed(self, directory: Path) -> list[str]:
        """Return the sorted names in `directory` that pass the name rule; headers are not read here."""
        if not directory.is_dir():
            return []
        return sorted(path.name for path in directory.iterdir() if FILE_NAME_RULE.fullmatch(path.name) and path.is_file())

    def describe(self) -> dict:
        """Return the recommended checkpoint, its alternatives, the installed files and what is loaded."""
        return {
            "recommended": RECOMMENDED.catalog_id,
            "alternatives": [profile.catalog_id for profile in ALTERNATIVES],
            "contentPolicy": self._policy,
            "checkpoints": self._installed(self._models),
            "loras": self._installed(self._models / LORA_SUBDIRECTORY),
            "loaded": self._loaded_file,
        }

    def _profile(self, requested: str | None) -> tuple[CheckpointProfile, str]:
        """Return the profile and file name of a catalog ID, a file name or the recommended default."""
        if requested is None:
            return RECOMMENDED, RECOMMENDED.file_name
        for profile in (RECOMMENDED, *ALTERNATIVES):
            if requested in (profile.catalog_id, profile.file_name):
                return profile, profile.file_name
        if not FILE_NAME_RULE.fullmatch(requested):
            raise WorkerError(400, "invalid-file-name")
        return OWNER_CHECKPOINT, requested

    def load(self, body: dict) -> dict:
        """Make a checkpoint resident, and its separate VAE when its profile names one.

        A different resident checkpoint is unloaded first, because two never fit together on the
        reference host. A file that fails its checks leaves the resident model in place.
        """
        require_fields(body, {"checkpoint"})
        requested = body.get("checkpoint")
        if requested is not None and not isinstance(requested, str):
            raise WorkerError(400, "invalid-checkpoint")
        profile, name = self._profile(requested)
        with self._exclusive():
            path = self._model_file(self._models, name, "checkpoint-missing")
            if not any(key.startswith(SDXL_CHECKPOINT_PREFIX) for key in read_safetensors_header(path)):
                raise WorkerError(422, "not-sdxl-checkpoint")
            vae = self._model_file(self._models, profile.vae_file, "vae-missing") if profile.vae_file else None
            if self._loaded_file != name:
                self._backend.unload()
                self._loaded = self._loaded_file = None
                self._backend.load(path, vae)
                self._loaded, self._loaded_file = profile, name
            event(self._log, "loaded", catalog=profile.catalog_id is not None, separate_vae=vae is not None)
            return {"loaded": name, "catalogId": profile.catalog_id, "contentPolicy": self._policy}

    def unload(self, body: dict) -> dict:
        """Release the resident checkpoint; answering is the same when none was resident."""
        require_fields(body, set())
        with self._exclusive():
            self._backend.unload()
            self._loaded = self._loaded_file = None
            event(self._log, "unloaded")
            return {"loaded": None}

    def _loras(self, requested: object) -> list[LoraChoice]:
        """Validate the job's LoRA list against the files of the LoRA subdirectory."""
        if requested is None:
            return []
        if not isinstance(requested, list) or len(requested) > MAX_LORAS:
            raise WorkerError(400, "invalid-loras")
        choices = []
        for entry in requested:
            if not isinstance(entry, dict) or set(entry) - {"name", "weight"} or not isinstance(entry.get("name"), str):
                raise WorkerError(400, "invalid-loras")
            weight = number(entry.get("weight", 1.0), -MAX_LORA_WEIGHT, MAX_LORA_WEIGHT, "invalid-loras")
            choices.append(LoraChoice(self._model_file(self._models / LORA_SUBDIRECTORY, entry["name"], "lora-missing"), weight))
        return choices

    def generate(self, body: dict) -> dict:
        """Run one job on the resident checkpoint and return its PNG, base64-encoded, with its facts."""
        require_fields(body, GENERATE_FIELDS)
        prompt = prompt_text(body, "prompt")
        if not prompt.strip():
            raise WorkerError(400, "invalid-prompt")
        negative = prompt_text(body, "negativePrompt")
        settings = generation_settings(body)
        with self._exclusive():
            if self._loaded is None:
                raise WorkerError(409, "not-loaded")
            positive_text, negative_text = compose_prompts(self._loaded, prompt, negative, self._policy)
            loras = self._loras(body.get("loras"))
            first, second = self._backend.tokenizers()
            rows = token_rows(first, second, positive_text, negative_text)
            image = self._backend.generate(rows, settings, loras)
            event(self._log, "generated", chunks=len(rows.positive_first), loras=len(loras), steps=settings.steps)
            return {
                "imagePngBase64": base64.b64encode(image).decode("ascii"),
                "checkpoint": self._loaded_file,
                "catalogId": self._loaded.catalog_id,
                "contentPolicy": self._policy,
                "seed": settings.seed,
                "width": settings.width,
                "height": settings.height,
                "steps": settings.steps,
                "guidanceScale": settings.guidance_scale,
                "chunks": len(rows.positive_first),
            }


def require_fields(body: object, allowed: set[str]) -> None:
    """Refuse a body that is not an object or holds an unknown field.

    This is what makes the safe defaults impossible to turn off by request: a field asking for
    that is refused here instead of being quietly ignored.
    """
    if not isinstance(body, dict):
        raise WorkerError(400, "invalid-request")
    if set(body) - allowed:
        raise WorkerError(400, "unknown-field")


class Handler(http.server.BaseHTTPRequestHandler):
    """Map the routes of the module header onto one `ImageWorker`; every answer is JSON."""

    server: "WorkerServer"
    protocol_version = "HTTP/1.1"
    timeout = READ_SECONDS

    def log_message(self, format: str, *args: object) -> None:
        """Say nothing: the default line would print request paths without the fixed-code rule."""

    def answer(self, status: int, document: dict) -> None:
        """Send one JSON document and close, so that no partly read request can linger."""
        body = json.dumps(document).encode("utf-8")
        self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def run(self, action: Callable[[], dict]) -> None:
        """Answer with what `action` returns, or with its fixed code; other faults answer 500."""
        try:
            status, document = 200, action()
        except WorkerError as refusal:
            event(self.server.log, "refused", code=refusal.code, status=refusal.status)
            status, document = refusal.status, {"error": refusal.code}
        except Exception:
            event(self.server.log, "failed", code="internal-error")
            status, document = 500, {"error": "internal-error"}
        self.answer(status, document)

    def json_body(self) -> object:
        """Read a bounded JSON body; an empty one means an empty object."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            raise WorkerError(400, "invalid-request") from None
        if length < 0:
            raise WorkerError(400, "invalid-request")
        if length > MAX_REQUEST_BYTES:
            # Closing with unread bytes resets the connection before the client sees the answer.
            self.rfile.read(min(length, MAX_DRAINED_BYTES))
            raise WorkerError(413, "request-too-large")
        raw = self.rfile.read(length)
        if not raw:
            return {}
        try:
            return json.loads(raw)
        except ValueError:
            raise WorkerError(400, "invalid-request") from None

    def do_GET(self) -> None:
        """Serve the health and model routes; neither needs the job slot."""
        worker = self.server.worker
        if self.path == "/healthz":
            self.answer(200, {"status": "serving"})
        elif self.path == "/v1/models":
            self.run(worker.describe)
        else:
            self.answer(404, {"error": "unknown-route"})

    def do_POST(self) -> None:
        """Serve the job routes; each one takes the single job slot."""
        routes = {"/v1/load": self.server.worker.load, "/v1/unload": self.server.worker.unload,
                  "/v1/generate": self.server.worker.generate}
        route = routes.get(self.path)
        if route is None:
            self.answer(404, {"error": "unknown-route"})
            return
        self.run(lambda: route(self.json_body()))


class WorkerServer(http.server.ThreadingHTTPServer):
    """Serve one worker. Threads keep `/healthz` answering while a job holds the GPU."""

    daemon_threads = True

    def __init__(self, address: tuple[str, int], worker: ImageWorker, log: TextIO) -> None:
        super().__init__(address, Handler)
        self.worker = worker
        self.log = log

    def handle_error(self, request: object, client_address: object) -> None:
        """Log a dropped connection by code: the default traceback can quote request data."""
        event(self.log, "connection-failed")


def content_policy(environ: Mapping[str, str]) -> str:
    """Return the owner's policy, `safe` when unset; any other value raises ValueError at startup."""
    policy = environ.get(POLICY_VARIABLE) or POLICY_SAFE
    if policy not in (POLICY_SAFE, POLICY_OWNER_PRIVATE):
        raise ValueError(POLICY_VARIABLE)
    return policy


def health(port: int) -> int:
    """Return 0 when the local listener answers its health path, whatever a model's state."""
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=HEALTH_SECONDS)
    try:
        connection.request("GET", "/healthz")
        return 0 if connection.getresponse().status == 200 else HEALTH_FAILED_STATUS
    except (OSError, http.client.HTTPException):
        return HEALTH_FAILED_STATUS
    finally:
        connection.close()


def stop_on_signal(_signal: int, _frame: object) -> NoReturn:
    """End the serving loop promptly on a stop request."""
    raise SystemExit(0)


def main(arguments: list[str] | None = None) -> int:
    """Start serving, or return the exit status documented in the module header."""
    parser = argparse.ArgumentParser(description="Generate images from single-file SDXL checkpoints.")
    parser.add_argument("--models-dir", default=MODELS_DIRECTORY)
    parser.add_argument("--port", type=int, default=LISTEN_PORT)
    parser.add_argument("--health", action="store_true")
    options = parser.parse_args(arguments)
    if options.health:
        return health(options.port)
    models = Path(options.models_dir)
    try:
        policy = content_policy(os.environ)
    except ValueError:
        event(sys.stderr, "startup-refused", reason="the content policy is not safe or owner-private")
        return CONFIG_STATUS
    if not models.is_dir():
        event(sys.stderr, "startup-refused", reason="the model directory does not exist")
        return CONFIG_STATUS
    signal.signal(signal.SIGTERM, stop_on_signal)
    with WorkerServer(("0.0.0.0", options.port), ImageWorker(models, SdxlBackend(), policy), sys.stderr) as server:
        event(sys.stderr, "started", content_policy=policy)
        server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
