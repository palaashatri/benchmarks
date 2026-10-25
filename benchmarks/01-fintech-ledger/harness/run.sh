#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
# The application's dependency jars are listed relative to the app directory; the shared test starts the
# application from this directory, so the classpath is made absolute first.
(cd ../app && ./run.sh build > /dev/null)
APP_CLASSPATH="$(cd ../app && python3 -c 'import os,sys; base=os.getcwd(); print(os.pathsep.join(os.path.join(base, p) for p in open("build/run-sh/classpath.txt").read().split(os.pathsep) if p))')" \
  exec sh ../../../tools/workload_tests/run_harness.sh 01-fintech-ledger com.palaashatri.bench.b01.harness.BenchmarkHarness 21 "$@"
