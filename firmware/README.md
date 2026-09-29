# LG 360 VR (LGR100AT) — Firmware-Analyse & Umbau-Machbarkeit

> Ziel: Klären, ob die Firmware der LG 360 VR so angepasst werden kann, dass
> sich die Brille als Display/Monitor (oder wenigstens als standalone
> Bildausgabe) nutzen lässt — statt nur im proprietären LG-„VR App"-Modus.
>
> **Status: Firmware-Analyse abgeschlossen; Watchdog gepatcht (nicht geflasht).**
> **Live-Test ergab neuen Hauptblocker (siehe 4c):** Der Mac handelt selbst bei
> aktiver Videopipeline **keinen DP-Alt-Mode** mit der Brille aus — die Brille
> bewirbt DP gar nicht auf USB-C-PD-Ebene. Zudem war **kein DFU-Modus**
> erreichbar (kein Recovery-Pfad) → es wurde **bewusst NICHTS geflasht**.

---

## 1. Kernaussage

Ein Firmware-Umbau ist **technisch plausibel** — deutlich zugänglicher als
erwartet, weil die Firmware **unverschlüsselt, unsigniert (sehr wahrscheinlich)
und über Standard-STM32-DFU flashbar** ist. Der eigentliche Aufwand liegt im
Reverse-Engineering (Ghidra) der ARM-Cortex-M-Logik, um den „Warte-auf-HDMI →
Reset"-Watchdog zu neutralisieren bzw. die DisplayPort-Sink-Initialisierung
vorzuziehen.

---

## 2. Belege (verifiziert)

| Aspekt | Befund | Beleg |
|--------|--------|-------|
| Datei | `LGR100AT-00-V10d-310-XX-MAY-02-2016+0.dfu` (159.716 B) | aus `apks/LG+360+VR+Manager_5.0.29_APKPure.apk`, `assets/` |
| Format | **DfuSe v1** (STMicroelectronics), 1 Target „ST...", 3 Elemente | `tools/dfuse.py info` |
| Bootloader-IDs | DFU-Suffix **VID 0x0483 / PID 0xDF11** | Standard-STM32-DFU-Bootloader |
| Integrität | **nur CRC32-Suffix**, `crc = (~zlib.crc32(body)) & 0xFFFFFFFF` | verifiziert: stock-CRC `0xB9EB4887` stimmt; No-Op-Patch erzeugt bit-identische Datei |
| Verschlüsselung | **keine** — Klartext-Symbole/Strings lesbar | `do_VRAppStart`, `ANX7401_power_on`, `BSP_TC358870XBG_Start_Video`, `LGE9911IoT` |
| Signatur | **keine** Krypto-Strings gefunden (rsa/sha/ecdsa/aes/hmac/sign/verify/cert) | grep über alle Elemente = leer |
| MCU | **STM32** (ARM Cortex-M, Thumb-2) | kohärente Vektortabelle + Reset-Trampolin disassembliert sauber |
| Reflash-Weg | HID-Kommando **„GoToDload"** (Opcode **0x09**, ungepolstert) → Reboot als DFU-Gerät **`1004:6374`** („LGE Download Firmware Update") → `dfu-util` | Quelle: strfry-Gist (s. 4d); `do_GoToDload`-Symbol vorhanden; `dfu-util` installiert. **`0483:DF11` ist nur die Suffix-ID der *Datei*, NICHT die Laufzeit-USB-ID.** |

### Flash-Layout (3 Elemente)

| Element | Ladeadresse | Größe | Datei-Offset im .dfu | Inhalt |
|---------|-------------|-------|----------------------|--------|
| elem0 | `0x08000000` | 3.096 B | `0x125` | Bootloader / Vektoren |
| elem1 | `0x08010000` | 21.912 B | `0xD45` | DLOAD / 2nd-stage |
| **elem2** | `0x08020000` | 134.383 B | `0x62E5` | **Haupt-App** (gesamte relevante Logik) |

Vektortabelle von elem2 (Basis `0x08020000`):
`Initial_SP = 0x20001100`, `Reset = 0x0803FC39`, `HardFault = 0x08031F9D`.
Der Reset-Handler @ `0x0803FC38` disassembliert als sauberes Trampolin
(`ldr r0,[pc,#4]; blx r0; ldr r0,[pc,#4]; bx r0`) → Image ist kohärent.

### Interessante Strings in elem2 (absolute Adressen)

| String | Adresse |
|--------|---------|
| `BSP_TC358870XBG_Init_Reg : Waiting for HDMI signal reg=(%x)` | `0x0803602C` |
| `failed ~!! stop to wait HDMI signal` | `0x0803606C` |
| `HDMI signals arrived~!!` | `0x08036094` |
| `BSP_TC358870XBG_Start_Video` | `0x08036010` |
| `ANX7401_power_on` | `0x08038DC8` |
| `do_VRAppStart` | `0x080387B0` |
| `do_GoToDload` / `%s : Go to Dload Mode` | `0x080374B4` / `0x080374C4` |

