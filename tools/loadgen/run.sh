#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CLASSES="$ROOT/build/classes"
DEPS="$ROOT/build/deps"
SOURCES="$ROOT/build/sources.txt"
HDR="$DEPS/HdrHistogram-2.2.2.jar"
MAIN="com.palaashatri.bench.load.OpenLoopLedger"

fetch_dependency() {
  mkdir -p "$DEPS"
  if [ ! -f "$HDR" ]; then
    curl --fail --location --silent --show-error \
      "https://repo1.maven.org/maven2/org/hdrhistogram/HdrHistogram/2.2.2/HdrHistogram-2.2.2.jar" \
      --output "$HDR"
  fi
}

compile() {
  fetch_dependency
  mkdir -p "$CLASSES"
  find "$ROOT/src/main/java" -name '*.java' -print | sort > "$SOURCES"
  javac --release 21 -cp "$HDR" -d "$CLASSES" @"$SOURCES"
}

run() {
  compile
  exec java -cp "$CLASSES:$HDR" "$MAIN" "$@"
}

free_port() {
  python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
}

test_loadgen() {
  compile
  port="$(free_port)"
  mkdir -p "$ROOT/build/self-test"
  python3 - "$port" <<'PY' >"$ROOT/build/self-test/server.log" 2>&1 &
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'{"status":"ok"}'
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_POST(self):
        length = int(self.headers.get('Content-Length', '0'))
        self.rfile.read(length)
        body = b'{"accepted":true}'
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *args):
        pass
ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()
PY
  server_pid=$!
  trap 'kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true' EXIT INT TERM
  sleep 0.2
  java -cp "$CLASSES:$HDR" "$MAIN" \
    --base-url "http://127.0.0.1:$port" \
    --target-rate 20 \
    --warmup-seconds 1 \
    --measure-seconds 2 \
    --threads 4 \
    --out "$ROOT/build/self-test/results.json" \
    --histogram-out "$ROOT/build/self-test/latency.hgrm"
  python3 - "$ROOT/build/self-test/results.json" "$ROOT/build/self-test/latency.hgrm" <<'PY'
import json
import pathlib
import sys
result = json.load(open(sys.argv[1]))
assert result['load_model'] == 'open-loop'
assert result['coordinated_omission_corrected'] is True
assert result['scheduled']['measurement'] == 40
assert result['completed']['measurement'] == 40
assert result['errors']['measurement'] == 0
assert result['kpis']['p99_ms'] >= 0
assert pathlib.Path(sys.argv[2]).stat().st_size > 0
print('open-loop HdrHistogram self-test passed')
PY
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  trap - EXIT INT TERM
}

case "$COMMAND" in
  build) compile ;;
  test) test_loadgen ;;
  run) run "$@" ;;
  clean) rm -rf "$ROOT/build" ;;
  help|-h|--help) echo 'Usage: tools/loadgen/run.sh {build|test|run|clean}' ;;
  *) echo "Unknown command: $COMMAND" >&2; exit 2 ;;
esac
