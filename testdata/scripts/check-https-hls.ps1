# 验证:HTTPS(8480,自签名)可用、本机管理端口(8481,明文)可用、内置 ffmpeg 转码可播(不打印任何凭据)。
#   powershell -File testdata\scripts\check-https-hls.ps1 [-Engine ffmpeg]
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$acc = @{}
Get-Content (Join-Path $Root 'runtime\mediahub\emu-account.txt') | ForEach-Object { $k, $v = $_ -split '[:=]', 2; $acc[$k.Trim()] = $v.Trim() }
$curl = 'curl.exe'
function J($args2) { & $curl -s -k @args2 }

Write-Host '--- HTTPS server/info'
J @('https://127.0.0.1:8480/api/v1/server/info')
Write-Host "`n--- 明文 HTTP 访问 8480 应被拒绝"
& $curl -s -o NUL -w '%{http_code}' http://127.0.0.1:8480/api/v1/server/info; Write-Host ''
Write-Host '--- 本机管理端口 8481 (server/info)'
& $curl -s http://127.0.0.1:8481/api/v1/server/info; Write-Host ''

$body = @{ username = $acc['username']; password = $acc['password']; deviceName = 'check' } | ConvertTo-Json -Compress
$tmp = Join-Path $env:TEMP 'mh-login.json'; [IO.File]::WriteAllText($tmp, $body)
$login = & $curl -s -k -X POST -H 'Content-Type: application/json' --data-binary "@$tmp" https://127.0.0.1:8480/api/v1/auth/login | ConvertFrom-Json
Remove-Item $tmp
$tok = $login.token
if (-not $tok) { throw '登录失败' }
$H = @('-H', "Authorization: Bearer $tok")

$s = & $curl -s -k @H 'https://127.0.0.1:8480/api/v1/search?q=h264_aac_1080p&types=video&limit=5' | ConvertFrom-Json
$vid = ($s.items | Select-Object -First 1).id
Write-Host "视频 $vid $(($s.items | Select-Object -First 1).name)"
Write-Host '--- HLS 播放列表(前 8 行)'
& $curl -s -k @H "https://127.0.0.1:8480/api/v1/media/$vid/hls/master.m3u8?maxHeight=480&maxBitrate=1500000&sid=chk" | Select-Object -First 8
Write-Host '--- 第 1 段(跳过第 0 段,模拟拖动)/ 第 0 段'
foreach ($i in 1, 0) {
  $out = Join-Path $env:TEMP "mh-seg$i.ts"
  $code = & $curl -s -k @H -o $out -w '%{http_code} %{size_download}B %{time_total}s' "https://127.0.0.1:8480/api/v1/media/$vid/hls/seg0000$i.ts?maxHeight=480&maxBitrate=1500000&sid=chk"
  Write-Host "seg$i : $code"
}
$ff = Join-Path $Root 'tools\ffmpeg\ffprobe.exe'
& $ff -v error -show_entries format=start_time,duration -of default=nw=1 (Join-Path $env:TEMP 'mh-seg1.ts')
& $curl -s -k @H -X DELETE "https://127.0.0.1:8480/api/v1/media/$vid/hls?sid=chk" -o NUL -w 'stop: %{http_code}'
Write-Host ''


