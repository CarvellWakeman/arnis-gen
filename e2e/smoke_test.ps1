<#
.SYNOPSIS
  End-to-end smoke test for the arnis-paper server terrain generator.

.DESCRIPTION
  Boots a real, headless Paper 1.21.1 server with the arnis-paper plugin and the
  arnis region-bake backend, and asserts that the plugin creates the void world
  and bakes the spawn region (r.0.0.mca) with no errors, then shuts the server
  down cleanly. Re-runnable: downloads the Paper server jar and the plugin's
  compile dependencies into a gitignored cache, builds the plugin with javac, and
  uses a throwaway run directory each time.

  Requires: JDK 21+ (JAVA_HOME), a Rust toolchain (to build arnis if no binary is
  supplied), and internet access (Paper jar, Maven deps, and the OSM/elevation
  data arnis fetches while baking).

.PARAMETER Origin
  Real-world "lat,lng" mapped to Minecraft (0,0). Default is central London.

.PARAMETER TimeoutSec
  How long to wait for the spawn-region bake before failing.

.PARAMETER ArnisBinary
  Path to a prebuilt arnis executable. If omitted, an existing target/release or
  target/debug binary is used, or one is built with cargo.

.PARAMETER KeepRun
  Keep the server run directory (for inspection) instead of deleting it.
