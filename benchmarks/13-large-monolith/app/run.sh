#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b13.app.BenchmarkApp"; CLASSES="build/run-sh/classes"; SOURCES="build/run-sh/sources.txt"
compile(){ mkdir -p "$CLASSES"; find src/main/java -name '*.java' -print|sort>"$SOURCES"; javac --release 17 -d "$CLASSES" @"$SOURCES"; }
run(){ compile; exec java -cp "$CLASSES" "$MAIN_CLASS" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_app(){
 compile; port="${PORT:-$(free_port)}"; token="monolith-test-$$-$(date +%s)"; mkdir -p build/run-sh
 BENCH_RUN_TOKEN="$token" java -cp "$CLASSES" "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$pid" "$token" <<'PY'
import json,sys,time,urllib.request
port,pid,token=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3];base=f'http://127.0.0.1:{port}'
for _ in range(200):
 try:
  with urllib.request.urlopen(base+'/runtime',timeout=.3) as r:x=json.load(r)
  if x['pid']==pid and x['run_token']==token:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned app did not become ready')
with urllib.request.urlopen(base+'/health') as r:health=json.load(r)
assert health['generated_component_classes']==500 and health['tier']=='tier-0'
for _ in range(20):
 with urllib.request.urlopen(base+'/api/v1/monolith/work') as r:work=json.load(r)
 assert work['components_visited']==500
with urllib.request.urlopen(base+'/api/v1/monolith/warmup/status') as r:status=json.load(r)
assert status['generated_component_classes']==500 and 'jit_compilation_ms' in status
assert 'compiled_methods' not in status
print('class-surface and metric-semantics checks passed')
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_app;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
