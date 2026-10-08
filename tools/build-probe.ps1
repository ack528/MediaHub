# 构建天玑 GPU 检测程序:  powershell -File tools\build-probe.ps1 [任务,默认 :app:assembleRelease]
# 原生部分复用 android\ 里 tools\setup-lsfg.ps1 复制好的 LSFG 源码。输出 probe\app\build\outputs\apk\release\app-release.apk
param([string[]]$Tasks = @(':app:assembleRelease'))
$Root = Split-Path -Parent $PSScriptRoot
$Drive = 'L:'
if (-not (Test-Path "$Drive\")) { cmd /c "subst $Drive `"$Root`"" | Out-Null }
if (Test-Path "$Drive\env.ps1") { $Root = "$Drive\" }
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
$env:ANDROID_HOME = Join-Path $Root 'tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location (Join-Path $Root 'probe')
& gradle --console=plain @Tasks 2>&1 | ForEach-Object { "$_" } |
  Where-Object { $_ -match '^e: |^w: |error:|FAILED|BUILD|Unresolved|What went wrong|^\s+> |\.apk|warning: unused' }
exit $LASTEXITCODE
