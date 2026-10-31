"""Check the image worker's rules in process, with scripted tokenizers and a scripted GPU backend.

`docker/image-worker/image_worker.py` is loaded by path. Nothing here needs Docker, a GPU, torch,
diffusers or model weights, so hosted CI runs every case. The tokenizers split on words and commas
and are not CLIP: these cases prove the chunking and safe-default rules, not what a real tokenizer
or checkpoint does with them. The stand-in `torch` and `diffusers` modules only record calls, so
`SdxlBackendCallTest` proves which calls the backend makes, not that the libraries accept them;
`test_image_worker_container.py` runs the real libraries where the image exists.
"""

import ast
import base64
import hashlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock
import zlib


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docker" / "image-worker" / "image_worker.py"
CATALOG = ROOT / "backend" / "src" / "main" / "resources" / "inference" / "model-catalog.json"


def load_worker() -> types.ModuleType:
    """Load the worker by path, because `docker/` is no package."""
    specification = importlib.util.spec_from_file_location("image_worker", SOURCE)
    module = importlib.util.module_from_spec(specification)
    # Its dataclasses look their module up while the class is created.
    sys.modules["image_worker"] = module
    specification.loader.exec_module(module)
    return module


worker = load_worker()


class WordTokenizer:
    """Give every word and every comma one stable id, as a real tokenizer would for the same text."""

    bos_token_id = 1
    eos_token_id = 2

    def __init__(self, pad_token_id: int = 2, vocabulary: dict | None = None, offset: int = 0) -> None:
        self.pad_token_id = pad_token_id
        self.vocabulary = {} if vocabulary is None else vocabulary
        self.offset = offset

    def ids(self, text: str) -> list[int]:
        """Return the content ids of `text`; ids 0 to 9 stay free for the special tokens."""
        return [self.vocabulary.setdefault(word, len(self.vocabulary) + 10) + self.offset
                for word in text.replace(",", " , ").split()]

    def __call__(self, text: str, add_special_tokens: bool, truncation: bool) -> types.SimpleNamespace:
        assert add_special_tokens is False and truncation is False, "the worker must never let a tokenizer cut"
        return types.SimpleNamespace(input_ids=self.ids(text))


def tokenizer_pair() -> tuple[WordTokenizer, WordTokenizer]:
    """Return SDXL's shape of tokenizers: one vocabulary, padding with the end token and with id 0."""
    first = WordTokenizer(pad_token_id=2)
    return first, WordTokenizer(pad_token_id=0, vocabulary=first.vocabulary)


def safetensors_bytes(tensors: dict[str, int], metadata: dict | None = None) -> bytes:
    """Return a well-formed safetensors file whose named tensors hold the given byte counts."""
    header: dict = {"__metadata__": metadata} if metadata else {}
    position = 0
    for name, size in tensors.items():
        header[name] = {"dtype": "U8", "shape": [size], "data_offsets": [position, position + size]}
        position += size
    raw = json.dumps(header).encode("utf-8")
    return struct.pack("<Q", len(raw)) + raw + b"\0" * position


CHECKPOINT_BYTES = safetensors_bytes({worker.SDXL_CHECKPOINT_PREFIX + "ln_final.weight": 4, "model.diffusion_model.a": 4})
LORA_BYTES = safetensors_bytes({"lora_unet_a.lora_down.weight": 4})


def png_chunk(kind: bytes, body: bytes, checksum: int | None = None) -> bytes:
    """Return one PNG chunk; `checksum` replaces the correct CRC to build a damaged file."""
    crc = zlib.crc32(kind + body) if checksum is None else checksum
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", crc)


def png_bytes(width: int, height: int, color: int = 2, depth: int = 8, interlace: int = 0, filter_byte: int = 0,
              rows: int | None = None) -> bytes:
    """Return a PNG whose pixels are all zero; `rows` can differ from `height` to build a short or long stream."""
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    stride = 1 + (width * channels * depth + 7) // 8
    raw = (bytes([filter_byte]) + b"\0" * (stride - 1)) * (height if rows is None else rows)
    return (worker.PNG_SIGNATURE
            + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, interlace))
            + (png_chunk(b"PLTE", b"\0\0\0") if color == 3 else b"")
            + png_chunk(b"IDAT", zlib.compress(raw)) + png_chunk(b"IEND", b""))


class ScriptedBackend:
    """Record what the worker asks of the GPU and run a stand-in step loop.

    `gate` can hold a job open to test exclusion. `step_hook` runs after each step, as a pipeline's
    step-end callback would, and the loop then checks the cancellation as a real backend must.
    `output` replaces the image, and `ignore_cancel` makes a backend that never checks.
    """

    def __init__(self) -> None:
        self.loads: list[tuple[Path, Path | None]] = []
        self.unloads = 0
        self.trims = 0
        self.jobs: list[tuple] = []
        self.steps_run = 0
        self.first, self.second = tokenizer_pair()
        self.gate: threading.Event | None = None
        self.started = threading.Event()
        self.step_hook = None
        self.output: bytes | None = None
        self.ignore_cancel = False

    def load(self, checkpoint: Path, vae: Path | None) -> None:
        self.loads.append((checkpoint, vae))

    def unload(self) -> None:
        self.unloads += 1

    def trim(self) -> None:
        self.trims += 1

    def versions(self) -> dict[str, str]:
        return {"torch": "0-test"}

    def tokenizers(self) -> tuple[WordTokenizer, WordTokenizer]:
        return self.first, self.second

    def generate(self, rows, settings, loras, cancel) -> bytes:
        self.jobs.append((rows, settings, loras))
        self.started.set()
        if self.gate is not None:
            self.gate.wait(30)
        if not self.ignore_cancel:
            cancel.raise_if_requested()
        for step in range(settings.steps):
            self.steps_run += 1
            if self.step_hook is not None:
                self.step_hook(step)
            if not self.ignore_cancel:
                cancel.raise_if_requested()
        return png_bytes(settings.width, settings.height) if self.output is None else self.output


class ChunkingTest(unittest.TestCase):
    """Prompts beyond 77 tokens are split, and no token is ever lost, repeated or moved."""

    COMMA = 7

    def test_limit_counts_the_two_special_tokens(self) -> None:
        """The encoder's 77 positions hold 75 tokens of content."""
        self.assertEqual(77, worker.CLIP_TOKEN_LIMIT)
        self.assertEqual(75, worker.CHUNK_TOKENS)
        self.assertEqual([list(range(100, 175))], worker.split_tokens(list(range(100, 175)), self.COMMA))
        self.assertEqual([list(range(100, 175)), [175]], worker.split_tokens(list(range(100, 176)), self.COMMA))

    def test_empty_prompt_is_one_empty_chunk(self) -> None:
        """An encoder always needs one row, even for no content."""
        self.assertEqual([[]], worker.split_tokens([], self.COMMA))

    def test_every_token_survives_in_order(self) -> None:
        """Joining the chunks gives the input back at every length around the boundaries."""
        for length in (0, 1, 74, 75, 76, 150, 151, 225, 226, 600):
            tokens = [self.COMMA if index % 6 == 5 else 100 + index for index in range(length)]
            chunks = worker.split_tokens(tokens, self.COMMA)
            self.assertEqual(tokens, [token for chunk in chunks for token in chunk], length)
            self.assertTrue(all(len(chunk) <= worker.CHUNK_TOKENS for chunk in chunks), length)
            self.assertTrue(all(chunk for chunk in chunks[:-1]), "only the last chunk may be empty")

    def test_chunk_ends_after_the_last_comma_near_its_end(self) -> None:
        """A tag stays whole when a comma lies inside the backtrack window."""
        tokens = list(range(100, 200))
        tokens[70] = self.COMMA
        chunks = worker.split_tokens(tokens, self.COMMA)
        self.assertEqual(71, len(chunks[0]))
        self.assertEqual(self.COMMA, chunks[0][-1])

    def test_comma_too_early_is_not_used(self) -> None:
        """A comma before the window would waste the chunk: the split stays at the limit."""
        tokens = list(range(100, 200))
        tokens[75 - worker.COMMA_BACKTRACK_TOKENS - 1] = self.COMMA
        self.assertEqual(75, len(worker.split_tokens(tokens, self.COMMA)[0]))
        tokens = list(range(100, 200))
        tokens[75 - worker.COMMA_BACKTRACK_TOKENS] = self.COMMA
        self.assertEqual(75 - worker.COMMA_BACKTRACK_TOKENS + 1, len(worker.split_tokens(tokens, self.COMMA)[0]))

    def test_rows_are_framed_with_each_tokenizers_own_padding(self) -> None:
        """SDXL's encoders pad differently, so one row cannot serve both."""
        first, second = tokenizer_pair()
        rows = worker.token_rows(first, second, "a b", "c")
        for row in (*rows.positive_first, *rows.positive_second, *rows.negative_first, *rows.negative_second):
            self.assertEqual(77, len(row))
            self.assertEqual(1, row[0])
        self.assertEqual([first.vocabulary["a"], first.vocabulary["b"], 2], rows.positive_first[0][1:4])
        self.assertEqual(2, rows.positive_first[0][-1])
        self.assertEqual(0, rows.positive_second[0][-1])
        self.assertEqual(rows.positive_first[0][:4], rows.positive_second[0][:4])

    def test_long_prompt_yields_several_rows_with_every_word(self) -> None:
        """A prompt of 200 words spans chunks, and every word reaches an encoder row."""
        first, second = tokenizer_pair()
        words = [f"word{index}" for index in range(200)]
        rows = worker.token_rows(first, second, " ".join(words), "bad")
        self.assertEqual(3, len(rows.positive_first))
        content = [token for row in rows.positive_first for token in row[1:] if token not in (first.eos_token_id,)]
        self.assertEqual([first.vocabulary[word] for word in words], content)

    def test_sides_get_equal_chunk_counts(self) -> None:
        """Conditional and unconditional embeddings must be equally long, whichever side is longer."""
        for positive_words, negative_words in ((200, 1), (1, 200)):
            first, second = tokenizer_pair()
            rows = worker.token_rows(first, second, " ".join(f"p{i}" for i in range(positive_words)),
                                     " ".join(f"n{i}" for i in range(negative_words)))
            self.assertEqual(3, len(rows.positive_first))
            self.assertEqual(3, len(rows.negative_first))
            self.assertEqual(3, len(rows.positive_second))
            self.assertEqual(3, len(rows.negative_second))

    def test_too_many_chunks_are_refused_not_truncated(self) -> None:
        """Dropping the end of a prompt silently is exactly what chunking exists to avoid."""
        first, second = tokenizer_pair()
        limit = " ".join(f"w{i}" for i in range(worker.CHUNK_TOKENS * worker.MAX_CHUNKS))
        self.assertEqual(worker.MAX_CHUNKS, len(worker.token_rows(first, second, limit, "").positive_first))
        with self.assertRaises(worker.WorkerError) as refused:
            worker.token_rows(first, second, limit + " one-more", "")
        self.assertEqual((422, "prompt-too-long"), (refused.exception.status, refused.exception.code))

    def test_tokenizers_that_disagree_are_refused(self) -> None:
        """Chunk boundaries come from one tokenizer, so a differing second one would misalign rows."""
        first = WordTokenizer()
        second = WordTokenizer(vocabulary=first.vocabulary, offset=1)
        with self.assertRaises(worker.WorkerError) as refused:
            worker.token_rows(first, second, "a", "b")
        self.assertEqual("tokenizers-disagree", refused.exception.code)


