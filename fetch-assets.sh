#!/usr/bin/env bash
# Download ThinkDiag_Brands zips from GitHub Releases into assets/
# Usage: ./fetch-assets.sh [output_dir]
set -euo pipefail

BASE="https://github.com/sarapudheen-ops/puthus/releases/download/assets-v1"
OUT="${1:-assets}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$SCRIPT_DIR/assets-manifest.txt"

mkdir -p "$OUT"
ok=0; fail=0
while read -r f; do
  dest="$OUT/$f"
  if [[ -s "$dest" ]]; then
    echo "skip  $f (exists)"
    ok=$((ok+1))
    continue
  fi
  echo "get   $f"
  if curl -fL --retry 3 --retry-delay 2 -C - -o "$dest.part" "$BASE/$f"; then
    mv "$dest.part" "$dest"
    ok=$((ok+1))
  else
    echo "FAIL  $f" >&2
    rm -f "$dest.part"
    fail=$((fail+1))
  fi
done < "$MANIFEST"

echo "Done: $ok ok, $fail failed -> $OUT/"
[[ $fail -eq 0 ]]
