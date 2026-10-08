package com.localtg.probe

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class Status(val label: String) { OK("通过"), FAIL("失败"), CRASH("进程崩溃"), TIMEOUT("超时(GPU 可能卡死)") }

data class Result(val spec: TestSpec, val status: Status, val text: String, val ms: Long, val medianMs: Double?, val verdict: String?)

/** 依次在 :probe 进程里跑每个测试,日志实时写文件;原生崩溃 / 超时不会中断后面的测试。 */
class Runner(
    private val ctx: Context,
    private val onLog: (String) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onDone: (File) -> Unit,
) {
    @Volatile private var cancelled = false
    @Volatile private var curPid = 0
    private val replyThread = HandlerThread("probe-reply").also { it.start() }
    val results = mutableListOf<Result>()
    lateinit var dir: File; private set
    private lateinit var report: File

    fun cancel() {
        cancelled = true
        if (curPid != 0) Process.killProcess(curPid)
    }

    fun start(mode: Int) {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        dir = File(ctx.getExternalFilesDir("reports"), "run-$ts").also { it.mkdirs() }
        report = File(dir, "report.txt")
        Thread({ runAll(mode) }, "probe-runner").start()
    }

    private fun emit(s: String) {
        report.appendText(s + "\n")
        onLog(s)
    }

    private fun runAll(mode: Int) {
        try {
            emit("######## 天玑GPU检测 ${BuildInfo.VERSION} ########")
            emit(DeviceInfo.collect(ctx))
            val drivers = Plan.customDrivers(ctx)
            emit("自定义驱动: " + if (drivers.isEmpty()) "无(只测系统驱动)" else drivers.joinToString { "${it.name}(${it.length() / 1024}KB)" })
            val plan = Plan.build(ctx, mode).toMutableList()
            var i = 0
            var extractOk = true
            while (i < plan.size) {
                if (cancelled) { emit("--- 已取消 ---"); break }
                val spec = plan[i]
                i++
                onProgress("[$i/${plan.size}] ${spec.title}")
                if (spec.test == "lsfg" && !extractOk) {
                    emit("\n=== [$i/${plan.size}] ${spec.title} ===\n跳过:着色器提取失败\n")
                    results += Result(spec, Status.FAIL, "跳过", 0, null, null)
                    continue
                }
                emit("\n=== [$i/${plan.size}] ${spec.title} ===")
                emit("温度: " + DeviceInfo.thermalText(ctx))
                val r = execute(spec)
                results += r
                if (spec.test == "extract" && r.status != Status.OK) extractOk = false
                emit(r.text.trimEnd())
                emit("→ ${r.status.label}  用时 ${r.ms} ms")
            }
            if (!cancelled && mode != Plan.DUMP && mode != Plan.REAL && mode != Plan.STILL && mode != Plan.GT && mode != Plan.GTREF) {
                // 持续测试:挑一个"能跑且最大"的配置
                val best = results.filter { it.spec.test == "lsfg" && it.status == Status.OK && it.medianMs != null && it.medianMs < 30 && it.verdict == "正常" }
                    .maxByOrNull { it.spec.w * it.spec.h }
                if (best != null) {
                    val s = Plan.sustained(best.spec)
                    onProgress(s.title)
                    emit("\n=== ${s.title} ===")
                    emit("温度(前): " + DeviceInfo.thermalText(ctx))
                    val r = execute(s)
                    results += r
                    emit(r.text.trimEnd())
                    emit("温度(后): " + DeviceInfo.thermalText(ctx))
                    emit("→ ${r.status.label}  用时 ${r.ms} ms")
                } else emit("\n(没有可用于持续测试的配置:没有任何 LSFG 配置同时满足 通过 + 中位<30ms + 输出正常)")
            }
            emit("\n######## 汇总 ########")
            for (r in results) {
                val extra = buildString {
                    if (r.medianMs != null) append("  生成中位 ${"%.1f".format(r.medianMs)} ms")
                    if (r.verdict != null) append("  输出:${r.verdict}")
                }
                emit("[${r.status.label}] ${r.spec.title}$extra")
            }
            emit("报告目录: ${dir.path}")
        } catch (t: Throwable) {
            emit("检测流程异常: $t")
        } finally {
            onProgress("完成")
            onDone(dir)
        }
    }

    private fun execute(spec: TestSpec): Result {
        val queue = LinkedBlockingQueue<Any>()
        var messenger: Messenger? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) { messenger = Messenger(b); queue.put("connected") }
            override fun onServiceDisconnected(n: ComponentName) { queue.put("died") }
            override fun onBindingDied(n: ComponentName) { queue.put("died") }
        }
        val t0 = System.currentTimeMillis()
        val thumb = File(ctx.cacheDir, "thumb_${spec.id}.raw").also { it.delete() }
        var pid = 0
        var text: String? = null
        var status = Status.OK
        try {
            ctx.bindService(Intent(ctx, ProbeService::class.java), conn, Context.BIND_AUTO_CREATE)
            if (queue.poll(20, TimeUnit.SECONDS) != "connected") return Result(spec, Status.FAIL, "无法连接检测进程", 0, null, null)
            val reply = Messenger(object : Handler(replyThread.looper) {
                override fun handleMessage(m: Message) {
                    when (m.what) {
                        ProbeService.ACK -> queue.put("pid:${m.arg1}")
                        ProbeService.DONE -> queue.put("done:" + m.data.getString("text").orEmpty())
                    }
                }
            })
            val b = Bundle().apply {
                putString("test", spec.test); putString("driver", spec.driver)
                putInt("w", spec.w); putInt("h", spec.h); putFloat("flow", spec.flow); putInt("variant", spec.variant)
                putBoolean("perf", spec.perf); putInt("gen", spec.gen); putInt("iters", spec.iters); putInt("frames", spec.frames); putFloat("pace", spec.paceMs)
                putString("dump", if (spec.dumpW > 0) File(dumpDir(), "dump_${spec.id}.yuv").path else ""); putInt("dumpW", spec.dumpW); putInt("dumpH", spec.dumpH); putInt("flags", spec.flags)
                putString("thumb", thumb.path); putString("asset", spec.asset); putDouble("start", spec.startSec); putDouble("fps", spec.fps)
            }
            messenger!!.send(Message.obtain(null, ProbeService.RUN).also { it.data = b; it.replyTo = reply })
            val deadline = System.currentTimeMillis() + spec.timeoutSec * 1000L
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val m = if (left > 0) queue.poll(left, TimeUnit.MILLISECONDS) else null
                if (m == null) { status = Status.TIMEOUT; break }
                val s = m as String
                when {
                    s.startsWith("pid:") -> { pid = s.substring(4).toInt(); curPid = pid }
                    s.startsWith("done:") -> { text = s.substring(5); break }
                    s == "died" -> { status = Status.CRASH; break }
                }
            }
        } catch (t: Throwable) {
            status = Status.FAIL
            text = "客户端异常: $t"
        }
        val tail = StringBuilder()
        if (status == Status.CRASH || status == Status.TIMEOUT) tail.append(exitInfo(pid))
        if (text != null && status == Status.OK) status = if (text.contains("RESULT: OK")) Status.OK else Status.FAIL
        if (status != Status.OK || spec.test == "lsfg") tail.append(logcat(pid, if (status == Status.OK) 40 else 120))
        try { ctx.unbindService(conn) } catch (_: Throwable) {}
        if (pid != 0) Process.killProcess(pid) // 每个测试都用全新进程:互不污染,也能测到冷启动
        curPid = 0
        Thread.sleep(400)

        val body = buildString {
            append(text ?: "(没有输出:${status.label})")
            if (tail.isNotEmpty()) append("\n").append(tail)
        }
        if (thumb.isFile) savePng(thumb, File(dir, "thumb_${spec.id}.png"))
        var median: Double? = null
        var verdict: String? = null
        if (text != null) {
            Regex("""生成耗时.*?中位 ([\d.]+) ms""").find(text)?.let { median = it.groupValues[1].toDoubleOrNull() }
            Regex("""输出判定: (.+)""").find(text)?.let { verdict = it.groupValues[1].trim() }
        }
        return Result(spec, status, body, System.currentTimeMillis() - t0, median, verdict)
    }

    /** 导出的原始 I420 帧序列放在这里(很大,不进报告 zip,用 adb pull 取)。 */
    private fun dumpDir(): File = ctx.getExternalFilesDir("dumps")!!.also { it.mkdirs() }

    private fun exitInfo(pid: Int): String {
        if (pid == 0) return ""
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = am.getHistoricalProcessExitReasons(ctx.packageName, pid, 1)
            if (infos.isEmpty()) return "[退出信息] 系统没有记录 pid=$pid\n"
            val i = infos[0]
            val reason = when (i.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃(CRASH_NATIVE)"
                ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
                ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                else -> "reason=${i.reason}"
            }
            val sb = StringBuilder("[退出信息] pid=$pid $reason status=${i.status} 描述=${i.description}\n")
            if (i.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                // 墓碑是 protobuf,不好解析;抽出里面的可读字符串(库路径、函数名、信号、故障地址)
                val bytes = i.traceInputStream?.use { it.readBytes() }
                if (bytes != null) sb.append("[墓碑可读字符串]\n").append(printable(bytes)).append('\n')
            }
            sb.toString()
        }.getOrDefault("[退出信息] 读取失败\n")
    }

    private fun printable(b: ByteArray): String {
        val out = StringBuilder()
        val cur = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 32..126) cur.append(c.toChar())
            else {
                if (cur.length >= 6) out.append(cur).append('\n')
                cur.setLength(0)
            }
            if (out.length > 9000) break
        }
        return out.toString()
    }

    private fun logcat(pid: Int, max: Int): String {
        if (pid == 0) return ""
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "*:W"))
            val lines = p.inputStream.bufferedReader().readLines().filter { !it.startsWith("---") }
            // 连续重复的行(时间戳之外内容相同)折叠成 "×N",否则模拟器 / 驱动的刷屏会淹没有用信息
            val folded = mutableListOf<String>()
            var prevKey = ""
            var count = 1
            var first = ""
            for (ln in lines) {
                val key = if (ln.length > 30) ln.substring(30) else ln
                if (key == prevKey && folded.isNotEmpty()) {
                    count++
                    folded[folded.size - 1] = "$first  ×$count"
                } else { folded += ln; first = ln; prevKey = key; count = 1 }
            }
            if (folded.isEmpty()) "" else "[logcat 警告/错误 末尾${minOf(max, folded.size)}行]\n" + folded.takeLast(max).joinToString("\n") + "\n"
        }.getOrDefault("")
    }

    private fun savePng(raw: File, png: File) {
        runCatching {
            val bb = ByteBuffer.wrap(raw.readBytes()).order(java.nio.ByteOrder.nativeOrder())
            val w = bb.getInt(); val h = bb.getInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(bb.slice())
            png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            emit("(已保存对比图 ${png.name}:左=前一帧 中=生成帧 右=后一帧)")
        }
    }
}

object BuildInfo { const val VERSION = "0.9.7" }

