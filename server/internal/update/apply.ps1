# MediaHub 自动更新的安装脚本(由服务端写到 <根>\update\apply.ps1 后以独立进程启动)。
# 流程:等服务退出 → 关管理程序 → 备份 → 覆盖文件 → 启动新服务并确认能响应 → 起不来就还原旧文件重启旧服务 → 清理。
param(
  [int]$ServerPid,
  [string]$Root,
  [string]$Stage,
  [string]$Version,
  [string]$Config
)
$ErrorActionPreference = 'Continue'
$upd = Join-Path $Root 'update'
$log = Join-Path $upd 'update.log'
function Log($m) { ("{0} {1}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $m) | Out-File -FilePath $log -Append -Encoding utf8 }

$svcExe = Join-Path $Root 'runtime\bin\mediahub.exe'
$mgrExe = Join-Path $Root 'MediaHub.exe'

function Start-Hub {
  $env:MEDIAHUB_ROOT = $Root
  $a = @('serve')
  if ($Config) { $a += @('-config', ('"' + $Config + '"')) }
  Start-Process -FilePath $svcExe -ArgumentList $a -WorkingDirectory $Root -WindowStyle Hidden | Out-Null
}

# 服务是否在监听(读配置里的 listen 端口,连一下回环地址)
function Test-Hub {
  $port = 8480
  try {
    if ($Config -and (Test-Path -LiteralPath $Config)) {
      $c = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
      if ($c.listen -match ':(\d+)$') { $port = [int]$Matches[1] }
    }
  } catch { }
  try {
    $t = New-Object System.Net.Sockets.TcpClient
    $iar = $t.BeginConnect('127.0.0.1', $port, $null, $null)
    $ok = $iar.AsyncWaitHandle.WaitOne(1500) -and $t.Connected
    $t.Close()
    return $ok
  } catch { return $false }
}

Log "开始安装 $Version(服务 PID $ServerPid)"

# 1. 等服务退出
$p = Get-Process -Id $ServerPid -ErrorAction SilentlyContinue
if ($p) {
  if (-not $p.WaitForExit(60000)) { Log '服务 60 秒内没有退出,强制结束'; Stop-Process -Id $ServerPid -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Milliseconds 800

# 2. 管理程序(正在运行的话要先关,覆盖完再打开)
$mgr = @(Get-Process -Name 'MediaHub' -ErrorAction SilentlyContinue | Where-Object { $_.Path -and ($_.Path -ieq $mgrExe) })
$mgrWas = $mgr.Count -gt 0
if ($mgrWas) { Log '关闭管理程序'; $mgr | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1 }

# 3. 备份旧的程序文件
$bak = Join-Path $upd 'backup'
New-Item -ItemType Directory -Force (Join-Path $bak 'runtime\bin') | Out-Null
Copy-Item -LiteralPath $svcExe -Destination (Join-Path $bak 'runtime\bin\mediahub.exe') -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $mgrExe -Destination (Join-Path $bak 'MediaHub.exe') -Force -ErrorAction SilentlyContinue

# 4. 覆盖(配置和数据都不在这个目录里,不会动;tools 里没有变的文件原样保留)
& robocopy $Stage $Root /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
$rc = $LASTEXITCODE
Log "文件覆盖完成(robocopy 返回 $rc)"
$installed = $rc -lt 8

function Restore-Old {
  Log '还原旧版本'
  Get-Process -Name 'mediahub' -ErrorAction SilentlyContinue | Where-Object { $_.Path -ieq $svcExe } | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 1
  Copy-Item -LiteralPath (Join-Path $bak 'runtime\bin\mediahub.exe') -Destination $svcExe -Force -ErrorAction SilentlyContinue
  Copy-Item -LiteralPath (Join-Path $bak 'MediaHub.exe') -Destination $mgrExe -Force -ErrorAction SilentlyContinue
  Start-Hub
}

# 5. 启动新服务,最多等 45 秒确认它在监听
$ok = $false
if ($installed) {
  Start-Hub
  for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 1
    if (Test-Hub) { $ok = $true; break }
  }
}
if ($ok) {
  Log "新版本 $Version 启动成功"
  'ok ' + $Version | Out-File -FilePath (Join-Path $upd 'result.txt') -Encoding utf8
} else {
  Log "新版本没有在 45 秒内响应(或覆盖文件失败),回退"
  Restore-Old
  'rollback ' + $Version | Out-File -FilePath (Join-Path $upd 'result.txt') -Encoding utf8
}

# 6. 管理程序重新打开
if ($mgrWas) { Log '重新打开管理程序'; Start-Process -FilePath $mgrExe -WorkingDirectory $Root | Out-Null }

# 7. 清理(留 update.log 和 result.txt)
Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath $upd -Filter '*.zip' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
Log '完成'
