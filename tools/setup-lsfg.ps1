# 把 LSFG 帧生成需要的源码和 Lossless.dll 放到安卓工程里(只在你自己的电脑上做,这些文件都在 .gitignore 里,不会提交)。
#   powershell -File tools\setup-lsfg.ps1
# 前提:
#   参考项目\LSFG-Android\            已克隆(git clone --recurse-submodules https://github.com/FrankBarretta/LSFG-Android)
#   参考项目\Lossless.dll             你自己购买的 Lossless Scaling 里的 Lossless.dll(版权属于 THS,不要分发带它的 APK)
# 为什么要复制而不是直接引用 参考项目\:CMake / Ninja 在 Windows 上处理不了路径里的中文,必须是纯 ASCII 路径。
$Root = Split-Path -Parent $PSScriptRoot
$Ref = Join-Path $Root '参考项目'
$Vk = Join-Path $Ref 'LSFG-Android\lsfg-vk-android'
$App = Join-Path $Ref 'LSFG-Android\LSFG-Android-Application\app\src\main\cpp'
$Dll = Join-Path $Ref 'Lossless.dll'
foreach ($p in @($Vk, $App, $Dll)) { if (-not (Test-Path $p)) { throw "找不到 $p" } }

$Dst = Join-Path $Root 'android\app\src\main\cpp\lsfg\vendor'
$VkDst = Join-Path $Dst 'lsfg-vk-android'
New-Item -ItemType Directory -Force $VkDst | Out-Null
foreach ($d in @('framegen', 'thirdparty\volk', 'thirdparty\pe-parse', 'thirdparty\dxbc')) {
  $to = Join-Path $VkDst $d
  & robocopy (Join-Path $Vk $d) $to /E /XD .git docs tests /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "复制失败: $d" }
}
$global:LASTEXITCODE = 0
# 本项目对 framegen 的补丁(目前:帧生成队列设为 LOW 全局优先级,让上屏能抢占),覆盖到复制来的源码上
$Patch = Join-Path $PSScriptRoot 'lsfg-patches'
if (Test-Path $Patch) {
  foreach ($f in Get-ChildItem $Patch -Recurse -File) {
    $rel = $f.FullName.Substring($Patch.Length + 1)
    $to = Join-Path $VkDst $rel
    New-Item -ItemType Directory -Force (Split-Path $to) | Out-Null
    Copy-Item $f.FullName $to -Force
  }
}
$AppDst = Join-Path $Dst 'app'
New-Item -ItemType Directory -Force $AppDst | Out-Null
foreach ($f in @('android_shader_loader.cpp', 'android_shader_loader.hpp', 'unicode_minimal.cpp')) {
  Copy-Item (Join-Path $App $f) (Join-Path $AppDst $f) -Force
}
# 授权文件一起带上
Copy-Item (Join-Path $Vk 'LICENSE.md') (Join-Path $VkDst 'LICENSE.md') -Force

$Assets = Join-Path $Root 'android\app\src\main\assets\lsfg'
New-Item -ItemType Directory -Force $Assets | Out-Null
Copy-Item $Dll (Join-Path $Assets 'Lossless.dll') -Force
Write-Host "完成:" -ForegroundColor Green
Write-Host "  源码  $Dst"
Write-Host "  DLL   $Assets\Lossless.dll(会打进你自己的 APK,请不要把 APK 发给别人)"
