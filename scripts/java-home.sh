#!/usr/bin/env bash
# Print JAVA_HOME for JDK 27. PATH may be a 26 shim; never trust `java` on PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

is_jdk27() {
  local home="$1"
  [ -n "$home" ] && [ -x "$home/bin/java" ] || return 1
  local spec
  spec="$("$home/bin/java" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/.*java.specification.version = //p' | tr -d '[:space:]')"
  [ "$spec" = "27" ]
}

candidates=()
[ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
candidates+=(
  "${HOME}/.local/jdk-27"
  "${HOME}/.local/share/mise/installs/java/27.0.0"
  "${HOME}/.local/share/mise/installs/java/27"
  "${ROOT}/.tools/jdk-27"
  /opt/java
)

for home in "${candidates[@]}"; do
  if is_jdk27 "$home"; then
    printf '%s\n' "$home"
    exit 0
  fi
done

dest="${ROOT}/.tools/jdk-27"
mkdir -p "${ROOT}/.tools"
arch="$(uname -m)"
case "$arch" in
  x86_64 | amd64)
    jarch=x64
    sha=95fc37eb3a18a27a26d5904c2d89d52bace8dafa9a078ca27f4747fbc4bf070b
    ;;
  aarch64 | arm64)
    jarch=aarch64
    sha=da4e9dde1fff90204739e969187bab4751bd59a2a1c479672e1a1810f7dd23ea
    ;;
  *)
    echo "unsupported arch for JDK 27: $arch" >&2
    exit 1
    ;;
esac

tarball="${ROOT}/.tools/openjdk-27.tar.gz"
url="https://download.java.net/java/GA/jdk27/55ce5470a6294008af0057ff4626d0e5/35/GPL/openjdk-27_linux-${jarch}_bin.tar.gz"
echo "installing JDK 27 to ${dest}" >&2
curl -fsSL -o "${tarball}.part" "$url"
echo "${sha}  ${tarball}.part" | sha256sum -c -
mv "${tarball}.part" "$tarball"
rm -rf "$dest"
mkdir -p "$dest"
tar -xzf "$tarball" -C "$dest" --strip-components=1
rm -f "$tarball"

if ! is_jdk27 "$dest"; then
  echo "downloaded JDK is not specification version 27" >&2
  exit 1
fi
printf '%s\n' "$dest"