> Hinweis: Die Code-Stellen, die diese Strings referenzieren, sind **nicht**
> per einfachem Byte-Scan auffindbar (Adressen werden per `MOVW/MOVT`-Immediates
> bzw. PC-relativen Literal-Pools geladen). Sie wurden per **Ghidra** aufgelöst —
> siehe Abschnitt 4b (Watchdog lokalisiert & gepatcht).

---

## 3. Video-Signalkette (Hardware-Kontext)

```
USB-C  ──(DP-AltMode?)──►  ANX7401 (DP-RX)  ──►  TC358870 (HDMI/DP → MIPI-DSI)  ──►  Panels
                              ▲                       ▲
                        ANX7737 (Mux/Redriver)   interner Testbild-Generator
```
Weitere Chips: SM5306 (Backlight), NCP6924 (PMIC).

**Grundproblem (bestätigt auf Mac + Galaxy S25 Ultra):** Beim Einstecken meldet
die Brille nur USB-HID; ANX7401 ist unbestromt → bei der PD-Aushandlung KEIN
DisplayPort-AltMode. Erst nach HID-`VR App Start` bestromt die Firmware
ANX7401 + TC358870 und wartet ~0,5 s auf HDMI/DP-Video; da der Host nie
DP-AltMode betreten hat, kommt keins → Reset (Henne-Ei-/Timing-Deadlock).

---

## 4. Zwei Umbau-Ziele

### Ziel A — Panels standalone ansteuern (geringeres Risiko)
Den „warte-auf-HDMI → Reset"-Watchdog neutralisieren, damit die Brille nicht
boot-loopt, Pipeline/Backlight oben bleiben und der **interne Testbild-Generator
des TC358870** dauerhaft läuft → Panels zeigen ein Bild **ohne Host-Video**.
Beweist die Displays; nutzbar als feste Bildausgabe (keine beliebigen Inhalte).

### Ziel B — echter Plug-&-Play-Monitor (hoher Gewinn, unsicherer)
Firmware so patchen, dass **ANX7401 + DP-Sink bereits beim Einstecken** (vor
`VR App Start`) hochfahren und der Reset entfällt → ein normaler Host handelt
DP-AltMode aus und treibt die Brille als **Standard-Monitor**.
Offene Frage: ob ANX7401 hier auch die USB-C-CC/PD-Sink-Aushandlung übernimmt.

---

## 4b. Watchdog lokalisiert & gepatcht (Ghidra) ✅

Per Ghidra 12 (headless, ARM Cortex-M/Thumb, Basis `0x08020000`) wurde der
Watchdog vollständig aufgelöst. Scripts: `tools/ghidra_find_watchdog.java`,
`tools/ghidra_dump_watchdog.java`, `tools/ghidra_verify_patch.java`.

### Funktions-Landkarte

| Funktion | Adresse | Rolle |
|----------|---------|-------|
| `FUN_08034fba` | `0x08034FBA` | Aufrufer (ruft Watchdog @ `0x08034FDE`) |
| **`FUN_08035a42`** | **`0x08035A42`** | **HDMI-Warte-Watchdog** (die relevante Funktion) |
| `FUN_08035ba2` | `0x08035BA2` | „give up"-Pfad → `FUN_0802d64c(10)` (Reset/Neustart) |
| `FUN_08031196` | `0x08031196` | I2C-Read (TC358870-Register) |
| `FUN_080311d6` | `0x080311D6` | I2C-Write → **Start Video** |
| `FUN_080305bc` | `0x080305BC` | Delay |

### Dekompilierte Watchdog-Logik (`FUN_08035a42`)

```c
r7 = 2001; r9 = 0x8520;              // Poll-Register = TC358870 HDMI-Status
counter = 0;
while (true) {
    status = i2c_read(0x1e, 0x8520, ...);   // FUN_08031196
    delay(3);                                // ~3 ms
    if (status != last) log("Waiting for HDMI signal reg=(%x)", status);
    if (++counter >= 2001) {                 // TIMEOUT (~6 s)
        log("failed ~!! stop to wait HDMI signal");
        FUN_08035ba2();                      // <-- RESET (Boot-Loop-Ursache)
        return;
    }
    if (status & 0x80) {                     // Bit7 = HDMI present
        log("HDMI signals arrived~!!");
        start_video();                       // FUN_080311d6 (2×)
        return;                              // SUCCESS
    }
}
```

