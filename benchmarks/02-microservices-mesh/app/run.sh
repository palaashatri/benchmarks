#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b02.app.BenchmarkApp"
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
  token="mesh-test-$$-$(date +%s)"
  mkdir -p build/run-sh
  BENCH_RUN_TOKEN="$token" java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$port" \
    >build/run-sh/app-test.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$pid" "$token" <<'PY'
import json
import re
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
    raise SystemExit('owned mesh process did not become ready')

with urllib.request.urlopen(base + '/api/v1/users/1001') as response:
    user = json.load(response)
assert user['user_id'] == '1001'
assert user['account']['id'] == '1001'

request = urllib.request.Request(
    base + '/api/v1/orders',
    data=b'{"from_id":"1001","item":"demo","amount":125}',
    method='POST',
    headers={'Content-Type': 'application/json'},
)
with urllib.request.urlopen(request) as response:
    order = json.load(response)
assert order['status'] == 'ACCEPTED'
assert order['transaction']['status'] == 'RECORDED'

with urllib.request.urlopen(base + '/health') as response:
    health = json.load(response)
assert health['process_model'] == 'single-jvm-multi-server'
assert health['external_processes'] == 0

for _ in range(30):
    with urllib.request.urlopen(base + '/metrics') as response:
        metrics = response.read().decode()
    match = re.search(r'^mesh_transactions_retained (\d+)$', metrics, re.MULTILINE)
    notifications = re.search(r'^mesh_notifications_accepted_total (\d+)$', metrics, re.MULTILINE)
    if match and match.group(1) == '1' and notifications and int(notifications.group(1)) >= 1:
        break
    time.sleep(.05)
else:
    raise AssertionError(metrics)
print('ephemeral inner-port and single transaction-record checks passed')
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
