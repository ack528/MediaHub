# 构建 Android 应用:  powershell -File tools\build-android.ps1 [任务,默认 :app:assembleDebug]
# 构建用项目内的 JDK / Gradle / Android SDK(tools\android-sdk)。
param([string[]]$Tasks = @(':app:assembleDebug'))
$Root = Split-Path -Parent $PSScriptRoot
# CMake / Ninja 在 Windows 上处理不了路径里的中文(编译 LSFG 原生库时会乱码):把项目根目录映射成盘符 L: 再构建
$Drive = 'L:'
if (-not (Test-Path "$Drive\")) { cmd /c "subst $Drive `"$Root`"" | Out-Null }
if (Test-Path "$Drive\env.ps1") { $Root = "$Drive\" }
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
$env:ANDROID_HOME = Join-Path $Root 'tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location (Join-Path $Root 'android')
# 通过 build-guard 跑:输出写进日志文件(不走管道),多核并行,整机 CPU 偏低时自动检查构建是否正常
$jobs = [int]$env:BUILD_JOBS; if ($jobs -lt 1) { $jobs = [Environment]::ProcessorCount }
$log = Join-Path $PSScriptRoot '..\dist\logs\android.log'
& (Join-Path $PSScriptRoot 'build-guard.ps1') -Name android -Exe gradle -WorkDir (Get-Location).Path -Log $log `
  -ArgList (@('--console=plain', '--parallel', '--max-workers', "$jobs", '--build-cache') + $Tasks)
exit $LASTEXITCODE
