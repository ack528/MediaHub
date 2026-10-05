# adb 界面自动化小工具(点 / 输入都先用 uiautomator 找控件坐标,避免键盘弹出后布局移位点偏)。
#   . .\testdata\scripts\adb-ui.ps1
$script:Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:adb = Join-Path $script:Root 'tools\android-sdk\platform-tools\adb.exe'

function Get-Nodes {
  & $script:adb shell uiautomator dump /sdcard/u.xml | Out-Null
  $f = Join-Path $env:TEMP 'u.xml'
  & $script:adb pull /sdcard/u.xml $f | Out-Null
  ([xml](Get-Content $f -Encoding UTF8)).SelectNodes('//node')
}
function Get-Center($n) {
  if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return @([int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2)) }
}
function Tap-Text([string]$text, [string]$cls = '') {
  $n = Get-Nodes | Where-Object { ($_.text -eq $text -or $_.'content-desc' -eq $text) -and ($cls -eq '' -or $_.class -eq $cls) } | Select-Object -First 1
  if (-not $n) { throw "找不到控件: $text" }
  $c = Get-Center $n; & $script:adb shell input tap $c[0] $c[1]
}
function Set-Field([int]$index, [string]$value) {
  $n = @(Get-Nodes | Where-Object { $_.class -eq 'android.widget.EditText' })[$index]
  if (-not $n) { throw "找不到输入框 #$index" }
  $c = Get-Center $n
  & $script:adb shell input tap $c[0] $c[1]
  & $script:adb shell input keycombination 113 29   # Ctrl+A
  & $script:adb shell input keyevent KEYCODE_DEL
  & $script:adb shell input text $value
}
function Shot([string]$name) {
  & $script:adb shell screencap -p /sdcard/s.png
  & $script:adb pull /sdcard/s.png (Join-Path $script:Root "testdata\screens\$name.png") | Out-Null
}

function Tap-Like([string]$part) {
  $n = Get-Nodes | Where-Object { $_.text -like "*$part*" } | Select-Object -First 1
  if (-not $n) { throw "找不到包含该文字的控件: $part" }
  $c = Get-Center $n; & $script:adb shell input tap $c[0] $c[1]
}
