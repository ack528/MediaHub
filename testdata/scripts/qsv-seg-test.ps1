# 检查 QSV / libx264 编码时,强制关键帧是否让分段严格按 4 秒切分(内置转码依赖这一点)。
#   powershell -File testdata\scripts\qsv-seg-test.ps1 [-Enc h264_qsv|libx264] [-Extra "-g 96"]
param([string]$Enc = 'h264_qsv', [string]$Extra = '-g 96 -bf 2', [string]$Pix = 'nv12')
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ff = Join-Path $Root 'tools\ffmpeg\ffmpeg.exe'
$in = Join-Path $Root 'testdata\generated\video\h264_aac_1080p.mp4'
$d = Join-Path $env:TEMP 'qsvtest'
Remove-Item $d -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $d | Out-Null
Push-Location $d
$a = @('-hide_banner', '-loglevel', 'error', '-stream_loop', '3', '-i', $in, '-map', '0:v:0',
  '-vf', "scale=-2:'min(480,ih)',format=$Pix", '-c:v', $Enc, '-b:v', '1500000', '-maxrate', '1500000') + ($Extra -split ' ') +
  @('-force_key_frames', 'expr:gte(t,n_forced*4)', '-an', '-f', 'hls', '-hls_time', '4', '-hls_list_size', '0',
    '-hls_flags', 'independent_segments+temp_file', '-hls_segment_filename', 'seg%05d.ts', 'run.m3u8')
& $ff @a 2>&1 | Select-Object -First 5
if (Test-Path run.m3u8) { Get-Content run.m3u8 | Select-String EXTINF } else { 'ffmpeg 失败(没有生成播放列表)' }
Pop-Location
