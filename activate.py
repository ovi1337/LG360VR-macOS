#!/usr/bin/env python3
"""
LG 360 VR Activator for macOS.

Port of bauermaximilian/LG-360-VR-for-PC (LG360VRActivator) to macOS.

The headset (USB 0x1004:0x6374) enumerates as a vendor-defined HID device, so on
macOS the Apple kernel HID driver claims it and libusb cannot grab the raw
endpoints. We therefore talk to it through IOKit HID (hidapi) and deliver the
activation commands as HID *output reports*. The bytes are identical to the
Windows tool:

    report {0x03, 0x0C} + "Sleep Disable"   -> disables proximity auto-sleep
    report {0x03, 0x0C} + "VR App Start"     -> powers the display pipeline

0x03 is the HID report ID (the device exposes report IDs 1..5, each a 63-byte
output report on vendor usage page 0xFF00), 0x0C is the command opcode.

NOTE ON RESULT (see README.md): sending these commands is confirmed working - the
headset firmware reports `do_VRAppStart: 1`, turns on the backlight and initialises
its ANX7401 DisplayPort receiver + TC358870 HDMI->MIPI bridge. However the device
then waits for a DisplayPort-alt-mode video signal from the Mac ("Waiting for HDMI
signal" -> "failed, stop to wait HDMI signal") and resets in a ~0.56s boot loop,
because Apple-Silicon macOS never enters DP alt mode on the port for this headset.
So the panel flashes on but no usable external display appears. This tool remains
useful for driving/diagnosing the headset; see send_cmd.py for arbitrary commands.

Run:  DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 activate.py
      (or use ./run.sh)
"""
import sys
import time
import hid

VID, PID = 0x1004, 0x6374
REPORT_ID, OPCODE, REPORT_LEN = 0x03, 0x0C, 63


def make_report(cmd: str) -> bytes:
    payload = bytes([OPCODE]) + cmd.encode("ascii")
    payload += bytes(REPORT_LEN - len(payload))
    return bytes([REPORT_ID]) + payload


def quick_drain(dev):
    # The device wants to be heard before it will listen; read one report.
    try:
        dev.read(64, timeout=30)
    except Exception:
        pass


def send_once(path) -> bool:
    dev = hid.Device(path=path)
    try:
        quick_drain(dev)
        dev.write(make_report("Sleep Disable"))
        dev.write(make_report("VR App Start"))
        return True
    finally:
        try:
            dev.close()
        except Exception:
            pass


def main():
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    print("LG 360 VR Activator (macOS / hidapi)")
    print("Put the glasses down flat and keep them still. Working for %ds..." % duration)
    deadline = time.time() + duration
    ok = fail = 0
    while time.time() < deadline:
        ds = hid.enumerate(VID, PID)
        if not ds:
            time.sleep(0.005)
            continue
        try:
            send_once(ds[0]["path"])
            ok += 1
        except Exception:
            fail += 1
        sys.stdout.write("\r  activations sent: %d (failed opens: %d)   " % (ok, fail))
        sys.stdout.flush()
        time.sleep(0.02)
    print("\nDone. The headset backlight should have flashed on in response.")
    print("If no external display appears, see README.md (DP-alt-mode limitation).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
