# 在模拟器里用 LSFG 帧生成播放「增强测试」里的 movebox 视频,录屏并分析方块位移,同时看日志
param([string]$Pick = '15:00')
. (Join-Path $PSScriptRoot 'adb-ui.ps1')
function Has-Node([string]$text) { [bool](Get-Nodes | Where-Object { $_.text -like "*$text*" -or $_.'content-desc' -like "*$text*" } | Select-Object -First 1) }
function Chrome { if (-not (Has-Node '更多')) { & $script:adb shell input tap 540 1100; Start-Sleep 1 } }
& $script:adb logcat -c
& $script:adb shell am force-stop com.localtg
& $script:adb shell am start -n com.localtg/.MainActivity | Out-Null
Start-Sleep 6
Tap-Like '增强测试'; Start-Sleep 3
$n = Get-Nodes | Where-Object { $_.text -eq $Pick } | Select-Object -First 1
$c = Get-Center $n; & $script:adb shell input tap ($c[0] + 200) ($c[1] + 200); Start-Sleep 6
Chrome
& $script:adb shell input tap 1011 138; Start-Sleep 1
Tap-Like '画质增强(超分'; Start-Sleep 1
Tap-Like '补帧:LSFG'; Start-Sleep 8
& $script:adb shell input tap 540 1100; Start-Sleep 1
& $script:adb shell rm -f /sdcard/rec.mp4
$null = Start-Process -FilePath $script:adb -ArgumentList 'shell', 'screenrecord', '--time-limit', '3', '--size', '540x1200', '/sdcard/rec.mp4' -PassThru -WindowStyle Hidden
Start-Sleep 5
$out = Join-Path $script:Root 'testdata\screens\box_lsfg.mp4'
& $script:adb pull /sdcard/rec.mp4 $out | Out-Null
python (Join-Path $PSScriptRoot 'motion-box.py') $out
Shot 'lsfg-play'
& $script:adb logcat -d | Select-String -Pattern 'lsfg|enhance|FATAL|AndroidRuntime: Shutting|Fatal signal' | Where-Object { $_ -notmatch 'uiautomator' } | Select-Object -Last 25
