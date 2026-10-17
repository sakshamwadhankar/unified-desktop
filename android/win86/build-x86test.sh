#!/usr/bin/env bash
# Builds the T2 test guests (pure-syscall x86-64 ELF files, no interpreter).
# Runs in WSL (default Ubuntu user, NDK r27c at ~/android-ndk-r27c).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
B="$HOME/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$ROOT"

"$B/x86_64-linux-android29-clang" -c -fPIC -o vendor/x86test.o x86test.S
"$B/ld.lld" -static -e _start -o vendor/x86test-static vendor/x86test.o
"$B/ld.lld" -pie    -e _start -o vendor/x86test-pie    vendor/x86test.o
file vendor/x86test-static vendor/x86test-pie
sha256sum vendor/x86test-static vendor/x86test-pie