class SafeDefaultsTest(unittest.TestCase):
    """The default safe-content tags are always applied, and only the owner can turn them off."""

    def test_recommended_checkpoint_gets_its_rating_and_quality_tags(self) -> None:
        """Docs/34 section 8: the `general` rating tag and the quality prefix precede the prompt."""
        positive, negative = worker.compose_prompts(worker.WAI_ILLUSTRIOUS, "castle, dusk", "", worker.POLICY_SAFE)
        self.assertEqual("general, masterpiece, best quality, amazing quality, castle, dusk", positive)
        tags = worker.split_tags(negative)
        for term in ("nsfw", "explicit", "nude"):
            self.assertIn(term, tags)

    def test_alternatives_use_their_own_vocabulary(self) -> None:
        """Pony has rating and score tags of its own; SDXL base has no rating vocabulary at all."""
        positive, negative = worker.compose_prompts(worker.PONY_V6, "castle", "", worker.POLICY_SAFE)
        self.assertTrue(positive.startswith("rating_safe, score_9, score_8_up, score_7_up, castle"), positive)
        self.assertIn("rating_explicit", worker.split_tags(negative))
        self.assertIn("nsfw", worker.split_tags(negative))
        positive, negative = worker.compose_prompts(worker.SDXL_BASE, "castle", "", worker.POLICY_SAFE)
        self.assertEqual("high quality, castle", positive)
        self.assertIn("nsfw", worker.split_tags(negative))

    def test_an_owner_file_still_gets_the_defaults(self) -> None:
        """An unknown checkpoint is no way around the policy."""
        positive, negative = worker.compose_prompts(worker.OWNER_CHECKPOINT, "castle", "", worker.POLICY_SAFE)
        self.assertEqual("general, castle", positive)
        self.assertIn("explicit", worker.split_tags(negative))

    def test_a_callers_negative_prompt_adds_to_the_defaults(self) -> None:
        """A caller's negative prompt must not replace the explicit-content terms."""
        _, negative = worker.compose_prompts(worker.WAI_ILLUSTRIOUS, "castle", "blurry", worker.POLICY_SAFE)
        tags = worker.split_tags(negative)
        self.assertEqual("blurry", tags[-1])
        self.assertLess(tags.index("nsfw"), tags.index("blurry"))

    def test_repeating_a_default_does_not_duplicate_it(self) -> None:
        """Tags compare without case or underscores, so a repeat adds nothing."""
        positive, _ = worker.compose_prompts(worker.WAI_ILLUSTRIOUS, "General, Best_Quality, castle", "", worker.POLICY_SAFE)
        self.assertEqual("general, masterpiece, best quality, amazing quality, castle", positive)

    def test_a_tag_against_the_defaults_is_refused_in_every_spelling(self) -> None:
        """Otherwise a prompt could cancel the negative terms, which would drop them in effect."""
        for prompt in ("castle, nsfw", "NSFW", "castle, (explicit:1.3)", "[nude]", "rating_explicit", "rating:explicit",
                       "Rating Questionable, castle", "{uncensored}", "castle, nudity"):
            with self.assertRaises(worker.WorkerError, msg=prompt) as refused:
                worker.compose_prompts(worker.WAI_ILLUSTRIOUS, prompt, "", worker.POLICY_SAFE)
            self.assertEqual((422, "prompt-conflicts-with-safe-defaults"),
                             (refused.exception.status, refused.exception.code))

    def test_a_tag_that_merely_contains_a_term_passes(self) -> None:
        """The check compares whole tags: it is no word filter, and does not pretend to be."""
        positive, _ = worker.compose_prompts(worker.WAI_ILLUSTRIOUS, "sensitive plant", "", worker.POLICY_SAFE)
        self.assertTrue(positive.endswith("sensitive plant"))

    def test_owner_private_policy_keeps_quality_tags_only(self) -> None:
        """Private use changes the safety tags and leaves the checkpoint's quality vocabulary."""
        positive, negative = worker.compose_prompts(worker.WAI_ILLUSTRIOUS, "castle, nsfw", "", worker.POLICY_OWNER_PRIVATE)
        self.assertEqual("masterpiece, best quality, amazing quality, castle, nsfw", positive)
        self.assertNotIn("general", worker.split_tags(positive))
        self.assertNotIn("nsfw", worker.split_tags(negative))
        self.assertIn("worst quality", worker.split_tags(negative))

    def test_defaults_stay_in_the_first_chunk_of_a_long_prompt(self) -> None:
        """They come first and fit, so that neither chunking nor pooling can leave them out."""
        first, second = tokenizer_pair()
        long_text = ", ".join(f"tag{index}" for index in range(120))
        for profile in (worker.WAI_ILLUSTRIOUS, worker.PONY_V6, worker.SDXL_BASE, worker.OWNER_CHECKPOINT):
            positive, negative = worker.compose_prompts(profile, long_text, long_text, worker.POLICY_SAFE)
            rows = worker.token_rows(first, second, positive, negative)
            self.assertGreater(len(rows.positive_first), 1)
            for tags, row in (((*profile.rating_tags, *profile.quality_tags), rows.positive_first[0]),
                              ((*profile.safe_negative, *profile.quality_negative), rows.negative_first[0])):
                for tag in tags:
                    for token in first.ids(tag):
                        self.assertIn(token, row, (profile.catalog_id, tag))

    def test_policy_comes_only_from_the_owners_setting(self) -> None:
        """Unset means safe; anything unknown stops the worker at startup."""
        self.assertEqual(worker.POLICY_SAFE, worker.content_policy({}))
        self.assertEqual(worker.POLICY_SAFE, worker.content_policy({worker.POLICY_VARIABLE: ""}))
        self.assertEqual(worker.POLICY_OWNER_PRIVATE, worker.content_policy({worker.POLICY_VARIABLE: "owner-private"}))
        with self.assertRaises(ValueError):
            worker.content_policy({worker.POLICY_VARIABLE: "off"})

    def test_a_refused_policy_stops_startup_with_the_configuration_status(self) -> None:
        """A mistyped setting must not fall back to anything, and the worker must not start."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict("os.environ", {worker.POLICY_VARIABLE: "off"}), \
                mock.patch("sys.stderr", io.StringIO()) as log:
            self.assertEqual(worker.CONFIG_STATUS, worker.main(["--models-dir", directory, "--port", "0"]))
        self.assertIn("startup-refused", log.getvalue())


class CatalogTest(unittest.TestCase):
    """WAI-illustrious v17.0 is the recommended default; Pony and SDXL base are the alternatives."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = {entry["id"]: entry for entry in
                       json.loads(CATALOG.read_text(encoding="utf-8"))["entries"]}

    def test_recommended_and_alternatives(self) -> None:
        """The worker's default equals ADR 0021's choice, by name, version and file."""
        self.assertEqual("wai-illustrious-sdxl-v17", worker.RECOMMENDED.catalog_id)
        self.assertEqual({"pony-diffusion-v6-xl", "sdxl-base-1.0"}, {p.catalog_id for p in worker.ALTERNATIVES})
        self.assertEqual("WAI-illustrious-SDXL v17.0", self.catalog[worker.RECOMMENDED.catalog_id]["displayName"])
        self.assertEqual("Pony Diffusion V6 XL", self.catalog["pony-diffusion-v6-xl"]["displayName"])

    def test_every_catalog_image_entry_has_a_profile_with_its_exact_files(self) -> None:
        """A renamed catalog file would otherwise turn a known checkpoint into an unknown one."""
        images = {identifier: entry for identifier, entry in self.catalog.items() if entry["kind"] == "image"}
        profiles = {profile.catalog_id: profile for profile in (worker.RECOMMENDED, *worker.ALTERNATIVES)}
        self.assertEqual(set(images), set(profiles))
        for identifier, entry in images.items():
            roles = {file["role"]: file["name"] for file in entry["files"]}
            self.assertEqual(roles["weights"], profiles[identifier].file_name, identifier)
            self.assertEqual(roles.get("vae"), profiles[identifier].vae_file, identifier)
            self.assertIn("sdxl", entry["capabilities"])

    def test_config_revision_is_the_catalogs_sdxl_revision(self) -> None:
        """The baked configurations must describe the checkpoint revision the catalog pins."""
        revision = self.catalog["sdxl-base-1.0"]["source"]["revision"]
        dockerfile = (SOURCE.parent / "Dockerfile").read_text(encoding="utf-8")
        added = [line for line in dockerfile.splitlines() if line.startswith("ADD ")]
        self.assertGreater(len(added), 10)
        for line in added:
            self.assertIn(f"/resolve/{revision}/", line)


