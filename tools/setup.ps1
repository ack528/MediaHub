# 便携工具链安装脚本(可重复运行,已存在则跳过)。所有内容装入 tools\ 下,不改系统。
# 用法:  powershell -ExecutionPolicy Bypass -File tools\setup.ps1 [-Only go,jdk,...]
param([string[]]$Only)
$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'
$T  = $PSScriptRoot
$DL = Join-Path $T '_download'
New-Item -ItemType Directory -Force $DL | Out-Null

$items = @(
  @{ name='go';       url='https://go.dev/dl/go1.27.1.windows-amd64.zip';
     sha256='a3911b5e0e1b1053f25ed0675f4c1c6aad1e2bfcf253df2b9be4caabd2edd95d'; dest='go'; strip=1 },
  @{ name='jdk';      url='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip';
     sha256='f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'; dest='jdk'; strip=1 },
  @{ name='android';  url='https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip';
     sha256=''; dest='android-sdk\cmdline-tools\latest'; strip=1 },
  @{ name='ffmpeg';   url='https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v8.1.3-1/jellyfin-ffmpeg_8.1.3-1_portable_win64-clang-gpl.zip';
     sha256=''; dest='ffmpeg'; strip=0 },
  @{ name='jellyfin'; url='https://repo.jellyfin.org/files/server/windows/latest-stable/amd64/jellyfin_12.1-amd64.zip';
     sha256=''; dest='jellyfin'; strip=0 },
  @{ name='exiftool'; url='https://sourceforge.net/projects/exiftool/files/exiftool-13.59_64.zip/download'; file='exiftool-13.59_64.zip';
     sha256='44b512b25af500724ba579d0a53c8fc5851628b692dd5e5d94ae4a15c2cba9ec'; dest='exiftool'; strip=1 },
  @{ name='vips';     url='https://github.com/libvips/build-win64-mxe/releases/download/v8.18.7/vips-dev-x64-all-8.18.7.zip';
     sha256=''; dest='vips'; strip=1 },
  @{ name='gradle';   url='https://services.gradle.org/distributions/gradle-9.8.0-bin.zip';
     sha256='bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c'; dest='gradle'; strip=1 },
  @{ name='sqlite';   url='https://sqlite.org/2026/sqlite-tools-win-x64-3530400.zip';
     sha256=''; dest='sqlite'; strip=0 }
)

$lock = @{}
foreach ($it in $items) {
  if ($Only -and ($Only -notcontains $it.name)) { continue }
  $dest = Join-Path $T $it.dest
  $fn   = if ($it.file) { $it.file } else { [IO.Path]::GetFileName(([uri]$it.url).AbsolutePath) }
  $zip  = Join-Path $DL $fn
  if (Test-Path (Join-Path $dest '.installed')) {
    Write-Host "[skip] $($it.name)"
    if (Test-Path $zip) { $lock[$it.name] = @{ url=$it.url; sha256=(Get-FileHash $zip -Algorithm SHA256).Hash.ToLower(); verified=[bool]$it.sha256 } }
    continue
  }

  if (-not (Test-Path $zip)) {
    Write-Host "[download] $($it.name)  <-  $($it.url)"
    & curl.exe -L --fail --retry 3 -o $zip $it.url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $($it.name)" }
  }
  $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
  if ($it.sha256 -and $hash -ne $it.sha256) { throw "SHA256 mismatch for $($it.name): $hash" }
  $lock[$it.name] = @{ url=$it.url; sha256=$hash; verified=[bool]$it.sha256 }
  Write-Host ("[sha256]   {0} {1} {2}" -f $it.name, $hash, $(if($it.sha256){'(matches publisher)'}else{'(recorded, publisher hash not available)'}))

  Write-Host "[extract]  $($it.name) -> $dest"
  $tmp = Join-Path $DL ("x_" + $it.name)
  if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
  New-Item -ItemType Directory -Force $tmp | Out-Null
  & tar.exe -xf $zip -C $tmp
  if ($LASTEXITCODE -ne 0) { throw "extract failed: $($it.name)" }
  $src = $tmp
  if ($it.strip -eq 1) { $src = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName }
  if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
  New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
  Move-Item $src $dest
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  Set-Content (Join-Path $dest '.installed') (Get-Date -Format o)
}

# 记录版本锁
$LockFile = Join-Path $PSScriptRoot 'versions.json'
$merged = [ordered]@{}
if (Test-Path -LiteralPath $LockFile) {
  (Get-Content -LiteralPath $LockFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $merged[$_.Name] = $_.Value }
}
foreach ($k in $lock.Keys) { $merged[$k] = $lock[$k] }
$merged | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $LockFile -Encoding utf8
Write-Host "done."
