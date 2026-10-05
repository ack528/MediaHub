"""分析一段屏幕录像里"页面转场"的进度曲线:每帧与最终画面的差异 → 归一化成 0~1 的进度,
打印转场开始 / 结束时间、时长、进度到 10% / 50% / 90% 的时间点,以及前 3 帧的进度(起步是否突兀)。
用法: python anim-curve.py rec.mp4
"""
import subprocess, sys, os
import numpy as np

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ff = os.path.join(root, "tools", "ffmpeg", "ffmpeg.exe")
fp = os.path.join(root, "tools", "ffmpeg", "ffprobe.exe")
src = sys.argv[1]
W, H = 270, 600
ts = [float(x) for x in subprocess.run([fp, "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", src], capture_output=True, text=True).stdout.split()]
raw = subprocess.run([ff, "-v", "error", "-i", src, "-vf", "scale=%d:%d" % (W, H), "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
n = min(len(raw) // (W * H), len(ts))
fr = np.frombuffer(raw[: n * W * H], dtype=np.uint8).reshape(n, H, W).astype(np.int16)
last = fr[-1]
d = np.abs(fr - last).mean(axis=(1, 2))
if d.max() < 1.0:
    print("没有检测到画面变化"); sys.exit()
# 转场区间:第一次和最后一次"相邻帧有明显变化"的位置
step = np.abs(np.diff(fr, axis=0)).mean(axis=(1, 2))
idx = np.nonzero(step > 0.4)[0]
if len(idx) == 0:
    print("没有检测到画面变化"); sys.exit()
a, b = idx[0], idx[-1] + 1
t0 = ts[a]
d0 = d[a]
prog = 1 - d[a:b + 1] / max(d0, 1e-6)
tt = np.array(ts[a:b + 1]) - t0


def at(p):
    k = np.nonzero(prog >= p)[0]
    return (tt[k[0]] * 1000) if len(k) else float("nan")


print("转场帧数 %d  时长 %.0f ms  (%.0f fps)" % (b - a + 1, tt[-1] * 1000, (b - a) / max(tt[-1], 1e-3)))
print("进度到 10%% %.0f ms   50%% %.0f ms   90%% %.0f ms   99%% %.0f ms" % (at(0.1), at(0.5), at(0.9), at(0.99)))
print("前 4 帧进度:", [round(float(x), 2) for x in prog[:4]], " 最大单帧跳变 %.2f" % float(np.max(np.diff(prog))) if len(prog) > 1 else "")
