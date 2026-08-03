#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b07.app.BenchmarkApp"; CLASSES="build/run-sh/classes"; SOURCES="build/run-sh/sources.txt"
compile(){ mkdir -p "$CLASSES"; find src/main/java -name '*.java' -print|sort>"$SOURCES"; javac --release 21 -d "$CLASSES" @"$SOURCES"; }
run(){ compile; exec java -cp "$CLASSES" "$MAIN_CLASS" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_app(){
 compile; port="${PORT:-$(free_port)}"; token="cold-test-$$-$(date +%s)"; mkdir -p build/run-sh
 BENCH_RUN_TOKEN="$token" java -cp "$CLASSES" "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$pid" "$token" <<'PY'
import json,sys,time,urllib.request
port,pid,token=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3];base=f'http://127.0.0.1:{port}'
for _ in range(150):
 try:
  with urllib.request.urlopen(base+'/runtime',timeout=.3) as r:x=json.load(r)
  if x['pid']==pid and x['run_token']==token:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned app did not become ready')
request=urllib.request.Request(base+'/api/v1/coldstart/measure',data=b'{}',method='POST',headers={'Content-Type':'application/json'})
with urllib.request.urlopen(request,timeout=20) as r:measurement=json.load(r)
assert measurement['time_to_health_ms']>0 and measurement['measurement_kind']=='process_to_health'
with urllib.request.urlopen(base+'/api/v1/jfr/stats') as r:stats=json.load(r)
assert 'jit_compilation_ms' in stats and stats['cds_mode']=='not-controlled-by-app'
print('isolated child-process cold-start check passed')
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_app;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
