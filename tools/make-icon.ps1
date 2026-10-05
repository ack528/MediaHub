# 生成应用图标源图:蓝色渐变圆角方块 + 相框(山与太阳)+ 服务器底座。与 desktop\app-icon.svg 完全一致。
# 输出 desktop\app-icon.png(1024)和 desktop\public\logo.png(桌面端侧栏用,256)。
# 之后用 `npx tauri icon app-icon.png` 生成桌面端全部尺寸;安卓端用同样形状的矢量图标(见 res\drawable)。
param([string]$Out = (Join-Path (Split-Path -Parent $PSScriptRoot) 'desktop\app-icon.png'))
Add-Type -AssemblyName System.Drawing
$S = 1024
$bmp = New-Object System.Drawing.Bitmap $S, $S
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.Clear([System.Drawing.Color]::Transparent)

function RoundRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = 2 * $r
  $p.AddArc($x, $y, $d, $d, 180, 90)
  $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
  $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
  $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
  $p.CloseFigure()
  return $p
}
function C([string]$hex, [int]$a = 255) { $c = [System.Drawing.ColorTranslator]::FromHtml($hex); return [System.Drawing.Color]::FromArgb($a, $c.R, $c.G, $c.B) }

# 背景:渐变圆角方块
$bg = RoundRect 64 64 896 896 210
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush((New-Object System.Drawing.PointF 64, 64), (New-Object System.Drawing.PointF 960, 960), (C '#4aa3ff'), (C '#1b6fe0'))
$g.FillPath($grad, $bg)

$white = New-Object System.Drawing.SolidBrush (C '#ffffff')
# 相框(描边 44)
$pen = New-Object System.Drawing.Pen((C '#ffffff'), 44)
$g.DrawPath($pen, (RoundRect 232 268 560 440 56))
# 太阳
$g.FillEllipse($white, 352, 360, 96, 96)
# 山
$mt = [System.Drawing.PointF[]]@(
  (New-Object System.Drawing.PointF 262, 676), (New-Object System.Drawing.PointF 452, 500),
  (New-Object System.Drawing.PointF 560, 600), (New-Object System.Drawing.PointF 640, 524),
  (New-Object System.Drawing.PointF 762, 676))
$g.FillPolygon($white, $mt)
# 服务器底座
$g.FillPath((New-Object System.Drawing.SolidBrush (C '#ffffff' 235)), (RoundRect 332 768 360 52 26))
$dot = New-Object System.Drawing.SolidBrush (C '#1b6fe0')
$g.FillEllipse($dot, 381, 783, 22, 22)
$g.FillEllipse($dot, 425, 783, 22, 22)

$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
# 侧栏小图
$small = New-Object System.Drawing.Bitmap 256, 256
$g2 = [System.Drawing.Graphics]::FromImage($small)
$g2.InterpolationMode = 'HighQualityBicubic'
$g2.SmoothingMode = 'AntiAlias'
$g2.DrawImage($bmp, 0, 0, 256, 256)
$g2.Dispose()
$logo = Join-Path (Split-Path -Parent $Out) 'public\logo.png'
$small.Save($logo, [System.Drawing.Imaging.ImageFormat]::Png)
$small.Dispose(); $bmp.Dispose()
"icon: $Out"
"logo: $logo"
