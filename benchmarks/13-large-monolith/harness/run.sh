#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 13-large-monolith com.palaashatri.bench.b13.harness.BenchmarkHarness 17 "$@"
