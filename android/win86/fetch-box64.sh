#!/usr/bin/env bash
# Phase 2, T2: fetch box64 source (pinned) and build libbox64.so with an NDK.
# Runs in WSL (user `asus`, default Ubuntu distro): NDK r27c lives there with
# linux-x86_64 prebuilts. The Windows-side NDK cannot be used from WSL (its
# toolchains are windows-x86_64 only, checked 2026-10-16). Never run as root
# (LESSONS.md); output lands on the Windows tree via /mnt/c.
# Output: vendor/box64-build/box64 -> android/app/src/main/jniLibs/arm64-v8a/libbox64.so
set -euo pipefail

REV=v0.4.4
URL="https://github.com/ptitSeb/box64/archive/refs/tags/${REV}.tar.gz"
SHA256="99c6de4f509e46ab1de15df740d0e0ea338a7790efa3f67510dfbb975cc24029"

NDK="${ANDROID_NDK_HOME:-$HOME/android-ndk-r27c}"
SDK="${ANDROID_HOME:-/mnt/c/Users/Asus/AppData/Local/Android/Sdk}"
CMAKE="${CMAKE_EXE:-$HOME/.local/bin/cmake}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
VENDOR="$ROOT/vendor"
ARC="$VENDOR/box64-${REV}.tar.gz"
OUT="$ROOT/../app/src/main/jniLibs/arm64-v8a/libbox64.so"

# Compiling on the Windows-mounted tree (/mnt/c) is flaky (missing .d files) and
# slow — LESSONS.md. Extract and build on the WSL filesystem, copy the result back.
WORK="${BOX64_BUILD_DIR:-$HOME/box64-work}"
ARC2="$WORK/box64-${REV}.tar.gz"
SRC="$WORK/box64-${REV}"
BUILD="$WORK/box64-build"

mkdir -p "$VENDOR" "$WORK"

fetch() {
  local url="$1" out="$2" expected="$3"
  curl --fail --location --retry 3 --output "$out" "$url"
  echo "$expected  $out" | sha256sum --check --status
}

[[ -f "$ARC2" ]] || fetch "$URL" "$ARC2" "$SHA256"
echo "$SHA256  $ARC2" | sha256sum --check --status
# Keep a copy next to the script for the record (not committed; vendor/ is gitignored).
[[ -f "$ARC" ]] || cp -f "$ARC2" "$ARC"

if [[ -d "$SRC" ]]; then rm -rf "$SRC"; fi
mkdir -p "$SRC"
tar -xzf "$ARC2" --strip-components=1 -C "$SRC"

# unified-desktop patch (2026-10-16): guests are downloaded DATA in the app's private
# storage at targetSdk 36, and can never carry the owner-exec bit (Android/W^X refuses
# to execute app storage; box64 only opens/mmaps/reads and translates them). box64's
# only S_IXUSR check (src/core.c, the argv[0] FileExist(IS_EXECUTABLE)) would reject
# every legitimate guest. Gate it off for ANDROID builds; nothing else changes.
python3 - "$SRC/src/core.c" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = '    if(!FileExist(my_context->argv[0], IS_FILE|IS_EXECUTABLE)) {'
new = '''#if defined(ANDROID)
    // unified-desktop patch: skip S_IXUSR for guests under ANDROID W^X (see fetch script)
    if(!FileExist(my_context->argv[0], IS_FILE)) {
#else
    if(!FileExist(my_context->argv[0], IS_FILE|IS_EXECUTABLE)) {
#endif
'''
assert old in s, "patch anchor not found (core.c)"
open(p, "w").write(s.replace(old, new))
print("patched", p)
PYEOF

# unified-desktop glibc-compat shim (android/win86/box64-glibcshim/glibcshim.c):
# Bionic lacks glibc-internal symbols (__errno_location, __ctype_b_loc, ...) that
# glibc-linked guests resolve; box64's wrapped libc looks them up with dlsym on
# its own program object, so the shim is compiled INTO box64 (default
# visibility) and is found there. See glibcshim.c header for the full rationale.
cp -f "$ROOT/box64-glibcshim/glibcshim.c" "$SRC/glibcshim.c"
python3 - "$SRC/CMakeLists.txt" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = "${WRAPPEDS} ${WRAPPEDS32})"
new = '${WRAPPEDS} ${WRAPPEDS32} "${CMAKE_SOURCE_DIR}/glibcshim.c")'
assert anchor in s, "patch anchor not found (CMakeLists mainobj)"
s = s.replace(anchor, new)
# the shim uses liblog (assert diagnostics through __android_log_print)
anchor2 = "target_link_libraries(${BOX64} mainobj)"
assert anchor2 in s, "patch anchor not found (CMakeLists target_link_libraries)"
s = s.replace(anchor2, anchor2 + "\ntarget_link_options(${BOX64} PRIVATE -llog)")
open(p, "w").write(s)
print("patched", p)
PYEOF
# Verify the patch landed on disk before configure (a repeat extraction step in
# earlier runs silently reverted CMakeLists.txt; never build that way again).
grep -q "glibcshim" "$SRC/CMakeLists.txt" || { echo "ERROR: CMakeLists patch did not land"; exit 1; }
stat -c "patched state: %y %s %n" "$SRC/CMakeLists.txt"

