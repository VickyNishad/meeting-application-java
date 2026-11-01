# Starts the whole application on this PC without Docker:
# PostgreSQL (5432) + LiveKit (7880) + API (8081) + web app (5173).
# Run from PowerShell:   powershell -ExecutionPolicy Bypass -File .\start-local.ps1
#   -Build   rebuild the API jar first (needed after backend code changes)
# Then open http://127.0.0.1:5173
# The API runs with the "dev" Spring profile: recordings and files are stored under D:\meetly.
param([switch]$Build)
$tools = "$env:USERPROFILE\.meet-tools"
$jar = "$PSScriptRoot\backend\target\meet-platform-0.1.0-SNAPSHOT.jar"
if (-not $env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE = 'dev' }

if ($Build -or -not (Test-Path $jar)) {
    Write-Host "Building the API..."
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12" }
    & "$PSScriptRoot\backend\mvnw.cmd" -q -B -f "$PSScriptRoot\backend\pom.xml" package -DskipTests
    if ($LASTEXITCODE -ne 0) { Write-Host "API build failed." -ForegroundColor Red; exit 1 }
}

function Test-Port($port) {
    [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

if (-not (Test-Port 5432)) {
    Write-Host "Starting PostgreSQL..."
    Start-Process -WindowStyle Hidden "$tools\pg\bin\pg_ctl.exe" -ArgumentList @(
        '-D', "`"$tools\pgdata`"", '-l', "`"$tools\pg.log`"", '-o', '"-p 5432 -c listen_addresses=127.0.0.1"', 'start')
} else { Write-Host "PostgreSQL already running" }

if (-not (Test-Port 7880)) {
    Write-Host "Starting LiveKit media server..."
    Start-Process -WindowStyle Minimized "$tools\livekit\livekit-server.exe" -ArgumentList @('--config', "`"$tools\livekit-local.yaml`"")
} else { Write-Host "LiveKit already running" }

if (-not (Test-Port 8081)) {
    Write-Host "Starting API (takes about 20 seconds)..."
    Start-Process -WindowStyle Minimized powershell -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$tools\start-api.ps1`"")
} else { Write-Host "API already running" }

if (-not (Test-Port 5173)) {
    Write-Host "Starting web app..."
    Start-Process -WindowStyle Minimized powershell -ArgumentList @('-NoProfile', '-Command',
        "Set-Location '$PSScriptRoot\web'; npm run dev -- --host 127.0.0.1 --port 5173 --strictPort")
    # (also reachable as http://localhost:5173 on most browsers; 127.0.0.1 always works)
} else { Write-Host "Web app already running" }

Write-Host "Waiting for the API to become ready..."
$deadline = (Get-Date).AddSeconds(120)
do {
    try { if ((Invoke-RestMethod -UseBasicParsing http://127.0.0.1:8081/actuator/health/readiness -TimeoutSec 3).status -eq 'UP') { break } } catch { }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)

Write-Host ""
Write-Host "Ready. Open http://127.0.0.1:5173 in Chrome or Edge." -ForegroundColor Green
Start-Process "http://127.0.0.1:5173"
