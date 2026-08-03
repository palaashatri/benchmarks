#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN="com.palaashatri.bench.compat.CompatibilityApp"; CLASSES="build/classes"; SOURCES="build/sources.txt"
compile(){
 mkdir -p "$CLASSES"; find src/main/java -name '*.java' -print|sort>"$SOURCES"
 version="$(javac -version 2>&1)"
 case "$version" in javac\ 1.8*) javac -source 8 -target 8 -Xlint:-options -d "$CLASSES" @"$SOURCES";; *) javac --release 8 -d "$CLASSES" @"$SOURCES";; esac
}
run(){ compile; exec java -cp "$CLASSES" "$MAIN" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_app(){
 compile; port="${PORT:-$(free_port)}"; token="compat-test-$$-$(date +%s)"; mkdir -p build
 BENCH_RUN_TOKEN="$token" java -cp "$CLASSES" "$MAIN" "$port" >build/app-test.log 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$token" <<'PY'
import json,sys,time,urllib.request
port,token=int(sys.argv[1]),sys.argv[2];base=f'http://127.0.0.1:{port}'
for _ in range(150):
 try:
  with urllib.request.urlopen(base+'/runtime',timeout=.3) as r:x=json.load(r)
  if x['run_token']==token and x['bytecode_target']==8:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned compatibility app did not become ready')
def work():
 q=urllib.request.Request(base+'/work',data=b'{"seed":424242,"size":4096}',method='POST',headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(q) as r:return json.load(r)
a,b=work(),work();assert a['checksum']==b['checksum'] and a['bytecode_target']==8
print('Java 8 bytecode deterministic-work check passed')
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_app;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