# glibc >= 2.34 merged librt's POSIX-mqueue symbols into libc.so.6, and glibc
# 2.36 guests (libsystemd.so.0 relocations) look them up against libc. Upstream
# box64 only lists them in wrappedlibrt (rarely loaded), so mirror the exact
# same GO proto lines into the wrapped libc symbol list; each symbolmap entry
# resolves through dlsym(lib->w.lib, name) on the dlopen(NULL) handle and so
# finds the glibcshim implementations compiled into box64.
python3 - "$SRC/src/wrapped/wrappedlibc_private.h" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
if "GO(mq_getattr, iFip)" not in s:
    block = (
        "// glibc >= 2.34: mqueue functions live in libc (unified-desktop glibcshim)\n"
        "GO(mq_close, iFi)\n"
        "GO(mq_getattr, iFip)\n"
        "GO(mq_notify, iFip)\n"
        "GO(mq_open, iFpONN)\n"
        "GO(__mq_open_2, iFpO)\n"
        "GO(mq_receive, lFipLp)\n"
        "GO(mq_send, iFipLu)\n"
        "GO(mq_setattr, iFipp)\n"
        "GO(mq_timedreceive, lFipLpp)\n"
        "GO(mq_timedsend, iFipLup)\n"
        "GO(mq_unlink, iFp)\n"
    )
    anchor = "GOD(ynl, DFiD, yn)"
    assert anchor in s, "patch anchor not found (wrappedlibc_private.h tail)"
    s = s.replace(anchor, block + anchor)
    open(p, "w").write(s)
    print("patched", p)
else:
    print("wrappedlibc_private.h already carries mq entries")
PYEOF
grep -q "GO(mq_getattr, iFip)" "$SRC/src/wrapped/wrappedlibc_private.h" || { echo "ERROR: wrappedlibc_private.h patch did not land"; exit 1; }

# Upstream's #ifdef ANDROID branch of entrypoint.c compiles my___libc_init
# (a symbol no file in the tree references) and skips my___libc_start_main,
# which every glibc guest's libc.so.6 needs to reach main. Select the standard
# (Linux) branch unconditionally; my___libc_start_main is EXPORT'ed there.
python3 - "$SRC/src/emu/entrypoint.c" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = "#ifdef ANDROID\nvoid EXPORT my___libc_init("
new = "#if 0 /* unified-desktop: glibc guests need the standard my___libc_start_main; the ANDROID my___libc_init branch has no references in the tree */\nvoid EXPORT my___libc_init("
assert old in s, "patch anchor not found (entrypoint.c first ANDROID branch)"
s = s.replace(old, new, 1)
open(p, "w").write(s)
print("patched", p)
PYEOF
grep -n "unified-desktop: glibc guests need" "$SRC/src/emu/entrypoint.c" >/dev/null || { echo "ERROR: entrypoint.c patch did not land"; exit 1; }

# Box64's wrapped libc binds popen() to the native (Bionic) popen, whose child
# is exec'd at the literal path /bin/sh - absent on Android - so every guest
# spawn through popen (Xvfb's xkbcomp keymap compile) fails with no child ever
# starting. Route popen through the glibcshim's my_popen, which calls the
# Bionic popen with an explicit /system/bin/sh -c wrapper (see glibcshim.c).
python3 - "$SRC/src/wrapped/wrappedlibc_private.h" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = "GO(popen, SFpp)"
assert old in s, "patch anchor not found (GO(popen))"
s = s.replace("GO(popen, SFpp)", "GOM(popen, SFpp)", 1)
open(p, "w").write(s)
print("patched popen->GOM in", p)
PYEOF
grep -q "GOM(popen, SFpp)" "$SRC/src/wrapped/wrappedlibc_private.h" || { echo "ERROR: popen GOM patch did not land"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD"
cd "$BUILD"

# -DANDROID=ON: box64's own Android mode (adds the androidshmem wrapper).
# -DARM64=1: generic arm64 target, turns ARM_DYNAREC=ON.
# -DNOGIT=1: built from the released tarball, not a git checkout.
"$CMAKE" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DANDROID=ON -DARM64=1 -DNOGIT=1 \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  "$SRC"

"$CMAKE" --build . -j "$(nproc)"

# Verify the shim really is in the built program's dynamic symbol table: box64's
# wrapped-libc lookup finds glibc-internal symbols there (dlopen(NULL) handle).
NM_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
sync
sleep 2
# NOTE: no `grep -q` under pipefail — it can close the read pipe early and kill
# llvm-nm with SIGPIPE (exit 141), which read as "symbol missing".
if ! "$NM_BIN" -D --defined-only "$BUILD/box64" | grep "__errno_location" >/dev/null; then
  echo "ERROR: glibcshim symbols missing from final dynamic symbol table" >&2
  echo "DEBUG dynsym count: $("$NM_BIN" -D --defined-only "$BUILD/box64" | wc -l)" >&2
  echo "DEBUG symtab match: $("$NM_BIN" "$BUILD/box64" | grep -c '__errno_location')" >&2
  exit 1
fi
echo "shim symbols present in dynsym"
if ! "$NM_BIN" -D --defined-only "$BUILD/box64" | grep -E "^.* (getaddrinfo|freeaddrinfo)$" >/dev/null; then
  echo "ERROR: getaddrinfo/freeaddrinfo converters missing from dynsym" >&2
  exit 1
fi
echo "addrinfo converters present in dynsym"

# box64 is an Android PIE executable; ship it as lib*.so (extracted to nativeLibraryDir).
# Strip the shipped copy (22 MB vs 70 MB); keep the unstripped one in vendor/ for gdb.
mkdir -p "$(dirname "$OUT")"
cp -f "$BUILD/box64" "$OUT"
STRIP="$NDK/toolchains/llvm/prebuilt/*/bin/llvm-strip"
$STRIP "$OUT"
cp -f "$BUILD/box64" "$VENDOR/libbox64.unstripped.so"
echo "OK built $OUT"
file "$OUT"
ls -la "$OUT"
