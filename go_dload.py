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

NOTE: a single write is usually NOT enough — the device boot-loops (~1.4 s) and
only accepts the switch inside a narrow window, so this tool RESENDS on every HID
enumeration until the interface tears down. (Verified live: ~33 sends over ~25 s
were needed before `dfu-util -l` showed the DFU device 1004:6374.)

Run:  DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 go_dload.py
"""
import sys
import time
import hid

VID, PID = 0x1004, 0x6374
# report id 0x03, opcode 0x09, then the ASCII command — sent unpadded (len 11)
DLOAD_REPORT = bytes([0x03, 0x09]) + b"GoToDload"


def send_dload(timeout_s: float = 30.0) -> bool:
    """Hammer GoToDload across boot-loop windows until the HID device disappears.

    A single write is NOT enough: the device boot-loops (~1.4 s) and only accepts
    the switch inside a narrow window. We therefore resend on every enumeration
    until the vendor HID interface tears down (that teardown — surfacing as a
    write error / the device going absent — is the success signal).
    """
    print("Hammering GoToDload to %04x:%04x until it switches to DFU ..." % (VID, PID))
    end = time.time() + timeout_s
    sends = 0
    while time.time() < end:
        devs = hid.enumerate(VID, PID)
        if not devs:
            # If we have already sent at least once and the device is now gone,
            # it very likely switched into DFU mode — stop and let the caller check.
            if sends:
                print("  HID interface gone after %d sends — likely in DFU now." % sends)
                return True
            time.sleep(0.005)
            continue
        try:
            d = hid.Device(path=devs[0]["path"])
        except Exception:
            continue
        try:
            for _ in range(3):
                d.write(DLOAD_REPORT)
                sends += 1
        except Exception:
            # Expected while the device tears down HID to switch into DFU.
            pass
        finally:
            try:
                d.close()
            except Exception:
                pass
    print("  sent %d times; timed out (device may still be plugged as HID)." % sends)
    return sends > 0


if __name__ == "__main__":
    ok = send_dload()
    if not ok:
        print("Device HID never appeared — is it plugged in?")
        sys.exit(1)
    print("\nNow check for the DFU device (may take a second):")
    print("  DYLD_LIBRARY_PATH=/opt/homebrew/lib dfu-util -l")
    print("Expect an entry with idVendor:idProduct 1004:6374 (LGE Download Firmware Update).")
