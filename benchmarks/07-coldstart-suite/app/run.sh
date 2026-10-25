#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b07.app.BenchmarkApp"
BUILD="build/run-sh"; CLASSES="$BUILD/classes"; SOURCES="$BUILD/sources.txt"
# The application runs from a JAR: the JVM only archives classes from JARs for AppCDS, and children
# inherit this classpath.
JAR="$(pwd)/$BUILD/app.jar"

compile(){
  rm -rf "$BUILD"; mkdir -p "$CLASSES"
  find src/main/java ../../common/src/main/java -name '*.java' | sort > "$SOURCES"
  javac --release 21 -d "$CLASSES" @"$SOURCES"
  jar --create --file "$JAR" -C "$CLASSES" .
}
run(){ compile; exec java -cp "$JAR" "$MAIN_CLASS" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_app(){
 compile; port="${PORT:-$(free_port)}"; token="coldstart-test-$$-$(date +%s)"
 BENCH_RUN_TOKEN="$token" java -cp "$JAR" "$MAIN_CLASS" "$port" >"$BUILD/app-test.log" 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$pid" "$token" <<'PY'
import json,sys,time,urllib.error,urllib.request
port,pid,token=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3];base=f'http://127.0.0.1:{port}'
def get(path):
 with urllib.request.urlopen(base+path,timeout=30) as r:return json.load(r)
def measure(body):
 request=urllib.request.Request(base+'/api/v1/coldstart/measure',data=json.dumps(body).encode(),method='POST',headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(request,timeout=90) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as error:return error.code,json.load(error)
for _ in range(300):
 try:
  identity=get('/runtime')
  if identity['pid']==pid and identity['run_token']==token:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned app did not become ready')
modes={mode['id']:mode for mode in get('/api/v1/coldstart/modes')['modes']}
assert set(modes)=={'default','no-cds','appcds'}, modes
for name,mode in modes.items():assert mode['available'] is True, (name,mode)
expected={'default':'-Xshare:auto','no-cds':'-Xshare:off','appcds':'-Xshare:on'}
for name in ['default','no-cds','appcds']:
 status,result=measure({'mode':name})
 assert status==200,(name,status,result)
 assert result['mode']==name and result['time_to_health_ms']>0 and result['measurement_kind']=='process_to_health',result
 assert result['child_pid']!=pid
 child_args=result['child_runtime']['jvm_args']
 assert expected[name] in child_args,(name,child_args)
 if name=='appcds':assert any(a.startswith('-XX:SharedArchiveFile=') for a in child_args),child_args
status,rejected=measure({'mode':'bogus'})
assert status==400,(status,rejected)
samples=get('/api/v1/coldstart/samples')['samples']
for name in ['default','no-cds','appcds']:assert len(samples[name]['time_to_health_ms'])==1 and samples[name]['failures']==0,samples
assert get('/api/v1/jfr/stats')['jit_compilation_ms'] is None or get('/api/v1/jfr/stats')['jit_compilation_ms']>=0
print('cold-start modes measured in child JVMs: default, no-cds, appcds')
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_app;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