Der entscheidende Test steht bei `0x08035B08`:
`bpl 0x08035a64` (Bytes `acd5`) — springt zurück in die Warteschleife, solange
Bit 7 (HDMI-present) **nicht** gesetzt ist. Der Reset-Aufruf steht bei
`0x08035AF8`: `bl 0x08035ba2` (Bytes `00f053f8`).

### Zwei fertige, verifizierte Patches (elem2 @ `0x08020000`)

Beide sind erzeugt, CRC-korrekt und per Ghidra-Disassembly gegengeprüft.
**Es wurde nichts geflasht.**

| Datei | Stelle | rel. Offset | Bytes (alt → neu) | Wirkung |
|-------|--------|-------------|-------------------|---------|
| `patched_goalA_noreset.dfu` | `0x08035AF8` `bl`→`nop;nop` | `0x15AF8` | `00f053f8` → `00bf00bf` | **Sicherste Variante:** LG-Verhalten unverändert; bei HDMI-Timeout **kein Reset** mehr (sauberes Return). |
| `patched_goalA_startvideo.dfu` | `0x08035B08` `bpl`→`nop` | `0x15B08` | `acd5` → `00bf` | **Standalone:** startet die Videopipeline sofort (unabhängig von HDMI), Reset-Pfad nie erreichbar. |

Reproduzieren (nicht-destruktiv):

```bash
python3 - <<'PY'
import importlib.util
s=importlib.util.spec_from_file_location("dfuse","tools/dfuse.py")
m=importlib.util.module_from_spec(s); s.loader.exec_module(m)
STOCK="LGR100AT-00-V10d-310-XX-MAY-02-2016+0.dfu"
m.cmd_patch(STOCK,"patched_goalA_noreset.dfu",   "0x08020000","0x15AF8","00bf00bf")
m.cmd_patch(STOCK,"patched_goalA_startvideo.dfu","0x08020000","0x15B08","00bf")
PY
```

> **Offener Vorbehalt (Ziel A):** Das Entfernen des Resets ist hart bewiesen.
> Ob die Panels *tatsächlich ein Bild* zeigen, hängt davon ab, ob der TC358870
> ohne gültigen HDMI-Eingang einen validen MIPI-DSI-Ausgang liefert. Falls nicht,
> ist als Folgeschritt zusätzlich der **interne Testbild-Generator des TC358870**
> per I2C-Register zu aktivieren (separate Schreibsequenz, nach dem ersten
> erfolgreichen Flash empirisch zu ermitteln).

## 4c. Live-Test am Mac + DFU-Blocker (diese Session)

Gerät angeschlossen, alles **nicht-destruktiv** über HID getestet (kein Flash).

**GoToDload-Handler vollständig reverse-engineert.** Die Dispatch-Tabelle
@ `0x08040940` enthält genau einen Eintrag `{name→0x0803EE6C "GoToDload",
handler→0x0803747D}`. Der Handler @ `0x0803747C`:

```
push {r4,lr}; adr r1,do_GoToDload; adr r0,...; bl log
ldr r4,[0x080381e8]            ; Objekt-Pointer
adr r1,"Go to Dload Mode"; bl 0x08034a64   ; Antwort/State
mov r0,r4; bl 0x08030910; ...  ; bl 0x0802d9e8  ; I2C/Comm-Write
bl 0x0802f556                  ; HW-Register-Manipulation (setzt Bit23)
movs r0,#0; pop {r4,pc}
```

Es ist **kein** simples „Flag setzen + NVIC_SystemReset". Der Handler schreibt
Peripherie-Register. In der Praxis erschien **nie** ein `0483:df11`-DFU-Gerät —
**aber** genau das war der Fehler (siehe 4d): (1) das Kommando wurde mit dem
falschen Opcode `0x0C` statt `0x09` gesendet und (2) es wurde nach der falschen
USB-ID `0483:df11` gesucht statt nach dem echten Laufzeit-DFU-Gerät `1004:6374`.
Download läuft **LGE-custom** (elem1 „LGE DownLoad Firmware Update",
`DFU_CMD_REBOOT"`); das Gerät bleibt dabei unter VID:PID `1004:6374`.

**`VR App Start` bestätigt live funktionsfähig** (HID-Report `03 0C "VR App
Start"`). Firmware-Log:
`ANX7401_power_on >> power on` → `BSP_TC358870XBG_Init_Reg >> HDMI registers set`
→ `BSP_SM5306_On: turn on B/L` → `do_VRAppStart: 1` → `HB`. Die komplette
DP-Videopipeline (ANX7401 USB-C→HDMI-Bridge + TC358870 HDMI→MIPI + Backlight)
fährt hoch.

