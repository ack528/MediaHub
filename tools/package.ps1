# 一键打包:服务端 + 桌面管理程序便携版 zip(不再生成 NSIS 安装包)、Android APK。输出到 dist\,并清掉 dist 里的历史版本。
#   powershell -File tools\package.ps1                 # 全部
#   powershell -File tools\package.ps1 -SkipAndroid    # 只打服务端 / 桌面端(便携版)
#   powershell -File tools\package.ps1 -SkipDesktop    # 只打安卓端
# 前提:已运行过 tools\setup.ps1(便携工具链),signing\ 下有 Android 发布密钥(没有则用调试签名)。
param(
  [string]$Version = '1.3.0',          # 手机端(APK)版本号
  [string]$ServerVersion = '',        # 服务端 / 管理程序版本号;留空 = 取 desktop\package.json 里的 version
  [switch]$SkipAndroid,
  [switch]$SkipDesktop,
  [switch]$SkipTests,
  [switch]$Release                    # 发布到 GitHub 用:APK 不内置 Lossless.dll(版权),另出自动更新用的 -update.zip,产物放 dist\release\
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
if (-not $ServerVersion) { $ServerVersion = (Get-Content (Join-Path $Root 'desktop\package.json') -Raw -Encoding UTF8 | ConvertFrom-Json).version }
. (Join-Path $Root 'env.ps1') | Out-Null
$Dist = Join-Path $Root 'dist'
$Stage = Join-Path $Dist 'stage'
New-Item -ItemType Directory -Force $Dist | Out-Null
$RelDir = Join-Path $Dist 'release'   # -Release 的产物(要上传到 GitHub 的文件)
if ($Release) { if (Test-Path $RelDir) { Remove-Item -LiteralPath $RelDir -Recurse -Force }; New-Item -ItemType Directory -Force $RelDir | Out-Null }

# 更新日志:根目录 CHANGELOG.md 是唯一来源,打包时同步一份到手机端 assets 和管理程序 src(程序里「更新说明」显示它)
Copy-Item (Join-Path $Root 'CHANGELOG.md') (Join-Path $Root 'android\app\src\main\assets\changelog.md') -Force
Copy-Item (Join-Path $Root 'CHANGELOG.md') (Join-Path $Root 'desktop\src\changelog.md') -Force

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
@'
MediaHub 便携包里随附的第三方程序:

- ffmpeg / ffprobe:jellyfin-ffmpeg(GPLv3 构建),源码与许可证见 https://github.com/jellyfin/jellyfin-ffmpeg 。本包只是原样附带其可执行文件,未做修改。
- ExifTool:Phil Harvey,Perl Artistic License / GPL,见 https://exiftool.org 。
- 其余部分(MediaHub 服务端 / 管理程序)的源码见 https://github.com/ack528/MediaHub 。
'@ | Set-Content -LiteralPath (Join-Path $Stage 'tools\THIRD_PARTY_NOTICES.txt') -Encoding UTF8
Remove-Item (Join-Path $Stage 'tools\exiftool\.installed') -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 桌面端
if (-not $SkipDesktop) {
  Step '构建桌面管理程序 (Tauri,只编译,不生成安装包)'
  Push-Location (Join-Path $Root 'desktop')
  if (-not (Test-Path 'node_modules')) { & npm install --no-audit --no-fund }
  & npx tauri build --no-bundle
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'tauri build 失败' }
  Pop-Location

  $rel = Join-Path $env:CARGO_TARGET_DIR 'release'

  Step '生成便携版 zip'
  $pt = Join-Path $Dist "MediaHub-$ServerVersion-portable"
  if (Test-Path $pt) { Remove-Item -LiteralPath $pt -Recurse -Force }
  New-Item -ItemType Directory -Force $pt | Out-Null
  Copy-Item (Join-Path $rel 'mediahub-desktop.exe') (Join-Path $pt 'MediaHub.exe')
  Copy-Tree (Join-Path $Stage 'runtime') (Join-Path $pt 'runtime')
  Copy-Tree (Join-Path $Stage 'tools') (Join-Path $pt 'tools')
  $zip = Join-Path $Dist "MediaHub-$ServerVersion-portable.zip"
  if (Test-Path $zip) { Remove-Item $zip -Force }
  Compress-Archive -Path (Join-Path $pt '*') -DestinationPath $zip -CompressionLevel Optimal
  Remove-Item -LiteralPath $pt -Recurse -Force

  if ($Release) {
    Step '生成自动更新包 (MediaHub-<版本>-update.zip,只含程序本体)'
    $up = Join-Path $Dist 'update-tmp'
    if (Test-Path $up) { Remove-Item -LiteralPath $up -Recurse -Force }
    New-Item -ItemType Directory -Force (Join-Path $up 'runtime\bin') | Out-Null
    Copy-Item (Join-Path $rel 'mediahub-desktop.exe') (Join-Path $up 'MediaHub.exe')
    Copy-Item (Join-Path $Stage 'runtime\bin\mediahub.exe') (Join-Path $up 'runtime\bin\mediahub.exe')
    Compress-Archive -Path (Join-Path $up '*') -DestinationPath (Join-Path $RelDir "MediaHub-$ServerVersion-update.zip") -CompressionLevel Optimal
    Remove-Item -LiteralPath $up -Recurse -Force
    Copy-Item $zip (Join-Path $RelDir "MediaHub-$ServerVersion-portable.zip") -Force
  }
}

