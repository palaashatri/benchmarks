#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
# The application runs from its JAR so that its AppCDS archive can be built and used by child JVMs.
APP_CLASSPATH="../app/build/run-sh/app.jar" exec sh ../../../tools/workload_tests/run_harness.sh 07-coldstart-suite com.palaashatri.bench.b07.harness.BenchmarkHarness 21 "$@"