**Entscheidender empirischer Befund:** Auch bei **kontinuierlichem Re-Arming**
von `VR App Start` (~12×/20 s, Pipeline durchgehend aktiv) handelt der Mac
**nie** ein DP-Display aus (`system_profiler SPDisplaysDataType` zeigt nur das
Built-in). `ioreg` bestätigt: Die Brille enumeriert **nur als Full-Speed-HID-
Gerät** — **kein USB-Billboard, kein DP-Alt-Mode-Knoten**. Ein USB-C-Gerät mit
noch-nicht-ausgehandeltem DP-Alt-Mode würde normalerweise ein Billboard-Device
präsentieren; das fehlt komplett. Der Mac hat volle DP-Alt-Mode-Infrastruktur
(AppleTypeCRetimer/Phy/HPM) — die Brille **bewirbt DP-Alt-Mode aber gar nicht**
auf USB-C-PD-Ebene.

**Konsequenz für Ziel B:** Der Blocker ist nicht (nur) der Watchdog-Reset,
sondern das **Fehlen der DP-Alt-Mode-Aushandlung auf PD-Ebene**. Selbst ein
erfolgreicher `noreset`-Patch würde damit sehr wahrscheinlich **kein**
Mac-Display erzeugen — die ANX7401 müsste zusätzlich als DP-Sink das
DisplayPort-SVID (`0xFF01`) via PD bewerben, was die Firmware außerhalb ihrer
Telefon-Erkennungslogik offenbar nicht tut. Das liegt unterhalb der per
macOS-Software erreichbaren Ebene (USB-C-PD/CC-Handshake, ANX7401-Konfig).

**Status:** Kein DFU-Modus erreichbar → **kein Recovery-Pfad bewiesen** → es
wurde **bewusst nichts geflasht** (Bricking des Einzelgeräts wäre
irreversibel). Nach den `VR App Start`-Tests flackert das Gerät in der
VR-Boot-Loop; **einmal ab-/anstecken** stellt den stabilen Idle-HID-Zustand
wieder her.

### Proximity-/Aufsetzerkennungs-Test (mit Nutzer-Freigabe)

Nutzer hat den Wear-/Proximity-Sensor mechanisch auf „aufgesetzt" präpariert.
Test mit `Proximity On` + `Sleep Disable` + `VR App Start`:

- `do_ProximityOn : start` bestätigt; Sensor liefert **live Registerdaten**
  (Report-ID 0x03, wechselnde Werte) → Präparation wird ausgelesen.
- Reset-Zyklus **unverändert ~1,42 s** (16 Resets / 22 s), identisch zum
  Zustand ohne Proximity → **der Reset ist NICHT proximity-/sleep-getrieben.**
- **Kein** DP-Display am Mac (paralleles `SPDisplaysDataType`-Polling, 25 s).

**Neue Erkenntnis — zwei getrennte Reset-Pfade:** Der beobachtete ~1,4-s-Reset
ist deutlich schneller als der per Ghidra gefundene **6-s**-HDMI-Watchdog
(`FUN_08035a42`, 2000 I²C-Polls von TC358870-Reg `0x8520`). Der Reset erfolgt
unmittelbar nach „HDMI registers set", ohne die 6-s-Wartezeit. → Es existiert
ein **früherer, schnellerer Fail-Pfad** (sehr wahrscheinlich ANX7401
„keine-DP-Quelle" bzw. TC358870 „no valid input"), der VOR dem 6-s-Watchdog
resettet. **Folge:** Der vorbereitete `patched_goalA_noreset.dfu` zielt nur auf
den 6-s-Watchdog und würde diesen schnellen Reset-Pfad vermutlich **nicht**
abfangen — vor einem Flash müsste dieser frühe Pfad zusätzlich per Ghidra
lokalisiert und mitgepatcht werden.

**Gesamtfazit unverändert:** Selbst mit korrekt gepatchten Reset-Pfaden bliebe
Blocker Nr. 1 (Mac handelt keinen DP-Alt-Mode aus, Brille bewirbt DP nicht auf
PD-Ebene). Aufsetzerkennung ändert am Ergebnis nichts.

## 4d. Externe Bestätigung & Protokoll-Korrektur (strfry)

