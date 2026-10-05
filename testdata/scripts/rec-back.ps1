# 录屏 + 抽帧,检查返回动画。先让手机停在要测试的页面,再运行:
#   powershell -File testdata\scripts\rec-back.ps1 -Mode swipe|button|cancel -Name back1 [-Fps 12]
# swipe  = 从左边缘慢拖到约 60% 宽度后松手(预测性返回,会提交)
# cancel = 从左边缘拖到约 25% 宽度,停一下再拖回去松手(取消)
# button = 触发一次返回键(非手势)
param([ValidateSet('swipe', 'button', 'cancel')][string]$Mode = 'swipe', [string]$Name = 'back', [int]$Fps = 12)
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$adb = Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
$ff = Join-Path $Root 'tools\ffmpeg\ffmpeg.exe'
$out = Join-Path $Root "testdata\screens\$Name"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $out -Filter *.png | Remove-Item -Force
& $adb shell rm -f /sdcard/rec.mp4
$rec = Start-Process -FilePath $adb -ArgumentList 'shell', 'screenrecord', '--time-limit', '5', '--size', '540x1200', '/sdcard/rec.mp4' -PassThru -WindowStyle Hidden
Start-Sleep -Milliseconds 800
switch ($Mode) {
  'swipe' { & $adb shell input swipe 2 1200 650 1200 900 }
  'cancel' {
    & $adb shell 'input draganddrop 2 1200 270 1200 700'  # 近似:拖到 25% 后释放(draganddrop 不会"拖回去",这里只验证慢拖时的跟手效果)
  }
  'button' { & $adb shell input keyevent KEYCODE_BACK }
}
Start-Sleep -Seconds 5
& $adb pull /sdcard/rec.mp4 (Join-Path $out 'rec.mp4') | Out-Null
& $ff -y -v error -i (Join-Path $out 'rec.mp4') -vf "fps=$Fps,scale=270:-1,tile=8x3" -frames:v 1 (Join-Path $out 'sheet.png')
Write-Host "已生成 $out\sheet.png"
