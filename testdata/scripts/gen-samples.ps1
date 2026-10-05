# 用 ffmpeg / vips 生成测试样本(免下载、可复现)。输出到 testdata\generated\{video,image}
# 用法:  . .\env.ps1 ; powershell -File testdata\scripts\gen-samples.ps1
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$V = Join-Path $Root 'testdata\generated\video'
$I = Join-Path $Root 'testdata\generated\image'
New-Item -ItemType Directory -Force $V, $I | Out-Null

function Enc($name, [string[]]$extra, $dur = 3, $src = 'testsrc2=size=1280x720:rate=30') {
  $out = Join-Path $V $name
  if (Test-Path $out) { return }
  $args = @('-hide_banner','-v','error','-y','-f','lavfi','-i',$src,'-f','lavfi','-i',"sine=frequency=440:sample_rate=48000",'-t',$dur) + $extra + @($out)
  & ffmpeg @args
  "{0,-34} {1}" -f $name, $(if ($LASTEXITCODE -eq 0) { 'ok' } else { 'FAIL' })
}

# ---- 视频:容器 × 编码 × 音频 ----
Enc 'h264_aac.mp4'        @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac')
Enc 'h264_aac_moovend.mp4' @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac')            # moov 在尾部(默认即是)
Enc 'h264_aac_faststart.mp4' @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac','-movflags','+faststart')
Enc 'hevc8_aac.mp4'       @('-c:v','libx265','-pix_fmt','yuv420p','-tag:v','hvc1','-c:a','aac')
Enc 'hevc10_ac3.mkv'      @('-c:v','libx265','-pix_fmt','yuv420p10le','-c:a','ac3')
Enc 'hevc10_hdr10.mkv'    @('-c:v','libx265','-pix_fmt','yuv420p10le','-x265-params','colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc','-c:a','aac')
Enc 'h264_mp3.avi'        @('-c:v','mpeg4','-c:a','libmp3lame')
Enc 'h264_aac.mov'        @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac')
Enc 'vp9_opus.webm'       @('-c:v','libvpx-vp9','-b:v','1M','-c:a','libopus')
Enc 'h264_aac.ts'         @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac')
Enc 'h264_mp3.flv'        @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','libmp3lame')
Enc 'h263_aac.3gp'        @('-c:v','h263','-s','352x288','-c:a','aac','-ac','1') 3 'testsrc2=size=352x288:rate=15'
Enc 'wmv2_wma.wmv'        @('-c:v','wmv2','-c:a','wmav2')
Enc 'mpeg2_mp2.mpg'       @('-c:v','mpeg2video','-c:a','mp2')
Enc 'av1_opus.mkv'        @('-c:v','libsvtav1','-pix_fmt','yuv420p','-c:a','libopus')
Enc 'h264_flac.mkv'       @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','flac')
Enc 'h264_aac_1080p.mp4'  @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac') 5 'testsrc2=size=1920x1080:rate=30'
Enc 'h264_4k.mp4'         @('-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac') 3 'testsrc2=size=3840x2160:rate=30'

# ---- 图片:以一张 PNG 为母版,转各种格式(用 vips,顺便验证 HEIC/AVIF/JXL 读写)----
$base = Join-Path $I 'base.png'
if (-not (Test-Path $base)) { & ffmpeg -hide_banner -v error -y -f lavfi -i "testsrc2=size=4000x3000:rate=1" -frames:v 1 $base }
$fmts = @('jpg','webp','tif','bmp','gif','heic','avif','jxl')
foreach ($f in $fmts) {
  $out = Join-Path $I "sample.$f"
  if (Test-Path $out) { continue }
  & vips copy $base $out 2>&1 | Out-Null
  "{0,-34} {1}" -f "sample.$f", $(if ((Test-Path $out) -and $LASTEXITCODE -eq 0) { 'ok' } else { 'FAIL (vips 写入不支持)' })
}
Write-Host "done -> $V , $I"
