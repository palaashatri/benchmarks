#!/usr/bin/env sh
set -eu

COMMAND="${1:-help}"
if [ "$#" -gt 0 ]; then
  shift
fi

MAIN_CLASS="com.palaashatri.bench.b06.app.BenchmarkApp"
JAVA_RELEASE="21"
ROLE="app"
CLI_APP="false"
DEFAULT_PORT="18006"
CLASSES_DIR="build/run-sh/classes"
SOURCES_FILE="build/run-sh/sources.txt"

compile_sources() {
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' | sort > "$SOURCES_FILE"
  if [ ! -s "$SOURCES_FILE" ]; then
    echo "No Java sources found under src/main/java" >&2
    exit 1
  fi
  javac --release "$JAVA_RELEASE" -d "$CLASSES_DIR" @"$SOURCES_FILE"
}

run_java() {
  compile_sources
  java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$@"
}

wait_for_health() {
  url="$1"
  python3 - "$url" <<'PYWAIT'
import sys
import time
import urllib.request

url = sys.argv[1]
last = None
for _ in range(50):
    try:
        with urllib.request.urlopen(url, timeout=0.5) as response:
            if response.status == 200:
                sys.exit(0)
    except Exception as exc:
        last = exc
    time.sleep(0.1)
print(f"Timed out waiting for {url}: {last}", file=sys.stderr)
sys.exit(1)
PYWAIT
}

smoke_app() {
  compile_sources
  if [ "$CLI_APP" = "true" ]; then
    rm -rf build/run-sh/smoke
    mkdir -p build/run-sh
    java -cp "$CLASSES_DIR" "$MAIN_CLASS" --records 5 --out build/run-sh/smoke > build/run-sh/app-smoke.log
    test -s build/run-sh/smoke/result.json
    cat build/run-sh/smoke/result.json
    return 0
  fi

  port="${PORT:-$DEFAULT_PORT}"
  mkdir -p build/run-sh
  java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$port" > build/run-sh/app-smoke.log 2>&1 &
  pid="$!"
  trap 'kill "$pid" 2>/dev/null || true' EXIT INT TERM
  wait_for_health "http://127.0.0.1:$port/health"
  python3 - "$port" <<'PYAPP'
import sys
import urllib.request

port = sys.argv[1]
for path in ("/health", "/metrics", "/bench/smoke/1"):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=2) as response:
        if response.status != 200:
            raise SystemExit(f"{path} returned {response.status}")
print(f"app smoke passed on port {port}")
PYAPP
  kill "$pid" 2>/dev/null || true
  trap - EXIT INT TERM
}

smoke_harness() {
  compile_sources
  mkdir -p build/run-sh
  base_url="${BASE_URL:-http://127.0.0.1:${PORT:-9}}"
  requests="${REQUESTS:-2}"
  java -cp "$CLASSES_DIR" "$MAIN_CLASS" --base-url "$base_url" --requests "$requests" --out build/run-sh/results.json > build/run-sh/harness-smoke.log
  test -s build/run-sh/results.json
  cat build/run-sh/results.json
}

usage() {
  cat <<USAGE
Usage: ./run.sh <command> [args]

Commands:
  build        Compile the local Java sources with javac --release $JAVA_RELEASE.
  test         Run a local smoke test (app health/metrics or harness results JSON).
  run [args]   Compile and run $MAIN_CLASS with the provided args.
  clean        Remove build/run-sh artifacts.
  help         Show this message.

Environment:
  PORT         App smoke-test port (default $DEFAULT_PORT) or harness target port fallback.
  BASE_URL     Harness target URL (default http://127.0.0.1:$PORT or port 9).
  REQUESTS     Harness smoke request count (default 2).
USAGE
}

case "$COMMAND" in
  build)
    compile_sources
    ;;
  test)
    if [ "$ROLE" = "app" ]; then
      smoke_app
    else
      smoke_harness
    fi
    ;;
  run)
    run_java "$@"
    ;;
  clean)
    rm -rf build/run-sh
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    echo "Unknown command: $COMMAND" >&2
    usage >&2
    exit 2
    ;;
esac
