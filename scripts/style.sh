#!/usr/bin/env bash
# google-java-format --dry-run on this app's Java sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=tools.sh
source "${ROOT}/scripts/tools.sh"
cd "$ROOT"
with_jdk27
need_gjf

jar="${TOOLS}/google-java-format-${GJF_VERSION}-all-deps.jar"
echo "google-java-format ${GJF_VERSION} checking Main.java PerfTest.java" >&2
java -jar "$jar" --version >&2
java -jar "$jar" --dry-run --set-exit-if-changed Main.java PerfTest.java
echo "google-java-format: Main.java PerfTest.java match Google Java Format" >&2
