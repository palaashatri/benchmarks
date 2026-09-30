#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec sh ../../../tools/workload_tests/run_harness.sh 06-massive-chat-loom com.palaashatri.bench.b06.harness.BenchmarkHarness 21 "$@"
