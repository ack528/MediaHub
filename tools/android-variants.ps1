# 构建 Android 发布版 APK 的各个变体(由 package.ps1 在后台启动,输出重定向到文件,不走管道)。
#   -Release  :出发布版(不含 Lossless.dll),放到 dist\release\
#   -Personal :出自用版(带 Lossless.dll),放到 dist\
# 为什么单独一个脚本、而不是 Start-Job:Job 的输出是管道,Gradle 守护进程会继承这根管道,守护进程不退出管道就不关,
# 主脚本 Receive-Job -Wait 就一直等(曾经卡了 10 多分钟,而构建 2 分钟就结束了)。这里由主脚本用 Start-Process + 文件重定向启动,守护进程想留多久都不影响。
param([string]$Version, [switch]$Release, [switch]$Personal)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Dist = Join-Path $Root 'dist'
$RelDir = Join-Path $Dist 'release'
$dll = Join-Path $Root 'android\app\src\main\assets\lsfg\Lossless.dll'
$dllHold = Join-Path $Dist 'Lossless.dll.hold'
$apk = Join-Path $Root 'android\app\build\outputs\apk\release\app-release.apk'
function Build {
  & powershell -NoProfile -File (Join-Path $PSScriptRoot 'build-android.ps1') ':app:assembleRelease'
  if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
}
try {
  if ($Release) {
    # 发布到 GitHub(公开仓库)的 APK 不能带 Lossless.dll(版权,只能自己用):构建期间把它临时挪走,构建完(无论成败)放回去
    $moved = $false
    if (Test-Path -LiteralPath $dll) { Move-Item -LiteralPath $dll -Destination $dllHold -Force; $moved = $true; '发布版:本次构建不包含 Lossless.dll' }
    try { Build; Copy-Item $apk (Join-Path $RelDir "LocalBrowse-$Version.apk") -Force }
    finally { if ($moved) { Move-Item -LiteralPath $dllHold -Destination $dll -Force } }
  }
  if ($Personal) { Build; Copy-Item $apk (Join-Path $Dist "LocalBrowse-$Version.apk") -Force }
  'EXIT=0'
  exit 0
} catch { "失败:$_"; 'EXIT=1'; exit 1 }
