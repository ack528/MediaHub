package com.localtg.probe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var drivers: TextView
    private lateinit var btnFull: Button
    private lateinit var btnQuick: Button
    private lateinit var btnStop: Button
    private lateinit var btnTune: Button
    private lateinit var btnDump: Button
    private lateinit var btnPlay: Button
    private lateinit var btnReal: Button
    private lateinit var btnStill: Button
    private lateinit var btnGt: Button
    private lateinit var btnRef: Button
    private var playQueue = ArrayDeque<String>()
    private lateinit var btnShare: Button
    private val log = StringBuilder()
    private var runner: Runner? = null
    private var reportDir: File? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(40), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#101418"))
        }
        root.addView(TextView(this).apply {
            text = "天玑 GPU 适配检测 ${BuildInfo.VERSION}"
            textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "测试 Vulkan / 光流补帧(LSFG)在这台手机 GPU 上能不能跑、跑多快。每项在独立进程里执行,崩溃不会中断检测。完整检测约 3~8 分钟,期间请保持屏幕常亮、不要切走。"
            textSize = 12f; setTextColor(Color.parseColor("#9AA5B1")); setPadding(0, dp(4), 0, dp(8))
        })
        drivers = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#7FD1AE")) }
        root.addView(drivers)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnFull = btn("完整检测") { start(Plan.FULL) }
        btnQuick = btn("快速检测") { start(Plan.QUICK) }
        btnTune = btn("补帧调参") { start(Plan.TUNE) }
        btnDump = btn("导出输出") { start(Plan.DUMP) }
        btnPlay = btn("回放录制") { startPlayback() }
        btnReal = btn("真实片段") { start(Plan.REAL) }
        btnStill = btn("静止测试") { start(Plan.STILL) }
        btnGt = btn("多点验证") { start(Plan.GT) }
        btnRef = btn("参照对比") { start(Plan.GTREF) }
        btnStop = btn("停止") { runner?.cancel() }.apply { isEnabled = false }
        row1.addView(btnGt, lp()); row1.addView(btnRef, lp()); row1.addView(btnFull, lp()); row1.addView(btnQuick, lp()); row1.addView(btnTune, lp()); row1.addView(btnStop, lp())
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnShare = btn("分享报告") { share() }.apply { isEnabled = false }
        row2.addView(btnDump, lp())
        row2.addView(btnPlay, lp())
        row2.addView(btnReal, lp())
        row2.addView(btnStill, lp())
        row2.addView(btnShare, lp())
        row2.addView(btn("复制日志") { copy() }, lp())
        row2.addView(btn("导入驱动") { importDriver() }, lp())
        root.addView(row2)

        status = TextView(this).apply { text = "就绪"; textSize = 13f; setTextColor(Color.parseColor("#FFD479")); setPadding(0, dp(6), 0, dp(4)) }
        root.addView(status)
        scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 10.5f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#D7DEE6")); setTextIsSelectable(true)
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshDrivers()
    }

    private fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun btn(t: String, click: () -> Unit) = Button(this).apply {
        text = t; textSize = 13f; isAllCaps = false; gravity = Gravity.CENTER
        setOnClickListener { click() }
    }

    private fun refreshDrivers() {
        val d = Plan.customDrivers(this)
        drivers.text = "系统驱动" + if (d.isEmpty()) "(未放入自定义驱动;自定义 Vulkan 驱动 .so 可用「导入驱动」或放到 ${getExternalFilesDir("drivers")?.path})"
        else " + 自定义驱动: ${d.joinToString { it.name }}"
    }

    private fun start(mode: Int) {
        log.setLength(0)
        logView.text = ""
        btnFull.isEnabled = false; btnQuick.isEnabled = false; btnTune.isEnabled = false; btnDump.isEnabled = false; btnPlay.isEnabled = false; btnReal.isEnabled = false; btnStill.isEnabled = false; btnGt.isEnabled = false; btnRef.isEnabled = false; btnStop.isEnabled = true; btnShare.isEnabled = false
        val r = Runner(this,
            onLog = { s -> runOnUiThread { append(s) } },
            onProgress = { s -> runOnUiThread { status.text = s } },
            onDone = { dir ->
                runOnUiThread {
                    reportDir = dir
                    btnFull.isEnabled = true; btnQuick.isEnabled = true; btnTune.isEnabled = true; btnDump.isEnabled = true; btnPlay.isEnabled = true; btnReal.isEnabled = true; btnStill.isEnabled = true; btnGt.isEnabled = true; btnRef.isEnabled = true; btnStop.isEnabled = false; btnShare.isEnabled = true
                    status.text = "完成。点「分享报告」把日志发给我(也可以 adb pull ${dir.path})"
                }
            })
        runner = r
        r.start(mode)
    }

    private fun append(s: String) {
        log.append(s).append('\n')
        val shown = if (log.length > 60000) "…(前面已省略,完整内容在报告文件里)\n" + log.substring(log.length - 60000) else log.toString()
        logView.text = shown
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val f = reportDir?.let { File(it, "report.txt") }
        val text = if (f != null && f.isFile) f.readText() else log.toString()
        cm.setPrimaryClip(ClipData.newPlainText("probe", text))
        Toast.makeText(this, "已复制 ${text.length} 字", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val dir = reportDir ?: return
        val zip = File(getExternalFilesDir(null), "${dir.name}.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            dir.listFiles()?.forEach { f ->
                z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享检测报告"))
    }

    /** 回放测试:内置视频用真实补帧管线播放,内录上屏帧;依次跑 低功耗 / 标准 / 不补帧。结果在 files/autotest/ 下。 */
    private fun startPlayback() {
        playQueue = ArrayDeque(listOf("lsfg_low", "lsfg", "off"))
        status.text = "回放录制中…(屏幕会自动播放,请不要操作)"
        log.setLength(0); logView.text = ""
        nextPlayback()
    }

    private fun nextPlayback() {
        val m = playQueue.removeFirstOrNull()
        if (m == null) {
            status.text = "回放录制完成:adb pull ${getExternalFilesDir("autotest")?.path}"
            append("回放录制完成,输出在 ${getExternalFilesDir("autotest")?.path}/{lsfg_low,lsfg,off}/(out.mp4 是上屏录像,app*.log 是补帧详细日志)")
            return
        }
        append("开始:模式 $m")
        startActivityForResult(Intent(this, PlaybackTestActivity::class.java).putExtra("mode", m).putExtra("out", m).putExtra("segs", "31:10,64:12"), 30)
    }

    private fun importDriver() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30) {
            val d = File(getExternalFilesDir("autotest"), "")
            append("  模式结束(结果码 $resultCode)")
            nextPlayback()
            return
        }
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "driver.so"
        if (!name.endsWith(".so")) { Toast.makeText(this, "只支持 .so 文件", Toast.LENGTH_LONG).show(); return }
        val dst = File(File(filesDir, "drivers").also { it.mkdirs() }, name)
        contentResolver.openInputStream(uri)?.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        dst.setReadable(true, false); dst.setExecutable(true, false)
        Toast.makeText(this, "已导入 $name", Toast.LENGTH_SHORT).show()
        refreshDrivers()
    }
}
