package com.localtg.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import com.localtg.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 增强渲染的配置。 */
data class EnhanceConfig(
    /** off | fsr | anime4k_s | anime4k_m */
    val upscale: String = "off",
    /** off | lsfg(标准:补到屏幕最高刷新率,≤120Hz)| lsfg_low(低功耗:补到 60fps,光流精度从 50% 起步、自动降级始终开启) */
    val frc: String = "off",
    /** 标准模式的补帧倍率:0 = 自动(补到屏幕刷新率),2 ~ 8 = 固定倍数(低功耗模式忽略) */
    val frcMultiplier: Int = 0,
    /** LSFG:内部光流精度(0.25 ~ 1.0;低功耗模式固定从 0.5 起步,自动调整) */
    val lsfgFlowScale: Float = 0.5f,
    /** LSFG:性能模式(3.1P;低功耗模式强制开启) */
    val lsfgPerf: Boolean = true,
    /** 补帧详细日志(每 2 秒一组汇总 + 异常事件,标签 frc) */
    val trace: Boolean = false,
    /** 补帧跟不上时自动降低光流精度,到最低还不够就停用补帧 */
    val frcAdaptive: Boolean = true,
    /** off | auto(显示器支持 HDR 才启用)| on */
    val hdr: String = "off",
    val hdrPeakNits: Int = 600,
    /** 视频高度超过这个值就不做超分(1080p 以上本来就够清晰,且计算量大) */
    val upscaleMaxSrcHeight: Int = 900,
) {
    val frcOn get() = frc == "lsfg" || frc == "lsfg_low"
    val lowPower get() = frc == "lsfg_low"
    val active get() = upscale != "off" || frcOn || hdr != "off"
}

/**
 * 增强渲染器:ExoPlayer 解码到 SurfaceTexture,在这里用 OpenGL ES 3 处理后显示到 SurfaceView。
 *
 * 处理流程:
 *   每个源帧到达时(onFrameAvailable)  OES → RGBA16F → [mpv 着色器链:FSR / Anime4K 超分] → 帧环形缓冲(+HDR 用的亮度图)→ [LSFG 帧生成:异步]
 *   每个屏幕刷新时(Choreographer vsync) 按相位累加器从"真实帧 + LSFG 生成帧"里选一张 → [SDR→HDR 逆色调映射] → 窗口表面
 *
 * 补帧只有 LSFG 一种(标准 / 低功耗两档);运动补偿、帧混合、OpenCV 光流都已删除。
 *
 * 帧上屏时间:ExoPlayer 用 releaseOutputBuffer(index, releaseTimeNs) 释放解码帧,SurfaceTexture.getTimestamp() 就是这个时间
 * (System.nanoTime 时钟),而且帧会比上屏时间早到几帧 —— 所以能在两个真实帧之间上屏生成帧。
 * 如果时间戳不在这个时钟里(个别解码器),退回到"到达时间 + 延迟一帧"。
 */
