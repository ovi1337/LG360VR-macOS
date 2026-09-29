#!/usr/bin/env python3
"""Minimal, SAFE DfuSe flasher for the LG 360 VR (LGR100AT) app region only.

Why this exists: this device's LGE "Download Firmware Update" bootloader is a
real DfuSe device (upload block 0 returns the command set {0x21 Set-Address,
0x41 Erase}), but it does NOT expose the ST memory-layout string in its
interface name (name="UNKNOWN"), so dfu-util 0.11 aborts with
"Failed to parse memory layout" for every download/upload.

We know the element addresses/sizes from the .dfu element headers, so we drive
DfuSe manually. HARD SAFETY GUARD: this tool refuses to erase or write any
address below APP_BASE (0x08020000). elem0 (bootloader @0x08000000) and elem1
(@0x08010000) are never touched, so the DFU recovery path is always preserved.

Usage:
  DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 dfuse_flash_app.py <elem2.bin>
"""
import sys, time
import usb.core, usb.util

VID, PID = 0x1004, 0x6374
APP_BASE = 0x08020000          # elem2 base; NEVER touch below this
INTF = 0
TS = 1024                      # transfer size the device reports

# DfuSe / DFU request helpers -------------------------------------------------
def dnload(dev, block, data):
    return dev.ctrl_transfer(0x21, 1, block, INTF, data)

def getstatus(dev):
    r = list(dev.ctrl_transfer(0xA1, 3, 0, INTF, 6))
    # bStatus, bwPollTimeout(3 LE), bState, iString
    poll = r[1] | (r[2] << 8) | (r[3] << 16)
    return r[0], poll, r[4]

def clrstatus(dev):
    dev.ctrl_transfer(0x21, 4, 0, INTF, None)

def abort(dev):
    dev.ctrl_transfer(0x21, 6, 0, INTF, None)

DFU_IDLE, DFU_DNLOAD_IDLE, DFU_DNBUSY, DFU_ERROR = 2, 5, 4, 10

def wait_ready(dev, ctx=""):
    # First GETSTATUS moves special commands into execution (dfuDNBUSY);
    # keep polling until we leave busy.
    for _ in range(200):
        st, poll, state = getstatus(dev)
        if state == DFU_ERROR:
            clrstatus(dev)
            raise RuntimeError(f"DFU error during {ctx}: status={st}")
        if state in (DFU_IDLE, DFU_DNLOAD_IDLE):
            return
        time.sleep(max(poll, 5) / 1000.0)
    raise RuntimeError(f"timeout waiting ready during {ctx}")

def special(dev, payload, ctx):
    dnload(dev, 0, payload)      # special commands go to block 0
    wait_ready(dev, ctx)

def set_address(dev, addr):
    assert addr >= APP_BASE, f"REFUSING set-address below app base: 0x{addr:08X}"
    special(dev, bytes([0x21]) + addr.to_bytes(4, "little"), f"set-addr 0x{addr:08X}")

def erase_page(dev, addr):
    assert addr >= APP_BASE, f"REFUSING erase below app base: 0x{addr:08X}"
    special(dev, bytes([0x41]) + addr.to_bytes(4, "little"), f"erase 0x{addr:08X}")

def main():
    if len(sys.argv) != 2:
        print(__doc__); sys.exit(2)
    data = open(sys.argv[1], "rb").read()
    print(f"payload: {sys.argv[1]} ({len(data)} bytes) -> 0x{APP_BASE:08X}")

    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print("DFU device 1004:6374 not found (enter DFU with go_dload.py first)")
        sys.exit(1)
    try:
        if dev.is_kernel_driver_active(INTF):
            dev.detach_kernel_driver(INTF)
    except Exception:
        pass
    dev.set_configuration()

    abort(dev); wait_ready(dev, "initial abort")

    # 1) Erase every 2KB across the payload span. 0x08020000 is 64KB aligned,
    #    so whatever the real sector size is, erases stay at/above APP_BASE.
    end = APP_BASE + len(data)
    a = APP_BASE
    npages = 0
    while a < end:
        erase_page(dev, a)
        a += 0x800
        npages += 1
    print(f"erased {npages} x 2KB steps [0x{APP_BASE:08X}..0x{end:08X})")

    # 2) Write data in TS-sized chunks. DfuSe: set address pointer once, then
    #    data blocks start at wValue=2; addr = base + (block-2)*TS.
    set_address(dev, APP_BASE)
    nblocks = (len(data) + TS - 1) // TS
    for i in range(nblocks):
        chunk = data[i * TS:(i + 1) * TS]
        dnload(dev, 2 + i, chunk)
        wait_ready(dev, f"write block {i}")
        if i % 20 == 0 or i == nblocks - 1:
            print(f"  wrote block {i+1}/{nblocks}", end="\r")
    print(f"\nwrote {nblocks} blocks ({len(data)} bytes)")

    # 3) Manifest: zero-length download, then GETSTATUS to trigger manifestation
    dnload(dev, 0, b"")
    try:
        getstatus(dev)
    except Exception as e:
        print("manifest getstatus (expected to detach):", e)
    print("DONE. Manifestation triggered.")

if __name__ == "__main__":
    main()
