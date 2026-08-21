#!/usr/bin/env bash
# Downloads the SNAP ca-HepPh collaboration network:
# https://snap.stanford.edu/data/ca-HepPh.html
# 12,008 nodes / 118,521 edges (undirected) — comfortably inside the
# 100k-500k relationship range the assignment asks for, and small enough
# to fit CognoDB's free-tier 256 MB instance.
set -euo pipefail
cd "$(dirname "$0")"

URL="https://snap.stanford.edu/data/ca-HepPh.txt.gz"
OUT="ca-HepPh.txt.gz"

echo "Downloading $URL ..."
curl -fL -o "$OUT" "$URL"
echo "Done. Dataset saved to data/$OUT"
echo "Loaders read the .gz directly — no need to unpack it."

# sha256sum for reproducibility — record this value in README.md once you've
# downloaded it, so anyone re-running the benchmark can confirm they have
# the same file.
sha256sum "$OUT" || true