class PackagingPinsTest(unittest.TestCase):
    """Every input of the image is fixed by digest or hash: nothing else can arrive in a build."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.context = SOURCE.parent
        cls.dockerfile = (cls.context / "Dockerfile").read_text(encoding="utf-8")

    def lock_lines(self, name: str) -> list[str]:
        """Return the requirement lines of one lock file, without comments and index options."""
        lines = (self.context / name).read_text(encoding="utf-8").splitlines()
        return [line for line in lines if line and not line.startswith(("#", "--"))]

    def test_base_image_is_the_reviewed_pin(self) -> None:
        """The base equals the pin of the gateway and the fetch helper, recorded in ADR 0009."""
        base = re.findall(r"^FROM (\S+)$", self.dockerfile, re.M)
        self.assertEqual(1, len(base))
        self.assertRegex(base[0], r"^python:3\.15\.1-slim-trixie@sha256:[0-9a-f]{64}$")
        self.assertIn(base[0], (ROOT / "docker" / "gateway" / "Dockerfile").read_text(encoding="utf-8"))
        self.assertIn(base[0], (ROOT / "docs" / "adr" / "0009-toolchain-baseline.md").read_text(encoding="utf-8"))
        self.assertRegex(self.dockerfile.splitlines()[0], r"^# syntax=docker/dockerfile:[\d.]+@sha256:[0-9a-f]{64}$")

    def test_every_requirement_is_exact_and_hashed(self) -> None:
        """A range, a missing hash or a URL requirement would let the resolver choose at build time."""
        for name in ("requirements.lock", "build-requirements.lock"):
            lines = self.lock_lines(name)
            self.assertTrue(lines, name)
            for line in lines:
                self.assertRegex(line, r"^[a-z0-9][a-z0-9._-]*==[^ ;]+ --hash=sha256:[0-9a-f]{64}$", name)

    def test_the_lock_holds_the_documented_libraries(self) -> None:
        """Docs/34 section 8 names PyTorch for CUDA and diffusers, and LoRA loading needs peft."""
        pinned = dict(line.split(" ")[0].split("==") for line in self.lock_lines("requirements.lock"))
        self.assertEqual("2.15.0+cu132", pinned["torch"])
        for name in ("diffusers", "transformers", "peft", "safetensors", "pillow"):
            self.assertIn(name, pinned)
        self.assertEqual(self.lock_lines("build-requirements.lock")[0], next(
            line for line in self.lock_lines("requirements.lock") if line.startswith("setuptools==")))

    def test_every_install_requires_hashes_and_adds_nothing(self) -> None:
        """With `--no-deps` an unlocked dependency fails `pip check` instead of arriving quietly."""
        installs = [line for line in self.dockerfile.splitlines() if '"pip", "install"' in line]
        self.assertEqual(2, len(installs))
        for line in installs:
            self.assertIn('"--require-hashes"', line)
            self.assertIn('"--no-deps"', line)
        self.assertIn('"--no-build-isolation"', installs[1])
        self.assertIn('RUN ["python", "-I", "-B", "-m", "pip", "check"]', self.dockerfile)

    def test_every_remote_file_has_a_checksum_and_a_readable_mode(self) -> None:
        """A remote file without `--chmod` gets mode 0600, which the service account cannot read."""
        added = [line for line in self.dockerfile.splitlines() if line.startswith("ADD ")]
        for line in added:
            self.assertRegex(line, r"^ADD --chmod=0644 --checksum=sha256:[0-9a-f]{64} https://huggingface\.co/\S+ /opt/image-worker/sdxl-config/\S+$")
        names = {line.rsplit("/sdxl-config/", 1)[1] for line in added}
        self.assertEqual({"model_index.json", "scheduler/scheduler_config.json", "unet/config.json", "vae/config.json",
                          *(f"{encoder}/config.json" for encoder in ("text_encoder", "text_encoder_2")),
                          *(f"{tokenizer}/{name}" for tokenizer in ("tokenizer", "tokenizer_2")
                            for name in ("merges.txt", "special_tokens_map.json", "tokenizer_config.json", "vocab.json"))}, names)

    def test_image_runs_offline_as_its_own_account(self) -> None:
        """Offline switches as a second line behind the network, a fixed account, and no build input."""
        self.assertIn("HF_HUB_OFFLINE=1", self.dockerfile)
        self.assertIn("TRANSFORMERS_OFFLINE=1", self.dockerfile)
        self.assertIn("USER 10003:10003", self.dockerfile)
        self.assertNotRegex(self.dockerfile, r"(?m)^ARG\b")
        self.assertIn(worker.CONFIG_DIRECTORY, self.dockerfile)
        self.assertIn('ENTRYPOINT ["python", "-I", "-B", "/opt/image-worker/image_worker.py"]', self.dockerfile)

    def test_worker_imports_only_the_standard_library_at_startup(self) -> None:
        """The health check runs every few seconds and must not load torch."""
        modules = set()
        for node in ast.parse(SOURCE.read_text(encoding="utf-8")).body:
            if isinstance(node, ast.Import):
                modules.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                modules.add(node.module.split(".")[0])
        self.assertIn("http", modules)
        self.assertFalse(modules - sys.stdlib_module_names)


class WorkerFixture(unittest.TestCase):
    """A real `WorkerServer` on a loopback port with a scripted backend and a temporary model directory."""

    policy = worker.POLICY_SAFE

    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.models = Path(directory.name)
        (self.models / "loras").mkdir()
        self.backend = ScriptedBackend()
        self.log = io.StringIO()
        pins = tempfile.TemporaryDirectory()
        self.addCleanup(pins.cleanup)
        self.pin_file = Path(pins.name) / "requirements.lock"
        self.pin_file.write_bytes(b"torch==0-test\n")
        self.server = worker.WorkerServer(
            ("127.0.0.1", 0), worker.ImageWorker(self.models, self.backend, self.policy, self.log, str(self.pin_file)), self.log)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 10)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def install(self, name: str, content: bytes = CHECKPOINT_BYTES, directory: str = "") -> Path:
        """Place one file in the model directory or one of its subdirectories."""
        path = self.models / directory / name
        path.write_bytes(content)
        return path

    def call(self, method: str, path: str, body: object = None, raw: bytes | None = None) -> tuple[int, dict]:
        """Send one request and return its status and JSON document."""
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=30)
        try:
            payload = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
            connection.request(method, path, payload, {"Content-Type": "application/json"})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def assert_refused(self, status: int, code: str, method: str, path: str, body: object = None, raw: bytes | None = None) -> None:
        """Require exactly this status and fixed code."""
        self.assertEqual((status, {"error": code}), self.call(method, path, body, raw))

    def load_recommended(self) -> None:
        """Install and load the recommended checkpoint."""
        self.install(worker.RECOMMENDED.file_name)
        self.assertEqual(200, self.call("POST", "/v1/load", {})[0])


class ModelFilesTest(WorkerFixture):
    """Only checked safetensors files inside the model directory reach a loader."""

    def test_models_route_names_the_recommended_default_and_alternatives(self) -> None:
        """What the engine reads matches the documented choice, with or without files installed."""
        status, document = self.call("GET", "/v1/models")
        self.assertEqual(200, status)
        self.assertEqual("wai-illustrious-sdxl-v17", document["recommended"])
        self.assertEqual(["pony-diffusion-v6-xl", "sdxl-base-1.0"], document["alternatives"])
        self.assertEqual("safe", document["contentPolicy"])
        self.assertEqual([], document["checkpoints"])
        self.assertIsNone(document["loaded"])

    def test_models_route_lists_only_safetensors(self) -> None:
        """A GGUF of the text server and partial downloads in the same directory are not offered."""
        self.install(worker.RECOMMENDED.file_name)
        self.install("gemma.gguf", b"x")
        self.install("style.safetensors", LORA_BYTES, "loras")
        (self.models / ".partial").mkdir()
        document = self.call("GET", "/v1/models")[1]
        self.assertEqual([worker.RECOMMENDED.file_name], document["checkpoints"])
        self.assertEqual(["style.safetensors"], document["loras"])

    def test_load_without_a_name_loads_the_recommended_checkpoint(self) -> None:
        """Omitting the checkpoint means the recommended default, not any file that happens to exist."""
        path = self.install(worker.RECOMMENDED.file_name)
        self.install(worker.SDXL_BASE.file_name)
        status, document = self.call("POST", "/v1/load")
        self.assertEqual(200, status)
        self.assertEqual("wai-illustrious-sdxl-v17", document["catalogId"])
        self.assertEqual([(path, None)], self.backend.loads)

    def test_missing_recommended_checkpoint_is_reported(self) -> None:
        """No other file takes its place silently."""
        self.install(worker.SDXL_BASE.file_name)
        self.assert_refused(404, "checkpoint-missing", "POST", "/v1/load", {})
        self.assertEqual([], self.backend.loads)

    def test_pony_loads_with_its_separate_vae_or_not_at_all(self) -> None:
        """The catalog pairs Pony with a VAE file: an absent one must not fall back to the embedded VAE."""
        checkpoint = self.install(worker.PONY_V6.file_name)
        self.assert_refused(404, "vae-missing", "POST", "/v1/load", {"checkpoint": "pony-diffusion-v6-xl"})
        self.assertEqual([], self.backend.loads)
        vae = self.install(worker.PONY_V6.vae_file, safetensors_bytes({"decoder.a": 4}))
        self.assertEqual(200, self.call("POST", "/v1/load", {"checkpoint": "pony-diffusion-v6-xl"})[0])
        self.assertEqual([(checkpoint, vae)], self.backend.loads)

    def test_alternative_loads_by_catalog_id_and_by_file_name(self) -> None:
        """Both identifiers select the same file, and repeating a load does nothing."""
        self.install(worker.SDXL_BASE.file_name)
        self.assertEqual("sdxl-base-1.0", self.call("POST", "/v1/load", {"checkpoint": "sdxl-base-1.0"})[1]["catalogId"])
        self.assertEqual("sdxl-base-1.0", self.call("POST", "/v1/load", {"checkpoint": worker.SDXL_BASE.file_name})[1]["catalogId"])
        self.assertEqual(1, len(self.backend.loads))

    def test_changing_checkpoint_unloads_the_previous_one_first(self) -> None:
        """Two checkpoints never fit together on the reference host."""
        self.load_recommended()
        self.install(worker.SDXL_BASE.file_name)
        before = self.backend.unloads
        self.assertEqual(200, self.call("POST", "/v1/load", {"checkpoint": "sdxl-base-1.0"})[0])
        self.assertEqual(before + 1, self.backend.unloads)
        self.assertEqual(worker.SDXL_BASE.file_name, self.call("GET", "/v1/models")[1]["loaded"])

    def test_an_owners_single_file_checkpoint_loads(self) -> None:
        """The catalog is a recommendation, not a limit (docs/34 section 6)."""
        path = self.install("ownerMix_v2.safetensors")
        status, document = self.call("POST", "/v1/load", {"checkpoint": "ownerMix_v2.safetensors"})
        self.assertEqual(200, status)
        self.assertIsNone(document["catalogId"])
        self.assertEqual([(path, None)], self.backend.loads)

    def test_names_that_could_leave_the_directory_are_refused(self) -> None:
        """A name is one path segment with the one admitted extension."""
        for name in ("../secret.safetensors", "..\\secret.safetensors", "a/b.safetensors", "/etc/passwd",
                     "model.ckpt", "model.pt", "model.bin", ".hidden.safetensors", "", "a" * 200 + ".safetensors"):
            self.assert_refused(400, "invalid-file-name", "POST", "/v1/load", {"checkpoint": name})
        self.assertEqual([], self.backend.loads)

    def test_files_that_are_not_safetensors_never_reach_the_backend(self) -> None:
        """A pickle, a truncated download and other malformed files are refused by their bytes."""
        good = safetensors_bytes({worker.SDXL_CHECKPOINT_PREFIX + "a": 8})
        length = struct.unpack("<Q", good[:8])[0]
        samples = {
            "pickle": b"\x80\x04\x95" + b"\0" * 64,
            "empty": b"",
            "short": b"\x01\x00",
            "huge-header": struct.pack("<Q", worker.MAX_HEADER_BYTES + 1) + b"{}",
            "cut-header": good[:8 + length // 2],
            "truncated": good[:-1],
            "extended": good + b"\0",
            "not-json": struct.pack("<Q", 3) + b"abc",
            "not-object": struct.pack("<Q", 2) + b"[]",
            "no-tensors": struct.pack("<Q", 2) + b"{}",
            "bad-offsets": self.with_header({"a": {"data_offsets": [4, 1]}}),
            "text-offsets": self.with_header({"a": {"data_offsets": ["0", "1"]}}),
            "no-offsets": self.with_header({"a": {"dtype": "U8"}}),
        }
        for label, content in samples.items():
            self.install(f"{label}.safetensors", content)
            self.assert_refused(422, "not-safetensors", "POST", "/v1/load", {"checkpoint": f"{label}.safetensors"})
        self.assertEqual([], self.backend.loads)

    @staticmethod
    def with_header(header: dict) -> bytes:
        """Return a file holding exactly this header and no data."""
        raw = json.dumps(header).encode()
        return struct.pack("<Q", len(raw)) + raw

    def test_a_lora_or_other_model_is_refused_as_a_checkpoint(self) -> None:
        """A valid safetensors file without SDXL's second encoder would only fail deep in the loader."""
        self.install("style.safetensors", LORA_BYTES)
        self.assert_refused(422, "not-sdxl-checkpoint", "POST", "/v1/load", {"checkpoint": "style.safetensors"})
        self.assertEqual([], self.backend.loads)

    def test_a_refused_file_leaves_the_resident_model_loaded(self) -> None:
        """The checks come before the unload, so a bad name does not cost a reload."""
        self.load_recommended()
        before = self.backend.unloads
        self.install("bad.safetensors", b"garbage")
        self.assert_refused(422, "not-safetensors", "POST", "/v1/load", {"checkpoint": "bad.safetensors"})
        self.assertEqual(before, self.backend.unloads)
        self.assertEqual(worker.RECOMMENDED.file_name, self.call("GET", "/v1/models")[1]["loaded"])

    def test_symbolic_link_leaving_the_directory_is_refused(self) -> None:
        """A link is followed only when its target stays inside the model directory."""
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / "outside.safetensors"
            target.write_bytes(CHECKPOINT_BYTES)
            link = self.models / "link.safetensors"
            try:
                link.symlink_to(target)
            except OSError as reason:
                self.skipTest(f"this host cannot create symbolic links: {reason}")
            self.assert_refused(422, "outside-model-directory", "POST", "/v1/load", {"checkpoint": link.name})
        self.assertEqual([], self.backend.loads)


