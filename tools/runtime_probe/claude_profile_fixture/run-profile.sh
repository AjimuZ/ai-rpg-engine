#!/bin/sh
# Runs one launch-profile case of the second narration client through its supervisor (P20.1-C).
#
# Usage: run-profile.sh CASE
#   refusals      malformed requests and credential-guard cases; the supervisor must start nothing
#   signals       one stateless task per scripted upstream scenario, in API-key mode
#   readiness     one neutral readiness task per model and effort pair of readiness-pairs.txt
#   subscription  limit, overage and error scenarios over a planted fixture subscription login
#   lifecycle     create, resume, retire and resume a session, then run one stateless task
#
# Every task goes through /opt/supervisor/supervise.sh exactly as the engine would call it, with
# the prompt on stdin. The only key is the fixture string in FIXTURE_API_KEY, and the planted
# login (home-login.json) holds fixture strings only; neither is a credential.
#
# Each non-empty prompt ends with " [step-NAME]", so the test can attribute every upstream
# request to the step that caused it.
#
# The script never fails on the supervisor's behalf: it prints one report and exits 0. For each
# step: "step NAME", "status N", "stdout BASE64", "stderr BASE64". The lifecycle case adds
# "stored PATH" (a file naming the session after its first turn), "plaintext PATH" (a stored file
# holding the first prompt), "before PATH" and, after its last step, "found PATH" (the same scan:
# any path naming the session, or any file holding it or a lifecycle prompt sentinel, anywhere
# the client can write), and "file PATH" (every file left in the home directory). The report
# ends with "end". Exit 2 means a usage error and no report.
set -u
[ "$#" -eq 1 ] || { echo "usage: run-profile.sh CASE" >&2; exit 2; }
supervisor=/opt/supervisor/supervise.sh
workspace=/var/lib/runtime/workspace
probe=/tmp/probe
key_file=$probe/api-key
login=$HOME/.claude/.credentials.json
session=0f8e3c2a-5b6d-4e7f-8a9b-0c1d2e3f4a5b
mkdir -p "$probe" "$HOME/.claude" || exit 2
printf '%s' "$FIXTURE_API_KEY" > "$key_file" || exit 2
cd "$workspace" || exit 2

# step NAME PROMPT ENV... -- ARGS...: run the supervisor with PROMPT on stdin under the given
# extra environment and ARGS, and report its status and output.
step() {
  name=$1
  prompt=$2
  shift 2
  if [ -n "$prompt" ]; then prompt="$prompt [step-$name]"; fi
  printf '%s' "$prompt" > "$probe/prompt"
  set -- env "$@"
  split=0
  for word in "$@"; do
    shift
    if [ "$split" -eq 0 ] && [ "$word" = -- ]; then
      set -- "$@" sh "$supervisor"
      split=1
    else
      set -- "$@" "$word"
    fi
  done
  timeout 150 "$@" < "$probe/prompt" > "$probe/stdout" 2> "$probe/stderr"
  status=$?
  echo "step $name"
  echo "status $status"
  printf 'stdout '; base64 -w0 "$probe/stdout"; echo
  printf 'stderr '; base64 -w0 "$probe/stderr"; echo
}

# scan PREFIX: print every path naming the session, and every file holding the session or a
# lifecycle prompt sentinel, in the home, the workspace and /tmp, except this script's own files.
scan() {
  { find "$HOME" "$workspace" /tmp -path "$probe" -prune -o -name "*$session*" -print
    grep -rlE --exclude-dir=probe -- "$session|session-sentinel" "$HOME" "$workspace" /tmp 2>/dev/null
  } | sort -u | sed "s/^/$1 /"
}

api="SUPERVISOR_AUTH_MODE=api-key SUPERVISOR_API_KEY_FILE=$key_file"
check='Answer the neutral protocol check.'
model=claude-sonnet-5-5

