#!/usr/bin/env sh
set -eu
COMMAND="${1:-help}"
if [ "$#" -gt 0 ]; then shift; fi
MAIN_CLASS="com.palaashatri.bench.b01.app.BenchmarkApp"
JAVA_RELEASE="21"
ROLE="app"
DEFAULT_PORT="18001"
SMOKE_GETS='/accounts/1001/balance|/accounts/1001/transactions'
SMOKE_POSTS='/transfers::{"from":"1001","to":"1002","amount_cents":125}'
CLASSES_DIR="build/run-sh/classes"
SOURCES_FILE="build/run-sh/sources.txt"

DEPS_DIR="build/run-sh/deps"

download_deps() {
  mkdir -p "$DEPS_DIR"
  _mvn_base="https://repo1.maven.org/maven2"
  _jars="
com/h2database/h2/2.2.224/h2-2.2.224.jar
com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0.jar
org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar
org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar
io/micrometer/micrometer-core/1.13.6/micrometer-core-1.13.6.jar
io/micrometer/micrometer-registry-prometheus/1.13.6/micrometer-registry-prometheus-1.13.6.jar
io/prometheus/simpleclient/0.16.0/simpleclient-0.16.0.jar
io/prometheus/simpleclient_common/0.16.0/simpleclient_common-0.16.0.jar
io/prometheus/simpleclient_tracer_otel/0.16.0/simpleclient_tracer_otel-0.16.0.jar
io/prometheus/simpleclient_tracer_otel_agent/0.16.0/simpleclient_tracer_otel_agent-0.16.0.jar
"
  for _path in $_jars; do
    _path="$(echo "$_path" | tr -d ' ')"
    [ -z "$_path" ] && continue
    _jar="$(basename "$_path")"
    _dest="$DEPS_DIR/$_jar"
    if [ ! -f "$_dest" ]; then
      echo "Downloading $_jar ..."
      curl -fsSL "$_mvn_base/$_path" -o "$_dest"
    fi
  done
}

build_deps_cp() {
  _cp=""
  for _f in "$DEPS_DIR"/*.jar; do
    [ -f "$_f" ] || continue
    if [ -z "$_cp" ]; then _cp="$_f"; else _cp="$_cp:$_f"; fi
  done
  echo "$_cp"
}

compile_sources() {
  download_deps
  mkdir -p "$CLASSES_DIR"
  find src/main/java -name '*.java' | sort > "$SOURCES_FILE"
  if [ ! -s "$SOURCES_FILE" ]; then echo "No Java sources found under src/main/java" >&2; exit 1; fi
  DEPS_CP="$(build_deps_cp)"
  javac --release "$JAVA_RELEASE" -cp "$DEPS_CP" -d "$CLASSES_DIR" @"$SOURCES_FILE"
}

run_java() {
  compile_sources
  DEPS_CP="$(build_deps_cp)"
  exec java -cp "$CLASSES_DIR:$DEPS_CP" "$MAIN_CLASS" "$@"
}

wait_for_health() {
  url="$1"
  python3 - "$url" <<'PYWAIT'
import sys,time,urllib.request
url=sys.argv[1]; last=None
for _ in range(80):
    try:
        with urllib.request.urlopen(url, timeout=0.5) as response:
            if response.status == 200: raise SystemExit(0)
    except SystemExit: raise
    except Exception as exc: last=exc
    time.sleep(0.1)
print(f"Timed out waiting for {url}: {last}", file=sys.stderr); raise SystemExit(1)
PYWAIT
}

smoke_app() {
  compile_sources
  DEPS_CP="$(build_deps_cp)"
  port="${PORT:-$DEFAULT_PORT}"
  mkdir -p build/run-sh
  java -cp "$CLASSES_DIR:$DEPS_CP" "$MAIN_CLASS" "$port" > build/run-sh/app-smoke.log 2>&1 &
  pid="$!"
  trap 'kill "$pid" 2>/dev/null || true' EXIT INT TERM
  wait_for_health "http://127.0.0.1:$port/health"
  python3 - "$port" "$SMOKE_GETS" "$SMOKE_POSTS" <<'PYAPP'
import sys, urllib.request
port, gets, posts = sys.argv[1], sys.argv[2], sys.argv[3]
base=f"http://127.0.0.1:{port}"
for path in ["/health", "/metrics"] + [p for p in gets.split('|') if p]:
    with urllib.request.urlopen(base + path, timeout=2) as response:
        if response.status != 200: raise SystemExit(f"{path} returned {response.status}")
for item in [p for p in posts.split('|') if p]:
    path, body = item.split('::', 1)
    req = urllib.request.Request(base + path, data=body.encode(), method='POST', headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=2) as response:
        if response.status != 200: raise SystemExit(f"{path} returned {response.status}")
print(f"app smoke passed on port {port}")
PYAPP
  kill "$pid" 2>/dev/null || true
  trap - EXIT INT TERM
}

smoke_harness() {
  compile_sources
  mkdir -p build/run-sh
  port="${PORT:-$DEFAULT_PORT}"
  if [ -z "${BASE_URL:-}" ] && [ -x ../app/run.sh ]; then
    (cd ../app && ./run.sh run "$port" > build/run-sh/harness-owned-app.log 2>&1 & echo $! > ../harness/build/run-sh/app.pid)
    pid="$(cat build/run-sh/app.pid)"
    trap 'kill "$pid" 2>/dev/null || true' EXIT INT TERM
    wait_for_health "http://127.0.0.1:$port/health"
    base_url="http://127.0.0.1:$port"
  else
    base_url="${BASE_URL:-http://127.0.0.1:$port}"
  fi
  requests="${REQUESTS:-4}"
  java -cp "$CLASSES_DIR" "$MAIN_CLASS" --base-url "$base_url" --requests "$requests" --out build/run-sh/results.json > build/run-sh/harness-smoke.log
  test -s build/run-sh/results.json
  cat build/run-sh/results.json
  if [ -n "${pid:-}" ]; then kill "$pid" 2>/dev/null || true; trap - EXIT INT TERM; fi
}

usage() { cat <<USAGE
Usage: ./run.sh <command> [args]
Commands:
  build        Compile local Java sources with javac --release $JAVA_RELEASE.
  test         Run app endpoint smoke or live app+harness smoke.
  run [args]   Compile and run $MAIN_CLASS with provided args.
  clean        Remove build/run-sh artifacts.
  help         Show this message.
USAGE
}
case "$COMMAND" in
  build) compile_sources ;;
  test) if [ "$ROLE" = "app" ]; then smoke_app; else smoke_harness; fi ;;
  run) run_java "$@" ;;
  clean) rm -rf build/run-sh ;;
  help|-h|--help) usage ;;
  *) echo "Unknown command: $COMMAND" >&2; usage >&2; exit 2 ;;
esac
