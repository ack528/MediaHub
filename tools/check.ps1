# 检查 / 构建的快捷入口(都经过 build-guard:多核并行 + CPU 监测 + 输出写日志文件)。
#   powershell -NoProfile -File tools\check.ps1 server    # go vet + go test(包之间并行)
#   powershell -NoProfile -File tools\check.ps1 desktop   # tsc 类型检查 + vite 构建前端
# 两个互不依赖,可以同时开两个终端 / 后台任务一起跑。
param([Parameter(Mandatory)][ValidateSet('server', 'desktop')][string]$What)
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
$jobs = [int]$env:BUILD_JOBS
$guard = Join-Path $PSScriptRoot 'build-guard.ps1'
$code = 0
if ($What -eq 'server') {
  & $guard -Name go-vet -Exe go -WorkDir (Join-Path $Root 'server') -ArgList @('vet', '-p', "$jobs", './...')
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & $guard -Name go-test -Exe go -WorkDir (Join-Path $Root 'server') -TimeoutSec 1500 -ArgList @('test', '-p', "$jobs", './...')
  exit $LASTEXITCODE
} else {
  $d = Join-Path $Root 'desktop'
  & $guard -Name tsc -Exe npx.cmd -WorkDir $d -ArgList @('tsc', '--noEmit')
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & $guard -Name vite -Exe npx.cmd -WorkDir $d -ArgList @('vite', 'build')
  exit $LASTEXITCODE
}
