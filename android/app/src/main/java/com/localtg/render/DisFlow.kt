package com.localtg.render

import android.os.Handler
import android.os.HandlerThread
import com.localtg.AppLog
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.video.DISOpticalFlow

/**
 * 稠密光流(OpenCV 的 DIS —— Dense Inverse Search,Kroeger 等 2016,比 Farneback / LK 快一个数量级,Apache-2.0)。
 *
 * 在专用线程上跑,不阻塞渲染线程:渲染线程把相邻两帧的 1/4 分辨率亮度图交过来,这里算出每像素的运动向量,
 * 再交回渲染线程上传成纹理。算得慢(来不及)时直接丢掉这一对,该对帧退回帧混合 —— 不会卡住画面。
 *
 * 向量约定和块匹配运动场一致:v(p) 定义在"当前帧 B"的像素上,B(p) ≈ A(p − v(p))(单位:传入亮度图的像素)。
 * OpenCV 的 calc(I0, I1) 给出的是 I0(p) ≈ I1(p + g(p)),所以 I0 = B、I1 = A,再取 v = −g。
 */
class DisFlow private constructor(
    private val dis: DISOpticalFlow,
    private val onResult: (id: Long, flow: FloatArray, w: Int, h: Int) -> Unit,
) {
    private val thread = HandlerThread("enh-flow").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var pending = 0
    @Volatile private var released = false

    /** 最近一次计算的耗时(毫秒,指数平滑),用来在界面 / 日志里显示。 */
    @Volatile var avgMs = 0.0; private set

    /** 提交一对帧;worker 忙不过来(积压 ≥ 2 对)就丢掉这一对,返回 false。 */
    fun submit(id: Long, prevLuma: ByteArray, curLuma: ByteArray, w: Int, h: Int): Boolean {
        if (released || pending >= 2) return false
        pending++
        handler.post {
            try {
                if (!released) compute(id, prevLuma, curLuma, w, h)
            } catch (e: Throwable) {
                AppLog.w("enhance", "光流计算失败:${e.message}")
            } finally {
                pending--
            }
        }
        return true
    }

    private fun compute(id: Long, prev: ByteArray, cur: ByteArray, w: Int, h: Int) {
        val t0 = System.nanoTime()
        val mCur = Mat(h, w, CvType.CV_8UC1)
        val mPrev = Mat(h, w, CvType.CV_8UC1)
        val flow = Mat()
        try {
            mCur.put(0, 0, cur)
            mPrev.put(0, 0, prev)
            dis.calc(mCur, mPrev, flow)
            val f = FloatArray(w * h * 2)
            flow.get(0, 0, f)
            for (i in f.indices) f[i] = -f[i]
            val ms = (System.nanoTime() - t0) / 1e6
            avgMs = if (avgMs == 0.0) ms else avgMs * 0.8 + ms * 0.2
            if (!released) onResult(id, f, w, h)
        } finally {
            mCur.release(); mPrev.release(); flow.release()
        }
    }

    fun release() {
        released = true
        handler.post { runCatching { dis.collectGarbage() } }
        thread.quitSafely()
    }

    companion object {
        @Volatile private var loaded: Boolean? = null

        /** OpenCV 原生库能否加载(只在 arm64 / x86_64 包里有;加载失败 = 不支持光流,调用方退回块匹配)。 */
        fun available(): Boolean {
            loaded?.let { return it }
            val ok = try { OpenCVLoader.initLocal() } catch (e: Throwable) { AppLog.w("enhance", "OpenCV 加载失败:${e.message}"); false }
            loaded = ok
            AppLog.i("enhance", "OpenCV 光流库:${if (ok) "可用" else "不可用"}")
            return ok
        }

        fun create(onResult: (Long, FloatArray, Int, Int) -> Unit): DisFlow? {
            if (!available()) return null
            return try {
                val d = DISOpticalFlow.create(DISOpticalFlow.PRESET_FAST)
                DisFlow(d, onResult)
            } catch (e: Throwable) {
                AppLog.w("enhance", "创建 DIS 光流失败:${e.message}"); null
            }
        }

        /** 基准测试用:在合成图上算一次光流,返回耗时(毫秒)。 */
        fun benchOnce(w: Int, h: Int, runs: Int): Double? {
            if (!available()) return null
            return try {
                val d = DISOpticalFlow.create(DISOpticalFlow.PRESET_FAST)
                val a = ByteArray(w * h) { i -> (((i % w) * 5 + (i / w) * 3) and 255).toByte() }
                val b = ByteArray(w * h) { i -> ((((i % w) + 3) * 5 + (i / w) * 3) and 255).toByte() }
                val ma = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, a) }
                val mb = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, b) }
                val flow = Mat()
                d.calc(ma, mb, flow)
                val t0 = System.nanoTime()
                repeat(runs) { d.calc(ma, mb, flow) }
                val ms = (System.nanoTime() - t0) / 1e6 / runs
                ma.release(); mb.release(); flow.release(); d.collectGarbage()
                ms
            } catch (e: Throwable) {
                AppLog.w("enhance", "光流基准测试失败:${e.message}"); null
            }
        }
    }
}
