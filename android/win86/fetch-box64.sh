#!/usr/bin/env bash
# Phase 2, T2: fetch box64 source (pinned) and build libbox64.so with an NDK.
# Runs in WSL (user `asus`, default Ubuntu distro): NDK r27c lives there with
# linux-x86_64 prebuilts. The Windows-side NDK cannot be used from WSL (its
# toolchains are windows-x86_64 only, checked 2026-10-16). Never run as root
# (LESSONS.md); output lands on the Windows tree via /mnt/c.
# Output: vendor/box64-build/box64 -> android/app/src/main/jniLibs/arm64-v8a/libbox64.so
set -euo pipefail

# Pin (default: v0.4.4). Round 3 candidate D builds a newer revision from main
# by overriding these three, e.g.:
#   BOX64_REV=27860e6a BOX64_SHA256=<tarball sha256> \
#   BOX64_URL=https://github.com/ptitSeb/box64/archive/<full-commit>.tar.gz ./fetch-box64.sh
# BOX64_ARC (optional) points at an already-downloaded tarball; it is verified
# against BOX64_SHA256 and copied in place of the network fetch.
REV="${BOX64_REV:-v0.4.4}"
URL="${BOX64_URL:-https://github.com/ptitSeb/box64/archive/refs/tags/${REV}.tar.gz}"
SHA256="${BOX64_SHA256:-99c6de4f509e46ab1de15df740d0e0ea338a7790efa3f67510dfbb975cc24029}"
ARC_SRC="${BOX64_ARC:-}"

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

if [[ -n "$ARC_SRC" ]]; then
  [[ -f "$ARC_SRC" ]] || { echo "ERROR: BOX64_ARC not found: $ARC_SRC"; exit 1; }
  cp -f "$ARC_SRC" "$ARC2"
fi
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

# unified-desktop patch (2026-10-17, BUGS B-020): guests are glibc x86-64
# binaries, so x64_sigaction_t must model glibc's struct sigaction. Upstream's
# #ifdef ANDROID branch uses a bionic-shaped layout (int sa_flags first), which
# made my_sigaction() read the guest handler out of sa_mask: the wineserver
# stored a garbage handler and died at the first SIGCHLD (jump to 0x110016007
# -> SIGSEGV livelock -> exit 139). Give the ANDROID branch glibc's layout
# {handler, 1024-bit mask, 32-bit flags, restorer}; the two mask copies in
# my_sigaction() then become size-aware. Upstream main still has the buggy
# layout (checked 2026-10-17); not submitted upstream.
python3 - "$SRC/src/include/signals.h" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
i = s.index("#ifdef ANDROID")
j = s.index("#else", i)
assert "x64_sigaction_s" in s[i:j] and "int sa_flags;" in s[i:j], "patch anchor not found (signals.h)"
new = ("#ifdef ANDROID\n"
"// unified-desktop patch: guests are glibc x86-64 binaries, so this must\n"
"// model glibc's struct sigaction {handler, 1024-bit mask, 32-bit flags,\n"
"// restorer}. The former bionic-shaped layout made my_sigaction() read the\n"
"// handler out of sa_mask and killed the guest at the first signal (BUGS B-020).\n"
"typedef struct x64_sigaction_s {\n"
"\tunion {\n"
"\t  sighandler_t _sa_handler;\n"
"\t  void (*_sa_sigaction)(int, siginfo_t *, void *);\n"
"\t} _u;\n"
"\tuint64_t sa_mask[16];\n"
"\tuint32_t sa_flags;\n"
"\tvoid (*sa_restorer)(void);\n"
"} x64_sigaction_t;\n")
s = s[:i] + new + s[j:]
open(p, "w").write(s)
print("patched", p)
PYEOF
grep -q "sa_mask\[16\]" "$SRC/src/include/signals.h" || { echo "ERROR: signals.h sigaction patch did not land"; exit 1; }

python3 - "$SRC/src/libtools/signals.c" <<'PYEOF'
import re, sys
p = sys.argv[1]
s = open(p).read()
a = s.index("int EXPORT my_sigaction(")
b = s.index("int EXPORT my___sigaction(", a)
body = s[a:b]
def sub1(text, needle, newlines):
    pat = re.compile(r"^([ \t]*)" + re.escape(needle) + r"[ \t]*$", re.M)
    assert len(pat.findall(text)) == 1, "anchor not unique: " + needle
    return pat.sub(lambda m: "".join(m.group(1) + n + "\n" for n in newlines), text, count=1)
body = sub1(body, "newact.sa_mask = act->sa_mask;",
    ["memcpy(&newact.sa_mask, &act->sa_mask, sizeof(newact.sa_mask)); // unified-desktop: glibc guest mask is 128 bytes"])
body = sub1(body, "oldact->sa_mask = old.sa_mask;",
    ["memset(&oldact->sa_mask, 0, sizeof(oldact->sa_mask)); // unified-desktop: zero glibc's 128-byte mask tail",
     "memcpy(&oldact->sa_mask, &old.sa_mask, sizeof(old.sa_mask));"])
s = s[:a] + body + s[b:]

# box64 main 2026-10-17 (commit 27860e6a) has two more plain mask assignments
# whose target/source is the glibc-shaped 128-byte field (one fills the guest
# oldact in fill_emulated_sigaction, one stores the guest mask into the host
# sigset); neither exists in v0.4.4, so replace only when present and keep the
# default pin buildable.
def sub1f(text, needle, newlines):
    if needle not in text:
        return text
    assert text.count(needle) == 1, "anchor not unique: " + needle
    pat = re.compile(r"^([ \t]*)" + re.escape(needle) + r"[ \t]*$", re.M)
    assert len(pat.findall(text)) == 1, "anchor not on its own line: " + needle
    return pat.sub(lambda m: "".join(m.group(1) + n + "\n" for n in newlines), text, count=1)