#>
[CmdletBinding()]
param(
    [string]$Origin = "51.515,-0.115",
    [int]$TimeoutSec = 360,
    [string]$ArnisBinary = "",
    [switch]$KeepRun
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$E2E = $PSScriptRoot
$Cache = Join-Path $E2E ".cache"
$LibsDir = Join-Path $Cache "libs"
$RunDir = Join-Path $Cache "run"
New-Item -ItemType Directory -Force -Path $Cache, $LibsDir | Out-Null

function Info($m) { Write-Host "[e2e] $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "[e2e] FAIL: $m" -ForegroundColor Red; exit 1 }

function Get-File($url, $dest) {
    if (Test-Path $dest) { return }
    Info "Downloading $(Split-Path -Leaf $dest)"
    Invoke-WebRequest -UseBasicParsing -Headers @{ "User-Agent" = "arnis-e2e/1.0" } $url -OutFile $dest
}

# --- 1. Resolve the arnis binary (build if needed) --------------------------------
function Resolve-Arnis {
    if ($ArnisBinary -ne "" -and (Test-Path $ArnisBinary)) { return (Resolve-Path $ArnisBinary).Path }
    $rel = Join-Path $RepoRoot "target\release\arnis.exe"
    $dbg = Join-Path $RepoRoot "target\debug\arnis.exe"
    if (Test-Path $rel) { return $rel }
    if (Test-Path $dbg) { return $dbg }
    Info "No arnis binary found; building (cargo build --release)..."
    $cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
    if (-not (Test-Path $cargo)) { $cargo = "cargo" }
    Push-Location $RepoRoot
    try { & $cargo build --release --bin arnis; if ($LASTEXITCODE -ne 0) { Fail "cargo build failed" } }
    finally { Pop-Location }
    if (-not (Test-Path $rel)) { Fail "arnis binary missing after build" }
    return $rel
}
$Arnis = Resolve-Arnis
Info "arnis binary: $Arnis"

# --- 2. Paper server jar (cached) ------------------------------------------------
$PaperJar = Join-Path $Cache "paper-1.21.1.jar"
if (-not (Test-Path $PaperJar)) {
    Info "Resolving latest Paper 1.21.1 build..."
    $b = (Invoke-WebRequest -UseBasicParsing -Headers @{ "User-Agent" = "arnis-e2e/1.0" } `
            "https://fill.papermc.io/v3/projects/paper/versions/1.21.1/builds/latest").Content | ConvertFrom-Json
    Get-File $b.downloads.'server:default'.url $PaperJar
}
Info "paper jar: $PaperJar"

# --- 3. Plugin compile dependencies (cached) -------------------------------------
$papermc = "https://repo.papermc.io/repository/maven-public"
$central = "https://repo1.maven.org/maven2"
# Resolve the paper-api snapshot jar name dynamically.
$apiBase = "$papermc/io/papermc/paper/paper-api/1.21.1-R0.1-SNAPSHOT"
[xml]$apiMeta = (Invoke-WebRequest -UseBasicParsing "$apiBase/maven-metadata.xml").Content
$apiVer = ($apiMeta.metadata.versioning.snapshotVersions.snapshotVersion | Where-Object { $_.extension -eq "jar" } | Select-Object -First 1).value
$deps = @{
    "paper-api.jar"       = "$apiBase/paper-api-$apiVer.jar"
    "adventure-api.jar"   = "$central/net/kyori/adventure-api/4.17.0/adventure-api-4.17.0.jar"
    "adventure-key.jar"   = "$central/net/kyori/adventure-key/4.17.0/adventure-key-4.17.0.jar"
    "examination-api.jar" = "$central/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar"
    "annotations.jar"     = "$central/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar"
    "bungeecord-chat.jar" = "$papermc/net/md-5/bungeecord-chat/1.20-R0.2-deprecated+build.18/bungeecord-chat-1.20-R0.2-deprecated+build.18.jar"
}
foreach ($k in $deps.Keys) { Get-File $deps[$k] (Join-Path $LibsDir $k) }

# --- 4. Build the plugin jar (javac + jar) ---------------------------------------
$Javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
$JarTool = Join-Path $env:JAVA_HOME "bin\jar.exe"
$Java = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path $Javac)) { Fail "javac not found under JAVA_HOME ($env:JAVA_HOME)" }

$Build = Join-Path $Cache "plugin-build"
$Classes = Join-Path $Build "classes"
$Stage = Join-Path $Build "stage"
foreach ($d in @($Classes, $Stage)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
$srcRoot = Join-Path $RepoRoot "arnis-paper\src\main\java"
$sources = Get-ChildItem $srcRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Info "Compiling plugin ($($sources.Count) sources)..."
# javac writes notes/warnings (e.g. deprecation) to stderr even on success; under
# ErrorActionPreference=Stop that would trip a terminating NativeCommandError, so
# capture output and gate on the exit code instead.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javacOut = & $Javac --release 21 -cp (Join-Path $LibsDir "*") -d $Classes $sources 2>&1
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($javacExit -ne 0) {
    $javacOut | ForEach-Object { Write-Host $_ }
    Fail "plugin compilation failed"
}

# Stage classes + resources (substituting the version placeholder in plugin.yml).
Copy-Item (Join-Path $Classes "com") -Destination $Stage -Recurse -Force
$resDir = Join-Path $RepoRoot "arnis-paper\src\main\resources"
$pluginYml = (Get-Content (Join-Path $resDir "plugin.yml") -Raw).Replace('${version}', '0.1.0')
Set-Content -Path (Join-Path $Stage "plugin.yml") -Value $pluginYml -Encoding UTF8
Copy-Item (Join-Path $resDir "config.yml") -Destination (Join-Path $Stage "config.yml") -Force
$PluginJar = Join-Path $Build "arnis-paper.jar"
& $JarTool --create --file $PluginJar -C $Stage .
if ($LASTEXITCODE -ne 0) { Fail "jar packaging failed" }
Info "plugin jar: $PluginJar"

# --- 5. Fresh server run directory -----------------------------------------------
if (Test-Path $RunDir) { Remove-Item $RunDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $RunDir "plugins\ArnisGen") | Out-Null

Set-Content -Path (Join-Path $RunDir "eula.txt") -Value "eula=true" -Encoding ASCII
@"
online-mode=false
level-type=minecraft:flat
view-distance=3
simulation-distance=3
spawn-protection=0
max-players=2
server-port=25599
generate-structures=false
spawn-npcs=false
spawn-animals=false
spawn-monsters=false
motd=arnis e2e
"@ | Set-Content -Path (Join-Path $RunDir "server.properties") -Encoding ASCII

Copy-Item $PluginJar (Join-Path $RunDir "plugins\arnis-paper.jar") -Force

$lat, $lng = $Origin.Split(",")
$arnisYamlPath = $Arnis.Replace("'", "''")
@"
world: arnis
origin:
  lat: $lat
  lng: $lng
scale: 1.0
bake-margin: 64
ground-level: -62
arnis-binary: '$arnisYamlPath'
bake-spawn-on-enable: true
spawn:
  x: 8
  z: 8
"@ | Set-Content -Path (Join-Path $RunDir "plugins\ArnisGen\config.yml") -Encoding UTF8

# --- 6. Run the server, watch for the bake, then stop ----------------------------
Info "Starting Paper server (timeout ${TimeoutSec}s)..."
$logPath = Join-Path $RunDir "server.out.log"

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $Java
$psi.Arguments = "-Xms1G -Xmx2G -jar `"$PaperJar`" --nogui"
$psi.WorkingDirectory = $RunDir
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.RedirectStandardInput = $true
$proc = [System.Diagnostics.Process]::new()
$proc.StartInfo = $psi

# Read the server's output asynchronously via events into a thread-safe queue.
# (Polling ReadLineAsync directly races the stream; the event model does not.)
$queue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$sink = { $d = $EventArgs.Data; if ($null -ne $d) { $Event.MessageData.Enqueue($d) } }
[void]$proc.Start()
$subOut = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $sink -MessageData $queue
$subErr = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived  -Action $sink -MessageData $queue
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()

$SUCCESS = "Spawn region baked."
$FAILURES = @(
    "Spawn region bake failed",
    "Failed to create/load arnis world",
    "Command 'arnis' is not defined"
)
$log = [System.IO.StreamWriter]::new($logPath, $false)
$outcome = "timeout"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    $line = $null
    if ($queue.TryDequeue([ref]$line)) {
        $log.WriteLine($line); $log.Flush()
        if ($line -match [regex]::Escape($SUCCESS)) { $outcome = "ok"; break }
        $hit = $false
        foreach ($f in $FAILURES) { if ($line -match [regex]::Escape($f)) { $outcome = "plugin-error: $f"; $hit = $true; break } }
        if ($hit) { break }
    } elseif ($proc.HasExited) {
        $outcome = "server-exited"; break
    } else {
        Start-Sleep -Milliseconds 200
    }
}

# After the spawn bake, exercise /arnis goto through the console: the configured
# origin must map to Minecraft (0,0), which cross-checks the plugin's projection
# against the Rust bake's coordinate frame end-to-end.
$gotoOk = $false
$statusSeen = $false
if ($outcome -eq "ok") {
    # First confirm arnis console commands dispatch at all, then check goto.
    # Comma form avoids any space-before-negative parsing of the longitude.
    try { $proc.StandardInput.WriteLine("arnis status"); $proc.StandardInput.Flush() } catch {}
    Start-Sleep -Milliseconds 800
    Info "Checking '/arnis goto $Origin' (origin should map to MC 0,0)..."
    try { $proc.StandardInput.WriteLine("arnis goto $Origin"); $proc.StandardInput.Flush() } catch {}
    $gsw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($gsw.Elapsed.TotalSeconds -lt 20 -and -not $gotoOk) {
        $line = $null
        if ($queue.TryDequeue([ref]$line)) {
            $log.WriteLine($line); $log.Flush()
            if ($line -match "Arnis generator:") { $statusSeen = $true }
            if ($line -match "MC x=0 z=0") { $gotoOk = $true }
        } elseif ($proc.HasExited) {
            break
        } else {
            Start-Sleep -Milliseconds 200
        }
    }
    Info "goto check: $gotoOk (console arnis dispatch: $statusSeen)"
}

Info "Stopping server..."
try { if (-not $proc.HasExited) { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } } catch {}
if (-not $proc.WaitForExit(90000)) { try { $proc.Kill() } catch {} }
Start-Sleep -Milliseconds 500
$line = $null
while ($queue.TryDequeue([ref]$line)) { $log.WriteLine($line) }
$log.Close()
Unregister-Event -SourceIdentifier $subOut.Name -ErrorAction SilentlyContinue
Unregister-Event -SourceIdentifier $subErr.Name -ErrorAction SilentlyContinue

# --- 7. Assertions ---------------------------------------------------------------
$regionFile = Join-Path $RunDir "arnis\region\r.0.0.mca"
$regionExists = Test-Path $regionFile
$regionSize = 0; if ($regionExists) { $regionSize = (Get-Item $regionFile).Length }

Write-Host ""
Info "Result: outcome=$outcome  region-file=$regionExists ($regionSize bytes)  goto=$gotoOk  log=$logPath"

$pass = ($outcome -eq "ok") -and $regionExists -and ($regionSize -gt 0) -and $gotoOk

if (-not $KeepRun -and $pass) { Remove-Item $RunDir -Recurse -Force -ErrorAction SilentlyContinue }

if ($pass) {
    Write-Host "[e2e] PASS: server booted, arnis world created, spawn region baked ($regionSize bytes), /arnis goto mapped origin to MC (0,0)." -ForegroundColor Green
    exit 0
} else {
    Write-Host "[e2e] FAIL: outcome=$outcome (see $logPath; run dir kept)." -ForegroundColor Red
    Write-Host "----- last 40 log lines -----"
    if (Test-Path $logPath) { Get-Content $logPath -Tail 40 }
    exit 1
}
