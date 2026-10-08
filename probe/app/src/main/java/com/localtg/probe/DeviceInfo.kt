package com.localtg.probe

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import java.io.File

/** 不需要 GPU 的设备信息:SoC、系统属性里和 GPU / 驱动相关的项、显示、解码器、温度。 */
object DeviceInfo {
    private fun getprop(): Map<String, String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop"))
        val m = linkedMapOf<String, String>()
        p.inputStream.bufferedReader().forEachLine { line ->
            val r = Regex("""\[(.+?)]: \[(.*)]""").matchEntire(line.trim())
            if (r != null) m[r.groupValues[1]] = r.groupValues[2]
        }
        m
    }.getOrDefault(emptyMap())

    fun thermalText(c: Context): String {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val st = pm.currentThermalStatus
        val name = arrayOf("无", "轻微", "中等", "严重", "危急", "紧急", "关机")[st.coerceIn(0, 6)]
        val head = if (Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN) else Float.NaN
        val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        return "热状态=$name($st) 10秒后热余量=${"%.2f".format(head)} 电池温度=${temp}℃ 电量=$lvl% 省电模式=${pm.isPowerSaveMode}"
    }

    fun collect(c: Context): String {
        val sb = StringBuilder()
        fun l(s: String) { sb.append(s).append('\n') }
        l("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
        l("机型: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) 品牌=${Build.BRAND}")
        l("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) 补丁=${Build.VERSION.SECURITY_PATCH} 构建=${Build.DISPLAY}")
        l("指纹: ${Build.FINGERPRINT}")
        if (Build.VERSION.SDK_INT >= 31) l("SoC: 厂商=${Build.SOC_MANUFACTURER} 型号=${Build.SOC_MODEL}")
        l("硬件=${Build.HARDWARE} 主板=${Build.BOARD} ABI=${Build.SUPPORTED_ABIS.joinToString()}")
        l("内核: " + (runCatching { File("/proc/version").readText().trim() }.getOrDefault("读不到")))
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        l("内存: 总 ${mi.totalMem / 1048576} MB 可用 ${mi.availMem / 1048576} MB  CPU 核数=${Runtime.getRuntime().availableProcessors()}")
        l("温度: ${thermalText(c)}")
        val dm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val disp = c.display
        if (disp != null) {
            val modes = disp.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }
            l("显示: 当前 ${"%.1f".format(disp.refreshRate)}Hz  HDR=${disp.isHdr}  模式: $modes")
        }
        l("屏幕指标: ${dm.currentWindowMetrics.bounds.width()}x${dm.currentWindowMetrics.bounds.height()}")

        val props = getprop()
        val keys = props.keys.filter { k ->
            val lk = k.lowercase()
            listOf("vulkan", "egl", "gpu", "mali", "mtk", "mediatek", "ro.board.platform", "ro.hardware", "ro.soc", "opengles", "hwui", "graphics", "gralloc", "ro.product.vendor", "ro.vendor.build.fingerprint", "ro.boot.hardware")
                .any { lk.contains(it) }
        }.sorted()
        l("系统属性(GPU / 驱动相关 ${keys.size} 项):")
        for (k in keys) l("  $k = ${props[k]}")

        // 厂商驱动文件(读不到就记录读不到)
        for (path in listOf("/vendor/lib64/hw", "/vendor/lib64/egl", "/vendor/lib64", "/system/vendor/lib64/hw", "/vendor/lib/hw")) {
            val f = File(path)
            val names = runCatching { f.list() }.getOrNull()
            if (names == null) { l("目录 $path: 无法列出"); continue }
            val hit = names.filter { n -> listOf("vulkan", "mali", "gles", "egl", "gralloc", "mtk_gpu", "libgpud", "gpu", "libGLES").any { n.lowercase().contains(it) } }.sorted()
            l("目录 $path: ${hit.joinToString().ifEmpty { "(无 GPU 相关文件)" }}")
        }
        for (path in listOf("/vendor/etc/vulkan", "/system/etc/vulkan", "/vendor/etc/permissions")) {
            val names = runCatching { File(path).list() }.getOrNull()
            l("目录 $path: ${names?.joinToString() ?: "无法列出"}")
        }
        // GPU 节点
        for (path in listOf("/dev/mali0", "/dev/dri", "/dev/kgsl-3d0", "/sys/class/misc/mali0/device/gpuinfo", "/sys/class/misc/mali0/device/available_frequencies")) {
            val f = File(path)
            val extra = if (f.isFile && f.canRead()) " 内容: " + runCatching { f.readText().trim().take(200) }.getOrDefault("") else ""
            l("节点 $path: 存在=${f.exists()} 可读=${f.canRead()} 可写=${f.canWrite()}$extra")
        }

        // 解码器
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_VP9)) {
            val decs = list.codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            l("解码器 $mime: " + decs.joinToString { it.name + (if (it.isHardwareAccelerated) "(硬)" else "(软)") })
        }
        return sb.toString()
    }
}
