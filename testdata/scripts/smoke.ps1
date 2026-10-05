# M1 验收冒烟测试:对运行中的 MediaHub 逐项调用主要接口并打印结果。
# 用法:  powershell -File testdata\scripts\smoke.ps1 [-Base http://127.0.0.1:8481]
# 账号取自 runtime\mediahub\dev-account.txt(开发用,随机密码)。
param([string]$Base = 'http://127.0.0.1:8481')
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$acct = Get-Content (Join-Path $Root 'runtime\mediahub\dev-account.txt') | ConvertFrom-StringData

$login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType 'application/json' `
  -Body (@{ username = $acct.username; password = $acct.password; deviceName = 'smoke' } | ConvertTo-Json -Compress)
$Hdr = @{ Authorization = "Bearer $($login.token)" }
function Get-Api([string]$p) { Invoke-RestMethod -Uri "$Base/api/v1/$p" -Headers $Hdr }

Write-Host "== dialogs =="
$d = Get-Api 'dialogs'
foreach ($x in $d.dialogs) {
  "{0}  {1,-28} photo={2} video={3} gif={4} audio={5}  last={6}" -f $x.id.Substring(0, 5), $x.pathDisplay, $x.counts.photo, $x.counts.video, $x.counts.gif, $x.counts.audio, $x.last.name
}
$vid = ($d.dialogs | Where-Object { $_.title -eq 'video' }).id
$img = ($d.dialogs | Where-Object { $_.title -eq 'image' }).id

Write-Host "`n== video history (sort=name asc) =="
$hist = Get-Api "dialogs/$vid/history?sort=name&dir=asc&types=video&limit=30"
"total=$($hist.total) returned=$($hist.items.Count) hasNext=$([bool]$hist.nextCursor)"
foreach ($i in $hist.items) {
  "{0,-26} {1,-5} {2}x{3} {4,-6} {5,-12} hdr={6,-5} {7}ms" -f $i.name, $i.video.container, $i.w, $i.h, $i.video.vcodec, ($i.video.acodecs -join '+'), $i.video.hdr, $i.durationMs
}

Write-Host "`n== image history (taken desc) =="
foreach ($i in (Get-Api "dialogs/$img/history?types=photo,gif&limit=20").items) { "{0,-14} {1}x{2} {3}" -f $i.name, $i.w, $i.h, $i.mime }

Write-Host "`n== search av1 / 4k =="
(Get-Api 'search?q=av1&types=video').items | ForEach-Object { $_.name }
$k = (Get-Api 'search?q=4k&types=video').items[0]

Write-Host "`n== poster (4K video) =="
$sw = [Diagnostics.Stopwatch]::StartNew()
$r1 = Invoke-WebRequest -Uri "$Base/api/v1/media/$($k.id)/poster" -Headers $Hdr -UseBasicParsing
"first : HTTP $($r1.StatusCode) $($r1.RawContentLength) bytes  $($r1.Headers['Content-Type'])  $($sw.ElapsedMilliseconds) ms"
$sw.Restart()
$r2 = Invoke-WebRequest -Uri "$Base/api/v1/media/$($k.id)/poster" -Headers $Hdr -UseBasicParsing
"cached: HTTP $($r2.StatusCode) $($r2.RawContentLength) bytes  $($sw.ElapsedMilliseconds) ms"

Write-Host "`n== range request =="
$req = [Net.HttpWebRequest]::Create("$Base/api/v1/media/$($k.id)/file")
$req.Headers.Add('Authorization', "Bearer $($login.token)"); $req.AddRange(0, 99)
$resp = $req.GetResponse()
"HTTP $([int]$resp.StatusCode)  Content-Range=$($resp.Headers['Content-Range'])  Type=$($resp.ContentType)  Accept-Ranges=$($resp.Headers['Accept-Ranges'])"
$resp.Close()

Write-Host "`n== cache files on the media drive =="
Get-ChildItem (Join-Path $Root 'testdata\generated\.mediahub') -Recurse -Force |
  Where-Object { -not $_.PSIsContainer } | ForEach-Object { "{0}  {1} B" -f $_.FullName.Substring($Root.Length + 1), $_.Length }

Write-Host "`n== admin status =="
$st = Get-Api 'admin/status'
"version=$($st.version) media=$($st.media) dialogs=$($st.dialogs) warnings=$($st.warnings.Count)"
foreach ($c in $st.cache) { "cache root=$($c.rootId) mode=$($c.mode) used=$($c.usedBytes) quota=$($c.quotaBytes) driveFreeGB=$([math]::Round($c.driveFreeGB,1))" }
foreach ($p in $st.index) { "index $($p.label) state=$($p.state) dirs=$($p.dirs) files=$($p.files) enriched=$($p.enriched)" }
