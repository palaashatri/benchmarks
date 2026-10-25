#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 03-streaming-analytics com.palaashatri.bench.b03.harness.BenchmarkHarness 21 "$@"