> **✅ LIVE BESTÄTIGT (diese Session): DFU-Modus erreicht — Recovery-Pfad bewiesen.**
> Mit `go_dload.py` (Opcode `0x09`, ungepolstert) **wiederholt** im Boot-Loop-
> Fenster gesendet (~33 Sends/25 s), schaltet die Brille in den DFU-Modus. Der
> Einzel-Send genügte NICHT — das Kommando muss im richtigen ~1,4-s-Fenster
> mehrfach ankommen (die HID-Schnittstelle wird beim Umschalten abgebaut →
> `IOHIDDeviceSetReport … device not responding` ist genau das Erfolgssignal).
> Danach:
> ```
> Found DFU: [1004:6374] ver=0200, devnum=1, cfg=1, intf=0, alt=0,
>            name="UNKNOWN", serial="00000000001A"   (dfu-util -l, UNGEFILTERT)
> DFU version 011a  (= DfuSe/ST-Erweiterung), transfer size 1024, dfuIDLE
> ```
> **Damit ist erstmals ein Recovery-Pfad real nachgewiesen** — die Grundlage,
> um einen Flash-Versuch überhaupt verantworten zu können.
>
> **Backup-Grenze:** Der Upload (Auslesen) funktioniert prinzipiell (`CanUpload`),
> aber diese LGE-DfuSe-Variante liefert keinen adressierbaren Voll-Dump: der
> Memory-Layout-String (String-Descriptor 6) ist nicht lesbar, `Set Address
> Pointer` (0x21) wird zwar mit `dfuDNLOAD-IDLE` quittiert, das anschließende
> `UPLOAD` liest aber trotzdem ab `0x08000000` (bzw. wiederholt sich alle 16 KB).
> **Nur der erste 1-KB-Block ist zuverlässig** — und er ist **byte-identisch mit
> dem Stock-`elem_08000000.bin`**. → Die Brille fährt Stock-Firmware; das
> vorhandene `.dfu` ist ein gültiges Recovery-Image (ein separater On-Device-Dump
> ist weder möglich noch nötig). **Es wurde weiterhin NICHTS geflasht.**
> Der DFU-Modus ist flüchtig: **einmal ab-/anstecken** → wieder normaler HID-Modus.

Zwei externe Quellen von **strfry** bestätigen die Kernanalyse und korrigieren
Detailfehler:

**(1) Gist — `GoToDload`-Protokoll**
<https://gist.github.com/strfry/d4097ec4167054c86826828a90019dbe>

- Der Download-Befehl nutzt **Opcode `0x09`**, nicht `0x0C`, und wird
  **ungepolstert** (11 Bytes) gesendet:
  `03 09 47 6f 54 6f 44 6c 6f 61 64` = `\x03\x09GoToDload`.
  Linux: `echo -en "\x03\x09GoToDload" > /dev/hidraw0`.
  → Erklärt, warum das frühere `0x0C`-Framing **nie** DFU auslöste.
- Nach Annahme verschwindet das Vendor-HID und re-enumeriert als DFU-Gerät
  **„LGE Download Firmware Update"** — weiterhin USB **`1004:6374`**
  (NICHT `0483:df11`; letzteres ist nur die Suffix-Ziel-ID der `.dfu`-Datei).
  → Erklärt, warum `dfu-util -l` (gefiltert auf `0483:df11`) das Gerät „nie"
  fand. Windows: Zadig-WinUSB-Treiber für „LGE Download Firmware Update".
- Flash-Rezept (Gist): Stock-`.dfu` aus `LG 360 VR Manager.apk`
  (`assets/LGR100AT-…MAY-02-2016+0.dfu`), 285-Byte-DFU-Suffix per
  `dd … bs=1 skip=285` strippen, mit `dfu-suffix -a` neu setzen, `dfu-util` flashen.
- Umgesetzt im Repo als **`go_dload.py`** (macOS, hidapi, Opcode 0x09, ungepolstert).

**(2) Hardware-Projekt — `strfry/LG360VR`** (Twinkie-USB-PD-Analyse)
<https://github.com/strfry/LG360VR>

Unabhängige, **deckungsgleiche** Bestätigung von Blocker Nr. 1:

- Ein **passiver USB-C→DisplayPort-Adapter funktioniert NICHT.**
- Ergebnis der USB-PD-Sniffing-Analyse (mit Google **„Twinkie"**/Chromium-EC,
  Submodul `LG360VR-EC`, Branch `firmware-twinkie-9628.B`):
  **„USB-PD-Handshake ist definitiv notwendig, um eine Display-Verbindung zu
  bekommen."** — exakt der hier gefundene PD-Alt-Mode-Blocker.
- Lösungsweg von strfry: **aktive Platine** mit **STM32F072** (führt den
  PD-Handshake als DP-Sink durch, bewirbt DP-Alt-Mode) **+ SN75DP119**
  DisplayPort-Redriver/Buffer, plus USB-C- und DP-Buchse (KiCad im Repo).

**Konsequenz:** Der Weg „Brille als DP-Monitor" ist **kein reines
Software-/Firmware-Problem**. Er erfordert eine **aktive USB-C-PD-Zwischenplatine**
(PD-Controller, der den DP-Alt-Mode-Sink-Handshake macht + DP-Redriver) —
identisch zu strfrys Hardware-Ansatz. Firmware-Patches der Brille allein
(Watchdog/Reset) genügen dafür nicht.

## 4e. Kann die Firmware den PD-Handshake lösen? (Analyse)

**Kurzantwort: Nicht sauber — der PD-Handshake liegt gar nicht in der flashbaren
Firmware.**

