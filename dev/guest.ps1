<#
.SYNOPSIS
  armacraft guest dev loop: long-lived container, incremental builds, process-only restarts.

.DESCRIPTION
  Container armacraft-guest-dev (image armacraft-guest-devenv, guest/Dockerfile.dev):
    127.0.0.1:4544/udp -> 4534  Armagetron clients
    127.0.0.1:4545/tcp -> 4535  bridge (PROTOCOL.md v1, no auth: localhost only)
  Named volume armacraft-guest-dev-work holds the patched tree, build dir, prefix and ccache.
  guest/ is bind-mounted read-only at /guest; guest/server at /etc/armacraft (as guest/run.ps1).
  The container stays up across rebuilds, so the published port never disappears and the MC
  mod's reconnect lands within its 0.5 s minimum backoff.

  Never touches armacraft-guest (4534/4535) or the armacraft-guest:dev image.

.EXAMPLE
  dev\guest.ps1 up                 # image + volume + container + first build, wait for bridge
  dev\guest.ps1 rebuild            # after editing guest/src: sync, incremental make, restart server
  dev\guest.ps1 rebuild -Quick     # same, changed files at -O0 (~2 s faster; normal rebuild restores -O2)
  dev\guest.ps1 rebuild -Full      # after editing configure.ac etc. (patches are detected automatically)
  dev\guest.ps1 restart            # restart the server process only
  dev\guest.ps1 cfg                # re-exec guest/server/settings_custom.cfg in the running server
  dev\guest.ps1 cfg bridge.cfg     # ... or a specific cfg file
  dev\guest.ps1 console "CYCLE_SPEED 25"
  dev\guest.ps1 logs [-Follow] [-Tail 100]
  dev\guest.ps1 status
  dev\guest.ps1 shell
  dev\guest.ps1 down [-Wipe]       # -Wipe also deletes the build volume (next up = full build)
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'rebuild', 'restart', 'logs', 'down', 'cfg', 'console', 'status', 'shell', 'image')]
    [string]$Command = 'status',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Rest = @(),
    [switch]$Full,
    [switch]$Force,
    [switch]$Quick,
    [switch]$Follow,
    [int]$Tail = 200,
    [switch]$Wipe,
    [switch]$NoWait
)

. (Join-Path $PSScriptRoot 'lib.ps1')

function Get-UpstreamPin {
    # single source of truth: the ARGs in guest/Dockerfile
    $df = Get-Content (Join-Path $GuestDir 'Dockerfile') -Raw
    $pin = @{}
    foreach ($k in 'ARMA_REPO', 'ARMA_REF', 'ARMA_COMMIT') {
        if ($df -match "(?m)^ARG $k=(\S+)") { $pin[$k] = $Matches[1] }
    }
    return $pin
}

function Build-Image([switch]$Quiet) {
    $pin = Get-UpstreamPin
    $a = @('build', '-f', (Join-Path $GuestDir 'Dockerfile.dev'), '-t', $GuestImage)
    foreach ($k in $pin.Keys) { $a += @('--build-arg', "$k=$($pin[$k])") }
    if ($Quiet) { $a += '-q' }
    $a += (Join-Path $GuestDir 'dev')   # tiny context; Dockerfile.dev copies nothing
    Say "docker build $GuestImage ($($pin['ARMA_REF']) @ $($pin['ARMA_COMMIT'].Substring(0,8)))"
    Invoke-Native docker $a | Out-Host
}

function Wait-Bridge([double]$timeout = 60) {
    $p = Invoke-BridgeProbe -Port $GuestBridge -Wait $timeout
    if (-not $p.ok) { throw "bridge on 127.0.0.1:$GuestBridge not ready after ${timeout}s: $($p.error)" }
    return $p
}

function Invoke-InGuest([string[]]$a) {
    Invoke-Native docker (@('exec', $GuestName, '/bin/bash') + $a)
}

