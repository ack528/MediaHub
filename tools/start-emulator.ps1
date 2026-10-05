# 启动你已安装的 Android Studio 模拟器(AVD),并等待系统启动完成。
# 模拟器与系统镜像复用 D:\AppData\AndroidSDK;adb 使用项目内的 tools\android-sdk\platform-tools。
param([string]$Avd = 'Medium_Phone_API_37.0', [string]$EmuSdk = 'D:\AppData\AndroidSDK', [int]$TimeoutSec = 240)
$Root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
$emu = Join-Path $EmuSdk 'emulator\emulator.exe'
if (-not (Test-Path $emu)) { throw "找不到模拟器: $emu" }

& $adb start-server | Out-Null
$running = (& $adb devices) -match 'emulator-\d+\s+device'
if (-not $running) {
  $env:ANDROID_SDK_ROOT = $EmuSdk
  $env:ANDROID_HOME = $EmuSdk
  Remove-Item Env:\ANDROID_AVD_HOME -ErrorAction SilentlyContinue
  Start-Process -FilePath $emu -ArgumentList @('-avd', $Avd, '-no-audio', '-no-boot-anim', '-netdelay', 'none', '-netspeed', 'full') -WindowStyle Minimized
}
$t0 = Get-Date
do {
  Start-Sleep -Seconds 3
  $boot = (& $adb shell getprop sys.boot_completed 2>$null) -join ''
} while ($boot.Trim() -ne '1' -and ((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec)
if ($boot.Trim() -eq '1') {
  Write-Host ("模拟器已就绪({0:N0}s):{1}" -f ((Get-Date) - $t0).TotalSeconds, ((& $adb devices) -join ' | '))
  & $adb shell getprop ro.build.version.release
} else { Write-Warning "模拟器在 $TimeoutSec 秒内没有启动完成" }
