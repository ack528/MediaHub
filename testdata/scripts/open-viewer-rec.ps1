# 录屏检查"点开图片 / 视频"的转场(会不会闪):  powershell -File testdata\scripts\open-viewer-rec.ps1 -Folder 大相册 -Name open1
param([string]$Folder = '大相册', [string]$Name = 'open1', [int]$Frames = 32)
. (Join-Path $PSScriptRoot 'adb-ui.ps1')
$ff = Join-Path $script:Root 'tools\ffmpeg\ffmpeg.exe'
$out = Join-Path $script:Root "testdata\screens\$Name"
New-Item -ItemType Directory -Force $out | Out-Null
& $script:adb shell am force-stop com.localtg
& $script:adb shell am start -n com.localtg/.MainActivity | Out-Null
Start-Sleep 5
Tap-Like $Folder; Start-Sleep 10
& $script:adb shell rm -f /sdcard/rec.mp4
$null = Start-Process -FilePath $script:adb -ArgumentList 'shell', 'screenrecord', '--time-limit', '4', '--size', '540x1200', '/sdcard/rec.mp4' -PassThru -WindowStyle Hidden
Start-Sleep -Milliseconds 800
& $script:adb shell input tap 540 1500   # 点聊天流里靠下的一张
Start-Sleep -Seconds 7
& $script:adb pull /sdcard/rec.mp4 (Join-Path $out 'rec.mp4') | Out-Null
python (Join-Path $PSScriptRoot 'anim-sheet.py') (Join-Path $out 'rec.mp4') (Join-Path $out 'sheet.png') $Frames 8
