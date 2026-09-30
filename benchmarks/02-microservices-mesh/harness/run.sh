#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 02-microservices-mesh com.palaashatri.bench.b02.harness.BenchmarkHarness 21 "$@"
