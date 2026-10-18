#!/usr/bin/env bash
# Phase 3 (ARCHITECTURE H8 / D6): build the native part of the full-speed tool set
# into android/app/src/main/jniLibs/<abi>/ as lib*.so files, so Android extracts
# them into nativeLibraryDir and they may be executed / dlopen'ed (Play rule:
# only files that came inside the APK are code; everything else is data).
#
# Ship set per ABI:
#   libnode.so          Termux/TUR nodejs-24 (opt/nodejs-24/bin/node, a PIE)
#   libcxx.so           Termux libc++ 30 (libc++_shared.so)
#   libssl3.so          Termux openssl 1:3.6.5 (libssl.so.3)
#   libcrypto3.so       Termux openssl 1:3.6.5 (libcrypto.so.3)
#   libcares.so         Termux c-ares 1.34.8 (libcares.so; name already fits)
#   libpty.so           code-server: node-pty build/Release/pty.node
#   libwatcher.so       code-server: @parcel/watcher build/Release/watcher.node
#   libspdlog.so        code-server: @vscode/spdlog build/Release/spdlog.node
#   libsqlite3node.so   code-server: @vscode/sqlite3 build/Release/vscode-sqlite3.node
#   libwatchdog.so      code-server: @vscode/native-watchdog build/Release/watchdog.node
#   libkerberos.so      code-server: kerberos build/Release/kerberos.node
#                                                   (node_modules/kerberos)
#   libargon2.so        code-server: argon2 build/Release/argon2.node
#                                                   (code-server/node_modules/argon2)
# zlib is NOT shipped: DT_NEEDED libz.so.1 -> libz.so, which is an NDK public
# system library (Android's linker finds it in /system/lib64).
#
# What this script does per file: unpack the pinned deb, copy the member out,
# llvm-strip it, rename it to its lib*.so name, rewrite the DT_NEEDED/DT_SONAME
# strings that point at the renamed files (android/vscode/patch-neededs.py; the
# new names are all shorter, so the bytes go back in place), check that every
# LOAD segment still has p_align 0x4000 (16 KB pages - Google Play requirement,
# and the emulator is a 16 KB-page image), and print size + sha256.
#
# Runs in WSL (see android/win86/fetch-box64.sh for the layout rules: NDK r27c
# lives there, never run as root, work on the WSL filesystem, copy results back).
# Binaries are gitignored (AGENTS.md rule: never commit binaries).
#
# usage: ./fetch-native.sh [--clean] [--keep-work]
#   --clean     remove the previously generated lib*.so files first
#   --keep-work keep the unpacked deb trees under $WORK (default: keep too; the
#               only throwaway is the intermediate .node copies)
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-$HOME/android-ndk-r27c}"
HOST_TAG=linux-x86_64
STRIP="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
READELF="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf"
for t in "$STRIP" "$READELF"; do
  [ -x "$t" ] || { echo "missing tool: $t (set ANDROID_NDK_HOME)" >&2; exit 1; }
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
JNI="$ROOT/../app/src/main/jniLibs"
WORK="${VSCODE_NATIVE_WORK:-$HOME/vscode-native-work}"
DL="$WORK/debs"
SRC="$WORK/src"
PATCHER="$ROOT/patch-neededs.py"

CLEAN=0
for a in "$@"; do [ "$a" = "--clean" ] && CLEAN=1; done

# ---------------------------------------------------------------- pins ------
# TUR (Termux User Repository) builds for Android/bionic. SHA-256 taken from the
# manager's 2026-10-18 pin check and re-verified at download time here.
TUR=https://tur.kcubeterm.com/pool/tur
TERMUX=https://packages.termux.dev/apt/termux-main

declare -A NODE_URL NODE_SHA CS_URL CS_SHA CARES_URL CARES_SHA LCPP_URL LCPP_SHA SSL_URL SSL_SHA
NODE_URL[x86_64]="$TUR/nodejs-24/nodejs-24_24.18.0_x86_64.deb"
NODE_SHA[x86_64]=79f92e082dda484cbd7842e4e415c95869806cbd8bca01e17ef6764fe7c5d16e
NODE_URL[arm64-v8a]="$TUR/nodejs-24/nodejs-24_24.18.0_aarch64.deb"
NODE_SHA[arm64-v8a]=f412bd475111cb1741f02487d95b392545349362db4b6bbfea57d9f527d17832
CS_URL[x86_64]="$TUR/code-server/code-server_4.141.0_x86_64.deb"
CS_SHA[x86_64]=90cbdcac6f0c45980d6b964a3d3f4ff9079353e4064389d3343ba4afc27b28c0
CS_URL[arm64-v8a]="$TUR/code-server/code-server_4.141.0_aarch64.deb"
CS_SHA[arm64-v8a]=eabd4122740486b2d1263f53be8d3770352bb85a6ff95f1aa0baf5303309d0c5
CARES_URL[x86_64]="$TERMUX/pool/main/c/c-ares/c-ares_1.34.8_x86_64.deb"
CARES_SHA[x86_64]=c5d6194d69a04089040ca50dad96ed7df8773bd3c3d7c4be7d1dd5661ceae6b4
CARES_URL[arm64-v8a]="$TERMUX/pool/main/c/c-ares/c-ares_1.34.8_aarch64.deb"
CARES_SHA[arm64-v8a]=7681fc23e822d7988ba8b2adf3468f93ae68f724dda365cff1385096a9fa87e6
LCPP_URL[x86_64]="$TERMUX/pool/main/libc/libc++/libc++_30_x86_64.deb"
LCPP_SHA[x86_64]=c9bcd9a8067a19c9c0e4893a72d314a2e37d6b67ce8dd05c67b9bbacad044b4f
LCPP_URL[arm64-v8a]="$TERMUX/pool/main/libc/libc++/libc++_30_aarch64.deb"
LCPP_SHA[arm64-v8a]=53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca
SSL_URL[x86_64]="$TERMUX/pool/main/o/openssl/openssl_1:3.6.5_x86_64.deb"
SSL_SHA[x86_64]=540f144592d23d35672976e175ede1b5d8cf464f153f646df5d4e6dcd8491a15
SSL_URL[arm64-v8a]="$TERMUX/pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb"
SSL_SHA[arm64-v8a]=a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b

