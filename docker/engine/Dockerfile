# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Build the single-origin application from reviewed inputs only. No host output,
# account, secret or development configuration enters any stage (.dockerignore).
# ADR 0009 permits these exact Java pins for development; refresh before release.
FROM node:24.22.1-trixie-slim@sha256:43b9ba1142b8dcf33fa8f922ca1be85714adc444e0586c7cf7726daaeabc1bea AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-fund --no-audit
COPY frontend/ ./
# Type checks and unit tests stay in the build, so cached production output was
# checked with the same pinned tools. Audit is a separate online verification.
RUN npm run typecheck && npm test && npm run test:i18n && npm run build:locales

FROM eclipse-temurin:21.0.12.1_1-jdk-resolute@sha256:3b59c79a788985965c89203cca233c53893f62293108bb825264740fac926724 AS verification
# The checksum-verified Maven wrapper extracts its pinned ZIP with unzip; without
# it the wrapper falls back to the JDK jar tool (ADR 0009). Keep the usual path.
# OS packages follow the reviewed base archive policy in ADR 0009.
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /build/backend
COPY backend/ ./
COPY --from=frontend /build/frontend/dist/ /build/frontend/dist/
RUN chmod 0555 mvnw
# Package with the account-free configuration tests. This also retains Maven,
# dependencies and the test provider in the verification image, allowing the
# full suite to run offline on an internal fixture network without build secrets.
# No build-time database connection or skipped-test release claim is involved.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp clean package -Ppackaged-ui -Dtest=PackagedSecretsTest \
 && cp -a /root/.m2 /opt/verification-maven
# Cache mounts are not image layers. Retain a verification-only copy for offline
# tests; the final JRE stage copies only the jar and cannot inherit this cache.
ENV MAVEN_USER_HOME=/opt/verification-maven
CMD ["./mvnw", "-B", "-ntp", "--offline", "-Dmaven.repo.local=/opt/verification-maven/repository", "clean", "verify", "-Ppackaged-ui"]

FROM eclipse-temurin:21.0.12.1_1-jre-resolute@sha256:e7b42d0fad9b3dd6d61d6816ce3984df079a5da7d8e9af81c464243d15c04e3e AS engine
# Runtime and client share UID 10001 solely for the owner-only control socket.
# Separate mounts/network membership, rather than UID, isolate account and data.
RUN groupadd --gid 10001 engine \
 && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin engine \
 && install -d -o 10001 -g 10001 -m 0700 /var/lib/engine/content /var/lib/engine/assets \
 && install -d -o 0 -g 0 -m 0755 /opt/engine
COPY --from=verification --chown=0:0 --chmod=0444 /build/backend/target/engine-0.1.0.jar /opt/engine/engine.jar
USER 10001:10001
WORKDIR /var/lib/engine
EXPOSE 8080
# Minimal HTTP/database health only; account and gameplay readiness are P02.5.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --start-interval=1s --retries=6 \
 CMD curl --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null || exit 1
# Exec form delivers shutdown directly to Java; Compose supplies the grace time.
ENTRYPOINT ["java", "-jar", "/opt/engine/engine.jar"]
