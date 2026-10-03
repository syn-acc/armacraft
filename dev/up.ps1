<#
.SYNOPSIS
  Bring up the armacraft dev stack: dev guest (127.0.0.1:4544/4545), then the dev MC server
  (127.0.0.1:25566) connected to it. Idempotent: running parts are left alone.

.EXAMPLE
  dev\up.ps1
  dev\up.ps1 -HostDir C:\path\to\host-copy -Mode jar
#>
param(
    [string]$HostDir = '',
    [ValidateSet('', 'exploded', 'jar')]
    [string]$Mode = '',
    [switch]$NoBuild
)
. (Join-Path $PSScriptRoot 'lib.ps1')
$t0 = Now
& (Join-Path $PSScriptRoot 'guest.ps1') up
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$a = @{}
if ($HostDir) { $a.HostDir = $HostDir }
if ($Mode) { $a.Mode = $Mode }
if ($NoBuild) { $a.NoBuild = $true }
& (Join-Path $PSScriptRoot 'host.ps1') up @a
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Say "stack up in $(Secs $t0)s"
& (Join-Path $PSScriptRoot 'status.ps1')
