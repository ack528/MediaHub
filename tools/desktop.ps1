# 桌面管理程序(Tauri)的常用操作。
#   powershell -File tools\desktop.ps1 dev      # 开发模式(热更新,首次会编译 Rust)
#   powershell -File tools\desktop.ps1 build    # 构建独立 exe(tools\cargo-target\debug\mediahub-desktop.exe)
#   powershell -File tools\desktop.ps1 run      # 运行已构建的 exe
#   powershell -File tools\desktop.ps1 web      # 只在浏览器里调界面(mock 后端) http://127.0.0.1:1420
param([ValidateSet('dev', 'build', 'run', 'web')][string]$Action = 'run')
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
Set-Location (Join-Path $Root 'desktop')
if (-not (Test-Path 'node_modules')) { & npm install --no-audit --no-fund }
switch ($Action) {
  'dev'   { & npx tauri dev }
  'build' { & npx tauri build --debug --no-bundle }
  'run'   { Start-Process -FilePath (Join-Path $env:CARGO_TARGET_DIR 'debug\mediahub-desktop.exe') -WorkingDirectory $Root }
  'web'   { & npx vite }
}
