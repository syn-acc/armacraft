<#
.SYNOPSIS
  Build and run the armacraft guest: patched Armagetron Advanced dedicated server
  with the JSON bridge.

.DESCRIPTION
  Ports are published on localhost only:
    127.0.0.1:4534/udp  Armagetron clients
    127.0.0.1:4535/tcp  bridge (newline-delimited JSON, PROTOCOL.md v1; no auth in v1)
  Inside the container the bridge binds 0.0.0.0 (guest/server/bridge.cfg).
  guest/server is bind-mounted read-only over /etc/armacraft, so config edits only
  need a restart (use -NoConfigMount to run with the copy baked into the image).

  Attached runs forward stdin, so you can type server console commands.

.EXAMPLE
  .\guest\run.ps1                 # build, then run attached (Ctrl+C stops)
  .\guest\run.ps1 -Detach         # build, then run in the background
  .\guest\run.ps1 -NoBuild        # run the last built image
  docker logs -f armacraft-guest  # follow a detached run
  docker rm -f armacraft-guest    # stop it
#>
param(
    [switch]$NoBuild,
    [switch]$Detach,
    [switch]$NoConfigMount,
    [string]$Name = "armacraft-guest",
    [string]$Image = "armacraft-guest:dev",
    [string[]]$ServerArgs = @()
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $NoBuild) {
    Write-Host "==> docker build -t $Image $here"
    docker build -t $Image $here
    if ($LASTEXITCODE -ne 0) { Write-Error "docker build failed ($LASTEXITCODE)"; exit $LASTEXITCODE }
}

# replace a previous container of the same name
$old = docker ps -aq --filter "name=^$Name$"
if ($old) {
    Write-Host "==> removing old container $Name"
    docker rm -f $Name | Out-Null
}

$runArgs = @(
    "run", "--rm", "--name", $Name,
    "-p", "127.0.0.1:4534:4534/udp",
    "-p", "127.0.0.1:4535:4535/tcp"
)
if (-not $NoConfigMount) {
    $runArgs += @("-v", "$(Join-Path $here 'server'):/etc/armacraft:ro")
}
if ($Detach) {
    $runArgs += "-d"
} elseif ([Console]::IsInputRedirected) {
    $runArgs += "-i"
} else {
    $runArgs += "-it"
}
$runArgs += $Image
$runArgs += $ServerArgs

Write-Host "==> docker $($runArgs -join ' ')"
docker @runArgs
exit $LASTEXITCODE
