# 检查视频封面来自哪里:Jellyfin 已有的(直接转发)还是我们自己生成的(缓存在媒体盘 .mediahub)。
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$acct = Get-Content (Join-Path $Root 'runtime\mediahub\dev-account.txt') | ConvertFrom-StringData
$B = 'http://127.0.0.1:8480/api/v1'
$login = Invoke-RestMethod "$B/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username = $acct.username; password = $acct.password } | ConvertTo-Json -Compress)
$H = @{ Authorization = "Bearer $($login.token)" }
$dialogs = (Invoke-RestMethod "$B/dialogs?limit=100" -Headers $H).dialogs
foreach ($title in '手机视频', '容器测试') {
  $d = $dialogs | Where-Object { $_.pathDisplay -like "LIB:*$title" } | Select-Object -First 1
  $items = (Invoke-RestMethod "$B/dialogs/$($d.id)/history?types=video&limit=4" -Headers $H).items
  "== $title =="
  foreach ($it in $items) {
    $r = Invoke-WebRequest "$B/media/$($it.id)/poster" -Headers $H -UseBasicParsing
    "{0,-32} source={1,-9} {2,6:N1} KB" -f $it.name, $r.Headers['X-Poster-Source'], ($r.RawContentLength / 1KB)
  }
}
"== 重新取列表,看 thumbhash 是否已补上 =="
$d = $dialogs | Where-Object { $_.pathDisplay -like 'LIB:*手机视频' } | Select-Object -First 1
(Invoke-RestMethod "$B/dialogs/$($d.id)/history?types=video" -Headers $H).items | ForEach-Object { "{0,-32} thumbhash={1}" -f $_.name, $(if ($_.thumbhash) { $_.thumbhash.Length.ToString() + ' chars' } else { 'none' }) }
"== 我们自己的缓存目录里的封面文件数(LIB) =="
(Get-ChildItem (Join-Path $Root 'testdata\library\.mediahub\posters') -Recurse -File -ErrorAction SilentlyContinue | Measure-Object).Count
$st = Invoke-RestMethod "$B/admin/status" -Headers $H
"Jellyfin 已映射视频数: " + (& (Join-Path $Root 'tools\sqlite\sqlite3.exe') (Join-Path $Root 'runtime\data\hub.sqlite') "select count(*) from media where jf_item_id is not null")
