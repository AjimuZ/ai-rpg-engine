#!/bin/sh
# Runs one isolation case of the second narration client inside its probe container (P20.1-B).
#
# Usage: run-case.sh PROFILE PLANT SCENARIO
#   PROFILE   isolated      the probe isolation profile, in API-key mode with the fixture key and
#                           hostile credentials of the other kind in the environment;
#             unrestricted  the client's own defaults with no key, and with the planted workspace
#                           marked trusted: the positive control.
#   PLANT     all           copy every synthetic home and project file (table below) into place;
#             none          start from the image's empty home and workspace.
#   SCENARIO  scenario-complete or scenario-tools, the token MessagesProvider.java answers.
#
# The fixture files keep flat names because the repository ignores the real configuration
# names; the table maps each one to the path where the client would look for it.
#
# The script never fails on the client's behalf: once the case has run it prints exactly one
# report and exits 0, so the test sees the client's exit status, output and side effects.
# Report lines, in order: "status N", "stdout BASE64", "stderr BASE64", zero or more
# "marker NAME", "canonical present|absent", zero or more "created PATH" each optionally
# followed by "secret PATH" when that new file holds a secret sentinel, then "end".
# Exit 2 means a usage error and no report.
set -u
[ "$#" -eq 3 ] || { echo "usage: run-case.sh PROFILE PLANT SCENARIO" >&2; exit 2; }
profile=$1
plant=$2
scenario=$3
workspace=/var/lib/runtime/workspace
probe=/tmp/probe
markers=/tmp/markers
engine_prompt='You narrate for the engine. Reply with plain text only.'
# The client loads bundled "name@builtin" plugins even with every setting source off. The
# documented `enabledPlugins` setting, passed inline through `--settings`, turns off each one
# the pinned 2.1.323 binary registers; it holds nothing else. A plugin a later version adds is
# not listed here, and the Docker test then fails on its non-empty init `plugins` list.
builtin_off='{"enabledPlugins":{"cc-plugin-agents-md@builtin":false,"cc-plugin-claude-platform@builtin":false,"cc-plugin-claude-test@builtin":false,"cc-plugin-diff@builtin":false,"cc-plugin-easel@builtin":false,"cc-plugin-mermaid@builtin":false,"cc-plugin-mods-guide@builtin":false,"cc-plugin-plugin-authoring@builtin":false,"cc-plugin-privacy@builtin":false,"cc-plugin-responsive-mode@builtin":false,"cc-plugin-sec-default@builtin":false,"cc-plugin-telemetry@builtin":false,"cc-plugin-tips@builtin":false,"cc-plugin-you-should-know@builtin":false}}'
mkdir -p "$probe" "$markers"

if [ "$plant" = all ]; then
  while read -r source target; do
    case $target in
      HOME/*) target=$HOME/${target#HOME/} ;;
      WORKSPACE/*) target=$workspace/${target#WORKSPACE/} ;;
      *) echo "bad plant target: $target" >&2; exit 2 ;;
    esac
    mkdir -p "${target%/*}" && cp "/fixture/$source" "$target" || exit 2
  done <<'TABLE'
home-settings.json HOME/.claude/settings.json
home-memory.md HOME/.claude/CLAUDE.md
home-skill.md HOME/.claude/skills/inherited-sentinel-home-skill/SKILL.md
home-command.md HOME/.claude/commands/inherited-sentinel-home-command.md
home-agent.md HOME/.claude/agents/inherited-sentinel-home-agent.md
home-credentials.json HOME/.claude/.credentials.json
home-global-config.json HOME/.claude.json
home-codex-auth.json HOME/.codex/auth.json
project-settings.json WORKSPACE/.claude/settings.json
project-settings-local.json WORKSPACE/.claude/settings.local.json
project-memory.md WORKSPACE/CLAUDE.md
project-local-memory.md WORKSPACE/CLAUDE.local.md
project-mcp.json WORKSPACE/.mcp.json
project-skill.md WORKSPACE/.claude/skills/inherited-sentinel-project-skill/SKILL.md
project-command.md WORKSPACE/.claude/commands/inherited-sentinel-project-command.md
project-agent.md WORKSPACE/.claude/agents/inherited-sentinel-project-agent.md
TABLE
elif [ "$plant" != none ]; then
  echo "unknown plant: $plant" >&2
  exit 2
fi

# Every regular file the client could have written, except this script's own bookkeeping.
snapshot() {
  find "$HOME" "$workspace" /tmp -path "$probe" -prune -o -path "$markers" -prune -o -type f -print | sort
}
cd "$workspace" || exit 2
snapshot > "$probe/before"
prompt="Answer the neutral protocol check. $scenario"

# The prompt comes right after -p: --tools takes a list and would otherwise absorb it.
case $profile in
  isolated)
    env ANTHROPIC_API_KEY="$FIXTURE_API_KEY" \
        CLAUDE_CODE_OAUTH_TOKEN=secret-sentinel-fixture-env-subscription-token \
        OPENAI_API_KEY=secret-sentinel-fixture-env-first-runtime-key \
      timeout 150 /usr/local/bin/claude -p "$prompt" \
        --output-format stream-json --verbose --model "$FIXTURE_MODEL" \
        --tools "" --strict-mcp-config --disable-slash-commands --setting-sources "" \
        --permission-mode dontAsk --no-session-persistence --system-prompt "$engine_prompt" \
        --settings "$builtin_off" \
        < /dev/null > "$probe/stdout" 2> "$probe/stderr"
    ;;
  unrestricted)
    # Positive control only: mark the workspace as trusted, as accepting the client's trust
    # dialog would, so the project's permissions.allow entries apply and the scripted host
    # command really runs. The isolated profile never receives this entry.
    if [ "$plant" = all ]; then
      sed -i '1s|^{$|{"projects": {"/var/lib/runtime/workspace": {"hasTrustDialogAccepted": true}},|' "$HOME/.claude.json" || exit 2
    fi
    timeout 150 /usr/local/bin/claude -p "$prompt" \
        --output-format stream-json --verbose --model "$FIXTURE_MODEL" \
        < /dev/null > "$probe/stdout" 2> "$probe/stderr"
    ;;
  *)
    echo "unknown profile: $profile" >&2
    exit 2
    ;;
esac
status=$?

echo "status $status"
printf 'stdout '; base64 -w0 "$probe/stdout"; echo
printf 'stderr '; base64 -w0 "$probe/stderr"; echo
for path in "$markers"/*; do
  [ -e "$path" ] && echo "marker ${path##*/}"
done
if [ -e "$workspace/canonical-sentinel.json" ]; then echo 'canonical present'; else echo 'canonical absent'; fi
snapshot > "$probe/after"
comm -13 "$probe/before" "$probe/after" > "$probe/created"
while IFS= read -r path; do
  echo "created $path"
  if grep -qF secret-sentinel "$path" 2>/dev/null; then echo "secret $path"; fi
done < "$probe/created"
echo end
exit 0
