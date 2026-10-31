#!/usr/bin/env bash
# start.sh - single entry point for the packaged local installation (P02.6).
#
# Purpose: validate the host, initialize private configuration, build the pinned
# images with the Docker layer cache, start the Compose project, wait for
# health-based readiness, and offer sign-in, status, log and stop commands.
#
# Usage: ./start.sh [--check | --login | --status | --logs [SERVICE] [--no-follow]
#                    | --stop | --models profile [--gpu-check]
#                    | --models fetch [--accept-license ID[,ID...]] ID... | --help]
#                    (no argument: validate, build, start)
#
# Exit status: 0 success; 1 failure (prerequisite, configuration, build, start,
# health timeout, or a command that could not run); 2 usage error; 3 only for
# --status, meaning storage is ready but gameplay is not yet available.
#
# Design rules (docs/22-container-deployment-and-startup.md, ADR 0008/0009):
#   * Host commands are limited to Bash builtins, docker (Compose and Buildx) and
#     the utilities listed in ADR 0009 "Shell requirements": cat chmod dirname
#     grep head mkdir od sed tr uname (plus cygpath when present).
#   * Nothing here resets data: no volume is ever removed, no existing secret is
#     overwritten or regenerated, and no unrelated Docker resource is touched.
#   * No secret value is ever printed; log output passes through a redactor.
#   * Readiness comes from the engine's health groups, never from container
#     creation order, and never triggers live inference.

# --- Bash floor --------------------------------------------------------------
# This check must run before any construct that older shells cannot parse.
# Bash reads and executes one top-level command at a time, so exiting here
# protects a Bash 3.2 (macOS default) user from a cryptic syntax error later.
# 4.4 is the first release where an empty array expansion is safe under `set -u`.
if [ -z "${BASH_VERSINFO:-}" ] || [ "${BASH_VERSINFO[0]}" -lt 4 ] ||
   { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -lt 4 ]; }; then
    echo "start.sh: GNU Bash 4.4 or later is required (found ${BASH_VERSION:-unknown})." >&2
    echo "start.sh: install a newer Bash and run this script with it." >&2
    exit 1
fi

set -euo pipefail

# --- Constants ---------------------------------------------------------------
# Verified floors and matrix (ADR 0009 "Docker Engine, Compose, and Buildx").
readonly MIN_ENGINE_VERSION="29.0"
readonly MIN_COMPOSE_VERSION="5.0"
readonly VERIFIED_MATRIX="Docker Desktop 4.96.3 (WSL 2, Linux containers) with Engine 29.9.2, Compose v5.6.1, Buildx v0.38.1, Git Bash 5.3"
# Seconds to wait for the engine's readiness group after containers are healthy.
readonly READY_TIMEOUT=180
# Seconds Compose may wait for container health checks (first start initializes
# the database cluster and applies migrations).
readonly WAIT_TIMEOUT=300
# Log lines shown by --logs and in failure diagnostics (bounded on purpose).
readonly LOG_TAIL=200
readonly DIAG_TAIL=40
# Supported sign-in method for the pinned runtime. API-key sign-in is not an
# accepted fallback: the engine uses the owner's account only.
readonly LOGIN_ARGS=(-c cli_auth_credentials_store=file -c forced_login_method=chatgpt)
readonly AUTH_DOC_URL="https://learn.chatgpt.com/docs/auth"
readonly SERVICES="engine postgres codex"

# Resolve the script's own directory so the launcher works from any working
# directory and when the checkout path contains spaces. All later paths are
# relative to it, which also keeps absolute MSYS paths away from docker.exe.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd -- "$SCRIPT_DIR"

# Git Bash (MSYS) rewrites arguments that look like POSIX paths before native
# docker.exe sees them; container paths such as /tmp would be corrupted.
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        export MSYS_NO_PATHCONV=1
        export MSYS2_ARG_CONV_EXCL='*'
        ;;
esac

# Private, ignored state directory (see .gitignore: /.local/).
readonly STATE_DIR=".local"
readonly SECRET_DIR="$STATE_DIR/secrets"
readonly ID_FILE="$STATE_DIR/installation-id"
readonly DEFAULT_SECRET_FILE="$SECRET_DIR/postgres-password"

# Filled in by resolve_config / identity helpers.
APP_PORT_VALUE=""
SECRET_FILE=""
PROJECT=""

# --- Output helpers ----------------------------------------------------------

# info MESSAGE... - progress line on stdout.
info() { printf 'start.sh: %s\n' "$*"; }

# warn MESSAGE... - non-fatal problem on stderr.
warn() { printf 'start.sh: warning: %s\n' "$*" >&2; }

# die MESSAGE... - print an actionable failure on stderr and exit 1. Callers
# must never pass secret values; diagnostics go through redact_stream instead.
die() {
    printf 'start.sh: error: %s\n' "$*" >&2
    exit 1
}

# usage - print the command summary. Used by --help (stdout) and usage errors.
usage() {
    cat <<'EOF'
Usage: ./start.sh [COMMAND]

  (no command)  Validate the host, create missing private configuration, build
                images with the layer cache, start the services, wait for
                readiness and print the local URL.
  --check       Inspect prerequisites and configuration; changes nothing.
  --login       Run the supported owner-controlled runtime sign-in (device code).
  --status      Show service health, storage readiness and gameplay readiness.
  --logs [SERVICE] [--no-follow]
                Show the last 200 redacted log lines, then follow. SERVICE is
                engine, postgres or codex.
  --stop        Stop and remove this installation's containers and networks.
                Volumes (database, content, assets, runtime account) are kept.
  --models profile [--gpu-check]
                Write the hardware profile .local/hardware.json for the model
                advisor (GPU memory from the NVIDIA driver tool, RAM, disk,
                container memory). --gpu-check also runs the pinned model-server
                image (about 2.6 GB, pulled once) to test container GPU access.
  --models fetch [--accept-license ID[,ID...]] ID...
                Show the license and source of each named catalog model, ask for
                confirmation, download the files and install them in the model
                directory (MODELS_DIR, default .local/models) only after their
                exact size and SHA-256 match the catalog. Any failure installs
                nothing. --accept-license records a confirmation already given:
                it must name each model it accepts, among those requested, and
                without a terminal every requested model must be named there.
                Wildcards are refused, and nothing is downloaded otherwise.
  --help        Show this text.

Prerequisites: GNU Bash 4.4+, Docker Engine 29.0+ running Linux containers,
the Docker Compose plugin 5.0+ and the Buildx plugin. About 10 GB of free Docker
disk space and network access are needed for the first build. No host Java,
Maven, Node or npm is needed.

Sign-in: gameplay needs the owner's runtime account. Run ./start.sh --login and
open the displayed link. Device-code login must be enabled in the account's
security settings (managed workspaces: an administrator must allow it); see
https://learn.chatgpt.com/docs/auth. The launcher never changes account settings.

Exit status: 0 success, 1 failure, 2 usage error, 3 (--status) storage ready
but gameplay not ready.
EOF
}

# --- Small utilities ---------------------------------------------------------

