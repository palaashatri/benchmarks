#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN="com.palaashatri.bench.compat.harness.CompatibilityHarness"; CLASSES="build/classes"; SOURCES="build/sources.txt"
compile(){ mkdir -p "$CLASSES"; find src/main/java -name '*.java' -print|sort>"$SOURCES"; version="$(javac -version 2>&1)"; case "$version" in javac\ 1.8*)javac -source 8 -target 8 -Xlint:-options -d "$CLASSES" @"$SOURCES";;*)javac --release 8 -d "$CLASSES" @"$SOURCES";;esac; }
run(){ compile; exec java -cp "$CLASSES" "$MAIN" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_harness(){
 compile; port="${PORT:-$(free_port)}"; token="compat-harness-$$-$(date +%s)"; mkdir -p build
 (cd ../app && BENCH_RUN_TOKEN="$token" ./run.sh run "$port") >build/app.log 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$token" <<'PY'
import json,sys,time,urllib.request
port,token=int(sys.argv[1]),sys.argv[2]
for _ in range(150):
 try:
  with urllib.request.urlopen(f'http://127.0.0.1:{port}/runtime',timeout=.3) as r:x=json.load(r)
  if x['run_token']==token:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned app did not become ready')
PY
 java -cp "$CLASSES" "$MAIN" --base-url "http://127.0.0.1:$port" --requests 20 --threads 4 --out build/result.json
 python3 - <<'PY'
import json
x=json.load(open('build/result.json'));assert x['measurement_valid'] is False and x['mode_kpis']['failures']==0;assert x['kpis']['gc_pause_p99_ms'] is None
print('compatibility harness truthfulness check passed')
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_harness;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
