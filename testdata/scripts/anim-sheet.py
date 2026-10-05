"""从屏幕录像里找到转场开始的那一帧,把之后连续的 N 帧(按录像的原始帧,不重采样)拼成一张图,并在每格标上相对开始的毫秒数。
用法: python anim-sheet.py rec.mp4 out.png [帧数=24] [每行格数=8]
"""
import subprocess, sys, os
import numpy as np

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ff = os.path.join(root, "tools", "ffmpeg", "ffmpeg.exe")
fp = os.path.join(root, "tools", "ffmpeg", "ffprobe.exe")
src, out = sys.argv[1], sys.argv[2]
count = int(sys.argv[3]) if len(sys.argv) > 3 else 24
cols = int(sys.argv[4]) if len(sys.argv) > 4 else 8
W, H = 270, 600
ts = [float(x) for x in subprocess.run([fp, "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", src], capture_output=True, text=True).stdout.split()]
raw = subprocess.run([ff, "-v", "error", "-i", src, "-vf", "scale=%d:%d" % (W, H), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
n = min(len(raw) // (W * H * 3), len(ts))
fr = np.frombuffer(raw[: n * W * H * 3], dtype=np.uint8).reshape(n, H, W, 3)
g = fr.mean(axis=3)
step = np.abs(np.diff(g, axis=0)).mean(axis=(1, 2))
idx = np.nonzero(step > 0.3)[0]
if len(idx) == 0:
    print("没有检测到画面变化"); sys.exit()
a = max(idx[0] - 1, 0)
# 把间隔很长(静止)的帧跳过,只取转场里密集的帧
sel = [a]
for i in range(a + 1, n):
    if ts[i] - ts[sel[-1]] > 0.15: break
    sel.append(i)
    if len(sel) >= count: break
rows = (len(sel) + cols - 1) // cols
sheet = np.zeros((rows * H, cols * W, 3), dtype=np.uint8)
for k, i in enumerate(sel):
    r, c = divmod(k, cols)
    sheet[r * H:(r + 1) * H, c * W:(c + 1) * W] = fr[i]
    ms = int((ts[i] - ts[sel[0]]) * 1000)
    # 在左上角画一个小色块条表示时间(像素数 = ms/10),不依赖字体
    sheet[r * H:r * H + 6, c * W:c * W + min(ms // 5, W)] = (255, 80, 0)
import PIL.Image as I
I.fromarray(sheet).save(out)
print("帧数 %d,覆盖 %.0f ms;每格顶部橙条长度 = 时间(5ms/像素)" % (len(sel), (ts[sel[-1]] - ts[sel[0]]) * 1000))
