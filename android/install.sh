#!/usr/bin/env bash
# Build the debug APK and install it on a connected Android device.
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

echo "==> Building debug APK…"
./gradlew :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

DEVICES=$("$ADB" devices | grep -cw "device" || true)
if [ "$DEVICES" -eq 0 ]; then
  echo "No device connected. Enable USB debugging and run: $ADB devices" >&2
  exit 1
fi

echo "==> Installing $APK …"
"$ADB" install -r "$APK"
echo "==> Launching…"
"$ADB" shell am start -n com.lg360vr.tester/.MainActivity
echo "Done."
