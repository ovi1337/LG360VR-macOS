#!/usr/bin/env python3
"""
Put the LG 360 VR (LGR100AT) into DFU / firmware-download mode on macOS.

Credit: command discovered by strfry —
https://gist.github.com/strfry/d4097ec4167054c86826828a90019dbe

The download command is NOT the normal 0x0C ASCII-command opcode used by
activate.py / send_cmd.py. It uses opcode **0x09** and is sent UNPADDED:

    Linux:  echo -en "\\x03\\x09GoToDload" > /dev/hidraw0
    bytes:  03 09 47 6f 54 6f 44 6c 6f 61 64            (report id 3, opcode 0x09, "GoToDload", len 11)

After it is accepted the vendor-HID device disappears and re-enumerates as the
DFU device **"LGE Download Firmware Update"** — still USB VID:PID **1004:6374**
(NOT the ST bootloader 0483:df11; that pair is only the .dfu file-suffix target).
Verify with:  DYLD_LIBRARY_PATH=/opt/homebrew/lib dfu-util -l

Run:  DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 go_dload.py
"""
import sys
import time
import hid

VID, PID = 0x1004, 0x6374
# report id 0x03, opcode 0x09, then the ASCII command — sent unpadded (len 11)
DLOAD_REPORT = bytes([0x03, 0x09]) + b"GoToDload"


def send_dload(timeout_s: float = 30.0) -> bool:
    print("Waiting for LG 360 VR (%04x:%04x) HID window to send GoToDload ..." % (VID, PID))
    end = time.time() + timeout_s
    while time.time() < end:
        devs = hid.enumerate(VID, PID)
        if not devs:
            time.sleep(0.003)
            continue
        try:
            d = hid.Device(path=devs[0]["path"])
        except Exception:
            continue
        try:
            # The device likes to be heard before it listens.
            try:
                d.read(64, timeout=20)
            except Exception:
                pass
            d.write(DLOAD_REPORT)
            print("  -> sent %s" % DLOAD_REPORT.hex(" "))
            return True
        except Exception as e:
            # A write error here is EXPECTED once the device tears down HID to
            # switch into DFU mode — that is the success signal, not a failure.
            print("  write raised (%s) — device may be switching to DFU now." % e)
            return True
        finally:
            try:
                d.close()
            except Exception:
                pass
    return False


if __name__ == "__main__":
    ok = send_dload()
    if not ok:
        print("Device HID never appeared — is it plugged in?")
        sys.exit(1)
    print("\nNow check for the DFU device (may take a second):")
    print("  DYLD_LIBRARY_PATH=/opt/homebrew/lib dfu-util -l")
    print("Expect an entry with idVendor:idProduct 1004:6374 (LGE Download Firmware Update).")
