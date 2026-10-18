#!/usr/bin/env python3
"""In-place rewrite of DT_NEEDED / DT_SONAME strings inside an ELF's .dynstr.

Phase 3 (android/vscode/fetch-native.sh) renames Termux library files to names
that match Android's `lib*.so` rule, so every DT_NEEDED pointing at them must be
rewritten too. The replacement is done inside the file's own dynamic string
table, not by rebuilding the table, so the new name may only be SHORTER OR EQUAL
in length (the caller relies on this; the leftover bytes become NUL padding).

How the table is located: the PT_DYNAMIC program header gives DT_STRTAB and
DT_STRSZ; the virtual address is mapped to a file offset through the PT_LOAD
segments. Nothing outside that byte range is ever touched, and every pair must
match exactly one NUL-terminated string (0 or >1 matches abort, so a wrong table
or an unexpected layout can never be patched silently).

usage: patch-neededs.py <elf> old=new [old=new ...]
prints "PATCH <file> <old> -> <new> n=<count>"; exits non-zero on any error.
"""
import struct
import sys

PT_LOAD = 1
PT_DYNAMIC = 2
DT_STRTAB = 5
DT_STRSZ = 10


def program_headers(blob):
    e_phoff, = struct.unpack_from("<Q", blob, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", blob, 0x36)
    out = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = \
            struct.unpack_from("<IIQQQQQQ", blob, off)
        out.append(dict(type=p_type, offset=p_offset, vaddr=p_vaddr, filesz=p_filesz))
    return out


def dynstr_range(blob, phdrs):
    dyn = [p for p in phdrs if p["type"] == PT_DYNAMIC]
    if len(dyn) != 1:
        raise SystemExit(f"expected exactly one PT_DYNAMIC, got {len(dyn)}")
    d = dyn[0]
    strtab = strsz = None
    for i in range(d["filesz"] // 16):
        tag, val = struct.unpack_from("<QQ", blob, d["offset"] + i * 16)
        if tag == DT_STRTAB:
            strtab = val
        elif tag == DT_STRSZ:
            strsz = val
    if strtab is None or strsz is None:
        raise SystemExit("no DT_STRTAB/DT_STRSZ in the dynamic table")
    for p in phdrs:
        if p["type"] == PT_LOAD and p["vaddr"] <= strtab < p["vaddr"] + p["filesz"]:
            return p["offset"] + (strtab - p["vaddr"]), strsz
    raise SystemExit("DT_STRTAB address is not inside any PT_LOAD segment")


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    path, pairs = argv[1], argv[2:]
    blob = bytearray(open(path, "rb").read())
    if blob[:4] != b"\x7fELF" or blob[4] != 2 or blob[5] != 1:
        raise SystemExit(f"{path}: not a 64-bit little-endian ELF")
    off, size = dynstr_range(blob, program_headers(blob))
    region = bytes(blob[off:off + size])
    for pair in pairs:
        old, new = pair.split("=", 1)
        ob, nb = old.encode(), new.encode()
        if len(nb) > len(ob):
            raise SystemExit(f"{path}: replacement '{new}' is longer than '{old}'")
        needle, repl = ob + b"\0", nb + b"\0" * (len(ob) - len(nb) + 1)
        n = region.count(needle)
        if n == 0:
            raise SystemExit(f"{path}: '{old}' not found in .dynstr")
        if n != 1:
            raise SystemExit(f"{path}: '{old}' appears {n} times in .dynstr")
        i = region.find(needle)
        blob[off + i:off + i + len(repl)] = repl
        region = bytes(blob[off:off + size])
        print(f"PATCH {path} {old} -> {new} n={n}")
    open(path, "wb").write(blob)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
