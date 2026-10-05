package com.localtg

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 应用内日志:写到 files/logs/app.log(单文件 1 MB,保留 3 份),崩溃时另存 crash-*.txt
 * (含堆栈、设备信息、最近的应用日志和系统 logcat 尾部)。
 * 设置 → 日志与诊断 里可查看、复制、打包分享。
 */
object AppLog {
    /** 级别:0 关闭,1 仅错误与警告,2 信息,3 调试(详细)。 */
    const val OFF = 0
    const val WARN = 1
    const val INFO = 2
    const val DEBUG = 3

    val LEVELS = listOf("off" to OFF, "warn" to WARN, "info" to INFO, "debug" to DEBUG)

    @Volatile private var level = INFO
    private lateinit var dir: File
    private lateinit var appCtx: Context
    private var ready = false
    private const val MAX_BYTES = 1_000_000L
    private const val KEEP = 3
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "applog").apply { isDaemon = true } }
    private val stamp get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context, levelName: String) {
        appCtx = ctx.applicationContext
        dir = File(appCtx.filesDir, "logs").apply { mkdirs() }
        setLevel(levelName)
        ready = true
    }

    fun setLevel(name: String) {
        level = LEVELS.firstOrNull { it.first == name }?.second ?: INFO
    }

    fun d(tag: String, msg: String) = log(DEBUG, 'D', tag, msg, null)
    fun i(tag: String, msg: String) = log(INFO, 'I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(WARN, 'W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(WARN, 'E', tag, msg, t)

    private fun log(need: Int, c: Char, tag: String, msg: String, t: Throwable?) {
        when (c) { 'D' -> Log.d(tag, msg); 'I' -> Log.i(tag, msg); 'W' -> Log.w(tag, msg, t); else -> Log.e(tag, msg, t) }
        if (!ready || level < need) return
        val line = buildString {
            append(stamp.format(Date())).append(' ').append(c).append('/').append(tag).append(": ").append(msg)
            if (t != null) append('\n').append(stackText(t))
            append('\n')
        }
        exec.execute { append(line) }
    }

    private fun append(text: String) {
        runCatching {
            val f = File(dir, "app.log")
            if (f.length() > MAX_BYTES) rotate()
            f.appendText(text)
        }
    }

    private fun rotate() {
        for (i in KEEP - 1 downTo 1) {
            val from = File(dir, if (i == 1) "app.log" else "app.${i - 1}.log")
            val to = File(dir, "app.$i.log")
            if (from.exists()) { to.delete(); from.renameTo(to) }
        }
    }

    fun stackText(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    // ---------------------------------------------------------------- 读取 / 清理

    /** 所有应用日志文件,旧 → 新。 */
    fun logFiles(): List<File> =
        (KEEP - 1 downTo 1).map { File(dir, "app.$it.log") }.plus(File(dir, "app.log")).filter { it.exists() }

    fun crashFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 最近的应用日志文本(最多 maxBytes 字节,取末尾)。 */
    fun tail(maxBytes: Int = 200_000): String {
        flush()
        val all = logFiles().joinToString("") { runCatching { it.readText() }.getOrDefault("") }
        return if (all.length > maxBytes) all.substring(all.length - maxBytes) else all
    }

    fun clear() {
        flush()
        dir.listFiles()?.forEach { if (it.name != "crash.pending") it.delete() }
    }

    /** 等队列里已提交的写入完成。 */
    private fun flush() {
        runCatching { exec.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    // ---------------------------------------------------------------- 设备信息

    fun deviceInfo(): String = buildString {
        val pm = appCtx.packageManager
        val pi = runCatching { pm.getPackageInfo(appCtx.packageName, 0) }.getOrNull()
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = appCtx.resources.displayMetrics
        append("应用: ").append(appCtx.packageName).append(' ').append(pi?.versionName).append(" (").append(pi?.longVersionCode).append(")\n")
        append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / ").append(Build.DEVICE).append('\n')
        append("系统: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ").append(Build.DISPLAY).append('\n')
        append("CPU ABI: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append("内存: 总 ").append(mi.totalMem shr 20).append(" MB, 可用 ").append(mi.availMem shr 20).append(" MB, 应用上限 ")
            .append(am.memoryClass).append(" MB\n")
        append("屏幕: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" @").append(dm.densityDpi).append("dpi\n")
        append("语言: ").append(Locale.getDefault()).append('\n')
        append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
    }

    /** 本应用进程的 logcat 尾部(系统日志里和本应用相关的部分,含 ExoPlayer / Coil 等库输出)。 */
    private fun logcatTail(lines: Int = 400): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", lines.toString(), "-v", "threadtime").redirectErrorStream(true).start()
        var out = ""
        val th = Thread { out = p.inputStream.bufferedReader().readText() }
        th.start()
        th.join(3000)
        p.destroy()
        out
    }.getOrDefault("(无法读取 logcat)")

    // ---------------------------------------------------------------- 崩溃

    fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching { writeCrash(thread, t) }
            prev?.uncaughtException(thread, t)
        }
    }

    private fun writeCrash(thread: Thread, t: Throwable) {
        Log.e("Crash", "未捕获异常 @ ${thread.name}", t)
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val body = buildString {
            append("=== 崩溃报告 ===\n").append(deviceInfo()).append("线程: ").append(thread.name).append("\n\n")
            append(stackText(t)).append("\n\n=== 最近的应用日志 ===\n")
            append(tail(60_000)).append("\n=== logcat 尾部 ===\n").append(logcatTail())
        }
        File(dir, name).writeText(body)
        File(dir, "crash.pending").writeText(name)
        // 只保留最近 10 份崩溃报告
        crashFiles().dropLast(10).forEach { it.delete() }
    }

    /** 上次是否崩溃过且用户还没看过。 */
    fun pendingCrash(): File? = runCatching {
        val n = File(dir, "crash.pending").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        File(dir, n).takeIf { n.isNotEmpty() && it.exists() }
    }.getOrNull()

    fun dismissPendingCrash() { File(dir, "crash.pending").delete() }

    fun crashSummary(f: File): String = runCatching {
        f.readLines().firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim().orEmpty()
    }.getOrDefault("")

    // ---------------------------------------------------------------- 导出

    /** 复制到剪贴板:设备信息 + 最近日志(+ 最近一次崩溃)。可直接粘贴发给开发者。 */
    fun copyRecent(ctx: Context): Int {
        val text = buildString {
            append(deviceInfo()).append('\n')
            crashFiles().lastOrNull()?.let { append("=== 最近一次崩溃 ===\n").append(it.readText().take(30_000)).append("\n\n") }
            append("=== 应用日志(最近) ===\n").append(tail(80_000))
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("本地浏览日志", text))
        return text.length
    }

    /** 打包成 zip(设备信息、应用日志、崩溃报告、logcat),用系统分享面板发出去。 */
    fun share(ctx: Context) {
        val out = File(ctx.cacheDir, "shared").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val zip = File(out, "localbrowse-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip")
        flush()
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("device.txt", deviceInfo().toByteArray())
            logFiles().forEach { put(it.name, it.readBytes()) }
            crashFiles().forEach { put(it.name, it.readBytes()) }
            put("logcat.txt", logcatTail(1500).toByteArray())
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".logs", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "本地浏览 日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
