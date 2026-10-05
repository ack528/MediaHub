# 在 testdata\library 下搭建一个贴近真实的测试媒体库(盘符/文件夹/媒体 的缩影)。可重复运行。
# 依赖:先运行 gen-samples.ps1 与 fetch-samples.ps1,并 `. .\env.ps1`。
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Gen  = Join-Path $Root 'testdata\generated'
$Real = Join-Path $Root 'testdata\real'
$Lib  = Join-Path $Root 'testdata\library'

function Copy-Into($src, $dstDir, $newName) {
  New-Item -ItemType Directory -Force $dstDir | Out-Null
  $dst = Join-Path $dstDir $(if ($newName) { $newName } else { Split-Path $src -Leaf })
  if (-not (Test-Path $dst)) { Copy-Item -LiteralPath $src -Destination $dst }
}

# 1) 真实照片 / 手机 HEIC
$kyoto = Join-Path $Lib '旅行\2023-京都'
Copy-Into "$Real\image\iphone_13_pro_max.HEIC" $kyoto 'IMG_0001.HEIC'
Copy-Into "$Real\image\nokia_8.3_5G_hdr.jpg"   $kyoto 'IMG_0002.jpg'
Copy-Into "$Real\image\nokia_8.3_5G.heif"      $kyoto 'IMG_0010.heif'
Copy-Into "$Real\image\large_unicode_exif.jpg" $kyoto 'IMG_0003.jpg'
Copy-Into "$Real\image\libheif_example.heic"   (Join-Path $Lib '旅行\2022-上海') 'example.heic'

# 2) 相机 RAW
Get-ChildItem "$Real\raw" -File | ForEach-Object { Copy-Into $_.FullName (Join-Path $Lib '相机RAW') $null }

# 3) 真实视频
Get-ChildItem "$Real\video" -File | ForEach-Object { Copy-Into $_.FullName (Join-Path $Lib '手机视频') $null }

# 4) 容器 × 编码测试:把真实 1080p H.264 的前 10 秒封装 / 转成各种容器(加一路正弦音频,贴近真实)
$cont = Join-Path $Lib '容器测试'
New-Item -ItemType Directory -Force $cont | Out-Null
$src = "$Real\video\jf_1080p_avc_3M.mp4"
$aud = @('-f','lavfi','-i','sine=frequency=440:sample_rate=48000')
function Remux($name, [string[]]$codec) {
  $out = Join-Path $cont $name
  if (Test-Path $out) { return }
  & ffmpeg -hide_banner -v error -y -t 10 -i $src @aud -map 0:v:0 -map 1:a:0 -shortest @codec $out
  "{0,-22} {1}" -f $name, $(if ($LASTEXITCODE -eq 0) { 'ok' } else { 'FAIL' })
}
Remux 'h264_aac.mkv'  @('-c:v','copy','-c:a','aac')
Remux 'h264_aac.mov'  @('-c:v','copy','-c:a','aac')
Remux 'h264_aac.avi'  @('-c:v','copy','-c:a','libmp3lame')
Remux 'h264_aac.flv'  @('-c:v','copy','-c:a','aac')
Remux 'h264_aac.ts'   @('-c:v','copy','-c:a','aac')
Remux 'h264_ac3.mkv'  @('-c:v','copy','-c:a','ac3')
Remux 'h264_dts.mkv'  @('-c:v','copy','-c:a','dca','-strict','-2')
Remux 'h264_eac3.mp4' @('-c:v','copy','-c:a','eac3')
Remux 'vp9_opus.webm' @('-c:v','libvpx-vp9','-b:v','2M','-deadline','realtime','-cpu-used','8','-c:a','libopus')
Remux 'mpeg4_mp3.avi' @('-c:v','mpeg4','-q:v','5','-c:a','libmp3lame')
Remux 'wmv.wmv'       @('-c:v','wmv2','-b:v','3M','-c:a','wmav2')
Remux 'mpeg2.mpg'     @('-c:v','mpeg2video','-b:v','6M','-c:a','mp2')
Remux 'h264_aac.3gp'  @('-c:v','copy','-c:a','aac')
Remux 'h264_aac_cn字幕.mkv' @('-c:v','copy','-c:a','aac')

# 5) 各种图片格式(vips 生成的样本)
Get-ChildItem "$Gen\image" -File | ForEach-Object { Copy-Into $_.FullName (Join-Path $Lib '图片格式') $null }

# 6) 大相册:400 张约 2-4MB 的"原图",拍摄日期分散在 2021-2024,用来测滚动与分页
$big = Join-Path $Lib '大相册'
New-Item -ItemType Directory -Force $big | Out-Null
$have = @(Get-ChildItem $big -Filter *.jpg -ErrorAction SilentlyContinue).Count
if ($have -lt 400) {
  $rnd = New-Object System.Random 42
  $srcs = @("$Real\image\large_unicode_exif.jpg", "$Real\image\nokia_8.3_5G_hdr.jpg")
  for ($i = $have + 1; $i -le 400; $i++) {
    $k = $i % 2
    $s = $srcs[$k]
    $sw = @(4896, 4608)[$k]; $sh = @(3264, 3456)[$k]      # 源图像素尺寸
    $w = 3000 + $rnd.Next(0, 1000); $h = 2000 + $rnd.Next(0, 700)
    $x = $rnd.Next(0, $sw - $w);    $y = $rnd.Next(0, $sh - $h)
    $out = Join-Path $big ('IMG_{0:D4}.jpg' -f $i)
    & vips extract_area $s "$out[Q=93]" $x $y $w $h 2>$null
    if ($i % 100 -eq 0) { Write-Host "  big album: $i/400" }
  }
}
# 写入 EXIF 拍摄时间(CSV 批量导入)。ExifTool 对含中文的绝对路径参数不友好,所以进入目录、用 ASCII 相对路径。
if (-not (Test-Path (Join-Path $big '.dates-set'))) {
  Push-Location $big
  try {
    $base = Get-Date '2021-03-01'
    $lines = @('SourceFile,DateTimeOriginal')
    for ($i = 1; $i -le 400; $i++) {
      $d = $base.AddHours($i * 2.7 + ($i % 7) * 5)
      $lines += ('IMG_{0:D4}.jpg,"{1}"' -f $i, $d.ToString('yyyy:MM:dd HH:mm:ss'))
    }
    [IO.File]::WriteAllLines((Join-Path $big 'dates.csv'), $lines, (New-Object Text.UTF8Encoding($false)))
    & exiftool -overwrite_original -q '-csv=dates.csv' . | Out-Null
    Remove-Item 'dates.csv'
    Set-Content '.dates-set' (Get-Date -Format o)
  } finally { Pop-Location }
}
Write-Host "library ready: $Lib"
Get-ChildItem $Lib -Directory -Recurse | ForEach-Object { $n = @(Get-ChildItem $_.FullName -File).Count; if ($n) { "{0,-26} {1,4} files" -f $_.FullName.Substring($Lib.Length + 1), $n } }
