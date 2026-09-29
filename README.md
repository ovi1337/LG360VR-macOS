# LG 360 VR on macOS — Investigation & Tools

Goal: use an **LG 360 VR** headset (model **LGR100AT**, USB `0x1004:0x6374`) as a
display / external monitor on a Mac (Apple Silicon, M4 Pro).

Reference (Windows/PC): https://github.com/bauermaximilian/LG-360-VR-for-PC

> **Note on repository contents & copyright.** This repo contains only original
> work (tooling, reverse-engineering scripts, the Android/Windows test-app source
> and the written analysis). **LG-owned binaries are intentionally excluded** and
> git-ignored: the stock/patched firmware images (`firmware/*.dfu`), the extracted
> firmware segments (`firmware/elements/`) and the LG application packages
> (`apks/*.apk`). Obtain the stock firmware yourself from *LG 360 VR Manager.apk*
> (`assets/LGR100AT-…dfu`) to reproduce the analysis; `firmware/CHECKSUMS.txt` lists
> the expected SHA-256. Original code here is provided as-is for interoperability
> research and education.

## TL;DR

- **Activation protocol works on macOS.** We reverse-engineered and replicated the
  Windows activator using IOKit HID (`hidapi`). The headset firmware confirms it
  receives our command (`do_VRAppStart: 1`), turns on the backlight and initialises
  its display bridges.
- **But the headset cannot become a usable display on this Mac.** It waits for a
  DisplayPort-alt-mode video signal that Apple-Silicon macOS never delivers to it,
  and it resets in a ~0.56 s boot loop. This is a hardware/platform timing deadlock
  that cannot be fixed in software on macOS.

## Test apps (Android & Windows)

Two companion apps replicate the activation/analysis protocol on the platforms
where a working video path is more likely than on Apple-Silicon macOS:

- **`android/`** — Kotlin + Jetpack Compose. Tested on a Galaxy S25 Ultra: USB-HID
  activation works (`do_VRAppStart: 1`, stable heartbeats, no boot loop), but no
  external DP-alt-mode display appears. See `android/README.md`.
- **`windows/`** — C# / .NET 8 / WinForms (HidSharp, no Zadig needed). Same feature
  set (activate, command set, live debug log, display test pattern, USB info). Build
  with `windows/build.ps1`. See `windows/README.md`.

## Firmware modification (feasibility)

- **`firmware/`** — analysis of the stock `.dfu` and whether the device can be
  repurposed by patching its firmware. Finding: **feasible** — the firmware is an
  unencrypted, unsigned STM32 **DfuSe** image (`.dfu` suffix IDs `0483:DF11`;
  the runtime DFU device is `1004:6374`), flashable
  with the already-installed `dfu-util`; integrity is a plain CRC32 that is trivially
  recomputed. **Ghidra RE went further:** the HDMI-wait watchdog (`FUN_08035a42`)
  was decompiled and the reset isolated to a single instruction. Two verified 2–4
  byte patches are ready (`patched_goalA_noreset.dfu` removes the boot-loop reset;
  `patched_goalA_startvideo.dfu` also starts the video pipeline standalone).
  **Nothing has been flashed on macOS** — dfu-util there aborts because the LGE
  bootloader exposes no memory-layout string, and raw DfuSe set-address STALLs
  (`firmware/README.md` §4f). Flash from **Linux/Windows** instead:
  see **`firmware/FLASHING.md`** and the one-shot prep scripts
  `firmware/tools/prepare_flash.sh` / `prepare_flash.ps1`.
  See `firmware/README.md`.

## How the device works

The LG 360 VR is a **USB-C DisplayPort-Alt-Mode display sink**. Internally:

- `ANX7401` — Analogix DisplayPort receiver (handles USB-C DP alt mode)
- `TC358870` — Toshiba HDMI/DP → MIPI-DSI bridge feeding the panels
- `SM5306` — backlight/LED driver, `NCP6924` — PMIC

On USB plug-in it appears as a **vendor HID device** (`LGE Custom Human interface`,
report IDs 1..5, usage page 0xFF00, EP 0x81 IN / 0x01 OUT). It stays dark until it
receives an activation command, then it powers the DP receiver and waits for video.

### Activation command format

Each command is a HID **output report**:

```
[0x03]      report ID
[0x0C]      opcode
"<ASCII>"   command text
... zero-padded to the 63-byte report length
```

Confirmed firmware commands (extracted from the device firmware
`LG 360 VR Manager.apk/assets/LGR100AT-...dfu`):

```
VR App Start        Sleep Disable
Get Swversion       Get ModelName      Get SerialNumber   Get Hwversion   Get FID
Proximity On/Off/Cal/NEAR/FAR/Get Data/Get Crosstalk/Get Regi/Set Regi
Gyro   On/Off/Cal/Selftest/Get XYZ
Accel  On/Off/Cal/Selftest/Get XYZ
Compass On/Off/Get XYZ
Set LCD Pattern Test      (BSP_SM5306_setLCDPatternTest)
Go to Dload               (do_GoToDload -> firmware-update mode "LGE Download Firmware Update")
```

