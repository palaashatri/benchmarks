#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
MAIN_CLASS="com.palaashatri.bench.b08.app.BenchmarkApp"; CLASSES="build/run-sh/classes"; SOURCES="build/run-sh/sources.txt"
compile(){ mkdir -p "$CLASSES"; find src/main/java ../../common/src/main/java -name '*.java' | sort > "$SOURCES"; javac --release 21 -d "$CLASSES" @"$SOURCES"; }
run(){ compile; exec java -cp "$CLASSES" "$MAIN_CLASS" "$@"; }
free_port(){ python3 - <<'PY'
import socket
with socket.socket() as s:s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PY
}
test_app(){
 compile; port="${PORT:-$(free_port)}"; token="etl-test-$$-$(date +%s)"; mkdir -p build/run-sh
 BENCH_RUN_TOKEN="$token" java -cp "$CLASSES" "$MAIN_CLASS" "$port" >build/run-sh/app-test.log 2>&1 & pid=$!
 trap 'kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true' EXIT INT TERM
 python3 - "$port" "$pid" "$token" <<'PY'
import json,sys,time,urllib.request
port,pid,token=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3];base=f'http://127.0.0.1:{port}'
def get(path):
 with urllib.request.urlopen(base+path,timeout=10) as r:return json.load(r)
def post(path):
 request=urllib.request.Request(base+path,data=b'{}',method='POST',headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(request,timeout=10) as r:return json.load(r)
for _ in range(300):
 try:
  identity=get('/runtime')
  if identity['pid']==pid and identity['run_token']==token:break
 except Exception:time.sleep(.1)
else:raise SystemExit('owned app did not become ready')
job=post('/api/v1/etl/run')['job_id']
status=get(f'/api/v1/etl/{job}/status')
if status['status']!='completed':
 for field in ['records_processed','records_per_second','io_throughput_mb_s','elapsed_ms','output_sha256','reference_sha256','digest_match']:
  assert status[field] is None, (field, status)
deadline=time.time()+120
while True:
 status=get(f'/api/v1/etl/{job}/status')
 if status['status'] in ('completed','failed'):break
 if time.time()>deadline:raise SystemExit('job did not finish')
 time.sleep(0.2)
assert status['status']=='completed', status
assert status['records_processed']==50000 and status['digest_match'] is True, status
assert status['output_sha256']==status['reference_sha256'] and len(status['output_sha256'])==64, status
assert status['records_per_second']>0 and status['elapsed_ms']>0 and status['failure'] is None, status
listed=[entry for entry in get('/api/v1/etl/jobs')['jobs'] if entry['job_id']==job]
assert listed and listed[0]['digest_match'] is True, listed
print('etl job output matched its in-memory reference: '+status['output_sha256'][:16])
PY
 kill "$pid" 2>/dev/null||true; wait "$pid" 2>/dev/null||true; trap - EXIT INT TERM
}
case "$COMMAND" in build)compile;;test)test_app;;run)run "$@";;clean)rm -rf build;;help|-h|--help)echo 'Usage: ./run.sh {build|test|run|clean}';;*)exit 2;;esac
