#!/usr/bin/env sh
set -eu
BASE_URL="${BASE_URL:-http://localhost:8080}"
if [ -x ./gradlew ]; then ./gradlew run --args="--base-url $BASE_URL --requests 5 --out results/results.json"; else mvn -q exec:java -Dexec.args="--base-url $BASE_URL --requests 5 --out results/results.json"; fi
