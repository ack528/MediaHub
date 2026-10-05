# 一键打包:服务端 + 桌面管理程序安装包(NSIS)、便携版 zip、Android APK。输出到 dist\。
#   powershell -File tools\package.ps1                 # 全部
#   powershell -File tools\package.ps1 -SkipAndroid    # 只打桌面端
#   powershell -File tools\package.ps1 -SkipDesktop    # 只打安卓端
# 前提:已运行过 tools\setup.ps1(便携工具链),signing\ 下有 Android 发布密钥(没有则用调试签名)。
param(
  [string]$Version = '1.2.0',
  [switch]$SkipAndroid,
  [switch]$SkipDesktop,
  [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
. (Join-Path $Root 'env.ps1') | Out-Null
$Dist = Join-Path $Root 'dist'
$Stage = Join-Path $Dist 'stage'
New-Item -ItemType Directory -Force $Dist | Out-Null

function Step($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Copy-Tree($from, $to) {
  New-Item -ItemType Directory -Force $to | Out-Null
  & robocopy $from $to /E /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "robocopy 失败: $from" }
  $global:LASTEXITCODE = 0
}

# ---------------------------------------------------------------- 服务端 + 工具(暂存)
Step '编译服务端 mediahub.exe'
Push-Location (Join-Path $Root 'server')
if (-not $SkipTests) {
  & go test ./... 2>&1 | Select-Object -Last 12
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Go 测试未通过,已中止打包' }
}
if (Test-Path $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
New-Item -ItemType Directory -Force (Join-Path $Stage 'runtime\bin') | Out-Null
& go build -trimpath -ldflags '-s -w' -o (Join-Path $Stage 'runtime\bin\mediahub.exe') .\cmd\mediahub
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'go build 失败' }
Pop-Location

Step '暂存随包工具 (ffmpeg / ffprobe / ExifTool)'
$tf = Join-Path $Stage 'tools\ffmpeg'
New-Item -ItemType Directory -Force $tf | Out-Null
Copy-Item (Join-Path $Root 'tools\ffmpeg\ffmpeg.exe'), (Join-Path $Root 'tools\ffmpeg\ffprobe.exe') $tf
Copy-Tree (Join-Path $Root 'tools\exiftool') (Join-Path $Stage 'tools\exiftool')
Remove-Item (Join-Path $Stage 'tools\exiftool\.installed') -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 桌面端
if (-not $SkipDesktop) {
  Step '构建桌面管理程序安装包 (Tauri + NSIS)'
  Push-Location (Join-Path $Root 'desktop')
  if (-not (Test-Path 'node_modules')) { & npm install --no-audit --no-fund }
  & npx tauri build --config src-tauri/tauri.bundle.conf.json
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'tauri build 失败' }
  Pop-Location

  $rel = Join-Path $env:CARGO_TARGET_DIR 'release'
  $setup = Get-ChildItem (Join-Path $rel 'bundle\nsis') -Filter '*-setup.exe' | Sort-Object LastWriteTime | Select-Object -Last 1
  if (-not $setup) { throw '没有找到 NSIS 安装包' }
  Copy-Item $setup.FullName (Join-Path $Dist "MediaHub-$Version-setup.exe") -Force

  Step '生成便携版 zip'
  $pt = Join-Path $Dist "MediaHub-$Version-portable"
  if (Test-Path $pt) { Remove-Item -LiteralPath $pt -Recurse -Force }
  New-Item -ItemType Directory -Force $pt | Out-Null
  Copy-Item (Join-Path $rel 'mediahub-desktop.exe') (Join-Path $pt 'MediaHub.exe')
  Copy-Tree (Join-Path $Stage 'runtime') (Join-Path $pt 'runtime')
  Copy-Tree (Join-Path $Stage 'tools') (Join-Path $pt 'tools')
  $zip = Join-Path $Dist "MediaHub-$Version-portable.zip"
  if (Test-Path $zip) { Remove-Item $zip -Force }
  Compress-Archive -Path (Join-Path $pt '*') -DestinationPath $zip -CompressionLevel Optimal
  Remove-Item -LiteralPath $pt -Recurse -Force
}

# ---------------------------------------------------------------- 安卓端
if (-not $SkipAndroid) {
  Step '构建 Android 发布版 APK'
  & powershell -NoProfile -File (Join-Path $Root 'tools\build-android.ps1') ':app:assembleRelease'
  if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
  $apk = Join-Path $Root 'android\app\build\outputs\apk\release\app-release.apk'
  Copy-Item $apk (Join-Path $Dist "LocalBrowse-$Version.apk") -Force
}

# ---------------------------------------------------------------- 收尾
Step '校验和与说明'
Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
$doc = Join-Path $Root 'docs\09-安装与打包.md'
if (Test-Path $doc) { Copy-Item $doc (Join-Path $Dist '安装说明.md') -Force }
$lines = Get-ChildItem $Dist -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | ForEach-Object {
  "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name
}
[System.IO.File]::WriteAllLines((Join-Path $Dist 'SHA256SUMS.txt'), $lines, (New-Object System.Text.UTF8Encoding($false)))
Get-ChildItem $Dist -File | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
Write-Host '完成。输出目录: dist\' -ForegroundColor Green
