# 管理项目内的便携版 Jellyfin(只当转码器用,仅监听 127.0.0.1)。
#   powershell -File tools\jellyfin.ps1 start     # 启动(首次会写入只监听回环的网络配置)
#   powershell -File tools\jellyfin.ps1 stop
#   powershell -File tools\jellyfin.ps1 setup     # 首次初始化:创建管理员、API 密钥,把密钥写进 MediaHub 配置
#   powershell -File tools\jellyfin.ps1 status
# 管理员密码随机生成,保存在 runtime\jellyfin\dev-admin.txt(开发用)。
param([ValidateSet('start', 'stop', 'setup', 'status')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$JF = Join-Path $Root 'runtime\jellyfin'
$Exe = Join-Path $Root 'tools\jellyfin\jellyfin\jellyfin.exe'
$Base = 'http://127.0.0.1:8096'
$Auth = 'MediaBrowser Client="MediaHub", Device="gateway", DeviceId="mediahub-gateway", Version="0.1"'

function Test-Up { try { $null = Invoke-RestMethod "$Base/System/Info/Public" -TimeoutSec 2; $true } catch { $false } }

function Start-JF {
  if (Test-Up) { Write-Host "Jellyfin 已在运行"; return }
  foreach ($d in 'data', 'cache', 'config', 'log', 'transcode') { New-Item -ItemType Directory -Force (Join-Path $JF $d) | Out-Null }
  $net = Join-Path $JF 'config\network.xml'
  if (-not (Test-Path $net)) {
    @'
<?xml version="1.0" encoding="utf-8"?>
<NetworkConfiguration xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <InternalHttpPort>8096</InternalHttpPort>
  <PublicHttpPort>8096</PublicHttpPort>
  <AutoDiscovery>false</AutoDiscovery>
  <EnableUPnP>false</EnableUPnP>
  <EnableIPv6>false</EnableIPv6>
  <RequireHttps>false</RequireHttps>
  <LocalNetworkAddresses><string>127.0.0.1</string></LocalNetworkAddresses>
</NetworkConfiguration>
'@ | Set-Content $net -Encoding utf8
  }
  $ffmpeg = Join-Path $Root 'tools\ffmpeg\ffmpeg.exe'
  Start-Process -FilePath $Exe -WindowStyle Hidden -WorkingDirectory (Split-Path $Exe) -ArgumentList @(
    '-d', (Join-Path $JF 'data'), '-C', (Join-Path $JF 'cache'), '-c', (Join-Path $JF 'config'),
    '-l', (Join-Path $JF 'log'), '--ffmpeg', $ffmpeg, '--nowebclient')
  for ($i = 0; $i -lt 90 -and -not (Test-Up); $i++) { Start-Sleep -Seconds 1 }
  if (Test-Up) { Write-Host "Jellyfin 已启动: $Base" } else { throw "Jellyfin 90 秒内没有启动,查看 $JF\log" }
}

switch ($Action) {
  'start' { Start-JF }
  'stop' { Get-Process jellyfin -ErrorAction SilentlyContinue | Stop-Process -Force; Write-Host "已停止" }
  'status' {
    if (Test-Up) { $i = Invoke-RestMethod "$Base/System/Info/Public"; "运行中 版本 $($i.Version) 向导完成=$($i.StartupWizardCompleted)" } else { "未运行" }
  }
  'setup' {
    Start-JF
    $cred = Join-Path $JF 'dev-admin.txt'
    $info = Invoke-RestMethod "$Base/System/Info/Public"
    if (-not $info.StartupWizardCompleted) {
      $pw = -join ((48..57) + (97..122) | Get-Random -Count 16 | ForEach-Object { [char]$_ })
      Set-Content $cred "username=mediahub`npassword=$pw" -Encoding ascii
      $h = @{ 'Content-Type' = 'application/json' }
      Invoke-RestMethod "$Base/Startup/Configuration" -Method Post -Headers $h -Body '{"UICulture":"zh-CN","MetadataCountryCode":"CN","PreferredMetadataLanguage":"zh"}' | Out-Null
      Invoke-RestMethod "$Base/Startup/User" -Method Get | Out-Null
      Invoke-RestMethod "$Base/Startup/User" -Method Post -Headers $h -Body (@{ Name = 'mediahub'; Password = $pw } | ConvertTo-Json -Compress) | Out-Null
      Invoke-RestMethod "$Base/Startup/RemoteAccess" -Method Post -Headers $h -Body '{"EnableRemoteAccess":false,"EnableAutomaticPortMapping":false}' | Out-Null
      Invoke-RestMethod "$Base/Startup/Complete" -Method Post | Out-Null
      Write-Host "向导已完成,管理员账号已创建(见 $cred)"
    }
    $c = Get-Content $cred | ConvertFrom-StringData
    $login = Invoke-RestMethod "$Base/Users/AuthenticateByName" -Method Post -ContentType 'application/json' `
      -Headers @{ Authorization = $Auth } -Body (@{ Username = $c.username; Pw = $c.password } | ConvertTo-Json -Compress)
    $hdr = @{ Authorization = "$Auth, Token=`"$($login.AccessToken)`"" }
    $keys = (Invoke-RestMethod "$Base/Auth/Keys" -Headers $hdr).Items | Where-Object { $_.AppName -eq 'MediaHub' }
    if (-not $keys) {
      Invoke-RestMethod "$Base/Auth/Keys?app=MediaHub" -Method Post -Headers $hdr | Out-Null
      $keys = (Invoke-RestMethod "$Base/Auth/Keys" -Headers $hdr).Items | Where-Object { $_.AppName -eq 'MediaHub' }
    }
    $apiKey = $keys[0].AccessToken
    # 写进 MediaHub 的配置(jellyfin.apiKey)
    $cfgPath = Join-Path $Root 'runtime\mediahub\config.json'
    $cfg = [IO.File]::ReadAllText($cfgPath, [Text.Encoding]::UTF8) | ConvertFrom-Json   # 必须显式 UTF-8,否则中文路径会被读成乱码
    if (-not $cfg.jellyfin) { $cfg | Add-Member -NotePropertyName jellyfin -NotePropertyValue ([pscustomobject]@{}) }
    $cfg.jellyfin | Add-Member -NotePropertyName apiKey -NotePropertyValue $apiKey -Force
    $cfg.jellyfin | Add-Member -NotePropertyName url -NotePropertyValue $Base -Force
    [IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    Write-Host "API 密钥已写入 MediaHub 配置(jellyfin.apiKey)"
  }
}
