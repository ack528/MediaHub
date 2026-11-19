# 构建 Android 应用:  powershell -File tools\build-android.ps1 [任务,默认 :app:assembleDebug]
# JDK、Gradle 和 Android SDK 通过 env.ps1 配置。
param([string[]]$Tasks = @(':app:assembleDebug'))
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
Set-Location (Join-Path $Root 'android')
# 通过 build-guard 跑:输出写进日志文件(不走管道),多核并行,整机 CPU 偏低时自动检查构建是否正常
$jobs = [int]$env:BUILD_JOBS; if ($jobs -lt 1) { $jobs = [Environment]::ProcessorCount }
$log = Join-Path $PSScriptRoot '..\dist\logs\android.log'
& (Join-Path $PSScriptRoot 'build-guard.ps1') -Name android -Exe gradle -WorkDir (Get-Location).Path -Log $log `
  -ArgList (@('--console=plain', '--parallel', '--max-workers', "$jobs", '--build-cache') + $Tasks)
exit $LASTEXITCODE
