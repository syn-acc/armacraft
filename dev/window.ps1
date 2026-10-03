<#
  Resize the running armacraft Minecraft window for recording. No restart needed.

  dev\window.ps1 portrait        # borderless 9:16 as tall as the screen minus the taskbar, centred: OBS scales it to 1080x1920
  dev\window.ps1 landscape       # borderless 16:9 as tall as the screen minus the taskbar, centred
  dev\window.ps1 1080            # normal window with a 1920x1080 client area
  dev\window.ps1 restore         # normal resizable window, 1600x900
#>
param([Parameter(Position = 0)][ValidateSet('portrait', 'landscape', '1080', 'restore')][string]$Mode = 'portrait',
      [int]$Id = 0)   # -Id <pid>: a specific game process (default: the play.ps1 game, else the first Minecraft window)

Add-Type @'
using System; using System.Runtime.InteropServices;
public class ArmaWin {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll", EntryPoint="GetWindowLongPtr")] public static extern IntPtr GetStyle(IntPtr h, int i);
  [DllImport("user32.dll", EntryPoint="SetWindowLongPtr")] public static extern IntPtr SetStyle(IntPtr h, int i, IntPtr v);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int w, int hh, uint f);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool AdjustWindowRectEx(ref RECT r, uint style, bool menu, uint ex);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
  [DllImport("user32.dll")] public static extern bool SystemParametersInfo(uint a, uint b, ref RECT r, uint c);
}
'@

$g = if ($Id) { Get-Process -Id $Id -ErrorAction SilentlyContinue } else { Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1 }
if ($g -and -not $g.MainWindowHandle) { throw "process $($g.Id) has no window yet" }
if (-not $g) { throw 'no Minecraft window found' }
$h = $g.MainWindowHandle
$sw = [ArmaWin]::GetSystemMetrics(0); $sh = [ArmaWin]::GetSystemMetrics(1)
# work area = the screen minus the taskbar, so the HUD at the bottom is never covered
$wa = New-Object ArmaWin+RECT; [ArmaWin]::SystemParametersInfo(0x0030, 0, [ref]$wa, 0) | Out-Null
$waW = $wa.R - $wa.L; $waH = $wa.B - $wa.T
$frame = 0x00CF0000   # caption, thick frame, sysmenu, min/max boxes
$flags = 0x0020 -bor 0x0004 -bor 0x0040   # FRAMECHANGED | NOZORDER | SHOWWINDOW
[ArmaWin]::ShowWindow($h, 9) | Out-Null
$style = [ArmaWin]::GetStyle($h, -16).ToInt64()

function Set-Borderless([int]$w, [int]$hh) {
    $new = ($style -band (-bnot $frame)) -bor 0x80000000
    [ArmaWin]::SetStyle($h, -16, [IntPtr]$new) | Out-Null
    [ArmaWin]::SetWindowPos($h, [IntPtr]::Zero, $wa.L + [int](($waW - $w) / 2), $wa.T + [int](($waH - $hh) / 2), $w, $hh, $flags) | Out-Null
}
function Set-Framed([int]$w, [int]$hh) {
    $new = ($style -band (-bnot 0x80000000)) -bor $frame
    [ArmaWin]::SetStyle($h, -16, [IntPtr]$new) | Out-Null
    $r = New-Object ArmaWin+RECT; $r.L = 0; $r.T = 0; $r.R = $w; $r.B = $hh
    [ArmaWin]::AdjustWindowRectEx([ref]$r, [uint32]$new, $false, 0) | Out-Null
    $ow = $r.R - $r.L; $oh = $r.B - $r.T
    [ArmaWin]::SetWindowPos($h, [IntPtr]::Zero, [int](($sw - $ow) / 2), [Math]::Max(0, [int](($sh - $oh) / 2)), $ow, $oh, $flags) | Out-Null
}

switch ($Mode) {
    'portrait' { $ph = $waH - ($waH % 16); Set-Borderless ([int]($ph * 9 / 16)) $ph }
    'landscape' { $lh = [Math]::Min($waH, 1440); $lh -= $lh % 9; Set-Borderless ([int]($lh * 16 / 9)) $lh }
    '1080' { Set-Framed 1920 1080 }
    'restore' { Set-Framed 1600 900 }
}
Start-Sleep -Milliseconds 500
$c = New-Object ArmaWin+RECT; [ArmaWin]::GetClientRect($h, [ref]$c) | Out-Null
"Minecraft window (pid $($g.Id)): client $($c.R)x$($c.B) [$Mode]"
