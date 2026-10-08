package com.localtg.probe

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.localtg.AppLog
import com.localtg.render.EnhanceConfig
import com.localtg.render.EnhancedVideoView
import com.localtg.render.Lsfg
import java.io.File

/**
 * 回放测试:内置的视频(assets/play_src.mp4)用主程序同一套代码播放 —— ExoPlayer → VideoRenderer(含 LSFG 补帧、相位上屏)→ 屏幕,
 * 依次跳到几个位置各播几秒,同时把渲染器实际上屏的每一帧录成 mp4(可变帧率,时间戳 = vsync 时间),应用日志(含补帧详细日志)一起放进输出目录。
 * extras:mode(off / lsfg / lsfg_low)、segs("起点秒:时长秒,…")、mult(倍率,0 = 自动)、out(输出子目录名)
 * 输出:/sdcard/Android/data/com.localtg.probe/files/autotest/<out>/{out.mp4, app*.log, meta.txt, DONE}
 */
class PlaybackTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var view: EnhancedVideoView
    private lateinit var player: ExoPlayer
    private lateinit var outDir: File
    private val meta = StringBuilder()
    private var recStartMs = 0L
    private var segs = listOf<Pair<Int, Int>>()
    private var segIdx = 0
    private var mode = "lsfg_low"
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        mode = intent.getStringExtra("mode") ?: "lsfg_low"
        val mult = intent.getIntExtra("mult", 0)
        segs = (intent.getStringExtra("segs") ?: "31:10,64:12").split(',').mapNotNull { s -> s.split(':').takeIf { it.size == 2 }?.let { it[0].toInt() to it[1].toInt() } }
        val outName = intent.getStringExtra("out") ?: mode
        outDir = File(getExternalFilesDir("autotest"), outName).also { it.deleteRecursively(); it.mkdirs() }
        meta.appendLine("mode=$mode mult=$mult segs=$segs")
        AppLog.i("autotest", "开始:mode=$mode mult=$mult segs=$segs 输出=$outDir")

        view = EnhancedVideoView(this)
        setContentView(view)
        view.setEnhance(EnhanceConfig(frc = mode, frcMultiplier = mult, lsfgFlowScale = 0.5f, lsfgPerf = true, trace = true, frcAdaptive = true), "fit")
        player = ExoPlayer.Builder(this).build()
        player.volume = 0f
        player.setMediaItem(MediaItem.fromUri(Uri.parse("asset:///play_src.mp4")))
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { fail("播放错误:${error.message}") }
        })
        view.setPlayer(player)
        player.prepare()
        Lsfg.prepareAsync(this)
        waitReady(SystemClock.elapsedRealtime())
    }

    // 等渲染器 + LSFG 着色器就绪(第一次运行要从 Lossless.dll 提取着色器,可能要几秒)
    private fun waitReady(t0: Long) {
        if (finished) return
        val lsfgOk = mode == "off" || Lsfg.state == 2
        if (view.rendererReady && lsfgOk) { startRun(); return }
        if (mode != "off" && Lsfg.state == 3) { fail("LSFG 不可用:${Lsfg.error}"); return }
        if (SystemClock.elapsedRealtime() - t0 > 90_000) { fail("等待就绪超时(渲染器=${view.rendererReady} lsfg=${Lsfg.state})"); return }
        main.postDelayed({ waitReady(t0) }, 300)
    }

    private fun startRun() {
        player.seekTo(segs.first().first * 1000L)
        player.playWhenReady = true
        main.postDelayed({
            view.startRecording(File(outDir, "out.mp4").path, 0.5f)
            recStartMs = SystemClock.elapsedRealtime()
            runSeg()
        }, 2500)
    }

    private fun runSeg() {
        if (finished) return
        if (segIdx >= segs.size) { finish2(); return }
        val (start, dur) = segs[segIdx]
        val rel = SystemClock.elapsedRealtime() - recStartMs
        if (segIdx > 0) player.seekTo(start * 1000L)
        meta.appendLine("seg $segIdx src=${start}s dur=${dur}s recMs=$rel")
        AppLog.i("autotest", "第 ${segIdx + 1}/${segs.size} 段:原片 ${start}s 起播 ${dur}s(录制已进行 ${rel}ms)")
        segIdx++
        main.postDelayed({ AppLog.i("autotest", "统计:${view.statsText()}"); runSeg() }, dur * 1000L)
    }

    private fun finish2() {
        if (finished) return
        finished = true
        meta.appendLine("recTotalMs=${SystemClock.elapsedRealtime() - recStartMs}")
        view.stopRecording()
        player.pause()
        Thread {
            Thread.sleep(1500) // 等 AppLog 的异步写入
            runCatching {
                File(filesDir, "logs").listFiles()?.filter { it.name.startsWith("app") && it.name.endsWith(".log") }?.forEach { it.copyTo(File(outDir, it.name), true) }
            }
            File(outDir, "meta.txt").writeText(meta.toString())
            File(outDir, "DONE").writeText("ok")
            runOnUiThread { runCatching { player.release() }; setResult(RESULT_OK); finish() }
        }.start()
    }

    private fun fail(msg: String) {
        if (finished) return
        finished = true
        AppLog.w("autotest", msg)
        meta.appendLine("FAIL $msg")
        File(outDir, "meta.txt").writeText(meta.toString())
        File(outDir, "DONE").writeText("fail: $msg")
        runCatching { player.release() }
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        if (!finished) runCatching { player.release() }
    }
}
