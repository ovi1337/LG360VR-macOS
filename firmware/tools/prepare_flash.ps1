<#
prepare_flash.ps1 — build & verify everything needed to flash the LG 360 VR
(LGR100AT) on Windows. NON-DESTRUCTIVE: only reads the stock firmware and writes
prepared artifacts into an output dir. Nothing is sent to the device.
See firmware\FLASHING.md before flashing.

Usage:
  powershell -ExecutionPolicy Bypass -File firmware\tools\prepare_flash.ps1 [-Stock <path>] [-Out <dir>]

Requires: python (python.exe on PATH), dfu-suffix.exe on PATH (ships with the
dfu-util Windows release: http://dfu-util.sourceforge.net/releases/).
#>
param(
  [string]$Stock = "",
  [string]$Out   = ""
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$fw   = Split-Path -Parent $here
if (-not $Out) { $Out = Join-Path $fw "out" }

# Known-good SHA-256 (see firmware\CHECKSUMS.txt)
$STOCK_SHA    = "FFA4994FAD7ADB32FD0B4D85744D619FBF8D1B213929AD7E4A52382457CD43DB"
$NORESET_SHA  = "9FD04198AAB9983C367D6E4DDC52679A9D7A1BE20F1A8786CC2DB4C011ABD921"
$STARTVID_SHA = "ACAE6C17C2C3C11A816C82EC7BA2DF4DC60BE688C5FF6D7253862322800D3E63"

function Sha($p) { (Get-FileHash -Algorithm SHA256 $p).Hash.ToUpper() }
function Need($exe) { if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { throw "MISSING TOOL: $exe" } }

$python = (Get-Command python -ErrorAction SilentlyContinue) ?? (Get-Command python3 -ErrorAction SilentlyContinue)
if (-not $python) { throw "MISSING TOOL: python" }
Need "dfu-suffix"

# --- locate stock firmware --------------------------------------------------
if (-not $Stock) {
  $Stock = (Get-ChildItem -Path $fw -Filter "LGR100AT-*.dfu" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}
if (-not $Stock -or -not (Test-Path $Stock)) {
  throw "No stock .dfu. Get it from 'LG 360 VR Manager.apk' (assets\LGR100AT-*.dfu) or strfry's gist, then pass -Stock <path>."
}

Write-Host "== stock: $Stock"
$got = Sha $Stock
if ($got -eq $STOCK_SHA) { Write-Host "   SHA-256 OK (matches known-good)" }
else { Write-Warning "SHA-256 $got != expected $STOCK_SHA (different firmware version?)" }

New-Item -ItemType Directory -Force -Path $Out | Out-Null

# --- 1) regenerate the patched .dfu images (deterministic from stock) -------
Write-Host "== building patched images (reproducible) ..."
& $python.Source (Join-Path $here "dfuse.py") patch $Stock (Join-Path $Out "patched_goalA_noreset.dfu")    08020000 15AF9 bf00bf
& $python.Source (Join-Path $here "dfuse.py") patch $Stock (Join-Path $Out "patched_goalA_startvideo.dfu") 08020000 15B08 00bf
function Verify($f,$want) { $g = Sha $f; if ($g -eq $want) { Write-Host "   $(Split-Path -Leaf $f) SHA-256 OK" } else { Write-Warning "$(Split-Path -Leaf $f) SHA MISMATCH: $g" } }
Verify (Join-Path $Out "patched_goalA_noreset.dfu")    $NORESET_SHA
Verify (Join-Path $Out "patched_goalA_startvideo.dfu") $STARTVID_SHA

# --- 2) build plain-DFU 'myfw' artifacts (gist recipe: strip 285-byte DfuSe
#        header + trailing suffix, re-add a plain suffix). Used only if your
#        dfu-util cannot flash the DfuSe .dfu directly.
function Build-Myfw($src,$dst) {
  $bytes = [System.IO.File]::ReadAllBytes($src)
  # DfuSe file = 285-byte header + elements + 16-byte suffix.
  $len = $bytes.Length - 285 - 16
  $slice = New-Object byte[] $len
  [Array]::Copy($bytes, 285, $slice, 0, $len)
  [System.IO.File]::WriteAllBytes($dst, $slice)
  & dfu-suffix -a $dst | Out-Null   # re-add plain (wildcard) suffix
}
Write-Host "== building plain-DFU artifacts ..."
Build-Myfw $Stock                                   (Join-Path $Out "myfw_stock")
Build-Myfw (Join-Path $Out "patched_goalA_noreset.dfu")    (Join-Path $Out "myfw_noreset")
Build-Myfw (Join-Path $Out "patched_goalA_startvideo.dfu") (Join-Path $Out "myfw_startvideo")

Write-Host ""
Write-Host "== prepared in: $Out"
Get-ChildItem $Out | Format-Table Name,Length -AutoSize

@"
Next steps (see firmware\FLASHING.md):
  0) Install the WinUSB driver with Zadig (https://zadig.akeo.ie/):
     select 'LGE Download Firmware Update' -> install WinUSB.
  1) Enter DFU: python go_dload.py   (or testgui.exe from the gist);
     verify: dfu-util -l  ->  Found DFU: [1004:6374]
  2) ROUND-TRIP first (unmodified stock):
       dfu-util -d 1004:6374 -a 0 -D "$Stock"
       # if 'Failed to parse memory layout' -> use the plain-DFU artifact:
       dfu-util -d 1004:6374 -a 0 -D "$(Join-Path $Out 'myfw_stock')"
     Power-cycle; device must boot to normal HID (LGE Custom Human interface).
  3) Only after a clean round-trip, flash a patch:
       dfu-util -d 1004:6374 -a 0 -D "$(Join-Path $Out 'patched_goalA_noreset.dfu')"
  Recovery: re-flash the stock image. elem0 (bootloader) is never touched.
"@ | Write-Host
