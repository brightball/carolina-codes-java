#!/usr/bin/env bash
# google-java-format --dry-run on this app's Java sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=tools.sh
source "${ROOT}/scripts/tools.sh"
cd "$ROOT"
with_jdk27
need_gjf

mapfile -t sources < <(app_java_sources)
if [ "${#sources[@]}" -eq 0 ]; then
  echo "no application Java sources to format-check" >&2
  exit 1
fi
jar="${TOOLS}/google-java-format-${GJF_VERSION}-all-deps.jar"
echo "google-java-format ${GJF_VERSION} --dry-run ${sources[*]}" >&2
java -jar "$jar" --version >&2
java -jar "$jar" --dry-run --set-exit-if-changed "${sources[@]}"
echo "google-java-format: ${sources[*]} match Google Java Format" >&2
