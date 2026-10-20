#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 11-autoscaling-burst com.palaashatri.bench.b11.harness.BenchmarkHarness 17 "$@"
