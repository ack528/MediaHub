"""分析屏幕录像里相邻帧的差异,判断补帧后画面运动是否均匀。
   无补帧:24fps 在 60Hz 上每帧重复 2~3 次 → 差异序列里大量接近 0,夹杂大跳变(卡顿感);
   补帧后:每个刷新周期都有新画面 → 差异均匀,几乎没有 0。
用法: python motion-analyze.py rec.mp4
"""
import subprocess, sys, os
import numpy as np

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ff = os.path.join(root, "tools", "ffmpeg", "ffmpeg.exe")
src = sys.argv[1]
W, H = 540, 1200
raw = subprocess.run([ff, "-v", "error", "-i", src, "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
n = len(raw) // (W * H)
fr = np.frombuffer(raw[: n * W * H], dtype=np.uint8).reshape(n, H, W)[:, 460:620, :].astype(np.int16)  # 只看视频区域
d = np.abs(np.diff(fr, axis=0)).mean(axis=(1, 2))
print("帧数", n)
print("相邻帧平均差异(前 40):", np.round(d[:40], 2).tolist())
z = (d < 0.05).mean()
print("几乎相同的相邻帧占比 %.0f%%   差异均值 %.2f  变异系数 %.2f" % (100 * z, d.mean(), d.std() / max(d.mean(), 1e-6)))