class GenerateTest(WorkerFixture):
    """A job through the real HTTP surface reaches the backend with the rules applied."""

    def test_generate_needs_a_resident_checkpoint(self) -> None:
        """Loading stays an explicit step, because the admission coordinator owns the GPU."""
        self.assert_refused(409, "not-loaded", "POST", "/v1/generate", {"prompt": "castle"})
        self.assertEqual([], self.backend.jobs)

    def test_generate_returns_the_image_and_its_facts(self) -> None:
        """Documented defaults apply, the seed is reported, and defaults reach the encoder rows."""
        self.load_recommended()
        status, document = self.call("POST", "/v1/generate", {"prompt": "castle", "seed": 42})
        self.assertEqual(200, status)
        self.assertEqual(png_bytes(832, 1216), base64.b64decode(document["imagePngBase64"]))
        self.assertEqual("wai-illustrious-sdxl-v17", document["catalogId"])
        self.assertEqual("safe", document["contentPolicy"])
        self.assertEqual((42, 832, 1216, 24, 6.0, 1), (document["seed"], document["width"], document["height"],
                                                       document["steps"], document["guidanceScale"], document["chunks"]))
        rows, settings, loras = self.backend.jobs[0]
        self.assertEqual(42, settings.seed)
        self.assertEqual([], loras)
        first = self.backend.first
        self.assertEqual(first.ids("general")[0], rows.positive_first[0][1])
        self.assertIn(first.ids("nsfw")[0], rows.negative_first[0])

    def test_a_request_cannot_turn_the_defaults_off(self) -> None:
        """A field asking for it is refused instead of being ignored, and nothing runs."""
        self.load_recommended()
        for field in ("contentPolicy", "content_policy", "safeTags", "disableDefaults", "rawPrompt"):
            self.assert_refused(400, "unknown-field", "POST", "/v1/generate", {"prompt": "castle", field: "owner-private"})
        self.assertEqual([], self.backend.jobs)

    def test_a_conflicting_tag_reaches_no_backend(self) -> None:
        """The refusal comes before any tokenization or GPU work."""
        self.load_recommended()
        self.assert_refused(422, "prompt-conflicts-with-safe-defaults", "POST", "/v1/generate", {"prompt": "castle, nsfw"})
        self.assertEqual([], self.backend.jobs)

    def test_long_prompt_passes_all_its_tokens_to_the_backend(self) -> None:
        """The whole path keeps every tag and reports the number of chunks."""
        self.load_recommended()
        tags = [f"tag{index}" for index in range(100)]
        status, document = self.call("POST", "/v1/generate", {"prompt": ", ".join(tags)})
        self.assertEqual(200, status)
        self.assertGreater(document["chunks"], 1)
        rows = self.backend.jobs[0][0]
        self.assertEqual(document["chunks"], len(rows.positive_first))
        self.assertEqual(document["chunks"], len(rows.negative_first))
        present = {token for row in rows.positive_first for token in row}
        for tag in tags:
            self.assertIn(self.backend.first.ids(tag)[0], present, tag)

    def test_a_prompt_beyond_the_chunk_limit_is_refused(self) -> None:
        """Neither the tokenizer nor the worker may shorten it."""
        self.load_recommended()
        prompt = " ".join(f"w{index}" for index in range(worker.CHUNK_TOKENS * worker.MAX_CHUNKS))
        self.assert_refused(422, "prompt-too-long", "POST", "/v1/generate", {"prompt": prompt})
        self.assert_refused(422, "prompt-too-long", "POST", "/v1/generate", {"prompt": "x" * (worker.MAX_PROMPT_CHARACTERS + 1)})
        self.assertEqual([], self.backend.jobs)

    def test_loras_are_checked_and_passed_with_their_weights(self) -> None:
        """A LoRA comes from the LoRA directory, passes the same header check, and keeps its weight."""
        self.load_recommended()
        path = self.install("style.safetensors", LORA_BYTES, "loras")
        status, _ = self.call("POST", "/v1/generate", {"prompt": "castle", "loras": [{"name": "style.safetensors", "weight": 0.7}]})
        self.assertEqual(200, status)
        self.assertEqual([worker.LoraChoice(path, 0.7)], self.backend.jobs[0][2])

    def test_lora_refusals(self) -> None:
        """Missing, malformed or out-of-bounds entries stop the job before the GPU."""
        self.load_recommended()
        self.install("bad.safetensors", b"\x80\x04", "loras")
        self.install("style.safetensors", LORA_BYTES, "loras")
        self.install("root.safetensors", LORA_BYTES)
        for status, code, loras in (
                (404, "lora-missing", [{"name": "none.safetensors"}]),
                (404, "lora-missing", [{"name": "root.safetensors"}]),
                (422, "not-safetensors", [{"name": "bad.safetensors"}]),
                (400, "invalid-file-name", [{"name": "../root.safetensors"}]),
                (400, "invalid-file-name", [{"name": "style.ckpt"}]),
                (400, "invalid-loras", [{"name": "style.safetensors", "weight": 3}]),
                (400, "invalid-loras", [{"name": "style.safetensors", "weight": True}]),
                (400, "invalid-loras", [{"name": "style.safetensors", "other": 1}]),
                (400, "invalid-loras", [{"weight": 1}]),
                (400, "invalid-loras", "style.safetensors"),
                (400, "invalid-loras", [{"name": "style.safetensors"}] * (worker.MAX_LORAS + 1))):
            self.assert_refused(status, code, "POST", "/v1/generate", {"prompt": "castle", "loras": loras})
        self.assertEqual([], self.backend.jobs)

    def test_settings_outside_their_bounds_are_refused(self) -> None:
        """Each numeric field has a fixed code, and no value is clamped silently."""
        self.load_recommended()
        for code, field in (("invalid-width", {"width": 511}), ("invalid-width", {"width": 1544}), ("invalid-width", {"width": 833}),
                            ("invalid-width", {"width": True}), ("invalid-height", {"height": "1024"}),
                            ("invalid-steps", {"steps": 0}), ("invalid-steps", {"steps": 61}),
                            ("invalid-guidancescale", {"guidanceScale": 0.5}), ("invalid-guidancescale", {"guidanceScale": 12.5}),
                            ("invalid-seed", {"seed": -1}), ("invalid-seed", {"seed": 2 ** 32}), ("invalid-seed", {"seed": 1.5}),
                            ("invalid-prompt", {"prompt": ""}), ("invalid-prompt", {"prompt": "   "}), ("invalid-prompt", {"prompt": 7}),
                            ("invalid-negativeprompt", {"negativePrompt": 7})):
            self.assert_refused(400, code, "POST", "/v1/generate", {"prompt": "castle", **field})
        self.assertEqual([], self.backend.jobs)

    def test_second_job_is_busy_while_health_keeps_answering(self) -> None:
        """One GPU job at a time, and the health check does not depend on the job's length."""
        self.load_recommended()
        self.backend.gate = threading.Event()
        self.addCleanup(self.backend.gate.set)
        results = []
        job = threading.Thread(target=lambda: results.append(self.call("POST", "/v1/generate", {"prompt": "castle"})))
        job.start()
        self.assertTrue(self.backend.started.wait(30))
        self.assertEqual((200, {"status": "serving"}), self.call("GET", "/healthz"))
        self.assertEqual(worker.RECOMMENDED.file_name, self.call("GET", "/v1/models")[1]["loaded"])
        self.assert_refused(409, "busy", "POST", "/v1/generate", {"prompt": "other"})
        self.assert_refused(409, "busy", "POST", "/v1/load", {})
        self.assert_refused(409, "busy", "POST", "/v1/unload", {})
        self.assertEqual(1, len(self.backend.jobs))
        self.backend.gate.set()
        job.join(30)
        self.assertEqual(200, results[0][0])
        self.assertEqual(200, self.call("POST", "/v1/generate", {"prompt": "next"})[0])

    def test_unload_releases_and_a_later_generate_needs_a_load(self) -> None:
        """After unload nothing is resident, and repeating the unload is harmless."""
        self.load_recommended()
        self.assertEqual((200, {"loaded": None}), self.call("POST", "/v1/unload", {}))
        self.assertEqual((200, {"loaded": None}), self.call("POST", "/v1/unload"))
        self.assert_refused(409, "not-loaded", "POST", "/v1/generate", {"prompt": "castle"})
        self.assertIsNone(self.call("GET", "/v1/models")[1]["loaded"])

    def test_a_backend_failure_answers_its_code_and_the_slot_is_free(self) -> None:
        """A fixed-code failure and an unexpected one both leave the worker able to take a job."""
        self.load_recommended()
        for raised, status, code in ((worker.WorkerError(507, "gpu-out-of-memory"), 507, "gpu-out-of-memory"),
                                     (RuntimeError("private path /models/x"), 500, "internal-error")):
            with mock.patch.object(self.backend, "generate", side_effect=raised):
                self.assert_refused(status, code, "POST", "/v1/generate", {"prompt": "castle"})
            self.assertNotIn("private path", self.log.getvalue())
        self.assertEqual(200, self.call("POST", "/v1/generate", {"prompt": "castle"})[0])

    def test_request_shape_refusals(self) -> None:
        """Bad JSON, a non-object, an oversized body and an unknown route all have fixed codes."""
        self.assert_refused(400, "invalid-request", "POST", "/v1/generate", raw=b"{")
        self.assert_refused(400, "invalid-request", "POST", "/v1/generate", [1])
        self.assert_refused(400, "unknown-field", "POST", "/v1/unload", {"x": 1})
        self.assert_refused(413, "request-too-large", "POST", "/v1/generate", raw=b" " * (worker.MAX_REQUEST_BYTES + 1))
        self.assert_refused(404, "unknown-route", "POST", "/v1/other", {})
        self.assert_refused(404, "unknown-route", "GET", "/other")

    def test_log_never_holds_a_prompt(self) -> None:
        """Prompts can quote private adventure text, so a log line has codes and counts only."""
        self.load_recommended()
        sentinel = "sentinel" + hashlib.sha256(b"prompt").hexdigest()[:12]
        self.assertEqual(200, self.call("POST", "/v1/generate", {"prompt": sentinel, "negativePrompt": sentinel})[0])
        self.assert_refused(422, "prompt-conflicts-with-safe-defaults", "POST", "/v1/generate", {"prompt": f"{sentinel}, nsfw"})
        self.assert_refused(400, "unknown-field", "POST", "/v1/generate", {"prompt": sentinel, sentinel: 1})
        self.assertIn("generated", self.log.getvalue())
        self.assertNotIn(sentinel, self.log.getvalue())


