#!/usr/bin/env python3
"""Wrap a raw STM32 flash dump into a minimal ELF32 (little-endian ARM) so a
standard disassembler (llvm-objdump / objdump / Ghidra import) can load it at
the correct virtual address WITHOUT installing extra tools.

Usage:
    python3 raw2elf.py <raw.bin> <vaddr_hex> <out.elf>
    # e.g. python3 raw2elf.py elements/elem_08020000.bin 0x08020000 /tmp/app.elf

Then, for example (Xcode ships llvm-objdump):
    llvm-objdump -d --triple=thumbv7em-none-eabi /tmp/app.elf | less

Note: the first words at vaddr are the Cortex-M vector table (data, not code);
the reset handler address is the 2nd word (with bit0 set for Thumb).
"""
import struct
import sys


def build(data: bytes, vaddr: int) -> bytes:
    shstr = b"\x00.text\x00.shstrtab\x00"
    ehsize, phentsize, phnum, shentsize = 52, 32, 1, 40
    phoff = ehsize
    text_off = phoff + phentsize * phnum
    shstr_off = text_off + len(data)
    sh_off = shstr_off + len(shstr)
    pad = (-sh_off) % 4
    sh_off += pad
    ehdr = struct.pack(
        "<16sHHIIIIIHHHHHH",
        b"\x7fELF\x01\x01\x01" + b"\x00" * 9,
        2, 40, 1, vaddr, phoff, sh_off, 0x05000000,
        ehsize, phentsize, phnum, shentsize, 3, 2,
    )
    phdr = struct.pack("<IIIIIIII", 1, text_off, vaddr, vaddr,
                       len(data), len(data), 0x5, 4)
    sh_null = b"\x00" * 40
    sh_text = struct.pack("<IIIIIIIIII", 1, 1, 0x6, vaddr, text_off,
                          len(data), 0, 0, 4, 0)
    sh_shstr = struct.pack("<IIIIIIIIII", 7, 3, 0, 0, shstr_off,
                           len(shstr), 0, 0, 1, 0)
    return ehdr + phdr + data + shstr + b"\x00" * pad + sh_null + sh_text + sh_shstr


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(1)
    data = open(sys.argv[1], "rb").read()
    vaddr = int(sys.argv[2], 16)
    open(sys.argv[3], "wb").write(build(data, vaddr))
    print(f"wrote {sys.argv[3]} (vaddr=0x{vaddr:08X}, {len(data)} bytes of code/data)")


if __name__ == "__main__":
    main()
