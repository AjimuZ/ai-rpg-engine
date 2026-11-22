#!/bin/sh
# Verify one file against a single npm registry integrity string (`sha512-<base64>`).
#
# Usage: verify-sri FILE INTEGRITY
# Exit 0 only when the SHA-512 of FILE equals the digest in INTEGRITY; exit 1 when
# the digests differ or FILE cannot be read; exit 2 for bad usage, another
# algorithm, or a digest that does not decode to 64 bytes. Only coreutils are used,
# so the image that runs it needs no Node.js, npm or other package manager.
set -eu

[ "$#" -eq 2 ] || { echo "usage: verify-sri FILE sha512-BASE64" >&2; exit 2; }
file=$1
integrity=$2

# One SHA-512 entry only: a weaker or additional algorithm must never satisfy the pin.
case "$integrity" in
  sha512-*' '* | sha512-) echo "verify-sri: exactly one sha512 digest is required" >&2; exit 2 ;;
  sha512-*) ;;
  *) echo "verify-sri: only sha512 integrity is accepted" >&2; exit 2 ;;
esac

expected=$(printf '%s' "${integrity#sha512-}" | base64 -d 2>/dev/null | od -An -v -tx1 | tr -d ' \n')
[ "${#expected}" -eq 128 ] || { echo "verify-sri: malformed sha512 digest" >&2; exit 2; }

[ -r "$file" ] || { echo "verify-sri: cannot read $file" >&2; exit 1; }
actual=$(sha512sum "$file" | cut -d' ' -f1)
if [ "$actual" != "$expected" ]; then
  echo "verify-sri: integrity mismatch for $file" >&2
  exit 1
fi
echo "verify-sri: $file matches its sha512 integrity"