class OwnerPrivateWorkerTest(WorkerFixture):
    """The owner's setting changes the defaults, and every answer says so."""

    policy = worker.POLICY_OWNER_PRIVATE

    def test_owner_private_is_reported_and_applied(self) -> None:
        """A tag refused under safe passes, and the policy in force is visible to the caller."""
        self.load_recommended()
        status, document = self.call("POST", "/v1/generate", {"prompt": "castle, nsfw"})
        self.assertEqual(200, status)
        self.assertEqual("owner-private", document["contentPolicy"])
        self.assertEqual("owner-private", self.call("GET", "/v1/models")[1]["contentPolicy"])
        negative = self.backend.jobs[0][0].negative_first[0]
        self.assertNotIn(self.backend.first.ids("nsfw")[0], negative)


class CancellationTest(WorkerFixture):
    """A cancel stops a job at a step boundary and frees the worker; nothing partial is ever returned."""

    def run_job(self, results: list, **extra: object) -> threading.Thread:
        """Start a generate request in its own thread, because it holds the connection until it ends."""
        job = threading.Thread(target=lambda: results.append(self.call("POST", "/v1/generate", {"prompt": "castle", **extra})))
        job.start()
        self.addCleanup(job.join, 30)
        return job

    def pause_at_step(self, step: int) -> tuple[threading.Event, threading.Event]:
        """Hold the stand-in loop after `step`; return the event that says it got there and the one that resumes it."""
        reached, resume = threading.Event(), threading.Event()
        self.addCleanup(resume.set)

        def hook(index: int) -> None:
            if index == step:
                reached.set()
                resume.wait(30)
        self.backend.step_hook = hook
        return reached, resume

    def test_cancel_at_a_step_boundary_reports_cancelled_and_returns_no_image(self) -> None:
        """The request arrives during step 3 and the job ends at that step's boundary, 20 steps early."""
        self.load_recommended()
        reached, resume = self.pause_at_step(2)
        results: list = []
        job = self.run_job(results)
        self.assertTrue(reached.wait(30))
        self.assertEqual((200, {"cancelling": True}), self.call("POST", "/v1/cancel", {}))
        resume.set()
        job.join(30)
        self.assertEqual([(409, {"error": "cancelled"})], results)
        self.assertEqual(3, self.backend.steps_run)
        self.assertIn("cancelled", self.log.getvalue())

    def test_a_cancelled_job_releases_its_resources(self) -> None:
        """The slot is free, the cache is trimmed, the model stays resident, and the next job runs whole."""
        self.load_recommended()
        reached, resume = self.pause_at_step(0)
        results: list = []
        job = self.run_job(results)
        self.assertTrue(reached.wait(30))
        self.call("POST", "/v1/cancel", {})
        resume.set()
        job.join(30)
        self.assertEqual(1, self.backend.trims)
        self.assertEqual(worker.RECOMMENDED.file_name, self.call("GET", "/v1/models")[1]["loaded"])
        self.backend.step_hook = None
        self.backend.steps_run = 0
        status, document = self.call("POST", "/v1/generate", {"prompt": "castle", "steps": 5})
        self.assertEqual(200, status)
        self.assertEqual(5, self.backend.steps_run, "a cancel must not leak into the next job")
        self.assertEqual(png_bytes(832, 1216), base64.b64decode(document["imagePngBase64"]))
        self.assert_refused(409, "no-job", "POST", "/v1/cancel", {})

    def test_cancel_during_the_wait_before_the_first_step_runs_no_step(self) -> None:
        """The check before step one stops a job that was asked to stop while it was still being prepared."""
        self.load_recommended()
        self.backend.gate = threading.Event()
        self.addCleanup(self.backend.gate.set)
        results: list = []
        job = self.run_job(results)
        self.assertTrue(self.backend.started.wait(30))
        self.assertEqual(200, self.call("POST", "/v1/cancel", {})[0])
        self.backend.gate.set()
        job.join(30)
        self.assertEqual([(409, {"error": "cancelled"})], results)
        self.assertEqual(0, self.backend.steps_run)

    def test_an_image_finished_after_the_request_is_discarded(self) -> None:
        """A backend that never checks still cannot hand over an image the caller asked to cancel."""
        self.load_recommended()
        self.backend.ignore_cancel = True
        reached, resume = self.pause_at_step(worker.DEFAULT_STEPS - 1)
        results: list = []
        job = self.run_job(results)
        self.assertTrue(reached.wait(30))
        self.assertEqual(200, self.call("POST", "/v1/cancel", {})[0])
        resume.set()
        job.join(30)
        self.assertEqual([(409, {"error": "cancelled"})], results)
        self.assertEqual(worker.DEFAULT_STEPS, self.backend.steps_run)
        self.assertEqual(1, self.backend.trims)

    def test_cancel_without_a_running_job_is_refused(self) -> None:
        """Idle, loading and unloading all answer the same, and nothing is remembered for a later job."""
        self.assert_refused(409, "no-job", "POST", "/v1/cancel", {})
        self.load_recommended()
        self.assert_refused(409, "no-job", "POST", "/v1/cancel")
        status, _ = self.call("POST", "/v1/generate", {"prompt": "castle"})
        self.assertEqual(200, status)
        self.assertEqual(0, self.backend.trims)

    def test_cancel_takes_no_fields(self) -> None:
        """The route has no option to grow: an unknown field is refused as everywhere else."""
        self.assert_refused(400, "unknown-field", "POST", "/v1/cancel", {"jobId": "x"})

    def test_a_failed_backend_job_leaves_no_cancel_target(self) -> None:
        """After a job fails with a fixed code, a late cancel finds nothing to stop."""
        self.load_recommended()
        with mock.patch.object(self.backend, "generate", side_effect=worker.WorkerError(507, "gpu-out-of-memory")):
            self.assert_refused(507, "gpu-out-of-memory", "POST", "/v1/generate", {"prompt": "castle"})
        self.assert_refused(409, "no-job", "POST", "/v1/cancel", {})


