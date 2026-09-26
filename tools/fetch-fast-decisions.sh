#!/usr/bin/env bash
# Fetches the Fast Decisions development split and verifies it, byte for byte.
#
# The data is not committed: it is 1.9 MB of someone else's dataset, and the same treatment the
# Phishing.Database fixtures get (a trimmed sample in git, the rest fetched) keeps the repository
# honest about what it owns. Every file is checked against tools/fast-decisions.sha256, so a
# silently re-generated upstream file fails the fetch instead of quietly moving a benchmark number.
#
#   tools/fetch-fast-decisions.sh [target-dir]      # default third-party/fast-decisions
#
# Then:  ./gradlew :loupe-kit:fastDecisions
set -euo pipefail

REPO="https://huggingface.co/datasets/fastino/fast-decisions/resolve/main"
DIR="${1:-third-party/fast-decisions}"
LOCK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fast-decisions.sha256"

mkdir -p "$DIR"
grep -v '^#' "$LOCK" | while read -r _ name; do
  [ -n "$name" ] || continue
  if [ -f "$DIR/$name" ]; then
    echo "have $name"
  else
    echo "get  $name"
    curl -fsSL --retry 3 --retry-delay 2 -o "$DIR/$name.part" "$REPO/$name"
    mv "$DIR/$name.part" "$DIR/$name"
  fi
done

echo "verifying against $(basename "$LOCK") ..."
( cd "$DIR" && grep -v '^#' "$LOCK" | sha256sum --check --strict )
echo "OK: 17 files, 1700 rows, 2900 head-instances in $DIR"
