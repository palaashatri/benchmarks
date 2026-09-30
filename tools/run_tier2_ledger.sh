#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME_VALUE=${JAVA_HOME:-}
if [ -z "$JAVA_HOME_VALUE" ]; then
  echo "ERROR: set JAVA_HOME to an OpenJDK 21-25 installation" >&2
  exit 2
fi
OUT=${1:-"$ROOT/results/ledger-tier2"}
mkdir -p "$(dirname -- "$OUT")"
exec python3 "$ROOT/tools/run_ledger_experiment.py" \
  --java-home "$JAVA_HOME_VALUE" \
  --gc "${GC:-g1}" \
  --target-rate "${TARGET_RATE:-80}" \
  --warmup-seconds "${WARMUP_SECONDS:-8}" \
  --measure-seconds "${MEASURE_SECONDS:-12}" \
  --threads "${THREADS:-16}" \
  --repetitions "${REPETITIONS:-5}" \
  --heap-mb "${HEAP_MB:-512}" \
  --run-kind benchmark \
  --out "$OUT"
