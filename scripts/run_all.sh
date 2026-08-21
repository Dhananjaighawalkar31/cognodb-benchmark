#!/usr/bin/env bash
# Builds the harness and runs the full workload suite against every platform,
# one after another (same client, same time window, so network conditions are
# as comparable as we can make them). Results land as JSON under results/.
#
# Prereqs:
#   1. cp .env.example .env and fill in real credentials for each platform
#   2. ./data/download_dataset.sh
#   3. Nebula running locally: docker compose -f docker/docker-compose.nebula.yml up -d
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Building..."
mvn -q -DskipTests package

JAR=target/cognodb-benchmark.jar
DATASET=data/ca-HepPh.txt.gz
ITERATIONS="${ITERATIONS:-100}"
CONCURRENCY="${CONCURRENCY:-20}"
DURATION="${DURATION:-20}"

for platform in cognodb aura memgraph arango nebula; do
  echo ""
  echo "==================================================================="
  echo "==> Running: $platform"
  echo "==================================================================="
  java -jar "$JAR" \
    --platform="$platform" \
    --dataset="$DATASET" \
    --iterations="$ITERATIONS" \
    --concurrency="$CONCURRENCY" \
    --duration="$DURATION" \
    || echo "!! $platform run failed — see console output above; record this as a caveat in README.md"
done

echo ""
echo "All runs attempted. Raw JSON results are in results/. Now:"
echo "  1. Transcribe the p50/p95 numbers into the results tables in README.md"
echo "  2. Write the analysis section"
echo "  3. Commit results/*.json alongside README.md for transparency"
