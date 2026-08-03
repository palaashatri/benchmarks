#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b05.app.BenchmarkApp"
CLASSES_DIR="build/run-sh/classes"
DEPS_DIR="build/run-sh/deps"
SOURCES_FILE="build/run-sh/sources.txt"
RHINO_JAR="$DEPS_DIR/rhino-1.7.15.jar"

fetch_dependency() {
  mkdir -p "$DEPS_DIR"
  if [ ! -f "$RHINO_JAR" ]; then
    curl --fail --location --silent --show-error \
      "https://repo1.maven.org/maven2/org/mozilla/rhino/1.7.15/rhino-1.7.15.jar" \
      --output "$RHINO_JAR"
  fi
}

compile() {
  fetch_dependency
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' -print | sort > "$SOURCES_FILE"
  javac --release 21 -cp "$RHINO_JAR" -d "$CLASSES_DIR" @"$SOURCES_FILE"
}

run() {
  compile
  exec java -cp "$CLASSES_DIR:$RHINO_JAR" "$MAIN_CLASS" "$@"
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
  token="dynamic-test-$$-$(date +%s)"
  mkdir -p build/run-sh
  BENCH_RUN_TOKEN="$token" java -cp "$CLASSES_DIR:$RHINO_JAR" \
    "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$pid" "$token" <<'PY'
import json
import sys
import time
import urllib.error
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
    raise SystemExit('owned dynamic-language process did not become ready')

def post(path, payload):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode(),
        method='POST',
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.status, json.load(response)

status, rule = post('/api/v1/score/rule/1', {'amount': 750})
assert status == 200 and rule['cache_hit'] is True
assert abs(float(rule['result']) - 0.375) < 1e-9

source = 'function score(data){return data.value*2.0;}score(data);'
status, first = post('/api/v1/score', {'script': source, 'data': {'value': 21}})
status, second = post('/api/v1/score', {'script': source, 'data': {'value': 21}})
assert first['result'] == '42' and first['cache_hit'] is False and first['compile_ns'] > 0
assert second['result'] == '42' and second['cache_hit'] is True and second['compile_ns'] == 0

try:
    post('/api/v1/score', {'script': 'Packages.java.lang.System.exit(0);', 'data': {}})
    raise AssertionError('host-access script should be rejected')
except urllib.error.HTTPError as error:
    assert error.code == 400
    rejected = json.load(error)
    assert rejected['error'] == 'invalid_script'

with urllib.request.urlopen(base + '/api/v1/scripts') as response:
    cache = json.load(response)
assert cache['cache_misses'] == 1
assert cache['cache_hits'] >= 2
assert cache['host_class_access'] is False
assert cache['rejected_scripts'] >= 1
print('sandbox, deterministic result, and cache-semantics checks passed')
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