class VideoRenderer(
    private val surface: Surface,
    private var surfaceW: Int,
    private var surfaceH: Int,
    private val refreshRate: Float,
    private val displayIsHdr: Boolean,
    @Volatile private var config: EnhanceConfig,
    /** 这块屏同分辨率下支持的最高刷新率(补帧时请求系统保持它,省电模式 / 不触摸降帧时不至于掉下去) */
    private val maxRefreshRate: Float = refreshRate,
    /** 渲染线程发现需要的刷新率变了(0 = 不再需要)就回调,由界面线程去向系统申请 */
    private val onRateHint: (Float) -> Unit = {},
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("enh-render").apply { start() }
    private val handler = Handler(thread.looper)

    // EGL
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var win: EGLSurface = EGL14.EGL_NO_SURFACE
    var hdrSurface = false; private set
    private var halfOk = false

    // 输入
    private var oesTex = 0
    private var st: SurfaceTexture? = null
    @Volatile var inputSurface: Surface? = null; private set
    private val stMatrix = FloatArray(16)

    // 程序
    private var pOes = 0; private var pLuma = 0; private var pMerge = 0; private var pFinal = 0
    private lateinit var pool: Gl.Pool
    private var luma: LumaBuilder? = null
    private val chains = HashMap<String, ShaderChain?>()

    // 视频信息(由播放器回调设置)
    @Volatile private var vw = 0
    @Volatile private var vh = 0
    @Volatile private var vpar = 1f
    @Volatile private var resizeMode = "fit"

    private class Slot(val ts: Long, val img: Gl.Tex, val pyr: LumaPyramid?, val id: Long) {
        val gen = ArrayList<Gl.Tex>() // LSFG:前一帧 → 这一帧 之间生成的中间帧
        val arrivalNs = System.nanoTime() // 这一帧到达渲染器的时刻(统计生成延迟用)
    }
    private val ring = ArrayList<Slot>()
    private var nextId = 1L
    private var tsInDisplayClock: Boolean? = null
    private var interval = 0L           // 估计的源帧间隔(ns)
    private var lastDrawKey = ""
    private var dirty = true
    @Volatile private var released = false
    private var choreoPosted = false

    // 统计 / 自适应
    @Volatile var stats = ""; private set
    /** 补帧时显示在画面右上角的小字:源帧率 → 输出帧率 */
    @Volatile var fpsText = ""; private set
    @Volatile private var lsfgGaveUp = false // 光流精度已经降到最低还是持续跟不上:暂时停用补帧(30 秒后自动再试)
    private var lsfgBadStreak = 0            // 光流精度已是最低时,连续"不达标"的评估窗口数
    private var lsfgWindows = 0              // 当前会话已经评估过几个覆盖率窗口(第一个窗口含会话建立的停顿,不算数)
    private var lsfgGaveUpAt = 0L
    private var presentEma = 0.0             // 当前会话 worker 生成耗时的滑动平均(ms)
    private val scaleHist = HashMap<Float, Double>() // 各个光流精度下实测的生成耗时(ms),降精度没有变快就退回去
    private var lsfgPrevScale = 0f           // 上一次降级之前的精度
    private var lsfgScaleFloor = 0f          // 不能再往下降的精度(降到它之下反而更慢)
    private var ingestedWin = 0
    private var lsfg: LsfgSession? = null
    private var lsfgFail = 0
    private var pBlit = 0
    private val lsfgPending = ArrayDeque<Slot>() // 等 worker 空出来的帧(最多 2 个,丢最旧的):排队而不是丢,否则一次丢帧会让下一帧也失去前一帧(生成帧只在连续两帧之间有效)
    private var lsfgBusy = false            // worker 正在生成(只在渲染线程读写)
    private var lsfgStopPending = false
    private val trace = FrcTrace()
    private var lsfgLastId = -1L            // 最近一个成功生成的帧的 id
    private var lsfgPf = Double.NaN         // 相位累加器(单位:相位 = 真实帧 id × n + 第几张)
    private var lsfgPfN = 0
    private var lsfgExec: java.util.concurrent.ExecutorService? = null
    private var lsfgOk = 0
    private lateinit var pool8: Gl.Pool     // 生成帧用 RGBA8 的纹理池(比 16F 省 4 倍带宽,每帧要拷 k 张)
    private val flowSteps = floatArrayOf(1f, 0.75f, 0.5f, 0.35f, 0.25f)
    @Volatile private var lsfgScaleCap = 1f // 自动调低后的光流精度上限
    private var lsfgStartId = Long.MAX_VALUE // 当前会话从哪一帧开始(前几个区间还在预热,不计覆盖率)
    private var lastCountedA = -1L

    // 节拍校准(没有生成帧、源帧率和屏幕刷新率成整数比时):按"时间戳 ≤ 上屏时间"挑帧,如果帧的时间戳正好落在上屏时间的边界附近,
    // 时钟的一点抖动就会让这一帧时而被选中、时而被下一帧顶掉,表现就是 60fps 画面"晃"(时而重复一帧、时而跳一帧)。
    // 这里测每个 vsync 上"选中的帧的时间戳 距离上屏时间 多少个刷新周期的小数部分"f,慢慢调整上屏时间,让 f 稳定在 0.5(离两侧边界最远)。
    private var pacingShift = 0L
    private var pacingCos = 0.0
    private var pacingSin = 0.0
    private var pacingN = 0
    private var lastSelA = -1L
    private var covTotal = 0                // 统计窗口:上屏的帧区间数 / 其中有生成帧的区间数
    private var covOk = 0
    @Volatile private var lsfgCoverage = -1 // 最近一个窗口的覆盖率(%),显示在右上角
    @Volatile private var lsfgLastError = ""

    private var lastHint = -1f
    private var measuredPeriod = 0L       // 实测的 vsync 间隔(省电模式 / 不触摸降帧时会变)
    private var deviant = 0
    private var lastVsync = 0L
    private var ingestEma = 0.0
    private var ingestN = 0
    private var degraded = false
    private var overBudget = 0
    private var frames = 0L
    private var shown = 0L
    private var lastStatsAt = 0L

    /** 当前屏幕刷新间隔(ns):优先用 Choreographer 实测值 —— 系统会因省电 / 长时间不触摸降低刷新率,启动时读到的值会过时。 */
    val period: Long get() = if (measuredPeriod > 0) measuredPeriod else (1_000_000_000f / refreshRate.coerceIn(30f, 240f)).toLong()

    fun start(): Surface? {
        val latch = CountDownLatch(1)
        var err: String? = null
        handler.post {
            try { init() } catch (e: Throwable) {
                AppLog.e("enhance", "增强渲染初始化失败", e); err = e.message ?: e.javaClass.simpleName
            }
            latch.countDown()
        }
        if (!latch.await(4, TimeUnit.SECONDS) || err != null) {
            release()
            onError(err ?: "初始化超时")
            return null
        }
        return inputSurface
    }

    // ---------------------------------------------------------------- 对外接口

    private fun poke() { handler.post { dirty = true; if (!released && !choreoPosted && ring.isNotEmpty()) scheduleVsync() } }
    fun setVideoSize(w: Int, h: Int, par: Float) { vw = w; vh = h; vpar = if (par > 0f) par else 1f; poke() }
    fun setResizeMode(m: String) { resizeMode = m; poke() }
    fun setConfig(c: EnhanceConfig) {
        trace.enabled = c.trace
        if (c.frc != config.frc) { lsfgGaveUp = false; lsfgBadStreak = 0; lsfgScaleCap = 1f; lsfgCoverage = -1; resetScaleTuning() }
        if (c.lsfgFlowScale != config.lsfgFlowScale) { lsfgScaleCap = 1f; resetScaleTuning() }
        config = c; poke()
    }

    private fun resetScaleTuning() { scaleHist.clear(); lsfgPrevScale = 0f; lsfgScaleFloor = 0f }

    /** 低功耗模式的目标输出帧率 / 固定的光流精度。 */
    private val lowTargetHz = 60.0
    // 1.12.1 的日志(Adreno 829,1080p 30fps):精度 25%、k=1 时 worker 要 41ms(超过 33ms 的帧间隔,覆盖率 0%),
    // 而精度 50%、k=3 只要 25ms —— 精度压到 25% 反而更慢。所以低功耗从 50% 起步,自动降级只在"降了确实更快"时才继续往下降。
    private val lowFlowScale = 0.5f

    /** 补帧的目标刷新率:低功耗 = 60Hz;标准 = 这块屏同分辨率下 ≤120Hz 里最高的。 */
    private fun targetHz(cfg: EnhanceConfig): Double = if (cfg.lowPower) lowTargetHz else maxRefreshRate.toDouble()

    /** 这次播放里 LSFG 能不能用:没提取完着色器 / 不可用 / 多次启动失败 / 性能不够已停用 都不行。 */
    private fun lsfgUsable(cfg: EnhanceConfig): Boolean {
        if (!cfg.frcOn) return false
        lsfgRetryIfDue()
        val app = Assets.app
        if (app != null) Lsfg.prepareAsync(app) // 正常情况下应用启动时已经在提取了;这里是保险
        return Lsfg.state == 2 && lsfgFail < 2 && !lsfgGaveUp
    }

    /** 源帧率已经接近目标刷新率(例如 60fps 视频在 60Hz 屏上)时不需要补帧。 */
    private fun frcNeeded(cfg: EnhanceConfig): Boolean =
        interval <= 0L || (1e9 / interval) * 1.3 < min(1e9 / period, targetHz(cfg))

    fun onSurfaceSize(w: Int, h: Int) { surfaceW = w; surfaceH = h; poke() }

    fun release() {
        if (released) return
        released = true
        handler.post {
            try { teardown() } catch (e: Throwable) { AppLog.w("enhance", "释放渲染器时出错", e) }
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- EGL / GL 初始化

    private fun init() {
        trace.enabled = config.trace
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize 失败" }
        val ext = EGL14.eglQueryString(dpy, EGL14.EGL_EXTENSIONS) ?: ""
        val wantHdr = config.hdr != "off" && (config.hdr == "on" || displayIsHdr) && ext.contains("EGL_EXT_gl_colorspace_bt2020_pq")
        var cfg: EGLConfig? = null
        if (wantHdr) cfg = chooseConfig(10, 2)
        if (cfg == null) cfg = chooseConfig(8, 8) ?: throw IllegalStateException("没有可用的 EGL 配置")
        // 上屏的 GL 上下文设成高优先级(EGL_IMG_context_priority,Adreno 支持):帧生成的 Vulkan 计算占满 GPU 时,上屏也不会被排在后面
        val c3 = if (ext.contains("EGL_IMG_context_priority"))
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, 0x3100 /* EGL_CONTEXT_PRIORITY_LEVEL_IMG */, 0x3101 /* EGL_CONTEXT_PRIORITY_HIGH_IMG */, EGL14.EGL_NONE)
        else intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, c3, 0)
        check(ctx != EGL14.EGL_NO_CONTEXT) { "无法创建 OpenGL ES 3 上下文" }
        // HDR:带 BT.2020 PQ 色彩空间的 10 位表面(SurfaceFlinger 会按显示器能力做色调映射);失败就退回普通 SDR 表面
        if (cfg === hdrConfigTried) {
            val a = intArrayOf(0x309D /* EGL_GL_COLORSPACE_KHR */, 0x3340 /* EGL_GL_COLORSPACE_BT2020_PQ_EXT */, EGL14.EGL_NONE)
            win = EGL14.eglCreateWindowSurface(dpy, cfg, surface, a, 0)
            hdrSurface = win != EGL14.EGL_NO_SURFACE
            if (!hdrSurface) AppLog.w("enhance", "HDR 表面创建失败(0x${Integer.toHexString(EGL14.eglGetError())}),改用 SDR")
        }
        if (win == EGL14.EGL_NO_SURFACE) {
            val cfg8 = if (cfg === hdrConfigTried) chooseConfig(8, 8)!! else cfg
            win = EGL14.eglCreateWindowSurface(dpy, cfg8, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (cfg8 !== cfg) { // 上下文要和表面配置兼容:重建上下文
                EGL14.eglDestroyContext(dpy, ctx)
                ctx = EGL14.eglCreateContext(dpy, cfg8, EGL14.EGL_NO_CONTEXT, c3, 0)
            }
        }
        check(win != EGL14.EGL_NO_SURFACE) { "无法创建窗口表面" }
        check(EGL14.eglMakeCurrent(dpy, win, win, ctx)) { "eglMakeCurrent 失败" }
        EGL14.eglSwapInterval(dpy, 1)

        val glExt = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        halfOk = glExt.contains("GL_EXT_color_buffer_float") || glExt.contains("GL_EXT_color_buffer_half_float")
        AppLog.i("enhance", "GL: ${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)};浮点渲染=$halfOk HDR 表面=$hdrSurface")
        pool = Gl.Pool(halfOk)
        pool8 = Gl.Pool(false)

        pOes = Gl.program(OES_FRAG)
        pLuma = Gl.program(ChainShaders.LUMA)
        pMerge = Gl.program(ChainShaders.MERGE)
        pFinal = Gl.program(FINAL_FRAG)
        pBlit = Gl.program(BLIT_FRAG)
        luma = LumaBuilder(pool)

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val s = SurfaceTexture(oesTex)
        s.setOnFrameAvailableListener({ if (!released) ingest() }, handler)
        st = s
        inputSurface = Surface(s)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(dpy, win)
        scheduleVsync()
    }

    private var hdrConfigTried: EGLConfig? = null
    private fun chooseConfig(rgb: Int, alpha: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, rgb, EGL14.EGL_GREEN_SIZE, rgb, EGL14.EGL_BLUE_SIZE, rgb, EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, 0x0040 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return null
        if (rgb == 10) hdrConfigTried = cfgs[0]
        return cfgs[0]
    }

    private fun teardown() {
        chains.clear()
        ring.forEach { retire(it) }
        ring.clear()
        runCatching { luma?.destroy() }
        lsfgExec?.let { ex -> ex.shutdown(); runCatching { ex.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) } } // 等 worker 生成完,原生层才能安全释放
        lsfgBusy = false
        stopLsfg()
        runCatching { st?.release() }
        runCatching { inputSurface?.release() }
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
        dpy = EGL14.EGL_NO_DISPLAY
    }

    // ---------------------------------------------------------------- 目标矩形

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun destRect(): Rect {
        val w = surfaceW; val h = surfaceH
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return Rect(0, 0, w, h)
        val ar = vw * vpar / vh
        return when (resizeMode) {
            "fill" -> Rect(0, 0, w, h)
            "zoom" -> { val s = max(w / (vw * vpar), h.toFloat() / vh); val dw = (vw * vpar * s).toInt(); val dh = (vh * s).toInt(); Rect((w - dw) / 2, (h - dh) / 2, dw, dh) }
            else -> { val s = min(w / (vw * vpar), h.toFloat() / vh); val dw = (vw * vpar * s).toInt(); val dh = (vh * s).toInt(); Rect((w - dw) / 2, (h - dh) / 2, max(dw, 1), max(dh, 1)) }
        }.also { if (ar <= 0f) return Rect(0, 0, w, h) }
    }

    // ---------------------------------------------------------------- 源帧到达

    private fun chain(name: String): ShaderChain? = chains.getOrPut(name) {
        val files = when (name) {
            "fsr" -> listOf("FSR.glsl")
            "anime4k_s" -> listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_S.glsl", "Anime4K_Upscale_CNN_x2_S.glsl", "Anime4K_AutoDownscalePre_x2.glsl")
            "anime4k_m" -> listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl", "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_Upscale_CNN_x2_S.glsl")
            else -> return@getOrPut null
        }
        try {
            val passes = files.flatMap { f -> MpvShaderParser.parse(Assets.read("shaders/$f")) }
            ShaderChain(name, passes, pool, pLuma, pMerge)
        } catch (e: Exception) {
            AppLog.w("enhance", "加载着色器 $name 失败:${e.message}"); null
        }
    }

    private fun ingest() {
        val s = st ?: return
        val t0 = SystemClock.elapsedRealtimeNanos()
        val tn0 = System.nanoTime()
        try {
            s.updateTexImage()
        } catch (e: Exception) {
            AppLog.w("enhance", "updateTexImage 失败", e); return
        }
        s.getTransformMatrix(stMatrix)
        var ts = s.timestamp
        val now = System.nanoTime()
        if (tsInDisplayClock == null) {
            tsInDisplayClock = abs(ts - now) < 3_000_000_000L
            AppLog.i("enhance", "帧时间戳时钟:${if (tsInDisplayClock == true) "上屏时间(可前瞻插帧)" else "解码时间戳(退回到达时间 + 延迟一帧)"}")
        }
        if (tsInDisplayClock != true) ts = now
        trace.src(ts, now)
        val w = vw; val h = vh
        if (w <= 0 || h <= 0) return
        val cfg = config
        frames++
        ingestedWin++

        val src = pool.acquire(w, h)
        Gl.target(src)
        Gl.use(pOes)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(Gl.loc(pOes, "uTex"), 0)
        GLES20.glUniformMatrix4fv(Gl.loc(pOes, "uMat"), 1, false, stMatrix, 0)
        Gl.draw()

        val lsfgOn = lsfgUsable(cfg)
        val pyr = if (hdrActive(cfg) && halfOk) luma?.build(src) else null
        var img = src
        if (cfg.upscale != "off" && halfOk && !degraded && h <= cfg.upscaleMaxSrcHeight) {
            val d = destRect()
            val up = chain(cfg.upscale)?.run(src, d.w, d.h)
            if (up != null) { img = up; pool.release(src) }
        }
        val slot = Slot(ts, img, pyr, nextId++)
        val prev = ring.lastOrNull()
        if (lsfgOn && frcNeeded(cfg)) runLsfg(slot, cfg) else if (lsfg != null) stopLsfg()
        if (prev != null) {
            val dt = ts - prev.ts
            if (dt in 4_000_000L..200_000_000L) interval = if (interval == 0L) dt else (interval * 7 + dt) / 8
        }
        ring.add(slot)
        while (ring.size > 5) retire(ring.removeAt(0))
        if (trace.enabled) trace.ingestCpu.add((System.nanoTime() - tn0) / 1e6)

        // 自适应:每 8 帧测一次处理耗时(glFinish 才能量出 GPU 时间);连续超过帧间隔的 85% 就停用超分
        if (frames % 8L == 0L) {
            GLES30.glFinish()
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            ingestEma = if (ingestN == 0) ms else ingestEma * 0.8 + ms * 0.2
            ingestN++
            val budget = (if (interval > 0) interval else 41_000_000L) / 1e6
            // 连续 6 次采样(约 2 秒)都超过帧间隔的 90% 才算跟不上,偶发的卡一下(换页、系统繁忙)不算
            overBudget = if (ms > budget * 0.9) overBudget + 1 else 0
            if (overBudget >= 6 && cfg.upscale != "off" && !degraded) {
                degraded = true
                AppLog.w("enhance", "超分耗时 ${"%.1f".format(ingestEma)}ms 持续超过帧间隔 ${"%.1f".format(budget)}ms,自动停用超分")
            } else if (overBudget >= 6 && (cfg.frcAdaptive || cfg.lowPower) && lsfgOn && (cfg.upscale == "off" || degraded)) {
                // 超分已经停了(或没开)还是跟不上:降光流精度,降到最低还不够就停用补帧
                overBudget = 0
                AppLog.w("enhance", "补帧耗时 ${"%.1f".format(ingestEma)}ms 持续超过帧间隔 ${"%.1f".format(budget)}ms")
                lowerLsfgQuality(cfg, "处理耗时超过帧间隔")
            }
        }
        // 覆盖率 = 上屏的帧区间里有生成帧的比例。画面"一会卡一会流畅"就是有的区间有生成帧、有的没有(生成速度偶尔跟不上)。
        // 低于 85% 就先降光流精度(逐档 100→75→50→35→25%),到最低档还不够就停用补帧
        if (lsfgOn && covTotal >= 24) {
            val cov = covOk * 100 / covTotal
            lsfgCoverage = cov
            // 会话刚建立后的第一个窗口含建立时的停顿(分配缓冲、建管线,约 0.5 秒),只记录不处罚
            if (lsfgWindows++ == 0) AppLog.i("lsfg", "LSFG 第一个覆盖率窗口 $cov%(含会话建立的停顿,不作为降级依据)")
            else if (cov < 85 && (cfg.frcAdaptive || cfg.lowPower)) lowerLsfgQuality(cfg, "覆盖率只有 $cov%(最近 $covTotal 个区间里 $covOk 个有生成帧)")
            else { lsfgBadStreak = 0; AppLog.d("lsfg", "LSFG 覆盖率 $cov%") }
            covTotal = 0; covOk = 0
        }
        if (!choreoPosted) scheduleVsync()
    }

    /**
     * 光流精度降一档。降之前先看上一次降级有没有让生成变快:没有(耗时没少 5% 以上)就退回上一档并且以后不再往下降;
     * 已经降到头 / 不能再降了,连续 4 次评估都不达标才暂时停用补帧(30 秒后自动再试)。
     */
    private fun lowerLsfgQuality(cfg: EnhanceConfig, why: String) {
        val cur = lsfgScale(cfg)
        val ema = presentEma
        val prev = lsfgPrevScale
        val prevMs = scaleHist[prev]
        if (prev > cur + 0.01f && prevMs != null && ema > 0 && ema >= prevMs * 0.95) {
            lsfgScaleFloor = prev; lsfgScaleCap = prev; lsfgPrevScale = 0f
            AppLog.w("lsfg", "LSFG $why;光流精度从 ${(prev * 100).toInt()}% 降到 ${(cur * 100).toInt()}% 后生成耗时 %.1fms → %.1fms 没有变快,退回 ${(prev * 100).toInt()}%,不再往下降".format(prevMs, ema))
            return
        }
        if (ema > 0) scaleHist[cur] = ema
        val next = flowSteps.firstOrNull { it < cur - 0.01f && it >= lsfgScaleFloor - 0.001f }
        if (next != null) {
            lsfgPrevScale = cur
            lsfgScaleCap = next
            AppLog.w("lsfg", "LSFG $why,光流精度从 ${(cur * 100).toInt()}% 降到 ${(next * 100).toInt()}%(当前生成耗时 %.1fms),重建会话".format(ema))
        } else if (++lsfgBadStreak >= 4) {
            lsfgGaveUp = true; lsfgGaveUpAt = SystemClock.elapsedRealtime()
            AppLog.w("lsfg", "LSFG $why,光流精度已经不能再降且连续 $lsfgBadStreak 次不达标,暂时停用补帧(30 秒后自动再试)")
        } else {
            AppLog.w("lsfg", "LSFG $why,光流精度已经不能再降(第 $lsfgBadStreak / 4 次),继续观察")
        }
    }

    /** 当前实际使用的光流精度:低功耗固定最低档,标准按设置,都受自动降级的上限约束。 */
    private fun lsfgScale(cfg: EnhanceConfig): Float = min(if (cfg.lowPower) lowFlowScale else cfg.lsfgFlowScale, lsfgScaleCap)

    private fun retire(s: Slot) {
        pool.release(s.img)
        luma?.release(s.pyr)
        s.gen.forEach { pool8.release(it) }
        s.gen.clear()
    }

    private fun hdrActive(c: EnhanceConfig) = hdrSurface && c.hdr != "off"

    // ---------------------------------------------------------------- 屏幕刷新:插帧 + HDR + 上屏

    private val frameCb = Choreographer.FrameCallback { vsync -> choreoPosted = false; if (!released) draw(vsync) }

    private fun scheduleVsync() {
        if (released) return
        choreoPosted = true
        handler.post { if (!released) Choreographer.getInstance().postFrameCallback(frameCb) }
    }

    private fun draw(vsyncNs: Long) {
        val tDraw0 = System.nanoTime()
        // 实测 vsync 间隔:省电模式 / 久不触摸系统会把刷新率降下来(例如 120 → 60 → 30Hz),要跟着调整;
        // 连续 3 次偏离当前值 25% 以上才切换,单次掉帧(间隔变成两倍)不算
        if (lastVsync != 0L) {
            val d = vsyncNs - lastVsync
            if (d in 3_000_000L..300_000_000L) trace.vsync(d, period)
            if (d in 3_000_000L..60_000_000L) {
                if (measuredPeriod == 0L) measuredPeriod = d
                else if (abs(d - measuredPeriod) > measuredPeriod / 4) {
                    if (++deviant >= 3) {
                        AppLog.i("frc", "屏幕刷新间隔变化 %.2fms(%.0fHz)→ %.2fms(%.0fHz)".format(measuredPeriod / 1e6, 1e9 / measuredPeriod, d / 1e6, 1e9 / d))
                        measuredPeriod = d; deviant = 0
                    }
                }
                else { deviant = 0; measuredPeriod = (measuredPeriod * 7 + d) / 8 }
            }
        }
        lastVsync = vsyncNs
        val cfg = config
        // 补帧要用到"下一帧",而 ExoPlayer 只会在上屏前 ~50ms 才释放帧,几乎没有前瞻余量;
        // LSFG 的生成有延迟(几十毫秒),所以补帧时让画面整体晚 1.5 个源帧间隔(最多 80ms)上屏 —— 视频比声音慢这么一点人感觉不到
        // (ITU 容限是 −45ms 超前 / +125ms 滞后);余量不够的话,一个区间的前半段还没有生成帧、后半段才有,会"一会卡一会流畅"
        val wanted = lsfgWanted(cfg)
        val lsfgActive = lsfg != null && wanted
        val frcDelay = frcDelayFor(cfg)
        val presentAt = vsyncNs + period + (if (tsInDisplayClock == true) 0L else -interval) // 到达时间时钟下,显示"前一帧间隔"的画面
        val nowNs = presentAt - frcDelay + pacingShift
        // A = 上屏时间之前(含)的最后一帧;B = 之后的第一帧
        var ai = -1
        for (i in ring.indices) if (ring[i].ts <= nowNs) ai = i
        val a = ring.getOrNull(ai)
        val b = ring.getOrNull(ai + 1)
        if (a == null) { if (ring.isNotEmpty()) scheduleVsync(); return }
        if (wanted && b == null && trace.enabled) trace.underrun++
        // 覆盖率:每当 A 换成新的一帧,说明"上一个区间"放完了,看那个区间(前一帧 → A)有没有生成帧
        if (a.id != lastCountedA) {
            lastCountedA = a.id
            if (lsfgActive && a.id > lsfgStartId + 2 && frcNeeded(cfg)) { covTotal++; if (a.gen.isNotEmpty()) covOk++ }
        }

        var generating = false
        var showTex = a.img.id
        if (wanted && b != null && interval > 0 && b.ts > a.ts) {
            val dt = b.ts - a.ts
            val refreshHz = 1e9 / period
            val srcHz = 1e9 / dt
            if (dt in 4_000_000L..120_000_000L && refreshHz > srcHz * 1.3) {
                if (b.gen.isNotEmpty()) {
                    // LSFG:每个真实帧之间有 k 张生成帧,共 n = k + 1 个"相位"(第 0 个是真实帧本身)。
                    // 不能直接用 floor(t * n) 按时间取相位:源帧率 × n 正好等于屏幕刷新率时(24fps × 5 = 120Hz),
                    // 每个 vsync 的采样点落在相位边界附近,时钟的微小漂移 / 抖动会让相位时而重复、时而跳过,
                    // 表现就是"一会流畅一会卡"。改用相位累加器(锁相环):每个 vsync 前进 n × 源帧率 / 刷新率 个相位,
                    // 再慢慢向真实时间靠拢(±0.3 个相位的死区内不修正),整数倍时每个 vsync 恰好前进一个相位,节奏均匀。
                    generating = true
                    val t = ((nowNs - a.ts).toDouble() / dt).coerceIn(0.0, 1.0)
                    val n = b.gen.size + 1
                    val step = n * srcHz / refreshHz
                    val ptrue = a.id.toDouble() * n + t * n
                    if (lsfgPf.isNaN() || lsfgPfN != n || abs(ptrue - lsfgPf) > 2.5) { lsfgPf = ptrue; lsfgPfN = n }
                    else {
                        lsfgPf += step
                        val err = ptrue - lsfgPf
                        if (abs(err) > 0.3) lsfgPf += (err - 0.3 * Math.signum(err)) * 0.1
                    }
                    val ph = floor(lsfgPf).toLong()
                    trace.phase(ph)
                    val tex = phaseTex(ph, n)
                    if (tex != null) {
                        showTex = tex
                    } else { // 相位对应的帧 / 生成帧不全:退回按时间取
                        if (trace.enabled) trace.phMissing++
                        val ix = min((t * n + 1e-3).toInt(), n - 1)
                        showTex = if (ix == 0) a.img.id else b.gen[ix - 1].id
                    }
                } else if (lsfgActive && trace.enabled) trace.noGen++ // 这个区间没有生成帧,保持显示 A
            }
        }
        if (!generating) { lsfgPf = Double.NaN; trace.phaseReset() }
        // 选帧统计(详细日志用):相邻两次 vsync 选中的源帧 id 之差 —— 1 = 前进一帧,0 = 重复,>1 = 跳过
        if (trace.enabled && lastSelA >= 0) trace.sel(a.id - lastSelA)
        lastSelA = a.id
        updatePacing(a, nowNs, generating)
        val key = "${a.id}/$showTex"
        if (key == lastDrawKey && !dirty) {
            if (trace.enabled) trace.skipped++
            if (ring.isNotEmpty() && (wanted || b != null)) scheduleVsync()
            return
        }
        lastDrawKey = key
        dirty = false

        val d = destRect()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(d.x, d.y, d.w, d.h)
        Gl.use(pFinal)
        Gl.bindTex(0, showTex); GLES20.glUniform1i(Gl.loc(pFinal, "uA"), 0)
        // 局部平均亮度(1/8 分辨率亮度)用来在大面积亮区压低 HDR 增益
        val la = a.pyr?.d3
        Gl.bindTex(1, la?.id ?: showTex); GLES20.glUniform1i(Gl.loc(pFinal, "uLA"), 1)
        GLES20.glUniform1i(Gl.loc(pFinal, "uHasArea"), if (la != null) 1 else 0)
        GLES20.glUniform1i(Gl.loc(pFinal, "uHdr"), if (hdrActive(cfg)) 1 else 0)
        GLES20.glUniform1f(Gl.loc(pFinal, "uPeak"), cfg.hdrPeakNits.toFloat())
        Gl.draw()
        val tS0 = System.nanoTime()
        EGL14.eglSwapBuffers(dpy, win)
        trace.swap(tDraw0, tS0, System.nanoTime(), period)
        shown++
        updateStats(cfg, generating, a)
        if (ring.isNotEmpty() && (wanted || b != null)) scheduleVsync()
    }

    /** 节拍校准:见 [pacingShift] 的说明。只在没有生成帧、而且 刷新周期 / 源帧间隔 接近整数时工作,否则复位。 */
    private fun updatePacing(a: Slot, nowNs: Long, generating: Boolean) {
        val r = if (interval > 0) interval.toDouble() / period else 0.0
        val n = Math.round(r).toInt()
        if (generating || n < 1 || abs(r - n) > 0.04 * n) {
            if (pacingShift != 0L || pacingN != 0) { pacingShift = 0; pacingCos = 0.0; pacingSin = 0.0; pacingN = 0 }
            return
        }
        val e = nowNs - a.ts
        val f = (((e % period) + period) % period).toDouble() / period
        pacingCos = pacingCos * 0.9 + Math.cos(2 * Math.PI * f) * 0.1
        pacingSin = pacingSin * 0.9 + Math.sin(2 * Math.PI * f) * 0.1
        if (++pacingN < 20) return // 先攒一会儿样本
        var fm = Math.atan2(pacingSin, pacingCos) / (2 * Math.PI)
        if (fm < 0) fm += 1.0
        var err = 0.5 - fm
        if (err > 0.5) err -= 1.0
        if (err < -0.5) err += 1.0
        if (abs(err) > 0.08) pacingShift = (pacingShift + (0.03 * err * period).toLong()).coerceIn(-period, period)
    }

    private fun updateStats(cfg: EnhanceConfig, generating: Boolean, a: Slot) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastStatsAt < 1000) return
        val secs = if (lastStatsAt == 0L) 1.0 else (nowMs - lastStatsAt) / 1000.0
        lastStatsAt = nowMs
        val fps = shown / secs
        shown = 0
        val wanted = lsfgWanted(cfg)
        val engine = if (cfg.lowPower) "LSFG·低功耗" else "LSFG"
        stats = buildString {
            append("增强渲染:")
            append(
                when (cfg.upscale) { "fsr" -> "FSR"; "anime4k_s" -> "Anime4K-S"; "anime4k_m" -> "Anime4K-M"; else -> "" }.let { if (it.isNotEmpty() && degraded) "$it(已因性能停用)" else it }
            )
            if (cfg.frcOn) append(" 补帧($engine)${if (!generating) "·待机" else ""}${if (lsfgGaveUp) "·已因性能停用" else ""}")
            if (hdrActive(cfg)) append(" HDR(PQ,${cfg.hdrPeakNits}nit)") else if (cfg.hdr != "off") append(" HDR:显示器/表面不支持")
            append("\n输出 ${"%.0f".format(fps)}fps  源 ${a.img.w}×${a.img.h}  处理 ${"%.1f".format(ingestEma)}ms  屏幕 ${"%.0f".format(1e9 / period)}Hz")
        }
        val srcFps = ingestedWin / secs
        ingestedWin = 0
        // 向系统申请刷新率:补帧时固定申请目标刷新率(标准 = 这块屏 ≤120Hz 里最高的,低功耗 = 60Hz);不补帧时释放。
        // 固定值而不是跟着源帧率算:源帧率估计值一抖,申请值就在 144 / 142 之间改来改去,每次改系统都会重新选刷新率
        val hint = if (!cfg.frcOn) 0f else targetHz(cfg).toFloat()
        if (abs(hint - lastHint) > 1f) { lastHint = hint; onRateHint(hint) }
        val note = when {
            cfg.frcOn && !wanted -> " · LSFG 未启用:" + lsfgWhy()
            cfg.frcOn && Lsfg.state != 2 -> " · 正在提取着色器"
            else -> ""
        }
        val detail = if (lsfg != null) {
            " ×${(lsfg?.generated ?: 0) + 1} 精度${(lsfgScale(cfg) * 100).toInt()}%" + (if (lsfgCoverage >= 0) " 覆盖${lsfgCoverage}%" else "")
        } else ""
        fpsText = when {
            !cfg.frcOn -> ""
            !generating -> {
                // 待机的原因:源帧率已经够(不需要补)/ 会话还没建立 / 这一帧区间还没有生成帧
                val why = when {
                    !wanted -> ""
                    !frcNeeded(cfg) -> " · 源帧率已够,无需补帧(目标 ${"%.0f".format(targetHz(cfg))}Hz)"
                    lsfg == null -> " · 等待 LSFG 会话(${lsfgWhy()})"
                    else -> " · 等生成帧"
                }
                "补帧待机 ${"%.0f".format(srcFps)}fps · $engine$detail$note$why"
            }
            else -> "补帧 ${"%.0f".format(srcFps)} → ${"%.0f".format(fps)} fps · $engine$detail$note"
        }
        if (cfg.frcOn && trace.flushDue(nowMs)) {
            val header = "配置=${cfg.frc} 倍率=${cfg.frcMultiplier} k=${lsfg?.generated ?: 0} 精度=${(lsfgScale(cfg) * 100).toInt()}% " +
                "性能模式=${cfg.lsfgPerf || cfg.lowPower} 自动降级=${cfg.frcAdaptive}(已停用=$lsfgGaveUp) | 屏幕 ${"%.1f".format(1e9 / period)}Hz(间隔 ${"%.2f".format(period / 1e6)}ms,目标 ${"%.0f".format(targetHz(cfg))}Hz) | " +
                "源 ${"%.2f".format(srcFps)}fps ${a.img.w}×${a.img.h} 输出 ${"%.1f".format(fps)}fps | 热状态=${thermalStatus()} | ingest 平均 ${"%.1f".format(ingestEma)}ms"
            val pf = run { var x = Math.atan2(pacingSin, pacingCos) / (2 * Math.PI); if (x < 0) x += 1.0; x }
            val extra = "节拍 f=${"%.2f".format(pf)} 校准=${"%.1f".format(pacingShift / 1e6)}ms | 覆盖=${if (lsfgCoverage >= 0) "$lsfgCoverage%" else "-"} 精度上限=${(lsfgScaleCap * 100).toInt()}% 会话=${if (lsfg != null) "有" else "无"} 失败=$lsfgFail"
            trace.flush(nowMs, header, extra, true)
        }
    }

    /** 系统热状态:0 无 / 1 轻微 / 2 中等 / 3 严重 / 4 危急 / 5 紧急 / 6 关机(Android 10+)。 */
    private fun thermalStatus(): String {
        if (android.os.Build.VERSION.SDK_INT < 29) return "?"
        return runCatching { Assets.app?.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus?.toString() }.getOrNull() ?: "?"
    }

    /** LSFG 没启用的原因(显示在右上角小字里,方便在真机上判断为什么回退)。 */
    private fun lsfgWhy(): String = when {
        Lsfg.state == 1 -> "正在提取着色器"
        Lsfg.state == 3 -> Lsfg.error.take(40)
        lsfgFail >= 2 -> lsfgLastError.take(40).ifEmpty { "启动失败" }
        lsfgGaveUp -> "性能跟不上,已暂停(稍后自动重试)"
        else -> "等待第一帧"
    }

    // ---------------------------------------------------------------- LSFG

    /** 补帧开着、而且(暂时)还有希望用上 LSFG:着色器还在提取也算(上屏延迟先留出来,提取完不会跳一下)。 */
    private fun lsfgWanted(cfg: EnhanceConfig): Boolean { lsfgRetryIfDue(); return cfg.frcOn && Lsfg.state != 3 && lsfgFail < 2 && !lsfgGaveUp }

    /** 因性能停用补帧 30 秒后自动再试一次(跟不上多半是一时的:刚开始播放 / 系统繁忙 / 发热降频后恢复)。 */
    private fun lsfgRetryIfDue() {
        if (lsfgGaveUp && SystemClock.elapsedRealtime() - lsfgGaveUpAt > 30_000) {
            lsfgGaveUp = false; lsfgBadStreak = 0; lsfgScaleCap = 1f; lsfgCoverage = -1
            AppLog.i("lsfg", "停用补帧已满 30 秒,重新尝试")
        }
    }

    /** 补帧时画面整体晚多少上屏(ns);源帧率已经够高(不需要补帧)时不延迟。 */
    private fun frcDelayFor(cfg: EnhanceConfig): Long =
        if (!lsfgWanted(cfg) || !frcNeeded(cfg)) 0L else min((if (interval > 0) interval else 41_000_000L) * 3 / 2, 80_000_000L)

    /** 绝对相位 ph(= 真实帧 id × n + 第几张)对应的纹理:第 0 张是真实帧,其余是下一帧上挂着的生成帧;帧或生成帧不全返回 null。 */
    private fun phaseTex(ph: Long, n: Int): Int? {
        val fa = Math.floorDiv(ph, n.toLong())
        val idx = (ph - fa * n).toInt()
        val sa = ring.firstOrNull { it.id == fa } ?: return null
        if (idx == 0) return sa.img.id
        val sb = ring.firstOrNull { it.id == fa + 1 } ?: return null
        if (sb.gen.size != n - 1) return null
        return sb.gen[idx - 1].id
    }

    private var lsfgK = 0
    private var lsfgKKey = ""
    private var lsfgKSrc = 0.0

    /** 把实测的源帧率归到常见的标准值(23.976 / 24 / 25 / 29.97 / 30 / 50 / 59.94 / 60…),差在 3% 以内算同一档。 */
    private fun stdRate(hz: Double): Double =
        doubleArrayOf(23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0, 90.0, 120.0).minByOrNull { abs(it - hz) }?.takeIf { abs(it - hz) / it < 0.03 } ?: hz

    /**
     * 每个真实帧之间生成几张。**定下来就不再变**:以前每次都用瞬时帧间隔 + 屏幕最高刷新率重新算(144 / 28.8 = 5.0,144 / 29 = 4.97),
     * k 在 3 和 4 之间来回跳,每跳一次就重建 LSFG 会话(分配缓冲、建 Vulkan 管线,渲染线程卡 0.4 ~ 0.5 秒)—— 日志里就是每秒一次的大卡顿。
     * 现在:标准模式 固定倍率 m → k = m - 1,自动 → 补到目标刷新率(这块屏 ≤120Hz 里最高的)附近;低功耗模式 → 补到 60fps 附近(倍率设置忽略);
     * 按标准化后的源帧率算一次;只有模式 / 倍率设置变了、或源帧率变化超过 30%(换视频 / 变速)才重算。
     */
    private fun lsfgGenerated(cfg: EnhanceConfig): Int {
        if (interval <= 0L) return 0
        val src = stdRate(1e9 / interval)
        val key = "${cfg.frc}/${if (cfg.lowPower) 0 else cfg.frcMultiplier}"
        if (lsfgK > 0 && lsfgKKey == key && abs(src - lsfgKSrc) / lsfgKSrc < 0.3) return lsfgK
        // 倍率不超过 目标刷新率 / 源帧率(30fps 在 120Hz 上 ×5 = 150 > 120,多生成的相位上屏时只能跳过,白白多花 GPU 时间 ——
        // 日志里 k=4 时生成耗时 29.5ms 贴着 33ms 的帧间隔,k=3 只要 23ms)。低功耗:24fps → ×2 = 48,30fps → ×2 = 60,25fps → ×2 = 50。
        val cap = max(2, floor(targetHz(cfg) / src + 0.15).toInt())
        val m = if (!cfg.lowPower && cfg.frcMultiplier > 0) min(cfg.frcMultiplier, cap) else cap
        lsfgK = (m - 1).coerceIn(1, 7)
        lsfgKKey = key
        lsfgKSrc = src
        AppLog.i("frc", "LSFG 每帧生成数定为 $lsfgK(${if (cfg.lowPower) "低功耗,目标 60fps" else "倍率 ${if (cfg.frcMultiplier > 0) "${cfg.frcMultiplier}" else "自动"}"},源帧率按 ${"%.3f".format(src)}fps 计)")
        return lsfgK
    }

    /** 停掉会话。worker 还在生成时不能释放(原生层正在用),等它完成后在 [onLsfgDone] 里释放。 */
    private fun stopLsfg() {
        lsfgPending.clear()
        if (lsfgBusy) { lsfgStopPending = true; return }
        lsfg?.destroy()
        lsfg = null
        lsfgLastId = -1L
    }

    /**
     * 把这一帧交给 LSFG。生成(presentContext + waitIdle,几毫秒到几十毫秒)放在专用线程,渲染线程不等:
     *   渲染线程:这一帧渲染进输入 AHB(glFinish)→ 提交给 worker;
     *   worker:presentContext + waitIdle → 回到渲染线程 [onLsfgDone]:把生成的 k 张中间帧拷进纹理池,挂在这一帧上。
     * worker 忙的时候来的帧直接放弃(输入 AHB 还被它读着,不能覆盖),这些帧没有生成帧,上屏时退回帧混合;
     * 丢帧太多(来不及)会自动降级。生成帧是"前一个被处理的帧 → 这一帧"之间的,只有两帧连续(中间没丢帧)时才有效。
     */
    private fun runLsfg(slot: Slot, cfg: EnhanceConfig) {
        if (lsfgBusy) {
            // worker 还在读输入 AHB,这一帧不能现在写进去:排队,worker 做完马上接着做(帧纹理还在帧环里;队列满了才放弃,放弃会让下一帧失去前一帧)
            // 排队只留最新的 2 个:来不及的时候丢最旧的(它的生成结果出来也太晚了,上屏早就过去了),而不是丢新来的让队列里全是过期帧
            if (lsfgPending.size >= 2) { lsfgPending.removeFirst(); if (trace.enabled) trace.busyDrops++ }
            lsfgPending.addLast(slot)
            return
        }
        submitLsfg(slot, cfg)
    }

    /** 真正提交一帧(worker 空闲时才会调用)。 */
    private fun submitLsfg(slot: Slot, cfg: EnhanceConfig) {
        val k = lsfgGenerated(cfg)
        if (k < 1) return
        val scale = lsfgScale(cfg)
        val perf = cfg.lsfgPerf || cfg.lowPower
        val key = "$scale/$perf"
        var s = lsfg
        if (s != null && (s.w != slot.img.w || s.h != slot.img.h || s.generated != k || s.key != key)) { stopLsfg(); s = null }
        if (s == null) {
            val app = Assets.app ?: return
            val ns = LsfgSession(slot.img.w, slot.img.h, k, key)
            if (!ns.create(Lsfg.cacheDir(app), scale, perf)) {
                lsfgLastError = LsfgNative.nativeLastError().ifEmpty { "启动失败" }
                ns.destroy()
                lsfgFail++
                AppLog.w("lsfg", "LSFG 启动失败 $lsfgFail 次(${lsfgLastError});两次失败后本次播放不再补帧")
                return
            }
            lsfg = ns; s = ns; lsfgFail = 0; lsfgLastError = ""
            lsfgStartId = slot.id
            covTotal = 0; covOk = 0; lsfgWindows = 0; presentEma = 0.0
            AppLog.i("lsfg", "LSFG 会话已建立 ${slot.img.w}×${slot.img.h} 每帧生成 $k 张 光流精度 ${(scale * 100).toInt()}%")
        }
        val tw0 = System.nanoTime()
        s.writeInput(pBlit, slot.img.id) // 含 glFinish:GL 写完,Vulkan 才能安全地读
        if (trace.enabled) trace.writeIn.add((System.nanoTime() - tw0) / 1e6)
        lsfgBusy = true
        val sess = s
        val id = slot.id
        val consecutive = id - 1 == lsfgLastId
        val ex = lsfgExec ?: java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-present") }.also { lsfgExec = it }
        ex.execute {
            val tp0 = System.nanoTime()
            val rc = try { LsfgNative.nativePresent() } catch (e: Throwable) { -1 }
            val pms = (System.nanoTime() - tp0) / 1e6
            handler.post { onLsfgDone(sess, id, rc, consecutive, k, pms) }
        }
    }

    /** worker 做完(渲染线程)。 */
    private fun onLsfgDone(sess: LsfgSession, id: Long, rc: Int, consecutive: Boolean, k: Int, presentMs: Double) {
        val td0 = System.nanoTime()
        lsfgBusy = false
        presentEma = if (presentEma == 0.0) presentMs else presentEma * 0.9 + presentMs * 0.1
        if (trace.enabled) {
            trace.present.add(presentMs)
            if (interval > 0 && presentMs > interval / 1e6) trace.event("LSFG 生成耗时 %.1fms,超过源帧间隔 %.1fms(帧 %d,后面的帧会因为 worker 忙被放弃)".format(presentMs, interval / 1e6, id))
            if (rc != 0) trace.presentFail++
        }
        if (released) return
        if (lsfg !== sess || lsfgStopPending) {
            lsfgStopPending = false
            if (lsfg === sess) { sess.destroy(); lsfg = null; lsfgLastId = -1L }
            return
        }
        if (rc != 0) {
            lsfgLastError = LsfgNative.nativeLastError()
            AppLog.w("lsfg", "生成失败(代码 $rc):$lsfgLastError")
            if (rc == -2) { lsfgFail = 99; stopLsfg(); AppLog.w("lsfg", "Vulkan 设备丢失,本次播放不再补帧") }
            return
        }
        val slot = ring.firstOrNull { it.id == id }
        if (slot != null && consecutive && sess.counter > 0) { // 第一帧只是预热(前一帧还不存在)
            for (i in 0 until k) {
                val t = pool8.acquire(slot.img.w, slot.img.h)
                Gl.target(t)
                Gl.use(pBlit)
                Gl.bindTex(0, sess.output(i))
                GLES20.glUniform1i(Gl.loc(pBlit, "uTex"), 0)
                Gl.draw()
                slot.gen.add(t)
            }
            dirty = true
            if (!choreoPosted) scheduleVsync()
        }
        if (trace.enabled && slot != null) {
            val nowN = System.nanoTime()
            trace.doneCost.add((nowN - td0) / 1e6)
            trace.genAge.add((nowN - slot.arrivalNs) / 1e6)
            // 这一帧的区间(前一帧 → 这一帧)什么时候开始上屏:前一帧的时间戳 + 延迟;生成帧在那之后才就绪就是"晚了"
            val prevSlot = ring.firstOrNull { it.id == id - 1 }
            if (prevSlot != null && consecutive) {
                val late = (nowN - (prevSlot.ts + frcDelayFor(config))) / 1e6
                if (late > 0) { trace.genLateN++; trace.genLate.add(late) }
            }
        }
        sess.counter++
        lsfgLastId = id
        lsfgOk++
        // 排队的帧接着做(帧被帧环淘汰了就跳过;那样链会断一次)
        while (true) {
            val next = lsfgPending.removeFirstOrNull() ?: break
            if (ring.contains(next) && lsfg === sess && !lsfgStopPending) { submitLsfg(next, config); break }
        }
    }

    companion object {
        /** 直接拷贝(RGBA16F 纹理 ↔ AHB 纹理)。 */
        private const val BLIT_FRAG = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vPos;
out vec4 outColor;
void main() { outColor = vec4(texture(uTex, vPos).rgb, 1.0); }
"""

        private const val OES_FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uMat;
in vec2 vPos;
out vec4 outColor;
void main() {
    vec2 uv = (uMat * vec4(vPos, 0.0, 1.0)).xy;
    outColor = vec4(texture(uTex, uv).rgb, 1.0);
}
"""

        /** 上屏:SDR→HDR 逆色调映射(PQ,BT.2020)→ 输出(补帧的画面已经由 LSFG 生成,这里只负责显示)。 */
        private const val FINAL_FRAG = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uA;
uniform sampler2D uLA;
uniform int uHasArea;
uniform int uHdr;
uniform float uPeak;
in vec2 vPos;
out vec4 outColor;

vec3 pq(vec3 nits) {
    vec3 y = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(0.1593017578125));
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), vec3(78.84375));
}

void main() {
    vec3 c = texture(uA, vPos).rgb;
    if (uHdr == 0) { outColor = vec4(c, 1.0); return; }
    // SDR → HDR:解码 gamma 得到相对亮度,对高光做扩展(阴影和中间调不动),大面积亮区少扩展(避免整屏刺眼),BT.709→BT.2020,PQ 编码
    vec3 lin = pow(max(c, 0.0), vec3(2.4));
    float L = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    float area = uHasArea == 1 ? texture(uLA, vPos).r : L;
    float areaLin = pow(max(area, 0.0), 2.4);
    float k = max(uPeak / 203.0 - 1.0, 0.0);
    float gain = 1.0 + k * pow(clamp(L, 0.0, 1.0), 4.0) * (1.0 - smoothstep(0.35, 0.75, areaLin));
    vec3 hdrLin = lin * gain * 203.0; // 参考白 203 nit
    const mat3 M709to2020 = mat3(0.6274, 0.0691, 0.0164, 0.3293, 0.9195, 0.0880, 0.0433, 0.0114, 0.8956);
    outColor = vec4(pq(M709to2020 * hdrLin), 1.0);
}
"""
    }
}
