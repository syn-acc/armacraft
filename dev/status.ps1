<#
.SYNOPSIS
  One-screen status of the armacraft dev stack (dev guest + dev MC server).

.DESCRIPTION
  guest   container/server process/build id, bridge hello on 127.0.0.1:4545 and the snapshot
          rate measured by a 2 s probe connection (the probe never joins)
  mc      server process, mod load mode, deployed build + hotswap count, armacraft version from
          latest.log, bridge state from `/arma status` over RCON, and the host's lines-in rate
          over the same 2 s window
#>
param([double]$Window = 2)

. (Join-Path $PSScriptRoot 'lib.ps1')

$state = Join-Path $McDir '.dev'
$props = Join-Path $McDir 'server.properties'
$rcon = Join-Path $DevDir 'tools\rcon.py'

function Rcon([string]$c) {
    if (-not (Test-Path $props)) { return $null }
    $o = & $Py $rcon --props $props $c 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($o -join "`n")
}
function LinesIn([string]$s) { if ($s -match 'lines in/out (\d+)/') { return [int]$Matches[1] } return $null }
function Row([string]$k, [string]$v, [string]$color = 'Gray') { Write-Host ("{0,-16}" -f $k) -NoNewline -ForegroundColor DarkGray; Write-Host $v -ForegroundColor $color }

# ---- MC process
$mcPid = $null
$pidFile = Join-Path $state 'server.pid'
if (Test-Path $pidFile) {
    $p = Get-Process -Id ([int](Get-Content $pidFile -Raw).Trim()) -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -eq 'java') { $mcPid = $p }
}

# ---- sample: rcon, 2 s probe, rcon
$s0 = if ($mcPid) { Rcon 'arma status' } else { $null }
$t0 = Now
$guestUp = Test-ContainerRunning $GuestName
$probe = if ($guestUp) { Invoke-BridgeProbe -Port $GuestBridge -Wait 0 -Rate $Window } else { $null }
$s1 = if ($mcPid) { Rcon 'arma status' } else { $null }
$dt = Secs $t0

Write-Host "armacraft dev stack  $(Get-Date -Format 'HH:mm:ss')" -ForegroundColor Cyan

# ---- guest
if (-not $guestUp) {
    Row 'guest' "$GuestName not running (dev\guest.ps1 up)" 'Red'
} else {
    $cs = (docker exec $GuestName /bin/bash /guest/dev/ctl.sh status 2>$null) -join ' '
    Row 'guest' "${GuestName}: $cs" $(if ($cs -match 'not running') { 'Red' } else { 'Green' })
    if ($probe -and $probe.ok) {
        Row 'guest bridge' "127.0.0.1:$GuestBridge hello $($probe.hello.game) $($probe.hello.version) proto $($probe.hello.proto), BRIDGE_RATE $($probe.hello.rate)" 'Green'
        Row 'snapshots' "$($probe.state_hz) Hz at the guest ($($probe.state_msgs) state msgs / ${Window}s probe), cycles $($probe.cycles)"
    } else {
        Row 'guest bridge' "127.0.0.1:$GuestBridge not answering: $($probe.error)" 'Red'
    }
}

# ---- mc
if (-not $mcPid) {
    Row 'mc' "dev server not running (dev\host.ps1 up)" 'Red'
} else {
    $up = [int]((Get-Date) - $mcPid.StartTime).TotalSeconds
    $mode = if (Test-Path (Join-Path $state 'mode')) { (Get-Content (Join-Path $state 'mode') -Raw).Trim() } else { '?' }
    Row 'mc' "pid $($mcPid.Id) up ${up}s on 127.0.0.1:$McPort, mod mode $mode" 'Green'
    $ver = Select-String -Path (Join-Path $McDir 'logs\latest.log') -Pattern '^\s+- armacraft (\S+)' -ErrorAction SilentlyContinue | Select-Object -First 1
    $dep = if (Test-Path (Join-Path $state 'deployed.txt')) { (Get-Content (Join-Path $state 'deployed.txt') -Raw).Trim() } else { '?' }
    Row 'mod' "armacraft $(if ($ver) { $ver.Matches[0].Groups[1].Value } else { '?' }) | $dep"
    if ($s1) {
        $first = ($s1 -split "`n")[0]
        Row 'mc bridge' $first $(if ($first -match ' connected') { 'Green' } else { 'Yellow' })
        $a = LinesIn $s0; $b = LinesIn $s1
        if ($null -ne $a -and $null -ne $b -and $dt -gt 0) {
            Row 'host lines in' ("{0:N1}/s over {1}s (state + events, as received by the mod)" -f (($b - $a) / $dt), $dt)
        }
        foreach ($l in ($s1 -split "`n" | Select-Object -Skip 1)) { if ($l.Trim()) { Row '' $l } }
    } else {
        Row 'mc bridge' 'RCON not answering (starting up?)' 'Yellow'
    }
}