class OutputValidationTest(WorkerFixture):
    """`validate_png` accepts a sound PNG of the requested size and refuses everything else."""

    SIDE = 512

    def refused(self, data: bytes, width: int = SIDE, height: int = SIDE) -> None:
        with self.assertRaises(worker.WorkerError) as raised:
            worker.validate_png(data, width, height)
        self.assertEqual((500, "invalid-output"), (raised.exception.status, raised.exception.code))

    def test_sound_pngs_pass(self) -> None:
        """Every legal color type and depth the format has passes, not only the RGB the pipeline writes."""
        for color, depth in ((2, 8), (2, 16), (6, 8), (0, 8), (0, 1), (0, 16), (3, 8), (3, 2), (4, 8)):
            worker.validate_png(png_bytes(self.SIDE, self.SIDE, color=color, depth=depth), self.SIDE, self.SIDE)
        worker.validate_png(png_bytes(832, 1216), 832, 1216)

    def test_what_is_not_a_png_is_refused(self) -> None:
        """Text, a bare signature, a truncated file and any added byte all fail."""
        good = png_bytes(self.SIDE, self.SIDE)
        for label, data in {
            "empty": b"", "text": b"not a png at all", "signature": worker.PNG_SIGNATURE,
            "scripted": b"\x89PNG scripted", "truncated": good[:-5], "no end": good[:-12],
            "trailing byte": good + b"x", "after the end": good + png_chunk(b"tEXt", b"a"),
            "end first": worker.PNG_SIGNATURE + png_chunk(b"IEND", b"") + good[8:],
            "header not first": worker.PNG_SIGNATURE + png_chunk(b"tEXt", b"a") + good[8:],
            "short header": worker.PNG_SIGNATURE + png_chunk(b"IHDR", b"\0" * 12) + png_chunk(b"IEND", b""),
            "end with data": good[:-12] + png_chunk(b"IEND", b"x"),
            "no data": good.replace(good[good.index(b"IDAT") - 4:good.index(b"IEND") - 4], b""),
        }.items():
            with self.subTest(label):
                self.refused(data)

    def test_damaged_bytes_are_refused(self) -> None:
        """A changed checksum, a cut compressed stream and garbage with a correct checksum all fail."""
        good = png_bytes(self.SIDE, self.SIDE)
        crc = good.index(b"IHDR") + 4 + 13
        header = good[8:crc + 4]
        end = png_chunk(b"IEND", b"")
        stream = zlib.compress((b"\0" + b"\0" * (self.SIDE * 3)) * self.SIDE)
        for label, data in {
            "header checksum": good[:crc] + bytes([good[crc] ^ 1]) + good[crc + 1:],
            "data checksum": good[:-17] + bytes([good[-17] ^ 1]) + good[-16:],
            "cut stream": worker.PNG_SIGNATURE + header + png_chunk(b"IDAT", stream[:-6]) + end,
            "garbage stream": worker.PNG_SIGNATURE + header + png_chunk(b"IDAT", b"garbage") + end,
            "second stream": worker.PNG_SIGNATURE + header + png_chunk(b"IDAT", stream + stream) + end,
        }.items():
            with self.subTest(label):
                self.refused(data)

    def test_wrong_dimensions_are_refused(self) -> None:
        """The size must equal the request, in both directions, and stay inside the bounds."""
        self.refused(png_bytes(self.SIDE + 8, self.SIDE))
        self.refused(png_bytes(self.SIDE, self.SIDE + 8))
        self.refused(png_bytes(self.SIDE, self.SIDE), self.SIDE + 8, self.SIDE)
        too_wide = worker.MAX_SIDE + worker.SIDE_STEP
        self.refused(png_bytes(too_wide, self.SIDE), too_wide, self.SIDE)
        too_small = worker.MIN_SIDE - worker.SIDE_STEP
        self.refused(png_bytes(too_small, too_small), too_small, too_small)

    def test_inconsistent_headers_and_streams_are_refused(self) -> None:
        """Interlacing, impossible depths, a stream of the wrong length and a bad filter byte all fail."""
        for label, data in {
            "interlaced": png_bytes(self.SIDE, self.SIDE, interlace=1),
            "bad depth": png_bytes(self.SIDE, self.SIDE, color=2, depth=4),
            "short stream": png_bytes(self.SIDE, self.SIDE, rows=self.SIDE - 1),
            "long stream": png_bytes(self.SIDE, self.SIDE, rows=self.SIDE + 1),
            "bad filter": png_bytes(self.SIDE, self.SIDE, filter_byte=5),
            "palette missing": png_bytes(self.SIDE, self.SIDE, color=3).replace(png_chunk(b"PLTE", b"\0\0\0"), b""),
        }.items():
            with self.subTest(label):
                self.refused(data)

    def test_a_stream_that_inflates_far_past_its_size_is_refused_early(self) -> None:
        """A small file must not make the worker allocate what its header never promised."""
        bomb = png_bytes(self.SIDE, self.SIDE, rows=40000)
        self.assertLess(len(bomb), worker.MAX_IMAGE_BYTES)
        self.refused(bomb)

    def test_the_byte_limit_is_enforced(self) -> None:
        """A PNG that is otherwise sound still fails when it is larger than the limit."""
        good = png_bytes(self.SIDE, self.SIDE)
        worker.validate_png(good, self.SIDE, self.SIDE)
        with mock.patch.object(worker, "MAX_IMAGE_BYTES", len(good) - 1):
            self.refused(good)

    def test_an_invalid_image_is_never_returned(self) -> None:
        """Whatever the backend hands over, the caller gets the fixed code, the slot frees and the cache is trimmed."""
        self.load_recommended()
        for output in (b"\x89PNG scripted", png_bytes(1024, 1216), b"", png_bytes(832, 1216)[:-1]):
            self.backend.output = output
            trims = self.backend.trims
            self.assert_refused(500, "invalid-output", "POST", "/v1/generate", {"prompt": "castle"})
            self.assertEqual(trims + 1, self.backend.trims)
        self.assertIn("output-refused", self.log.getvalue())
        self.backend.output = None
        self.assertEqual(200, self.call("POST", "/v1/generate", {"prompt": "castle"})[0])

    def test_the_requested_size_not_the_default_is_what_must_match(self) -> None:
        """A backend answering the default size for a custom request is refused."""
        self.load_recommended()
        self.backend.output = png_bytes(832, 1216)
        self.assert_refused(500, "invalid-output", "POST", "/v1/generate", {"prompt": "castle", "width": 1024, "height": 1024})
        self.backend.output = png_bytes(1024, 1024)
        self.assertEqual(200, self.call("POST", "/v1/generate", {"prompt": "castle", "width": 1024, "height": 1024})[0])


