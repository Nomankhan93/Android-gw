#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_output_dir="$(mktemp -d)"
trap 'rm -rf "$test_output_dir"' EXIT
javac -d "$test_output_dir" app/src/main/java/com/bulktext/gateway/Policy.java tests/PolicyTest.java
java -cp "$test_output_dir" PolicyTest