fetch() { # url sha outname ; sets $DEB_PATH
  local url=$1 sha=$2 name=$3
  local f="$DL/$name"
  mkdir -p "$DL"
  if [ ! -s "$f" ]; then
    echo "DL   $name"
    curl -fsSL -o "$f.part" "$url"
    mv "$f.part" "$f"
  fi
  local got; got=$(sha256sum "$f" | cut -d' ' -f1)
  [ "$got" = "$sha" ] || { echo "SHA FAIL $name got=$got want=$sha" >&2; exit 1; }
  echo "OK   $name $(stat -c%s "$f")"
  DEB_PATH="$f"
}

# extract <deb> <outdir> <strip-components> <member-glob>...
# Only the named members are taken out of data.tar.xz; the unpacked deb is not
# kept (the debs are 24-117 MB and unpack to 0.5 GB each).
extract() {
  local deb=$1 out=$2 strip=$3; shift 3
  [ -f "$out/.done" ] && return 0
  rm -rf "$out"; mkdir -p "$out" "$WORK/tmp"
  ( cd "$WORK/tmp" && ar x "$deb" )
  tar -xf "$WORK/tmp/data.tar.xz" -C "$out" --strip-components="$strip" --wildcards "$@"
  rm -rf "$WORK/tmp"
  : > "$out/.done"
  echo "X    $(basename "$deb") -> $out"
}

# collect <src-file> <dest-name> <pairs...>   pairs are old=new for the patcher
COLLECTED=""
collect() {
  local src=$1 dest=$2 abi=$3; shift 3
  [ -f "$src" ] || { echo "MISSING $src" >&2; exit 1; }
  local out="$JNI/$abi/$dest"
  mkdir -p "$JNI/$abi"
  cp "$src" "$out"
  chmod 644 "$out"
  "$STRIP" --strip-unneeded "$out"
  if [ "$#" -gt 0 ]; then python3 "$PATCHER" "$out" "$@"; fi
  COLLECTED="$COLLECTED $out"
}

