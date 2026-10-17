package com.localtg.data

import android.os.SystemClock
import com.localtg.AppContainer
import com.localtg.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 手机 ↔ 服务器的网络测速(设置 → 网络测速)。三项:
 *  - 延迟:连续发 12 个很小的请求(沿用同一个连接),取最小 / 平均 / 最大 / 抖动 / 丢包;
 *  - 下载:服务端持续发送不可压缩的随机数据,手机边收边算速度,**至少持续 15 秒**(前几秒是 TCP 慢启动,不能代表稳定速度);
 *  - 上传:手机持续上传随机数据,最终以服务端实际收到的字节数和耗时为准(手机端的发送缓冲会让开头的速度虚高)。
 * 速度单位 Mbps(兆比特 / 秒);括号里换算成 MB/s。数据不读写磁盘,量的是手机到服务器整条链路(含云中继)。
 */
class SpeedTest(private val c: AppContainer) {
    class Latency(val samples: List<Long>, val lost: Int) {
        val min get() = samples.minOrNull() ?: 0L
        val max get() = samples.maxOrNull() ?: 0L
        val avg get() = if (samples.isEmpty()) 0.0 else samples.average()
        /** 抖动:相邻两次延迟之差的平均值。 */
        val jitter get() = if (samples.size < 2) 0.0 else samples.zipWithNext { a, b -> kotlin.math.abs(b - a).toDouble() }.average()
    }

    /** 一次下载 / 上传的结果。avg = 全程平均;stable = 去掉前 2 秒(慢启动)后的平均;peak = 任意 1 秒窗口的最高速度。 */
    class Flow(val bytes: Long, val ms: Long, val avgMbps: Double, val stableMbps: Double, val peakMbps: Double)

    private val base: String get() = c.session.session.value?.baseUrl ?: error("未登录")
    private val client by lazy { c.http.newBuilder().readTimeout(40, TimeUnit.SECONDS).writeTimeout(40, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build() }
    private val pingClient by lazy { c.apiHttp.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

    suspend fun latency(n: Int = 12, onSample: (index: Int, ms: Long?) -> Unit): Latency = withContext(Dispatchers.IO) {
        val ok = mutableListOf<Long>()
        var lost = 0
        for (i in 0 until n) {
            if (!isActive) break
            val t0 = System.nanoTime()
            val ms = try {
                pingClient.newCall(Request.Builder().url("$base/api/v1/speedtest/ping").build()).execute().use { r ->
                    if (r.code == 404) throw ApiException(404, "notfound", "服务端不支持测速(请更新服务端)")
                    if (!r.isSuccessful) null else (System.nanoTime() - t0) / 1_000_000
                }
            } catch (e: ApiException) { throw e
            } catch (e: IOException) { null }
            if (ms == null) lost++ else ok += ms
            onSample(i, ms)
            delay(120)
        }
        Latency(ok, lost)
    }

    private fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / 1000.0 / ms // 字节 × 8 / 毫秒 / 1000 = Mbps

    /**
     * 滑动窗口(1 秒)取瞬时速度,每 500ms 回调一次 onTick(已用毫秒, 瞬时 Mbps, 平均 Mbps)。
     * 计数器由各个连接的线程累加;返回时给出最终统计。
     */
    private suspend fun sample(secs: Int, counter: AtomicLong, t0: Long, onTick: (Long, Double, Double) -> Unit): Triple<Long, Double, Long> = coroutineScope {
        // 返回 (2 秒时的字节数, 峰值 Mbps, 总字节)
        val window = ArrayDeque<Pair<Long, Long>>() // (毫秒, 字节)
        var bytesAt2s = -1L
        var peak = 0.0
        while (isActive) {
            delay(500)
            val el = SystemClock.elapsedRealtime() - t0
            val b = counter.get()
            window.addLast(el to b)
            while (window.size > 2 && el - window.first().first > 1000) window.removeFirst()
            val inst = window.first().let { mbps(b - it.second, el - it.first) }
            if (el >= 2000 && bytesAt2s < 0) bytesAt2s = b
            if (el >= 1500 && inst > peak) peak = inst
            onTick(el, inst, mbps(b, el))
            if (el >= secs * 1000L) break
        }
        Triple(bytesAt2s, peak, counter.get())
    }

    /** 下载测速。seconds 至少 15;threads = 1(单连接,最接近播放视频)或 4(多连接,测最大带宽)。 */
    suspend fun download(seconds: Int, threads: Int, onTick: (elapsedMs: Long, instMbps: Double, avgMbps: Double) -> Unit): Flow = withContext(Dispatchers.IO) {
        val secs = maxOf(15, seconds)
        val total = AtomicLong(0)
        val calls = ConcurrentLinkedQueue<Call>()
        val t0 = SystemClock.elapsedRealtime()
        coroutineContext.job.invokeOnCompletion { calls.forEach { it.cancel() } } // 用户点停止 / 离开页面:立刻断开
        var res: Triple<Long, Double, Long>? = null
        try {
            coroutineScope {
                val workers = (1..threads).map {
                    launch(Dispatchers.IO) {
                        val call = client.newCall(Request.Builder().url("$base/api/v1/speedtest/down?seconds=${secs + 2}").build())
                        calls.add(call)
                        try {
                            call.execute().use { r ->
                                if (r.code == 404) throw ApiException(404, "notfound", "服务端不支持测速(请更新服务端)")
                                if (!r.isSuccessful) throw ApiException(r.code, "http.${r.code}", "HTTP ${r.code}")
                                val ins = r.body.byteStream()
                                val buf = ByteArray(64 * 1024)
                                while (isActive && SystemClock.elapsedRealtime() - t0 < secs * 1000L) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    total.addAndGet(n.toLong())
                                }
                            }
                        } catch (e: IOException) {
                            if (isActive && SystemClock.elapsedRealtime() - t0 < secs * 1000L - 500) throw e // 到点主动断开的不算错
                        }
                    }
                }
                res = sample(secs, total, t0, onTick)
                calls.forEach { it.cancel() }
                workers.forEach { it.cancel() }
            }
        } catch (e: CancellationException) { throw e }
        val ms = SystemClock.elapsedRealtime() - t0
        val (at2, peak, bytes) = res ?: Triple(-1L, 0.0, total.get())
        val stable = if (at2 >= 0 && ms > 2500) mbps(bytes - at2, ms - 2000) else mbps(bytes, ms)
        AppLog.i("speedtest", "下载 ${threads} 连接 ${ms}ms ${bytes / 1024}KB 平均 ${"%.1f".format(mbps(bytes, ms))} 稳定 ${"%.1f".format(stable)} 峰值 ${"%.1f".format(peak)} Mbps")
        NetEstimate.set((stable * 1_000_000).toLong()) // 测速量到的稳定下载速度 = 最可靠的网速估计,自动画质据此判断
        Flow(bytes, ms, mbps(bytes, ms), stable, peak)
    }

    private class FlowBody(val secs: Int, val counter: AtomicLong, val t0: Long) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun writeTo(sink: BufferedSink) {
            val buf = ByteArray(64 * 1024).also { java.util.Random(7).nextBytes(it) }
            while (SystemClock.elapsedRealtime() - t0 < secs * 1000L) {
                sink.write(buf)
                sink.flush()
                counter.addAndGet(buf.size.toLong())
            }
        }
    }

