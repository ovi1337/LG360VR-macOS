#!/usr/bin/env bash
# Re-extract the stock .dfu firmware from the LG 360 VR Manager APK.
# Non-destructive: only reads the APK and copies the .dfu out.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
APK="$HERE/../apks/LG+360+VR+Manager_5.0.29_APKPure.apk"
OUT="$HERE"
if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  exit 1
fi
TMP="$(mktemp -d)"
unzip -o "$APK" 'assets/LGR100AT*.dfu' -d "$TMP" >/dev/null
cp "$TMP"/assets/LGR100AT*.dfu "$OUT"/
rm -rf "$TMP"
echo "Extracted stock firmware to $OUT/"
ls -la "$OUT"/LGR100AT*.dfu
