# 一键打包:服务端 + 桌面管理程序便携版 zip(不再生成 NSIS 安装包)、Android APK。输出到 dist\,并清掉 dist 里的历史版本。
#   powershell -File tools\package.ps1                 # 全部
#   powershell -File tools\package.ps1 -SkipAndroid    # 只打服务端 / 桌面端(便携版)
#   powershell -File tools\package.ps1 -SkipDesktop    # 只打安卓端
#   powershell -File tools\package.ps1 -Both -SkipTests   # 一次出两套:自己用的(带 Lossless.dll,放 dist\)和发布用的(放 dist\release\),桌面端只编译一次
#   powershell -File tools\package.ps1 -Both -Changed -SkipTests   # 只打有改动的那一端:对比最近一个 git 标签,android\ 有改动才打手机端,server\ 或 desktop\ 有改动才打服务端 + 管理程序;没改动的那端沿用上次发布的文件
# 桌面端(Rust)、Android、Go 三条编译互不依赖,会同时跑;Android 的第二套(带 DLL)只重新打包,不重新编译 Kotlin。
# 前提:已运行过 tools\setup.ps1(便携工具链),signing\ 下有 Android 发布密钥(没有则用调试签名)。
param(
  [string]$Version = '4.0.1',          # 手机端(APK)版本号
  [string]$ServerVersion = '',        # 服务端 / 管理程序版本号;留空 = 取 desktop\package.json 里的 version
  [switch]$SkipAndroid,
  [switch]$SkipDesktop,
  [switch]$SkipTests,
  [switch]$Release,                   # 发布到 GitHub 用:APK 不内置 Lossless.dll(版权),另出自动更新用的 -update.zip,产物放 dist\release\
  [switch]$Both,                      # 自用版和发布版一次打完(桌面端只编译一次)
  [switch]$Changed                    # 只打有改动的那一端(见上面的说明)
)
$ErrorActionPreference = 'Stop'
if ($Both) { $Release = $true }
$Root0 = Split-Path -Parent $PSScriptRoot
if ($Changed) {
  # 对比最近一个发布标签:手机端 = android\ 下有改动;服务端 + 管理程序 = server\ 或 desktop\ 下有改动。
  # 打包时自动同步的更新日志副本(android\...\changelog.md、desktop\src\changelog.md)不算改动。
  $lastTag = (& git -C $Root0 describe --tags --abbrev=0 2>$null)
  if (-not $lastTag) { Write-Host '没有找到发布标签,按全部有改动处理' -ForegroundColor Yellow }
  else {
    $files = @(& git -C $Root0 -c core.quotepath=false diff --name-only $lastTag) + @(& git -C $Root0 -c core.quotepath=false ls-files --others --exclude-standard)
    $files = $files | Where-Object { $_ -and $_ -notmatch 'changelog\.md$' }
    $phone = [bool]($files | Where-Object { $_ -like 'android/*' })
    $server = [bool]($files | Where-Object { $_ -like 'server/*' -or $_ -like 'desktop/*' })
    Write-Host ("改动检测(对比 {0}):手机端 {1},服务端 + 管理程序 {2}" -f $lastTag, $(if ($phone) { '有改动 → 打包' } else { '没有改动 → 跳过' }), $(if ($server) { '有改动 → 打包' } else { '没有改动 → 跳过' })) -ForegroundColor Cyan
    if (-not $phone) { $SkipAndroid = $true }
    if (-not $server) { $SkipDesktop = $true }
    if ($SkipAndroid -and $SkipDesktop) { Write-Host '两端都没有改动,没有需要打包的内容。'; exit 0 }
  }
}
$Personal = (-not $Release) -or $Both   # 要不要出自用版(带 Lossless.dll,放 dist\)
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

