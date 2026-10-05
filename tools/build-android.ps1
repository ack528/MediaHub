# 构建 Android 应用:  powershell -File tools\build-android.ps1 [任务,默认 :app:assembleDebug]
# 构建用项目内的 JDK / Gradle / Android SDK(tools\android-sdk)。
param([string[]]$Tasks = @(':app:assembleDebug'))
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
$env:ANDROID_HOME = Join-Path $Root 'tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location (Join-Path $Root 'android')
& gradle --console=plain @Tasks 2>&1 | ForEach-Object { "$_" } |
  Where-Object { $_ -match '^e: |^w: .*localtg|error:|FAILED|BUILD|Unresolved|What went wrong|^\s+> |\.apk' }
exit $LASTEXITCODE
