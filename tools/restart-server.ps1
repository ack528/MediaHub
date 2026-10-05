# 重新编译并重启开发用的 MediaHub。  powershell -File tools\restart-server.ps1 [-ClearCache]
# -ClearCache:清掉各盘上的封面缓存与缓存记录(封面会重新生成)。
param([switch]$ClearCache)
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null

Get-Process mediahub -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1

Push-Location (Join-Path $Root 'server')
& go build -o (Join-Path $Root 'runtime\bin\mediahub.exe') .\cmd\mediahub
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "go build 失败" }
Pop-Location

if ($ClearCache) {
  foreach ($d in 'testdata\generated\.mediahub', 'testdata\library\.mediahub') {
    $p = Join-Path $Root $d
    if (Test-Path $p) { Remove-Item -LiteralPath $p -Recurse -Force }
  }
  & (Join-Path $Root 'tools\sqlite\sqlite3.exe') (Join-Path $Root 'runtime\data\hub.sqlite') "DELETE FROM cache_entries; UPDATE media SET thumbhash=NULL;"
}

Start-Process -FilePath (Join-Path $Root 'runtime\bin\mediahub.exe') -ArgumentList 'serve' -WindowStyle Hidden `
  -RedirectStandardError (Join-Path $Root 'runtime\data\serve.err') -RedirectStandardOutput (Join-Path $Root 'runtime\data\serve.out')

# 等服务可用(本机管理端口 = 监听端口 + 1,明文 HTTP;对外的 8480 是 HTTPS)
$ok = $false
for ($i = 0; $i -lt 30 -and -not $ok; $i++) {
  Start-Sleep -Seconds 1
  try { $null = Invoke-RestMethod 'http://127.0.0.1:8481/api/v1/server/info' -TimeoutSec 2; $ok = $true } catch {}
}
if ($ok) { Write-Host "MediaHub 已启动: http://127.0.0.1:8481" } else { Write-Warning "服务未能在 30 秒内启动,查看 runtime\data\serve.err" }
