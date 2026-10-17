#!/usr/bin/env python3
"""Data-only patches to the Debian Xvfb guest binary (pulled from the staged
rootfs). All patches keep byte length so string-table offsets and shared
suffixes stay valid. Each guest-absolute path becomes a cwd-relative path that
resolves inside our extracted rootfs (the server is launched with cwd=rootfs):

  /usr/... -> .usr/...   (rootfs contains a ".usr" -> "usr" symlink)
  /var/... -> .var/...   (rootfs contains a ".var" -> "var" symlink)
  "/tmp" (lock dir, tail of "/var/tmp") -> "tmp" (relative)

Side effect of the last one, accepted and noted: the "/var/tmp" literal in the
MIT-SHM tempfile fallback becomes "/vartmp"; that fallback is not used when
memfd-style SHM works, which it does on this setup."""
import hashlib, os, re

src = '/mnt/c/Users/Asus/AppData/Local/Temp/opencode/Xvfb.orig2'
out = '/mnt/c/Users/Asus/Documents/GitHub/unified-desktop/android/win86/vendor/xvfb-patched/Xvfb'

d = open(src, 'rb').read()   # pristine copy every run
patches = [
    # lock dir: "/tmp\0" (the standalone tail of "/var/tmp\0", used by LockServer)
    (b'/tmp\x00', b'tmp\x00\x00', 1),
    # XKB bin dir + data root, followed by each other
    (b'/usr/bin\x00', b'.usr/bin\x00', 1),
    (b'/usr/share/X11/xkb\x00', b'.usr/share/X11/xkb\x00', 1),
    # compiled-keymap output dir (XKM_OUTPUT_DIR)
    (b'/var/lib/xkb/\x00', b'.var/lib/xkb/\x00', 1),
    # module registry protocol list
    (b'/usr/lib/xorg', b'.usr/lib/xorg', 3),
    # default font path elements (7 entries incl. inside the long list)
    (b'/usr/share/fonts', b'.usr/share/fonts', 7),
    # Xorg os/utils.c Popen() execs a literal "/bin/sh" (fork+execute, not the
    # libc popen); make it relative so it becomes our own rootfs dash.
    (b'/bin/sh\x00', b'bin/sh\x00\x00', 1),
]
for old, new, expect in patches:
    n = [m.start() for m in re.finditer(re.escape(old), d)]
    assert len(n) == expect, f'patch {old!r}: found {len(n)}, expected {expect}'
    d = d.replace(old, new)
os.makedirs(os.path.dirname(out), exist_ok=True)
open(out, 'wb').write(d)
h = hashlib.sha256()
h.update(d)
print('all patches applied ->', out)
print('sha256', h.hexdigest())
