#!/usr/bin/env bash
set -euo pipefail

REV=40282a7bf4500948cfd598fc67e6e63114b26dd9
URL="https://github.com/Black00Z/Blacks-BlackBox/archive/${REV}.tar.gz"
SHA256="aa17025db768bf23f52d19050949bd6baf5a529e6186060d89315515cdb8ea61"
DOBBY_REV=5dfc8546954ce3b3198132ab13fddb89ee92cdd7
DOBBY_URL="https://github.com/jmpews/Dobby/archive/${DOBBY_REV}.tar.gz"
DOBBY_SHA256="01a417233c40929aa21098c8a9724c00f6f05b5cde3f11abcac84b8e00053748"
ROOT="$(cd "$(dirname "$0")" && pwd)"
VENDOR="$ROOT/vendor"
mkdir -p "$VENDOR"

fetch() {
  local url="$1" out="$2" expected="$3"
  curl --fail --location --retry 3 --output "$out" "$url"
  echo "$expected  $out" | sha256sum --check --status
}

archive="$VENDOR/blacks-${REV}.tar.gz"
dobby_archive="$VENDOR/dobby-${DOBBY_REV}.tar.gz"
[[ -f "$archive" ]] || curl --fail --location --retry 3 --output "$archive" "$URL"
echo "$SHA256  $archive" | sha256sum --check --status
[[ -f "$dobby_archive" ]] || curl --fail --location --retry 3 --output "$dobby_archive" "$DOBBY_URL"
echo "$DOBBY_SHA256  $dobby_archive" | sha256sum --check --status

rm -rf "$VENDOR/source" "$VENDOR/dobby"
mkdir "$VENDOR/source" "$VENDOR/dobby"
tar -xzf "$archive" --strip-components=1 -C "$VENDOR/source"
tar -xzf "$dobby_archive" --strip-components=1 -C "$VENDOR/dobby"

# Never import upstream packaged artifacts or signing/media material.
find "$VENDOR/source" -type f \( -name '*.a' -o -name '*.so' -o -name '*.jar' -o -name '*.aar' -o -name '*.apk' -o -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \) -delete
rm -rf "$VENDOR/source/app" "$VENDOR/source/blackbox-gallery-stub"
# Gradle/CMake read the ignored vendor tree; no generated sources enter tracked src/.
printf '%s\n' "Fetched $REV and Dobby $DOBBY_REV; prebuilt artifacts excluded." > "$VENDOR/MANIFEST"
