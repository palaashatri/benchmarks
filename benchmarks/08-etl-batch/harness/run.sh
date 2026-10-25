#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 08-etl-batch com.palaashatri.bench.b08.harness.BenchmarkHarness 21 "$@"
