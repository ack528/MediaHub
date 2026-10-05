# 补帧效果检查:播放「增强测试」里的 movebox 视频(白方块匀速右移),分别在 关闭 / 帧混合 / 运动补偿 下录屏 3 秒并分析方块位移。
#   powershell -File testdata\scripts\frc-test.ps1 [-Pick 15:00]
param([string]$Pick = '15:00')
. (Join-Path $PSScriptRoot 'adb-ui.ps1')
function Has-Node([string]$text) { [bool](Get-Nodes | Where-Object { $_.text -like "*$text*" -or $_.'content-desc' -like "*$text*" } | Select-Object -First 1) }
function Chrome { if (-not (Has-Node '更多')) { & $script:adb shell input tap 540 1100; Start-Sleep 1 } }
function Pick-Mode([string]$label) {
  Chrome
  & $script:adb shell input tap 1011 138; Start-Sleep 1
  Tap-Like '画质增强(超分'; Start-Sleep 1
  Tap-Like $label; Start-Sleep 3
  & $script:adb shell input tap 540 1100; Start-Sleep 1   # 收起控制条(避免控件文字干扰方块跟踪)
}
# 打开视频(最新的那个 15:00)
& $script:adb shell am force-stop com.localtg
& $script:adb shell am start -n com.localtg/.MainActivity | Out-Null
Start-Sleep 5
Tap-Like '增强测试'; Start-Sleep 3
$n = Get-Nodes | Where-Object { $_.text -eq $Pick } | Select-Object -First 1
$c = Get-Center $n; & $script:adb shell input tap ($c[0] + 200) ($c[1] + 200); Start-Sleep 5
foreach ($m in @(@('关闭全部增强', 'off'), @('补帧:帧混合', 'blend'), @('补帧:运动补偿', 'mc'))) {
  Pick-Mode $m[0]
  & $script:adb shell rm -f /sdcard/rec.mp4
  $null = Start-Process -FilePath $script:adb -ArgumentList 'shell', 'screenrecord', '--time-limit', '3', '--size', '540x1200', '/sdcard/rec.mp4' -PassThru -WindowStyle Hidden
  Start-Sleep 5
  $out = Join-Path $script:Root "testdata\screens\box_$($m[1]).mp4"
  & $script:adb pull /sdcard/rec.mp4 $out | Out-Null
  Write-Host "=== $($m[1])"
  python (Join-Path $PSScriptRoot 'motion-box.py') $out
}
Pick-Mode '关闭全部增强'
