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
    /** off | blend(帧混合)| mc_fast(运动补偿·轻量)| mc(运动补偿)| mc_hq(运动补偿·高质量)| flow(光流·OpenCV DIS)| lsfg(LSFG 帧生成) */
    val frc: String = "off",
    /** 补帧倍率:0 = 自动(补到屏幕刷新率),2 ~ 8 = 固定倍数 */
    val frcMultiplier: Int = 0,
    /** LSFG:内部光流精度(0.25 ~ 1.0,越小越快) */
    val lsfgFlowScale: Float = 0.5f,
    /** LSFG:性能模式(3.1P) */
    val lsfgPerf: Boolean = true,
    /** 补帧详细日志(每 2 秒一组汇总 + 异常事件,标签 frc) */
    val trace: Boolean = false,
    /** 补帧跟不上时自动降级:mc_hq → mc → mc_fast → blend */
    val frcAdaptive: Boolean = true,
    /** off | auto(显示器支持 HDR 才启用)| on */
    val hdr: String = "off",
    val hdrPeakNits: Int = 600,
    /** 视频高度超过这个值就不做超分(1080p 以上本来就够清晰,且计算量大) */
    val upscaleMaxSrcHeight: Int = 900,
) {
    val active get() = upscale != "off" || frc != "off" || hdr != "off"
}

