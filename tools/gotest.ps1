# 跑指定的 Go 测试(输出写日志文件,经 build-guard)。  powershell -NoProfile -File tools\gotest.ps1 'TestA|TestB' [包,默认 ./internal/api]
param([string]$Run = '.', [string]$Pkg = './internal/api')
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
& (Join-Path $PSScriptRoot 'build-guard.ps1') -Name gotest-one -Exe go -WorkDir (Join-Path $Root 'server') -TimeoutSec 900 `
  -ArgList @('test', '-count=1', '-run', $Run, $Pkg) -Show '^(---|===|ok|FAIL|panic|\s+\S+_test\.go|.*\.go:\d+)'
exit $LASTEXITCODE
