#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b10.app.BenchmarkApp"
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
  token="fleet-test-$$-$(date +%s)"
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
    raise SystemExit('owned fleet simulator did not become ready')

def get(path):
    with urllib.request.urlopen(base + path) as response:
        return json.load(response)

def post(path):
    request = urllib.request.Request(
        base + path,
        data=b'{}',
        method='POST',
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request) as response:
        return json.load(response)

before = get('/api/v1/service/0/inventory/item-1')
assert before['generation'] == 1
replacement = post('/api/v1/fleet/deploy/0')
assert replacement['previous_generation'] == 1
assert replacement['new_generation'] == 2
assert replacement['external_process_restarted'] is False
assert replacement['simulated_downtime_ms'] == 0
after = get('/api/v1/service/0/inventory/item-1')
assert after['generation'] == 2
assert after['value'] != before['value']
status = get('/api/v1/fleet/status')
assert status['external_processes'] == 0
assert status['process_model'] == 'single-jvm-simulation'
assert status['services'][0]['generation'] == 2
assert status['services'][1]['generation'] == 1
print('atomic logical-service replacement checks passed')
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
