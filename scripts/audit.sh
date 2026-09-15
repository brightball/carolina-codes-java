#!/usr/bin/env bash
# osv-scanner against Maven coordinates taken from each shipped lib/*.jar manifest.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=tools.sh
source "${ROOT}/scripts/tools.sh"
cd "$ROOT"
need_osv

shopt -s nullglob
jars=(lib/*.jar)
if [ "${#jars[@]}" -eq 0 ]; then
  echo "no jars under lib/ to scan" >&2
  exit 1
fi

components=""
sep=""
for jar in "${jars[@]}"; do
  mf="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')"
  ver="$(printf '%s\n' "$mf" | awk -F': ' '/^Implementation-Version:/ {print $2; exit}')"
  group="$(printf '%s\n' "$mf" | awk -F': ' '/^Implementation-Vendor-Id:/ {print $2; exit}')"
  if [ -z "$ver" ] || [ -z "$group" ]; then
    echo "cannot read Implementation-Version / Implementation-Vendor-Id from ${jar}" >&2
    exit 1
  fi
  base="$(basename "$jar" .jar)"
  name="${base%-"${ver}"}"
  purl="pkg:maven/${group}/${name}@${ver}"
  echo "osv-scanner inspecting $(basename "$jar") as ${group}:${name}:${ver}" >&2
  components="${components}${sep}{\"type\":\"library\",\"group\":\"${group}\",\"name\":\"${name}\",\"version\":\"${ver}\",\"purl\":\"${purl}\",\"bom-ref\":\"${purl}\"}"
  sep=","
done

bom="${TOOLS}/lib.cdx.json"
printf '%s\n' "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"version\":1,\"components\":[${components}]}" >"$bom"
echo "osv-scanner ${OSV_VERSION} scanning CycloneDX generated from lib/*.jar" >&2
exec "${TOOLS}/osv-scanner" scan source -L "$bom"
