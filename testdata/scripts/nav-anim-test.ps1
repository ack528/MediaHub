# 检查所有页面的前进 / 返回(按钮 + 手势)转场:每一步录屏 → 分析进度曲线。
#   powershell -File testdata\scripts\nav-anim-test.ps1
. (Join-Path $PSScriptRoot 'adb-ui.ps1')
$out = Join-Path $script:Root 'testdata\screens\nav'
New-Item -ItemType Directory -Force $out | Out-Null

function Record([string]$name, [scriptblock]$act) {
  & $script:adb shell rm -f /sdcard/rec.mp4
  $null = Start-Process -FilePath $script:adb -ArgumentList 'shell', 'screenrecord', '--time-limit', '5', '--size', '540x1200', '/sdcard/rec.mp4' -PassThru -WindowStyle Hidden
  Start-Sleep -Milliseconds 900
  & $act
  Start-Sleep -Seconds 7
  $f = Join-Path $out "$name.mp4"
  & $script:adb pull /sdcard/rec.mp4 $f | Out-Null
  Write-Host "=== $name"
  python (Join-Path $PSScriptRoot 'anim-curve.py') $f
}
function Swipe-Back { & $script:adb shell input swipe 2 1200 650 1200 250 }
function Key-Back { & $script:adb shell input keyevent KEYCODE_BACK }

& $script:adb shell am force-stop com.localtg
& $script:adb shell am start -n com.localtg/.MainActivity | Out-Null
Start-Sleep 5

# 列表 ↔ 设置
Record '01_列表到设置(前进)' { & $script:adb shell input tap 60 138; Start-Sleep -Milliseconds 700; Tap-Text '设置' }
Start-Sleep 1
Record '02_设置到列表(按钮返回)' { Key-Back }
& $script:adb shell input tap 60 138; Start-Sleep 1; Tap-Text '设置'; Start-Sleep 2
Record '03_设置到列表(手势返回)' { Swipe-Back }

# 设置 ↔ 子页面
& $script:adb shell input tap 60 138; Start-Sleep 1; Tap-Text '设置'; Start-Sleep 2
Record '04_设置到外观(前进)' { Tap-Text '外观' }
Start-Sleep 1
Record '05_外观到设置(按钮返回)' { Key-Back }
Tap-Text '外观'; Start-Sleep 2
Record '06_外观到设置(手势返回)' { Swipe-Back }
Key-Back; Start-Sleep 2

# 列表 ↔ 聊天
Record '07_列表到聊天(前进)' { Tap-Text '容器测试' }
Start-Sleep 2
Record '08_聊天到列表(按钮返回)' { Key-Back }
Tap-Text '容器测试'; Start-Sleep 3
Record '09_聊天到列表(手势返回)' { Swipe-Back }

# 列表 ↔ 搜索
Record '10_列表到搜索(前进)' { & $script:adb shell input tap 1011 138 }
Start-Sleep 2
Record '11_搜索到列表(按钮返回)' { Key-Back }
