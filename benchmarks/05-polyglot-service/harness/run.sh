#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
APP_CLASSPATH="../app/build/run-sh/classes:../app/build/run-sh/deps/rhino-1.7.15.jar" exec sh ../../../tools/workload_tests/run_harness.sh 05-polyglot-service com.palaashatri.bench.b05.harness.BenchmarkHarness 21 "$@"
