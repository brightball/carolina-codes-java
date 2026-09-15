#!/usr/bin/env bash
# Compile shipped Main + PerfTest on JDK 27 and run PerfTest (fake catalog, no Postgres).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=tools.sh
source "${ROOT}/scripts/tools.sh"
cd "$ROOT"
with_jdk27

jar="$(echo lib/postgresql-*.jar)"
if [ ! -f "$jar" ]; then
  echo "missing JDBC jar under lib/postgresql-*.jar" >&2
  exit 1
fi
echo "compiling Main.java PerfTest.java with ${jar} on JDK 27" >&2
javac -cp "$jar" Main.java PerfTest.java
echo "running PerfTest against shipped Main.dispatch" >&2
exec java -cp ".:${jar}" PerfTest
