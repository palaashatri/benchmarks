#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b04.app.BenchmarkApp"
CLASSES_DIR="build/run-sh/classes"
SOURCES_FILE="build/run-sh/sources.txt"

compile() {
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' -print | sort > "$SOURCES_FILE"
  javac --release 21 --enable-preview --add-modules jdk.incubator.vector \
    -d "$CLASSES_DIR" @"$SOURCES_FILE"
}

run() {
  compile
  exec java --enable-preview --add-modules=jdk.incubator.vector \
    -cp "$CLASSES_DIR" "$MAIN_CLASS" "$@"
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
  mkdir -p build/run-sh
  java --enable-preview --add-modules=jdk.incubator.vector \
    -cp "$CLASSES_DIR" "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$pid" <<'PY'
import json, os, sys, time, urllib.request
port, pid = int(sys.argv[1]), int(sys.argv[2])
base = f'http://127.0.0.1:{port}'
for _ in range(150):
    try:
        os.kill(pid, 0)
        with urllib.request.urlopen(base + '/api/v1/health', timeout=.3) as response:
            health = json.load(response)
        if health['status'] == 'UP':
            break
    except Exception:
        time.sleep(.1)
else:
    raise SystemExit('owned Vector/FFM app did not become ready')
body = json.dumps({'features':[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0,0.1,0.2,0.3,0.4,0.5,0.6]}).encode()
def infer(path):
    request = urllib.request.Request(base + path, data=body, method='POST', headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request) as response:
        return json.load(response)
scalar = infer('/api/v1/inference/scalar')
simd = infer('/api/v1/inference')
assert scalar['class'] == simd['class'], (scalar, simd)
assert abs(scalar['confidence'] - simd['confidence']) < 1e-4, (scalar, simd)
assert scalar['method'] == 'scalar' and simd['method'] == 'simd'
print('Vector/FFM scalar-equivalence check passed')
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
