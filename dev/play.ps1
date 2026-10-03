<#
.SYNOPSIS
  armacraft play loop: get host/ changes into the game the user is playing, in seconds, without ever
  letting a running game see half of a new build.

.DESCRIPTION
  The play copy (.work/play-shaders, else .work/play; -Play or $env:ARMA_PLAY_DIR for another) keeps its
  run dir, mods, shaderpacks and client jar. What this script owns is <play>/.fastloop:

    live/      the mod (classes + resources of host's main and client source sets, merged) that a game
               started by `launch` loads. Only two things ever write here: `launch` (before the JVM
               exists) and a successful `push` (through the agent inside the JVM, after the swap).
    staged/    the next build, written by `stage`. Never on any class path. `launch` renames it to live/.
    run.spec   the play copy's runClient launch spec (class path, JVM args), exported from its gradle
               build by dev/tools/play-export.init.gradle. The play copy's own files are not edited.

  The play copy's src/ and build/ are never touched, so nothing here can repeat the crash where a game
  lazy-loaded a class from a build dir that had been recompiled under it.

  Commands:
    push         compile host/, then swap the changed classes of BOTH source sets into the running game
                 (dev/tools/HotSwap.java push: load-then-redefine inside the JVM, disk after memory).
                 Refuses with "RESTART NEEDED" and changes nothing when the change cannot be applied
                 live: a mixin, a removed or renamed class, a startup-only resource, or a structural
                 change on a JVM without enhanced redefinition. Exit 0 live, 10 restart needed, 1 error.
                 A game started by the play copy's own `gradlew runClient` is refused too, unless
                 -GradleGame (same mechanism into its build/classes; not tested).
    stage        compile host/ and write the build to staged/. Safe while the game runs.
    launch       start exactly one game window from live/ (staged/ is promoted first). Refuses when this
                 play copy's game is already running. No watcher, no relaunch.
    status       what is running, live, staged and built.
    config-diff  each ride config file of the play copy against the defaults of the current host build.

.EXAMPLE
  dev\play.ps1 launch                 # first time: stages host/ and starts the game
  dev\play.ps1 push                   # after an edit in host/: live in the running game in ~5 s
  dev\play.ps1 stage                  # push said RESTART NEEDED: prepare it; quit the game; launch
  dev\play.ps1 launch -Jdk jbr        # JetBrains Runtime: push also takes new fields and methods
  dev\play.ps1 config-diff            # which config values override the current defaults
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('push', 'stage', 'launch', 'status', 'config-diff')]
    [string]$Command = 'status',
    [string]$Play = '',
    [string]$HostDir = '',
    [int]$TargetPid = 0,
    [switch]$NoBuild,
    [switch]$DryRun,
    [switch]$All,
    [ValidateSet('', 'jbr', 'temurin')]
    [string]$Jdk = '',
    [string]$World = '',
    [string[]]$JvmArg = @(),
    [string[]]$GameArg = @(),
    [switch]$Online,
    [switch]$GradleGame
)

. (Join-Path $PSScriptRoot 'lib.ps1')

if (-not $HostDir) { $HostDir = if ($env:ARMA_HOST_DIR) { $env:ARMA_HOST_DIR } else { Join-Path $Root 'host' } }
$HostDir = (Resolve-Path $HostDir).Path
if (-not $Play) {
    $Play = if ($env:ARMA_PLAY_DIR) { $env:ARMA_PLAY_DIR }
            elseif (Test-Path (Join-Path $Root '.work\play-shaders')) { Join-Path $Root '.work\play-shaders' }
            else { Join-Path $Root '.work\play' }
}
if (-not (Test-Path $Play)) { Write-Host "play.ps1: no play copy at $Play (-Play <dir>)" -ForegroundColor Red; exit 1 }
$Play = (Resolve-Path $Play).Path.TrimEnd('\')
$State = Join-Path $Play '.fastloop'
$Live = Join-Path $State 'live'
$Staged = Join-Path $State 'staged'
$Spec = Join-Path $State 'run.spec'
$PidFile = Join-Path $State 'game.pid'
$JdkFile = Join-Path $State 'jdk'
$ToolDir = Join-Path $DevDir '.play\tools'
$HotSwapSrc = Join-Path $DevDir 'tools\HotSwap.java'
$ExportInit = Join-Path $DevDir 'tools\play-export.init.gradle'
$StampName = 'armacraft-build.properties'
# resources the mod reads again at run time: safe to replace under a running game
$LiveRes = @($StampName, 'data/armacraft/ride_presets/')
$Sets = @(
    @{ name = 'main'; anchor = 'gl.syn.armacraft.Armacraft' },
    @{ name = 'client'; anchor = 'gl.syn.armacraft.client.ride.RideClientMod' }
)

# ----------------------------------------------------------------------------- helpers

function Find-Jdk([string]$kind) {
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    $pat = if ($kind -eq 'jbr') { '^jbr.*25' } else { '^jdk-25' }
    $c = Get-ChildItem $jdks -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match $pat } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $c) { throw "no $kind JDK 25 in $jdks" }
    return $c.FullName
}

