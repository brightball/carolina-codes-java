#!/usr/bin/env bash
# PMD source-level SAST of this app's Java (security ruleset).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=tools.sh
source "${ROOT}/scripts/tools.sh"
cd "$ROOT"
with_jdk27
need_pmd

mapfile -t sources < <(app_java_sources)
if [ "${#sources[@]}" -eq 0 ]; then
  echo "no application Java sources to scan" >&2
  exit 1
fi
pmd="${TOOLS}/pmd-bin-${PMD_VERSION}/bin/pmd"
ver="$("$pmd" --version 2>&1 | sed -n 's/^PMD //p' | head -1)"
pmd_args=()
for f in "${sources[@]}"; do
  pmd_args+=(-d "$f")
done
echo "PMD ${ver} check ${pmd_args[*]} -R ${ROOT}/pmd-ruleset.xml --use-version java-27" >&2
"$pmd" check \
  "${pmd_args[@]}" \
  -R "${ROOT}/pmd-ruleset.xml" \
  -f text \
  --use-version java-27 \
  --no-progress
echo "PMD scanned ${sources[*]} (security ruleset)" >&2
