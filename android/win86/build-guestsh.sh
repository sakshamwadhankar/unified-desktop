#!/usr/bin/env bash
# Build the guest "sh" wrapper (x86-64, native WSL gcc) and verify it.
# Output: unified-desktop/android/win86/guestsh/udsh + a sha256.
set -euo pipefail
D=/mnt/c/Users/Asus/Documents/GitHub/unified-desktop/android/win86/guestsh
gcc -Os -fno-pie -no-pie "$D/udsh.c" -o "$D/udsh"
file "$D/udsh"
sha256sum "$D/udsh"
# quick local sanity (x86-64 host): execv needs a real path, so use /bin/echo;
# the second case exercises the quoted-token tokenizer the server relies on.
"$D/udsh" -c '/bin/echo LOCAL_OK'
"$D/udsh" -c '/bin/echo "QUOTED 1" "TWO WORDS"'
