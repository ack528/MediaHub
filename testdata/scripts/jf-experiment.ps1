# 实验:Jellyfin 对视频自己生成什么图片?生成要多久?存在哪里?我们能不能直接复用?
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$B = 'http://127.0.0.1:8096'
$cfg = Get-Content (Join-Path $Root 'runtime\mediahub\config.json') -Raw | ConvertFrom-Json
$H = @{ Authorization = "MediaBrowser Token=`"$($cfg.jellyfin.apiKey)`"" }
function J($path, $method = 'Get', $body = $null) {
  $p = @{ Uri = "$B$path"; Method = $method; Headers = $H }
  if ($body) { $p.Body = ($body | ConvertTo-Json -Depth 8 -Compress); $p.ContentType = 'application/json' }
  Invoke-RestMethod @p
}

$libPath = (Resolve-Path (Join-Path $Root 'testdata\library\手机视频')).Path
$existing = J '/Library/VirtualFolders'
if (-not ($existing | Where-Object { $_.Name -eq 'TestVideos' })) {
  $opts = @{ LibraryOptions = @{
      PathInfos = @(@{ Path = $libPath }); EnableRealtimeMonitor = $false; SaveLocalMetadata = $false
      EnableTrickplayImageExtraction = $false; ExtractTrickplayImagesDuringLibraryScan = $false
      ExtractChapterImagesDuringLibraryScan = $false } }
  $q = "/Library/VirtualFolders?name=TestVideos&collectionType=homevideos&refreshLibrary=true&paths=" + [uri]::EscapeDataString($libPath)
  J $q 'Post' $opts | Out-Null
  Write-Host "已创建库 TestVideos -> $libPath"
}

# 等扫描结束
$t0 = Get-Date
do {
  Start-Sleep -Seconds 2
  $items = (J '/Items?Recursive=true&IncludeItemTypes=Video&Fields=Path&EnableImageTypes=Primary&ImageTypeLimit=1').Items
  $withImg = @($items | Where-Object { $_.ImageTags.Primary }).Count
  Write-Host ("{0,3:N0}s  items={1}  withPrimaryImage={2}" -f ((Get-Date) - $t0).TotalSeconds, $items.Count, $withImg)
} while (((Get-Date) - $t0).TotalSeconds -lt 60 -and ($items.Count -lt 6 -or $withImg -lt $items.Count))

"--- 每个视频的图片 ---"
foreach ($it in $items) {
  $tag = $it.ImageTags.Primary
  if ($tag) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-WebRequest "$B/Items/$($it.Id)/Images/Primary?fillWidth=640&quality=75&format=Webp" -Headers $H -UseBasicParsing
    "{0,-34} tag={1}  {2} {3,6:N1} KB  {4} ms" -f $it.Name, $tag.Substring(0, 8), $r.Headers['Content-Type'], ($r.RawContentLength / 1KB), $sw.ElapsedMilliseconds
  } else { "{0,-34} (无 Primary 图)" -f $it.Name }
}

"--- Jellyfin 把图片存在哪里 ---"
foreach ($d in 'data\metadata', 'cache\images', 'data\data') {
  $p = Join-Path $Root "runtime\jellyfin\$d"
  if (Test-Path $p) {
    $f = Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match 'jpg|webp|png' }
    "{0,-18} 图片文件 {1} 个, {2:N1} KB" -f $d, @($f).Count, (($f | Measure-Object Length -Sum).Sum / 1KB)
  }
}
"--- 媒体目录里有没有被写入文件? ---"
Get-ChildItem $libPath -Recurse -Force -File | Where-Object { $_.Extension -notmatch 'mp4|mkv' } | Select-Object -ExpandProperty Name