function Start-GuestContainer {
    if (Test-ContainerRunning $GuestName) { return $false }
    $img = docker images -q $GuestImage 2>$null
    if (-not $img) { Build-Image }
    docker rm -f $GuestName 2>$null | Out-Null
    docker volume create $GuestVolume | Out-Null
    $a = @('run', '-d', '--init', '--name', $GuestName,
        '-p', "127.0.0.1:${GuestUdp}:4534/udp",
        '-p', "127.0.0.1:${GuestBridge}:4535/tcp",
        '-v', "${GuestVolume}:/work",
        '-v', "${GuestDir}:/guest:ro",
        '-v', "$(Join-Path $GuestDir 'server'):/etc/armacraft:ro",
        '--label', 'armacraft.role=guest-dev',
        $GuestImage)
    Say "docker run $GuestName (127.0.0.1:$GuestUdp/udp, 127.0.0.1:$GuestBridge/tcp)"
    Invoke-Native docker $a | Out-Null
    return $true
}

$t0 = Now
try {
switch ($Command) {
    'image' {
        Build-Image
    }
    'up' {
        $started = Start-GuestContainer
        if (-not $started) { Say "$GuestName already running" }
        $ba = @('/guest/dev/build.sh')
        if ($Full) { $ba += '--full' }
        if (-not $started) { $ba += '--restart' }
        Invoke-InGuest $ba
        if (-not $NoWait) {
            $p = Wait-Bridge 120
            Say "guest up: $($p.hello.game) $($p.hello.version) rate $($p.hello.rate) on 127.0.0.1:$GuestBridge  ($(Secs $t0)s)"
        }
    }
    'rebuild' {
        if (-not (Test-ContainerRunning $GuestName)) { throw "$GuestName is not running (dev\guest.ps1 up)" }
        $ba = @('/guest/dev/build.sh', '--restart')
        if ($Full) { $ba += '--full' }
        if ($Force) { $ba += '--force-restart' }
        if ($Quick) { $ba += '--quick' }
        Invoke-InGuest $ba | Tee-Object -Variable out | Out-Host
        $tb = Secs $t0
        if (-not $NoWait -and ($out -match 'server restarted')) {
            $p = Wait-Bridge 60
            Say "rebuilt + restarted: build ${tb}s, bridge hello after $(Secs $t0)s total"
        } else {
            Say "rebuild done in ${tb}s"
        }
    }
    'restart' {
        Invoke-InGuest @('/guest/dev/ctl.sh', 'restart')
        if (-not $NoWait) { $null = Wait-Bridge 60 }
        Say "server restarted, bridge up after $(Secs $t0)s"
    }
    'cfg' {
        Invoke-InGuest (@('/guest/dev/ctl.sh', 'cfg') + $Rest)
        Say "cfg applied in $(Secs $t0)s (round-scoped settings take effect next round)"
    }
    'console' {
        if (-not $Rest) { throw 'usage: dev\guest.ps1 console "LINE"' }
        Invoke-InGuest @('/guest/dev/ctl.sh', 'console', ($Rest -join ' '))
    }
    'logs' {
        $a = @('logs', '--tail', $Tail)
        if ($Follow) { $a += '-f' }
        $a += $GuestName
        docker @a
    }
    'shell' {
        docker exec -it $GuestName /bin/bash
    }
    'status' {
        if (-not (Test-ContainerRunning $GuestName)) { Write-Host "guest: $GuestName not running"; break }
        docker exec $GuestName /bin/bash /guest/dev/ctl.sh status
        $p = Invoke-BridgeProbe -Port $GuestBridge -Wait 0 -Rate 2
        if ($p.ok) { Write-Host "bridge: hello $($p.hello.version), state $($p.state_hz) Hz, cycles $($p.cycles)" }
        else { Write-Host "bridge: not answering ($($p.error))" }
    }
    'down' {
        docker rm -f $GuestName 2>$null | Out-Null
        Say "$GuestName removed"
        if ($Wipe) {
            docker volume rm $GuestVolume 2>$null | Out-Null
            Say "volume $GuestVolume removed"
        }
    }
}
} catch {
    Write-Host "guest.ps1 ${Command}: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