s = sub1f(s, "oldact->sa_mask = my_context->sigmask[signum];",
    ["memcpy(&oldact->sa_mask, &my_context->sigmask[signum], sizeof(my_context->sigmask[signum])); // unified-desktop: struct was zeroed at entry"])
s = sub1f(s, "my_context->sigmask[signum] = act->sa_mask;",
    ["memcpy(&my_context->sigmask[signum], &act->sa_mask, sizeof(my_context->sigmask[signum])); // unified-desktop: keep the host-width low signals"])
open(p, "w").write(s)
print("patched", p)
PYEOF
grep -q "memcpy(&newact.sa_mask, &act->sa_mask, sizeof(newact.sa_mask))" "$SRC/src/libtools/signals.c" || { echo "ERROR: signals.c sigaction patch did not land"; exit 1; }
if grep -qE "sa_mask = my_context->sigmask|sigmask\[signum\] = act->sa_mask" "$SRC/src/libtools/signals.c"; then echo "ERROR: plain 128-byte mask assignment remains in signals.c"; exit 1; fi

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

# Android's policy can refuse mprotect(PROT_EXEC) on guest mappings (wine:
# "map_image_into_view failed to set 60000020 protection on ... section .text,
# noexec filesystem?"). box64 never executes guest code from those mappings -
# it reads the bytes as data and translates them - so on failure retry without
# PROT_EXEC and report success if that works. Always log the failing call and
# its errno; this line is the evidence used to root-cause wine load failures.
python3 - "$SRC/src/wrapped/wrappedlibc.c" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()

def helper(ind):
    L = ind
    I = ind + "    "
    code = L + "if(ret) {\n"
    code += I + "/* unified-desktop: wine insists on PROT_EXEC for its PE .text but this\n"
    code += I + "   kernel/policy may refuse it; under box64 execution is translation,\n"
    code += I + "   never host execution of guest pages, so exec protection is optional.\n"
    code += I + "   Keep both log lines: they are the accepted evidence trail. */\n"
    code += I + "int e = errno;\n"
    code += I + 'printf_log(LOG_NONE, "mprotect(%p, 0x%lx, 0x%x) FAILED errno=%d\\n", addr, len, prot, e);\n'
    code += I + "if(prot & PROT_EXEC) {\n"
    code += I + "    /* diagnostic: the VMAs covering the refused range (evidence trail) */\n"
    code += I + '    FILE *mf = fopen("/proc/self/maps", "r");\n'
    code += I + "    if(mf) {\n"
    code += I + "        char mline[512];\n"
    code += I + "        unsigned long a0 = (unsigned long)addr, a1 = (unsigned long)addr + len;\n"
    code += I + "        while(fgets(mline, sizeof(mline), mf)) {\n"
    code += I + "            unsigned long s, e2;\n"
    code += I + '            if(sscanf(mline, "%lx-%lx", &s, &e2) == 2 && s < a1 && e2 > a0)\n'
    code += I + '                printf_log(LOG_NONE, "  map: %s", mline);\n'
    code += I + "        }\n"
    code += I + "        fclose(mf);\n"
    code += I + "    }\n"
    code += I + "    int ret2 = mprotect(addr, len, prot & ~PROT_EXEC);\n"
    code += I + '    printf_log(LOG_NONE, "mprotect(%p, 0x%lx, 0x%x) retry-no-exec -> %d errno=%d\\n",\n'
    code += I + "               addr, len, prot & ~PROT_EXEC, ret2, ret2 ? errno : 0);\n"
    code += I + "    if(!ret2) ret = 0;\n"
    code += I + "}\n"
    code += L + "}\n"
    return code

# anchor A: box64 v0.4.4 (the default pin)
old_a = """    int ret = mprotect(addr, len, prot);
    if(!ret && len) {
        updateProtection((uintptr_t)addr, len, prot);
    }
    return ret;
}

typedef struct mallinfo (*mallinfo_fnc)(void);"""
new_a = ("    int ret = mprotect(addr, len, prot);\n"
         + helper("    ") +
         """    if(!ret && len) {
        updateProtection((uintptr_t)addr, len, prot);
    }
    return ret;
}

typedef struct mallinfo (*mallinfo_fnc)(void);""")

# anchor B: box64 main 2026-10-17 (commit 27860e6a): my_mprotect rewritten
# with a 4K-page fast path; this device (box64_pagesize == 4096 == X86_PAGE_SIZE)
# takes that path.
old_b = """    if(box64_pagesize == X86_PAGE_SIZE) {
        int ret = mprotect(addr, len, prot);
        if(!ret && len) {
            updateProtection(start, len, prot);
            setGuestProtection(start, len, prot, 0);
        }
        return ret;
    }
"""
new_b = ("    if(box64_pagesize == X86_PAGE_SIZE) {\n"
         "        int ret = mprotect(addr, len, prot);\n"
         + helper("        ") +
         """        if(!ret && len) {
            updateProtection(start, len, prot);
            setGuestProtection(start, len, prot, 0);
        }
        return ret;
    }
""")

if s.count(old_a) == 1:
    s = s.replace(old_a, new_a)
    print("patched my_mprotect (v0.4.4 shape) in", p)
elif s.count(old_b) == 1:
    s = s.replace(old_b, new_b)
    print("patched my_mprotect (main-2026-10-17 shape) in", p)
else:
    raise AssertionError("mprotect anchor not found (neither v0.4.4 nor main shape)")
open(p, "w").write(s)
PYEOF
grep -q "retry-no-exec" "$SRC/src/wrapped/wrappedlibc.c" || { echo "ERROR: mprotect patch did not land"; exit 1; }

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
