#!/usr/bin/env bash
# gitleaks detect on this git tree.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=tools.sh
source "${ROOT}/scripts/tools.sh"
cd "$ROOT"
need_gitleaks
echo "gitleaks $("${TOOLS}/gitleaks" version) detect --source ${ROOT}" >&2
exec "${TOOLS}/gitleaks" detect --source "$ROOT" --verbose --redact --no-banner
