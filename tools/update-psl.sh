#!/usr/bin/env bash
# Refresh the bundled Mozilla Public Suffix List (BUILD.md risk 10).
#
# Build-time / developer tool only. The engine never fetches the list at runtime: it reads the
# pinned snapshot from its resources, and a test fails if the snapshot and its recorded SHA-256
# disagree. Run this, review the diff, run `./gradlew test`, then commit the three files together.
#
#   tools/update-psl.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES="$ROOT/engine/src/jvmMain/resources/dev/loupe/engine"
TEST_RES="$ROOT/engine/src/jvmTest/resources/dev/loupe/engine"
LIST_URL="https://publicsuffix.org/list/public_suffix_list.dat"
TESTS_URL="https://raw.githubusercontent.com/publicsuffix/list/main/tests/tests.txt"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -sSfL --proto '=https' -o "$tmp/list.dat" "$LIST_URL"
curl -sSfL --proto '=https' -o "$tmp/tests.txt" "$TESTS_URL"

# Sanity checks: a truncated or substituted download must not become the pinned list.
for marker in '===BEGIN ICANN DOMAINS===' '===END ICANN DOMAINS===' \
              '===BEGIN PRIVATE DOMAINS===' '===END PRIVATE DOMAINS==='; do
  grep -q -- "$marker" "$tmp/list.dat" || { echo "missing marker: $marker" >&2; exit 1; }
done
grep -q '^// VERSION: ' "$tmp/list.dat" || { echo "missing VERSION line" >&2; exit 1; }
grep -q '^// COMMIT: ' "$tmp/list.dat" || { echo "missing COMMIT line" >&2; exit 1; }
rules=$(grep -cv '^\s*\(//.*\)\?$' "$tmp/list.dat")
[ "$rules" -gt 5000 ] || { echo "only $rules rules; refusing" >&2; exit 1; }
grep -q 'checkPublicSuffix\|^example.com example.com' "$tmp/tests.txt" || { echo "tests file looks wrong" >&2; exit 1; }

cp "$tmp/list.dat" "$RES/public_suffix_list.dat"
(cd "$RES" && shasum -a 256 public_suffix_list.dat > public_suffix_list.dat.sha256)
cp "$tmp/tests.txt" "$TEST_RES/psl_tests.txt"

grep -E '^// (VERSION|COMMIT): ' "$RES/public_suffix_list.dat"
cat "$RES/public_suffix_list.dat.sha256"
echo "rules: $rules"
echo "Now update the VERSION/COMMIT/SHA-256 recorded in THIRD_PARTY_NOTICES.md and run ./gradlew test."
