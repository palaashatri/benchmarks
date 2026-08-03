#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b09.app.BenchmarkApp"
CLASSES_DIR="build/run-sh/classes"
SOURCES_FILE="build/run-sh/sources.txt"

compile() {
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' -print | sort > "$SOURCES_FILE"
  javac --release 17 -d "$CLASSES_DIR" @"$SOURCES_FILE"
}
run() { compile; exec java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$@"; }
free_port() { python3 - <<'PY'
import socket
with socket.socket() as s:
    s.bind(('127.0.0.1',0)); print(s.getsockname()[1])
PY
}
test_app() {
  compile
  port="${PORT:-$(free_port)}"; token="onnx-fallback-test-$$-$(date +%s)"; mkdir -p build/run-sh
  BENCH_RUN_TOKEN="$token" java -cp "$CLASSES_DIR" "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 & pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$pid" "$token" <<'PY'
import json,sys,time,urllib.request
port,pid,token=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3];base=f'http://127.0.0.1:{port}'
for _ in range(120):
    try:
        with urllib.request.urlopen(base+'/runtime',timeout=.3) as r: runtime=json.load(r)
        if runtime['pid']==pid and runtime['run_token']==token: break
    except Exception: time.sleep(.1)
else: raise SystemExit('owned process did not become ready')
with urllib.request.urlopen(base+'/api/v1/inference/health') as r: health=json.load(r)
assert health['mode']=='java-fallback' and health['onnx_session_active'] is False
request=urllib.request.Request(base+'/api/v1/inference/classify',data=b'{"features":[5.1,3.5,1.4,0.2]}',method='POST',headers={'Content-Type':'application/json'})
with urllib.request.urlopen(request) as r: result=json.load(r)
assert result['mode']=='java-fallback' and result['onnx_session_active'] is False
print('truthful Java fallback inference checks passed')
PY
  kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; trap - EXIT INT TERM
}
case "$COMMAND" in
 build) compile;; test) test_app;; run) run "$@";; clean) rm -rf build;; help|-h|--help) echo 'Usage: ./run.sh {build|test|run|clean}';; *) exit 2;;
esac