# ---------------------------------------------------------------- 安卓端
if (-not $SkipAndroid) {
  Step '构建 Android 发布版 APK'
  # 发布到 GitHub(公开仓库)的 APK 不能带 Lossless.dll(版权,只能自己用):构建期间把它临时挪走,构建完(无论成败)放回去
  $dll = Join-Path $Root 'android\app\src\main\assets\lsfg\Lossless.dll'
  $dllHold = Join-Path $Dist 'Lossless.dll.hold'
  $moved = $false
  if ($Release -and (Test-Path -LiteralPath $dll)) { Move-Item -LiteralPath $dll -Destination $dllHold -Force; $moved = $true; Write-Host '  发布版:本次构建不包含 Lossless.dll' }
  try {
    & powershell -NoProfile -File (Join-Path $Root 'tools\build-android.ps1') ':app:assembleRelease'
    if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
  } finally {
    if ($moved) { Move-Item -LiteralPath $dllHold -Destination $dll -Force }
  }
  $apk = Join-Path $Root 'android\app\build\outputs\apk\release\app-release.apk'
  if ($Release) { Copy-Item $apk (Join-Path $RelDir "LocalBrowse-$Version.apk") -Force }
  else { Copy-Item $apk (Join-Path $Dist "LocalBrowse-$Version.apk") -Force }
}

# ---------------------------------------------------------------- 收尾
Step '清理 dist 里的历史版本'
# 每种产物只留这次打出来的:手机端 APK 只留当前版本,服务端便携包只留当前版本;以前的安装包(setup.exe)不再生成,旧的一并删掉
if (-not $SkipAndroid) {
  Get-ChildItem $Dist -Filter 'LocalBrowse-*.apk' -File | Where-Object { $_.Name -ne "LocalBrowse-$Version.apk" } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force; Write-Host "  删除 $($_.Name)" }
}
if (-not $SkipDesktop) {
  Get-ChildItem $Dist -Filter 'MediaHub-*' -File | Where-Object { $_.Name -ne "MediaHub-$ServerVersion-portable.zip" } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force; Write-Host "  删除 $($_.Name)" }
}

if ($Release) {
  $rl = Get-ChildItem $RelDir -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | ForEach-Object { "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name }
  [System.IO.File]::WriteAllLines((Join-Path $RelDir 'SHA256SUMS.txt'), $rl, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "发布文件在 $RelDir :" -ForegroundColor Green
  Get-ChildItem $RelDir -File | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
}

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
