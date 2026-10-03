<#
.SYNOPSIS
  armacraft host dev loop: standalone Fabric dedicated server in dev/mcserver with hotswap.

.DESCRIPTION
  dev/mcserver is a plain Fabric server (official launcher from meta.fabricmc.net) on
  127.0.0.1:25566, RCON 127.0.0.1:25576 (random password in server.properties). Its armacraft
  config points the bridge at the dev guest (127.0.0.1:4545). -Jdwp adds JDWP on 127.0.0.1:5006
  for an IDE debugger; hotswap itself uses a dynamic-attach agent, so no port is needed.
  Never touches host/run, port 25565 or the shared guest on 4535.

  Mod load modes (sticky, stored in dev/mcserver/.dev/mode):
    exploded (default)  the server loads the mod from dev/mcserver/.dev/mod (classes + resources,
                        via -Dfabric.addMods). Hotswap can add classes; no jar lock on Windows.
    jar                 mods/armacraft.jar from `gradlew jar`: the shipped artifact.

  Loops:
    hotswap   gradle `classes` -> redefine changed classes via an attach agent (JBR: structural too).
              Falls back to redeploy when a mixin, resource, or (jar mode) new class changed,
              or when the JVM refuses the change. -NoFallback to just report.
    redeploy  gradle `classes`/`jar` while the server runs -> stop via RCON -> deploy -> start ->
              wait for RCON + bridge connected.

  The host project defaults to ..\host; -HostDir (or $env:ARMA_HOST_DIR) points at another copy.
  Gradle runs with the Temurin JDK (shares daemons); the server prefers a JetBrains Runtime from
  ~/.jdks for -XX:+AllowEnhancedClassRedefinition (set ARMA_JAVA_HOME to force a JDK).

  Dev JVM flags (each measured, see dev/README.md): C1 only (-XX:TieredStopAtLevel=1; -C2 to
  drop) and no bytecode verification of mod/game classes (-Verify to keep it).

.EXAMPLE
  dev\host.ps1 up                    # init dev/mcserver, build, start, wait for bridge
  dev\host.ps1 hotswap               # after a Java edit: compile + redefine (~3-5 s)
  dev\host.ps1 hotswap -TargetPid 1234     # into another JVM running host/build/classes (gradlew runServer)
  dev\host.ps1 redeploy              # full restart with the new build
  dev\host.ps1 redeploy -Mode jar    # switch to the real jar artifact
  dev\host.ps1 cfg                   # re-read config/armacraft.json in place (host proposal 1)
  dev\host.ps1 rcon "arma status"
  dev\host.ps1 logs -Follow
  dev\host.ps1 status
  dev\host.ps1 down
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'redeploy', 'hotswap', 'restart', 'build', 'logs', 'down', 'status', 'rcon', 'cfg', 'init')]
    [string]$Command = 'status',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Rest = @(),
    [string]$HostDir = '',
    [ValidateSet('', 'exploded', 'jar')]
    [string]$Mode = '',
    [switch]$NoBuild,
    [switch]$NoFallback,
    [switch]$Follow,
    [int]$Tail = 80,
    [switch]$NoWait,
    [switch]$C2,
    [switch]$Verify,
    [switch]$Jdwp,
    [int]$TargetPid = 0
)

. (Join-Path $PSScriptRoot 'lib.ps1')

if (-not $HostDir) { $HostDir = if ($env:ARMA_HOST_DIR) { $env:ARMA_HOST_DIR } else { Join-Path $Root 'host' } }
$HostDir = (Resolve-Path $HostDir).Path
$StateDir = Join-Path $McDir '.dev'
$PidFile = Join-Path $StateDir 'server.pid'
$ModeFile = Join-Path $StateDir 'mode'
$ExplodedDir = Join-Path $StateDir 'mod'
$Manifest = Join-Path $StateDir 'classes.manifest'
$ResManifest = Join-Path $StateDir 'resources.manifest'
$DeployInfo = Join-Path $StateDir 'deployed.txt'
$Props = Join-Path $McDir 'server.properties'
$Rcon = Join-Path $DevDir 'tools\rcon.py'
$HotSwapSrc = Join-Path $DevDir 'tools\HotSwap.java'
$ClassesDir = Join-Path $HostDir 'build\classes\java\main'
$ResourcesDir = Join-Path $HostDir 'build\resources\main'
$MixinPrefix = 'gl.syn.armacraft.mixin.'
$FabricInstaller = '1.1.2'