# ---------------------------------------------------------------- 并行启动桌面端和 Android(和下面的 Go 编译同时跑)
function Show-Job($name, $job) {
  $out = Receive-Job $job -Wait -AutoRemoveJob 2>&1 | ForEach-Object { "$_" }
  $out | Where-Object { $_ -notmatch '^EXIT=' } | Select-Object -Last 40 | ForEach-Object { Write-Host "  [$name] $_" }
  $code = ($out | Where-Object { $_ -match '^EXIT=' } | Select-Object -Last 1)
  if ($code -ne 'EXIT=0') { throw "$name 构建失败(见上面的输出)" }
}
$jobTauri = $null; $procAndroid = $null
if (-not $SkipDesktop) {
  Step '后台启动:构建桌面管理程序 (Tauri,只编译,不生成安装包)'
  $jobTauri = Start-Job -Name tauri -ArgumentList (Join-Path $Root 'desktop') -ScriptBlock {
    param($dir)
    Set-Location $dir
    if (-not (Test-Path 'node_modules')) { & npm install --no-audit --no-fund 2>&1 }
    & npx tauri build --no-bundle 2>&1
    "EXIT=$LASTEXITCODE"
  }
}
if (-not $SkipAndroid) {
  Step '后台启动:构建 Android APK'
  $androidLog = Join-Path $Dist 'logs\android-variants.log'
  New-Item -ItemType Directory -Force (Split-Path -Parent $androidLog) | Out-Null
  $aa = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'android-variants.ps1'), '-Version', $Version)
  if ($Release) { $aa += '-Release' }
  if ($Personal) { $aa += '-Personal' }
  # 用 Start-Process + 文件重定向,不用 Start-Job:Job 的输出是管道,Gradle 守护进程继承了这根管道,守护进程不退出管道就不关,这里会一直等
  $procAndroid = Start-Process -FilePath 'powershell.exe' -ArgumentList $aa -NoNewWindow -PassThru -RedirectStandardOutput $androidLog -RedirectStandardError "$androidLog.err"
  $null = $procAndroid.Handle
}

