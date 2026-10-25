#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
# The Vector and FFM paths are preview APIs on JDK 21; the application needs the same flags the JVM was started with.
JDK_JAVA_OPTIONS="--enable-preview --add-modules jdk.incubator.vector" \
  APP_CLASSPATH="../app/build/run-sh/classes" \
  exec sh ../../../tools/workload_tests/run_harness.sh 04-ml-inference-panama-vector com.palaashatri.bench.b04.harness.BenchmarkHarness 21 "$@"
