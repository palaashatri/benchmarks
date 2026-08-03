#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b06.app.BenchmarkApp"; CLASSES="build/run-sh/classes"; SOURCES="build/run-sh/sources.txt"
compile(){ mkdir -p "$CLASSES"; find src/main/java -name '*.java' -print|sort>"$SOURCES"; javac --release 21 -d "$CLASSES" @"$SOURCES"; }
run(){ compile; exec java -cp "$CLASSES" "$MAIN_CLASS" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_app(){
 compile; port="${PORT:-$(free_port)}"; token="chat-test-$$-$(date +%s)"; mkdir -p build/run-sh
 BENCH_RUN_TOKEN="$token" java -cp "$CLASSES" "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$pid" "$token" <<'PY'
import json,sys,time,urllib.request
port,pid,token=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3];base=f'http://127.0.0.1:{port}'
for _ in range(120):
 try:
  with urllib.request.urlopen(base+'/runtime',timeout=.3) as r:x=json.load(r)
  if x['pid']==pid and x['run_token']==token:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned app did not become ready')
def post(path,data):
 q=urllib.request.Request(base+path,data=json.dumps(data).encode(),method='POST',headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(q) as r:return json.load(r)
post('/rooms/team/subscribers',{'user':'alice'});post('/rooms/team/subscribers',{'user':'bob'})
result=post('/rooms/team/messages',{'sender':'alice','content':'hello'})
assert result['delivered']==2 and result['delivery_model']=='simulated'
with urllib.request.urlopen(base+'/api/v1/stats') as r:stats=json.load(r)
assert stats['simulated_deliveries']==2 and stats['persistent_connections']==0
with urllib.request.urlopen(base+'/health') as r:health=json.load(r)
assert health['persistent_connections'] is False
print('subscriber delivery semantics checks passed')
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_app;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
