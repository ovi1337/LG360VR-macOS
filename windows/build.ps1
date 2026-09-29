# Build (and optionally run) the LG 360 VR Tester on Windows.
# Requires the .NET 8 SDK: https://dotnet.microsoft.com/download/dotnet/8.0
#
# Usage:
#   .\build.ps1              # build Debug
#   .\build.ps1 -Run         # build + run
#   .\build.ps1 -Publish     # self-contained single-file exe in .\publish

param(
    [switch]$Run,
    [switch]$Publish
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    Write-Error "dotnet SDK not found. Install .NET 8 SDK from https://dotnet.microsoft.com/download/dotnet/8.0"
}

if ($Publish) {
    Write-Host "==> Publishing self-contained single-file exe..." -ForegroundColor Cyan
    dotnet publish LG360VRTester/LG360VRTester.csproj -c Release -r win-x64 `
        --self-contained true `
        -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
        -o publish
    Write-Host "==> Done: publish\LG360VRTester.exe" -ForegroundColor Green
    return
}

Write-Host "==> Building Debug..." -ForegroundColor Cyan
dotnet build LG360VRTester/LG360VRTester.csproj -c Debug

if ($Run) {
    Write-Host "==> Running..." -ForegroundColor Cyan
    dotnet run --project LG360VRTester/LG360VRTester.csproj -c Debug
}