# version_ge ACTUAL MINIMUM - succeed when dotted version ACTUAL >= MINIMUM.
# Compares up to three numeric components; a leading "v" and any suffix after
# the numbers are ignored. Non-numeric components count as 0.
version_ge() {
    local actual="${1#v}" minimum="${2#v}"
    local -a a=() b=()
    local IFS=.
    read -r -a a <<<"${actual%%[!0-9.]*}"
    read -r -a b <<<"${minimum%%[!0-9.]*}"
    local i x y
    for i in 0 1 2; do
        x="${a[i]:-0}"
        y="${b[i]:-0}"
        # 10# forces decimal so a component such as "08" is not read as octal.
        if ((10#${x:-0} > 10#${y:-0})); then return 0; fi
        if ((10#${x:-0} < 10#${y:-0})); then return 1; fi
    done
    return 0
}

# config_value NAME DEFAULT - effective setting with Compose's precedence:
# exported environment first, then the ignored .env file, then DEFAULT.
# The .env file is parsed, never sourced, so its content cannot run code.
config_value() {
    local name="$1" default="$2" value=""
    if [ -n "${!name:-}" ]; then
        printf '%s' "${!name}"
        return 0
    fi
    if [ -f .env ]; then
        # Last assignment wins; strip CR (files edited on Windows), an inline
        # comment on unquoted values, and one pair of surrounding quotes.
        value="$(grep -E "^[[:space:]]*${name}=" .env | tail_one | tr -d '\r' || true)"
        value="${value#*=}"
        case "$value" in
            \"*\") value="${value#\"}"; value="${value%\"}" ;;
            \'*\') value="${value#\'}"; value="${value%\'}" ;;
            *) value="$(printf '%s' "$value" | sed -E 's/[[:space:]]+#.*$//; s/[[:space:]]+$//')" ;;
        esac
    fi
    printf '%s' "${value:-$default}"
}

# tail_one - print only the last line of stdin (tail is not in the host-command
# allowlist, so use sed).
tail_one() { sed -n '$p'; }

# compose ARGS... - run Compose for this installation. COMPOSE_PROJECT_NAME is
# exported by use_identity so every call targets exactly one project and never
# another installation's services or volumes.
compose() { docker compose --file compose.yaml "$@"; }

# redact_stream - filter stdin to stdout, masking credential-shaped text and the
# database password value. Defence in depth: services should not log secrets,
# but diagnostics shown to the owner must never be the place they leak.
redact_stream() {
    local secret="" line
    if [ -n "$SECRET_FILE" ] && [ -f "$SECRET_FILE" ]; then
        # Builtin read: the value never appears in a process argument list.
        secret="$(<"$SECRET_FILE")" || secret=""
    fi
    # GNU sed (Git Bash, Linux): case-insensitive key=value, bearer tokens,
    # signed tokens and common API-key prefixes.
    sed -E \
        -e 's/Bearer +[A-Za-z0-9._~+\/=-]+/Bearer [redacted]/Ig' \
        -e 's/(password|passwd|secret|token|api[_-]?key|authorization)([=:"'"'"' ]+)[^ ,"'"'"'&]+/\1\2[redacted]/Ig' \
        -e 's/eyJ[A-Za-z0-9_-]{10,}(\.[A-Za-z0-9_-]+){0,2}/[redacted]/g' \
        -e 's/sk-[A-Za-z0-9_-]{16,}/[redacted]/g' |
        while IFS= read -r line || [ -n "$line" ]; do
            # Literal (quoted) replacement of the exact password value, which an
            # owner-provisioned secret might make unrecognizable to the patterns.
            if [ "${#secret}" -ge 8 ]; then line="${line//"$secret"/[redacted]}"; fi
            printf '%s\n' "$line"
        done
}

# on_interrupt - Ctrl+C / termination handler for long operations. Completed
# steps are reused by the next run, so the only duty is a clear message.
on_interrupt() {
    printf '\nstart.sh: interrupted. Nothing was reset; rerun ./start.sh to continue (completed steps are reused).\n' >&2
    exit 130
}

# Sign-in bookkeeping shared with the interrupt handler.
LOGIN_CONTAINER=""
LOGIN_RESTORE=0

# restore_runtime - restart the runtime service when --login paused it, then
# clear the flag so the work is done once. Returns the restart status.
restore_runtime() {
    [ "$LOGIN_RESTORE" -eq 1 ] || return 0
    LOGIN_RESTORE=0
    compose up --detach --no-build --wait --wait-timeout 120 codex >/dev/null 2>&1
}

# on_login_interrupt - Ctrl+C / termination during --login: remove the one-off
# sign-in container (it is ours; the account volume is untouched), put the
# runtime service back as it was, and leave with the interrupt status.
on_login_interrupt() {
    printf '
start.sh: sign-in interrupted. Nothing was reset; rerun ./start.sh --login to try again.
' >&2
    [ -z "$LOGIN_CONTAINER" ] || docker rm --force "$LOGIN_CONTAINER" >/dev/null 2>&1 || true
    restore_runtime || true
    exit 130
}

# --- Host prerequisites --------------------------------------------------------

# require_utilities - fail when a utility used by this script is missing, naming
# it. Only the allowlisted utilities are required (ADR 0009).
require_utilities() {
    local tool
    for tool in cat chmod dirname grep mkdir od sed tr uname; do
        command -v "$tool" >/dev/null 2>&1 || die "required host utility '$tool' was not found on PATH."
    done
}

# check_docker - verify CLI, daemon (Linux containers), Engine, Compose and
# Buildx floors. Prints one summary line on success; exits with an actionable
# message otherwise. Never installs software, switches contexts or elevates.
check_docker() {
    command -v docker >/dev/null 2>&1 ||
        die "the 'docker' CLI was not found. Install Docker Desktop (Windows/macOS) or Docker Engine (Linux), then rerun."
    local context info engine_os engine_version compose_version
    context="$(docker context show 2>/dev/null || true)"
    # One call returns both values; a failure means the daemon is not reachable.
    if ! info="$(docker info --format '{{.OSType}} {{.ServerVersion}}' 2>/dev/null)"; then
        die "the Docker daemon is not reachable (context '${context:-unknown}'). Start Docker Desktop or the Docker service and rerun. The launcher does not change Docker contexts."
    fi
    engine_os="${info%% *}"
    engine_version="${info#* }"
    [ "$engine_os" = "linux" ] ||
        die "the Docker daemon runs '$engine_os' containers; switch Docker Desktop to Linux containers (WSL 2 backend) and rerun."
    version_ge "$engine_version" "$MIN_ENGINE_VERSION" ||
        die "Docker Engine $engine_version is older than the required $MIN_ENGINE_VERSION. Update Docker."
    compose_version="$(docker compose version --short 2>/dev/null || true)"
    [ -n "$compose_version" ] ||
        die "the Docker Compose plugin (5.0 or later) was not found. The legacy 'docker-compose' command is not supported."
    version_ge "$compose_version" "$MIN_COMPOSE_VERSION" ||
        die "Docker Compose $compose_version is older than the required $MIN_COMPOSE_VERSION. Update Docker."
    docker buildx version >/dev/null 2>&1 ||
        die "the Docker Buildx plugin was not found; Compose 5 builds through it. Install or update Docker."
    DOCKER_SUMMARY="context '${context:-unknown}', Engine $engine_version (linux), Compose ${compose_version#v}, Buildx present"
}

# --- Configuration -------------------------------------------------------------

# resolve_config - read and validate the effective settings used by the launcher
# (APP_PORT and the secret file location). Exits on invalid values. Only paths
# and labels are handled here; no secret value is read.
resolve_config() {
    APP_PORT_VALUE="$(config_value APP_PORT 8080)"
    case "$APP_PORT_VALUE" in
        '' | *[!0-9]*) die "APP_PORT '$APP_PORT_VALUE' is not a number; set a port from 1 to 65535 in .env." ;;
    esac
    if [ "$((10#$APP_PORT_VALUE))" -lt 1 ] || [ "$((10#$APP_PORT_VALUE))" -gt 65535 ]; then
        die "APP_PORT $APP_PORT_VALUE is outside 1-65535."
    fi
    SECRET_FILE="$(config_value POSTGRES_PASSWORD_FILE "$DEFAULT_SECRET_FILE")"
    # Compose needs a path docker.exe understands. An absolute MSYS path such as
    # /c/Users/... is converted to mixed form; relative paths resolve from here.
    case "$SECRET_FILE" in
        /*)
            if command -v cygpath >/dev/null 2>&1; then
                export POSTGRES_PASSWORD_FILE
                POSTGRES_PASSWORD_FILE="$(cygpath -m "$SECRET_FILE")"
            fi
            ;;
    esac
}

# load_identity - set PROJECT from the stored installation id without creating
# anything. Returns 1 when no id exists yet; dies when the file is malformed
# (regenerating would orphan the existing volumes).
load_identity() {
    [ -f "$ID_FILE" ] || return 1
    local id
    id="$(head -n 1 "$ID_FILE" | tr -d '\r')"
    case "$id" in
        '' | *[!0-9a-f]*) die "$ID_FILE is malformed. Restore it from backup; do not delete it, because it names this installation's Docker volumes." ;;
    esac
    [ "${#id}" -eq 12 ] || die "$ID_FILE is malformed (expected 12 lowercase hex characters)."
    PROJECT="rpg-engine-$id"
    export COMPOSE_PROJECT_NAME="$PROJECT"
}

# ensure_identity - create the stable per-checkout installation id when missing.
# The id makes the Compose project name unique to this checkout, so two clones
# cannot silently share containers, networks or volumes, while repeated launches
# of this checkout always reuse the same project.
ensure_identity() {
    if load_identity; then return 0; fi
    require_random_source
    mkdir -p -- "$STATE_DIR"
    local id
    # 6 random bytes -> 12 hex characters.
    id="$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
    [ "${#id}" -eq 12 ] || die "could not read the operating-system random source for the installation id."
    (umask 077 && printf '%s\n' "$id" >"$ID_FILE")
    load_identity
    info "created installation identity $PROJECT"
}

# require_random_source - fail with provisioning guidance when the OS
# cryptographic random device is unavailable. Passwords are never derived from
# anything weaker (no $RANDOM, timestamps or fixed values).
require_random_source() {
    [ -r /dev/urandom ] ||
        die "no operating-system random source (/dev/urandom). Provision $DEFAULT_SECRET_FILE yourself with a long random password, restricted to your account, and rerun."
}

# ensure_env_file - create the ignored .env from the sanitized example when it
# does not exist. Existing owner values are never touched. The copy contains no
# credentials; the database password lives in a separate private file.
ensure_env_file() {
    [ -f .env ] && return 0
    [ -f .env.example ] || die ".env.example is missing from the checkout; restore it from version control."
    (umask 077 && cat .env.example >.env)
    info "created .env from .env.example (edit it to change APP_PORT; no secrets belong there)"
}

# ensure_secret - guarantee the database password file exists and is usable.
# * Existing non-empty file: left byte-for-byte untouched.
# * Missing file with an existing database volume: refuse. PostgreSQL applies the
#   password only when the cluster is created, so a new random value would lock
#   the owner out of existing campaigns.
# * Otherwise: create a 256-bit random hex password from /dev/urandom with a
#   private directory (0700) and file (0600) and no trailing newline.
# The value is never printed.
ensure_secret() {
    if [ -f "$SECRET_FILE" ]; then
        [ -s "$SECRET_FILE" ] ||
            die "$SECRET_FILE is empty. Refusing to use it; if this is a brand-new installation delete the empty file and rerun, otherwise restore the original password."
        return 0
    fi
    # A volume of this project proves a cluster may already hold the old password.
    if docker volume inspect "${PROJECT}_database" >/dev/null 2>&1; then
        die "$SECRET_FILE is missing but the database volume '${PROJECT}_database' exists. A new password would not match it. Restore the original password file (or POSTGRES_PASSWORD_FILE); no data was changed."
    fi
    require_random_source
    local dir value
    dir="$(dirname -- "$SECRET_FILE")"
    (umask 077 && mkdir -p -- "$dir")
    # Also restrict the default directory explicitly (umask does not apply on
    # every filesystem); best effort for owner-chosen locations.
    chmod 700 -- "$dir" 2>/dev/null || true
    value="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    [ "${#value}" -eq 64 ] || die "could not read the operating-system random source for the database password."
    # `set -C` (noclobber) makes the redirection fail rather than overwrite a
    # file that appeared concurrently.
    (umask 077 && set -C && printf '%s' "$value" >"$SECRET_FILE") ||
        die "could not create $SECRET_FILE."
    chmod 600 -- "$SECRET_FILE" 2>/dev/null || true
    value=""
    info "created database password file $SECRET_FILE (random, owner-only; value not shown)"
}

# port_is_busy PORT - succeed when something accepts connections on loopback.
# Uses Bash's /dev/tcp; a refused connection returns immediately.
port_is_busy() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# service_running SERVICE - succeed when SERVICE has a running container in this
# project.
service_running() {
    local ids
    ids="$(compose ps --status running --quiet "$1" 2>/dev/null || true)"
    [ -n "$ids" ]
}

# check_port - fail when the engine's loopback port is taken by something other
# than this installation's own engine (a repeated launch is not a conflict).
check_port() {
    if service_running engine; then return 0; fi
    if port_is_busy "$APP_PORT_VALUE"; then
        die "port $APP_PORT_VALUE on 127.0.0.1 is already in use. Stop the other program or set another APP_PORT in .env."
    fi
}

# --- Diagnostics and health ------------------------------------------------------

# service_table - one line per service: name, state and health. Plain rows are
# used instead of Compose's table format, whose empty Health header renders as
# "<no value>".
service_table() {
    compose ps --all --format '{{.Service}}: {{.State}} {{.Health}}'
}

# diagnostics - bounded, redacted evidence after a failed start. Never prints
# unredacted logs or environment.
diagnostics() {
    echo "--- service state ---" >&2
    service_table 2>&1 | redact_stream >&2 || true
    echo "--- last $DIAG_TAIL log lines per service (redacted) ---" >&2
    compose logs --no-color --tail "$DIAG_TAIL" 2>&1 | redact_stream >&2 || true
}

# engine_http_code PATH - HTTP status of an engine path, requested from inside
# the engine container (its curl, loopback). This needs no host HTTP client and
# works even when the published port differs. Prints "000" when unreachable.
engine_http_code() {
    local code
    code="$(compose exec -T engine curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 "http://127.0.0.1:8080$1" 2>/dev/null)" || code=""
    printf '%s' "${code:-000}"
}

# wait_ready - poll the readiness group (database + migrated schema) until it
# reports 200 or READY_TIMEOUT expires. Container health alone is not enough.
wait_ready() {
    local waited=0 code
    while [ "$waited" -lt "$READY_TIMEOUT" ]; do
        code="$(engine_http_code /actuator/health/readiness)"
        if [ "$code" = "200" ]; then return 0; fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

# runtime_signin_state - print "signed-in", "signed-out" or "unknown" using the
# runtime's own `login status` command inside the running runtime container.
# Only the classification leaves this function: account files are never read
# here and the command's text is not echoed (it could name an account).
runtime_signin_state() {
    local output
    if ! service_running codex; then printf 'unknown'; return 0; fi
    if output="$(compose exec -T codex /usr/local/bin/codex "${LOGIN_ARGS[@]}" login status 2>&1)"; then
        case "$output" in
            *ChatGPT*) printf 'signed-in' ;;
            *) printf 'unsupported' ;; # e.g. API-key sign-in: not an accepted method
        esac
    else
        case "$output" in
            *"Not logged in"*) printf 'signed-out' ;;
            *) printf 'unknown' ;;
        esac
    fi
}

# report_gameplay - print the gameplay line and the account state; returns 0
# only when the gameplay group reports ready. Gameplay readiness never starts an
# inference request.
report_gameplay() {
    local code state
    code="$(engine_http_code /actuator/health/gameplay)"
    state="$(runtime_signin_state)"
    case "$state" in
        signed-in) info "runtime account: signed in" ;;
        signed-out)
            info "runtime account: NOT signed in. Run ./start.sh --login to sign in."
            info "device-code login must be enabled in your account security settings: $AUTH_DOC_URL"
            ;;
        unsupported) info "runtime account: signed in with an unsupported method; run ./start.sh --login to use the supported account flow." ;;
        *) info "runtime account: state unknown (runtime not running or not answering)." ;;
    esac
    if [ "$code" = "200" ]; then
        info "gameplay: ready"
        return 0
    fi
    info "gameplay: NOT ready (health group answered $code). Saved data stays available; narration is unavailable."
    return 1
}

# --- Commands --------------------------------------------------------------------

# cmd_start - the default command: validate, initialize, build, start, wait.
# Idempotent: every step reuses existing state and a second run only confirms it.
cmd_start() {
    trap on_interrupt INT TERM
    require_utilities
    check_docker
    info "docker: $DOCKER_SUMMARY"
    resolve_config
    ensure_identity
    ensure_env_file
    # .env may have just been created; re-read settings from it.
    resolve_config
    ensure_secret
    check_port
    info "building images (cached layers are reused; the first build downloads dependencies and can take several minutes)"
    compose build || die "the image build failed; see the build output above. Rerunning reuses cached layers."
    info "starting services"
    if ! compose up --detach --no-build --wait --wait-timeout "$WAIT_TIMEOUT"; then
        diagnostics
        die "services did not become healthy. Run ./start.sh --logs for more detail; rerun ./start.sh to retry (data is preserved)."
    fi
    info "waiting for storage readiness (database connection and migrated schema)"
    if ! wait_ready; then
        diagnostics
        die "the engine did not report storage readiness within ${READY_TIMEOUT}s. Rerun ./start.sh after checking ./start.sh --logs."
    fi
    info "storage: ready"
    local gameplay=0
    report_gameplay || gameplay=1
    info "open http://127.0.0.1:${APP_PORT_VALUE}"
    info "commands: ./start.sh --status | --logs | --stop"
    if [ "$gameplay" -ne 0 ]; then
        info "started with gameplay incomplete (see above); this is not a startup failure."
    fi
}

# cmd_check - read-only inspection of prerequisites and configuration. Reports
# what a normal start would create; creates nothing and touches no container.
cmd_check() {
    local failed=0
    require_utilities
    info "bash: ${BASH_VERSION}"
    check_docker
    info "docker: $DOCKER_SUMMARY"
    info "required: Engine >= $MIN_ENGINE_VERSION, Compose >= $MIN_COMPOSE_VERSION, Buildx present, Linux containers"
    info "verified matrix: $VERIFIED_MATRIX"
    resolve_config
    info "APP_PORT: $APP_PORT_VALUE (bound to 127.0.0.1 only)"
    if [ -f .env ]; then info ".env: present"; else info ".env: missing (a normal start copies .env.example)"; fi
    if load_identity; then info "installation: $PROJECT"; else info "installation: not initialized (a normal start creates $ID_FILE)"; fi
    if [ -f "$SECRET_FILE" ]; then
        if [ -s "$SECRET_FILE" ]; then info "database password file: present"; else warn "database password file is empty: $SECRET_FILE"; failed=1; fi
    else
        if [ -n "$PROJECT" ] && docker volume inspect "${PROJECT}_database" >/dev/null 2>&1; then
            # A fresh password would not match the existing cluster: report it
            # now instead of letting the start fail.
            warn "database password file is missing but the database volume exists; restore the original file (a start would refuse to generate a new one)"
            failed=1
        else
            info "database password file: missing (a normal start generates a random one)"
        fi
        [ -r /dev/urandom ] || { warn "no /dev/urandom: provision $SECRET_FILE manually"; failed=1; }
    fi
    if [ -n "$PROJECT" ] && service_running engine; then
        info "port $APP_PORT_VALUE: in use by this installation's engine"
    elif port_is_busy "$APP_PORT_VALUE"; then
        warn "port $APP_PORT_VALUE on 127.0.0.1 is in use by another program"
        failed=1
    else
        info "port $APP_PORT_VALUE: free"
    fi
    # Syntax-level validation of the Compose model; needs no secret content.
    if compose config --quiet >/dev/null 2>&1; then info "compose.yaml: valid"; else warn "compose.yaml did not validate (run 'docker compose config' for details)"; failed=1; fi
    if [ "$failed" -ne 0 ]; then die "check found problems (see warnings above)."; fi
    info "check passed"
}

# cmd_status - report service state, storage readiness and gameplay readiness.
# Read-only. Exit 0 fully ready, 1 not running/unhealthy, 3 storage ready only.
cmd_status() {
    require_utilities
    check_docker
    resolve_config
    if ! load_identity; then
        info "not installed: run ./start.sh first."
        exit 1
    fi
    info "installation: $PROJECT"
    service_table || true
    if ! service_running engine; then
        info "engine is not running. Run ./start.sh to start."
        exit 1
    fi
    local code
    code="$(engine_http_code /actuator/health/readiness)"
    if [ "$code" != "200" ]; then
        info "storage: NOT ready (readiness group answered $code). See ./start.sh --logs."
        exit 1
    fi
    info "storage: ready"
    info "url: http://127.0.0.1:${APP_PORT_VALUE}"
    if report_gameplay; then exit 0; fi
    exit 3
}

# cmd_logs [SERVICE] [--no-follow] - bounded, redacted service logs. Follows by
# default; Ctrl+C ends the view and leaves the services running.
cmd_logs() {
    local service="" follow=1 arg
    for arg in "$@"; do
        case "$arg" in
            --no-follow) follow=0 ;;
            engine | postgres | codex) service="$arg" ;;
            *) echo "start.sh: unknown logs argument '$arg'" >&2; usage >&2; exit 2 ;;
        esac
    done
    require_utilities
    check_docker
    resolve_config
    load_identity || die "not installed: run ./start.sh first."
    local -a args=(logs --no-color --tail "$LOG_TAIL")
    [ "$follow" -eq 1 ] && args+=(--follow)
    [ -n "$service" ] && args+=("$service")
    # 130/141 are the normal results of Ctrl+C or a closed reader.
    local status=0
    compose "${args[@]}" 2>&1 | redact_stream || status=$?
    case "$status" in 0 | 130 | 141) return 0 ;; *) return "$status" ;; esac
}

# cmd_stop - stop this installation without data loss. `down` (never with -v)
# removes containers and networks but keeps every named volume; Compose stops the
# engine first (it depends on the others) within each service's grace period.
cmd_stop() {
    require_utilities
    check_docker
    resolve_config
    if ! load_identity; then
        info "nothing to stop: not installed."
        return 0
    fi
    info "stopping $PROJECT (volumes are preserved)"
    # The profiles are named so a selected model server, image worker and gateway stop
    # too: left running, the first two would keep their GPU memory and the third its keys
    # in memory and its egress network, and each would block removal of the network it shares.
    compose --profile models --profile images --profile gateway down --timeout 35 || die "stopping failed; run 'docker compose -p $PROJECT ps' to inspect. Data is not affected."
    info "stopped. Run ./start.sh to start again; campaigns and the runtime account are kept."
}

# cmd_login - sign in the isolated runtime with the supported ChatGPT device-code
# flow, storing state only in this project's runtime account volume.
# Safety: the runtime service is stopped first so only one process uses the
# account volume, then restarted if it was running. Credentials are written by
# the runtime inside its volume; this script never reads or copies them.
cmd_login() {
    require_utilities
    check_docker
    resolve_config
    ensure_identity
    ensure_env_file
    resolve_config
    ensure_secret
    info "building the runtime image (cached layers are reused)"
    compose build codex || die "the runtime image build failed; see the output above."
    local was_running=0
    if service_running codex; then
        was_running=1
        LOGIN_RESTORE=1
        info "pausing the runtime service so only one process uses the account volume"
        compose stop --timeout 35 codex || die "could not stop the runtime service."
    fi
    info "device-code sign-in: open the link shown below and enter the one-time code."
    info "if it is rejected, enable device-code login in your account security settings (workspace administrators must allow it): $AUTH_DOC_URL"
    # -T: the device flow only prints a link and polls, and Git Bash terminals
    # cannot hand a TTY to docker.exe. --no-deps: do not start database/engine.
    # A fixed container name lets the interrupt handler remove the one-off
    # container even though the Compose client was the process interrupted.
    LOGIN_CONTAINER="${PROJECT}-codex-login"
    trap on_login_interrupt INT TERM
    local login_status=0
    compose run --rm --no-deps -T --name "$LOGIN_CONTAINER" codex "${LOGIN_ARGS[@]}" login --device-auth || login_status=$?
    if [ "$login_status" -ne 0 ]; then
        restore_runtime
        die "sign-in did not complete (exit $login_status). Enable device-code login in the account security settings and rerun ./start.sh --login: $AUTH_DOC_URL"
    fi
    local verify_output verify_status=0
    verify_output="$(compose run --rm --no-deps -T --name "$LOGIN_CONTAINER" codex "${LOGIN_ARGS[@]}" login status 2>&1)" || verify_status=$?
    if [ "$was_running" -eq 1 ]; then
        info "restarting the runtime service"
        restore_runtime || die "the runtime service did not restart; run ./start.sh."
    fi
    if [ "$verify_status" -ne 0 ] || [[ "$verify_output" != *ChatGPT* ]]; then
        die "sign-in finished but the runtime does not report a signed-in account. Rerun ./start.sh --login."
    fi
    info "runtime account: signed in. Live inference was not exercised."
    info "run ./start.sh to start (or ./start.sh --status to check readiness)."
}

# --- Model hardware profile (P21.1) -------------------------------------------
#
# `./start.sh --models profile [--gpu-check]` writes .local/hardware.json, the
# input of the model advisor (docs/34-inference-backends-and-model-selection.md
# section 5). It reads hardware facts only: GPU model, VRAM, driver and compute
# capability from the NVIDIA driver tool (never from the Windows adapter field,
# a 32-bit value that reports about 4 GB on a 12 GB card); CPU model and thread
# count; RAM and, on Windows, the memory module layout; free disk space for the
# private model directory; the container engine's memory (the WSL 2 VM under
# Docker Desktop); and, with --gpu-check, the CUDA devices that the pinned
# model-server image sees through Docker GPU passthrough. It records no host or
# user name, serial number, UUID, IP address or absolute path.
#
# Every probe is optional: a missing or failing tool is recorded as unavailable
# or failed and never aborts the command. Only --gpu-check may download (the
# pinned image, once) or start a container, which is removed on exit and runs
# without network, capabilities, a writable root filesystem or root user.

# Pinned llama.cpp server image (build b11960, CUDA 12.8.1) as a multi-platform
# index digest, per the ADR 0009 image policy; mirrored in docs/adr/0009 and
# docs/34. The image's entrypoint is llama-server, whose --list-devices option
# prints every non-CPU device with total and free memory, then exits.
readonly MODEL_SERVER_IMAGE="ghcr.io/ggml-org/llama.cpp:server-cuda-b11960@sha256:4ccae195bf542f7844301ae06891baa32a7551cb16de8428af6e929403d4292c"
readonly HARDWARE_PROFILE="$STATE_DIR/hardware.json"
readonly HARDWARE_SCHEMA="rpg-engine.hardware-profile/1"
# Bytes per MiB and per KiB-to-MiB step, named so the conversions read clearly.
readonly MIB=1048576
readonly KIB_PER_MIB=1024

# read_proc NAME - print /proc/NAME when readable, else nothing (macOS has no
# /proc). Git Bash (MSYS2) and WSL both provide cpuinfo, meminfo and version.
# A separate function so tests can replay another host's files.
read_proc() {
    if [ -r "/proc/$1" ]; then cat "/proc/$1"; fi
}

# json_string VALUE - print VALUE as a JSON string literal. Control characters
# are dropped and backslash and double quote are escaped, which is complete
# for the hardware names and versions written here.
json_string() {
    local value
    value="$(printf '%s' "$1" | tr -d '\000-\037')"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '"%s"' "$value"
}

# json_text VALUE - a JSON string, or null when VALUE is empty or a driver
# placeholder such as "[N/A]".
json_text() {
    case "$1" in
        '' | '[N/A]' | 'N/A' | '[Not Supported]') printf 'null' ;;
        *) json_string "$1" ;;
    esac
}

# json_count VALUE - VALUE as a JSON integer when it is all decimal digits,
# otherwise null (missing, "[N/A]" or malformed driver output).
json_count() {
    case "$1" in
        '' | *[!0-9]*) printf 'null' ;;
        *) printf '%s' "$((10#$1))" ;;
    esac
}

# trim VALUE - VALUE without leading and trailing blanks.
trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

# profile_platform - windows (Git Bash), wsl, linux, macos or other.
profile_platform() {
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*) printf 'windows' ;;
        Darwin) printf 'macos' ;;
        Linux)
            if read_proc version | grep -qi microsoft; then printf 'wsl'; else printf 'linux'; fi ;;
        *) printf 'other' ;;
    esac
}

# gpu_tool - print the NVIDIA driver tool to run, or fail when there is none.
# WSL exposes the Windows driver's tool under /usr/lib/wsl/lib, which is not
# always on PATH.
gpu_tool() {
    if command -v nvidia-smi >/dev/null 2>&1; then
        printf 'nvidia-smi'
    elif [ -x /usr/lib/wsl/lib/nvidia-smi ]; then
        printf '/usr/lib/wsl/lib/nvidia-smi'
    else
        return 1
    fi
}

# profile_gpus - set GPU_SOURCE (nvidia-smi, unavailable or failed) and
# GPUS_JSON (a JSON array, possibly empty). The driver tool reports memory in
# MiB with nounits; free memory is what remains after the desktop and other
# applications at the moment of profiling.
profile_gpus() {
    GPU_SOURCE="unavailable"
    GPUS_JSON="[]"
    local tool output line index name total used free driver capability entries=""
    tool="$(gpu_tool)" || return 0
    if ! output="$("$tool" --query-gpu=index,name,memory.total,memory.used,memory.free,driver_version,compute_cap \
        --format=csv,noheader,nounits 2>/dev/null)"; then
        GPU_SOURCE="failed"
        return 0
    fi
    GPU_SOURCE="nvidia-smi"
    while IFS=',' read -r index name total used free driver capability; do
        [ -n "$(trim "$index")" ] || continue
        entries+="${entries:+,}
    {
      \"index\": $(json_count "$(trim "$index")"),
      \"name\": $(json_text "$(trim "$name")"),
      \"memoryTotalMiB\": $(json_count "$(trim "$total")"),
      \"memoryUsedMiB\": $(json_count "$(trim "$used")"),
      \"memoryFreeMiB\": $(json_count "$(trim "$free")"),
      \"driverVersion\": $(json_text "$(trim "$driver")"),
      \"computeCapability\": $(json_text "$(trim "$capability")")
    }"
    done < <(printf '%s\n' "$output" | tr -d '\r')
    [ -z "$entries" ] || GPUS_JSON="[${entries}
  ]"
}

# profile_memory_modules - set MODULES_JSON (array or null) and SLOTS_JSON
# (integer or null). Only Windows exposes the module layout without elevation,
# through PowerShell's CIM classes; elsewhere both stay null.
profile_memory_modules() {
    MODULES_JSON="null"
    SLOTS_JSON="null"
    [ "$1" = windows ] || return 0
    command -v powershell.exe >/dev/null 2>&1 || return 0
    local output kind first second entries="" slots=0 slots_seen=0
    # Single quotes keep $_ for PowerShell; the MSYS path conversion is already
    # disabled for this script, so the command reaches PowerShell unchanged.
    output="$(powershell.exe -NoProfile -NonInteractive -Command \
        'Get-CimInstance -ClassName Win32_PhysicalMemory | ForEach-Object { "module {0} {1}" -f $_.Capacity, $_.ConfiguredClockSpeed }; Get-CimInstance -ClassName Win32_PhysicalMemoryArray | ForEach-Object { "slots {0}" -f $_.MemoryDevices }' \
        2>/dev/null)" || return 0
    while read -r kind first second; do
        case "$kind" in
            module)
                case "$first" in '' | *[!0-9]*) continue ;; esac
                entries+="${entries:+,}
      { \"capacityMiB\": $((first / MIB)), \"speedMTs\": $(json_count "$second") }"
                ;;
            slots)
                case "$first" in '' | *[!0-9]*) continue ;; esac
                slots=$((slots + first))
                slots_seen=1
                ;;
        esac
    done < <(printf '%s\n' "$output" | tr -d '\r')
    [ -z "$entries" ] || MODULES_JSON="[${entries}
    ]"
    [ "$slots_seen" -eq 0 ] || SLOTS_JSON="$slots"
}

# profile_container_engine - set ENGINE_JSON: the container engine's version,
# operating system label, memory and CPUs, or {"available": false}. Under
# Docker Desktop on Windows the memory is the WSL 2 VM's, the real ceiling for
# every container including a CPU-offloaded model.
profile_container_engine() {
    ENGINE_JSON='{ "available": false }'
    ENGINE_READY=0
    command -v docker >/dev/null 2>&1 || return 0
    local info os version label memory cpus
    info="$(docker info --format '{{.OSType}}|{{.ServerVersion}}|{{.OperatingSystem}}|{{.MemTotal}}|{{.NCPU}}' 2>/dev/null)" ||
        return 0
    IFS='|' read -r os version label memory cpus <<<"$(printf '%s' "$info" | tr -d '\r')"
    case "$memory" in '' | *[!0-9]*) memory="" ;; *) memory="$((memory / MIB))" ;; esac
    ENGINE_JSON="{
    \"available\": true,
    \"osType\": $(json_text "$os"),
    \"serverVersion\": $(json_text "$version"),
    \"operatingSystem\": $(json_text "$label"),
    \"memoryTotalMiB\": $(json_count "$memory"),
    \"cpus\": $(json_count "$cpus")
  }"
    [ "$os" = linux ] && ENGINE_READY=1
    return 0
}

# profile_container_gpu CHECK - set CONTAINER_GPU_JSON. With CHECK=1 and a
# Linux container engine, run the pinned model-server image with all GPUs and
# parse its --list-devices output. Status values: visible (at least one CUDA
# device), not-visible (the image ran and saw no CUDA device, or Docker could
# not attach a GPU), failed (the image could not be pulled or run), not-checked
# (no --gpu-check) and engine-unavailable.
profile_container_gpu() {
    local check="$1" status output line devices="" run_status=0
    CONTAINER_GPU_STATUS="not-checked"
    if [ "$check" -eq 1 ] && [ "$ENGINE_READY" -ne 1 ]; then
        CONTAINER_GPU_STATUS="engine-unavailable"
    elif [ "$check" -eq 1 ]; then
        if ! docker image inspect "$MODEL_SERVER_IMAGE" >/dev/null 2>&1; then
            info "pulling the pinned model-server image once (about 2.6 GB compressed)"
            docker pull "$MODEL_SERVER_IMAGE" >/dev/null 2>&1 || CONTAINER_GPU_STATUS="failed"
        fi
        if [ "$CONTAINER_GPU_STATUS" != failed ]; then
            output="$(docker run --rm --pull never --gpus all --network none --read-only \
                --cap-drop ALL --security-opt no-new-privileges:true --user 65534:65534 \
                "$MODEL_SERVER_IMAGE" --list-devices 2>&1)" || run_status=$?
            local name description total free
            while IFS= read -r line; do
                # "  CUDA0: NVIDIA GeForce RTX 4070 (12281 MiB, 11043 MiB free)"
                if [[ "$line" =~ ^[[:space:]]+([A-Za-z0-9_]+):[[:space:]](.+)[[:space:]]\(([0-9]+)[[:space:]]MiB,[[:space:]]([0-9]+)[[:space:]]MiB[[:space:]]free\)$ ]]; then
                    name="${BASH_REMATCH[1]}"; description="${BASH_REMATCH[2]}"
                    total="${BASH_REMATCH[3]}"; free="${BASH_REMATCH[4]}"
                    devices+="${devices:+,}
      { \"name\": $(json_string "$name"), \"description\": $(json_string "$description"), \"memoryTotalMiB\": $(json_count "$total"), \"memoryFreeMiB\": $(json_count "$free") }"
                    [[ "$name" == CUDA* ]] && CONTAINER_GPU_STATUS="visible"
                fi
            done < <(printf '%s\n' "$output" | tr -d '\r')
            if [ "$CONTAINER_GPU_STATUS" != visible ]; then
                # A GPU request Docker cannot satisfy fails before the image
                # runs; the daemon's message names the device or driver.
                if [ "$run_status" -eq 0 ] || [[ "$output" == *"Available devices:"* ]] ||
                   printf '%s' "$output" | grep -qiE 'gpu|device driver|nvidia'; then
                    CONTAINER_GPU_STATUS="not-visible"
                else
                    CONTAINER_GPU_STATUS="failed"
                fi
            fi
        fi
    fi
    if [ "$check" -eq 1 ]; then
        CONTAINER_GPU_JSON="{
    \"status\": $(json_string "$CONTAINER_GPU_STATUS"),
    \"image\": $(json_string "$MODEL_SERVER_IMAGE"),
    \"devices\": [${devices}${devices:+
    }]
  }"
    else
        CONTAINER_GPU_JSON="{
    \"status\": $(json_string "$CONTAINER_GPU_STATUS"),
    \"image\": $(json_string "$MODEL_SERVER_IMAGE"),
    \"devices\": []
  }"
    fi
}

# profile_now - the profiling time as UTC ISO-8601 to the second.
profile_now() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }

# build_hardware_profile CHECK - set PROFILE_DOCUMENT to the complete profile
# (schema rpg-engine.hardware-profile/1) and leave GPU_SOURCE and
# CONTAINER_GPU_STATUS for the summary. It runs in the current shell, not in a
# command substitution, so those results and the pull notice reach the owner.
# Field order and layout are fixed so a profile can be compared byte for byte.
build_hardware_profile() {
    local check="$1" platform cpu_model cpu_threads total available scope disk now
    platform="$(profile_platform)"
    cpu_model="$(read_proc cpuinfo | grep -m 1 '^model name' | sed -E 's/^[^:]*:[[:space:]]*//; s/[[:space:]]+$//' || true)"
    cpu_threads="$(read_proc cpuinfo | grep -c '^processor' || true)"
    [ "$cpu_threads" != 0 ] || cpu_threads=""
    total="$(read_proc meminfo | sed -n -E '/^MemTotal:/{s/^MemTotal:[[:space:]]+([0-9]+) kB$/\1/p;q}')"
    available="$(read_proc meminfo | sed -n -E '/^MemAvailable:/{s/^MemAvailable:[[:space:]]+([0-9]+) kB$/\1/p;q}')"
    [ -z "$total" ] || total="$((total / KIB_PER_MIB))"
    [ -z "$available" ] || available="$((available / KIB_PER_MIB))"
    # Under WSL the kernel reports the VM's memory, not the machine's.
    case "$platform" in wsl) scope="vm" ;; *) scope="host" ;; esac
    # POSIX df output: the fourth field of the second line is available KiB.
    local _filesystem _blocks _used
    read -r _filesystem _blocks _used disk _ <<<"$(df -Pk "$STATE_DIR" 2>/dev/null | sed -n '2p' || true)"
    case "$disk" in '' | *[!0-9]*) disk="" ;; *) disk="$((disk / KIB_PER_MIB))" ;; esac
    now="$(profile_now)"
    profile_gpus
    profile_memory_modules "$platform"
    profile_container_engine
    profile_container_gpu "$check"
    PROFILE_DOCUMENT="$(cat <<EOF_PROFILE
{
  "schema": "$HARDWARE_SCHEMA",
  "generatedAt": $(json_string "$now"),
  "platform": "$platform",
  "cpu": { "model": $(json_text "$cpu_model"), "logicalCores": $(json_count "$cpu_threads") },
  "memory": {
    "scope": "$scope",
    "totalMiB": $(json_count "$total"),
    "availableMiB": $(json_count "$available"),
    "modules": $MODULES_JSON,
    "moduleSlots": $SLOTS_JSON
  },
  "gpuSource": "$GPU_SOURCE",
  "gpus": $GPUS_JSON,
  "disk": { "path": "$STATE_DIR", "freeMiB": $(json_count "$disk") },
  "containerEngine": $ENGINE_JSON,
  "containerGpu": $CONTAINER_GPU_JSON
}
EOF_PROFILE
)"
}

