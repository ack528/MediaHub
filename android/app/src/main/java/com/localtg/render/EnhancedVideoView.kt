package com.localtg.render

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.localtg.AppLog

/** assets 里的着色器文本。 */
object Assets {
    @Volatile var app: Context? = null
    fun read(path: String): String = app!!.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/**
 * 增强渲染的显示控件:一个 SurfaceView,ExoPlayer 的画面交给 [VideoRenderer] 处理后再显示。
 * 渲染器初始化失败(不支持 OpenGL ES 3 / 浮点渲染 / EGL 配置)时回调 [onFailure],界面应改回普通播放。
 */
@SuppressLint("ViewConstructor")
class EnhancedVideoView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    var onFailure: ((String) -> Unit)? = null
    private var renderer: VideoRenderer? = null
    private var attached: ExoPlayer? = null
    private var config = EnhanceConfig()
    private var resizeMode = "fit"
    private var windowChanged = false
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { pushFps() }
        override fun onVideoSizeChanged(v: VideoSize) {
            pushFps()
            val swap = v.unappliedRotationDegrees % 180 != 0
            renderer?.setVideoSize(if (swap) v.height else v.width, if (swap) v.width else v.height, v.pixelWidthHeightRatio)
        }
    }

    private fun pushFps() { renderer?.setNominalFps(attached?.videoFormat?.frameRate ?: -1f) }

    init {
        holder.addCallback(this)
    }

    /** 自动化测试用:渲染器就绪了没有 / 开始、结束内录(见 FrameRecorder)。 */
    val rendererReady: Boolean get() = renderer != null
    fun startRecording(path: String, scale: Float = 0.5f) { renderer?.startRecording(path, scale) }
    fun stopRecording() { renderer?.stopRecording() }
    fun statsText(): String = renderer?.stats.orEmpty()

    fun setEnhance(c: EnhanceConfig, resize: String) {
        config = c
        resizeMode = resize
        renderer?.setConfig(c)
        renderer?.setResizeMode(resize)
    }

    /** 绑定播放器(null = 解绑)。渲染器已就绪时立即把输出接到渲染器的输入表面。 */
    fun setPlayer(p: ExoPlayer?) {
        if (attached === p) return
        attached?.removeListener(listener)
        attached?.let { runCatching { it.clearVideoSurface() } }
        attached = p
        p?.addListener(listener)
        link()
    }

    private fun link() {
        val p = attached ?: return
        val r = renderer ?: return
        val s: Surface = r.inputSurface ?: return
        p.setVideoSurface(s)
        val v = p.videoSize
        if (v.width > 0) listener.onVideoSizeChanged(v)
        pushFps()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val r = renderer
        if (r != null) { r.onSurfaceSize(width, height); return }
        val dm = display
        val hdr = if (Build.VERSION.SDK_INT >= 26) dm?.isHdr == true else false
        val rate = dm?.refreshRate ?: 60f
        // 补帧时请求系统保持的刷新率:同分辨率下 ≤ 120Hz 里最高的 —— 120 是 24 / 30 / 60 / 120 的整数倍,上屏节奏均匀;
        // 144 不是 30 / 60 的整数倍(30fps 补到 144 是 4.8 倍),而且更费电。没有 ≤ 120 的模式才用最高的。
        val maxRate = runCatching {
            val m = dm!!.mode
            val rates = dm.supportedModes.filter { it.physicalWidth == m.physicalWidth && it.physicalHeight == m.physicalHeight }.map { it.refreshRate }
            rates.filter { it <= 120.5f }.maxOrNull() ?: rates.max()
        }.getOrDefault(rate).coerceAtLeast(rate)
        val nr = VideoRenderer(
            holder.surface, width, height, rate, hdr, config, maxRefreshRate = maxRate,
            onRateHint = { hint -> main.post { applyRateHint(hint) } },
        ) { msg ->
            AppLog.w("enhance", "增强渲染不可用:$msg")
            main.post { onFailure?.invoke(msg) }
        }
        nr.setResizeMode(resizeMode)
        if (nr.start() == null) return
        renderer = nr
        setHdrWindow(nr.hdrSurface)
        link()
    }

    /**
     * 向系统申请刷新率(省电模式 / 久不触摸时系统会自动降刷新率,补帧的节奏就乱了):
     * 窗口的 preferredRefreshRate + Surface.setFrameRate(Android 11+,Android 12+ 允许无缝切换之外的切换)+ Android 15+ 的"高帧率类别"。
     * rate = 0 表示不再需要,恢复系统自己决定。
     */
    private fun applyRateHint(rate: Float) {
        // 同分辨率下刷新率最接近目标的显示模式:preferredDisplayModeId 比 preferredRefreshRate 更"硬",系统更不容易自己降下去
        val d = display
        val cur = d?.mode
        val best = runCatching {
            // 刷新率最接近申请值的模式(申请值就是上面选好的目标刷新率)
            d!!.supportedModes.filter { it.physicalWidth == cur!!.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { kotlin.math.abs(it.refreshRate - rate) }
        }.getOrNull()
        AppLog.i(
            "enhance",
            "申请屏幕刷新率 ${if (rate > 0) "%.0f Hz".format(rate) else "(释放)"};当前模式 ${cur?.modeId}/${cur?.refreshRate}Hz,最高模式 ${best?.modeId}/${best?.refreshRate}Hz,所有模式 " +
                runCatching { d!!.supportedModes.joinToString { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" } }.getOrDefault("?"),
        )
        context.findActivity()?.window?.let { w ->
            val lp = w.attributes
            val modeId = if (rate > 0 && best != null) best.modeId else 0
            if (lp.preferredRefreshRate != rate || lp.preferredDisplayModeId != modeId) {
                lp.preferredRefreshRate = rate
                lp.preferredDisplayModeId = modeId
                w.attributes = lp
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
            else if (Build.VERSION.SDK_INT >= 30) holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 35) requestedFrameRate = if (rate > 0) REQUESTED_FRAME_RATE_CATEGORY_HIGH else REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        applyRateHint(0f)
        attached?.let { runCatching { it.clearVideoSurface() } }
        renderer?.release()
        renderer = null
        setHdrWindow(false)
    }

    /** 窗口切到 HDR 色彩模式(SDR→HDR 输出需要)。离开时恢复。 */
    private fun setHdrWindow(on: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        val w = context.findActivity()?.window ?: return
        if (on && !windowChanged) { w.colorMode = ActivityInfo.COLOR_MODE_HDR; windowChanged = true }
        else if (!on && windowChanged) { w.colorMode = ActivityInfo.COLOR_MODE_DEFAULT; windowChanged = false }
    }

    val stats: String get() = renderer?.stats.orEmpty()

    /** 补帧时画面右上角的小字("补帧 24 → 60 fps");没开补帧为空。 */
    val fpsText: String get() = renderer?.fpsText.orEmpty()

    fun release() {
        attached?.removeListener(listener)
        attached = null
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