# $api is split on purpose: it holds two assignments without spaces in their values.
# shellcheck disable=SC2086
case $1 in
  refusals)
    step usage "$check" $api --
    step operation "$check" $api -- start none $model high none
    step extra-argument "$check" $api -- run none $model high none --tools
    step session-shape "$check" $api -- run new:not-a-uuid $model high none
    step session-upper-case "$check" $api -- run new:0F8E3C2A-5B6D-4E7F-8A9B-0C1D2E3F4A5B $model high none
    step model-option "$check" $api -- run none --dangerously-skip-permissions high none
    step model-with-space "$check" $api -- run none 'claude-x --tools Bash' high none
    step effort-unknown "$check" $api -- run none $model bogus none
    step schema-path "$check" $api -- run none $model high ../settings
    step schema-absent "$check" $api -- run none $model high absent
    step prompt-empty '' $api -- run none $model high none
    step prompt-size "$(head -c 262145 /dev/zero | tr '\0' a)" $api -- run none $model high none
    step auth-mode "$check" SUPERVISOR_AUTH_MODE=other -- run none $model high none
    step api-key-file "$check" SUPERVISOR_AUTH_MODE=api-key SUPERVISOR_API_KEY_FILE=$probe/absent -- run none $model high none
    step retire-shape "$check" $api -- retire ../workspace
    step guard-oauth-variable "$check" $api CLAUDE_CODE_OAUTH_TOKEN=fixture-oauth-token-not-a-credential -- run none $model high none
    cp /fixture/home-login.json "$login" || exit 2
    step guard-stored-login "$check" $api -- run none $model high none
    rm -f "$login"
    step guard-api-key-variable "$check" SUPERVISOR_AUTH_MODE=subscription ANTHROPIC_API_KEY=fixture-api-key-not-a-credential -- run none $model high none
    step guard-api-key-file "$check" SUPERVISOR_AUTH_MODE=subscription SUPERVISOR_API_KEY_FILE=$key_file -- run none $model high none
    ;;
  signals)
    step schema "$check scenario-schema" $api -- run none $model high neutral-check
    step schema-unanswered "$check scenario-complete" $api -- run none $model high neutral-check
    step prompt-is-data "--version $check scenario-complete" $api -- run none $model high none
    for scenario in auth billing invalid toolong overloaded server ratelimit overage maxtokens; do
      step "$scenario" "$check scenario-$scenario" $api -- run none $model high none
    done
    step model "$check scenario-complete" $api -- run none claude-unlisted-fixture high none
    ;;
  readiness)
    while read -r pair; do
      step "$pair" "$check readiness" $api -- run none "${pair%@*}" "${pair#*@}" none
    done < /fixture/readiness-pairs.txt
    ;;
  subscription)
    cp /fixture/home-login.json "$login" && chmod 600 "$login" || exit 2
    for scenario in complete ratelimit overage auth billing; do
      step "$scenario" "$check scenario-$scenario" SUPERVISOR_AUTH_MODE=subscription -- run none $model high none
    done
    ;;
  lifecycle)
    step create "$check scenario-complete session-sentinel-first" $api -- run "new:$session" $model high none
    find "$HOME" -name "*$session*" -type f | sed 's/^/stored /'
    find "$HOME" -name "*$session*" -type f -exec grep -lF session-sentinel-first {} + | sed 's/^/plaintext /'
    step resume "$check scenario-complete session-sentinel-second" $api -- run "resume:$session" $model high none
    step reuse "$check scenario-complete session-sentinel-reuse" $api -- run "new:$session" $model high none
    scan before
    step retire '' $api -- retire "$session"
    step resume-retired "$check scenario-complete session-sentinel-after" $api -- run "resume:$session" $model high none
    step stateless "$check scenario-complete session-sentinel-stateless" $api -- run none $model high none
    scan found
    find "$HOME" -type f | sort | sed 's/^/file /'
    ;;
  *)
    echo "unknown case: $1" >&2
    exit 2
    ;;
esac
echo end
exit 0
