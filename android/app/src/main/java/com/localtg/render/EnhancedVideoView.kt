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
        override fun onVideoSizeChanged(v: VideoSize) {
            val swap = v.unappliedRotationDegrees % 180 != 0
            renderer?.setVideoSize(if (swap) v.height else v.width, if (swap) v.width else v.height, v.pixelWidthHeightRatio)
        }
    }

    init {
        holder.addCallback(this)
    }

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
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val r = renderer
        if (r != null) { r.onSurfaceSize(width, height); return }
        val dm = display
        val hdr = if (Build.VERSION.SDK_INT >= 26) dm?.isHdr == true else false
        val rate = dm?.refreshRate ?: 60f
        val nr = VideoRenderer(holder.surface, width, height, rate, hdr, config) { msg ->
            AppLog.w("enhance", "增强渲染不可用:$msg")
            main.post { onFailure?.invoke(msg) }
        }
        nr.setResizeMode(resizeMode)
        if (nr.start() == null) return
        renderer = nr
        setHdrWindow(nr.hdrSurface)
        link()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
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