# ---------------------------------------------------------------- 服务端 + 工具(暂存)
if (-not $SkipDesktop) {
  Step '编译服务端 mediahub.exe'
  # 内置 frpc(内网穿透用):tools\frp\frpc.exe 压成 gzip 放进 embed 目录,编译进 mediahub.exe(frp 是 Apache-2.0,许可证随包附带)
  $frpc = Join-Path $Root 'tools\frp\frpc.exe'
  $frpGz = Join-Path $Root 'server\internal\tunnel\embed\frpc.exe.gz'
  if (Test-Path -LiteralPath $frpc) {
    Step '内置 frpc(压缩后编译进服务端)'
    $in = [IO.File]::OpenRead($frpc); $out = [IO.File]::Create($frpGz)
    $gz = New-Object IO.Compression.GZipStream($out, [IO.Compression.CompressionLevel]::Optimal)
    $in.CopyTo($gz); $gz.Dispose(); $out.Dispose(); $in.Dispose()
    Write-Host ("  frpc.exe {0:N1} MB -> {1:N1} MB" -f ((Get-Item $frpc).Length / 1MB), ((Get-Item $frpGz).Length / 1MB))
  } else {
    Remove-Item -LiteralPath $frpGz -Force -ErrorAction SilentlyContinue
    Write-Host '  没有 tools\frp\frpc.exe:本次服务端不内置 frpc(先运行 tools\setup.ps1 -Only frp)' -ForegroundColor Yellow
  }
  Push-Location (Join-Path $Root 'server')
  if (-not $SkipTests) {
    & go test -p $env:BUILD_JOBS ./... 2>&1 | Select-Object -Last 12
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Go 测试未通过,已中止打包' }
  }
  if (Test-Path $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
  New-Item -ItemType Directory -Force (Join-Path $Stage 'runtime\bin') | Out-Null
  & go build -p $env:BUILD_JOBS -trimpath -ldflags '-s -w' -o (Join-Path $Stage 'runtime\bin\mediahub.exe') .\cmd\mediahub
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
- frpc:fatedier/frp(Apache-2.0),内置在 mediahub.exe 里,内网穿透功能第一次启动时释放到数据目录下的 tunnel\bin\。许可证见同目录 LICENSE-frp.txt,源码见 https://github.com/fatedier/frp 。
- 其余部分(MediaHub 服务端 / 管理程序)的源码见 https://github.com/ack528/MediaHub 。
'@ | Set-Content -LiteralPath (Join-Path $Stage 'tools\THIRD_PARTY_NOTICES.txt') -Encoding UTF8
  Remove-Item (Join-Path $Stage 'tools\exiftool\.installed') -ErrorAction SilentlyContinue
  if (Test-Path (Join-Path $Root 'tools\frp\LICENSE')) { Copy-Item (Join-Path $Root 'tools\frp\LICENSE') (Join-Path $Stage 'tools\LICENSE-frp.txt') }
}

# ---------------------------------------------------------------- 桌面端
if (-not $SkipDesktop) {
  Step '等待桌面管理程序编译完成'
  Show-Job 'tauri' $jobTauri

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
  Step '等待 Android 构建完成'
  while (-not $procAndroid.HasExited) { Start-Sleep -Seconds 1 }
  Get-Content $androidLog -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  [android] $_" }
  if ($procAndroid.ExitCode -ne 0) { throw "Android 构建失败(见 $androidLog)" }
}

# ---------------------------------------------------------------- 收尾
# 发布时某一端没有打包:沿用上次发布的文件(dist\last-release\ 由 publish-release.py 在发布成功后保存)。
# 只带小文件(APK、-update.zip):手机和服务端的自动更新只看「最新发布」,里面缺一端的文件就收不到那一端的更新。
if ($Release -and ($SkipAndroid -or $SkipDesktop)) {
  $last = Join-Path $Dist 'last-release'
  if (Test-Path $last) {
    if ($SkipAndroid) { Get-ChildItem $last -Filter 'LocalBrowse-*.apk' -File | ForEach-Object { Copy-Item $_.FullName $RelDir -Force; Write-Host "  手机端没有改动:沿用上次发布的 $($_.Name)" } }
    if ($SkipDesktop) { Get-ChildItem $last -Filter 'MediaHub-*-update.zip' -File | ForEach-Object { Copy-Item $_.FullName $RelDir -Force; Write-Host "  服务端没有改动:沿用上次发布的 $($_.Name)" } }
  } else { Write-Host '  (没有 dist\last-release,这次发布里不含没打包的那一端)' -ForegroundColor Yellow }
}

# SHA-256(十六进制小写)。不用 Get-FileHash:从 PowerShell 7 里启动这个脚本(Windows PowerShell 5.1)时,
# 继承来的 PSModulePath 让 Microsoft.PowerShell.Utility 加载失败,Get-FileHash 报"找不到命令",SHA256SUMS.txt 就没生成
# (2026-10-18 v3.2.7 因此发布时缺了校验文件,手机和服务端都检查不了更新)。
function Get-Sha256([string]$Path) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $fs = [System.IO.File]::OpenRead($Path)
  try { return (($sha.ComputeHash($fs) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $fs.Dispose(); $sha.Dispose() }
}

Step '清理 dist 里的历史版本'
# 每种产物只留这次打出来的:手机端 APK 只留当前版本,服务端便携包只留当前版本;以前的安装包(setup.exe)不再生成,旧的一并删掉
if (-not $SkipAndroid) {
  Get-ChildItem $Dist -Filter 'LocalBrowse-*.apk' -File | Where-Object { $_.Name -ne "LocalBrowse-$Version.apk" } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force; Write-Host "  删除 $($_.Name)" }
}
if (-not $SkipDesktop) {
  Get-ChildItem $Dist -Filter 'MediaHub-*' -File | Where-Object { $_.Name -ne "MediaHub-$ServerVersion-portable.zip" } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force; Write-Host "  删除 $($_.Name)" }
}

if ($Release) {
  $rl = Get-ChildItem $RelDir -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | ForEach-Object { "{0}  {1}" -f (Get-Sha256 $_.FullName), $_.Name }
  [System.IO.File]::WriteAllLines((Join-Path $RelDir 'SHA256SUMS.txt'), $rl, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "发布文件在 $RelDir :" -ForegroundColor Green
  Get-ChildItem $RelDir -File | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
}

Step '校验和与说明'
Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
$doc = Join-Path $Root 'docs\09-安装与打包.md'
if (Test-Path $doc) { Copy-Item $doc (Join-Path $Dist '安装说明.md') -Force }
$lines = Get-ChildItem $Dist -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | ForEach-Object {
  "{0}  {1}" -f (Get-Sha256 $_.FullName), $_.Name
}
[System.IO.File]::WriteAllLines((Join-Path $Dist 'SHA256SUMS.txt'), $lines, (New-Object System.Text.UTF8Encoding($false)))
Get-ChildItem $Dist -File | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
Write-Host '完成。输出目录: dist\' -ForegroundColor Green