# ----------------------------------------------------------------------------- helpers

function Get-GradleProps {
    $p = @{}
    foreach ($line in Get-Content (Join-Path $HostDir 'gradle.properties')) {
        if ($line -match '^\s*([\w.]+)\s*=\s*(.*?)\s*$') { $p[$Matches[1]] = $Matches[2] }
    }
    return $p
}

function Find-GradleJava {
    # Temurin first: same JDK as the other gradle users, so idle daemons get reused
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    $c = Get-ChildItem $jdks -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^jdk-25' } | Sort-Object Name -Descending | Select-Object -First 1
    if ($c) { return $c.FullName }
    return (Find-Java)
}

function Get-Mode {
    if ($Mode) { return $Mode }
    if (Test-Path $ModeFile) { return (Get-Content $ModeFile -Raw).Trim() }
    return 'exploded'
}

function Get-ServerProcess {
    if (-not (Test-Path $PidFile)) { return $null }
    $id = [int](Get-Content $PidFile -Raw).Trim()
    $p = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -eq 'java') { return $p }
    return $null
}

function Invoke-Rcon([string]$cmd, [double]$wait = 0) {
    $out = & $Py $Rcon --props $Props --wait $wait $cmd 2>&1
    return @{ ok = ($LASTEXITCODE -eq 0); text = (($out | ForEach-Object { "$_" }) -join "`n") }
}

function Invoke-Gradle([string[]]$tasks) {
    # scripts run in the caller's process: restore JAVA_HOME afterwards
    $savedJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = Find-GradleJava
    $gw = Join-Path $HostDir 'gradlew.bat'
    $t = Now
    try {
        & $gw -p $HostDir @tasks --console=plain -q 2>&1 | ForEach-Object {
            $l = "$_"
            # gradle -q still prints deprecation boilerplate; keep errors and warnings from javac
            if ($l -and $l -notmatch 'Deprecated Gradle features|warning-mode|command_line_warnings|Problems report is available|^\s*$') { Write-Host $l }
        }
        $rc = $LASTEXITCODE
    } finally {
        $env:JAVA_HOME = $savedJavaHome
    }
    if ($rc -ne 0) { throw "gradle $($tasks -join ' ') failed" }
    Say "gradle $($tasks -join ' ') $(Secs $t)s"
}

