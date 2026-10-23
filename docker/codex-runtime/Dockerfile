# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Narration runtime image (Compose service `codex`).
#
# The image contains only the pinned upstream runtime release binary on a
# digest-pinned Debian base. It contains no engine code, no database client,
# no credentials, and no development configuration. Account state lives in a
# private volume mounted at CODEX_HOME; the control socket lives in a separate
# IPC volume shared only with the engine.
#
# Pins (mirrored in docs/adr/0009-toolchain-baseline.md; change both together):
# - base image: Debian 13 slim, dated tag plus multi-platform index digest;
# - runtime: GitHub release `rust-v0.164.0` of openai/codex, statically linked
#   musl build, verified against the SHA-256 digests published for the release.

ARG BASE_IMAGE=debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad

# One fetch stage per architecture. `ADD --checksum` makes BuildKit verify the
# download before any later step can use it, so a replaced release asset fails
# the build instead of producing an unpinned image. Only the stage matching
# TARGETARCH is built.
FROM ${BASE_IMAGE} AS fetch-amd64
ADD --checksum=sha256:90dd0b03b28afcb7fa733a4f73b413db2916a47347efd43fb669cc1c774ee8e4 \
    https://github.com/openai/codex/releases/download/rust-v0.164.0/codex-x86_64-unknown-linux-musl.tar.gz \
    /fetch/runtime.tar.gz

FROM ${BASE_IMAGE} AS fetch-arm64
ADD --checksum=sha256:29a507a2a3fd8f6cd94b96b762d281d299d15d7902dee6132d314b78cf9108eb \
    https://github.com/openai/codex/releases/download/rust-v0.164.0/codex-aarch64-unknown-linux-musl.tar.gz \
    /fetch/runtime.tar.gz

FROM fetch-${TARGETARCH} AS fetch
# The archive holds a single binary named after its target triple; install it
# under the stable name `codex` so launch arguments do not depend on the
# architecture.
RUN mkdir /fetch/out \
 && tar -xzf /fetch/runtime.tar.gz -C /fetch/out \
 && set -- /fetch/out/codex-*-unknown-linux-musl \
 && test "$#" -eq 1 && test -f "$1" \
 && install -m 0755 "$1" /fetch/codex

FROM ${BASE_IMAGE} AS runtime

# ca-certificates is the only added package: the runtime needs trusted roots
# for TLS to the inference and sign-in services. Package versions are not
# pinned because the Debian archive drops superseded versions (ADR 0009).
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Fixed non-root identity (UID/GID 10001). Verified behavior of the pinned
# runtime: for `--listen unix://PATH` it binds the real socket, mode 0600, at
# /tmp/codex-daemon-<uid>/<sha256(PATH)> inside a 0700 directory and leaves
# only a symlink at PATH. PATH's parent must not be writable by other users.
# The socket is therefore owner-only: a client must run as UID 10001 and reach
# the socket directory itself, not the symlink. The packaged profile mounts the
# IPC volume at the socket directory; the engine mounts the same volume and
# connects as UID 10001. File isolation between the two services comes from
# their mounts: the engine never mounts the account volume.
RUN groupadd --gid 10001 runtime \
 && useradd --uid 10001 --gid 10001 --no-create-home \
      --home-dir /var/lib/runtime/home --shell /usr/sbin/nologin runtime \
 && install -d -o 10001 -g 10001 -m 0700 \
      /var/lib/runtime/home /var/lib/runtime/workspace \
      /run/runtime /tmp/codex-daemon-10001

COPY --from=fetch /fetch/codex /usr/local/bin/codex

# CODEX_HOME isolates account and session state from any inherited user
# configuration. RUST_LOG keeps stderr at warnings so diagnostics stay bounded;
# NO_COLOR removes ANSI escapes so stderr can be redacted and logged as text.
ENV CODEX_HOME=/var/lib/runtime/home \
    HOME=/var/lib/runtime/home \
    RUST_LOG=warn \
    NO_COLOR=1

USER 10001:10001
WORKDIR /var/lib/runtime/workspace

# Packaged profile: app-server on a Unix socket whose real file lives in the
# IPC volume mounted at /tmp/codex-daemon-10001 (file name: SHA-256 of
# /run/runtime/control.sock). The stdio probe overrides the command with
# `app-server --listen stdio://`. Features that reach remote catalogs or
# update services at startup are disabled; analytics are off by default.
ENTRYPOINT ["/usr/local/bin/codex"]
CMD ["app-server", "--listen", "unix:///run/runtime/control.sock", \
     "--disable", "plugins", "--disable", "remote_plugin", \
     "--disable", "apps", "--disable", "in_app_updates"]
