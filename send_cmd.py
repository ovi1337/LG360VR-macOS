#!/usr/bin/env python3
"""
Send arbitrary LG 360 VR firmware commands and capture responses.
Usage: python3 send_cmd.py "VR App Start" "Set LCD Pattern Test" ...
Each command is framed as HID output report: [0x03, 0x0C] + ASCII (padded to 64).
NOTE: this 0x0C framing is for normal commands (VR App Start, sensors, ...). The
firmware-download command "GoToDload" is the exception — it uses opcode 0x09 and is
sent unpadded; use go_dload.py for that.
"""
import sys, time, hid

VID, PID = 0x1004, 0x6374

def make(cmd):
    p = bytes([0x0C]) + cmd.encode("ascii")
    p += bytes(63 - len(p))
    return bytes([0x03]) + p

def decode(b):
    b = bytes(b)
    return b[0], b[1:].split(b"\x00")[0].decode("ascii", "replace").rstrip()

cmds = sys.argv[1:] or ["VR App Start", "Set LCD Pattern Test"]
print("Will send on next connection window:", cmds)

end = time.time() + 40
sent = 0
while time.time() < end and sent < 3:
    ds = hid.enumerate(VID, PID)
    if not ds:
        time.sleep(0.003); continue
    try:
        d = hid.Device(path=ds[0]["path"])
    except Exception:
        continue
    t0 = time.time()
    try:
        for c in cmds:
            d.write(make(c))
            time.sleep(0.03)
        sent += 1
        print("\n=== sent batch %d: %s ===" % (sent, cmds))
        # capture responses until drop
        while True:
            try:
                data = d.read(64, timeout=120)
            except Exception:
                break
            if not data:
                continue
            rid, txt = decode(data)
            if txt:
                print("  +%.3fs [id%d] %s" % (time.time()-t0, rid, txt))
    finally:
        try: d.close()
        except Exception: pass
print("\ndone.")