**Belege (Symbol-/String-Analyse aller 3 Elemente):**
- In `elem0`/`elem1`/`elem2` gibt es **null** USB-PD-Logik — keine Strings/Symbole
  für `PD`, `Type-C`, `CC1/CC2`, `alt-mode`, `SVID`, `VDM`, `DisplayPort`,
  `Discover`, `Enter Mode`, `orientation`, `Billboard`.
- Die STM32-App macht nur: HID-Kommandos, Sensoren und **I²C-Register-Init** der
  Video-Chips (`ANX7401_Init`, `ANX7401_power_on`, `BSP_ANX7737_On`,
  `BSP_TC358870XBG_*`, `NCP6924`, `SM5306`).

**Architektur-Schlussfolgerung:**

| Baustein | Rolle | Von uns flashbar? |
|----------|-------|-------------------|
| **STM32** | App-MCU: HID, Sensoren, Power-Sequencing, I²C-Init — **kennt kein PD** | ✅ ja (DFU) |
| **ANX7401 / ANX7737** (Analogix) | **USB-C-PD-Handshake + DP-Alt-Mode autonom** über CC-Leitungen | ❌ eigenes Silizium/OTP, proprietär |

Der Handshake läuft **im Analogix-Chip**, nicht im STM32. Und dieser Chip wird
laut Firmware erst bei `VR App Start` eingeschaltet (`ANX7401_power_on >> power
on`) → **während der PD-Aushandlung beim Einstecken ist er tot**; der Mac schließt
den PD-Vertrag als „USB-only" ab, danach re-negotiiert niemand.

**Einziger indirekter Firmware-Hebel (Experiment, nicht getestet):**
1. STM32-Patch: ANX7401 **schon im Boot** (`BSP_Init`) einschalten + I²C-init,
   statt erst bei `VR App Start`.
2. Reset/Watchdog entfernen (`patched_goalA_noreset.dfu`), damit der Chip lebt.
3. **Physisch neu einstecken** → der Mac (vollwertiger USB-C-DP-Source) fährt die
   DP-Discovery neu, während der ANX bereits DP-Alt-Mode bewirbt.

**Harte Vorbehalte:** (a) Timing — STM32-Boot ≈ 1,5 s vs. PD ≈ ms; ein erzwungenes
Re-Negotiate (CC-Detach/Hard-Reset) ist wieder Sache des ANX-Chips. (b) Die
ANX-I²C-Sequenzen sind undokumentierte Magic-Numbers (Analogix-Registermap nicht
öffentlich). (c) Ohne **USB-PD-Sniffer** (Twinkie/PD-Analyzer) auf den CC-Leitungen
ist nicht verifizierbar, ob DP-Alt-Mode überhaupt angeboten/abgelehnt wird — genau
deshalb hat strfry einen Twinkie benutzt.

> **Hinweis zur RE-Methodik:** Diese Firmware referenziert Log-Strings per
> PC-relativem `ADR` (kein Literal-Pool mit Absolutadresse). Absolute
> Pointer-Scans und Ghidras Auto-XREF greifen daher für die
> Funktionsnamen-Strings nicht; das Skript `tools/ghidra_anx_powerpath.java`
> dokumentiert diesen Versuch. Der PD-Kernbefund (kein PD-Code) beruht auf der
> vollständigen String-/Symbol-Analyse aller Elemente und ist davon unabhängig.

## 4f. Flash-Test durchgeführt — Tooling-Blocker (diese Session)

