# 在模拟器里依次测试画质增强:打开「增强测试」里的长测试视频,逐项切换并截图 / 取日志。
#   powershell -File testdata\scripts\enh-test.ps1 -Modes fsr,anime4k_s,anime4k_m,blend,mc
param([string[]]$Modes = @('fsr'), [string]$Pick = '4:00')
$Modes = $Modes -split ','
. (Join-Path $PSScriptRoot 'adb-ui.ps1')

function Has-Node([string]$text) { [bool](Get-Nodes | Where-Object { $_.text -like "*$text*" -or $_.'content-desc' -like "*$text*" } | Select-Object -First 1) }

function Open-Video {
  & $script:adb shell am force-stop com.localtg
  & $script:adb shell am start -n com.localtg/.MainActivity | Out-Null
  Start-Sleep 5
  Tap-Like '增强测试'
  Start-Sleep 3
  $n = Get-Nodes | Where-Object { $_.text -eq $Pick } | Select-Object -First 1
  if ($n) { $c = Get-Center $n; & $script:adb shell input tap ($c[0] + 200) ($c[1] + 200) } else { & $script:adb shell input tap 400 700 }
  Start-Sleep 4
}

function Set-Mode([string]$mode) {
  if (-not (Has-Node '画质增强(超分')) {
    if (-not (Has-Node '更多')) { & $script:adb shell input tap 540 1100; Start-Sleep 1 }  # 唤出控制条
    & $script:adb shell input tap 1011 138; Start-Sleep 1                                  # 更多
  }
  Tap-Like '画质增强(超分'; Start-Sleep 1
  $label = switch ($mode) {
    'off' { '关闭全部增强' } 'fsr' { '超分:FSR' } 'anime4k_s' { '超分:Anime4K 小模型' } 'anime4k_m' { '超分:Anime4K 中模型' }
    'blend' { '补帧:帧混合' } 'mc' { '补帧:运动补偿' } 'hdr' { 'SDR→HDR' }
  }
  Tap-Like $label
}

function Stats { (Get-Nodes | Where-Object { $_.text -like '*增强渲染*' } | ForEach-Object { $_.text }) }

& $script:adb logcat -c
Open-Video
foreach ($m in $Modes) {
  Set-Mode 'off'; Start-Sleep 1
  Set-Mode $m
  Start-Sleep 6
  Shot "enh_$m"
  Write-Host "== $m"
  Stats
  & $script:adb logcat -d -s enhance:W enhance:E | Select-Object -Last 4
}
Set-Mode 'off'
