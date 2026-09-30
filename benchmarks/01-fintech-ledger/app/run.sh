#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b01.app.BenchmarkApp"
JAVA_RELEASE=21
CLASSES_DIR="build/run-sh/classes"
DEPS_DIR="build/run-sh/deps"
SOURCES_FILE="build/run-sh/sources.txt"

fetch() {
  url="$1" destination="$2"
  [ -f "$destination" ] || curl --fail --location --silent --show-error "$url" --output "$destination"
}

download_deps() {
  mkdir -p "$DEPS_DIR"
  base="https://repo1.maven.org/maven2"
  fetch "$base/com/h2database/h2/2.2.224/h2-2.2.224.jar" "$DEPS_DIR/h2-2.2.224.jar"
  fetch "$base/com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0.jar" "$DEPS_DIR/HikariCP-5.1.0.jar"
  fetch "$base/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar" "$DEPS_DIR/slf4j-api-2.0.9.jar"
  fetch "$base/org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar" "$DEPS_DIR/slf4j-simple-2.0.9.jar"
}

classpath() {
  result=""
  for jar in "$DEPS_DIR"/*.jar; do
    [ -f "$jar" ] || continue
    result="${result:+$result:}$jar"
  done
  printf '%s' "$result"
}

compile() {
  download_deps
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' -print | sort > "$SOURCES_FILE"
  javac --release "$JAVA_RELEASE" -cp "$(classpath)" -d "$CLASSES_DIR" @"$SOURCES_FILE"
}

run() {
  compile
  exec java -cp "$CLASSES_DIR:$(classpath)" "$MAIN_CLASS" "$@"
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
  token="ledger-test-$$-$(date +%s)"
  mkdir -p build/run-sh
  BENCH_RUN_TOKEN="$token" java -cp "$CLASSES_DIR:$(classpath)" "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$pid" "$token" <<'PY'
import json, sys, time, urllib.request, urllib.error
port, expected_pid, token = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
base = f'http://127.0.0.1:{port}'
for _ in range(150):
    try:
        with urllib.request.urlopen(base + '/health', timeout=.3) as response:
            if response.status == 200:
                break
    except Exception:
        time.sleep(.1)
else:
    raise SystemExit('application did not become ready')
with urllib.request.urlopen(base + '/runtime') as response:
    runtime = json.load(response)
assert runtime['pid'] == expected_pid, (runtime, expected_pid)
assert runtime['run_token'] == token, runtime

def get(path):
    with urllib.request.urlopen(base + path) as response:
        return json.load(response)

def post(path, body):
    request = urllib.request.Request(base + path, data=json.dumps(body).encode(), method='POST', headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request) as response:
        return response.status, json.load(response)

before_a = get('/accounts/1001/balance')['balance_cents']
before_b = get('/accounts/1002/balance')['balance_cents']
status, transfer = post('/transfers', {'from':'1001','to':'1002','amount_cents':125})
assert status == 200 and transfer['approved'] is True, transfer
after_a = get('/accounts/1001/balance')['balance_cents']
after_b = get('/accounts/1002/balance')['balance_cents']
assert after_a == before_a - 125
assert after_b == before_b + 125
assert before_a + before_b == after_a + after_b
try:
    post('/transfers', {'from':'1001','to':'1001','amount_cents':1})
    raise AssertionError('self-transfer should fail')
except urllib.error.HTTPError as error:
    assert error.code == 400
with urllib.request.urlopen(base + '/metrics') as response:
    metrics = response.read().decode()
assert 'ledger_transactions_total 1' in metrics
print('ledger integration and conservation checks passed')
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
  help|-h|--help) printf '%s\n' 'Usage: ./run.sh {build|test|run|clean}' ;;
  *) printf 'Unknown command: %s\n' "$COMMAND" >&2; exit 2 ;;
esac
