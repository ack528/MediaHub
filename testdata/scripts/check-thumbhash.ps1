# 检查视频条目是否已带 ThumbHash(封面预热是后台任务,可重复运行直到全部完成)。
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$B = 'http://127.0.0.1:8481/api/v1'
$acct = Get-Content (Join-Path $Root 'runtime\mediahub\dev-account.txt') | ConvertFrom-StringData
$login = Invoke-RestMethod -Method Post -Uri "$B/auth/login" -ContentType 'application/json' -Body (@{ username = $acct.username; password = $acct.password } | ConvertTo-Json -Compress)
$Hdr = @{ Authorization = "Bearer $($login.token)" }
$st = Invoke-RestMethod -Uri "$B/admin/status" -Headers $Hdr
$st.index | ForEach-Object { "{0} state={1} files={2}" -f $_.label, $_.state, $_.files }
$d = (Invoke-RestMethod -Uri "$B/dialogs" -Headers $Hdr).dialogs | Where-Object { $_.pathDisplay -like '*手机视频' } | Select-Object -First 1
$url = "$B/dialogs/$($d.id)/history?types=video"
foreach ($i in (Invoke-RestMethod -Uri $url -Headers $Hdr).items) {
  "{0,-30} thumbhash={1}" -f $i.name, $(if ($i.thumbhash) { "$($i.thumbhash.Length) chars" } else { '(none yet)' })
}
