# LG 360 VR Tester (Android)

Native Android-App (Kotlin + Jetpack Compose), um die **LG 360 VR (LGR100AT,
USB `0x1004:0x6374`)** an einem Android-Telefon anzusteuern, zu analysieren und
als externes Display zu testen.

Android ist die vom Hersteller vorgesehene Umgebung: das Telefon liefert über
**USB-C DisplayPort-Alt-Mode** das Videosignal, das der Brille am Apple-Silicon-Mac
fehlt (siehe `../README.md`).

## Funktionen

| Tab | Inhalt |
|-----|--------|
| **Status** | Verbindungszustand, Geräte-Infos, Verbinden/Trennen, **Aktivieren** (Sleep Disable + VR App Start) |
| **Befehle** | Gesamter Firmware-Befehlssatz als Buttons (Core, Info, Proximity, Gyro, Accel, Compass) + Freitext-Kommando |
| **Log** | Live-Debug-Stream der Brille (EP 0x81), farbcodiert TX/RX/INFO/ERROR |
| **Display** | Erkannte Displays via `DisplayManager`; Testbild (Farbbalken/Gitter/Fadenkreuz/Weiß) auf das externe Display (Brille) ausgeben |
| **USB** | Vollständige USB-Deskriptoren: Interfaces, Endpoints, Klassen, MaxPacketSize |

## Architektur

- `usb/Lg360Protocol.kt` — Befehls-Framing `[0x03][0x0C]+ASCII`, Kommando-Vokabular.
- `usb/UsbController.kt` — USB-Host: Permission, `claimInterface(force=true)`, Senden
  (Interrupt-OUT, Fallback HID SET_REPORT), Lese-Thread (`UsbRequest`) für den Debug-Stream.
- `usb/UsbDescribe.kt` — Deskriptor-Snapshot für den USB-Tab.
- `display/DisplayMonitor.kt` — `DisplayManager`-Listener, Präsentations-Displays.
- `display/VrPresentation.kt` — `Presentation` mit Canvas-Testbildern.
- `MainActivity.kt` — Compose-UI.

## Bauen

Voraussetzungen: Android Studio (JBR 21) + SDK android-35. Kein separates Gradle nötig.

```bash
cd android
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Das mitgelieferte `install.sh` baut und installiert auf ein per USB verbundenes Telefon:

```bash
./install.sh
```

## Nutzung

1. App aufs Telefon installieren, Telefon bleibt per WLAN-`adb` oder separat erreichbar.
2. **Brille per USB-C ans Telefon** (die App startet dank `USB_DEVICE_ATTACHED`-Filter
   automatisch und fragt die USB-Berechtigung ab).
3. Tab **Status → Aktivieren**. Im **Log** die Boot-/Antwortmeldungen beobachten.
4. Tritt die Brille in DP-Alt-Mode ein, erscheint sie im Tab **Display** als
   „EXTERN" → **Anzeigen** rendert das Testbild auf die Brille.

> Erscheint die Brille nur als kurzzeitig blinkendes Gerät und **nie** unter
> „Display", kommt (wie am Mac) kein Videopfad zustande — dann liefert der Log die
> Firmware-Meldungen zur Diagnose.

## Hinweise

- `minSdk 26`, `targetSdk 35`, getestet gegen Galaxy S25 Ultra (DP-Alt-Mode/DeX-fähig).
- Die App braucht keine Laufzeit-Permissions außer der USB-Gerätefreigabe (Dialog).
- Endpoints werden dynamisch gewählt (erstes Interface mit Interrupt IN+OUT, HID bevorzugt).

## Ergebnis am Gerät (Galaxy S25 Ultra, 28.09.2026)

Realer Test mit angeschlossener Brille über die App:

- **USB-HID-Kommunikation: ✅** Gerät erkannt als „LGE Custom Human interface",
  VID/PID `0x1004/0x6374`, Serial `00000000001A`, Interface 0 (HID), EP `0x81` IN / `0x01` OUT.
- **Aktivierung: ✅** Jeder `VR App Start` wird bestätigt (`do_VRAppStart: 1`), dazwischen
  Heartbeats (`HB`). Auf Android bleibt die Brille **stabil verbunden** — kein Boot-Loop
  wie am Apple-Silicon-Mac.
- **Externes Display / Video: ❌** Über die gesamte Aktivierung `type=EXTERNAL = 0`
  (OS-`dumpsys display`) und „Kein externes Display erkannt" in der App. Auch das
  S25 Ultra baut **keinen DP-Alt-Mode-Videopfad** zur Brille auf.

**Fazit:** Die Ansteuerung/Analyse funktioniert auf Android sauber und stabiler als am Mac,
aber die Brille annonciert auch hier kein DisplayPort-Sink. Der originale LG-360-VR-Betrieb
setzte spezifische LG-Telefone (G5/G6/V20 …) mit LGs „Friends"-Software voraus, die den
DP-Alt-Mode gerätespezifisch ansteuerte — ein generisches USB-C-DP-Telefon genügt nicht.