for ABI in x86_64 arm64-v8a; do
  echo "=== $ABI"
  fetch "${NODE_URL[$ABI]}" "${NODE_SHA[$ABI]}" "$(basename "${NODE_URL[$ABI]}")";  NODE_DEB=$DEB_PATH
  fetch "${CS_URL[$ABI]}"   "${CS_SHA[$ABI]}"   "$(basename "${CS_URL[$ABI]}")";    CS_DEB=$DEB_PATH
  fetch "${CARES_URL[$ABI]}" "${CARES_SHA[$ABI]}" "$(basename "${CARES_URL[$ABI]}")"; CARES_DEB=$DEB_PATH
  fetch "${LCPP_URL[$ABI]}"   "${LCPP_SHA[$ABI]}"   "$(basename "${LCPP_URL[$ABI]}")";   LCPP_DEB=$DEB_PATH
  fetch "${SSL_URL[$ABI]}"     "${SSL_SHA[$ABI]}"     "$(basename "${SSL_URL[$ABI]}")";     SSL_DEB=$DEB_PATH

  extract "$NODE_DEB"  "$SRC/$ABI/node"  6 './data/data/com.termux/files/usr/opt/nodejs-24/bin/node'
  extract "$CS_DEB"    "$SRC/$ABI/cs"    8 \
      './data/data/com.termux/files/usr/lib/code-server/lib/vscode/node_modules/*/build/Release/*.node' \
      './data/data/com.termux/files/usr/lib/code-server/node_modules/argon2/build/Release/*.node'
  extract "$CARES_DEB" "$SRC/$ABI/cares" 6 './data/data/com.termux/files/usr/lib/libcares.so'
  extract "$LCPP_DEB"  "$SRC/$ABI/libcpp" 6 './data/data/com.termux/files/usr/lib/libc++_shared.so'
  extract "$SSL_DEB"   "$SRC/$ABI/openssl" 6 \
      './data/data/com.termux/files/usr/lib/libssl.so.3' \
      './data/data/com.termux/files/usr/lib/libcrypto.so.3'

  U=opt/nodejs-24/bin
  N="$SRC/$ABI/node/$U/node"
  L="$SRC/$ABI/libcpp/lib/libc++_shared.so"
  SSL="$SRC/$ABI/openssl/lib/libssl.so.3"
  CRY="$SRC/$ABI/openssl/lib/libcrypto.so.3"
  CAR="$SRC/$ABI/cares/lib/libcares.so"
  V="$SRC/$ABI/cs/lib/vscode/node_modules"
  CSM="$SRC/$ABI/cs/node_modules"

  [ "$CLEAN" = 1 ] && rm -f "$JNI/$ABI"/libnode.so "$JNI/$ABI"/libcxx.so "$JNI/$ABI"/libssl3.so \
      "$JNI/$ABI"/libcrypto3.so "$JNI/$ABI"/libcares.so "$JNI/$ABI"/libpty.so \
      "$JNI/$ABI"/libwatcher.so "$JNI/$ABI"/libspdlog.so "$JNI/$ABI"/libsqlite3node.so \
      "$JNI/$ABI"/libwatchdog.so "$JNI/$ABI"/libkerberos.so "$JNI/$ABI"/libargon2.so

  collect "$N"   libnode.so      "$ABI" libz.so.1=libz.so libcrypto.so.3=libcrypto3.so libssl.so.3=libssl3.so libc++_shared.so=libcxx.so
  collect "$L"   libcxx.so       "$ABI" libc++_shared.so=libcxx.so
  collect "$SSL" libssl3.so      "$ABI" libcrypto.so.3=libcrypto3.so libssl.so.3=libssl3.so
  collect "$CRY" libcrypto3.so   "$ABI" libcrypto.so.3=libcrypto3.so
  collect "$CAR" libcares.so     "$ABI"
  collect "$V/node-pty/build/Release/pty.node"                      libpty.so         "$ABI" libc++_shared.so=libcxx.so
  collect "$V/@parcel/watcher/build/Release/watcher.node"           libwatcher.so     "$ABI" libc++_shared.so=libcxx.so
  collect "$V/@vscode/spdlog/build/Release/spdlog.node"             libspdlog.so      "$ABI" libc++_shared.so=libcxx.so
  collect "$V/@vscode/sqlite3/build/Release/vscode-sqlite3.node"    libsqlite3node.so "$ABI" libc++_shared.so=libcxx.so
  collect "$V/@vscode/native-watchdog/build/Release/watchdog.node"  libwatchdog.so    "$ABI"
  collect "$V/kerberos/build/Release/kerberos.node"                 libkerberos.so    "$ABI" libc++_shared.so=libcxx.so
  collect "$CSM/argon2/build/Release/argon2.node"                   libargon2.so      "$ABI" libc++_shared.so=libcxx.so
done

# ------------------------------------------------------- verification ------
echo
echo "=== alignment (every LOAD segment must be 0x4000 = 16 KB pages) ==="
BAD=0
for f in $COLLECTED; do
  aligns=$("$READELF" -lW "$f" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ',')
  case "$aligns" in
    *0x4000*) oklabel=ok ;;
    *) oklabel=BAD; BAD=1 ;;
  esac
  printf '%-58s %s %s\n' "${f#$ROOT/../app/src/main/jniLibs/}" "$oklabel" "$aligns"
done

echo
echo "=== output files ==="
TOTAL=0
for abi in x86_64 arm64-v8a; do
  SUM=0
  printf '%-9s %-22s %12s %s\n' ABI FILE BYTES SHA256
  for f in "$JNI/$abi"/libnode.so "$JNI/$abi"/libcxx.so "$JNI/$abi"/libssl3.so \
           "$JNI/$abi"/libcrypto3.so "$JNI/$abi"/libcares.so "$JNI/$abi"/libpty.so \
           "$JNI/$abi"/libwatcher.so "$JNI/$abi"/libspdlog.so "$JNI/$abi"/libsqlite3node.so \
           "$JNI/$abi"/libwatchdog.so "$JNI/$abi"/libkerberos.so "$JNI/$abi"/libargon2.so; do
    [ -f "$f" ] || { echo "MISSING OUTPUT $f" >&2; exit 1; }
    s=$(stat -c%s "$f"); SUM=$((SUM+s)); TOTAL=$((TOTAL+s))
    printf '%-9s %-22s %12s %s\n' "$abi" "$(basename "$f")" "$s" "$(sha256sum "$f" | cut -d' ' -f1)"
  done
  echo "$abi jniLibs total: $SUM bytes"
done
echo "both ABIs total: $TOTAL bytes"
[ "$BAD" = 0 ] || { echo "ALIGNMENT CHECK FAILED" >&2; exit 1; }
echo "OK"
