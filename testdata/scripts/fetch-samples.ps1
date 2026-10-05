# 下载公开测试素材到 testdata\real\(视频 / 图片 / RAW),并写入来源与许可说明。可重复运行,已存在则跳过。
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Real = Join-Path $Root 'testdata\real'

$jf  = 'https://repo.jellyfin.org/test-videos/'
$exif = 'https://raw.githubusercontent.com/ianare/exif-samples/master/'
$pix = 'https://raw.pixls.us/data/'

$items = @(
  # ---- 视频:Jellyfin 官方测试片(CC BY-SA 4.0)----
  @{ dir='video'; name='jf_1080p_avc_3M.mp4';        url=$jf + 'SDR/AVC/Test%20Jellyfin%201080p%20AVC%203M.mp4' },
  @{ dir='video'; name='jf_1080p_hevc8_5M.mp4';      url=$jf + 'SDR/HEVC%208bit/Test%20Jellyfin%201080p%20HEVC%208bit%205M.mp4' },
  @{ dir='video'; name='jf_1080p_hevc10_5M.mp4';     url=$jf + 'SDR/HEVC%2010bit/Test%20Jellyfin%201080p%20HEVC%2010bit%205M.mp4' },
  @{ dir='video'; name='jf_1080p_av1_10bit_5M.mp4';  url=$jf + 'SDR/AV1/Test%20Jellyfin%201080p%20AV1%2010bit%205M.mp4' },
  @{ dir='video'; name='jf_1080p_hevc_hdr10_10M.mp4';url=$jf + 'HDR/HDR10/HEVC/Test%20Jellyfin%201080p%20HEVC%20HDR10%2010M.mp4' },
  @{ dir='video'; name='jf_1080p_dv_p8.1.mp4';       url=$jf + 'HDR/Dolby%20Vision/Test%20Jellyfin%201080p%20DV%20P8.1.mp4' },
  # ---- 图片:HEIC / 手机 JPEG / 大 JPEG ----
  @{ dir='image'; name='libheif_example.heic';       url='https://github.com/strukturag/libheif/raw/master/examples/example.heic' },
  @{ dir='image'; name='iphone_13_pro_max.HEIC';     url=$exif + 'heic/mobile/iphone_13_pro_max.HEIC' },
  @{ dir='image'; name='nokia_8.3_5G.heif';          url=$exif + 'heic/mobile/HMD_Nokia_8.3_5G.heif' },
  @{ dir='image'; name='nokia_8.3_5G_hdr.jpg';       url=$exif + 'jpg/mobile/HMD_Nokia_8.3_5G_hdr.jpg' },
  @{ dir='image'; name='large_unicode_exif.jpg';     url=$exif + 'jpg/tests/46_UnicodeEncodeError.jpg' },
  # ---- RAW:raw.pixls.us(CC0)----
  @{ dir='raw';   name='Canon_EOS_R6.CR3';           url=$pix + 'Canon/EOS%20R6/Canon_EOS_R6_CRAW_ISO_100_crop_nodual.CR3' },
  @{ dir='raw';   name='Nikon_Z6_DSC_0750.NEF';      url=$pix + 'Nikon/Z%206/DSC_0750.NEF' },
  @{ dir='raw';   name='Sony_A7M3_uncompressed.ARW'; url=$pix + 'Sony/ILCE-7M3/UNCOMPRESSED_14bit.ARW' },
  @{ dir='raw';   name='Apple_iPhone12Pro_IMG_1361.DNG'; url=$pix + 'Apple/iPhone%2012%20Pro/IMG_1361.DNG' }
)

foreach ($it in $items) {
  $d = Join-Path $Real $it.dir
  New-Item -ItemType Directory -Force $d | Out-Null
  $out = Join-Path $d $it.name
  if ((Test-Path $out) -and (Get-Item $out).Length -gt 1000) { Write-Host "[skip] $($it.name)"; continue }
  Write-Host "[get ] $($it.name)"
  & curl.exe -sS -L --fail --retry 3 -A 'claude-code-sample-fetch' -o $out $it.url
  if ($LASTEXITCODE -ne 0) { Write-Warning "下载失败: $($it.url)"; Remove-Item $out -ErrorAction SilentlyContinue; continue }
  "{0,-34} {1,8:N2} MB" -f $it.name, ((Get-Item $out).Length / 1MB)
}

@"
# 测试素材来源与许可

| 目录 | 来源 | 许可 |
|---|---|---|
| video\ | Jellyfin 官方测试视频 https://repo.jellyfin.org/test-videos/ | CC BY-SA 4.0 |
| image\ (heic/heif) | libheif 仓库 examples/example.heic;ianare/exif-samples(heic/mobile) | 见各仓库;仅用于本地测试,不再分发 |
| image\ (jpg) | ianare/exif-samples https://github.com/ianare/exif-samples | 仓库未声明许可;仅用于本地测试,不再分发 |
| raw\ | raw.pixls.us https://raw.pixls.us/ | CC0 |

下载日期:$(Get-Date -Format yyyy-MM-dd)。这些文件仅用于开发测试,不要提交到版本库(已在 .gitignore 中排除)。
"@ | Set-Content (Join-Path $Real 'SOURCES.md') -Encoding utf8
Write-Host "done."
