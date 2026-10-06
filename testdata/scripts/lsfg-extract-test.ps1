# 在模拟器里验证 LSFG:打开 设置 → 画质增强 → LSFG 帧生成,点"现在提取着色器",看日志
. (Join-Path $PSScriptRoot 'adb-ui.ps1')
& $script:adb logcat -c
& $script:adb shell am start -n com.localtg/.MainActivity | Out-Null
Start-Sleep 5
Tap-Text '菜单'; Start-Sleep 1
Tap-Text '设置'; Start-Sleep 2
Tap-Like '画质增强'; Start-Sleep 1
Tap-Like 'LSFG 帧生成'; Start-Sleep 1
Shot 'lsfg-1'
Tap-Text '现在提取着色器'
Start-Sleep 12
Shot 'lsfg-2'
& $script:adb logcat -d | Select-String -Pattern 'lsfg|AndroidRuntime|FATAL|UnsatisfiedLink' | Select-Object -Last 25
