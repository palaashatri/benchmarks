#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b11.app.BenchmarkApp"
CLASSES_DIR="build/run-sh/classes"
SOURCES_FILE="build/run-sh/sources.txt"

compile() {
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' -print | sort > "$SOURCES_FILE"
  javac --release 17 -d "$CLASSES_DIR" @"$SOURCES_FILE"
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

test_app() {
  compile
  port="${PORT:-$(free_port)}"
  token="capacity-test-$$-$(date +%s)"
  mkdir -p build/run-sh
  BENCH_RUN_TOKEN="$token" java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$port" \
    >build/run-sh/app-test.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$pid" "$token" <<'PY'
import concurrent.futures
import json
import sys
import time
import urllib.request

port, expected_pid, token = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
base = f'http://127.0.0.1:{port}'
for _ in range(150):
    try:
        with urllib.request.urlopen(base + '/runtime', timeout=.3) as response:
            runtime = json.load(response)
        if runtime['pid'] == expected_pid and runtime['run_token'] == token:
            break
    except Exception:
        time.sleep(.1)
else:
    raise SystemExit('owned capacity simulator did not become ready')

def submit(index):
    request = urllib.request.Request(
        base + '/api/v1/catalog/search',
        data=json.dumps({'query': f'item-{index}', 'work_ms': 200}).encode(),
        method='POST',
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        payload = json.load(response)
        return response.status, payload

with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
    responses = list(pool.map(submit, range(80)))
assert all(status == 202 and body['accepted'] is True for status, body in responses)

state = None
for _ in range(80):
    with urllib.request.urlopen(base + '/api/v1/metrics/scaling') as response:
        state = json.load(response)
    if state['scale_up_count'] > 0:
        break
    time.sleep(.1)
assert state['scale_up_count'] > 0, state
assert state['worker_capacity'] > 2, state
assert state['external_replicas'] == 0
assert state['scaling_model'] == 'single-jvm-thread-pool'
print('safe local capacity scale-up check passed')
PY
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  trap - EXIT INT TERM
}

case "$COMMAND" in
  build) compile ;;
  test) test_app ;;
  run) run "$@" ;;
  clean) rm -rf build ;;
  help|-h|--help) echo 'Usage: ./run.sh {build|test|run|clean}' ;;
  *) echo "Unknown command: $COMMAND" >&2; exit 2 ;;
esac
