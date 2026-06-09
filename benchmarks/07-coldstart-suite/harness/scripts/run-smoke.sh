#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
requests="${REQUESTS:-5}"
if [ -n "${BASE_URL:-}" ]; then
  ./run.sh run --base-url "$BASE_URL" --requests "$requests" --out results/results.json
else
  REQUESTS="$requests" ./run.sh test
  mkdir -p results
  cp build/run-sh/results.json results/results.json
fi