function Get-ModJar {
    $j = Get-ChildItem (Join-Path $HostDir 'build\libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|dev|javadoc)\.jar$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $j) { throw "no jar in $HostDir\build\libs (gradle jar)" }
    return $j
}

function Get-TreeHashes([string]$dir, [string]$filter = '*') {
    $h = [ordered]@{}
    if (-not (Test-Path $dir)) { return $h }
    $base = (Resolve-Path $dir).Path.TrimEnd('\') + '\'
    Get-ChildItem $dir -Recurse -File -Filter $filter | Sort-Object FullName | ForEach-Object {
        $h[$_.FullName.Substring($base.Length).Replace('\', '/')] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
    }
    return $h
}

function Write-Snapshot {
    # what the running JVM has loaded: class hashes for HotSwap, resource hashes for restart detection
    $lines = foreach ($e in (Get-TreeHashes $ClassesDir '*.class').GetEnumerator()) {
        "$($e.Key.Substring(0, $e.Key.Length - 6).Replace('/', '.')) $($e.Value)"
    }
    Set-Content -Path $Manifest -Value $lines -Encoding Ascii
    $res = foreach ($e in (Get-TreeHashes $ResourcesDir).GetEnumerator()) { "$($e.Key) $($e.Value)" }
    Set-Content -Path $ResManifest -Value $res -Encoding Ascii
}

function Test-ResourcesChanged {
    if (-not (Test-Path $ResManifest)) { return $true }
    $now = foreach ($e in (Get-TreeHashes $ResourcesDir).GetEnumerator()) { "$($e.Key) $($e.Value)" }
    $old = Get-Content $ResManifest
    return [bool](Compare-Object @($old) @($now))
}

function Sync-Exploded {
    # mirror build/classes/java/main + build/resources/main into .dev/mod (copy changed, delete stale)
    New-Item -ItemType Directory -Force $ExplodedDir | Out-Null
    $want = @{}
    foreach ($src in @($ClassesDir, $ResourcesDir)) {
        if (-not (Test-Path $src)) { throw "missing $src (gradle classes)" }
        $base = (Resolve-Path $src).Path.TrimEnd('\') + '\'
        foreach ($f in Get-ChildItem $src -Recurse -File) { $want[$f.FullName.Substring($base.Length)] = $f }
    }
    $n = 0
    foreach ($rel in $want.Keys) {
        $f = $want[$rel]
        $dst = Join-Path $ExplodedDir $rel
        $d = Get-Item $dst -ErrorAction SilentlyContinue
        if (-not $d -or $d.Length -ne $f.Length -or $d.LastWriteTimeUtc -ne $f.LastWriteTimeUtc) {
            New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
            Copy-Item $f.FullName $dst -Force
            $n++
        }
    }
    $base = (Resolve-Path $ExplodedDir).Path.TrimEnd('\') + '\'
    foreach ($f in Get-ChildItem $ExplodedDir -Recurse -File) {
        if (-not $want.ContainsKey($f.FullName.Substring($base.Length))) { Remove-Item $f.FullName -Force; $n++ }
    }
    return $n
}

function Initialize-McDir {
    New-Item -ItemType Directory -Force $McDir, $StateDir, (Join-Path $McDir 'mods'), (Join-Path $McDir 'config'), (Join-Path $McDir 'logs') | Out-Null
    $gp = Get-GradleProps

    $eula = Join-Path $McDir 'eula.txt'
    if (-not ((Test-Path $eula) -and ((Get-Content $eula -Raw) -match '(?m)^eula=true'))) {
        throw "dev/mcserver/eula.txt must contain eula=true. Accepting the Minecraft EULA is your call: https://aka.ms/MinecraftEULA"
    }

    $launcher = Join-Path $McDir 'fabric-server-launch.jar'
    $stamp = Join-Path $StateDir 'launcher.version'
    $want = "$($gp['minecraft_version'])/$($gp['loader_version'])/$FabricInstaller"
    if (-not (Test-Path $launcher) -or -not (Test-Path $stamp) -or (Get-Content $stamp -Raw).Trim() -ne $want) {
        $url = "https://meta.fabricmc.net/v2/versions/loader/$want/server/jar"
        Say "downloading Fabric server launcher $want"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $launcher
        Set-Content $stamp $want
    }

    if (-not (Test-Path $Props)) {
        $bytes = New-Object byte[] 24
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $pw = [Convert]::ToBase64String($bytes).Replace('+', 'A').Replace('/', 'B').TrimEnd('=')
        $flat = '{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
        @(
            '# armacraft dev server (generated by dev/host.ps1). Localhost only.',
            "server-port=$McPort", 'server-ip=127.0.0.1', 'motd=armacraft dev',
            'online-mode=true', 'max-players=8',
            'enable-rcon=true', "rcon.port=$McRconPort", "rcon.password=$pw",
            'broadcast-rcon-to-ops=false', 'broadcast-console-to-ops=false',
            'level-name=world', 'level-type=minecraft\:flat', "generator-settings=$($flat.Replace(':', '\:'))",
            'generate-structures=false', 'spawn-monsters=false', 'spawn-protection=0',
            'view-distance=6', 'simulation-distance=4', 'sync-chunk-writes=false',
            'gamemode=creative', 'difficulty=peaceful'
        ) | Set-Content -Path $Props -Encoding Ascii
        Say "wrote server.properties (RCON password generated, stays in that file)"
    }

    $cfg = Join-Path $McDir 'config\armacraft.json'
    if (-not (Test-Path $cfg)) {
        # flat world surface is y=-61; put the arena floor on it at spawn
        @{ host = '127.0.0.1'; port = $GuestBridge; auto_connect = $true
           anchor = @{ dimension = 'minecraft:overworld'; x = 0.5; y = -60.0; z = 0.5 } } |
            ConvertTo-Json | Set-Content -Path $cfg -Encoding Ascii
        Say "wrote config/armacraft.json (bridge 127.0.0.1:$GuestBridge)"
    }

    # Fabric API: the exact artifact the mod compiles against, from the gradle cache
    $fv = $gp['fabric_api_version']
    $mods = Join-Path $McDir 'mods'
    $have = Join-Path $mods "fabric-api-$fv.jar"
    if (-not (Test-Path $have)) {
        Get-ChildItem $mods -Filter 'fabric-api-*.jar' | Remove-Item -Force
        $cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$fv"
        $src = Get-ChildItem $cache -Recurse -Filter "fabric-api-$fv.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($src) { Copy-Item $src.FullName $have }
        else {
            Say "downloading fabric-api $fv from maven.fabricmc.net"
            Invoke-WebRequest -UseBasicParsing -Uri "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$fv/fabric-api-$fv.jar" -OutFile $have
        }
    }
}

function Build-Mod([string]$m) {
    if ($m -eq 'jar') { Invoke-Gradle @('jar') } else { Invoke-Gradle @('classes') }
}

function Deploy-Mod([string]$m) {
    # server must be stopped (Windows keeps the jar locked while it runs)
    $mods = Join-Path $McDir 'mods'
    if ($m -eq 'jar') {
        $j = Get-ModJar
        Copy-Item $j.FullName (Join-Path $mods 'armacraft.jar') -Force
        $info = "jar $($j.Name) $((Get-FileHash $j.FullName).Hash.Substring(0,12).ToLower())"
    } else {
        Remove-Item (Join-Path $mods 'armacraft*.jar') -Force -ErrorAction SilentlyContinue
        $n = Sync-Exploded
        $info = "exploded ($n files synced)"
    }
    Set-Content $ModeFile $m
    Set-Content $DeployInfo "$info, $(Get-Date -Format s), hotswaps 0"
    Write-Snapshot
    return $info
}

function Wait-PortFree([int]$port, [int]$seconds = 15) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ($true) {
        # IPGlobalProperties is ~1 ms; Get-NetTCPConnection takes ~1-2 s on PS 5.1
        $busy = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq $port }
        if (-not $busy) { return }
        if ((Get-Date) -gt $deadline) {
            $owner = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
            throw "port $port is still in use by pid $owner (another server on the dev port?)"
        }
        Start-Sleep -Milliseconds 200
    }
}

function Start-McServer([string]$m) {
    if (Get-ServerProcess) { throw 'server already running' }
    Wait-PortFree $McPort
    Wait-PortFree $McRconPort
    $javaHome = Find-Java
    $jbr = Test-IsJbr $javaHome
    $jvm = @('-Xms1G', '-Xmx2G', '-XX:+UseG1GC', '-XX:+EnableDynamicAgentLoading')
    # C1 only: ~4 s faster start, less JIT CPU; the dev server is not throughput-bound
    if (-not $C2) { $jvm += '-XX:TieredStopAtLevel=1' }
    # skip verifying ~20k Knot-loaded classes: ~1.5 s faster start; a bad transform crashes instead of VerifyError
    if (-not $Verify) { $jvm += @('-XX:+UnlockDiagnosticVMOptions', '-XX:-BytecodeVerificationRemote') }
    # JDWP costs ~2 s of startup, so only on request (IDE debugger); hotswap does not need it
    if ($Jdwp) { $jvm += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$McJdwpPort" }
    if ($jbr) { $jvm += '-XX:+AllowEnhancedClassRedefinition' }
    if ($m -eq 'exploded') { $jvm += '-Dfabric.addMods=.dev/mod' }
    $jvm += @('-jar', 'fabric-server-launch.jar', 'nogui')
    $logs = Join-Path $McDir 'logs'
    $p = Start-Process -FilePath (Join-Path $javaHome 'bin\java.exe') -ArgumentList $jvm -WorkingDirectory $McDir `
        -RedirectStandardOutput (Join-Path $logs 'console.out') -RedirectStandardError (Join-Path $logs 'console.err') `
        -WindowStyle Hidden -PassThru
    Set-Content $PidFile $p.Id
    Say "server pid $($p.Id) ($(Split-Path -Leaf $javaHome)$(if ($jbr) {', enhanced redefinition'})$(if ($Jdwp) {", jdwp 127.0.0.1:$McJdwpPort"}), mode $m)"
}

function Wait-McReady([double]$t0) {
    $deadline = (Get-Date).AddSeconds(180)
    while ($true) {
        if (-not (Get-ServerProcess)) {
            Get-Content (Join-Path $McDir 'logs\console.out') -Tail 30 | Out-Host
            throw 'server process exited during startup (log tail above)'
        }
        $r = Invoke-Rcon 'arma status'
        if ($r.ok) { break }
        if ((Get-Date) -gt $deadline) { throw 'RCON not up after 180 s' }
        Start-Sleep -Milliseconds 150
    }
    $tr = Secs $t0
    if (-not (Test-ContainerRunning $GuestName) -and -not (Invoke-BridgeProbe -Port $GuestBridge -Wait 0).ok) {
        Warn "dev guest not running; not waiting for the bridge (dev\guest.ps1 up)"
        return @{ rcon = $tr; bridge = $null }
    }
    $deadline = (Get-Date).AddSeconds(30)
    while ($r.text -notmatch 'bridge\S* \S+ connected') {
        if ((Get-Date) -gt $deadline) { Warn "bridge not connected after 30 s:`n$($r.text)"; return @{ rcon = $tr; bridge = $null } }
        Start-Sleep -Milliseconds 100
        $r = Invoke-Rcon 'arma status'
    }
    return @{ rcon = $tr; bridge = (Secs $t0) }
}

function Stop-McServer {
    $p = Get-ServerProcess
    if (-not $p) { return }
    $r = Invoke-Rcon 'stop'
    if (-not $p.WaitForExit($(if ($r.ok) { 30000 } else { 0 }))) {
        Warn "server did not stop via RCON; killing pid $($p.Id)"
        Stop-Process -Id $p.Id -Force
        $p.WaitForExit(10000) | Out-Null
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

function Restart-Cycle([string]$m, [double]$t0, [switch]$Build) {
    if ($Build) { Build-Mod $m }
    $tb = Secs $t0
    Stop-McServer
    $ts = Secs $t0
    $info = Deploy-Mod $m
    Start-McServer $m
    if ($NoWait) { Say "deployed $info (not waiting)"; return }
    $w = Wait-McReady $t0
    # headless rendering: with no player online no chunks load, so the mod spawns no entities.
    # Force-load the arena area once per world (persisted in the world; flag file lives in it too).
    $flag = Join-Path $McDir 'world\.armacraft-forceloaded'
    if ((Test-Path (Join-Path $McDir 'world')) -and -not (Test-Path $flag)) {
        $r = Invoke-Rcon 'forceload add -16 -144 144 16'
        if ($r.ok) { Set-Content $flag $r.text; Say "forceloaded arena chunks: $($r.text)" }
    }
    Say ("{0}: build {1}s, stopped {2}s, RCON up {3}s, bridge connected {4}s  [{5}]" -f $Command, $tb, $ts, $w.rcon, $(if ($w.bridge) { $w.bridge } else { '-' }), $info)
}

function Invoke-HotSwap([string]$m, [int]$serverPid, [string]$manifest = $Manifest) {
    # compile the tool + agent jar once (a single-file source launch would cost ~1 s per call)
    $toolDir = Join-Path $StateDir 'tools'
    $cls = Join-Path $toolDir 'HotSwap.class'
    $agent = Join-Path $toolDir 'hotswap-agent.jar'
    $jh = Find-Java
    if (-not (Test-Path $agent) -or (Get-Item $cls).LastWriteTime -lt (Get-Item $HotSwapSrc).LastWriteTime) {
        New-Item -ItemType Directory -Force $toolDir | Out-Null
        & (Join-Path $jh 'bin\javac.exe') -d $toolDir $HotSwapSrc
        if ($LASTEXITCODE -ne 0) { throw 'javac HotSwap.java failed' }
        $mf = Join-Path $toolDir 'agent.mf'
        Set-Content $mf "Agent-Class: HotSwapAgent`nCan-Redefine-Classes: true`n" -Encoding Ascii
        & (Join-Path $jh 'bin\jar.exe') cfm $agent $mf -C $toolDir HotSwapAgent.class
        if ($LASTEXITCODE -ne 0) { throw 'jar hotswap-agent.jar failed' }
    }
    $a = @('-cp', $toolDir, 'HotSwap', 'swap', "pid:${serverPid}:$agent", $ClassesDir, $manifest, '--restart-prefix', $MixinPrefix)
    if ($m -eq 'exploded') { $a += '--allow-new' }
    & (Join-Path $jh 'bin\java.exe') @a | Out-Host
    return $LASTEXITCODE
}

# ----------------------------------------------------------------------------- commands

$t0 = Now
try {
    $m = Get-Mode
    switch ($Command) {
        'init' { Initialize-McDir; Say "dev/mcserver ready (mode $m)" }
        'build' { Build-Mod $m }
        'up' {
            Initialize-McDir
            if (Get-ServerProcess) {
                if ($Mode -and $Mode -ne (Get-Content $ModeFile -Raw).Trim()) { Restart-Cycle $m $t0 -Build:(-not $NoBuild); break }
                Say "server already running (pid $((Get-ServerProcess).Id), mode $m)"; break
            }
            Restart-Cycle $m $t0 -Build:(-not $NoBuild)
        }
        'redeploy' {
            Initialize-McDir
            Restart-Cycle $m $t0 -Build:(-not $NoBuild)
        }
        'restart' {
            Restart-Cycle $m $t0
        }
        'hotswap' {
            if ($TargetPid) {
                # any JVM running the mod from $HostDir/build/classes (e.g. gradlew runServer with proposal 4):
                # classes load from that dir, so new classes are fine; no fallback restart (not ours to restart)
                if (-not (Get-Process -Id $TargetPid -ErrorAction SilentlyContinue)) { throw "no process $TargetPid" }
                $pm = Join-Path $StateDir "pid-$TargetPid.manifest"
                if (-not (Test-Path $pm)) {
                    # unknown baseline: mark every non-mixin class changed once (redefining an unchanged
                    # class is harmless); mixins keep their real hash (they cannot be redefined anyway)
                    New-Item -ItemType Directory -Force $StateDir | Out-Null
                    Get-ChildItem $StateDir -Filter 'pid-*.manifest' | Remove-Item -Force
                    $lines = foreach ($e in (Get-TreeHashes $ClassesDir '*.class').GetEnumerator()) {
                        $n = $e.Key.Substring(0, $e.Key.Length - 6).Replace('/', '.')
                        if ($n.StartsWith($MixinPrefix)) { "$n $($e.Value)" } else { "$n 0" }
                    }
                    Set-Content -Path $pm -Value $lines -Encoding Ascii
                }
                if (-not $NoBuild) { Invoke-Gradle @('classes') }
                $rc = Invoke-HotSwap 'exploded' $TargetPid $pm
                if ($rc -ne 0) { throw "hotswap into pid $TargetPid not applied (exit $rc): restart that JVM" }
                Say "hotswap into pid $TargetPid done in $(Secs $t0)s"
                break
            }
            $sp = Get-ServerProcess
            if (-not $sp) { throw 'server not running (dev\host.ps1 up)' }
            $running = (Get-Content $ModeFile -Raw).Trim()
            if (-not $NoBuild) { Invoke-Gradle @('classes') }
            $reason = $null
            if (Test-ResourcesChanged) { $reason = 'resources changed (fabric.mod.json, mixin config, assets are read at startup)' }
            else {
                if ($running -eq 'exploded') { $null = Sync-Exploded }   # new classes become loadable
                $rc = Invoke-HotSwap $running $sp.Id
                switch ($rc) {
                    0 { }
                    3 { $reason = 'JVM refused the redefinition' }
                    4 { $reason = 'change needs a restart' }
                    default { $reason = "hotswap tool failed (exit $rc)" }
                }
            }
            if ($reason) {
                if ($NoFallback) { throw "hotswap not applied: $reason" }
                Warn "hotswap not possible: $reason -> redeploy"
                Restart-Cycle $running $t0 -Build:($running -eq 'jar')
            } else {
                $info = Get-Content $DeployInfo -Raw
                if ($info -match 'hotswaps (\d+)') { $info = $info -replace 'hotswaps \d+', "hotswaps $([int]$Matches[1] + 1)" }
                Set-Content $DeployInfo $info.Trim()
                if ($running -eq 'jar') { Warn 'jar mode: mods/armacraft.jar is now older than the running code (redeploy before restart)' }
                Say "hotswap done in $(Secs $t0)s"
            }
        }
        'down' {
            Stop-McServer
            Say "server stopped ($(Secs $t0)s)"
        }
        'logs' {
            $log = Join-Path $McDir 'logs\latest.log'
            if ($Follow) { Get-Content $log -Tail $Tail -Wait } else { Get-Content $log -Tail $Tail }
        }
        'cfg' {
            # after editing dev/mcserver/config/armacraft.json (needs host proposal 1: /arma reload)
            $r = Invoke-Rcon 'arma reload'
            if ($r.ok -and $r.text -match 'reloaded') { Say "$($r.text) ($(Secs $t0)s)" }
            elseif ($r.ok) { throw "mod has no /arma reload yet (dev/host-proposals.md #1); use dev\host.ps1 restart. Reply: $($r.text)" }
            else { throw "rcon failed: $($r.text)" }
        }
        'rcon' {
            if (-not $Rest) { throw 'usage: dev\host.ps1 rcon "command"' }
            $r = Invoke-Rcon ($Rest -join ' ')
            Write-Host $r.text
            if (-not $r.ok) { exit 2 }
        }
        'status' {
            $p = Get-ServerProcess
            if (-not $p) { Write-Host 'mc: not running'; break }
            $up = [int]((Get-Date) - $p.StartTime).TotalSeconds
            Write-Host "mc: pid $($p.Id) up ${up}s, 127.0.0.1:$McPort, mode $((Get-Content $ModeFile -Raw).Trim())"
            if (Test-Path $DeployInfo) { Write-Host "deployed: $((Get-Content $DeployInfo -Raw).Trim())" }
            $ver = Select-String -Path (Join-Path $McDir 'logs\latest.log') -Pattern '^\s+- armacraft (\S+)' | Select-Object -First 1
            if ($ver) { Write-Host "mod: armacraft $($ver.Matches[0].Groups[1].Value) (from latest.log)" }
            $r = Invoke-Rcon 'arma status'
            if ($r.ok) { Write-Host $r.text } else { Write-Host "rcon: $($r.text)" }
        }
    }
} catch {
    Write-Host "host.ps1 ${Command}: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
