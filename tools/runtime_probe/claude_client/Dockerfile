# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Runtime-probe image of the second narration client (P20.1-A), not the packaged service.
#
# It installs the unmodified upstream client at one exact version from the public npm
# registry. Both registry archives are checked against the SHA-512 integrity that the
# registry publishes for that version before anything reads them. The client is then
# installed by extracting the platform binary with `tar`: no stage contains Node.js,
# npm or another package manager, so neither the `postinstall` script of the wrapper
# package (`node install.cjs`) nor any other lifecycle script can run. The image holds
# no credentials, account state, engine code or development configuration.
#
# Pins (mirrored in docs/adr/0009-toolchain-baseline.md and checked by
# tools/runtime_probe/test_claude_client.py; change all three together):
# - base image: the Debian 13 slim reference of the first narration runtime image;
# - client: npm `@anthropic-ai/claude-code` and its `linux-x64` (glibc) platform package,
#   version 2.1.323, each with its registry `dist.integrity`. Only linux/amd64 is pinned.
#
# Stages: `fetch` downloads and verifies; `integrity-selftest` proves that the verifier
# rejects a modified archive and malformed pins; `client` (the default target) holds
# only the verified binary. The selftest stage is built only when it is the target.

ARG BASE_IMAGE=debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad
ARG CLIENT_VERSION=2.1.323
ARG WRAPPER_INTEGRITY=sha512-6YjP5aY9xKZfYsYOoUDZ/AKNbPCSOnjMvSI2vdfoBjBN5adCeQIZ3u/QkEFK21Z0PMdssQWOyw75a1IeS0B65g==
ARG PLATFORM_INTEGRITY=sha512-cUAH9W7KrcfM5v7hWjvv1rurvAAgvOe+BVfOlk17sXH3Vqh9V+tVj0WZin6RiJ8MiZ7RpEtVfaVKPcgrY3x6Vw==

FROM ${BASE_IMAGE} AS fetch
ARG TARGETARCH
ARG CLIENT_VERSION
ARG WRAPPER_INTEGRITY
ARG PLATFORM_INTEGRITY
# Other architectures have no pinned integrity yet and must fail instead of fetching
# an unverified archive.
RUN test "$TARGETARCH" = amd64 || { echo "only linux/amd64 is pinned" >&2; exit 1; }
COPY --chmod=0555 verify-sri.sh /usr/local/bin/verify-sri
# A remote ADD has no SHA-512 checksum option, so the next RUN verifies both archives
# before extracting them; a replaced archive fails the build there.
ADD https://registry.npmjs.org/@anthropic-ai/claude-code/-/claude-code-${CLIENT_VERSION}.tgz /fetch/wrapper.tgz
ADD https://registry.npmjs.org/@anthropic-ai/claude-code-linux-x64/-/claude-code-linux-x64-${CLIENT_VERSION}.tgz /fetch/platform.tgz
# The wrapper package is fetched only to prove that this exact version names the same
# platform package version; its scripts and placeholder `bin/claude.exe` are never
# used. The platform archive holds `package/claude`, installed unchanged.
RUN verify-sri /fetch/wrapper.tgz "$WRAPPER_INTEGRITY" \
 && verify-sri /fetch/platform.tgz "$PLATFORM_INTEGRITY" \
 && mkdir /fetch/wrapper /fetch/platform \
 && tar -xzf /fetch/wrapper.tgz -C /fetch/wrapper --no-same-owner \
 && tar -xzf /fetch/platform.tgz -C /fetch/platform --no-same-owner \
 && grep -Eq "\"version\"[[:space:]]*:[[:space:]]*\"$CLIENT_VERSION\"" /fetch/wrapper/package/package.json \
 && grep -Eq "\"@anthropic-ai/claude-code-linux-x64\"[[:space:]]*:[[:space:]]*\"$CLIENT_VERSION\"" /fetch/wrapper/package/package.json \
 && grep -Eq "\"version\"[[:space:]]*:[[:space:]]*\"$CLIENT_VERSION\"" /fetch/platform/package/package.json \
 && test -f /fetch/platform/package/claude \
 && install -m 0755 /fetch/platform/package/claude /fetch/claude

FROM fetch AS integrity-selftest
ARG WRAPPER_INTEGRITY
ARG PLATFORM_INTEGRITY
# Negative controls for the verifier, with the accepted case last as a positive
# control: a one-byte change, another archive's digest, another algorithm, a short
# digest and a second entry must all be refused with the documented exit status.
RUN cp /fetch/wrapper.tgz /tmp/modified.tgz && printf '\n' >> /tmp/modified.tgz \
 && { verify-sri /tmp/modified.tgz "$WRAPPER_INTEGRITY"; test "$?" -eq 1; } \
 && { verify-sri /fetch/wrapper.tgz "$PLATFORM_INTEGRITY"; test "$?" -eq 1; } \
 && { verify-sri /fetch/wrapper.tgz "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="; test "$?" -eq 2; } \
 && { verify-sri /fetch/wrapper.tgz "sha512-AAAA"; test "$?" -eq 2; } \
 && { verify-sri /fetch/wrapper.tgz "$WRAPPER_INTEGRITY $PLATFORM_INTEGRITY"; test "$?" -eq 2; } \
 && { verify-sri /fetch/missing.tgz "$WRAPPER_INTEGRITY"; test "$?" -eq 1; } \
 && verify-sri /fetch/wrapper.tgz "$WRAPPER_INTEGRITY" \
 && rm /tmp/modified.tgz \
 && echo "integrity selftest passed" > /fetch/selftest-passed

FROM ${BASE_IMAGE} AS client
# The same fixed non-root identity as the first narration runtime image (UID/GID 10001),
# so later isolation checks can reuse its mount layout.
RUN groupadd --gid 10001 runtime \
 && useradd --uid 10001 --gid 10001 --no-create-home \
      --home-dir /var/lib/runtime/home --shell /usr/sbin/nologin runtime \
 && install -d -o 10001 -g 10001 -m 0700 /var/lib/runtime/home /var/lib/runtime/workspace
COPY --from=fetch /fetch/claude /usr/local/bin/claude
ENV HOME=/var/lib/runtime/home \
    NO_COLOR=1
USER 10001:10001
WORKDIR /var/lib/runtime/workspace
ENTRYPOINT ["/usr/local/bin/claude"]
CMD ["--version"]
