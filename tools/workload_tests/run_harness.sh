#!/usr/bin/env sh
set -eu
WORKLOAD=$1
MAIN_CLASS=$2
JAVA_RELEASE=$3
shift 3
COMMAND=${1:-help}
if [ "$#" -gt 0 ]; then shift; fi
CLASSES=build/run-sh/classes
compile() {
  mkdir -p "$CLASSES"
  find src/main/java ../../common/src/main/java ../../../tools/smoke/src/main/java -name '*.java' -print | sort > build/run-sh/sources.txt
  javac --release "$JAVA_RELEASE" -d "$CLASSES" @build/run-sh/sources.txt
}
case "$COMMAND" in
  build) compile ;;
  run) compile; exec java -cp "$CLASSES" "$MAIN_CLASS" "$@" ;;
  test)
    compile
    (cd ../app && ./run.sh build)
    exec python3 ../../../tools/workload_tests/test_harness.py "$WORKLOAD" ../app/build/run-sh/classes "$CLASSES"
    ;;
  clean) rm -rf build/run-sh ;;
  help|-h|--help) echo 'Usage: ./run.sh build|test|run [--base-url URL --requests N --threads N --runs N --out FILE]|clean' ;;
  *) echo "Unknown command: $COMMAND" >&2; exit 2 ;;
esac
