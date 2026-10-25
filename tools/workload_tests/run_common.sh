#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT="$ROOT/benchmarks/common/build/test"
mkdir -p "$OUT/classes"
TEST_TMP=$(mktemp -d)
trap 'rm -rf "$TEST_TMP"' EXIT INT TERM
find "$ROOT/benchmarks/common/src/main/java" "$ROOT/tools/workload_tests/java" -name '*.java' -print | sort > "$OUT/sources.txt"
javac --release 17 -d "$OUT/classes" @"$OUT/sources.txt"
PORT=49152 java -Djava.io.tmpdir="$TEST_TMP" -cp "$OUT/classes" com.palaashatri.bench.common.LifecycleTest
java -cp "$OUT/classes" com.palaashatri.bench.common.JsonTest
java -cp "$OUT/classes" com.palaashatri.bench.common.TcpLifecycleTest
