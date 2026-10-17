# 带 CPU 监测的编译包装:所有编译 / 打包命令都应该通过它跑(全局约定,见 ~/.claude/CLAUDE.md)。
#   powershell -NoProfile -File tools\build-guard.ps1 -Name android -Exe gradle -ArgList '--console=plain','--parallel',':app:assembleRelease' -Log dist\logs\android.log
# 做的事:
#   1. 输出一律写进日志文件(不走管道,Gradle 守护进程不会把管道占住);结束后按 -Show 正则打印关键行
#   2. 每 2 秒采样整机 CPU 和编译进程(子进程树 + Gradle/Kotlin 守护进程 + cmake/ninja/clang/rustc/go 等)的 CPU 时间
#   3. 整机 CPU 低于 -LowPct(默认 20%)连续 -LowSec(默认 45)秒:检查编译是否正常
#        正常 = 日志还在增长,或编译进程树的 CPU 时间还在增加(单线程阶段 / 等磁盘 / 下载依赖都算正常,只提示)
#        卡死 = 日志不动且编译进程 CPU 不动超过 -StuckSec(默认 240)秒 → 打印诊断(最后日志、进程状态),结束进程树,
#               停掉 Gradle 守护进程,最多重试 -Retry(默认 1)次
#   4. 总超时 -TimeoutSec(默认 3000)
# 退出码 = 编译命令的退出码(卡死被杀掉为 124)。
param(
  [Parameter(Mandatory)][string]$Name,
  [Parameter(Mandatory)][string]$Exe,
  [string[]]$ArgList = @(),
  [string]$Log,
  [string]$WorkDir = (Get-Location).Path,
  [string]$Show = '^e: |error:|FAILED|BUILD (SUCCESSFUL|FAILED)|Unresolved|What went wrong|\.apk|^\s*--- FAIL|^FAIL|^ok |panic|error\[|warning: unused|Finished|built in',
  [int]$LowPct = 20, [int]$LowSec = 45, [int]$StuckSec = 240, [int]$Retry = 1, [int]$TimeoutSec = 3000
)
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
if (-not $Log) { $Log = Join-Path $Root "dist\logs\$Name.log" }
New-Item -ItemType Directory -Force (Split-Path -Parent $Log) | Out-Null
$ErrLog = "$Log.err"
$Cores = [Environment]::ProcessorCount
$Watch = 'java|kotlin|cmake|ninja|clang|clang\+\+|ld|lld|go|compile|cc1|cargo|rustc|link|cl|node|esbuild|ffmpeg|aapt2|d8|r8'

