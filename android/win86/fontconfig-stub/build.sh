#!/usr/bin/env bash
# unified-desktop: build the functional no-op libfontconfig.so.1 for the wine rootfs
# (the B-022 fix). Runs in WSL (user asus) with the NDK r27c clang; the target is
# x86_64-linux-gnu (a glibc guest library, like everything in the rootfs), NOT Android.
# stub.c carries the full rationale.
#
# Output: $ROOT/libfontconfig.so.1 by default (FONT_STUB_OUT overrides). Deploy into the
# phone's rootfs at files/win86/rootfs/usr/lib/x86_64-linux-gnu/ , keeping the real lib as
# libfontconfig.so.1.12.0 + a .hidden symlink for rollback (docs/PHASE2_RESULT.md round
# 3b). Wiring it into the in-app rootfs fetch is pending; see that document.
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-$HOME/android-ndk-r27c}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$BIN/clang"
NM="$BIN/llvm-nm"
READELF="$BIN/llvm-readelf"

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="${FONT_STUB_OUT:-$ROOT/libfontconfig.so.1}"

"$CLANG" --target=x86_64-linux-gnu -shared -nostdlib -fPIC -O2 \
    -Wl,-soname,libfontconfig.so.1 -o "$OUT" "$ROOT/stub.c"

# The whole point of the stub: no dependencies and no runtime relocations, so box64's
# emulated dlopen can never fail on a missing target (see stub.c header).
if "$READELF" -d "$OUT" | grep -E "NEEDED" >/dev/null; then
    echo "ERROR: $OUT has NEEDED entries" >&2
    exit 1
fi
if "$READELF" -r "$OUT" | grep -E "R_X86_64_" >/dev/null; then
    echo "ERROR: $OUT has runtime relocations" >&2
    exit 1
fi
# wine 11.19 dlsyms 26 Fc* entry points (win32u/freetype.c init_fontconfig); every one
# must be exported or the NULL-call crash comes back.
COUNT="$("$NM" -D --defined-only "$OUT" | grep -c ' T Fc' || true)"
if [ "$COUNT" -lt 26 ]; then
    echo "ERROR: only $COUNT exported Fc* symbols, expected at least 26" >&2
    exit 1
fi
echo "Fc* exports in dynsym: $COUNT"

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
echo "built $OUT ($(stat -c %s "$OUT") bytes)"
echo "sha256 $SHA"
echo "verified artifact 2026-10-17 (NDK r27c): 5640 bytes, sha256 0e967100613908e3b5769cf2c0e089f6d7f09c4f4b0907b3ae28415c393d3619"
