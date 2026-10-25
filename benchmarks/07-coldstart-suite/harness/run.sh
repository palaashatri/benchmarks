#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
if [ "$#" -gt 0 ]; then shift; fi
MAIN_CLASS="com.palaashatri.bench.b07.harness.BenchmarkHarness"
JAVA_RELEASE="21"
DEFAULT_PORT="18007"
CLASSES_DIR="build/run-sh/classes"
SOURCES_FILE="build/run-sh/sources.txt"

compile_sources() {
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' | sort > "$SOURCES_FILE"
  if [ ! -s "$SOURCES_FILE" ]; then echo "No Java sources found under src/main/java" >&2; exit 1; fi
  javac --release "$JAVA_RELEASE" -d "$CLASSES_DIR" @"$SOURCES_FILE"
}

run_java() { compile_sources; exec java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$@"; }

wait_for_health() {
  url="$1"
  python3 - "$url" <<'PYWAIT'
import sys,time,urllib.request
url=sys.argv[1]; last=None
for _ in range(80):
    try:
        with urllib.request.urlopen(url, timeout=0.5) as response:
            if response.status == 200: raise SystemExit(0)
    except SystemExit: raise
    except Exception as exc: last=exc
    time.sleep(0.1)
print(f"Timed out waiting for {url}: {last}", file=sys.stderr); raise SystemExit(1)
PYWAIT
}

smoke_harness() {
  compile_sources
  mkdir -p build/run-sh
  port="${PORT:-$DEFAULT_PORT}"
  if [ -z "${BASE_URL:-}" ] && [ -x ../app/run.sh ]; then
    (cd ../app && mkdir -p build/run-sh && ./run.sh run "$port" > build/run-sh/harness-owned-app.log 2>&1 & echo $! > ../harness/build/run-sh/app.pid)
    pid="$(cat build/run-sh/app.pid)"
    trap 'kill "$pid" 2>/dev/null || true' EXIT INT TERM
    wait_for_health "http://127.0.0.1:$port/health"
    base_url="http://127.0.0.1:$port"
  else
    base_url="${BASE_URL:-http://127.0.0.1:$port}"
  fi
  # One pass over the request table reaches every route, including the cold-start POST.
  requests="${REQUESTS:-10}"
  java -cp "$CLASSES_DIR" "$MAIN_CLASS" --base-url "$base_url" --requests "$requests" --out build/run-sh/results.json > build/run-sh/harness-smoke.log
  test -s build/run-sh/results.json
  cat build/run-sh/results.json
  if [ -n "${pid:-}" ]; then kill "$pid" 2>/dev/null || true; trap - EXIT INT TERM; fi
}

usage() { cat <<USAGE
Usage: ./run.sh <command> [args]
Commands:
  build        Compile local Java sources with javac --release $JAVA_RELEASE.
  test         Run the live app and harness smoke.
  run [args]   Compile and run $MAIN_CLASS with provided args.
  clean        Remove build/run-sh artifacts.
  help         Show this message.
USAGE
}
case "$COMMAND" in
  build) compile_sources ;;
  test) smoke_harness ;;
  run) run_java "$@" ;;
  clean) rm -rf build/run-sh ;;
  help|-h|--help) usage ;;
  *) echo "Unknown command: $COMMAND" >&2; usage >&2; exit 2 ;;
esac