/**
 * 增强渲染器:ExoPlayer 解码到 SurfaceTexture,在这里用 OpenGL ES 3 处理后显示到 SurfaceView。
 *
 * 处理流程:
 *   每个源帧到达时(onFrameAvailable)  OES → RGBA16F → [mpv 着色器链:FSR / Anime4K 超分] → 帧环形缓冲(+亮度金字塔+运动估计)
 *   每个屏幕刷新时(Choreographer vsync) 取 ExoPlayer 给出的"上屏时间"附近的两帧 → [运动补偿插帧] → [SDR→HDR 逆色调映射] → 窗口表面
 *
 * 帧上屏时间:ExoPlayer 用 releaseOutputBuffer(index, releaseTimeNs) 释放解码帧,SurfaceTexture.getTimestamp() 就是这个时间
 * (System.nanoTime 时钟),而且帧会比上屏时间早到几帧 —— 所以不用额外延迟就能在两个真实帧之间插值。
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
    private lateinit var grid: GridPool
    private var me: MotionEstimator? = null
    private val chains = HashMap<String, ShaderChain?>()

    // 视频信息(由播放器回调设置)
    @Volatile private var vw = 0
    @Volatile private var vh = 0
    @Volatile private var vpar = 1f
    @Volatile private var resizeMode = "fit"

    private class Slot(val ts: Long, val img: Gl.Tex, val pyr: LumaPyramid?, var mv: Gl.Tex?, val id: Long) {
        var mvUW = 1   // 运动向量的单位(某一层亮度图的宽高)
        var mvUH = 1
        var luma: ByteArray? = null   // 光流用:1/4 分辨率亮度(8 位)
        var mvOwn = false             // mv 是自己上传的光流纹理(不是纹理池里的),释放时要直接删除
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
    @Volatile private var frcDemote = 0   // 因性能不够已降了几档
    private var ingestedWin = 0
    private var flowEngine: DisFlow? = null
    private var flowTried = false
    private var lsfg: LsfgSession? = null
    private var lsfgFail = 0
    private var pBlit = 0
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
        if (c.frc != config.frc) { frcDemote = 0; lsfgScaleCap = 1f; lsfgCoverage = -1 }
        if (c.lsfgFlowScale != config.lsfgFlowScale) lsfgScaleCap = 1f
        config = c; poke()
    }

    /** 降级后实际使用的补帧方式。 */
    private fun effFrc(cfg: EnhanceConfig): String {
        var base = cfg.frc
        if (base == "lsfg") {
            val app = Assets.app
            if (app != null) Lsfg.prepareAsync(app)
            // 还没提取完着色器 / 不可用 / 多次启动失败:先用光流(不可用再往下降)
            if (Lsfg.state != 2 || lsfgFail >= 2) base = "flow"
        }
        if (base == "flow") {
            if (!flowTried) { flowTried = true; flowEngine = DisFlow.create { id, f, w, h -> handler.post { attachFlow(id, f, w, h) } } }
            if (flowEngine == null) base = "mc_hq" // 光流库不可用(架构不支持 / 加载失败):用块匹配的最高档
        }
        val ladder = listOf("lsfg", "flow", "mc_hq", "mc", "mc_fast", "blend")
        val i = ladder.indexOf(base)
        return if (i < 0) base else ladder[min(i + frcDemote, ladder.size - 1)]
    }

    private fun isMc(m: String) = m == "mc" || m == "mc_fast" || m == "mc_hq" || m == "flow"

    /** 源帧率已经接近刷新率(例如 60fps 视频在 60Hz 屏上)时不需要插帧,也就不用做运动估计。 */
    private fun frcNeeded(): Boolean = interval <= 0L || (1e9 / interval) * 1.3 < 1e9 / period

    /** 光流结果回来了(渲染线程):上传成纹理挂到对应的帧上。该帧已经被淘汰就丢掉。 */
    private fun attachFlow(id: Long, f: FloatArray, w: Int, h: Int) {
        if (released) return
        val s = ring.firstOrNull { it.id == id } ?: return
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        // RG32F 不可过滤,必须用最近邻(着色器里用 texelFetch)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val bb = java.nio.ByteBuffer.allocateDirect(f.size * 4).order(java.nio.ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(f)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG32F, w, h, 0, GLES30.GL_RG, GLES30.GL_FLOAT, bb)
        s.mv?.let { old -> if (s.mvOwn) old.delete() else grid.release(old) }
        s.mv = Gl.Tex(t[0], 0, w, h, true)
        s.mvOwn = true
        s.mvUW = w; s.mvUH = h
        dirty = true
        if (!choreoPosted) scheduleVsync()
    }

    /** 读回 1/4 分辨率亮度图,转成 8 位(光流库的输入)。 */
    private fun readLuma(t: Gl.Tex): ByteArray {
        val buf = java.nio.ByteBuffer.allocateDirect(t.w * t.h * 16).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
        GLES30.glReadPixels(0, 0, t.w, t.h, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        val fb = buf.asFloatBuffer()
        return ByteArray(t.w * t.h) { i -> (fb.get(i * 4).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
    }
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
        val c3 = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
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
        grid = GridPool()

        pOes = Gl.program(OES_FRAG)
        pLuma = Gl.program(ChainShaders.LUMA)
        pMerge = Gl.program(ChainShaders.MERGE)
        pFinal = Gl.program(FINAL_FRAG)
        pBlit = Gl.program(BLIT_FRAG)
        me = MotionEstimator(pool)

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
        runCatching { me?.destroy() }
        runCatching { flowEngine?.release() }
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

        val eff = effFrc(cfg)
        val needMv = (eff == "mc" || eff == "mc_fast" || eff == "mc_hq") && frcNeeded()
        val needFlow = eff == "flow" && frcNeeded() && flowEngine != null
        val needPyr = (needMv || needFlow || hdrActive(cfg)) && halfOk
        val pyr = if (needPyr) me?.pyramid(src) else null
        var img = src
        if (cfg.upscale != "off" && halfOk && !degraded && h <= cfg.upscaleMaxSrcHeight) {
            val d = destRect()
            val up = chain(cfg.upscale)?.run(src, d.w, d.h)
            if (up != null) { img = up; pool.release(src) }
        }
        val slot = Slot(ts, img, pyr, null, nextId++)
        val prev = ring.lastOrNull()
        if (needMv && prev?.pyr != null && pyr != null && halfOk) {
            val m = me
            if (m != null) {
                slot.mv = m.estimate(prev.pyr, pyr, grid, eff)
                slot.mvUW = m.unitW; slot.mvUH = m.unitH
            }
        }
        if (needFlow && pyr != null) {
            // 光流:读回亮度交给 OpenCV(专用线程),结果回来后再挂到这一帧上;还没回来时这对帧用帧混合
            val l = readLuma(pyr.d2)
            slot.luma = l
            val pl = prev?.luma
            val pp = prev?.pyr
            if (pl != null && pp != null && pp.d2.w == pyr.d2.w && pp.d2.h == pyr.d2.h) flowEngine?.submit(slot.id, pl, l, pyr.d2.w, pyr.d2.h)
        }
        if (eff == "lsfg" && frcNeeded()) runLsfg(slot, cfg) else if (lsfg != null) stopLsfg()
        if (slot.mv != null && !slot.mvOwn && frames % 48L == 0L && AppLog.isDebug()) debugDumpMotion(slot.mv!!)
        if (frames % 120L == 0L && AppLog.isDebug()) probeLeft = 12
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
            } else if (overBudget >= 6 && cfg.frcAdaptive && (isMc(eff) || eff == "lsfg") && (cfg.upscale == "off" || degraded)) {
                // 超分已经停了(或没开)还是跟不上:补帧降一档(高质量 → 标准 → 轻量 → 帧混合)
                frcDemote++
                overBudget = 0
                AppLog.w("enhance", "补帧耗时 ${"%.1f".format(ingestEma)}ms 持续超过帧间隔 ${"%.1f".format(budget)}ms,自动降级为 ${effFrc(cfg)}")
            }
        }
        // LSFG 来不及:最近 24 个帧里超过一半没处理上(worker 一直忙),说明这块 GPU 跑不动这个设置 → 降一档
        // 画面"一会卡一会流畅"就是有的区间有生成帧、有的没有(生成速度偶尔跟不上)。按覆盖率调整:低于 85% 就先降光流精度(逐档 100→75→50→35→25%),
        // 到最低档还不够再降级到光流
        if (eff == "lsfg" && covTotal >= 24) {
            val cov = covOk * 100 / covTotal
            lsfgCoverage = cov
            if (cov < 85 && cfg.frcAdaptive) {
                val cur = min(cfg.lsfgFlowScale, lsfgScaleCap)
                val next = flowSteps.firstOrNull { it < cur - 0.01f }
                if (next != null) {
                    lsfgScaleCap = next
                    AppLog.w("lsfg", "LSFG 覆盖率只有 $cov%(最近 $covTotal 个区间里 $covOk 个有生成帧),光流精度从 ${(cur * 100).toInt()}% 降到 ${(next * 100).toInt()}%,重建会话")
                } else {
                    frcDemote++
                    AppLog.w("lsfg", "LSFG 覆盖率只有 $cov% 且光流精度已是最低,自动降级为 ${effFrc(cfg)}")
                }
            } else AppLog.d("lsfg", "LSFG 覆盖率 $cov%")
            covTotal = 0; covOk = 0
        }
        if (!choreoPosted) scheduleVsync()
    }

    /** 调试:读回运动场,统计向量分布(只在日志级别为"调试"时每 48 帧一次)。 */
    private fun debugDumpMotion(t: Gl.Tex) {
        val buf = java.nio.ByteBuffer.allocateDirect(t.w * t.h * 16).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
        GLES30.glReadPixels(0, 0, t.w, t.h, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        val f = buf.asFloatBuffer()
        val hist = java.util.TreeMap<Int, Int>()
        var big = 0
        for (i in 0 until t.w * t.h) {
            val vx = Math.round(f.get(i * 4)); val vy = Math.round(f.get(i * 4 + 1))
            if (vx != 0 || vy != 0) { hist.merge(vx, 1, Int::plus); big++ }
        }
        AppLog.d("enhance", "运动场 ${t.w}x${t.h} 非零块 $big;vx 分布 ${hist.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key}:${it.value}" }}")
    }

    private var probeLeft = 0

    /** 调试:读回屏幕中间一行,找白色方块的位置(用 movebox 测试片),打印 模式 / 插值系数 / 方块位置。 */
    private fun debugProbe(a: Slot, mode: Int, t: Float, d: Rect) {
        probeLeft--
        val row = java.nio.ByteBuffer.allocateDirect(d.w * 4)
        GLES30.glReadPixels(d.x, d.y + d.h / 2, d.w, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, row)
        var sum = 0L; var cnt = 0
        for (x in 0 until d.w) {
            val r = row.get(x * 4).toInt() and 255; val g = row.get(x * 4 + 1).toInt() and 255
            if (r > 200 && g > 200) { sum += x; cnt++ }
        }
        val cx = if (cnt > 20) sum.toFloat() / cnt / d.w * 480f else -1f
        AppLog.d("enhance", "探针 帧${a.id} 模式$mode t=${"%.2f".format(t)} 方块x=${"%.1f".format(cx)}(源像素)")
    }

    private fun retire(s: Slot) {
        pool.release(s.img)
        me?.release(s.pyr)
        if (s.mvOwn) s.mv?.delete() else grid.release(s.mv)
        s.gen.forEach { pool8.release(it) }
        s.gen.clear()
        s.luma = null
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
        // 所以补帧时让画面整体晚一个源帧间隔(最多 50ms)上屏 —— 视频比声音慢 ≤50ms,人感觉不到(ITU 容限是 −45ms 超前 / +125ms 滞后)
        // LSFG 的生成有延迟(几十毫秒),多留一点余量,否则一个区间的前半段还没有生成帧、后半段才有,会"一会卡一会流畅"
        val lsfgActive = lsfg != null && effFrc(cfg) == "lsfg"
        val frcDelay = frcDelayFor(cfg, lsfgActive)
        val presentAt = vsyncNs + period + (if (tsInDisplayClock == true) 0L else -interval) // 到达时间时钟下,显示"前一帧间隔"的画面
        val nowNs = presentAt - frcDelay
        // A = 上屏时间之前(含)的最后一帧;B = 之后的第一帧
        var ai = -1
        for (i in ring.indices) if (ring[i].ts <= nowNs) ai = i
        val needNext = cfg.frc != "off"
        val a = ring.getOrNull(ai)
        val b = ring.getOrNull(ai + 1)
        if (a == null) { if (ring.isNotEmpty()) scheduleVsync(); return }
        if (needNext && b == null && trace.enabled) trace.underrun++
        // 覆盖率:每当 A 换成新的一帧,说明"上一个区间"放完了,看那个区间(前一帧 → A)有没有生成帧
        if (a.id != lastCountedA) {
            lastCountedA = a.id
            if (lsfgActive && a.id > lsfgStartId + 2 && frcNeeded()) { covTotal++; if (a.gen.isNotEmpty()) covOk++ }
        }

        // 插帧系数
        var t = 0f
        var mode = 0
        var lsfgIdx = -1
        var lsfgTaken = false
        var showTex = a.img.id
        if (needNext && b != null && interval > 0 && b.ts > a.ts) {
            val dt = b.ts - a.ts
            val refreshHz = 1e9 / period
            val srcHz = 1e9 / dt
            if (dt in 4_000_000L..120_000_000L && refreshHz > srcHz * 1.3) {
                t = ((nowNs - a.ts).toDouble() / dt).toFloat().coerceIn(0f, 1f)
                mode = if (isMc(effFrc(cfg)) && b.mv != null) 2 else 1
                if (b.gen.isNotEmpty() && effFrc(cfg) == "lsfg") {
                    // LSFG:每个真实帧之间有 k 张生成帧,共 n = k + 1 个"相位"(第 0 个是真实帧本身)。
                    // 不能直接用 floor(t * n) 按时间取相位:源帧率 × n 正好等于屏幕刷新率时(24fps × 5 = 120Hz),
                    // 每个 vsync 的采样点落在相位边界附近,时钟的微小漂移 / 抖动会让相位时而重复、时而跳过,
                    // 表现就是"一会流畅一会卡"。改用相位累加器(锁相环):每个 vsync 前进 n × 源帧率 / 刷新率 个相位,
                    // 再慢慢向真实时间靠拢(±0.3 个相位的死区内不修正),整数倍时每个 vsync 恰好前进一个相位,节奏均匀。
                    lsfgTaken = true
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
                        val ix = min((t * n + 1e-3f).toInt(), n - 1)
                        showTex = if (ix == 0) a.img.id else b.gen[ix - 1].id
                    }
                    mode = 0; t = 0f
                } else if (cfg.frcMultiplier > 0) {
                    // 固定倍率:每个源帧之间只出 N 个画面(N 不超过 刷新率 / 源帧率),其余刷新重复上一个画面(也省 GPU)
                    val n = min(cfg.frcMultiplier, max(1, floor(refreshHz / srcHz + 0.1).toInt()))
                    if (n <= 1) { t = 0f; mode = 0 } else t = floor(t * n + 1e-3f) / n
                }
            }
        }
        if (!lsfgTaken) { lsfgPf = Double.NaN; trace.phaseReset() }
        if (lsfgActive && !lsfgTaken && b != null && mode == 1 && trace.enabled) trace.noGen++ // 这个区间没有生成帧,退回了帧混合
        if (lsfgTaken) lsfgIdx = 0
        val key = "${a.id}/${if (mode == 0) 0 else (t * 64).toInt()}/$mode/${b?.mv != null}/$showTex"
        if (key == lastDrawKey && !dirty) {
            if (trace.enabled) trace.skipped++
            if (ring.isNotEmpty() && (needNext || b != null)) scheduleVsync()
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
        val bi = if (mode != 0) b!!.img.id else a.img.id
        Gl.bindTex(1, bi); GLES20.glUniform1i(Gl.loc(pFinal, "uB"), 1)
        val mvTex = if (mode == 2) b!!.mv!! else null
        Gl.bindTex(2, mvTex?.id ?: a.img.id); GLES20.glUniform1i(Gl.loc(pFinal, "uMV"), 2)
        // 局部平均亮度(1/8 分辨率亮度)用来在大面积亮区压低 HDR 增益
        val la = a.pyr?.d3; val lb = if (mode != 0) b!!.pyr?.d3 else la
        Gl.bindTex(3, la?.id ?: a.img.id); GLES20.glUniform1i(Gl.loc(pFinal, "uLA"), 3)
        Gl.bindTex(4, lb?.id ?: a.img.id); GLES20.glUniform1i(Gl.loc(pFinal, "uLB"), 4)
        GLES20.glUniform1f(Gl.loc(pFinal, "uT"), t)
        GLES20.glUniform1i(Gl.loc(pFinal, "uMode"), mode)
        val mvSlot = if (mode == 2) b else null
        GLES20.glUniform2f(Gl.loc(pFinal, "uD1Size"), (mvSlot?.mvUW ?: 1).toFloat(), (mvSlot?.mvUH ?: 1).toFloat())
        GLES20.glUniform1i(Gl.loc(pFinal, "uCand"), when (effFrc(cfg)) { "mc_fast" -> 1; "mc_hq" -> 9; else -> 5 })
        GLES20.glUniform1i(Gl.loc(pFinal, "uHasArea"), if (la != null) 1 else 0)
        GLES20.glUniform1i(Gl.loc(pFinal, "uHdr"), if (hdrActive(cfg)) 1 else 0)
        GLES20.glUniform1f(Gl.loc(pFinal, "uPeak"), cfg.hdrPeakNits.toFloat())
        Gl.draw()
        if (probeLeft > 0) debugProbe(a, mode, t, d)
        val tS0 = System.nanoTime()
        EGL14.eglSwapBuffers(dpy, win)
        trace.swap(tDraw0, tS0, System.nanoTime(), period)
        shown++
        updateStats(cfg, if (lsfgIdx >= 0) 2 else mode, a)
        if (ring.isNotEmpty() && (needNext || b != null)) scheduleVsync()
    }

    private fun updateStats(cfg: EnhanceConfig, mode: Int, a: Slot) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastStatsAt < 1000) return
        val secs = if (lastStatsAt == 0L) 1.0 else (nowMs - lastStatsAt) / 1000.0
        lastStatsAt = nowMs
        val fps = shown / secs
        shown = 0
        stats = buildString {
            append("增强渲染:")
            append(
                when (cfg.upscale) { "fsr" -> "FSR"; "anime4k_s" -> "Anime4K-S"; "anime4k_m" -> "Anime4K-M"; else -> "" }.let { if (it.isNotEmpty() && degraded) "$it(已因性能停用)" else it }
            )
            if (cfg.frc != "off") {
                val e = effFrc(cfg)
                append(" 补帧(${frcLabel(e)})${if (mode == 0) "·待机" else ""}${if (e != cfg.frc) "·已降级" else ""}")
            }
            if (hdrActive(cfg)) append(" HDR(PQ,${cfg.hdrPeakNits}nit)") else if (cfg.hdr != "off") append(" HDR:显示器/表面不支持")
            append("\n输出 ${"%.0f".format(fps)}fps  源 ${a.img.w}×${a.img.h}  处理 ${"%.1f".format(ingestEma)}ms  屏幕 ${"%.0f".format(1e9 / period)}Hz")
            flowEngine?.takeIf { cfg.frc == "flow" }?.let { append("  光流 ${"%.1f".format(it.avgMs)}ms") }
        }
        val srcFps = ingestedWin / secs
        ingestedWin = 0
        // 向系统申请刷新率:补帧时保持屏幕高刷(固定倍率时申请 源帧率 × 倍率);不补帧时释放
        val srcHz = if (interval > 0) 1e9 / interval else 0.0
        val hint = when {
            cfg.frc == "off" -> 0f
            cfg.frcMultiplier > 0 && srcHz > 0 -> min(maxRefreshRate, (srcHz * cfg.frcMultiplier).toFloat())
            else -> maxRefreshRate
        }
        if (abs(hint - lastHint) > 1f) { lastHint = hint; onRateHint(hint) }
        val eNow = effFrc(cfg)
        val note = when {
            cfg.frc == "lsfg" && eNow != "lsfg" -> " · LSFG 未启用:" + lsfgWhy()
            eNow != cfg.frc -> " · 已降级"
            else -> ""
        }
        val engine = frcLabel(eNow) + if (eNow == "lsfg") {
            " ×${(lsfg?.generated ?: 0) + 1} 精度${(min(cfg.lsfgFlowScale, lsfgScaleCap) * 100).toInt()}%" + (if (lsfgCoverage >= 0) " 覆盖${lsfgCoverage}%" else "")
        } else ""
        fpsText = when {
            cfg.frc == "off" -> ""
            mode == 0 -> "补帧待机 ${"%.0f".format(srcFps)}fps · $engine$note"
            else -> "补帧 ${"%.0f".format(srcFps)} → ${"%.0f".format(fps)} fps · $engine$note"
        }
        if (cfg.frc != "off" && trace.flushDue(nowMs)) {
            val header = "配置=${cfg.frc}(实际 $eNow) 倍率=${cfg.frcMultiplier} k=${lsfg?.generated ?: 0} 精度=${(min(cfg.lsfgFlowScale, lsfgScaleCap) * 100).toInt()}% " +
                "性能模式=${cfg.lsfgPerf} 自动降级=${cfg.frcAdaptive}(已降${frcDemote}档) | 屏幕 ${"%.1f".format(1e9 / period)}Hz(间隔 ${"%.2f".format(period / 1e6)}ms,最高 ${"%.0f".format(maxRefreshRate)}Hz) | " +
                "源 ${"%.2f".format(srcFps)}fps ${a.img.w}×${a.img.h} 输出 ${"%.1f".format(fps)}fps | 热状态=${thermalStatus()} | ingest 平均 ${"%.1f".format(ingestEma)}ms"
            val extra = "覆盖=${if (lsfgCoverage >= 0) "$lsfgCoverage%" else "-"} 精度上限=${(lsfgScaleCap * 100).toInt()}% 会话=${if (lsfg != null) "有" else "无"} 失败=$lsfgFail"
            trace.flush(nowMs, header, extra, eNow == "lsfg")
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
        else -> "等待第一帧"
    }

    private fun frcLabel(m: String) = when (m) {
        "blend" -> "混合"; "mc_fast" -> "运动补偿·轻量"; "mc_hq" -> "运动补偿·高质量"; "flow" -> "光流·DIS"; "lsfg" -> "LSFG"; else -> "运动补偿"
    }

    // ---------------------------------------------------------------- LSFG

    /** 每个真实帧之间要生成几张:固定倍率 m → m-1 张;自动 → 补到这块屏的最高刷新率附近。不超过屏幕能显示的。 */
    /** 补帧时画面整体晚多少上屏(ns)。 */
    private fun frcDelayFor(cfg: EnhanceConfig, lsfgActive: Boolean): Long = when {
        cfg.frc == "off" -> 0L
        lsfgActive -> min((if (interval > 0) interval else 41_000_000L) * 3 / 2, 80_000_000L)
        else -> min(if (interval > 0) interval else 41_000_000L, 50_000_000L)
    }

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

    private fun lsfgGenerated(cfg: EnhanceConfig): Int {
        if (interval <= 0L) return 0
        val srcHz = 1e9 / interval
        val cap = max(2, floor(maxRefreshRate / srcHz + 0.15).toInt())
        val m = if (cfg.frcMultiplier > 0) min(cfg.frcMultiplier, cap) else cap
        return (m - 1).coerceIn(1, 7)
    }

    /** 停掉会话。worker 还在生成时不能释放(原生层正在用),等它完成后在 [onLsfgDone] 里释放。 */
    private fun stopLsfg() {
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
        if (lsfgBusy) { if (trace.enabled) trace.busyDrops++; return } // worker 还在读输入 AHB,这一帧没有生成帧(覆盖率里体现)
        val k = lsfgGenerated(cfg)
        if (k < 1) return
        val scale = min(cfg.lsfgFlowScale, lsfgScaleCap)
        val key = "$scale/${cfg.lsfgPerf}"
        var s = lsfg
        if (s != null && (s.w != slot.img.w || s.h != slot.img.h || s.generated != k || s.key != key)) { stopLsfg(); s = null }
        if (s == null) {
            val app = Assets.app ?: return
            val ns = LsfgSession(slot.img.w, slot.img.h, k, key)
            if (!ns.create(Lsfg.cacheDir(app), scale, cfg.lsfgPerf)) {
                lsfgLastError = LsfgNative.nativeLastError().ifEmpty { "启动失败" }
                ns.destroy()
                lsfgFail++
                AppLog.w("lsfg", "LSFG 启动失败 $lsfgFail 次(${lsfgLastError});两次失败后本次播放改用光流")
                return
            }
            lsfg = ns; s = ns; lsfgFail = 0; lsfgLastError = ""
            lsfgStartId = slot.id
            covTotal = 0; covOk = 0
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
            if (rc == -2) { lsfgFail = 99; stopLsfg(); AppLog.w("lsfg", "Vulkan 设备丢失,本次播放改用光流") }
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
                val late = (nowN - (prevSlot.ts + frcDelayFor(config, true))) / 1e6
                if (late > 0) { trace.genLateN++; trace.genLate.add(late) }
            }
        }
        sess.counter++
        lsfgLastId = id
        lsfgOk++
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

        /** 上屏:插帧(运动补偿 / 混合)→ SDR→HDR 逆色调映射(PQ,BT.2020)→ 输出。 */
        private const val FINAL_FRAG = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uMV;
