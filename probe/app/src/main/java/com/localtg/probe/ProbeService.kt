package com.localtg.probe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import java.nio.ByteBuffer

/**
 * 在独立进程(:probe)里执行单个测试。客户端(界面进程)发 RUN,先收到 ACK(带 pid,用于超时后杀进程),再收到 DONE(带文本结果)。
 * 进程中途死掉(原生崩溃)时客户端通过连接断开感知到。
 */
class ProbeService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            if (m.what != RUN) return
            val reply = m.replyTo ?: return
            val args = m.data
            reply.send(Message.obtain(null, ACK).also { it.arg1 = Process.myPid() })
            Thread({
                val text = try { run(args) } catch (t: Throwable) { "异常: ${t::class.java.name}: ${t.message}\nRESULT: FAIL" }
                reply.send(Message.obtain(null, DONE).also { it.data = Bundle().apply { putString("text", text) } })
            }, "probe-test").start()
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun run(a: Bundle): String {
        val driver = a.getString("driver").orEmpty()
        return when (a.getString("test")) {
            "vkinfo" -> Native.vkInfo(driver)
            "vkcompute" -> Native.vkCompute(driver)
            "glinterop" -> Native.glInterop()
            "extract" -> extract()
            "lsfg" -> {
                val w = a.getInt("w"); val h = a.getInt("h"); val n = a.getInt("frames")
                val frames = FrameSource.ensure(this, w, h, n, a.getString("asset") ?: "test.mp4", a.getDouble("start", 1.0), a.getDouble("fps", 30.0))
                val thumb = a.getString("thumb").orEmpty()
                Native.lsfgBench(driver, Paths.cacheDir(this), frames, thumb, w, h, a.getFloat("flow"), a.getInt("variant"),
                    a.getBoolean("perf"), a.getInt("gen"), n, a.getInt("iters"), a.getFloat("pace"),
                    a.getString("dump").orEmpty(), a.getInt("dumpW"), a.getInt("dumpH"), a.getInt("flags"))
            }
            else -> "未知测试\nRESULT: FAIL"
        }
    }

    private fun extract(): String {
        val marker = File(Paths.lsfgDir(this), "extracted-v1")
        val cache = File(Paths.cacheDir(this))
        if (marker.exists() && cache.isDirectory) return "着色器缓存已存在,跳过提取\nRESULT: OK"
        val dll = File(Paths.lsfgDir(this), "Lossless.dll")
        dll.parentFile?.mkdirs()
        assets.open("lsfg/Lossless.dll").use { i -> dll.outputStream().use { o -> i.copyTo(o) } }
        cache.deleteRecursively()
        cache.mkdirs()
        val r = Native.extract(dll.path, cache.path)
        if (r.contains("RESULT: OK")) marker.writeText("ok")
        return r
    }

    companion object {
        const val RUN = 1
        const val ACK = 2
        const val DONE = 3
    }
}

object Paths {
    fun lsfgDir(c: Context) = File(c.filesDir, "lsfg").also { it.mkdirs() }
    fun cacheDir(c: Context) = File(lsfgDir(c), "cache").path
}

/** 从内置视频里取连续的 n 帧,缩放成 w×h 的 RGBA8,存成一个原始文件(同分辨率只做一次)。 */
object FrameSource {
    fun ensure(c: Context, w: Int, h: Int, n: Int, asset: String = "test.mp4", startSec: Double = 1.0, fps: Double = 30.0): String {
        val out = File(c.cacheDir, "frames_${asset}_${startSec}_${w}x${h}_$n.rgba")
        // 电脑推送的原始帧优先(adb push 到 Android/data/<包名>/files/frames/ 同名文件):两台设备的输入逐字节相同,逐级比对才有意义
        val pushed = c.getExternalFilesDir("frames")?.let { File(it, out.name) }
        if (pushed != null && pushed.isFile && pushed.length() == w.toLong() * h * 4 * n) return pushed.path
        if (out.isFile && out.length() == w.toLong() * h * 4 * n) return out.path
        val video = File(c.cacheDir, asset)
        if (!video.isFile) c.assets.open(asset).use { i -> video.outputStream().use { o -> i.copyTo(o) } }
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(video.path)
        val tmp = File(out.path + ".tmp")
        tmp.outputStream().buffered(1 shl 20).use { os ->
            val buf = ByteBuffer.allocate(w * h * 4)
            for (i in 0 until n) {
                // 从 1 秒处开始取连续帧(30fps → 每帧 33333µs)
                val t = (startSec * 1_000_000L).toLong() + (i * 1_000_000.0 / fps).toLong()
                var bmp = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                    ?: throw IllegalStateException("取第 $i 帧失败(t=${t}µs)")
                if (bmp.config != Bitmap.Config.ARGB_8888 || bmp.width != w || bmp.height != h) {
                    val s = Bitmap.createScaledBitmap(bmp.copy(Bitmap.Config.ARGB_8888, false), w, h, true)
                    bmp = s
                }
                buf.clear()
                bmp.copyPixelsToBuffer(buf)
                os.write(buf.array(), 0, buf.capacity())
                bmp.recycle()
            }
        }
        mmr.release()
        tmp.renameTo(out)
        return out.path
    }
}
