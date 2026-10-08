package com.localtg.probe

import android.content.Context
import java.io.File

/** 一个测试项。driver 为空 = 系统 Vulkan 驱动。 */
data class TestSpec(
    val id: String,
    val title: String,
    val test: String,            // vkinfo / vkcompute / glinterop / extract / lsfg
    val driver: String = "",
    val w: Int = 0, val h: Int = 0, val flow: Float = 0f,
    val variant: Int = 32, val perf: Boolean = false, val gen: Int = 1,
    val iters: Int = 40, val frames: Int = 12,
    val timeoutSec: Int = 120,
    val asset: String = "test.mp4", val startSec: Double = 1.0, val fps: Double = 30.0, // 源帧取自哪个内置视频、从几秒起、几 fps
    val dumpW: Int = 0, val dumpH: Int = 0,
    val flags: Int = 0,                    // framegen 实验开关(见 lsfgbench / framegen 补丁)   // >0:把输出帧序列(前一帧 + 生成帧,每对)缩放成这个尺寸存成 I420 原始文件
    val paceMs: Float = 0f,       // >0:按这个间隔送帧(模拟 30fps=33.3 / 60fps=16.7 源),0=连续不限速
)

object Plan {
    // 可测的自定义驱动:应用私有目录和外部目录 drivers 文件夹里的 .so
    fun customDrivers(c: Context): List<File> {
        val dirs = listOfNotNull(File(c.filesDir, "drivers"), c.getExternalFilesDir("drivers"))
        return dirs.flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".so") }?.toList().orEmpty() }.sortedBy { it.name }
    }

    private fun lsfg(id: String, w: Int, h: Int, flow: Float, variant: Int, perf: Boolean, gen: Int = 1, iters: Int = 40, driver: String = "", tag: String = ""): TestSpec {
        val v = when (variant) { 16 -> "FP16"; 32 -> "FP32"; else -> "DXBC" }
        return TestSpec(id, "LSFG ${w}x$h 光流${(flow * 100).toInt()}% $v ${if (perf) "3.1P" else "3.1"} ×${gen + 1}$tag", "lsfg", driver, w, h, flow, variant, perf, gen, iters,
            timeoutSec = if (h >= 1080) 150 else 120)
    }

    fun build(c: Context, mode: Int): List<TestSpec> = when (mode) { TUNE -> tune(); DUMP -> dump(); REAL -> real(); STILL -> still(); GT -> gt(); GTREF -> gtRef(); else -> buildBasic(c, mode == FULL) }

    private fun dumpSpec(id: String, w: Int, h: Int, flow: Float, perf: Boolean, gen: Int, frames: Int): TestSpec {
        val s = lsfg(id, w, h, flow, 16, perf, gen, iters = frames)
        return s.copy(title = "导出补帧输出 " + s.title, frames = frames, dumpW = 640, dumpH = 360, timeoutSec = 240)
    }

    private fun realSpec(id: String, flow: Float, perf: Boolean, gen: Int, w: Int, h: Int, start: Double, variant: Int = 16, asset: String = "play_src.mp4"): TestSpec {
        val s = lsfg(id, w, h, flow, variant, perf, gen, iters = 30)
        return s.copy(title = "真实片段导出 " + s.title + " 起点${start}s", frames = 30, asset = asset, startSec = start, fps = 24.0, dumpW = 1024, dumpH = 640, timeoutSec = 300)
    }

    /** 用用户那段真实视频(play_src.mp4,2048x1280 24fps)的运动剧烈片段(66.0s 起)离线跑 LSFG,存下输出,看重影是 FP16 精度问题还是 LSFG 本身的局限。 */
    /** 用用户那段真实视频(play_src.mp4,2048x1280 24fps)的运动剧烈片段(66.0s 起)离线跑 LSFG,存下输出,看重影是 FP16 精度问题还是 LSFG 本身的局限。 */
    private fun real(): List<TestSpec> = listOf(
        TestSpec("extract", "提取 Lossless.dll 着色器", "extract", timeoutSec = 240),
        realSpec("s-k4-p50", 0.5f, true, 4, 1824, 1140, 0.5, 16, "synth.mp4"),
        realSpec("s-k4-std100", 1.0f, false, 4, 1824, 1140, 0.5, 16, "synth.mp4"),
        realSpec("s-k1-p50", 0.5f, true, 1, 1824, 1140, 0.5, 16, "synth.mp4"),
    )

    /**
     * 0.8 多点验证:有真值的平移片段(gt24.mp4 = 240fps 渲染后每 10 帧取 1 帧),电脑上用中间帧当标准答案量化补帧误差。
     * flowArg 直接传给 framegen(它要的是"光流比例的倒数":50% = 2.0;主程序之前传的是 0.5)。
     */
    private fun gtSpec(id: String, flowArg: Float, perf: Boolean = true, gen: Int = 4, variant: Int = 16, flags: Int = 0, w: Int = 1824, h: Int = 1140): TestSpec {
        val v = when (variant) { 16 -> "FP16"; 32 -> "FP32"; else -> "DXBC" }
        return TestSpec(id, "[$id] ${w}x$h flow参数=$flowArg $v ${if (perf) "3.1P" else "3.1"} ×${gen + 1} flags=$flags", "lsfg", "", w, h, flowArg, variant, perf, gen,
            iters = 24, frames = 24, asset = "gt24.mp4", startSec = 0.0, fps = 24.0, dumpW = 640, dumpH = 400, flags = flags, timeoutSec = 240)
    }

    private fun gt(): List<TestSpec> = listOf(
        TestSpec("extract", "提取 Lossless.dll 着色器", "extract", timeoutSec = 240),
        gtSpec("G01-现状0.5", 0.5f),
        gtSpec("G02-修正2.0", 2.0f),
        gtSpec("G03-100%", 1.0f),
        gtSpec("G04-全屏障", 2.0f, flags = 1),
        gtSpec("G05-串行提交", 2.0f, flags = 2),
        gtSpec("G06-线性AHB", 2.0f, flags = 4),
        gtSpec("G07-清零后备图", 2.0f, flags = 8),
        gtSpec("G08-无LOW优先级", 2.0f, flags = 16),
        gtSpec("G09-FP32", 2.0f, variant = 32),
        gtSpec("G10-标准3.1", 2.0f, perf = false),
        gtSpec("G11-x2修正", 2.0f, gen = 1),
        gtSpec("G12-x2现状", 0.5f, gen = 1),
        gtSpec("G13-1920x1088", 2.0f, w = 1920, h = 1088),
        gtSpec("G14-25%修正4.0", 4.0f),
        gtSpec("G15-全部开关", 2.0f, flags = 31),
        gtSpec("G16-现状全部开关", 0.5f, flags = 31),
    )

    /** 0.9 参照对比:同样的真值片段,输出由 Vulkan 拷到主机内存读回(flags 64),在 Mali 和模拟器(宿主 Intel GPU)上各跑一遍对比。 */
    private fun gtRef(): List<TestSpec> = listOf(
        TestSpec("extract", "提取 Lossless.dll 着色器", "extract", timeoutSec = 240),
        // 0.9.7:用户那段真实视频的剧烈运动段(66s 起),输入 / 输出走 Vulkan;U1 = 修正(去 ConstOffset),U2 = 不修正
        realRef("U1-真实修正", 192 or 1024, 1),
        realRef("U2-真实不修正", 192, 1),
        realRef("U3-真实修正-低功耗x2", 192 or 1024, 0),
    )

    private fun realRef(id: String, flags: Int, std: Int): TestSpec {
        val gen = if (std == 1) 4 else 1
        return TestSpec(id, "[$id] 1824x1140 真实片段66s ×${gen + 1} flags=$flags", "lsfg", "", 1824, 1140, 2.0f, 16, true, gen,
            iters = 24, frames = 24, asset = "play_src.mp4", startSec = 66.0, fps = 24.0, dumpW = 640, dumpH = 400, flags = flags, timeoutSec = 240)
    }

    /** 0.6:静止画面(两帧完全相同)。理想输出 = 原图,任何扭曲都不是运动估计的问题。 */
    private fun still(): List<TestSpec> = listOf(
        TestSpec("extract", "提取 Lossless.dll 着色器", "extract", timeoutSec = 240),
        realSpec("still-k4-p50", 0.5f, true, 4, 1824, 1140, 0.5, 16, "static.mp4"),
        realSpec("still-k4-std100", 1.0f, false, 4, 1824, 1140, 0.5, 16, "static.mp4"),
        realSpec("still-k1-p50", 0.5f, true, 1, 1824, 1140, 0.5, 16, "static.mp4"),
    )

    /** 导出补帧输出:对内置视频(1080p30 合成画面)做真实补帧,存帧序列并检查生成帧的时间顺序。 */
    private fun dump(): List<TestSpec> = listOf(
        TestSpec("extract", "提取 Lossless.dll 着色器", "extract", timeoutSec = 240),
        dumpSpec("d720-x2", 1280, 720, 0.5f, true, 1, 60),
        dumpSpec("d720-x4", 1280, 720, 0.5f, true, 3, 60),
        dumpSpec("d1080-x2", 1920, 1080, 0.5f, true, 1, 40),
        dumpSpec("d720-x2-31", 1280, 720, 0.5f, false, 1, 60),
    )

    const val QUICK = 0
    const val FULL = 1
    const val TUNE = 2
    const val DUMP = 3
    const val REAL = 4
    const val STILL = 5
    const val GT = 6
    const val GTREF = 7

    private fun lsfgP(id: String, w: Int, h: Int, flow: Float, perf: Boolean, gen: Int, pace: Float, iters: Int = 300): TestSpec {
        val s = lsfg(id, w, h, flow, 16, perf, gen, iters)
        return s.copy(title = s.title + " 节奏${"%.1f".format(pace)}ms", paceMs = pace, timeoutSec = 150)
    }

    /** 第 2 轮:光流精度扫描(找出 Mali 上的最优点)+ 按真实 30 / 60fps 节奏的持续测试。 */
    private fun tune(): List<TestSpec> {
        val l = mutableListOf<TestSpec>()
        l += TestSpec("extract", "提取 Lossless.dll 着色器", "extract", timeoutSec = 240)
        for (f in listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.75f, 1.0f)) l += lsfg("t1080-3.1-${(f * 100).toInt()}", 1920, 1080, f, 16, false, iters = 30)
        for (f in listOf(0.3f, 0.4f, 0.5f, 0.75f, 1.0f)) l += lsfg("t720-3.1-${(f * 100).toInt()}", 1280, 720, f, 16, false, iters = 40)
        for (f in listOf(0.35f, 0.5f, 0.75f)) l += lsfg("t1080-3.1P-${(f * 100).toInt()}", 1920, 1080, f, 16, true, iters = 30)
        for (f in listOf(0.35f, 0.5f, 0.75f)) l += lsfg("t720-3.1P-${(f * 100).toInt()}", 1280, 720, f, 16, true, iters = 40)
        l += lsfg("t1440-3.1-50", 2560, 1440, 0.5f, 16, false, iters = 20)
        // 真实节奏:30fps 源 → 60fps(预算 33.3ms);60fps 源 → 120fps(预算 16.7ms)
        l += lsfgP("p1080-3.1-50-30", 1920, 1080, 0.5f, false, 1, 33.3f)
        l += lsfgP("p1080-3.1P-50-30", 1920, 1080, 0.5f, true, 1, 33.3f)
        l += lsfgP("p720-3.1-50-60", 1280, 720, 0.5f, false, 1, 16.7f)
        l += lsfgP("p720-3.1P-50-60", 1280, 720, 0.5f, true, 1, 16.7f)
        l += lsfgP("p720-3.1P-35-60", 1280, 720, 0.35f, true, 1, 16.7f)
        l += lsfgP("p1080-3.1-50-30-x3", 1920, 1080, 0.5f, false, 2, 33.3f)
        return l
    }

    private fun buildBasic(c: Context, full: Boolean): List<TestSpec> {
        val l = mutableListOf<TestSpec>()
        // 系统驱动
        l += TestSpec("vkinfo", "Vulkan 设备信息", "vkinfo", timeoutSec = 60)
        l += TestSpec("glinterop", "GL ↔ AHB 互通", "glinterop", timeoutSec = 90)
        l += TestSpec("vkcompute", "Vulkan 计算微基准", "vkcompute", timeoutSec = 180)
        l += TestSpec("extract", "提取 Lossless.dll 着色器", "extract", timeoutSec = 240)
        // 从最小开始:先确认"能不能跑",再看"快不快"
        l += lsfg("s540-fp32", 960, 540, 0.25f, 32, false)
        l += lsfg("s540-fp16", 960, 540, 0.25f, 16, false)
        if (full) l += lsfg("s540-dxbc", 960, 540, 0.25f, 0, false)
        l += lsfg("s720-fp16-50", 1280, 720, 0.5f, 16, false)
        if (full) {
            l += lsfg("s720-fp32-50", 1280, 720, 0.5f, 32, false)
            l += lsfg("s720-fp16-25", 1280, 720, 0.25f, 16, false)
            l += lsfg("s720-fp16-25p", 1280, 720, 0.25f, 16, true)
            l += lsfg("s720-fp16-50-x4", 1280, 720, 0.5f, 16, false, gen = 3)
        }
        l += lsfg("s1080-fp16-25p", 1920, 1080, 0.25f, 16, true, iters = 30)
        if (full) {
            l += lsfg("s1080-fp16-25", 1920, 1080, 0.25f, 16, false, iters = 30)
            l += lsfg("s1080-fp16-50", 1920, 1080, 0.5f, 16, false, iters = 30)
            l += lsfg("s1080-fp32-25p", 1920, 1080, 0.25f, 32, true, iters = 30)
        }
        // 自定义驱动:每个只跑信息 + 计算 + 3 个 LSFG 配置
        for (d in customDrivers(c)) {
            val n = d.name
            l += TestSpec("c-$n-vkinfo", "[$n] Vulkan 设备信息", "vkinfo", d.path, timeoutSec = 60)
            l += TestSpec("c-$n-vkcompute", "[$n] Vulkan 计算微基准", "vkcompute", d.path, timeoutSec = 180)
            l += lsfg("c-$n-540-fp32", 960, 540, 0.25f, 32, false, driver = d.path, tag = " [$n]")
            l += lsfg("c-$n-540-fp16", 960, 540, 0.25f, 16, false, driver = d.path, tag = " [$n]")
            l += lsfg("c-$n-720-fp16", 1280, 720, 0.5f, 16, false, driver = d.path, tag = " [$n]")
        }
        return l
    }

    /** 持续测试:在已完成的结果里挑一个"能跑且最大"的配置,连续跑 300 轮看掉频 / 发热。 */
    fun sustained(from: TestSpec): TestSpec = from.copy(id = from.id + "-sustained", title = "持续 300 轮:" + from.title, iters = 300, timeoutSec = 240)
}
