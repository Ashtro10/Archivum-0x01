<#
.SYNOPSIS
  CLU M1 benchmark for the target PC: CPU vs Vulkan llama.cpp, candidate models, memory, threads, and the effect on a
  foreground task. Writes results\results.json and results\results.js; open report.html to read the recommendation.

.DESCRIPTION
  Uses only what Windows ships (PowerShell 5.1+). Downloads the llama.cpp release CLU vendors (b11844) from GitHub and
  checks each file against the SHA-256 GitHub publishes. Models are taken from -ModelDir (your existing GGUF files,
  e.g. Primus's benchmarks\models) and, only with -Download, fetched from Hugging Face and checked against the SHA-256
  Hugging Face publishes. Nothing is installed system-wide; everything stays in this folder.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\Run-CluBenchmark.ps1 -ModelDir "C:\Users\me\Documents\Astra\benchmarks\models"
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\Run-CluBenchmark.ps1 -Download -Coexistence
#>
[CmdletBinding()]
param(
  [string[]] $ModelDir = @(),
  [switch] $Download,
  [switch] $Coexistence,
  [switch] $Quick,
  [string] $OutDir = (Join-Path $PSScriptRoot "results")
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with the progress bar on PowerShell 5.1
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Root = $PSScriptRoot
$Config = Get-Content (Join-Path $Root "bench.config.json") -Raw | ConvertFrom-Json
$Tools = Join-Path $Root "tools"
$Models = Join-Path $Root "models"
New-Item -ItemType Directory -Force -Path $OutDir, $Tools, $Models | Out-Null
$Headers = @{ "User-Agent" = "clu-bench/1" }

function Say([string] $m) { Write-Host ("[clu-bench] " + $m) }

function Get-Sha256([string] $path) { (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant() }

# ------------------------------------------------------------------------------------------------ hardware
function Get-Hardware {
  $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
  $gpus = @(Get-CimInstance Win32_VideoController | ForEach-Object {
    [ordered]@{ name = $_.Name; driverVersion = $_.DriverVersion; driverDate = [string]$_.DriverDate; adapterRamBytes = $_.AdapterRAM } })
  $cs = Get-CimInstance Win32_ComputerSystem
  $os = Get-CimInstance Win32_OperatingSystem
  $battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1
  $onBattery = $false
  if ($battery -and $battery.BatteryStatus -eq 1) { $onBattery = $true }   # 1 = discharging
  $plan = ""
  try { $plan = (powercfg /getactivescheme) -join " " } catch { $plan = "unknown" }
  $drive = Get-PSDrive -Name ($Root.Substring(0, 1))
  return [ordered]@{
    cpu = $cpu.Name; cores = $cpu.NumberOfCores; logicalProcessors = $cpu.NumberOfLogicalProcessors; maxClockMHz = $cpu.MaxClockSpeed
    gpus = $gpus; totalRamBytes = $cs.TotalPhysicalMemory; freeRamBytesAtStart = [int64]$os.FreePhysicalMemory * 1024
    os = $os.Caption; osBuild = $os.BuildNumber; powerPlan = $plan; onBattery = $onBattery; freeDiskBytes = $drive.Free
  }
}

# ------------------------------------------------------------------------------------------------ llama.cpp
function Get-LlamaBuilds {
  $rel = $Config.llama.release
  $api = "https://api.github.com/repos/$($Config.llama.repo)/releases/tags/$rel"
  Say "Looking up llama.cpp release $rel on GitHub"
  $release = Invoke-RestMethod -Uri $api -Headers $Headers -UseBasicParsing
  $out = @()
  foreach ($kind in @("cpu", "vulkan")) {
    $suffix = $Config.llama.assets.$kind
    $asset = $release.assets | Where-Object { $_.name -like "*$suffix" } | Select-Object -First 1
    if (-not $asset) { Say "No $kind asset ending in $suffix in release $rel"; continue }
    $zip = Join-Path $Tools $asset.name
    $dir = Join-Path $Tools ("llama-" + $kind)
    if (-not (Test-Path $zip)) { Say "Downloading $($asset.name)"; Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zip -Headers $Headers -UseBasicParsing }
    $actual = Get-Sha256 $zip
    $expected = ""
    if ($asset.digest -and ([string]$asset.digest).StartsWith("sha256:")) { $expected = ([string]$asset.digest).Substring(7).ToLowerInvariant() }
    $verified = ($expected -ne "" -and $expected -eq $actual)
    if ($expected -ne "" -and -not $verified) { Remove-Item $zip -Force; throw "SHA-256 mismatch for $($asset.name): expected $expected, got $actual (file deleted)" }
    if (-not (Test-Path $dir)) { Expand-Archive -Path $zip -DestinationPath $dir -Force }
    $exe = Get-ChildItem -Path $dir -Recurse -Filter "llama-bench.exe" | Select-Object -First 1
    $out += [ordered]@{ kind = $kind; name = $asset.name; sha256 = $actual; publishedSha256 = $expected; verified = $verified; exe = $(if ($exe) { $exe.FullName } else { "" }) }
  }
  return $out
}

# ------------------------------------------------------------------------------------------------ models
function Find-LocalModel([string] $file) {
  foreach ($d in @($Models) + $ModelDir) {
    if (-not $d -or -not (Test-Path $d)) { continue }
    $hit = Get-ChildItem -Path $d -Recurse -File -Filter $file -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { return $hit.FullName }
  }
  return ""
}

function Get-HfModel($m) {
  $tree = Invoke-RestMethod -Uri "https://huggingface.co/api/models/$($m.hf)/tree/main" -Headers $Headers -UseBasicParsing
  $entry = $tree | Where-Object { $_.path -eq $m.file } | Select-Object -First 1
  if (-not $entry) { throw "$($m.file) is not in $($m.hf)" }
  $expected = ""
  if ($entry.lfs -and $entry.lfs.oid) { $expected = ([string]$entry.lfs.oid).ToLowerInvariant() }
  $free = (Get-PSDrive -Name ($Models.Substring(0, 1))).Free
  if ($entry.size -and ($free - $entry.size) -lt 20GB) { throw "not enough free disk for $($m.file) (keeps 20 GB free)" }
  $dest = Join-Path $Models $m.file
  Say ("Downloading {0} ({1:N1} GB)" -f $m.file, ($entry.size / 1GB))
  Invoke-WebRequest -Uri "https://huggingface.co/$($m.hf)/resolve/main/$($m.file)" -OutFile $dest -Headers $Headers -UseBasicParsing
  $actual = Get-Sha256 $dest
  if ($expected -ne "" -and $expected -ne $actual) { Remove-Item $dest -Force; throw "SHA-256 mismatch for $($m.file) (file deleted)" }
  return [ordered]@{ path = $dest; sha256 = $actual; verified = ($expected -ne "") }
}

# ------------------------------------------------------------------------------------------------ one benchmark run
function Invoke-Bench([string] $exe, [string] $model, [int] $threads, [int] $ngl, [bool] $embedding) {
  $reps = [int]$Config.bench.repetitions; if ($Quick) { $reps = 1 }
  # Not "$args": that name is PowerShell's automatic variable.
  $argList = @("-m", "`"$model`"", "-p", $Config.bench.prompt, "-r", $reps, "-o", "json", "-t", $threads, "-ngl", $ngl)
  if ($embedding) { $argList += @("-n", "0", "-embd", "1") } else { $argList += @("-n", $Config.bench.gen) }
  $stdout = Join-Path $OutDir "last-stdout.txt"; $stderr = Join-Path $OutDir "last-stderr.txt"
  $p = Start-Process -FilePath $exe -ArgumentList $argList -NoNewWindow -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
  $peakPriv = 0; $peakWs = 0; $peakShared = 0; $peakDedicated = 0; $tick = 0
  while (-not $p.HasExited) {
    try { $p.Refresh(); if ($p.PrivateMemorySize64 -gt $peakPriv) { $peakPriv = $p.PrivateMemorySize64 }; if ($p.WorkingSet64 -gt $peakWs) { $peakWs = $p.WorkingSet64 } } catch { }
    if (($tick % 4) -eq 0) {   # GPU memory (UMA: the 860M's allocations come out of system RAM); about once a second
      try {
        $c = Get-Counter -Counter @("\GPU Process Memory(pid_$($p.Id)_*)\Shared Usage", "\GPU Process Memory(pid_$($p.Id)_*)\Dedicated Usage") -ErrorAction Stop
        $shared = ($c.CounterSamples | Where-Object { $_.Path -like "*shared usage" } | Measure-Object -Property CookedValue -Sum).Sum
        $ded = ($c.CounterSamples | Where-Object { $_.Path -like "*dedicated usage" } | Measure-Object -Property CookedValue -Sum).Sum
        if ($shared -gt $peakShared) { $peakShared = [int64]$shared }; if ($ded -gt $peakDedicated) { $peakDedicated = [int64]$ded }
      } catch { }
    }
    Start-Sleep -Milliseconds 250; $tick++
  }
  $p.WaitForExit()
  $bench = @(); $err = ""
  # PowerShell 5.1 returns a top-level JSON array as ONE object; piping it on enumerates the entries.
  try { $parsed = Get-Content $stdout -Raw | ConvertFrom-Json; $bench = @($parsed | ForEach-Object { $_ }) } catch { $err = "could not read llama-bench output" }
  $tail = ""; if (Test-Path $stderr) { $tail = ((Get-Content $stderr -Tail 15) -join "`n") }
  if ($p.ExitCode -ne 0 -and $err -eq "") { $err = "llama-bench exit code $($p.ExitCode)" }
  return [ordered]@{ exitCode = $p.ExitCode; bench = $bench; error = $err; stderrTail = $tail
    peakPrivateBytes = $peakPriv; peakWorkingSetBytes = $peakWs; peakGpuSharedBytes = $peakShared; peakGpuDedicatedBytes = $peakDedicated }
}

# ------------------------------------------------------------------------------------------------ foreground impact
function Measure-Foreground([int] $seconds) {
  $buf = New-Object byte[] (32MB); (New-Object Random 7).NextBytes($buf)
  $sha = [Security.Cryptography.SHA256]::Create(); $done = 0
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $seconds) { [void]$sha.ComputeHash($buf); $done += 32 }
  return [math]::Round($done / $sw.Elapsed.TotalSeconds, 1)   # MB/s of a fixed CPU task
}

# ------------------------------------------------------------------------------------------------ main
$results = [ordered]@{ schema = "clu-bench/1"; startedAt = (Get-Date).ToUniversalTime().ToString("o"); config = $Config }
Say "Reading hardware"
$results.hardware = Get-Hardware
$builds = Get-LlamaBuilds
$results.llama = [ordered]@{ release = $Config.llama.release; assets = $builds }
$vk = $builds | Where-Object { $_.kind -eq "vulkan" -and $_.exe } | Select-Object -First 1
if ($vk) { try { $results.llama.vulkanDevices = ((& $vk.exe --list-devices 2>&1) -join "`n") } catch { $results.llama.vulkanDevices = "error: $($_.Exception.Message)" } }

$runs = @(); $models = @()
foreach ($m in $Config.models) {
  $path = Find-LocalModel $m.file; $src = "local"; $sha = ""; $ver = $false
  if (-not $path -and $Download -and $m.download -and $m.hf) {
    try { $got = Get-HfModel $m; $path = $got.path; $src = "huggingface"; $sha = $got.sha256; $ver = $got.verified } catch { Say "Skipping $($m.id): $($_.Exception.Message)" }
  }
  if (-not $path) { $models += [ordered]@{ id = $m.id; found = $false }; continue }
  if ($sha -eq "") { $sha = Get-Sha256 $path }
  $models += [ordered]@{ id = $m.id; found = $true; path = $path; source = $src; sha256 = $sha; verified = $ver; sizeBytes = (Get-Item $path).Length }
  $isEmb = ($m.role -eq "embedding")
  foreach ($b in $builds) {
    if (-not $b.exe) { continue }
    if ($b.kind -eq "cpu") { $threadList = $Config.bench.cpuThreads; $ngl = 0 } else { $threadList = $Config.bench.vulkanThreads; $ngl = [int]$Config.bench.vulkanGpuLayers }
    if ($Quick) { $threadList = @($threadList[-1]) }
    foreach ($t in $threadList) {
      Say ("{0} | {1} | {2} threads" -f $m.id, $b.kind, $t)
      $r = Invoke-Bench $b.exe $path $t $ngl $isEmb
      $params = $m.params; if ($r.bench.Count -gt 0 -and $r.bench[0].model_n_params) { $params = $r.bench[0].model_n_params }
      $entry = [ordered]@{ model = $m.id; role = $m.role; eligible = $m.eligible; params = $params; backend = $b.kind; threads = $t; gpuLayers = $ngl }
      foreach ($k in $r.Keys) { $entry[$k] = $r[$k] }   # ordered dictionaries can't be joined with "+" on PowerShell 5.1
      $runs += $entry
      $results.runs = $runs; $results.models = $models
      ($results | ConvertTo-Json -Depth 12) | Set-Content -Path (Join-Path $OutDir "results.json") -Encoding UTF8   # saved after every run
    }
  }
}
$results.models = $models; $results.runs = $runs

if ($Coexistence) {
  $main = $runs | Where-Object { $_.role -eq "main" -and $_.exitCode -eq 0 -and $_.backend -eq "cpu" } | Select-Object -First 1
  if ($main) {
    Say "Foreground impact: measuring a fixed task alone, then while a model generates at below-normal priority"
    $alone = Measure-Foreground 20
    $exe = ($builds | Where-Object { $_.kind -eq "cpu" }).exe
    $path = ($models | Where-Object { $_.id -eq $main.model }).path
    $p = Start-Process -FilePath $exe -ArgumentList @("-m", "`"$path`"", "-p", "0", "-n", "512", "-r", "20", "-t", $main.threads) -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $OutDir "coexist-out.txt") -RedirectStandardError (Join-Path $OutDir "coexist-err.txt")
    try { $p.PriorityClass = "BelowNormal" } catch { }
    Start-Sleep -Seconds 5
    $with = Measure-Foreground 20
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    $results.coexistence = [ordered]@{ task = "SHA-256 over a 32 MB buffer (CPU-bound proxy for a foreground app)"; aloneMBps = $alone; withInferenceMBps = $with
      inference = "$($main.model), cpu, $($main.threads) threads, below-normal priority" }
  } else { Say "Foreground impact skipped: no main model ran on the CPU" }
}

$results.finishedAt = (Get-Date).ToUniversalTime().ToString("o")
$json = $results | ConvertTo-Json -Depth 12
$json | Set-Content -Path (Join-Path $OutDir "results.json") -Encoding UTF8
("window.CLU_RESULTS = " + $json + ";") | Set-Content -Path (Join-Path $OutDir "results.js") -Encoding UTF8
Say "Done. Open report.html in this folder. Send results\results.json back (it contains no personal files, only hardware names and timings)."
