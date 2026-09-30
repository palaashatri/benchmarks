#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b12.app.BenchmarkApp"
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
  token="gateway-test-$$-$(date +%s)"
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
    raise SystemExit('owned matching-engine process did not become ready')

def post(body):
    request = urllib.request.Request(
        base + '/orders',
        data=json.dumps(body).encode(),
        method='POST',
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request) as response:
        return json.load(response)

def get(order_id):
    with urllib.request.urlopen(base + '/orders/' + order_id) as response:
        return json.load(response)

buy = post({'symbol': 'ALPHA', 'side': 'BUY', 'quantity': 100, 'price_nanos': 150})
other_symbol = post({'symbol': 'BETA', 'side': 'SELL', 'quantity': 100, 'price_nanos': 100})
assert get(buy['order_id'])['status'] == 'OPEN', 'different symbols must not interact'
assert get(other_symbol['order_id'])['status'] == 'OPEN'

first_sell = post({'symbol': 'ALPHA', 'side': 'SELL', 'quantity': 40, 'price_nanos': 140})
assert get(first_sell['order_id'])['status'] == 'FILLED'
partially_filled = get(buy['order_id'])
assert partially_filled['status'] == 'PARTIALLY_FILLED'
assert partially_filled['remaining_quantity'] == 60

second_sell = post({'symbol': 'ALPHA', 'side': 'SELL', 'quantity': 60, 'price_nanos': 140})
assert get(buy['order_id'])['status'] == 'FILLED'
assert get(second_sell['order_id'])['status'] == 'FILLED'

with urllib.request.urlopen(base + '/health') as response:
    health = json.load(response)
assert health['transport'] == 'http-prototype'
assert health['grpc_active'] is False
print('symbol isolation, partial-fill, and transport-truth checks passed')
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