function Invoke-Gradle([string]$dir, [string[]]$tasks) {
    $saved = $env:JAVA_HOME
    $env:JAVA_HOME = Find-Jdk 'temurin'   # the JDK every other gradle user here runs: warm daemons get reused
    $t = Now
    try {
        $a = @('-p', $dir) + $tasks + @('--console=plain', '-q')
        if (-not $Online) { $a += '--offline' }
        & (Join-Path $dir 'gradlew.bat') @a 2>&1 | ForEach-Object {
            $l = "$_"
            if ($l -and $l -notmatch 'Deprecated Gradle features|warning-mode|command_line_warnings|Problems report is available|^\s*$') { Write-Host $l }
        }
        $rc = $LASTEXITCODE
    } finally { $env:JAVA_HOME = $saved }
    if ($rc -ne 0) { throw "gradle $($tasks -join ' ') failed in $dir" }
    return (Secs $t)
}

function Build-Host {
    if ($NoBuild) { return 0 }
    return (Invoke-Gradle $HostDir @('classes', 'clientClasses'))
}

function Get-SetDirs([string]$name) {
    $c = Join-Path $HostDir "build\classes\java\$name"
    $r = Join-Path $HostDir "build\resources\$name"
    if (-not (Test-Path $c)) { throw "missing $c (build host/ first)" }
    return @{ classes = $c; resources = $r }
}

function Get-Stamp([string]$dir) {
    $f = Join-Path $dir $StampName
    if (Test-Path $f) { $m = Select-String -Path $f -Pattern '^build=(.*)$' | Select-Object -First 1; if ($m) { return $m.Matches[0].Groups[1].Value.Trim() } }
    return $null
}

function Get-MixinPrefixes {
    # every package a *.mixins.json of the new build names: classes in it are merged into Minecraft at load
    $out = @()
    foreach ($s in $Sets) {
        $r = (Get-SetDirs $s.name).resources
        foreach ($f in Get-ChildItem $r -Filter '*.mixins.json' -ErrorAction SilentlyContinue) {
            $pkg = (Get-Content $f.FullName -Raw | ConvertFrom-Json).package
            if ($pkg) { $out += "$pkg." }
        }
    }
    return ($out | Select-Object -Unique)
}

function Build-Tools {
    $cls = Join-Path $ToolDir 'HotSwap.class'
    $agent = Join-Path $ToolDir 'hotswap-agent.jar'
    $jh = Find-Jdk 'temurin'
    if (-not (Test-Path $agent) -or (Get-Item $cls).LastWriteTime -lt (Get-Item $HotSwapSrc).LastWriteTime) {
        New-Item -ItemType Directory -Force $ToolDir | Out-Null
        & (Join-Path $jh 'bin\javac.exe') -d $ToolDir $HotSwapSrc
        if ($LASTEXITCODE -ne 0) { throw 'javac HotSwap.java failed' }
        $mf = Join-Path $ToolDir 'agent.mf'
        Set-Content $mf "Agent-Class: HotSwapAgent`nCan-Redefine-Classes: true`n" -Encoding Ascii
        & (Join-Path $jh 'bin\jar.exe') cfm $agent $mf -C $ToolDir HotSwapAgent.class
        if ($LASTEXITCODE -ne 0) { throw 'jar hotswap-agent.jar failed' }
    }
    return @{ java = (Join-Path $jh 'bin\java.exe'); dir = $ToolDir; agent = $agent }
}

function Get-JavaProcs {
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine }
}

