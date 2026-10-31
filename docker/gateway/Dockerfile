# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Gateway image (Compose service `gateway`): one standard-library file on the pinned Python
# image. It holds no key and no destination: both are mounted at run time, and nothing here
# reads a build argument or a secret mount.
#
# Base pin: the same digest as the model fetch helper in compose.yaml, recorded in
# docs/adr/0009-toolchain-baseline.md. Change them together.

FROM python:3.15.1-slim-trixie@sha256:533b0b2f9b5d46fd097091cf826f27902f1d6c3bca714b6dc74551356aa97a51

# Root-owned, so the service account cannot change it. The mode also applies to the directory
# this creates, which the account must be able to enter: a mode without execute stops startup.
COPY --chmod=0755 gateway.py /opt/gateway/gateway.py

USER 10002:10002
ENTRYPOINT ["python", "-I", "-B", "/opt/gateway/gateway.py"]
