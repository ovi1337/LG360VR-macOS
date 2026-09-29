#!/usr/bin/env bash
#
# prepare_flash.sh — build & verify everything needed to flash the LG 360 VR
# (LGR100AT) on a Linux (or macOS) host. NON-DESTRUCTIVE: only reads the stock
# firmware and writes prepared artifacts into an output dir. Nothing is sent to
# the device. See firmware/FLASHING.md before flashing.
#
# Usage:
#   firmware/tools/prepare_flash.sh [/path/to/stock.dfu] [outdir]
#
# If no stock .dfu is given it looks for firmware/LGR100AT-*.dfu, and if that's
# missing it tries firmware/tools/extract_from_apk.sh.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
FW="$(cd "$HERE/.." && pwd)"                 # firmware/
STOCK="${1:-}"
OUT="${2:-$FW/out}"

# Known-good SHA-256 (see firmware/CHECKSUMS.txt). Stock elements are also
# byte-identical to strfry's gist known-good elements.
STOCK_SHA="ffa4994fad7adb32fd0b4d85744d619fbf8d1b213929ad7e4a52382457cd43db"
NORESET_SHA="9fd04198aab9983c367d6e4ddc52679a9d7a1be20f1a8786cc2db4c011abd921"
STARTVID_SHA="acae6c17c2c3c11a816c82ec7ba2df4dc60be688c5ff6d7253862322800d3e63"

sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
        else shasum -a256 "$1" | cut -d' ' -f1; fi; }
need() { command -v "$1" >/dev/null || { echo "MISSING TOOL: $1" >&2; exit 1; }; }

need python3; need dd; need dfu-suffix

# --- locate stock firmware --------------------------------------------------
if [ -z "$STOCK" ]; then
  STOCK="$(ls "$FW"/LGR100AT-*.dfu 2>/dev/null | head -1 || true)"
fi
if [ -z "$STOCK" ] || [ ! -f "$STOCK" ]; then
  echo "Stock .dfu not found — trying to extract from APK ..."
  bash "$HERE/extract_from_apk.sh" || true
  STOCK="$(ls "$FW"/LGR100AT-*.dfu 2>/dev/null | head -1 || true)"
fi
[ -f "$STOCK" ] || { echo "ERROR: no stock .dfu. Get it from LG 360 VR Manager.apk (assets/) or strfry's gist." >&2; exit 1; }

echo "== stock: $STOCK"
got="$(sha "$STOCK")"
if [ "$got" = "$STOCK_SHA" ]; then echo "   SHA-256 OK (matches known-good) ✓"
else echo "   WARNING: SHA-256 $got != expected $STOCK_SHA (different firmware version?)"; fi

mkdir -p "$OUT"

# --- 1) regenerate the patched .dfu images (deterministic from stock) -------
echo "== building patched images (reproducible) ..."
python3 "$HERE/dfuse.py" patch "$STOCK" "$OUT/patched_goalA_noreset.dfu"    08020000 15AF9 bf00bf
python3 "$HERE/dfuse.py" patch "$STOCK" "$OUT/patched_goalA_startvideo.dfu" 08020000 15B08 00bf
verify() { local f="$1" want="$2"; local g; g="$(sha "$f")";
  [ "$g" = "$want" ] && echo "   $(basename "$f") SHA-256 OK ✓" \
                     || echo "   $(basename "$f") SHA MISMATCH: $g"; }
verify "$OUT/patched_goalA_noreset.dfu"    "$NORESET_SHA"
verify "$OUT/patched_goalA_startvideo.dfu" "$STARTVID_SHA"

# --- 2) build plain-DFU 'myfw' artifacts (gist recipe: strip header + suffix)
# Some hosts' dfu-util can flash the DfuSe .dfu directly; if not, use these.
build_myfw() {  # $1=src.dfu  $2=out
  cp "$1" "$OUT/.tmp.dfu"
  dfu-suffix -D "$OUT/.tmp.dfu" >/dev/null 2>&1 || true   # strip trailing suffix
  dd if="$OUT/.tmp.dfu" of="$2" bs=1 skip=285 status=none # strip 285-byte DfuSe header
  dfu-suffix -a "$2" >/dev/null 2>&1                       # re-add plain suffix
  rm -f "$OUT/.tmp.dfu"
}
echo "== building plain-DFU artifacts ..."
build_myfw "$STOCK"                              "$OUT/myfw_stock"
build_myfw "$OUT/patched_goalA_noreset.dfu"      "$OUT/myfw_noreset"
build_myfw "$OUT/patched_goalA_startvideo.dfu"   "$OUT/myfw_startvideo"

# --- 3) also drop the raw app element for the guarded app-only flasher ------
python3 "$HERE/dfuse.py" extract "$STOCK" "$OUT/elements" >/dev/null 2>&1 || true

echo ""
echo "== prepared in: $OUT"
ls -la "$OUT" | sed 's/^/   /'
cat <<EOF

Next steps (see firmware/FLASHING.md):
  1) Enter DFU:   python3 go_dload.py         # hammers GoToDload; verify: dfu-util -l -> 1004:6374
  2) ROUND-TRIP first (unmodified stock):
       dfu-util -d 1004:6374 -a 0 -D "$STOCK"          # Linux: usually works directly
       # if 'Failed to parse memory layout' -> use plain-DFU artifact instead:
       dfu-util -d 1004:6374 -a 0 -D "$OUT/myfw_stock"
     Power-cycle; device must boot to normal HID (LGE Custom Human interface).
  3) Only after a clean round-trip, flash a patch:
       dfu-util -d 1004:6374 -a 0 -D "$OUT/patched_goalA_noreset.dfu"   # or myfw_noreset
  Recovery: re-flash the stock image. elem0 (bootloader) is never touched.
EOF
