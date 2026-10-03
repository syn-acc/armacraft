<#
.SYNOPSIS
  Tear down the armacraft dev stack: stop the dev MC server (RCON stop, world saved), then
  remove the armacraft-guest-dev container. The guest build volume is kept unless -Wipe.
  Never touches armacraft-guest (4534/4535) or host/run (25565).
#>
param([switch]$Wipe)
. (Join-Path $PSScriptRoot 'lib.ps1')
& (Join-Path $PSScriptRoot 'host.ps1') down
$a = @{}
if ($Wipe) { $a.Wipe = $true }
& (Join-Path $PSScriptRoot 'guest.ps1') down @a