class ProvenanceTest(WorkerFixture):
    """Each result says which files, settings and prompts made it, by name and hash and never by prompt text."""

    def generate(self, **body: object) -> dict:
        status, document = self.call("POST", "/v1/generate", {"prompt": "castle", **body})
        self.assertEqual(200, status, document)
        return document

    def test_provenance_content(self) -> None:
        """The record holds the checkpoint, LoRA, seed, steps, sampler, prompt hashes and versions."""
        self.load_recommended()
        self.install("style.safetensors", LORA_BYTES, "loras")
        document = self.generate(seed=42, negativePrompt="blurry", steps=12,
                                 loras=[{"name": "style.safetensors", "weight": 0.7}])
        record = document["provenance"]
        positive, negative = worker.compose_prompts(worker.WAI_ILLUSTRIOUS, "castle", "blurry", worker.POLICY_SAFE)
        image = base64.b64decode(document["imagePngBase64"])
        self.assertEqual({"name": worker.RECOMMENDED.file_name, "sha256": hashlib.sha256(CHECKPOINT_BYTES).hexdigest(),
                          "catalogId": "wai-illustrious-sdxl-v17"}, record["checkpoint"])
        self.assertIsNone(record["vae"])
        self.assertEqual([{"name": "style.safetensors", "sha256": hashlib.sha256(LORA_BYTES).hexdigest(), "weight": 0.7}],
                         record["loras"])
        self.assertEqual((42, 12, 6.0, "euler-ancestral", 832, 1216, "safe"),
                         (record["seed"], record["steps"], record["guidanceScale"], record["sampler"],
                          record["width"], record["height"], record["contentPolicy"]))
        self.assertEqual({"promptSha256": hashlib.sha256(b"castle").hexdigest(),
                          "negativePromptSha256": hashlib.sha256(b"blurry").hexdigest(),
                          "composedPromptSha256": hashlib.sha256(positive.encode()).hexdigest(),
                          "composedNegativeSha256": hashlib.sha256(negative.encode()).hexdigest()}, record["prompts"])
        self.assertEqual((hashlib.sha256(image).hexdigest(), len(image)), (record["outputSha256"], record["outputBytes"]))
        self.assertEqual(worker.WORKER_VERSION, record["workerVersion"])
        self.assertEqual(hashlib.sha256(SOURCE.read_bytes()).hexdigest(), record["workerSourceSha256"])
        self.assertEqual(hashlib.sha256(self.pin_file.read_bytes()).hexdigest(), record["lockSha256"])
        self.assertEqual({"torch": "0-test"}, record["libraries"])

    def test_provenance_holds_no_prompt_text(self) -> None:
        """Prompts can quote private adventure text, and a hash is the most the record may say about them."""
        self.load_recommended()
        sentinel = "sentinel" + hashlib.sha256(b"provenance").hexdigest()[:12]
        record = self.generate(prompt=sentinel, negativePrompt=sentinel)["provenance"]
        self.assertNotIn(sentinel, json.dumps(record))
        self.assertEqual(hashlib.sha256(sentinel.encode()).hexdigest(), record["prompts"]["promptSha256"])

    def test_the_seed_the_worker_chose_is_in_the_record(self) -> None:
        """Without a seed in the request the record still allows a repeat."""
        self.load_recommended()
        document = self.generate()
        self.assertEqual(document["seed"], document["provenance"]["seed"])

    def test_a_separate_vae_is_recorded_with_its_hash(self) -> None:
        """Pony's output depends on its VAE file as much as on the checkpoint."""
        self.install(worker.PONY_V6.file_name)
        vae = safetensors_bytes({"decoder.a": 4})
        self.install(worker.PONY_V6.vae_file, vae)
        self.assertEqual(200, self.call("POST", "/v1/load", {"checkpoint": "pony-diffusion-v6-xl"})[0])
        record = self.generate()["provenance"]
        self.assertEqual({"name": worker.PONY_V6.vae_file, "sha256": hashlib.sha256(vae).hexdigest()}, record["vae"])
        self.assertEqual("pony-diffusion-v6-xl", record["checkpoint"]["catalogId"])

    def test_another_checkpoint_gives_another_hash_and_unload_forgets_it(self) -> None:
        """The record follows the resident file, never the one loaded before."""
        self.load_recommended()
        first = self.generate()["provenance"]["checkpoint"]["sha256"]
        other = safetensors_bytes({worker.SDXL_CHECKPOINT_PREFIX + "ln_final.weight": 8})
        self.install(worker.SDXL_BASE.file_name, other)
        self.assertEqual(200, self.call("POST", "/v1/load", {"checkpoint": "sdxl-base-1.0"})[0])
        second = self.generate()["provenance"]["checkpoint"]
        self.assertNotEqual(first, second["sha256"])
        self.assertEqual((hashlib.sha256(other).hexdigest(), "sdxl-base-1.0"), (second["sha256"], second["catalogId"]))
        self.call("POST", "/v1/unload", {})
        self.assert_refused(409, "not-loaded", "POST", "/v1/generate", {"prompt": "castle"})

    def test_a_changed_lora_file_gets_a_new_hash(self) -> None:
        """The hash is cached by size and time, so replacing the file must not return the old one."""
        self.load_recommended()
        self.install("style.safetensors", LORA_BYTES, "loras")
        loras = [{"name": "style.safetensors"}]
        first = self.generate(loras=loras)["provenance"]["loras"][0]["sha256"]
        again = self.generate(loras=loras)["provenance"]["loras"][0]["sha256"]
        replacement = safetensors_bytes({"lora_unet_a.lora_down.weight": 12})
        self.install("style.safetensors", replacement, "loras")
        changed = self.generate(loras=loras)["provenance"]["loras"][0]["sha256"]
        self.assertEqual(first, again)
        self.assertEqual(hashlib.sha256(replacement).hexdigest(), changed)
        self.assertNotEqual(first, changed)

    def test_without_the_lock_file_the_pin_digest_is_null(self) -> None:
        """Outside the image there is no lock to hash, and the record says so instead of inventing one."""
        absent = worker.ImageWorker(self.models, self.backend, worker.POLICY_SAFE, self.log, str(self.pin_file) + ".absent")
        self.assertIsNone(absent._build["lockSha256"])


class HealthTest(WorkerFixture):
    """The health command used by Compose reports only that the listener answers."""

    def test_health_passes_against_a_running_listener(self) -> None:
        self.assertEqual(0, worker.health(self.server.server_address[1]))

    def test_health_fails_without_a_listener(self) -> None:
        with mock.patch.object(worker, "HEALTH_SECONDS", 1):
            self.assertEqual(worker.HEALTH_FAILED_STATUS, worker.health(1))

    def test_a_missing_model_directory_stops_startup(self) -> None:
        with tempfile.TemporaryDirectory() as directory, mock.patch("sys.stderr", io.StringIO()):
            self.assertEqual(worker.CONFIG_STATUS, worker.main(["--models-dir", directory + "/absent", "--port", "0"]))


