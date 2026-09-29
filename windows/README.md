# LG 360 VR Tester (Windows)

Native Windows-App (**C# / .NET 8 / WinForms**), um die **LG 360 VR
(LGR100AT, USB `0x1004:0x6374`)** an einem Windows-PC anzusteuern, zu analysieren
und als Display zu testen. Funktions- und Layout-Gegenstück zur Android-App
(`../android`).

Anders als das ursprüngliche Referenzprojekt braucht diese App **kein Zadig/WinUSB**:
Der Zugriff läuft über den Standard-Windows-HID-Stack via **HidSharp** — genau wie
der macOS-hidapi-Ansatz.

## Funktionen

| Tab | Inhalt |
|-----|--------|
| **Status** | Verbindungszustand, Geräte-Infos, Verbinden/Trennen, **Aktivieren** (Sleep Disable + VR App Start) |
| **Befehle** | Gesamter Firmware-Befehlssatz als Buttons (Core, Info, Proximity, Gyro, Accel, Compass) + Freitext-Kommando |
| **Log** | Live-Debug-Stream der Brille (Input-Reports), farbcodiert TX/RX/INFO/ERROR |
| **Display** | Alle Bildschirme via `Screen.AllScreens`; Testbild (Farbbalken/Gitter/Fadenkreuz/Weiß) auf einen zweiten Monitor (Brille) ausgeben |
| **USB** | HID-Deskriptor-Infos: VID/PID, Report-Längen, Report-IDs (Input/Output/Feature) |

## Voraussetzungen

- Windows 10/11 (x64)
- **.NET 8 SDK** — <https://dotnet.microsoft.com/download/dotnet/8.0>
- Optional Visual Studio 2022 (öffne `LG360VR.sln`)

## Bauen & Starten

Per PowerShell:

```powershell
cd windows
.\build.ps1 -Run          # baut Debug und startet die App
```

Oder direkt mit der CLI:

```powershell
cd windows
dotnet run --project LG360VRTester/LG360VRTester.csproj
```

Eigenständige EXE (ohne installiertes .NET, single-file):

```powershell
.\build.ps1 -Publish      # -> publish\LG360VRTester.exe
```

## Nutzung

1. Brille per USB-C an den PC. Idealerweise an einen **USB-C-Port mit DisplayPort-Alt-Mode**
   (Thunderbolt/USB-C-Video), falls echtes Bild getestet werden soll.
2. App starten. Der Status-Tab zeigt „Verbunden" mit VID/PID `0x1004/0x6374`.
   (Die App sucht das Gerät automatisch alle ~1,5 s.)
3. **Aktivieren** drücken. Im **Log** die Boot-/Antwortmeldungen beobachten
   (`do_VRAppStart: 1`, Heartbeats `HB`).
4. Erscheint die Brille als zweiter Monitor (Tab **Display** zeigt einen `EXTERN`-Eintrag),
   Testbild wählen und **Auf externem Display anzeigen** → Bild auf die Brille (Esc schließt es).

> Bleibt es bei nur einem Bildschirm, baut auch Windows keinen DP-Alt-Mode-Videopfad
> zur Brille auf (gleiche Grenze wie am Mac/Android). Der Log dient dann der Diagnose.

## Architektur

- `Lg360Protocol.cs` — Befehls-Framing `[0x03][0x0C]+ASCII`, Kommando-Vokabular.
  (Ausnahme: `GoToDload` nutzt Opcode `0x09`, ungepolstert — siehe `firmware/README.md` §4d.)
- `Lg360Device.cs` — HidSharp: Suchen/Öffnen, Senden (Output-Report), Lese-Thread
  für den Debug-Stream, Events `Log` / `ConnectionChanged`.
- `PatternForm.cs` — randloses Vollbild-Testbild (GDI+) auf einem gewählten `Screen`.
- `MainForm.cs` — WinForms-UI mit 5 Tabs.
- `Program.cs` — Einstiegspunkt.

## Abhängigkeit

- [HidSharp](https://www.nuget.org/packages/HidSharp) 2.1.0 (MIT) — HID-Zugriff.
