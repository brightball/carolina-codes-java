#!/usr/bin/env bash
# Fetch pinned CLIs into .tools/. Sourced by the check scripts.
# shellcheck shell=bash

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="${ROOT}/.tools"
mkdir -p "$TOOLS"

OSV_VERSION=2.6.0
OSV_SHA256=ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108
GITLEAKS_VERSION=8.30.1
GITLEAKS_TGZ_SHA256=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
GJF_VERSION=1.36.1
GJF_SHA256=25b400f003089d23cc5320cdaf1a16cabee19b8aa3434d0ff021b3d9f42154b4
PMD_VERSION=7.27.0
PMD_ZIP_SHA256=4ae396ffaf2b0d3ef0b73a10b2925e77066f73d57a4ce9078c60e7302bcddec9

fetch() {
  local url="$1" dest="$2" sha="$3"
  mkdir -p "$(dirname "$dest")"
  if [ -f "$dest" ]; then
    echo "${sha}  ${dest}" | sha256sum -c - >/dev/null
    return 0
  fi
  echo "fetch ${url}" >&2
  curl -fsSL -o "${dest}.part" "$url"
  echo "${sha}  ${dest}.part" | sha256sum -c -
  mv "${dest}.part" "$dest"
}

need_osv() {
  if [ ! -x "${TOOLS}/osv-scanner" ]; then
    fetch \
      "https://github.com/google/osv-scanner/releases/download/v${OSV_VERSION}/osv-scanner_linux_amd64" \
      "${TOOLS}/osv-scanner" \
      "$OSV_SHA256"
    chmod +x "${TOOLS}/osv-scanner"
  fi
}

need_gitleaks() {
  if [ -x "${TOOLS}/gitleaks" ]; then
    return 0
  fi
  local tgz="${TOOLS}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
  fetch \
    "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz" \
    "$tgz" \
    "$GITLEAKS_TGZ_SHA256"
  tar -xzf "$tgz" -C "$TOOLS" gitleaks
  chmod +x "${TOOLS}/gitleaks"
}

need_gjf() {
  if [ ! -f "${TOOLS}/google-java-format-${GJF_VERSION}-all-deps.jar" ]; then
    fetch \
      "https://github.com/google/google-java-format/releases/download/v${GJF_VERSION}/google-java-format-${GJF_VERSION}-all-deps.jar" \
      "${TOOLS}/google-java-format-${GJF_VERSION}-all-deps.jar" \
      "$GJF_SHA256"
  fi
}

need_pmd() {
  if [ -x "${TOOLS}/pmd-bin-${PMD_VERSION}/bin/pmd" ]; then
    return 0
  fi
  local zip="${TOOLS}/pmd-dist-${PMD_VERSION}-bin.zip"
  fetch \
    "https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip" \
    "$zip" \
    "$PMD_ZIP_SHA256"
  unzip -q -o "$zip" -d "$TOOLS"
}

with_jdk27() {
  JAVA_HOME="$(${ROOT}/scripts/java-home.sh)"
  export JAVA_HOME
  export PATH="${JAVA_HOME}/bin:${PATH}"
  echo "JAVA_HOME=${JAVA_HOME}" >&2
  java -version >&2
}

# When executed (make tools / Gitea prepare), fetch JDK 27 and the check CLIs.
# When sourced by a check script, only the functions above are defined.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -euo pipefail
  with_jdk27
  need_osv
  need_gitleaks
  need_gjf
  need_pmd
fi
