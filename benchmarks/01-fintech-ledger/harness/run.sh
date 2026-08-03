#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b01.harness.BenchmarkHarness"
CLASSES_DIR="build/run-sh/classes"
SOURCES_FILE="build/run-sh/sources.txt"

compile() {
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' -print | sort > "$SOURCES_FILE"
  javac --release 21 -d "$CLASSES_DIR" @"$SOURCES_FILE"
}

run() {
  compile
  exec java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$@"
}

free_port() {
  python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
}

test_harness() {
  compile
  port="${PORT:-$(free_port)}"
  token="harness-test-$$-$(date +%s)"
  mkdir -p build/run-sh
  (cd ../app && BENCH_RUN_TOKEN="$token" ./run.sh run "$port") >build/run-sh/owned-app.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$token" <<'PY'
import json, sys, time, urllib.request
port, token = int(sys.argv[1]), sys.argv[2]
base=f'http://127.0.0.1:{port}'
for _ in range(200):
    try:
        with urllib.request.urlopen(base + '/runtime', timeout=.3) as response:
            runtime=json.load(response)
        if runtime['run_token']==token:
            break
    except Exception:
        time.sleep(.1)
else:
    raise SystemExit('owned app did not become ready')
PY
  java -cp "$CLASSES_DIR" "$MAIN_CLASS" --base-url "http://127.0.0.1:$port" --requests 30 --threads 4 --out build/run-sh/results.json
  python3 - <<'PY'
import json
with open('build/run-sh/results.json') as handle:
    result=json.load(handle)
assert result['measurement_valid'] is False
assert result['run_kind']=='smoke'
assert result['mode_kpis']['failures']==0
assert result['kpis']['gc_pause_p99_ms'] is None
print('harness truthfulness checks passed')
PY
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  trap - EXIT INT TERM
}

case "$COMMAND" in
  build) compile ;;
  test) test_harness ;;
  run) run "$@" ;;
  clean) rm -rf build ;;
  help|-h|--help) printf '%s\n' 'Usage: ./run.sh {build|test|run|clean}' ;;
  *) printf 'Unknown command: %s\n' "$COMMAND" >&2; exit 2 ;;
esac