class FakeTensor:
    """Track the shape operations `embed_rows` performs, without any numeric work."""

    def __init__(self, shape: tuple[int, ...]) -> None:
        self.shape = shape

    def reshape(self, *shape: int) -> "FakeTensor":
        total = 1
        for side in self.shape:
            total *= side
        known = 1
        for side in shape:
            if side != -1:
                known *= side
        return FakeTensor(tuple(total // known if side == -1 else side for side in shape))

    def __getitem__(self, item: slice) -> "FakeTensor":
        return FakeTensor((item.stop - (item.start or 0), *self.shape[1:]))


class FakeTorch(types.ModuleType):
    """The part of torch that the backend uses, recording what it receives."""

    def __init__(self, cuda_available: bool = True) -> None:
        super().__init__("torch")
        self.float16 = "float16"
        self.cuda = types.SimpleNamespace(is_available=lambda: cuda_available, OutOfMemoryError=type("OutOfMemoryError", (RuntimeError,), {}),
                                          empty_cache=mock.Mock(), ipc_collect=mock.Mock())
        self.generators: list = []

    def tensor(self, rows: list, device: str) -> FakeTensor:
        return FakeTensor((len(rows), len(rows[0])))

    def cat(self, parts: list, dim: int) -> FakeTensor:
        assert dim == -1
        return FakeTensor((*parts[0].shape[:-1], sum(part.shape[-1] for part in parts)))

    def no_grad(self) -> mock.MagicMock:
        return mock.MagicMock()

    def Generator(self, device: str) -> mock.Mock:
        generator = mock.Mock()
        generator.manual_seed.side_effect = lambda seed: self.generators.append((device, seed)) or generator
        return generator


class FakeEncoder:
    """Answer as a CLIP encoder would: hidden states by layer, and pooled output at index 0 for the second."""

    def __init__(self, features: int, pooled: int | None = None) -> None:
        self.features = features
        self.pooled = pooled

    def __call__(self, rows: FakeTensor, output_hidden_states: bool):
        assert output_hidden_states is True
        hidden = (FakeTensor((*rows.shape, 1)), FakeTensor((*rows.shape, self.features)), FakeTensor((*rows.shape, 1)))
        return types.SimpleNamespace(hidden_states=hidden) if self.pooled is None else FakeOutput(hidden, FakeTensor((rows.shape[0], self.pooled)))


class FakeOutput(tuple):
    """A tuple-like model output with named hidden states, as transformers returns."""

    def __new__(cls, hidden: tuple, pooled: FakeTensor) -> "FakeOutput":
        output = super().__new__(cls, (pooled,))
        output.hidden_states = hidden
        return output


class SdxlBackendCallTest(unittest.TestCase):
    """The backend's calls into the libraries, on recording stand-ins: arguments, order and cleanup."""

    def setUp(self) -> None:
        self.torch = FakeTorch()
        self.pipeline = mock.MagicMock()
        self.pipeline.text_encoder = FakeEncoder(768)
        self.pipeline.text_encoder_2 = FakeEncoder(1280, 1280)
        self.pipeline.return_value.images = [mock.Mock(save=lambda stream, format: stream.write(b"PNG:" + format.encode()))]
        self.pipeline_class = mock.Mock()
        self.pipeline_class.from_single_file.return_value = self.pipeline
        self.vae_class = mock.Mock()
        self.scheduler_class = mock.Mock()
        diffusers = types.ModuleType("diffusers")
        diffusers.StableDiffusionXLPipeline = self.pipeline_class
        diffusers.AutoencoderKL = self.vae_class
        diffusers.EulerAncestralDiscreteScheduler = self.scheduler_class
        patcher = mock.patch.dict(sys.modules, {"torch": self.torch, "diffusers": diffusers})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.backend = worker.SdxlBackend(config_directory="/baked")
        first, second = tokenizer_pair()
        self.rows = worker.token_rows(first, second, " ".join(f"w{i}" for i in range(100)), "bad")
        self.settings = worker.GenerationSettings(832, 1216, 24, 6.0, 42)

    def test_load_is_single_file_offline_and_uses_the_baked_configuration(self) -> None:
        """Without `config`, the library would look the configuration up online."""
        self.backend.load(Path("/models/w.safetensors"), None)
        call = self.pipeline_class.from_single_file.call_args
        self.assertEqual((str(Path("/models/w.safetensors")),), call.args)
        self.assertEqual({"config": "/baked", "local_files_only": True, "torch_dtype": "float16"}, call.kwargs)
        self.vae_class.from_single_file.assert_not_called()
        self.pipeline.to.assert_called_once_with("cuda")
        self.scheduler_class.from_config.assert_called_once()
        self.assertIs(self.scheduler_class.from_config.return_value, self.pipeline.scheduler)

    def test_separate_vae_is_loaded_the_same_way_and_handed_over(self) -> None:
        """Pony's VAE is one more single file, with its configuration in the same baked directory."""
        self.backend.load(Path("/models/p.safetensors"), Path("/models/v.safetensors"))
        vae_call = self.vae_class.from_single_file.call_args
        self.assertEqual({"config": "/baked", "local_files_only": True, "torch_dtype": "float16", "subfolder": "vae"}, vae_call.kwargs)
        self.assertIs(self.vae_class.from_single_file.return_value, self.pipeline_class.from_single_file.call_args.kwargs["vae"])

    def test_no_cuda_device_is_refused_before_any_load(self) -> None:
        """Running on the CPU would look like a hang, so it is an explicit refusal."""
        self.torch.cuda.is_available = lambda: False
        with self.assertRaises(worker.WorkerError) as refused:
            self.backend.load(Path("/models/w.safetensors"), None)
        self.assertEqual((503, "gpu-unavailable"), (refused.exception.status, refused.exception.code))
        self.pipeline_class.from_single_file.assert_not_called()

    def test_load_failures_are_fixed_codes(self) -> None:
        """A library's message can quote paths, and running out of memory has its own code."""
        for raised, status, code in ((self.torch.cuda.OutOfMemoryError(), 507, "gpu-out-of-memory"),
                                     (ValueError("/models/private"), 422, "checkpoint-load-failed")):
            self.pipeline_class.from_single_file.side_effect = raised
            with self.assertRaises(worker.WorkerError) as refused:
                self.backend.load(Path("/models/w.safetensors"), None)
            self.assertEqual((status, code), (refused.exception.status, refused.exception.code))
            self.assertIsNone(refused.exception.__cause__)

    def test_generate_passes_chunked_embeddings_of_matching_shapes(self) -> None:
        """Chunks join on the sequence axis, and the pooled vector is the first chunk's only."""
        self.backend.load(Path("/models/w.safetensors"), None)
        image = self.backend.generate(self.rows, self.settings, [], worker.Cancellation())
        self.assertEqual(b"PNG:PNG", image)
        call = self.pipeline.call_args.kwargs
        chunks = len(self.rows.positive_first)
        self.assertEqual(2, chunks)
        self.assertEqual((1, 77 * chunks, 2048), call["prompt_embeds"].shape)
        self.assertEqual(call["prompt_embeds"].shape, call["negative_prompt_embeds"].shape)
        self.assertEqual((1, 1280), call["pooled_prompt_embeds"].shape)
        self.assertEqual((1, 1280), call["negative_pooled_prompt_embeds"].shape)
        self.assertEqual((832, 1216, 24, 6.0), (call["width"], call["height"], call["num_inference_steps"], call["guidance_scale"]))
        self.assertEqual([("cuda", 42)], self.torch.generators)
        self.assertNotIn("prompt", call)

    def test_loras_load_before_encoding_and_leave_after_the_job(self) -> None:
        """A LoRA can change the text encoders, and none may outlive its job."""
        self.backend.load(Path("/models/w.safetensors"), None)
        loras = [worker.LoraChoice(Path("/models/loras/a.safetensors"), 0.5), worker.LoraChoice(Path("/models/loras/b.safetensors"), 1.0)]
        self.backend.generate(self.rows, self.settings, loras, worker.Cancellation())
        calls = self.pipeline.method_calls
        names = [call[0] for call in calls]
        self.assertLess(names.index("set_adapters"), names.index("delete_adapters"))
        first = self.pipeline.load_lora_weights.call_args_list[0]
        self.assertEqual((str(Path("/models/loras")),), first.args)
        self.assertEqual({"weight_name": "a.safetensors", "adapter_name": "job-lora-0", "local_files_only": True}, first.kwargs)
        self.pipeline.set_adapters.assert_called_once_with(["job-lora-0", "job-lora-1"], adapter_weights=[0.5, 1.0])
        self.pipeline.delete_adapters.assert_called_once_with(["job-lora-0", "job-lora-1"])

    def test_a_failed_job_still_removes_its_loras(self) -> None:
        """Otherwise the next job would run with this job's styles."""
        self.backend.load(Path("/models/w.safetensors"), None)
        self.pipeline.side_effect = ValueError("/models/private")
        loras = [worker.LoraChoice(Path("/models/loras/a.safetensors"), 0.5)]
        with self.assertRaises(worker.WorkerError) as refused:
            self.backend.generate(self.rows, self.settings, loras, worker.Cancellation())
        self.assertEqual("generation-failed", refused.exception.code)
        self.pipeline.delete_adapters.assert_called_once_with(["job-lora-0"])

    def pipeline_running(self, steps: int, hook=None) -> None:
        """Make the stand-in pipeline call its step-end callback `steps` times, as diffusers does."""
        def run(**kwargs):
            for step in range(steps):
                self.steps_seen = step + 1
                if hook is not None:
                    hook(step)
                returned = kwargs["callback_on_step_end"](self.pipeline, step, 999 - step, {})
                assert returned == {}, "the callback must hand its tensors back"
            return self.pipeline.return_value
        self.steps_seen = 0
        self.pipeline.side_effect = run

    def test_the_pipeline_gets_a_step_end_callback_without_tensors(self) -> None:
        """Handing no tensors to the callback keeps the latents out of its hands."""
        self.backend.load(Path("/models/w.safetensors"), None)
        self.pipeline_running(3)
        self.assertEqual(b"PNG:PNG", self.backend.generate(self.rows, self.settings, [], worker.Cancellation()))
        self.assertEqual(3, self.steps_seen)
        self.assertEqual([], self.pipeline.call_args.kwargs["callback_on_step_end_tensor_inputs"])

    def test_cancel_stops_at_the_step_boundary_and_returns_no_image(self) -> None:
        """The callback raises after the step that was running, so later steps and the decode never happen."""
        self.backend.load(Path("/models/w.safetensors"), None)
        cancel = worker.Cancellation()
        self.pipeline_running(10, lambda step: cancel.request() if step == 3 else None)
        with self.assertRaises(worker.JobCancelled):
            self.backend.generate(self.rows, self.settings, [], cancel)
        self.assertEqual(4, self.steps_seen)

    def test_a_cancel_before_the_first_step_never_calls_the_pipeline(self) -> None:
        """A request that arrives while the prompts are encoded stops the job before any denoising."""
        self.backend.load(Path("/models/w.safetensors"), None)
        cancel = worker.Cancellation()
        cancel.request()
        with self.assertRaises(worker.JobCancelled):
            self.backend.generate(self.rows, self.settings, [], cancel)
        self.pipeline.assert_not_called()

    def test_a_cancelled_job_still_removes_its_loras_and_is_no_failure(self) -> None:
        """A cancel is reported as a cancel, not as `generation-failed`, and no style outlives the job."""
        self.backend.load(Path("/models/w.safetensors"), None)
        cancel = worker.Cancellation()
        self.pipeline_running(5, lambda step: cancel.request() if step == 1 else None)
        loras = [worker.LoraChoice(Path("/models/loras/a.safetensors"), 0.5)]
        with self.assertRaises(worker.JobCancelled):
            self.backend.generate(self.rows, self.settings, loras, cancel)
        self.pipeline.delete_adapters.assert_called_once_with(["job-lora-0"])

    def test_trim_returns_cached_memory_and_keeps_the_pipeline(self) -> None:
        """What a stopped job left cached goes back to the card while the model stays resident."""
        self.backend.trim()
        self.torch.cuda.empty_cache.assert_not_called()
        self.backend.load(Path("/models/w.safetensors"), None)
        self.backend.trim()
        self.torch.cuda.empty_cache.assert_called_once_with()
        self.assertIs(self.pipeline.tokenizer, self.backend.tokenizers()[0])

    def test_versions_name_only_installed_libraries(self) -> None:
        """A library that is absent is left out instead of being reported as unknown."""
        def lookup(name: str) -> str:
            if name == "peft":
                raise worker.importlib.metadata.PackageNotFoundError(name)
            return f"{name}-1"
        with mock.patch.object(worker.importlib.metadata, "version", side_effect=lookup):
            found = self.backend.versions()
        self.assertEqual({name: f"{name}-1" for name in worker.PINNED_LIBRARIES if name != "peft"}, found)

    def test_unload_returns_cached_memory_and_is_safe_twice(self) -> None:
        """Whether this really frees the card is unproven until a GPU run measures it."""
        self.backend.unload()
        self.torch.cuda.empty_cache.assert_not_called()
        self.backend.load(Path("/models/w.safetensors"), None)
        self.backend.unload()
        self.torch.cuda.empty_cache.assert_called_once_with()
        self.torch.cuda.ipc_collect.assert_called_once_with()
        self.backend.unload()
        self.torch.cuda.empty_cache.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
