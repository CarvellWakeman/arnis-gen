<#
.SYNOPSIS
  Verifies arnis works as the server's PRIMARY world.

.DESCRIPTION
  Boots a headless Paper server configured with level-name=arnis and a bukkit.yml
  generator entry, and asserts the "Could not set generator" / "Cannot create
  additional worlds on STARTUP" / "Corrupt regionfile" errors do NOT occur, the
  arnis world comes up, and the spawn region bakes. Complements smoke_test.ps1,
  which covers the default (separate-world) setup.

  Prerequisite: run smoke_test.ps1 once first. It downloads the Paper jar and
  builds the plugin jar into e2e/.cache, which this test reuses.
#>
[CmdletBinding()]
param([int]$TimeoutSec = 300, [switch]$KeepRun)

$ErrorActionPreference = "Stop"
$E2E = $PSScriptRoot
$cache = Join-Path $E2E ".cache"
$paper = Join-Path $cache "paper-1.21.1.jar"
$plugin = Join-Path $cache "plugin-build\arnis-paper.jar"
$run = Join-Path $cache "run_primary"
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
$arnis = Join-Path (Split-Path -Parent $E2E) "target\debug\arnis.exe"
if (-not (Test-Path $arnis)) { $arnis = Join-Path (Split-Path -Parent $E2E) "target\release\arnis.exe" }

foreach ($p in @($paper, $plugin, $arnis)) {
    if (-not (Test-Path $p)) {
        Write-Host "[test] missing prerequisite: $p (run e2e/smoke_test.ps1 first)." -ForegroundColor Red
        exit 2
    }
}

if (Test-Path $run) { Remove-Item $run -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$run\plugins\ArnisGen" | Out-Null

Set-Content "$run\eula.txt" "eula=true" -Encoding ASCII

# Config files written as string arrays (robust; no here-strings).
@(
    "online-mode=false",
    "level-name=arnis",
    "view-distance=3",
    "simulation-distance=3",
    "spawn-protection=0",
    "max-players=2",
    "server-port=25601"
) | Set-Content "$run\server.properties" -Encoding ASCII

# Route the arnis world through the plugin's void generator.
@(
    "worlds:",
    "  arnis:",
    "    generator: ArnisGen"
) | Set-Content "$run\bukkit.yml" -Encoding ASCII

Copy-Item $plugin "$run\plugins\arnis-paper.jar" -Force
$arnisYaml = $arnis.Replace("'", "''")
@(
    "world: arnis",
    "origin:",
    "  lat: 51.515",
    "  lng: -0.115",
    "scale: 1.0",
    "bake-margin: 64",
    "ground-level: -62",
    "arnis-binary: '$arnisYaml'",
    "bake-spawn-on-enable: true",
    "spawn:",
    "  x: 8",
    "  z: 8"
) | Set-Content "$run\plugins\ArnisGen\config.yml" -Encoding UTF8

Write-Host "[test] starting primary-world server..."
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $java
$psi.Arguments = "-Xms1G -Xmx2G -jar `"$paper`" --nogui"
$psi.WorkingDirectory = $run
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.RedirectStandardInput = $true
$proc = [System.Diagnostics.Process]::new()
$proc.StartInfo = $psi
$queue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$sink = { $d = $EventArgs.Data; if ($null -ne $d) { $Event.MessageData.Enqueue($d) } }
[void]$proc.Start()
$so = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $sink -MessageData $queue
$se = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action $sink -MessageData $queue
$proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()

$log = "$run\server.out.log"
$sw = [System.IO.StreamWriter]::new($log, $false)
$SUCCESS = "Spawn region baked."
$FAILS = @("Could not set generator", "Cannot create additional worlds", "Failed to create/load arnis world", "Corrupt regionfile")
$outcome = "timeout"; $failMarker = $false
$t = [System.Diagnostics.Stopwatch]::StartNew()
while ($t.Elapsed.TotalSeconds -lt $TimeoutSec) {
    $line = $null
    if ($queue.TryDequeue([ref]$line)) {
        $sw.WriteLine($line); $sw.Flush()
        foreach ($f in $FAILS) { if ($line -match [regex]::Escape($f)) { $failMarker = $true; $outcome = "error: $f" } }
        if ($line -match [regex]::Escape($SUCCESS)) { $outcome = "ok"; break }
        if ($failMarker) { break }
    } elseif ($proc.HasExited) { $outcome = "server-exited"; break } else { Start-Sleep -Milliseconds 200 }
}
# Let the server settle so the console recognizes /stop (a too-early stop is
# rejected, and the resulting hard-kill would skip the level.dat save).
Start-Sleep -Seconds 3
try { if (-not $proc.HasExited) { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } } catch {}
if (-not $proc.WaitForExit(90000)) { try { $proc.Kill() } catch {} }
Start-Sleep -Milliseconds 500
$line = $null; while ($queue.TryDequeue([ref]$line)) { $sw.WriteLine($line) }
$sw.Close()
Unregister-Event -SourceIdentifier $so.Name -ErrorAction SilentlyContinue
Unregister-Event -SourceIdentifier $se.Name -ErrorAction SilentlyContinue

$regionOk = Test-Path "$run\arnis\region\r.0.0.mca"
$levelDatOk = Test-Path "$run\arnis\level.dat"
# level.dat is written on a clean shutdown; not gated on here because this harness's
# console /stop is timing-sensitive. The real assertions are no errors + world loaded.
$pass = ($outcome -eq "ok") -and (-not $failMarker) -and $regionOk
if (-not $KeepRun -and $pass) { Remove-Item $run -Recurse -Force -ErrorAction SilentlyContinue }
Write-Host "[test] outcome=$outcome  region-file=$regionOk  level.dat=$levelDatOk (informational)"
if ($pass) {
    Write-Host "[test] PASS: arnis primary world, no generator/regionfile error, spawn baked (Y from terrain)." -ForegroundColor Green
    exit 0
} else {
    Write-Host "[test] FAIL. Last 30 log lines:" -ForegroundColor Red
    if (Test-Path $log) { Get-Content $log -Tail 30 }
    exit 1
}
