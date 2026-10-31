# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Image worker (Compose service `image-worker`): one Python file on the pinned Python image, with
# PyTorch for CUDA and diffusers installed by exact version and hash. It holds no model weights:
# those stay on the owner's read-only model directory. Only this build has network access.
#
# Base pin: the same digest as the gateway and the model fetch helper, recorded in
# docs/adr/0009-toolchain-baseline.md. Change them together.

FROM python:3.15.1-slim-trixie@sha256:533b0b2f9b5d46fd097091cf826f27902f1d6c3bca714b6dc74551356aa97a51

# Root-owned, so the service account cannot change it. The mode also applies to the directory
# this creates, which the account must be able to enter.
COPY --chmod=0755 image_worker.py build-requirements.lock requirements.lock /opt/image-worker/

RUN ["python", "-I", "-B", "-m", "pip", "install", "--no-cache-dir", "--no-input", "--require-hashes", "--no-deps", "--requirement", "/opt/image-worker/build-requirements.lock"]
# PyYAML 6.0.3 has no wheel for Python 3.15. Its pure Python form needs no compiler, and without
# build isolation its unpinned declared build tools are never downloaded.
RUN ["env", "PYYAML_FORCE_LIBYAML=0", "python", "-I", "-B", "-m", "pip", "install", "--no-cache-dir", "--no-input", "--require-hashes", "--no-deps", "--no-build-isolation", "--requirement", "/opt/image-worker/requirements.lock"]
RUN ["python", "-I", "-B", "-m", "pip", "check"]

# A single-file checkpoint holds weights only. The loader's configurations and tokenizers are
# these files of the revision that the catalog pins for SDXL base 1.0, so that a load never needs
# the network. Remote files default to mode 0600, which the service account could not read.
ADD --chmod=0644 --checksum=sha256:6d7b93508390ab91ac5bfbe4aeb4dc2d83f7bb1b05fb069d714b5b0c75f70d44 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/model_index.json /opt/image-worker/sdxl-config/model_index.json
ADD --chmod=0644 --checksum=sha256:af3e45a949aff8b8341ab8b811429ec03fee857a700a1d9477363e4fff9666e2 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/scheduler/scheduler_config.json /opt/image-worker/sdxl-config/scheduler/scheduler_config.json
ADD --chmod=0644 --checksum=sha256:39b8b2e4b1949e36969caa425b6c81c68bace99198dd9078ce05d16ad401fe7f https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/text_encoder/config.json /opt/image-worker/sdxl-config/text_encoder/config.json
ADD --chmod=0644 --checksum=sha256:a892d1c3a69a7e9247a24de2bc1d5891e3109a54696e53be20093af671072c34 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/text_encoder_2/config.json /opt/image-worker/sdxl-config/text_encoder_2/config.json
ADD --chmod=0644 --checksum=sha256:9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer/merges.txt /opt/image-worker/sdxl-config/tokenizer/merges.txt
ADD --chmod=0644 --checksum=sha256:c4864a9376a8401918425bed71fc14fc0e81f9b59ec45c1cf96cccb2df508eac https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer/special_tokens_map.json /opt/image-worker/sdxl-config/tokenizer/special_tokens_map.json
ADD --chmod=0644 --checksum=sha256:19d7b034cb0cc3ce9766c2231373ab8aa8991fc72e2c8f76558bfaae3de0d563 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer/tokenizer_config.json /opt/image-worker/sdxl-config/tokenizer/tokenizer_config.json
ADD --chmod=0644 --checksum=sha256:e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer/vocab.json /opt/image-worker/sdxl-config/tokenizer/vocab.json
ADD --chmod=0644 --checksum=sha256:9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer_2/merges.txt /opt/image-worker/sdxl-config/tokenizer_2/merges.txt
ADD --chmod=0644 --checksum=sha256:f118ab3a983206e4f32583448de6bd6aae4ee21869135cef1f5848a753cdaab6 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer_2/special_tokens_map.json /opt/image-worker/sdxl-config/tokenizer_2/special_tokens_map.json
ADD --chmod=0644 --checksum=sha256:c9d23941f76a41cbd50eda9290f57be7828f0a7a677939e9ef181f7e12bd1bdf https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer_2/tokenizer_config.json /opt/image-worker/sdxl-config/tokenizer_2/tokenizer_config.json
ADD --chmod=0644 --checksum=sha256:e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/tokenizer_2/vocab.json /opt/image-worker/sdxl-config/tokenizer_2/vocab.json
ADD --chmod=0644 --checksum=sha256:30ebc70750223e59006f7f2b4e1e6c102570aa19a9c4ae3e1fbe7591332dbae6 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/unet/config.json /opt/image-worker/sdxl-config/unet/config.json
ADD --chmod=0644 --checksum=sha256:0b331c8ac22ded5f9997a144a575c1d113d6169aff262c353f39015bd24a6264 https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/462165984030d82259a11f4367a4eed129e94a7b/vae/config.json /opt/image-worker/sdxl-config/vae/config.json

# The account has no writable home: library caches stay on the tmpfs, and these switches refuse any
# lookup online even if a library tries one. The Compose network has no route out anyway.
ENV HF_HOME=/tmp/huggingface HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 HF_HUB_DISABLE_TELEMETRY=1 PYTHONDONTWRITEBYTECODE=1

USER 10003:10003
ENTRYPOINT ["python", "-I", "-B", "/opt/image-worker/image_worker.py"]