> **Exception — the download command uses a different opcode.** `GoToDload` is sent
> with opcode **`0x09`** (not `0x0C`) and **unpadded** (11 bytes):
> `03 09 47 6f 54 6f 44 6c 6f 61 64` = `\x03\x09GoToDload`. After it is accepted the
> device re-enumerates as the DFU device **"LGE Download Firmware Update"**, which
> keeps USB VID:PID **`1004:6374`** (the `0483:DF11` pair is only the `.dfu` file's
> suffix target, not the runtime device). See `go_dload.py` and `firmware/README.md`
> §4d. Credit: [strfry gist](https://gist.github.com/strfry/d4097ec4167054c86826828a90019dbe).

On Windows the reference tool replaces the HID driver with WinUSB (via Zadig) and
writes raw to the interrupt OUT endpoint. On macOS that is unnecessary/blocked —
`hidapi` output reports carry the identical bytes over the same endpoint.

## Why it does NOT work as a monitor on macOS (evidence)

Reading the headset's own debug log off EP 0x81 while activating shows, on every
connection:

```
do_VRAppStart: 1                         <- our command executed
BSP_SM5306_On: turn on B/L               <- backlight on (this is the visible "blink")
ANX7401_power_on >> power on             <- DP receiver powered
BSP_Init >> Init ANX7401 Init
BSP_Init >> Init BSP_TC358870XBG_On Init
BSP_TC358870XBG_Init_Reg >> HDMI registers set
BSP_TC358870XBG_Init_Reg : Waiting for HDMI signal
failed ~!! stop to wait HDMI signal      <- no video arrived -> reset (~0.56 s)
```

Meanwhile the Mac's USB-C port (`Port-USB-C@2`) only ever toggles **USB2/USB3**
transport states — it **never enters the DisplayPort transport state**. So:

1. The `ANX7401` DP receiver is powered **only after** `VR App Start`, so the device
   does not advertise DP alt mode during its initial USB-C power negotiation.
2. After activation the device would need macOS to (re-)enter DP alt mode, train the
   link and read EDID — which takes longer than the device's ~0.5 s
   "wait for HDMI" watchdog.
3. The device resets ("hardware connection lost", EP 0x81 transaction error) before
   macOS finishes, tearing down all DP progress → infinite ~1.6 s boot loop.

On Apple Silicon there is no supported way to force DP-alt-mode entry, inject an
EDID, or feed video fast enough to break this deadlock.

**Root-cause confirmation at the USB-C PD level:** the Mac's port
(`AppleHPMInterfaceType10`, `Port-USB-C@2`) supports DisplayPort
(`TransportsSupported = (CC, USB2, USB3, CIO, DisplayPort)`) but reports
`DisplayPortPinAssignment = No`, and no DP-alt-mode SVID / Discover-Identity /
Enter-Mode events ever appear while activating. In other words the **headset never
advertises DisplayPort alt mode** over USB-C PD (its ANX7401 DP controller is
unpowered at PD-contract time and the device resets before any re-negotiation), so
macOS correctly never assigns DP pins and never brings up video.

### Also observed / cleared during debugging
- `adb` (Android Debug Bridge) and Google Chrome (WebHID) were both grabbing the
  device for exclusive access on every enumeration (LG VID `0x1004` looks like an
  Android device to adb). Stopping the adb server and closing the Chrome tab removed
  that interference, but the boot loop persisted — so it is intrinsic to the
  headset waiting for video, not caused by those apps.

## Tools in this folder

- `activate.py` — sends `Sleep Disable` + `VR App Start` (the activation sequence).
- `send_cmd.py` — send arbitrary firmware commands and print the debug responses,
  e.g. `python3 send_cmd.py "VR App Start" "Set LCD Pattern Test"`.
- `go_dload.py` — put the device into DFU / firmware-download mode using the correct
  `\x03\x09GoToDload` report (opcode 0x09, unpadded); then check `dfu-util -l`
  (unfiltered) for the DFU device `1004:6374`.
- `run.sh` — wrapper that sets `DYLD_LIBRARY_PATH` for Homebrew hidapi.

### Requirements
```
brew install hidapi
python3 -m pip install hid
```
Run: `./run.sh` (or `DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 activate.py`).

## If you still want to try to get a picture

Software cannot bypass the DP-alt-mode requirement, but these hardware angles are
worth trying (all low-probability):

- Use a **certified full-featured USB-C / Thunderbolt cable** and try **every** port.
- Try a **powered USB-C DP-alt-mode dock/hub** between Mac and headset.
- Try on an **Intel Mac** (older Macs allowed EDID overrides / different DP-alt
  negotiation) or a **Windows PC / Android phone** with the reference tool, which is
  the environment the headset was designed for.

The original author already notes this is "a gamble" even on Windows and depends
entirely on the USB-C port's DP-alt-mode behaviour.

## Related work — independent confirmation (strfry)

Two projects by **strfry** independently confirm the findings here:

- **[strfry/LG360VR](https://github.com/strfry/LG360VR)** — a hardware project to
  drive the goggles from a normal DisplayPort source. Its result is the same
  root cause found above: **a passive USB-C→DP adapter does NOT work**, and after
  sniffing the USB-PD traffic with a Google **"Twinkie"** (Chromium-EC) probe the
  conclusion is *"USB-PD handshake is definitely necessary to get a display
  connection."* Their solution is an **active board with an STM32F072** (to perform
  the DP-alt-mode sink PD handshake) **plus an SN75DP119 DisplayPort redriver** —
  i.e. the monitor use-case needs active PD hardware, not just firmware patches.
- **[strfry gist](https://gist.github.com/strfry/d4097ec4167054c86826828a90019dbe)**
  — documents the correct `\x03\x09GoToDload` DFU-entry command (opcode `0x09`,
  unpadded), that the DFU device stays at USB `1004:6374`, and a `dfu-util` flashing
  recipe. Applied here in `go_dload.py` and `firmware/README.md` §4d.

**Bottom line:** using the LG 360 VR as a Mac monitor is not achievable in software
alone. It requires an active USB-C PD interposer (PD controller doing the DP-alt-mode
sink handshake + a DP redriver), exactly the direction strfry's hardware takes.
