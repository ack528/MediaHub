"""读 logcat 里渲染器的"探针"行(帧号 / 模式 / 插值系数 t / 方块位置),按 x = x0 + 6*(帧+t) 拟合,打印残差。
残差小 = 补帧后的方块位置符合匀速运动(每个源帧右移 6 源像素)。
用法: adb logcat -d -s enhance:D > log.txt ; python probe-fit.py log.txt [模式(2=运动补偿,1=混合)]
"""
import re, sys
import numpy as np

mode = int(sys.argv[2]) if len(sys.argv) > 2 else 2
rows = []
for line in open(sys.argv[1], encoding="utf-8", errors="ignore"):
    m = re.search(r"探针 帧(\d+) 模式(\d) t=([\d.]+) 方块x=([-\d.]+)", line)
    if m and int(m.group(2)) == mode and float(m.group(4)) > 0:
        rows.append((int(m.group(1)), float(m.group(3)), float(m.group(4))))
if len(rows) < 8:
    print("样本不足", len(rows)); sys.exit()
# 方块到右端折返:按"帧段"分组拟合(同一段内帧号连续、x 单调)
rows.sort()
segs, cur = [], [rows[0]]
for r in rows[1:]:
    if r[0] - cur[-1][0] <= 4 and r[2] >= cur[-1][2] - 1: cur.append(r)
    else: segs.append(cur); cur = [r]
segs.append(cur)
res = []
for sg in segs:
    if len(sg) < 5: continue
    t = np.array([r[0] + r[1] for r in sg]); x = np.array([r[2] for r in sg])
    x0 = np.mean(x - 6 * t)
    res += list(x - (x0 + 6 * t))
res = np.array(res)
print("样本 %d  残差(源像素) 均值 %.2f  RMS %.2f  最大 %.2f" % (len(res), res.mean(), np.sqrt((res ** 2).mean()), np.abs(res).max()))
