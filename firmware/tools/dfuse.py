#!/usr/bin/env python3
"""DfuSe (STM32) firmware helper for the LG 360 VR (LGR100AT).

Non-destructive utilities only. NOTHING here talks to the device or flashes
anything. It parses / extracts / rebuilds the .dfu container so a patched image
can be produced offline. Flashing is a separate, manual, explicitly-confirmed
step (see ../README.md).

DfuSe integrity is a plain CRC32 suffix (NO cryptographic signature):
    stored_crc = (~zlib.crc32(file[:-4])) & 0xFFFFFFFF

Usage:
    python3 dfuse.py info    <file.dfu>
    python3 dfuse.py extract <file.dfu> <outdir>
    python3 dfuse.py patch   <in.dfu> <out.dfu> <elemAddrHex> <byteOffset> <hexbytes>
        # e.g. patch app (0x08020000) at relative offset 0x1234 with 00bf00bf
    python3 dfuse.py fixcrc  <file.dfu>          # recompute suffix CRC in place
"""
import struct
import sys
import zlib


def dfuse_crc(body: bytes) -> int:
    return (~zlib.crc32(body)) & 0xFFFFFFFF


def parse(d: bytes):
    assert d[:5] == b"DfuSe", "not a DfuSe file"
    ntargets = d[10]
    off = 11
    targets = []
    for _ in range(ntargets):
        tname = d[off + 11:off + 11 + 255].split(b"\x00")[0].decode("latin1")
        nelem = struct.unpack("<I", d[off + 270:off + 274])[0]
        eoff = off + 274
        elems = []
        for _e in range(nelem):
            eaddr, esize = struct.unpack("<II", d[eoff:eoff + 8])
            data_off = eoff + 8
            elems.append({"addr": eaddr, "size": esize, "file_off": data_off})
            eoff = data_off + esize
        targets.append({"name": tname, "elems": elems})
        off = eoff
    return targets


def cmd_info(path):
    d = open(path, "rb").read()
    targets = parse(d)
    print(f"file: {path}  ({len(d)} bytes)")
    stored = struct.unpack("<I", d[-4:])[0]
    calc = dfuse_crc(d[:-4])
    print(f"suffix CRC stored=0x{stored:08X} calc=0x{calc:08X} "
          f"{'OK' if stored == calc else 'MISMATCH'}")
    for ti, t in enumerate(targets):
        print(f"Target{ti} name='{t['name']}'")
        for ei, e in enumerate(t["elems"]):
            print(f"  elem{ei}: addr=0x{e['addr']:08X} size={e['size']} "
                  f"file_off=0x{e['file_off']:X}")


def cmd_extract(path, outdir):
    import os
    d = open(path, "rb").read()
    os.makedirs(outdir, exist_ok=True)
    for t in parse(d):
        for e in t["elems"]:
            fn = os.path.join(outdir, f"elem_{e['addr']:08X}.bin")
            open(fn, "wb").write(d[e["file_off"]:e["file_off"] + e["size"]])
            print("wrote", fn)


def cmd_patch(inp, outp, addr_hex, off_hex, hexbytes):
    d = bytearray(open(inp, "rb").read())
    addr = int(addr_hex, 16)
    rel = int(off_hex, 16)
    patch = bytes.fromhex(hexbytes)
    tgt = None
    for t in parse(bytes(d)):
        for e in t["elems"]:
            if e["addr"] == addr:
                tgt = e
    if tgt is None:
        sys.exit(f"no element at 0x{addr:08X}")
    if rel + len(patch) > tgt["size"]:
        sys.exit("patch exceeds element bounds")
    fpos = tgt["file_off"] + rel
    old = bytes(d[fpos:fpos + len(patch)])
    d[fpos:fpos + len(patch)] = patch
    struct.pack_into("<I", d, len(d) - 4, dfuse_crc(bytes(d[:-4])))
    open(outp, "wb").write(d)
    print(f"patched {len(patch)}B at 0x{addr:08X}+0x{rel:X} "
          f"(file 0x{fpos:X}): {old.hex()} -> {patch.hex()}")
    print(f"wrote {outp}, CRC refreshed")


def cmd_fixcrc(path):
    d = bytearray(open(path, "rb").read())
    struct.pack_into("<I", d, len(d) - 4, dfuse_crc(bytes(d[:-4])))
    open(path, "wb").write(d)
    print("CRC updated")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    a = sys.argv[2:]
    {
        "info": lambda: cmd_info(a[0]),
        "extract": lambda: cmd_extract(a[0], a[1]),
        "patch": lambda: cmd_patch(a[0], a[1], a[2], a[3], a[4]),
        "fixcrc": lambda: cmd_fixcrc(a[0]),
    }.get(cmd, lambda: sys.exit("unknown cmd"))()


if __name__ == "__main__":
    main()
