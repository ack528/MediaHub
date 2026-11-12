package com.localtg.render

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.Build
import android.view.Display
import com.localtg.AppLog
import kotlinx.serialization.Serializable

/** 检测结果的一行。level:0 不支持 / 1 勉强 / 2 良好 / 3 仅供参考。 */
@Serializable
data class HwLine(val title: String, val level: Int, val text: String)

@Serializable
data class HwReport(
    val time: Long,
    val gpu: String,
    val lines: List<HwLine>,
    /** 实测耗时(毫秒):键 = 项目名 */
    val bench: Map<String, Double> = emptyMap(),
    val recUpscale: String = "off", // 超分已在 5.1.1 删除,保留字段只为读出旧的检测报告
    val recFrc: String = "off",
    val note: String = "",
    val lsfgReady: Boolean = false,
)

/**
 * 补帧 / SDR→HDR 的硬件支持检测:OpenGL ES 3、浮点渲染目标(RGBA16F)、Vulkan 版本、显示器刷新率 / HDR、CPU 核数 / ABI、
 * LSFG 的前提条件,按 GPU 型号给补帧建议。(实时超分和它的实测在 5.1.1 删除。)
 * 必须在后台线程调用(会创建并占用自己的 EGL 上下文,约 1 秒)。
 */
object HardwareProbe {
    fun run(ctx: Context, progress: (String) -> Unit = {}): HwReport {
        val lines = ArrayList<HwLine>()
        val bench = LinkedHashMap<String, Double>()
        var gpuName = "未知"
        var glMajor = 0
        var halfOk = false
        var adreno7 = false

        // ---------------- 显示器 / Vulkan / CPU(不需要 GL)
        var maxHz = 60f
        var curHz = 60f
        var hdr = false
        runCatching {
            val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            curHz = d.refreshRate
            val mode = d.mode
            maxHz = d.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                .maxOfOrNull { it.refreshRate } ?: curHz
            hdr = d.isHdr
        }
        val pm = ctx.packageManager
        val vk = when {
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x403000) -> "1.3"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x402000) -> "1.2"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000) -> "1.1"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) -> "1.0"
            else -> ""
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val cores = Runtime.getRuntime().availableProcessors()
        val soc = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "").ifBlank { Build.HARDWARE }

        // ---------------- GL 能力 + 实测
        progress("检测 OpenGL ES 能力…")
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var ctxEgl = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        var glOk = false
        try {
            val ver = IntArray(2)
            check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize 失败" }
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            check(EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "没有 OpenGL ES 3 的 EGL 配置" }
            ctxEgl = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(ctxEgl != EGL14.EGL_NO_CONTEXT) { "无法创建 OpenGL ES 3 上下文" }
            surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            check(surf != EGL14.EGL_NO_SURFACE) { "无法创建离屏表面" }
            check(EGL14.eglMakeCurrent(dpy, surf, surf, ctxEgl)) { "eglMakeCurrent 失败" }
            glOk = true

            val renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
            val vendor = GLES30.glGetString(GLES30.GL_VENDOR).orEmpty()
            val glVer = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            gpuName = renderer.ifBlank { "未知" }
            glMajor = Regex("""OpenGL ES (\d+)""").find(glVer)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            halfOk = ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")
            adreno7 = Regex("""Adreno.*\b([78]\d\d)\b""", RegexOption.IGNORE_CASE).containsMatchIn(renderer)
            val maxTex = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            lines += HwLine("GPU", 3, "$renderer($vendor)\n$glVer,最大纹理 $maxTex")

            if (glMajor >= 3 && halfOk) {
                lines += HwLine("实时增强所需的 GPU 能力", 2, "OpenGL ES 3.x + 浮点渲染目标:满足")
            } else {
                lines += HwLine(
                    "实时增强所需的 GPU 能力", 0,
                    (if (glMajor < 3) "OpenGL ES 版本低于 3.0;" else "") + (if (!halfOk) "不支持浮点渲染目标(EXT_color_buffer_float);" else "") + "补帧 / SDR→HDR 都无法使用",
                )
            }
        } catch (e: Throwable) {
            AppLog.w("enhance", "硬件检测 GL 部分失败:${e.message}", e)
            lines += HwLine("OpenGL ES", 0, "检测失败:${e.message}。补帧 / SDR→HDR 无法使用")
        } finally {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                if (ctxEgl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctxEgl)
                EGL14.eglTerminate(dpy)
            }
        }

        // ---------------- 显示器
        lines += HwLine(
            "显示器刷新率", if (maxHz >= 90f) 2 else 1,
            "当前 ${"%.0f".format(curHz)} Hz,最高 ${"%.0f".format(maxHz)} Hz" + (if (hdr) ",支持 HDR" else ",不支持 HDR") + "。" +
                if (maxHz >= 90f) "高刷屏补帧收益大(24fps 视频可补到 3~5 倍)。省电模式 / 不触摸时系统可能降低刷新率,播放补帧时应用会请求保持高刷并按实际刷新率自动调整" else "60Hz 屏补帧收益有限(24 → 60 约 2.5 倍,节奏不均匀),建议用低功耗模式(×2)",
        )
        lines += HwLine(
            "CPU", 3,
            "$soc,$cores 核,$abi" + if (abi != "arm64-v8a" && abi != "x86_64") "(LSFG 原生库只含 arm64-v8a / x86_64)" else "",
        )

        // ---------------- 内置 LSFG(Lossless Scaling 帧生成)
        val androidOk = Build.VERSION.SDK_INT >= 29
        val lsfgLib = LsfgNative.load()
        val dllBundled = Lsfg.dllBundled(ctx) || Lsfg.cacheReady(ctx)
        val lsfgLevel = when {
            !Lsfg.deviceSupported -> 0
            !androidOk || vk.isEmpty() || !lsfgLib || !dllBundled -> 0
            adreno7 -> 2
            else -> 1
        }
        lines += HwLine(
            "LSFG 帧生成(内置)", lsfgLevel,
            (if (!Lsfg.deviceSupported) Lsfg.UNSUPPORTED_TEXT + "。" else "") + "原生库 ${if (lsfgLib) "已加载" else "没有加载"};Lossless.dll ${if (dllBundled) "已内置" else "安装包里没有"};" +
                "Android 10+(${if (androidOk) "满足" else "不满足"});Vulkan ${if (vk.isEmpty()) "不支持" else vk};" +
                if (adreno7) "Adreno 7xx+(官方验证过的 GPU)" else "当前 GPU 不是 Adreno 7xx+(官方只在这类 GPU 上验证过,Mali / 天玑看驱动,试试再说)",
        )
        lines += HwLine("厂商系统级补帧(MEMC / 插帧)", 3, "高通 Adreno Frame Motion Engine、联发科 MEMC 等没有给第三方应用的公开接口,本应用无法调用;如果手机系统自带“视频插帧”,可以在系统设置里为本应用单独打开")

        // ---------------- 推荐
        val recFrc = when {
            !glOk || glMajor < 3 || !halfOk || lsfgLevel == 0 -> "off"
            lsfgLevel == 2 && maxHz >= 90f -> "lsfg"
            else -> "lsfg_low"
        }
        val note = buildString {
            append("推荐:补帧 ")
            append(
                when (recFrc) {
                    "lsfg" -> "LSFG 标准(补到屏幕最高刷新率)"; "lsfg_low" -> "LSFG 低功耗(目标 60fps,光流精度最低,省电发热小)"; else -> "不支持(LSFG 前提条件不满足)"
                },
            )
            append("。60fps 视频的帧预算只有 30fps 的一半,跟不上时会自动降低光流精度。")
        }
        AppLog.i("enhance", "硬件检测完成:GPU=$gpuName 推荐 补帧=$recFrc")
        return HwReport(System.currentTimeMillis(), gpuName, lines, bench, "off", recFrc, note, lsfgLevel == 2)
    }

}
