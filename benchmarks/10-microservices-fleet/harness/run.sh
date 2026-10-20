#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 10-microservices-fleet com.palaashatri.bench.b10.harness.BenchmarkHarness 17 "$@"
