#!/usr/bin/env bash
set -euo pipefail

REV=40282a7bf4500948cfd598fc67e6e63114b26dd9
URL="https://github.com/Black00Z/Blacks-BlackBox/archive/${REV}.tar.gz"
SHA256="aa17025db768bf23f52d19050949bd6baf5a529e6186060d89315515cdb8ea61"
# Dobby pinned to the last commit BEFORE the "Refactor? Yes" change (a588a0d) that removed
# core/arch/Cpu.h while leaving dangling #includes. Both that refactor and the rolling
# master/"latest" (5dfc854) fail to compile. e9fe7fb is the self-consistent parent:
# code-patch-tool-posix.cc -> core/arch/Cpu.h resolves, and it builds for Android arm64.
DOBBY_REV=e9fe7fbecae47a2287e761080f8b1133cc22e8fa
DOBBY_URL="https://github.com/jmpews/Dobby/archive/${DOBBY_REV}.tar.gz"
DOBBY_SHA256="b5dddb530ae3b1abe8b61dd3a690eaf0ffe74900b0313c3d1bee0597691cf2d3"
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
# Windows fix: two inner classes in IPackageManagerProxy differ only by case
# (GetComponentEnabledSetting vs getComponentEnabledSetting); their .class files collide on
# a case-insensitive filesystem and D8 dexing fails. Both register via the @ProxyMethod
# string, not the class name, so renaming the lowercase one is safe.
pmproxy="$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IPackageManagerProxy.java"
[[ -f "$pmproxy" ]] && sed -i 's/public static class getComponentEnabledSetting extends MethodHook/public static class GetComponentEnabledSettingLegacy extends MethodHook/' "$pmproxy"

# Dobby ODR fix: logging.h defines Logger::Shared() in-header without `inline`, so every
# translation unit emits a strong definition and the shared lib fails to link with
# "duplicate symbol: Logger::Shared()". Make it inline.
logging_h="$VENDOR/dobby/external/logging/logging/logging.h"
[[ -f "$logging_h" ]] && sed -i 's/^Logger \*Logger::Shared() {/inline Logger *Logger::Shared() {/' "$logging_h"

# Gradle/CMake read the ignored vendor tree; no generated sources enter tracked src/.
printf '%s\n' "Fetched $REV and Dobby $DOBBY_REV; prebuilt artifacts excluded." > "$VENDOR/MANIFEST"
