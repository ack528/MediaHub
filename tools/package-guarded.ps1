# 带 CPU 监测地打包:  powershell -NoProfile -File tools\package-guarded.ps1 [package.ps1 的参数...]
# 例:  tools\package-guarded.ps1 -Version 3.1.0 -SkipTests           (自己用的版本,带 Lossless.dll)
#      tools\package-guarded.ps1 -Version 3.1.0 -SkipTests -Release  (发布到 GitHub 的版本)
$Root = Split-Path -Parent $PSScriptRoot
$a = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'package.ps1')) + $args
& (Join-Path $PSScriptRoot 'build-guard.ps1') -Name package -Exe powershell -ArgList $a -WorkDir $Root -TimeoutSec 2400 -StuckSec 420 -Retry 0 `
  -Show '^===|^  |FAILED|失败|未通过|error|发布文件|\.apk|\.zip'
exit $LASTEXITCODE