# The game of THIS play copy: started by `launch` (its argfile is under .fastloop), or by the play
# copy's own `gradlew runClient` (the dev-launch-injector config under <play>\.gradle).
function Find-Game {
    if ($TargetPid) {
        if (-not (Get-Process -Id $TargetPid -ErrorAction SilentlyContinue)) { throw "no process $TargetPid" }
        return @{ pid = $TargetPid; how = '-TargetPid' }
    }
    $mine = (Join-Path $State 'launch.args').ToLower()
    $dli = (Join-Path $Play '.gradle\loom-cache\launch.cfg').ToLower()
    $found = @()
    foreach ($p in Get-JavaProcs) {
        $cl = $p.CommandLine.ToLower()
        if ($cl.Contains($mine)) { $found += @{ pid = [int]$p.ProcessId; how = 'play.ps1 launch' } }
        elseif ($cl.Contains($dli) -and $cl.Contains('fabric.dli.env=client')) { $found += @{ pid = [int]$p.ProcessId; how = 'gradlew runClient' } }
    }
    if ($found.Count -gt 1) { throw "more than one game runs from $Play (pids $(($found | ForEach-Object { $_.pid }) -join ', ')): pick one with -TargetPid" }
    if ($found.Count -eq 1) { return $found[0] }
    return $null
}

function Get-SpecKey {
    $parts = @()
    foreach ($f in 'build.gradle', 'gradle.properties', 'settings.gradle') {
        $p = Join-Path $Play $f
        if (Test-Path $p) { $parts += (Get-FileHash $p -Algorithm SHA256).Hash }
    }
    foreach ($f in Get-ChildItem (Join-Path $Play 'libs') -File -ErrorAction SilentlyContinue | Sort-Object Name) { $parts += "$($f.Name):$($f.Length):$($f.LastWriteTimeUtc.Ticks)" }
    $parts += (Get-FileHash $ExportInit -Algorithm SHA256).Hash
    return ($parts -join '|')
}

# Export the play copy's launch spec. Runs gradle in the play copy, so only while its game is not running
# (a running game keeps the spec it was started with; a stale spec only matters for the next launch).
function Update-Spec([bool]$gameRunning) {
    $keyFile = "$Spec.key"
    $key = Get-SpecKey
    if ((Test-Path $Spec) -and (Test-Path $keyFile) -and ((Get-Content $keyFile -Raw).Trim() -eq $key)) { return }
    if ($gameRunning) {
        if (Test-Path $Spec) { Warn "the play copy's build.gradle/libs changed; the launch spec is refreshed at the next launch (gradle is not run in the play copy while its game runs)" }
        return
    }
    New-Item -ItemType Directory -Force $State | Out-Null
    $s = Invoke-Gradle $Play @('--init-script', $ExportInit, '--no-configuration-cache', 'armaExportRun', "-Parma.export=$Spec")
    if (-not (Test-Path $Spec)) { throw "launch spec export produced no $Spec" }
    Set-Content $keyFile $key -Encoding Ascii
    Say "exported the play copy's launch spec (${s}s)"
}

function Copy-Build([string]$dst) {
    # merged mod dir: classes + resources of main and client (what the jar would hold)
    New-Item -ItemType Directory -Force $dst | Out-Null
    foreach ($s in $Sets) {
        $d = Get-SetDirs $s.name
        foreach ($src in @($d.classes, $d.resources)) {
            if (Test-Path $src) {
                robocopy $src $dst /E /NFL /NDL /NJH /NJS /NP /R:2 /W:1 | Out-Null
                if ($LASTEXITCODE -ge 8) { throw "copy $src -> $dst failed (robocopy $LASTEXITCODE)" }
            }
        }
    }
    $global:LASTEXITCODE = 0
    if (-not (Test-Path (Join-Path $dst 'fabric.mod.json'))) { throw "staged build has no fabric.mod.json ($dst)" }
}

function Invoke-Stage {
    $tb = Build-Host
    $tmp = "$Staged.tmp-$PID"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force $State | Out-Null
    Copy-Build $tmp
    # staged/ is on no class path: replacing it cannot affect a running game
    if (Test-Path $Staged) { Remove-Item $Staged -Recurse -Force }
    Rename-Item $tmp (Split-Path -Leaf $Staged)
    $n = (Get-ChildItem $Staged -Recurse -File -Filter '*.class').Count
    return @{ build = $tb; classes = $n; stamp = (Get-Stamp $Staged) }
}

