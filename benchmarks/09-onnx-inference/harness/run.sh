#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 09-onnx-inference com.palaashatri.bench.b09.harness.BenchmarkHarness 17 "$@"
