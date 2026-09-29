# Flashing the LG 360 VR (LGR100AT) firmware

> **Read `README.md` §4f first.** Flashing **does not work on macOS** with dfu-util
> 0.11: the LGE download bootloader exposes no ST memory-layout string
> (`name="UNKNOWN"`), so `dfu-util -D` aborts with *"Failed to parse memory
> layout"*, and a raw DfuSe `Set-Address-Pointer` (0x21) **STALLs** over the
> macOS/libusb path. Use a **Linux or Windows** host instead.
>
> **Brick warning:** single, irreplaceable device. Only ever write the app region
> (`elem2 @ 0x08020000`). Never overwrite `elem0` (bootloader `@0x08000000`) — it
> keeps the `GoToDload` recovery path alive. Even a "successful" flash will very
> likely **not** produce a Mac display: the DP/USB-PD handshake lives in the
> Analogix ANX chip, not in this firmware (see README §4e). Flash only to prove
> the write path / test app-region patches.

---

## 0. Prerequisites (all hosts)

- `dfu-util` ≥ 0.9 and `dfu-suffix` (ships with dfu-util).
- The stock firmware `LGR100AT-00-V10d-310-XX-MAY-02-2016+0.dfu`
  (from `LG 360 VR Manager.apk` → `assets/…`, or the strfry gist).
- Optionally a prepared patch from this repo, e.g. `patched_goalA_noreset.dfu`
  (differs from stock in **3 bytes in elem2** only).

Sanity-check your stock image against the known-good elements:

```bash
# our extracted elements are byte-identical (SHA-256) to strfry's gist elements
sha256sum firmware/elements/elem_08000000.bin \
          firmware/elements/elem_08010000.bin \
          firmware/elements/elem_08020000.bin
```

---

## 0b. One-shot preparation (recommended)

These scripts locate/verify the stock `.dfu`, **regenerate the patched images
deterministically**, build the plain-DFU `myfw_*` fallback artifacts, verify every
SHA-256 against `CHECKSUMS.txt`, and print the exact flash commands. All
**non-destructive** — nothing is sent to the device.

```bash
# Linux / macOS
firmware/tools/prepare_flash.sh [/path/to/stock.dfu] [outdir]   # default outdir: firmware/out
```

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File firmware\tools\prepare_flash.ps1 -Stock C:\path\stock.dfu
```

The patches are fully reproducible from stock (verified byte-perfect vs
`CHECKSUMS.txt`):

| image | element | rel. offset | bytes (old → new) | `dfuse.py patch` args |
|-------|---------|-------------|-------------------|-----------------------|
| `patched_goalA_noreset.dfu`    | `0x08020000` | `0x15AF9` | `f053f8` → `bf00bf` | `… 08020000 15AF9 bf00bf` |
| `patched_goalA_startvideo.dfu` | `0x08020000` | `0x15B08` | `acd5` → `00bf`     | `… 08020000 15B08 00bf`   |

CRC is refreshed automatically by `tools/dfuse.py patch`. All patches touch
**only elem2**; elem0 (bootloader) is never modified.

---

## 1. Enter DFU / download mode

The headset boot-loops (~1.4 s). You must send the HID `GoToDload` command into a
narrow window, so **hammer it** until the HID device tears down.

- **Cross-platform (this repo):**
  ```bash
  DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 go_dload.py   # macOS lib path; drop on Linux
  ```
  (`go_dload.py` repeatedly sends the 11-byte report `03 09 47 6F 54 6F 44 6C 6F 61 64`
  = opcode `0x09` + `"GoToDload"`, unpadded.)

- **Linux one-liner (if hidraw is stable enough):**
  ```bash
  echo -en "\x03\x09GoToDload" > /dev/hidraw0
  ```

- **Windows:** use `testgui.exe` (HIDAPI testapi) from the strfry gist — put the
  hex `0x3 0x9 0x47 0x6f 0x54 0x6f 0x44 0x6c 0x6f 0x61 0x64` (length 11) into the
  *Output Report* field and click *Send Output Report*.

Verify the DFU device appears (unfiltered — the USB ID is **`1004:6374`**, not
`0483:df11`):

```bash
dfu-util -l          # expect: Found DFU: [1004:6374] ... DFU version 011a
```

---

## 2A. Linux — the simple path (try this first)

On Linux, dfu-util usually reads the layout fine and can flash the DfuSe `.dfu`
directly, honoring the per-element addresses inside the file:

```bash
# round-trip proof FIRST: flash the UNMODIFIED stock image
dfu-util -d 1004:6374 -a 0 -D LGR100AT-00-V10d-310-XX-MAY-02-2016+0.dfu
```

If dfu-util still complains about the memory layout, fall back to the gist's
plain-DFU recipe:

```bash
cp LGR100AT-00-V10d-310-XX-MAY-02-2016+0.dfu myfw.dfu
dfu-suffix -D myfw.dfu                          # strip trailing 16-byte suffix
dd if=myfw.dfu of=myfw bs=1 skip=285            # strip 285-byte DfuSe header
dfu-suffix -a myfw                              # re-add plain suffix for dfu-util
dfu-util -d 1004:6374 -a 0 -D myfw              # plain download
```

Power-cycle (unplug/replug). The device should boot to normal HID
(`LGE Custom Human interface`). **Only after** a clean round-trip, repeat with
`patched_goalA_noreset.dfu` (prepare it the same way if using the plain recipe).

## 2B. Windows

1. Install the WinUSB driver with **[Zadig](https://zadig.akeo.ie/)**: select
   *LGE Download Firmware Update*, install *WinUSB*.
2. Use a dfu-util release from <http://dfu-util.sourceforge.net/releases/>.
3. Same commands as §2A.

## 2C. App-region-only DfuSe flasher (advanced / any host where addressing works)

If dfu-util cannot flash but the device **does** honor DfuSe `Set-Address-Pointer`
on your host, use the guarded flasher in this repo. It writes **only** elem2 and
**refuses any address < 0x08020000**, so the bootloader/recovery is never touched:

```bash
# extract patched elem2 payload (or use firmware/elements/elem_08020000.bin for a round-trip)
python3 firmware/tools/dfuse_flash_app.py firmware/elements/elem_08020000.bin
```

> ⚠️ On macOS this STALLs on the first `Set-Address` — that is the known blocker.
> Only use this route on a host where a plain read-back at `0x08020000` succeeds.

---

## 3. Recovery

Nothing here writes `elem0`, so `GoToDload` always remains available.

- If the device gets stuck in DFU/download mode, **physically unplug and replug**
  — with an intact bootloader it boots the (last-flashed) app firmware.
- To restore stock: repeat §2 with the unmodified
  `LGR100AT-00-V10d-310-XX-MAY-02-2016+0.dfu`.

---

## 4. Reality check

The firmware contains **zero** USB-PD / Type-C / DisplayPort-alt-mode code
(README §4e). Flashing patches can change HID/sensor/boot behavior and how/when
the video chips (TC358870, ANX7401/ANX7737) are powered, but the PD contract that
makes a host negotiate DP is handled by the Analogix chip. A real "use it as a
monitor" outcome most likely needs strfry's **active PD hardware**
(STM32 + DP redriver), not a firmware flash.