# --- Model fetch (P21.2) --------------------------------------------------------
#
# `./start.sh --models fetch` is the only path by which catalog weights reach the
# model directory (docs/34-inference-backends-and-model-selection.md section 5). The
# catalog, the download and the hash checks all run in one-off pinned containers
# (docker/model-fetch/model_fetch.py), because ADR 0009 allows no JSON parser, HTTP client
# or hash tool on the host. Only the confirmation stays here: the license is shown from a
# container with no network, and the container with egress starts only after every
# requested model has been accepted. A catalog entry's `confirmationRequired` flag does not
# relax this: every model is confirmed, whatever its license class.

readonly MODEL_CATALOG="backend/src/main/resources/inference/model-catalog.json"
readonly MODEL_CATALOG_IN_CONTAINER="/catalog/model-catalog.json"
readonly DEFAULT_MODELS_DIR="./$STATE_DIR/models"
readonly MODEL_ID_RULE='^[a-z0-9][a-z0-9._-]{0,63}$'

# Name of the running fetch container, shared with the interrupt handler.
FETCH_CONTAINER=""
# Model IDs named by --accept-license.
ACCEPTED_MODELS=()

# models_usage_error MESSAGE - report a bad fetch command line and exit 2.
models_usage_error() {
    echo "start.sh: $*" >&2
    usage >&2
    exit 2
}