function ConvertTo-ArgLine([string]$a) {
    # java @argfile syntax: quote, with backslashes doubled inside the quotes
    return '"' + $a.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Get-JdkChoice {
    if ($Jdk) { return $Jdk }
    if (Test-Path $JdkFile) { return (Get-Content $JdkFile -Raw).Trim() }
    return 'temurin'
}

# ----------------------------------------------------------------------------- commands

$t0 = Now
try {
    switch ($Command) {
        'push' {
            $game = Find-Game
            if (-not $game) { throw "no game is running from $Play, so there is nothing to push into. 'dev\play.ps1 stage' prepares this build for the next 'dev\play.ps1 launch'." }
            if ($game.how -eq 'gradlew runClient' -and -not $GradleGame) {
                # same mechanism, but pushing into the play copy's build/classes has not been tested: opt in
                Warn "RESTART NEEDED: the running game (pid $($game.pid)) was started by gradlew runClient, not by dev\play.ps1 launch. Nothing changed. Next: dev\play.ps1 stage   (safe while playing), quit the game, dev\play.ps1 launch.  (-GradleGame pushes into it anyway: untested.)"
                exit 10
            }
            $tb = Build-Host
            $tools = Build-Tools
            $a = @('-cp', $tools.dir, 'HotSwap', 'push', "$($game.pid)", $tools.agent)
            foreach ($s in $Sets) { $d = Get-SetDirs $s.name; $a += @('--set', "$($d.classes)|$($d.resources)|$($s.anchor)") }
            foreach ($p in Get-MixinPrefixes) { $a += @('--mixin-prefix', $p) }
            foreach ($r in $LiveRes) { $a += @('--live-res', $r) }
            $a += '--announce'   # the game says so in chat (BuildStamp.onHotPush)
            if ($DryRun) { $a += '--dry-run' }
            & $tools.java @a | Out-Host
            $rc = $LASTEXITCODE
            switch ($rc) {
                0 {
                    if (-not $DryRun -and (Test-Path $Staged)) { Remove-Item $Staged -Recurse -Force; Say 'dropped staged/ (older than what the game now runs)' }
                    if ($game.how -eq 'gradlew runClient') { Warn "this game was started by gradlew runClient: its build/classes now hold the pushed build, but the play copy's src does not. Start the next one with dev\play.ps1 launch." }
                    Say ("push: pid {0} ({1}), build {2}s, total {3}s" -f $game.pid, $game.how, $tb, (Secs $t0))
                }
                { $_ -in 3, 4 } {
                    Warn ("RESTART NEEDED (nothing changed, {0}s). Next: dev\play.ps1 stage   (safe while playing), quit the game, dev\play.ps1 launch" -f (Secs $t0))
                    exit 10
                }
                default { throw "push failed (HotSwap exit $rc)" }
            }
        }
        'stage' {
            $game = Find-Game
            $r = Invoke-Stage
            Update-Spec ([bool]$game)
            Say ("staged build {0} ({1} classes) in {2}s (gradle {3}s). {4}" -f $r.stamp, $r.classes, (Secs $t0), $r.build,
                $(if ($game) { "The running game (pid $($game.pid)) is untouched; it loads at the next dev\play.ps1 launch." } else { 'It loads at the next dev\play.ps1 launch.' }))
        }
        'launch' {
            $game = Find-Game
            if ($game) { throw "a game from $Play is already running (pid $($game.pid), $($game.how)). One window: close it first, or use dev\play.ps1 push." }
            if (-not (Test-Path $Staged) -and -not (Test-Path $Live)) { $r = Invoke-Stage; Say "first launch: staged build $($r.stamp)" }
            Update-Spec $false
            if (Test-Path $Staged) {
                # no JVM is using live/: swap the whole dir
                if (Test-Path $Live) { Remove-Item $Live -Recurse -Force }
                Rename-Item $Staged (Split-Path -Leaf $Live)
            }
            $spec = Get-Content $Spec
            $get = { param($k) $spec | Where-Object { $_.StartsWith("$k=") } | ForEach-Object { $_.Substring($k.Length + 1) } }
            $buildDir = (& $get 'build' | Select-Object -First 1).TrimEnd('\').ToLower() + '\'
            # the play copy's own compiled classes/resources are replaced by live/
            $cp = @($Live) + @(& $get 'cp' | Where-Object { -not ($_.ToLower() + '\').StartsWith($buildDir) })
            $missing = @($cp | Where-Object { -not (Test-Path $_) })
            if ($missing) { throw "class path entries missing: $($missing -join ', ') (delete $Spec to re-export)" }
            $kind = Get-JdkChoice
            $javaHome = Find-Jdk $kind
            $jvm = @(& $get 'jvm')
            if ($jvm -notcontains '-XX:+EnableDynamicAgentLoading') { $jvm += '-XX:+EnableDynamicAgentLoading' }
            if ($kind -eq 'jbr') { $jvm += '-XX:+AllowEnhancedClassRedefinition' }
            $jvm += @('-Dfile.encoding=UTF-8') + $JvmArg
            $gameArgs = @(& $get 'arg') + $GameArg
            if ($World) { $gameArgs += @('--quickPlaySingleplayer', $World) }
            $lines = @($jvm | ForEach-Object { ConvertTo-ArgLine $_ }) + @('-cp', (ConvertTo-ArgLine ($cp -join ';')), (& $get 'main' | Select-Object -First 1)) + @($gameArgs | ForEach-Object { ConvertTo-ArgLine $_ })
            $argFile = Join-Path $State 'launch.args'
            [IO.File]::WriteAllLines($argFile, $lines, (New-Object Text.UTF8Encoding $false))
            $work = & $get 'workdir' | Select-Object -First 1
            $logs = Join-Path $State 'logs'
            New-Item -ItemType Directory -Force $logs, $work | Out-Null
            # javaw: no console window. One process, not watched, not relaunched.
            $p = Start-Process -FilePath (Join-Path $javaHome 'bin\javaw.exe') -ArgumentList "`"@$argFile`"" -WorkingDirectory $work `
                -RedirectStandardOutput (Join-Path $logs 'game.out') -RedirectStandardError (Join-Path $logs 'game.err') -PassThru
            Set-Content $PidFile $p.Id
            Set-Content $JdkFile $kind
            Say ("launched pid {0}: build {1}, {2}{3}, run dir {4} ({5}s)" -f $p.Id, (Get-Stamp $Live), (Split-Path -Leaf $javaHome),
                $(if ($kind -eq 'jbr') { ' + enhanced redefinition' } else { '' }), $work, (Secs $t0))
        }
        'status' {
            Write-Host "play copy: $Play"
            $game = Find-Game
            if ($game) { $gp = Get-Process -Id $game.pid; Write-Host ("game:      pid {0} ({1}), up {2}s" -f $game.pid, $game.how, [int]((Get-Date) - $gp.StartTime).TotalSeconds) }
            else { Write-Host 'game:      not running' }
            Write-Host ("live:      {0}" -f $(if (Test-Path $Live) { "build $(Get-Stamp $Live)" } else { 'none (dev\play.ps1 launch stages one)' }))
            Write-Host ("staged:    {0}" -f $(if (Test-Path $Staged) { "build $(Get-Stamp $Staged) (loads at the next launch)" } else { 'nothing' }))
            $hb = Get-Stamp (Join-Path $HostDir 'build\resources\client')
            Write-Host ("host:      {0}" -f $(if ($hb) { "last compiled build $hb" } else { 'not built' }))
            Write-Host ("jdk:       {0} (-Jdk jbr|temurin at launch)" -f (Get-JdkChoice))
        }
        'config-diff' {
            if (-not $NoBuild) { $null = Invoke-Gradle $HostDir @('classes') }
            $cls = (Get-SetDirs 'main').classes
            $cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
            $jars = foreach ($m in @('com.google.code.gson\gson', 'org.slf4j\slf4j-api')) {
                $j = Get-ChildItem (Join-Path $cache $m) -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object { [version]($_.Directory.Parent.Name -replace '[^\d.].*$', '') } -Descending | Select-Object -First 1
                if (-not $j) { throw "no $m jar in the gradle cache" }
                $j.FullName
            }
            $cfgDir = Join-Path $Play 'run-client\config'
            if (Test-Path $Spec) { $w = Get-Content $Spec | Where-Object { $_.StartsWith('workdir=') } | Select-Object -First 1; if ($w) { $cfgDir = Join-Path $w.Substring(8) 'config' } }
            Write-Host "config dir: $cfgDir  (defaults: $HostDir, build $(Get-Stamp (Join-Path $HostDir 'build\resources\client')))"
            Write-Host "  * overrides the current default   ~ still the previous default (adopted at the next load)   + not a known key"
            $a = @('-cp', (@($cls) + $jars -join ';'), 'gl.syn.armacraft.config.ConfigDiff', $cfgDir)
            if ($All) { $a += '--all' }
            & (Join-Path (Find-Jdk 'temurin') 'bin\java.exe') @a 2>$null | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "ConfigDiff failed (exit $LASTEXITCODE)" }
        }
    }
} catch {
    Write-Host "play.ps1 ${Command}: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
