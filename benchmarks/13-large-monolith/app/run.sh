#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b13.app.BenchmarkApp"
BUILD="build/run-sh"; CLASSES="$BUILD/classes"; GENERATED="$BUILD/generated"; SOURCES="$BUILD/sources.txt"

compile(){
  rm -rf "$BUILD"; mkdir -p "$CLASSES" "$GENERATED"
  python3 tools/generate_graph.py --out "$GENERATED"
  find src/main/java "$GENERATED" ../../common/src/main/java -name '*.java' | sort > "$SOURCES"
  javac --release 17 -d "$CLASSES" @"$SOURCES"
  printf '%s' "$CLASSES" > "$BUILD/classpath.txt"
}
run(){ compile; exec java -cp "$CLASSES" "$MAIN_CLASS" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_app(){
 compile; port="${PORT:-$(free_port)}"; token="monolith-test-$$-$(date +%s)"
 BENCH_RUN_TOKEN="$token" java -cp "$CLASSES" "$MAIN_CLASS" "$port" >"$BUILD/app-test.log" 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$pid" "$token" <<'PY'
import json,sys,time,urllib.request
port,pid,token=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3];base=f'http://127.0.0.1:{port}'
def get(path):
 with urllib.request.urlopen(base+path,timeout=5) as r:return json.load(r)
for _ in range(300):
 try:
  x=get('/runtime')
  if x['pid']==pid and x['run_token']==token:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned app did not become ready')
graph=get('/api/v1/monolith/graph')
assert graph['components']==graph['modules']*graph['depth'] and graph['components']>=1000, graph
assert graph['wired_components']==graph['components'], graph
assert graph['reference_result']==graph['expected_result'], graph
for _ in range(10):
 work=get('/api/v1/monolith/work')
 assert work['components_visited']==graph['components'], work
 assert work['result']==graph['expected_result'], (work, graph)
assert get('/health')['wired_components']==graph['components']
status=get('/api/v1/monolith/warmup/status')
assert status['components']==graph['components'] and status['loaded_classes']>=graph['components'], status
assert status['startup_ms']>=0 and status['business_operations']==10, status
with urllib.request.urlopen(base+'/metrics',timeout=5) as r:assert b'monolith_components' in r.read()
print(f"wired {graph['wired_components']} components; 10 traversals matched the reference result")
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_app;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