# stdin_is_terminal - succeed when a person can answer on stdin. A function, so that tests
# can stand in for a terminal; every other stdin fails closed.
stdin_is_terminal() { [ -t 0 ]; }

# list_contains VALUE ITEM... - succeed when VALUE is one of the items.
list_contains() {
    local value="$1" item
    shift
    for item in "$@"; do
        [ "$item" != "$value" ] || return 0
    done
    return 1
}

# resolve_models_dir - create the model directory Compose will mount and leave its path in
# MODELS_DIR for Compose, with the same precedence and default as the `models` service. A
# new directory is world-readable because the model server runs as user 65534; an existing
# one is the owner's and keeps its mode.
resolve_models_dir() {
    local directory
    directory="$(config_value MODELS_DIR "$DEFAULT_MODELS_DIR")"
    if [ ! -d "$directory" ]; then
        mkdir -p -- "$directory" || die "could not create the model directory $directory."
        chmod 755 -- "$directory" 2>/dev/null || true
    fi
    # Compose needs a path docker.exe understands, as for the password file.
    case "$directory" in
        /*) if command -v cygpath >/dev/null 2>&1; then directory="$(cygpath -m "$directory")"; fi ;;
    esac
    export MODELS_DIR="$directory"
}

# fetch_tool SERVICE ARGS... - run the fetch helper once in SERVICE, with this host
# account's numeric identity so that installed files belong to the owner. Returns the
# helper's exit status. `-T`: Git Bash terminals cannot hand a TTY to docker.exe.
fetch_tool() {
    local service="$1"
    shift
    compose --profile model-fetch run --rm --no-deps -T --name "$FETCH_CONTAINER" \
        --user "$UID:${GROUPS[0]:-$UID}" "$service" "$@"
}

# on_fetch_interrupt - Ctrl+C / termination during a fetch. The Compose client may be what
# was interrupted, so stop the named container: the stop signal makes the helper remove its
# unfinished files, and then the container itself is removed. Nothing reaches the model
# directory, because files move there only after every download has been verified.
on_fetch_interrupt() {
    printf '\nstart.sh: model fetch interrupted. Nothing new was installed; rerun the command to download again.\n' >&2
    if [ -n "$FETCH_CONTAINER" ]; then
        docker stop --time 15 "$FETCH_CONTAINER" >/dev/null 2>&1 || true
        docker rm --force "$FETCH_CONTAINER" >/dev/null 2>&1 || true
    fi
    exit 130
}

# accept_licenses LIST - add a comma-separated --accept-license value to ACCEPTED_MODELS.
# An empty element would hide a mistyped list, and a wildcard is not a model ID: both are
# usage errors, so a confirmation always names exactly what it accepts.
accept_licenses() {
    local item
    local -a named=()
    case "$1" in
        '' | ,* | *, | *,,*) models_usage_error "--accept-license needs comma-separated model IDs, each one named." ;;
    esac
    IFS=, read -r -a named <<<"$1"
    for item in "${named[@]}"; do
        [[ "$item" =~ $MODEL_ID_RULE ]] ||
            models_usage_error "--accept-license takes exact model IDs; '$item' is not one."
        ACCEPTED_MODELS+=("$item")
    done
}

# confirm_licenses ID... - return when every ID is in ACCEPTED_MODELS or answered with
# exactly `yes`. Dies on any other answer, on end of input and, without a terminal, on any
# unnamed ID, always before a download starts.
confirm_licenses() {
    local answer identifier
    for identifier in "$@"; do
        if list_contains "$identifier" "${ACCEPTED_MODELS[@]}"; then
            info "$identifier: license accepted by --accept-license"
            continue
        fi
        stdin_is_terminal ||
            die "no confirmation for $identifier and no terminal to ask on. After reading the license above, name it: --accept-license $identifier. Nothing was downloaded."
        printf "start.sh: type yes to accept the license of %s and download it: " "$identifier"
        answer=""
        IFS= read -r answer || true
        [ "${answer%$'\r'}" = yes ] ||
            die "the license of $identifier was not accepted. Nothing was downloaded."
    done
}

# cmd_models_fetch ARGS... - parse, show the licenses, confirm, then download and install.
# Exit status: 0 installed or already present; 1 refused or failed, with nothing installed
# from this command; 2 a usage error or unknown model ID; 130 interrupted.
cmd_models_fetch() {
    local -a identifiers=()
    local item
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --accept-license)
                [ "$#" -ge 2 ] || models_usage_error "--accept-license needs model IDs."
                accept_licenses "$2"
                shift 2
                ;;
            --accept-license=*)
                accept_licenses "${1#*=}"
                shift
                ;;
            -*) models_usage_error "unknown --models fetch argument '$1'" ;;
            *)
                [[ "$1" =~ $MODEL_ID_RULE ]] || models_usage_error "'$1' is not a model ID."
                list_contains "$1" "${identifiers[@]}" || identifiers+=("$1")
                shift
                ;;
        esac
    done
    [ "${#identifiers[@]}" -gt 0 ] || models_usage_error "--models fetch needs at least one model ID."
    for item in "${ACCEPTED_MODELS[@]}"; do
        list_contains "$item" "${identifiers[@]}" ||
            models_usage_error "--accept-license names '$item', which this command does not fetch."
    done

    require_utilities
    [ -f "$MODEL_CATALOG" ] || die "$MODEL_CATALOG is missing from the checkout; restore it from version control."
    check_docker
    resolve_config
    ensure_identity
    FETCH_CONTAINER="$PROJECT-model-fetch-$$"
    trap on_fetch_interrupt INT TERM

    info "reading the catalog in a container with no network (the first fetch pulls the pinned helper image, about 45 MB)"
    local status=0
    fetch_tool models-license describe --catalog "$MODEL_CATALOG_IN_CONTAINER" "${identifiers[@]}" || status=$?
    case "$status" in
        0) ;;
        2) exit 2 ;;
        *) die "could not read the licenses (exit $status). Nothing was downloaded." ;;
    esac

    confirm_licenses "${identifiers[@]}"

    resolve_models_dir
    info "downloading to $MODELS_DIR; files are installed only after size and SHA-256 match the catalog"
    local confirmed="${identifiers[*]}"
    status=0
    fetch_tool models-fetch install --catalog "$MODEL_CATALOG_IN_CONTAINER" --models-dir /models \
        --accepted "${confirmed// /,}" "${identifiers[@]}" || status=$?
    case "$status" in
        0) info "model fetch complete: ${identifiers[*]}" ;;
        2) exit 2 ;;
        *) die "model fetch failed (exit $status); see the message above. Nothing new was installed." ;;
    esac
}

# cmd_models ARGS... - model-management commands: `profile [--gpu-check]` and `fetch`.
# verify, advise and remove are planned (P21.3).
cmd_models() {
    local action="${1:-}" check=0
    if [ "$action" = fetch ]; then
        shift
        cmd_models_fetch "$@"
        return
    fi
    [ "$action" = profile ] || { echo "start.sh: unknown --models command '${action}'" >&2; usage >&2; exit 2; }
    shift
    case "$#:${1:-}" in
        0:) ;;
        1:--gpu-check) check=1 ;;
        *) echo "start.sh: unknown --models profile argument '$1'" >&2; usage >&2; exit 2 ;;
    esac
    require_utilities
    mkdir -p "$STATE_DIR"
    build_hardware_profile "$check"
    printf '%s\n' "$PROFILE_DOCUMENT" > "$HARDWARE_PROFILE" || die "could not write $HARDWARE_PROFILE."
    info "hardware profile written to $HARDWARE_PROFILE"
    info "gpu source: $GPU_SOURCE; container gpu: $CONTAINER_GPU_STATUS"
    if [ "$GPU_SOURCE" != nvidia-smi ]; then
        info "no NVIDIA driver tool answered: local models are advised for CPU only."
    fi
    if [ "$check" -eq 0 ]; then
        info "run ./start.sh --models profile --gpu-check to test GPU access from a container."
    fi
}

# --- Entry point -----------------------------------------------------------------

# main ARGS... - dispatch exactly one command. Unknown input is a usage error
# (exit 2) and changes nothing.
main() {
    local command="${1:-}"
    case "$command" in
        '') cmd_start ;;
        --check) [ "$#" -eq 1 ] || { usage >&2; exit 2; }; cmd_check ;;
        --login) [ "$#" -eq 1 ] || { usage >&2; exit 2; }; cmd_login ;;
        --status) [ "$#" -eq 1 ] || { usage >&2; exit 2; }; cmd_status ;;
        --logs) shift; cmd_logs "$@" ;;
        --stop) [ "$#" -eq 1 ] || { usage >&2; exit 2; }; cmd_stop ;;
        --models) shift; cmd_models "$@" ;;
        --help | -h) usage ;;
        *) echo "start.sh: unknown argument '$command'" >&2; usage >&2; exit 2 ;;
    esac
}

# Run only when executed. Sourcing the file (the unit tests do this) defines the
# functions without starting anything.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    main "$@"
fi
