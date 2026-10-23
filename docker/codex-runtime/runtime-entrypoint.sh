#!/bin/sh
# Image entrypoint for the narration runtime (P01.3).
#
# Purpose: refuse to start the runtime when its home directory holds files
# the runtime would load as configuration or instructions. The home is the
# private runtime-account volume; only sign-in and session state belong there.
#
# Why a guard: verified with the pinned runtime (0.164.0), CODEX_HOME/AGENTS.md
# (and AGENTS.override.md) is always sent upstream as user instructions, and
# no configuration key disables it. CODEX_HOME/config.toml can also set keys
# the managed layer does not pin (for example model providers or base URLs).
# A copied developer home must therefore stop the runtime, not shape it.
#
# Inputs: CODEX_HOME (set by the image) and the runtime arguments ("$@").
# Output: on success, replaces itself with the runtime via exec, so signals
# and the exit status belong to the runtime. Failure: prints the offending
# file name (never its content) to stderr and exits 78 (EX_CONFIG).
set -eu

home="${CODEX_HOME:?CODEX_HOME must be set}"

# File names only; the guard never opens these files. `-e` and `-L` together
# also catch dangling symbolic links planted to point elsewhere later.
for name in config.toml AGENTS.md AGENTS.override.md; do
    if [ -e "$home/$name" ] || [ -L "$home/$name" ]; then
        echo "runtime-entrypoint: refusing to start: $home/$name is inherited configuration; remove it from the runtime account volume" >&2
        exit 78
    fi
done

exec /usr/local/bin/codex "$@"
