#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 12-hft-trading-gateway com.palaashatri.bench.b12.harness.BenchmarkHarness 21 "$@"
