#!/usr/bin/env bash
# PMD source-level SAST of this app's Java (security ruleset).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=tools.sh
source "${ROOT}/scripts/tools.sh"
cd "$ROOT"
with_jdk27
need_pmd

pmd="${TOOLS}/pmd-bin-${PMD_VERSION}/bin/pmd"
ver="$("$pmd" --version 2>&1 | sed -n 's/^PMD //p' | head -1)"
echo "PMD ${ver} analyzing Main.java PerfTest.java" >&2
"$pmd" check \
  -d Main.java \
  -d PerfTest.java \
  -R "${ROOT}/pmd-ruleset.xml" \
  -f text \
  --use-version java-27 \
  --no-progress
echo "PMD scanned Main.java PerfTest.java (security ruleset)" >&2
