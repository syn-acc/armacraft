# armacraft dev helpers, dot-sourced by dev/*.ps1. Windows PowerShell 5.1 compatible.

# 'Continue': PS 5.1 turns native stderr into terminating errors under 'Stop' (docker writes
# progress to stderr). Native exit codes are checked explicitly (Invoke-Native).
$ErrorActionPreference = 'Continue'
$script:DevDir  = $PSScriptRoot
$script:Root    = Split-Path -Parent $PSScriptRoot
$script:GuestDir = Join-Path $Root 'guest'

# --- dev guest (never the shared armacraft-guest on 4534/4535) ---
$script:GuestName   = 'armacraft-guest-dev'
$script:GuestImage  = 'armacraft-guest-devenv:latest'
$script:GuestVolume = 'armacraft-guest-dev-work'
$script:GuestUdp    = 4544
$script:GuestBridge = 4545

# --- dev MC server (never host/run on 25565) ---
$script:McDir      = Join-Path $DevDir 'mcserver'
$script:McPort     = 25566
$script:McRconPort = 25576
$script:McJdwpPort = 5006

function Find-Python {
    foreach ($c in @('python', 'py')) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd -and $cmd.Source -notmatch 'WindowsApps') { return $cmd.Source }
    }
    throw 'python 3 not found on PATH'
}
$script:Py = Find-Python

function Find-Java {
    # prefer JetBrains Runtime (enhanced class redefinition) when present, else any JDK 25
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    $cands = @()
    if ($env:ARMA_JAVA_HOME) { $cands += $env:ARMA_JAVA_HOME }
    if (Test-Path $jdks) {
        $cands += Get-ChildItem $jdks -Directory | Where-Object { $_.Name -match '^jbr.*25' } | Sort-Object Name -Descending | ForEach-Object FullName
        $cands += Get-ChildItem $jdks -Directory | Where-Object { $_.Name -match '25' } | Sort-Object Name -Descending | ForEach-Object FullName
    }
    if ($env:JAVA_HOME) { $cands += $env:JAVA_HOME }
    foreach ($h in $cands) {
        if (Test-Path (Join-Path $h 'bin\java.exe')) { return $h }
    }
    throw 'no Java 25 found (set ARMA_JAVA_HOME)'
}

function Test-IsJbr([string]$javaHome) {
    $rel = Join-Path $javaHome 'release'
    return (Test-Path $rel) -and ((Get-Content $rel -Raw) -match 'JetBrains|JBR')
}

function Now { [Diagnostics.Stopwatch]::GetTimestamp() }
function Secs($t0) { [math]::Round(([Diagnostics.Stopwatch]::GetTimestamp() - $t0) / [Diagnostics.Stopwatch]::Frequency, 2) }

function Say([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Warn([string]$msg) { Write-Host "!!  $msg" -ForegroundColor Yellow }

# Run a native command; throw on non-zero exit. Output streams through.
function Invoke-Native {
    param([Parameter(Mandatory)][string]$Exe, [string[]]$ArgList = @())
    & $Exe @ArgList
    if ($LASTEXITCODE -ne 0) { throw "$Exe $($ArgList -join ' ') failed (exit $LASTEXITCODE)" }
}

# Probe a bridge port: returns the parsed JSON from dev/tools/bridge_probe.py.
function Invoke-BridgeProbe {
    param([int]$Port = $GuestBridge, [double]$Wait = 0, [double]$Rate = 0, [string[]]$Cmd = @())
    $a = @((Join-Path $DevDir 'tools\bridge_probe.py'), '--port', $Port, '--wait', $Wait, '--retry', '0.05')
    if ($Rate -gt 0) { $a += @('--rate', $Rate) }
    foreach ($c in $Cmd) { $a += @('--cmd', $c) }
    $out = & $Py @a
    return ($out | Select-Object -Last 1 | ConvertFrom-Json)
}

function Test-ContainerRunning([string]$name) {
    $id = docker ps -q --filter "name=^$name$" 2>$null
    return [bool]$id
}