    /** 上传测速:最终速度以服务端量到的为准(它收到的字节数 / 它计的耗时)。 */
    suspend fun upload(seconds: Int, threads: Int, onTick: (elapsedMs: Long, instMbps: Double, avgMbps: Double) -> Unit): Flow = withContext(Dispatchers.IO) {
        val secs = maxOf(15, seconds)
        val sent = AtomicLong(0)
        val serverBytes = AtomicLong(0)
        val serverMs = AtomicLong(0)
        val calls = ConcurrentLinkedQueue<Call>()
        val t0 = SystemClock.elapsedRealtime()
        coroutineContext.job.invokeOnCompletion { calls.forEach { it.cancel() } }
        var res: Triple<Long, Double, Long>? = null
        coroutineScope {
            val workers = (1..threads).map {
                launch(Dispatchers.IO) {
                    val call = client.newCall(Request.Builder().url("$base/api/v1/speedtest/up?seconds=$secs").post(FlowBody(secs, sent, t0)).build())
                    calls.add(call)
                    try {
                        call.execute().use { r ->
                            if (r.code == 404) throw ApiException(404, "notfound", "服务端不支持测速(请更新服务端)")
                            if (!r.isSuccessful) throw ApiException(r.code, "http.${r.code}", "HTTP ${r.code}")
                            val j = JSONObject(r.body.string())
                            serverBytes.addAndGet(j.optLong("bytes"))
                            serverMs.updateAndGet { maxOf(it, j.optLong("ms")) }
                        }
                    } catch (e: IOException) {
                        if (isActive) throw e
                    }
                }
            }
            res = sample(secs, sent, t0, onTick)
            workers.forEach { it.join() }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val (at2, peak, clientBytes) = res ?: Triple(-1L, 0.0, sent.get())
        // 服务端量到的:总字节 / 最长的那一路耗时;量不到(服务端没回)就用手机端的
        val bytes = if (serverBytes.get() > 0) serverBytes.get() else clientBytes
        val sms = if (serverMs.get() > 0) serverMs.get() else ms
        val stable = if (at2 >= 0 && ms > 2500) mbps(clientBytes - at2, ms - 2000).coerceAtMost(mbps(bytes, sms) * 1.5) else mbps(bytes, sms)
        AppLog.i("speedtest", "上传 ${threads} 连接 服务端 ${sms}ms ${bytes / 1024}KB 平均 ${"%.1f".format(mbps(bytes, sms))} Mbps")
        Flow(bytes, sms, mbps(bytes, sms), stable, peak)
    }
}
