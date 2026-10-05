"""跟踪屏幕录像里白色方块的横向位置,检查补帧后运动是否均匀。
方块在源视频里每帧(24fps)右移 6 像素。
   无补帧:屏幕上每个源帧只出现一个位置,位置序列呈台阶状(每个源帧一步);
   补帧后:每个屏幕刷新都有新位置,位移约为 6×(24/60)×缩放,序列接近等差。
用法: python motion-box.py rec.mp4 [mode]
"""
import subprocess, sys, os
import numpy as np

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ff = os.path.join(root, "tools", "ffmpeg", "ffmpeg.exe")
src = sys.argv[1]
W, H = 540, 1200
raw = subprocess.run([ff, "-v", "error", "-i", src, "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
n = len(raw) // (W * H)
fr = np.frombuffer(raw[: n * W * H], dtype=np.uint8).reshape(n, H, W)
xs = []
for f in fr:
    reg = f[430:640]
    m = reg > 235
    ys, xx = np.nonzero(m)
    xs.append(float(xx.mean()) if len(xx) > 300 else float("nan"))
xs = np.array(xs)
d = np.diff(xs)
d = d[np.isfinite(d)]
d = d[(d > -3) & (d < 40)]  # 去掉折返(方块回到左边)和丢失目标
print("帧数", n, "跟踪到", int(np.isfinite(xs).sum()))
print("相邻录像帧的位移(前 50):", np.round(d[:50], 1).tolist())
print("位移均值 %.2f  标准差 %.2f  位移≈0 的占比 %.0f%%" % (d.mean(), d.std(), 100 * (abs(d) < 0.8).mean()))
