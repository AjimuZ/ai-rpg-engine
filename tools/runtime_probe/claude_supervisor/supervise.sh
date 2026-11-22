#!/bin/sh
# Engine-owned supervisor of the second narration client: the fixed launch profile (P20.1-C probe).
#
# The caller (later the backend's adapter) sends task data only. It never sends flags, paths or
# environment: this script builds the whole command line and the client's whole environment.
#
# Protocol, one process per operation:
#   supervise.sh run SESSION MODEL EFFORT SCHEMA    the prompt is read from stdin
#   supervise.sh retire UUID
# Fields, each refused unless it matches exactly:
#   SESSION  none (stateless, nothing persisted) | new:UUID | resume:UUID (lower-case UUID)
#   MODEL    claude-[a-z0-9.-]{1,58}, checked against the owner's allowlist by the engine, not here
#   EFFORT   low | medium | high | xhigh | max (the client would ignore any other value silently)
#   SCHEMA   none | the name of a schema file in schemas/ next to this script ([a-z0-9-]{1,40})
#   prompt   1 to 262144 bytes of stdin, handed to the client on its stdin. It is never an
#            argument: the client parses a positional prompt such as "--version" as an option.
# Container configuration (set by the runtime container, never by the caller):
#   SUPERVISOR_AUTH_MODE     api-key | subscription
#   SUPERVISOR_API_KEY_FILE  file holding the key, read in api-key mode only
#   ANTHROPIC_BASE_URL       forwarded when set; only the probe fixture sets it
#
# Results:
#   run     validates, then replaces itself with the client (exec), so the exit status, stdout
#           (stream-json events) and stderr are the client's own, and a signal sent to this
#           process ID reaches the client directly;
#   retire  deletes every path under HOME whose name holds UUID, then exits 0 and prints
#           "retired UUID" only when no remaining path names it and no file contains it;
#   exit 64 a refused request: one "supervisor-refused REASON" line on stderr, nothing started;
#   exit 65 retire left something: one "remaining PATH" line per path on stdout;
#   exit 66 the credential guard refused: credentials of the other sign-in mode are present.
set -u
root=$(cd "$(dirname "$0")" && pwd) || exit 70
client=${SUPERVISOR_CLIENT:-/usr/local/bin/claude}
engine_prompt='You narrate for the engine. Reply with plain text only.'
max_prompt=262144

refuse() {
  echo "supervisor-refused $1" >&2
  exit 64
}

# Exact match of the whole value against an extended regular expression; no partial matches.
matches() {
  printf '%s\n' "$1" | grep -Eqx -- "$2"
}

uuid_re='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

[ "$#" -ge 1 ] || refuse usage
operation=$1
shift

case $operation in
  retire)
    [ "$#" -eq 1 ] || refuse usage
    matches "$1" "$uuid_re" || refuse session
    uuid=$1
    find "$HOME" -depth -name "*$uuid*" -exec rm -rf {} + 2>/dev/null
    left=$( { find "$HOME" -name "*$uuid*"; grep -rlF -- "$uuid" "$HOME" 2>/dev/null; } | sort -u)
    if [ -n "$left" ]; then
      printf '%s\n' "$left" | sed 's/^/remaining /'
      exit 65
    fi
    echo "retired $uuid"
    exit 0
    ;;
  run) ;;
  *) refuse operation ;;
esac

[ "$#" -eq 4 ] || refuse usage
session=$1
model=$2
effort=$3
schema=$4
case $session in
  none) session_args='--no-session-persistence' ;;
  new:*) matches "${session#new:}" "$uuid_re" || refuse session; session_args="--session-id ${session#new:}" ;;
  resume:*) matches "${session#resume:}" "$uuid_re" || refuse session; session_args="--resume ${session#resume:}" ;;
  *) refuse session ;;
esac
matches "$model" 'claude-[a-z0-9.-]{1,58}' || refuse model
case $effort in
  low|medium|high|xhigh|max) ;;
  *) refuse effort ;;
esac
if [ "$schema" = none ]; then
  schema_json=
else
  matches "$schema" '[a-z0-9-]{1,40}' || refuse schema
  [ -f "$root/schemas/$schema.json" ] || refuse schema
  schema_json=$(cat "$root/schemas/$schema.json") || refuse schema
fi

# The credential guard: credentials of the other sign-in mode stop the task before anything
# starts. The client itself would prefer an API key over a stored login without a word.
mode=${SUPERVISOR_AUTH_MODE:-}
case $mode in
  api-key)
    if [ -e "$HOME/.claude/.credentials.json" ] || [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] \
       || [ -n "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
      echo "supervisor-guard subscription-credentials-in-api-key-mode" >&2
      exit 66
    fi
    key_file=${SUPERVISOR_API_KEY_FILE:-}
    [ -n "$key_file" ] && [ -s "$key_file" ] || refuse api-key-file
    key=$(cat "$key_file") || refuse api-key-file
    ;;
  subscription)
    if [ -n "${ANTHROPIC_API_KEY:-}" ] || [ -n "${ANTHROPIC_AUTH_TOKEN:-}" ] \
       || [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] \
       || { [ -n "${SUPERVISOR_API_KEY_FILE:-}" ] && [ -e "$SUPERVISOR_API_KEY_FILE" ]; }; then
      echo "supervisor-guard api-credentials-in-subscription-mode" >&2
      exit 66
    fi
    key=
    ;;
  *) refuse auth-mode ;;
esac

# The prompt: bounded and non-empty, read once from stdin into an unlinked file that stays open
# on descriptor 3 and becomes the client's stdin.
scratch=$(mktemp) || exit 70
head -c "$((max_prompt + 1))" > "$scratch"
size=$(wc -c < "$scratch")
exec 3< "$scratch"
rm -f "$scratch"
[ "$size" -le "$max_prompt" ] || refuse prompt-size
[ "$size" -gt 0 ] || refuse prompt-empty

settings=$(cat "$root/settings.json") || exit 70

# The client's whole environment: an allowlist. Telemetry, error reports, surveys, update checks
# and other non-essential traffic are off; the client retries nothing itself (the engine owns
# retries after reconciling); it never remaps a retired model identifier to another model; it
# keeps no automatic memory.
set -- HOME="$HOME" PATH=/usr/bin:/bin LANG=C.UTF-8 NO_COLOR=1 \
  DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 DISABLE_AUTOUPDATER=1 DISABLE_FEEDBACK_COMMAND=1 \
  CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY=1 CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
  CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP=1 CLAUDE_CODE_MAX_RETRIES=0
[ -n "${ANTHROPIC_BASE_URL:-}" ] && set -- "$@" ANTHROPIC_BASE_URL="$ANTHROPIC_BASE_URL"
[ -n "$key" ] && set -- "$@" ANTHROPIC_API_KEY="$key"

# The fixed template. With no positional prompt, -p reads the prompt from stdin.
set -- "$@" "$client" -p --output-format stream-json --verbose \
  --model "$model" --effort "$effort" \
  --tools "" --strict-mcp-config --disable-slash-commands --setting-sources "" \
  --permission-mode dontAsk --system-prompt "$engine_prompt" --settings "$settings"
[ -n "$schema_json" ] && set -- "$@" --json-schema "$schema_json"
# shellcheck disable=SC2086 # session_args holds one option and one validated UUID, or one option.
set -- "$@" $session_args
exec env -i "$@" <&3 3<&-
