# 快速检查服务端:go build + go vet(输出写文件,经 build-guard)。  powershell -NoProfile -File tools\gocheck.ps1 [包,默认 ./...]
param([string]$Pkg = './...')
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
$g = Join-Path $PSScriptRoot 'build-guard.ps1'
& $g -Name gobuild -Exe go -WorkDir (Join-Path $Root 'server') -ArgList @('build', '-p', "$env:BUILD_JOBS", $Pkg) -Show '.'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $g -Name govet -Exe go -WorkDir (Join-Path $Root 'server') -ArgList @('vet', $Pkg) -Show '.'
exit $LASTEXITCODE
