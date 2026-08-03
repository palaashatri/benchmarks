#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MAIN="com.palaashatri.bench.compat.CompatibilityApp"
CLASSES="build/classes"
SOURCES="build/sources.txt"

compile() {
  mkdir -p "$CLASSES"
  find src/main/java -name '*.java' -print | sort > "$SOURCES"
  version="$(javac -version 2>&1)"
  case "$version" in
    javac\ 1.8*) javac -source 8 -target 8 -Xlint:-options -d "$CLASSES" @"$SOURCES" ;;
    *) javac --release 8 -d "$CLASSES" @"$SOURCES" ;;
  esac
}

build_artifact() {
  output="${1:-build/compatibility-app-java8.jar}"
  case "$output" in
    /*) ;;
    *) output="$(pwd)/$output" ;;
  esac
  compile
  mkdir -p "$(dirname "$output")"
  rm -f "$output"
  (cd "$CLASSES" && jar cf "$output" .)
  printf '%s\n' "$output"
}

run() {
  if [ -n "${BENCH_APP_ARTIFACT:-}" ]; then
    [ -f "$BENCH_APP_ARTIFACT" ] || {
      echo "BENCH_APP_ARTIFACT does not exist: $BENCH_APP_ARTIFACT" >&2
      exit 1
    }
    exec java -cp "$BENCH_APP_ARTIFACT" "$MAIN" "$@"
  fi
  compile
  exec java -cp "$CLASSES" "$MAIN" "$@"
}

free_port() {
  python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
}

test_app() {
  artifact="$(build_artifact build/compatibility-app-java8.jar)"
  port="${PORT:-$(free_port)}"
  token="compat-test-$$-$(date +%s)"
  mkdir -p build
  BENCH_RUN_TOKEN="$token" BENCH_APP_ARTIFACT="$artifact" \
    java -cp "$artifact" "$MAIN" "$port" >build/app-test.log 2>&1 &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT INT TERM
  python3 - "$port" "$token" "$artifact" <<'PY'
import hashlib
import json
import sys
import time
import urllib.request

port, token, artifact = int(sys.argv[1]), sys.argv[2], sys.argv[3]
base = f'http://127.0.0.1:{port}'
for _ in range(150):
    try:
        with urllib.request.urlopen(base + '/runtime', timeout=.3) as response:
            runtime = json.load(response)
        if runtime['run_token'] == token and runtime['bytecode_target'] == 8:
            break
    except Exception:
        time.sleep(.1)
else:
    raise SystemExit('owned compatibility app did not become ready')

def work():
    request = urllib.request.Request(
        base + '/work',
        data=b'{"seed":424242,"size":4096}',
        method='POST',
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request) as response:
        return json.load(response)

first, second = work(), work()
assert first['checksum'] == second['checksum']
assert first['bytecode_target'] == 8
with open(artifact, 'rb') as handle:
    digest = hashlib.sha256(handle.read()).hexdigest()
assert len(digest) == 64
print(f'Java 8 artifact deterministic-work check passed: {digest}')
PY
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  trap - EXIT INT TERM
}

case "$COMMAND" in
  build) compile ;;
  artifact) build_artifact "${1:-build/compatibility-app-java8.jar}" ;;
  test) test_app ;;
  run) run "$@" ;;
  clean) rm -rf build ;;
  help|-h|--help) echo 'Usage: ./run.sh {build|artifact [path]|test|run|clean}' ;;
  *) echo "Unknown command: $COMMAND" >&2; exit 2 ;;
esac
