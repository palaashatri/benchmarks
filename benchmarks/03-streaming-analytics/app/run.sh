#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b03.app.BenchmarkApp"
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

test_app() {
  compile
  port="${PORT:-$(free_port)}"
  token="stream-test-$$-$(date +%s)"
  mkdir -p build/run-sh
  BENCH_RUN_TOKEN="$token" java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$port" \
    >build/run-sh/app-test.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$pid" "$token" <<'PY'
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
    raise SystemExit('owned streaming process did not become ready')

def post(value):
    body = json.dumps({
        'key': 'sensor-a',
        'value': value,
        'timestamp_ms': 1700000001234,
    }).encode()
    request = urllib.request.Request(
        base + '/api/v1/events',
        data=body,
        method='POST',
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request) as response:
        payload = json.load(response)
        assert response.status == 202 and payload['accepted'] is True

for value in (1.0, 2.0, 3.0):
    post(value)

for _ in range(100):
    with urllib.request.urlopen(base + '/api/v1/lag') as response:
        lag = json.load(response)
    if lag['accepted'] == 3 and lag['consumed'] == 3 and lag['lag'] == 0:
        break
    time.sleep(.02)
else:
    raise AssertionError(lag)

with urllib.request.urlopen(base + '/api/v1/windows/sensor-a') as response:
    window = json.load(response)
assert window['window_start_ms'] == 1700000000000
assert window['window_size_ms'] == 10000
assert window['count'] == 3
assert abs(window['sum'] - 6.0) < 1e-9
assert abs(window['average'] - 2.0) < 1e-9
assert abs(window['minimum'] - 1.0) < 1e-9
assert abs(window['maximum'] - 3.0) < 1e-9

with urllib.request.urlopen(base + '/health') as response:
    health = json.load(response)
assert health['state_backend'] == 'in-memory'
assert health['external_broker'] is False
print('deterministic event-time window and lag checks passed')
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