Nach ausdrücklicher Freigabe („lass uns die Firmware testen") wurde der
Round-Trip-Flash (Schritt 1, **unveränderte** Stock-Firmware) empirisch versucht.
Ergebnis: **Das Gerät ließ sich mit dem verfügbaren macOS-Tooling nicht
beschreiben. Es wurde NICHTS geflasht — die Stock-Firmware ist unverändert.**

**Vorbereitung (verifiziert, nicht-destruktiv):**
- Die drei extrahierten Stock-Elemente sind **byte-identisch** mit den bekannt-
  guten Element-`.bin`s aus dem strfry-Gist (SHA-256 geprüft, alle 3 IDENTICAL) —
  unser Stock-`.dfu` ist exakt das vom Gist-Autor erfolgreich geflashte Image.
- Flash-Artefakt `myfw` exakt nach Gist-Rezept gebaut: 285-Byte-DfuSe-Header
  entfernt (`dd … skip=285`), Plain-Suffix per `dfu-suffix -a` ergänzt.
- `patched_goalA_noreset.dfu` unterscheidet sich von Stock in genau **3 Bytes in
  elem2** (App @ 0x08020000) + 4 CRC-Bytes im Suffix; elem0/elem1 **0 Diffs**.

**Blocker (empirisch, drei unabhängige Befunde):**
1. **`dfu-util -D` bricht VOR dem Schreiben ab:** `Failed to retrieve string
   descriptor 6` → `Could not read name, sscanf returned 0` → `Failed to parse
   memory layout for alternate interface 0`. Der LGE-Downloader liefert den
   ST-Memory-Layout-String **nicht** (Interface-`name="UNKNOWN"`), den dfu-util
   0.11 für DfuSe-Geräte (Version 011a) zwingend zum Berechnen der Erase-Pages
   braucht. `-s <addr>` umgeht das nicht — der Layout-Parse läuft trotzdem zuerst.
2. **Gerät IST DfuSe:** Plain-DFU-Upload Block 0 liefert die Kommando-Liste
   `00 21 41` = {0x21 Set-Address-Pointer, 0x41 Erase}. Es ist also kein
   Plain-Sequential-Downloader, sondern nutzt die DfuSe-Kommando-Schnittstelle.
3. **Adressierung STALLt:** Ein manueller DfuSe-Flasher (`tools/dfuse_flash_app.py`,
   schreibt aus Sicherheitsgründen **nur** die App-Region, harter Guard gegen
   Adressen < 0x08020000) wurde gebaut. Doch aus sauberem `dfuIDLE` liefert der
   `Set-Address-Pointer`-Befehl (0x21) einen **USB-Pipe-Error (STALL)**; ebenso
   adressiertes Upload. Das Gerät **honoriert die DfuSe-Adressierung über den
   macOS/libusb-Pfad nicht**, obwohl es 0x21/0x41 bewirbt (deckt sich mit dem
   früheren Befund „ignoriert Set-Address-Pointer, wrappt alle 16 KB").

**Fazit:** dfu-util verweigert (kein Layout-String), roher DfuSe-Set-Address
STALLt → **zuverlässiges, adressiertes Schreiben ist mit dem aktuellen
macOS-Tooling nicht möglich**. Ein blindes Schreiben ohne funktionierende
Adressierung wäre ein reales Brick-Risiko am einzigen Gerät — bei nach 4e
zugleich sehr geringem strategischem Nutzen (der PD-Handshake sitzt im
ANX-Chip, nicht in dieser Firmware). Daher **bewusst abgebrochen, kein Write**.

**Mögliche Wege für einen echten Flash (künftig):**
- **Linux-Host mit dfu-util:** Auf Linux liest dfu-util den Layout-String evtl.
  fehlerfrei (der Gist-Autor arbeitete unter Linux) → normaler
  `dfu-util -a 0 -D myfw`. Erste Option der Wahl.
- **Windows** wie im Gist: Zadig (WinUSB-Treiber für „LGE Download Firmware
  Update") + dfu-util-Release.
- **Gepatchtes dfu-util**, das einen Layout-String manuell injiziert / den
  DfuSe-Layout-Parse überspringt.
- Erst wenn Set-Address auf einem anderen Host **nicht** STALLt, ist adressiertes
  Schreiben überhaupt sinnvoll testbar.

> **Geräte-Zustand nach Test:** Das Gerät verblieb im (sticky) DFU-Download-Modus
> und ließ sich per Software (USB-Reset, DFU_DETACH, `dfu-util -e`) nicht
> verlassen. **Physisch aus- und wieder einstecken** bootet es in die normale
> (unveränderte) Firmware zurück — nichts wurde geschrieben, kein Brick.

## 5. Plan (billig → teuer)

1. **Signatur-Check klären (Round-Trip, nicht-modifiziert).** Per HID
   `GoToDload` (Opcode **0x09**, ungepolstert) in den DFU-Modus, prüfen dass das
   DFU-Gerät **`1004:6374`** erscheint (`dfu-util -l`, **ungefiltert**), und das
   **unveränderte Stock-Image** mit `dfu-util` zurückflashen. Klappt das →
   Bootloader akzeptiert unsignierte Images → Tür ist offen, Recovery bewiesen.
   *(Destruktiv/Brick-Risiko — nur nach ausdrücklicher Freigabe.)*
   **⚠️ Update (4f): unter macOS aktuell nicht möglich** — dfu-util findet keinen
   Layout-String, roher DfuSe-Set-Address STALLt. Erst auf Linux/Windows-Host
   erneut versuchen.
2. ~~Ghidra-Analyse von elem2.~~ **✅ ERLEDIGT** — Watchdog `FUN_08035a42`
   lokalisiert, dekompiliert und zwei verifizierte Patches erzeugt (Abschnitt 4b).
   Der Patch ist minimal und isoliert (2–4 Bytes in einer einzigen Funktion).
3. **Round-Trip bestätigt (Schritt 1) → dann `patched_goalA_noreset.dfu` flashen**
   und beobachten, ob der Boot-Loop verschwindet und das Gerät stabil bleibt.
4. **Falls kein Bild:** `patched_goalA_startvideo.dfu` flashen; falls die Panels
   dann noch dunkel sind, TC358870-internen Testbild-Generator per I2C aktivieren
   (Folgeschritt, empirisch nach erstem Flash).
5. **Ziel B** (DP-Sink beim Einstecken) separat angehen — erfordert weiteres RE
   der ANX7401-/PD-Initialisierung.

Alle Patches: mit `tools/dfuse.py patch` gebaut (CRC automatisch), **nur elem2 @
0x08020000** flashen, Bootloader (elem0) niemals überschreiben.

---

## 6. Risiken

- **Bricking:** Nur die App-Region (`0x08020000`) flashen; elem0/Bootloader in
  Ruhe lassen → `Go to Dload`/Recovery bleibt erhalten.
- **Restsignatur-Risiko:** Ausgeschlossen erst nach erfolgreichem Round-Trip
  (Schritt 1).
- **RE-Aufwand:** ✅ für Ziel A erledigt (Patch steht). Ziel B: weiteres RE nötig.

---

## 7. Inhalt dieses Ordners

```
firmware/
├── LGR100AT-...+0.dfu               # Stock-Firmware (aus dem APK)
├── patched_goalA_noreset.dfu        # Patch: Reset entfernt (sicherste Variante)
├── patched_goalA_startvideo.dfu     # Patch: Video sofort starten (standalone)
├── CHECKSUMS.txt                    # SHA-256 aller .dfu-Dateien
├── elements/
│   ├── elem_08000000.bin            # Bootloader
│   ├── elem_08010000.bin            # DLOAD
│   └── elem_08020000.bin            # Haupt-App (RE-Ziel)
├── tools/
│   ├── dfuse.py                     # info / extract / patch / fixcrc (nicht-destruktiv)
│   ├── raw2elf.py                   # rohes .bin → ELF für Disassembler/Ghidra
│   ├── extract_from_apk.sh          # Stock-.dfu erneut aus dem APK ziehen
│   ├── ghidra_find_watchdog.java    # Ghidra: Xrefs zu Watchdog-Strings
│   ├── ghidra_dump_watchdog.java    # Ghidra: Disasm/Decompile des Watchdogs
│   └── ghidra_verify_patch.java     # Ghidra: Patch-Stelle gegenprüfen
└── README.md                        # dieses Dokument
```

### Ghidra headless (Analyse reproduzieren)

```bash
HL=/opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless
"$HL" /tmp/ghproj LG360 -import elements/elem_08020000.bin \
  -processor "ARM:LE:32:Cortex" -loader BinaryLoader -loader-baseAddr 0x08020000 \
  -postScript ghidra_dump_watchdog.java -scriptPath tools -deleteProject
```

### Nützliche Befehle (nur lesend/offline)

```bash
# Layout & CRC prüfen
python3 tools/dfuse.py info LGR100AT-*.dfu

# Elemente extrahieren
python3 tools/dfuse.py extract LGR100AT-*.dfu elements/

# App für Disassembler aufbereiten (Xcode liefert llvm-objdump)
python3 tools/raw2elf.py elements/elem_08020000.bin 0x08020000 /tmp/app.elf
llvm-objdump -d --triple=thumbv7em-none-eabi /tmp/app.elf | less

# Patch bauen (Beispiel; Offset erst nach Ghidra-Analyse bekannt) — CRC autom.
python3 tools/dfuse.py patch LGR100AT-*.dfu patched.dfu 0x08020000 0x<REL> <hexbytes>
```

### Flash-Befehle (DESTRUKTIV — nur nach Freigabe, hier zur Doku)

```bash
# Gerät via HID nach DFU schalten (Opcode 0x09, ungepolstert):
python3 go_dload.py                           # sendet \x03\x09GoToDload
# Gerät re-enumeriert als DFU "LGE Download Firmware Update" (USB 1004:6374):
dfu-util -l                                   # UNGEFILTERT — Ziel 1004:6374, Alt-Setting anzeigen
# 1) Round-Trip zuerst: Stock-Image zurückflashen (beweist unsigned-OK + Recovery)
dfu-util -a 0 -s 0x08020000 -D LGR100AT-00-V10d-310-XX-MAY-02-2016+0.dfu
# 2) Dann der Patch (nur App-Region, NICHT den Bootloader):
dfu-util -a 0 -s 0x08020000 -D patched_goalA_noreset.dfu
```

> ⚠️ Vor jedem Flashen: Stock-`.dfu` als Wiederherstellung bereithalten und
> Round-Trip (Schritt 1) zuerst erfolgreich durchführen. `-s 0x08020000` stellt
> sicher, dass nur die App-Region beschrieben wird — der Bootloader (elem0 @
> 0x08000000) bleibt unangetastet, damit `Go to Dload`/Recovery erhalten bleibt.