function Get-TotalCpu {
  try { return [int](Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'").PercentProcessorTime } catch { return -1 }
}
# 子进程树 + 名字匹配的编译相关进程(Gradle 守护进程不是子进程,得按名字找)
function Get-BuildProcs([int]$RootPid) {
  $all = Get-CimInstance Win32_Process
  $ids = [System.Collections.Generic.HashSet[int]]::new()
  [void]$ids.Add($RootPid)
  $grew = $true
  while ($grew) {
    $grew = $false
    foreach ($p in $all) { if ($ids.Contains([int]$p.ParentProcessId) -and $ids.Add([int]$p.ProcessId)) { $grew = $true } }
  }
  foreach ($p in $all) {
    $n = [IO.Path]::GetFileNameWithoutExtension($p.Name).ToLower()
    if ($n -match "^($Watch)$" -and ($n -ne 'java' -or $p.CommandLine -match 'Gradle|kotlin|jetbrains')) { [void]$ids.Add([int]$p.ProcessId) }
  }
  $ids
}
function Get-CpuSeconds($ids) {
  $sum = 0.0
  foreach ($i in $ids) { try { $sum += (Get-Process -Id $i -ErrorAction Stop).TotalProcessorTime.TotalSeconds } catch {} }
  $sum
}
function Stop-Tree([int]$RootPid) {
  & taskkill /PID $RootPid /T /F 2>&1 | Out-Null
}
function Tail($path, $n = 15) { if (Test-Path $path) { Get-Content $path -Tail $n -ErrorAction SilentlyContinue } }

$resolved = (Get-Command $Exe -ErrorAction Stop).Source
$attempt = 0
$exit = 1
while ($true) {
  $attempt++
  "[guard] $Name 第 $attempt 次:$resolved $($ArgList -join ' ')  (核心数 $Cores)" | Tee-Object -FilePath "$Log.guard" | Write-Host
  if (Test-Path $Log) { Remove-Item $Log -Force }
  if (Test-Path $ErrLog) { Remove-Item $ErrLog -Force }
  $sp = @{ FilePath = $resolved; WorkingDirectory = $WorkDir; PassThru = $true; NoNewWindow = $true; RedirectStandardOutput = $Log; RedirectStandardError = $ErrLog }
  if ($ArgList.Count -gt 0) { $sp.ArgumentList = $ArgList }
  $proc = Start-Process @sp
  $null = $proc.Handle   # 不先取句柄的话,进程结束后 ExitCode 可能是空
  $t0 = Get-Date
  $lowFor = 0; $lastLen = 0; $lastCpu = 0.0; $stillFor = 0; $killed = $false; $peak = 0; $sumCpu = 0; $samples = 0
  while (-not $proc.HasExited) {
    Start-Sleep -Seconds 2
    $cpu = Get-TotalCpu
    if ($cpu -ge 0) { $samples++; $sumCpu += $cpu; if ($cpu -gt $peak) { $peak = $cpu } }
    $len = 0; foreach ($f in @($Log, $ErrLog)) { if (Test-Path $f) { $len += (Get-Item $f).Length } }
    $ids = Get-BuildProcs $proc.Id
    $cs = Get-CpuSeconds $ids
    $progress = ($len -gt $lastLen) -or (($cs - $lastCpu) -gt 0.2)
    if ($progress) { $stillFor = 0 } else { $stillFor += 2 }
    $lastLen = $len; $lastCpu = $cs
    if ($cpu -ge 0 -and $cpu -lt $LowPct) { $lowFor += 2 } else { $lowFor = 0 }
    if ($lowFor -ge $LowSec -and ($lowFor % $LowSec) -lt 2) {
      $state = if ($progress) { '正常(仍在推进,多半是单线程阶段 / 等磁盘 / 下载依赖)' } else { "无进展 $stillFor 秒" }
      Write-Host ("[guard] 整机 CPU {0}% 已低于 {1}% 持续 {2} 秒;编译进程 {3} 个,CPU 时间 {4:N0} 秒;{5}" -f $cpu, $LowPct, $lowFor, $ids.Count, $cs, $state)
    }
    if ($stillFor -ge $StuckSec) {
      Write-Host "[guard] 判定卡死:日志和编译进程 $stillFor 秒都没有任何进展" -ForegroundColor Red
      Write-Host '[guard] 日志最后几行:'; Tail $Log | ForEach-Object { "    $_" }; Tail $ErrLog 5 | ForEach-Object { "    ! $_" }
      Write-Host '[guard] 编译相关进程:'
      foreach ($i in $ids) { try { $p = Get-Process -Id $i -ErrorAction Stop; "    {0,6} {1,-14} CPU {2,8:N1}s 句柄 {3}" -f $p.Id, $p.ProcessName, $p.TotalProcessorTime.TotalSeconds, $p.HandleCount } catch {} }
      Stop-Tree $proc.Id; $killed = $true; break
    }
    if (((Get-Date) - $t0).TotalSeconds -gt $TimeoutSec) {
      Write-Host "[guard] 超过总超时 $TimeoutSec 秒,结束" -ForegroundColor Red
      Stop-Tree $proc.Id; $killed = $true; break
    }
  }
  if ($killed) { $exit = 124 } else { $proc.WaitForExit(); $exit = $proc.ExitCode }
  $secs = [int]((Get-Date) - $t0).TotalSeconds
  $avg = if ($samples) { [int]($sumCpu / $samples) } else { 0 }
  Write-Host ("[guard] {0} 结束:退出码 {1},用时 {2} 秒,整机 CPU 平均 {3}% / 峰值 {4}%" -f $Name, $exit, $secs, $avg, $peak)
  if ($exit -eq 124 -and $attempt -le $Retry) {
    Write-Host '[guard] 清理后重试' -ForegroundColor Yellow
    if ($Exe -match 'gradle') { & $resolved --stop 2>&1 | Out-Null }
    Start-Sleep -Seconds 3
    continue
  }
  break
}
$lines = @(); foreach ($f in @($Log, $ErrLog)) { if (Test-Path $f) { $lines += Get-Content $f -ErrorAction SilentlyContinue } }
$lines | Where-Object { $_ -cmatch $Show } | Select-Object -First 60 | ForEach-Object { "$_" }
if ($exit -ne 0) { Write-Host "[guard] 完整日志:$Log" }
exit $exit
