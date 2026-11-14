# 备份签名密钥:丢了就无法覆盖安装旧版 APK。用法:  powershell -File toolsackup-signing.ps1 -To E:\备份目录
param([Parameter(Mandatory)][string]$To)
$src = Join-Path (Split-Path -Parent $PSScriptRoot) 'signing'
if (-not (Test-Path $src)) { throw "找不到 $src" }
New-Item -ItemType Directory -Force $To | Out-Null
$zip = Join-Path $To ("signing-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".zip")
Compress-Archive -Path "$src\*" -DestinationPath $zip
Write-Host "已备份到 $zip(含密钥和密码,请放在安全的地方,不要上传)"
