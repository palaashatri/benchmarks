#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
CLASSES="build/run-sh/classes"
compile() {
  mkdir -p "$CLASSES"
  find src/main/java ../../common/src/main/java -name '*.java' -print | sort > build/run-sh/sources.txt
  javac --release 21 -d "$CLASSES" @build/run-sh/sources.txt
}
case "$COMMAND" in
  build) compile ;;
  test) compile; python3 ../../../tools/workload_tests/test_architecture.py 06-massive-chat-loom "$CLASSES" ;;
  run) compile; exec java -cp "$CLASSES" com.palaashatri.bench.b06.app.BenchmarkApp "$@" ;;
  clean) rm -rf build ;;
  help|-h|--help) echo 'Usage: ./run.sh {build|test|run|clean}' ;;
  *) echo "Unknown command: $COMMAND" >&2; exit 2 ;;
esac