uniform sampler2D uLA;
uniform sampler2D uLB;
uniform float uT;
uniform int uMode;       // 0 单帧 1 混合 2 运动补偿
uniform vec2 uD1Size;
uniform int uCand;       // 运动补偿时每个像素比较的相邻块向量个数:1 轻量 / 5 标准 / 9 高质量
uniform int uHasArea;
uniform int uHdr;
uniform float uPeak;
in vec2 vPos;
out vec4 outColor;

vec3 pq(vec3 nits) {
    vec3 y = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(0.1593017578125));
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), vec3(78.84375));
}

vec3 interp() {
    if (uMode == 0) return texture(uA, vPos).rgb;
    vec3 a0 = texture(uA, vPos).rgb;
    vec3 b0 = texture(uB, vPos).rgb;
    vec3 z = mix(a0, b0, uT);
    if (uMode == 1) return z;
    if (dot(abs(a0 - b0), vec3(1.0)) < 0.03) return z;   // 两帧几乎一样(静止区域):不用做运动补偿,省掉十次采样
    // 运动补偿:A 沿运动场前移 t,B 后移 (1-t)。物体边缘处自己那个块的向量常常不准(块里一半是背景),
    // 所以在自己与上下左右 4 个相邻块的向量里,逐像素挑"A、B 对得最齐"的那一个。
    ivec2 msz = textureSize(uMV, 0);
    ivec2 c0 = ivec2(floor(vPos * vec2(msz)));
    ivec2 offs[9] = ivec2[9](ivec2(0, 0), ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1), ivec2(1, 1), ivec2(-1, 1), ivec2(1, -1), ivec2(-1, -1));
    float dMC = 1e9; vec3 a = a0; vec3 b = b0;
    for (int k = 0; k < 9; k++) {
        if (k >= uCand) break;
        vec2 v = texelFetch(uMV, clamp(c0 + offs[k], ivec2(0), msz - 1), 0).xy / uD1Size;
        vec3 ca = texture(uA, vPos - uT * v).rgb;
        vec3 cb = texture(uB, vPos + (1.0 - uT) * v).rgb;
        float d = dot(abs(ca - cb), vec3(1.0)) + (k == 0 ? 0.0 : 0.01);
        if (d < dMC) { dMC = d; a = ca; b = cb; }
    }
    float dZ = dot(abs(a0 - b0), vec3(1.0));
    vec3 mc = mix(a, b, uT);
    vec3 hold = uT < 0.5 ? a0 : b0;
    // 补偿后的匹配比不补偿更差 → 退回混合;两种都差(遮挡 / 场景切换)→ 直接保持最近的帧,避免重影
    vec3 r = mix(mc, z, smoothstep(0.02, 0.12, dMC - dZ));
    return mix(r, hold, smoothstep(0.20, 0.45, min(dMC, dZ)));
}

void main() {
    vec3 c = interp();
    if (uHdr == 0) { outColor = vec4(c, 1.0); return; }
    // SDR → HDR:解码 gamma 得到相对亮度,对高光做扩展(阴影和中间调不动),大面积亮区少扩展(避免整屏刺眼),BT.709→BT.2020,PQ 编码
    vec3 lin = pow(max(c, 0.0), vec3(2.4));
    float L = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    float area = uHasArea == 1 ? mix(texture(uLA, vPos).r, texture(uLB, vPos).r, uT) : L;
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
