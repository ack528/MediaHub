package com.localtg.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")
private val Context.playbackDataStore by preferencesDataStore(name = "playback_positions")

/**
 * 全部可调设置。字段加默认值,升级时新增字段不会破坏旧数据(解析时忽略未知字段、缺失字段取默认)。
 * 标注"重启生效"的项在创建 HTTP 客户端 / 图片加载器时读取。
 */
@Serializable
data class AppSettings(
    // ---- 外观
    val theme: String = "system",              // system / light / dark
    val chatBackground: String = "gradient",   // gradient / plain
    val motion: String = "slide",              // 界面动画:slide(系统风格,默认) / parallax(整屏滑动+视差) / axis(Material 共享轴) / fade(淡入淡出) / off(关闭)
    val fontScale: Float = 1f,                 // 界面文字缩放(在系统字号之上再乘)
    val avatarShape: String = "round",         // 头像形状:round(圆角方形,默认) / circle / square
    val screenSecure: Boolean = false,         // 禁止截屏 / 录屏,最近任务里不显示画面预览
    // ---- 文件夹列表
    val dialogSort: String = "recent",         // recent(服务端顺序:最近更新) / name / count(媒体数量) / path
    val dialogMinCount: Int = 0,               // 隐藏媒体数少于这个值的文件夹(0 = 不隐藏)
    val dialogSubtitle: String = "last",       // 每行第二行显示:last(最后一项文件名) / path(所在路径) / none
    val avatarCover: Boolean = true,           // 头像用文件夹封面;关闭则只显示首字母
    // ---- 浏览
    val defaultSort: String = "auto",          // 文件夹默认排序:auto(服务器按名称、本地按时间) / name / taken / size / type
    val defaultSortDir: String = "auto",       // auto / asc(升序) / desc(降序)
    val gridSpacing: Int = 1,                  // 网格间距(dp)
    val gridThumbWidth: Int = 480,             // 网格缩略图宽度(像素),越大越清晰也越费流量
    val swipeGroups: Boolean = true,           // 主界面左右滑动切换盘符(全部 / C: / D: …)
    val showVideoName: Boolean = true,         // 视频缩略图左下角显示文件名(小字)
    val showDuration: Boolean = true,          // 视频缩略图上显示时长
    val chatSpacing: Int = 3,                  // 聊天流里相邻气泡的间距(dp)
    val bubbleWidth: Int = 72,                 // 聊天流里媒体气泡占屏幕宽度的百分比
    val chatImageWidth: Int = 960,             // 聊天流里图片的清晰度(像素宽度)
    val defaultColumns: Int = 3,               // 网格默认列数(各文件夹自己记住的优先)
    val newestAtBottom: Boolean = true,        // 聊天流默认最新在底部
    val rememberPosition: Boolean = true,      // 记住每个文件夹的浏览位置
    val preloadCount: Int = 48,                // 滑动 / 跳到某处停下后,预加载附近这么多张缩略图(0 = 关闭)
    val pauseThumbsWhenFast: Boolean = true,   // 快速滑动时暂停加载缩略图
    val showDatePills: Boolean = true,         // 聊天流里显示日期胶囊
    // ---- 图片(重启生效)
    val imageDiskCacheMb: Int = 512,
    val imageMemPercent: Int = 20,
    val hardwareBitmaps: Boolean = true,
    val crossfade: Boolean = true,
    val heicMode: String = "server",           // HEIC / HEIF / AVIF 的显示方式:server = 服务端转换(推荐) / native = 手机直接解码
    // ---- 图片查看器
    val viewerPreload: Int = 1,                // 左右各预加载几页
    val viewerImageWidth: Int = 2880,          // 全屏查看时加载的图片宽度(像素)
    val maxZoom: Float = 2f,
    val rightSwipePrev: Boolean = true,        // 查看器里向右滑 = 上一个(和聊天里"上面那条"一致);关闭则向右滑 = 下一个                   // 双击 / 双指放大的上限
    // ---- 视频播放
    val autoplay: Boolean = true,
    val loop: Boolean = false,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val seekBackSec: Int = 10,
    val seekForwardSec: Int = 10,
    val resume: Boolean = true,                // 记住每个视频的播放进度
    val resizeMode: String = "fit",            // fit / fill / zoom
    val controllerTimeoutSec: Int = 4,
    val keepScreenOn: Boolean = true,
    val swipeSeek: Boolean = true,             // 视频页水平滑动 = 快进快退(VLC 方式);关闭则水平滑动切换上 / 下一项
    val gestures: Boolean = true,              // 手势:双击快进快退、长按倍速、上下滑动调亮度 / 音量
    val longPressSpeed: Float = 2f,            // 长按画面时的临时倍速
    val swipeSpanSec: Int = 90,                // 手指横扫整个屏幕宽度对应的快进秒数
    val gestureSense: Float = 1.3f,            // 上下滑动调亮度 / 音量的灵敏度(滑满屏幕高度变化 N 倍)
    val resumeRewindSec: Int = 0,              // 续播时往回退几秒,方便接上剧情
    val pauseOnUnplug: Boolean = true,         // 拔掉耳机 / 蓝牙断开时暂停
    val autoLandscape: Boolean = false,        // 横向视频自动转横屏
    val volumeBoostMb: Int = 0,                // 音量增益(毫贝,1000 = +10 dB),0 = 关闭
    val subtitleScale: Float = 1f,
    val subtitleStyle: String = "system",      // system / outline(描边) / shadow(阴影) / box(黑底) / yellow(黄字描边)
    val subtitleBottom: Int = 8,               // 字幕离底部的距离(占画面高度的百分比)
    val autoNext: Boolean = false,             // 播放完自动切到下一项
    val autoPip: Boolean = false,              // 离开应用时自动进入小窗播放
    val showStats: Boolean = false,            // 播放时显示解码器 / 码率等技术信息
    // ---- 编解码器
    val decoderMode: String = "auto",          // auto / hw_first / sw_first / hw_only / sw_only
    val autoSoftwareFallback: Boolean = true,  // 解码失败时自动改用软解重试
    val decoderFallback: Boolean = true,       // 首选解码器初始化失败时尝试下一个
    val autoTranscode: Boolean = true,         // 手机解不了(格式 / 编码不支持)时,自动改用服务端转码播放
    val asyncQueueing: String = "auto",        // auto / on / off:MediaCodec 异步队列
    val tunneling: Boolean = false,            // 隧道播放(部分电视 / 手机的硬解可降低功耗,不稳定)
    val maxBitrateMbps: Int = 0,               // 服务端转码的视频码率(Mbps),0 = 自动(按原分辨率);转码只改码率,不改分辨率
    val bufferMode: String = "standard",       // small / standard / large
    val audioLanguage: String = "",            // "" = 跟随系统
    val showSubtitles: Boolean = false,
    val subtitleLanguage: String = "",
    // ---- 画质增强(实验):用自己的 OpenGL 渲染器显示视频,可实时超分 / 补帧 / SDR→HDR
    val enhUpscale: String = "off",            // off / fsr(通用)/ anime4k_s(动漫,较快)/ anime4k_m(动漫,画质更好)
    val enhFrc: String = "off",                // off / lsfg(LSFG 标准:补到屏幕最高刷新率)/ lsfg_low(LSFG 低功耗:补到 60fps)
    val enhFrcMultiplier: Int = 0,             // 标准模式的补帧倍率:0 = 自动(补到屏幕刷新率),2 ~ 5 = 固定倍数(源 24fps × 3 = 72fps)
    val hwReport: String = "",                 // 最近一次"硬件支持检测"的结果(JSON)
    val lsfgFlowScale: Float = 0.5f,           // LSFG 内部光流精度(越小越快,画质略降)
    val lsfgFp32: Boolean = false,             // LSFG 强制 FP32 着色器(默认 FP16 优先,失败自动退回)
    val lsfgPerf: Boolean = true,              // LSFG 性能模式(3.1P;低功耗模式强制开启)
    val frcTrace: Boolean = true,              // 补帧详细日志(每 2 秒一组汇总 + 异常事件,排查卡顿用)
    val enhFrcAdaptive: Boolean = true,        // 补帧跟不上时自动降低光流精度,最低还不够就停用
    val enhFpsOverlay: Boolean = true,         // 补帧时在画面右上角显示 源帧率 → 输出帧率
    val enhHdr: String = "off",                // off / auto(显示器支持 HDR 才启用)/ on
    val enhPeak: Int = 600,                    // SDR→HDR 的高光峰值亮度(nit)
    val enhMaxH: Int = 900,                    // 视频高度超过这个值不做超分
    // ---- 日志
    val logLevel: String = "info",             // off / warn / info / debug
    // ---- 网络(重启生效)
    val connectTimeoutSec: Int = 5,
    val readTimeoutSec: Int = 30,
)

class SettingsStore(private val ctx: Context, private val scope: CoroutineScope) {
    private val key = stringPreferencesKey("v1")

    /** 启动时同步读一次(文件很小),这样图片加载器和 HTTP 客户端创建时就能用上用户的设置。 */
    val state = MutableStateFlow(loadBlocking())

    private fun loadBlocking(): AppSettings = runCatching {
        runBlocking { ctx.settingsDataStore.data.first()[key]?.let { AppJson.decodeFromString<AppSettings>(it) } }
    }.getOrNull()?.let { s ->
        // 补帧只保留 LSFG:旧版本的 blend / mc* / flow 等值一律回到"关闭"
        // 非高通处理器不支持补帧:一律关闭
        if (s.enhFrc in setOf("off", "lsfg", "lsfg_low") && (s.enhFrc == "off" || com.localtg.render.Lsfg.deviceSupported)) s else s.copy(enhFrc = "off")
    } ?: AppSettings()

    val value: AppSettings get() = state.value

    fun update(block: AppSettings.() -> AppSettings) {
        val old = state.value
        val n = old.block()
        state.value = n
        com.localtg.AppLog.setLevel(n.logLevel)
        runCatching {
            val a = old.toString().substringAfter('(').dropLast(1).split(", ")
            val b = n.toString().substringAfter('(').dropLast(1).split(", ")
            val diff = b.filterIndexed { i, v -> a.getOrNull(i) != v }
            if (diff.isNotEmpty()) com.localtg.AppLog.i("settings", "变更: " + diff.joinToString(", "))
        }
        scope.launch { runCatching { ctx.settingsDataStore.edit { it[key] = AppJson.encodeToString(n) } } }
    }

    fun reset() = update { AppSettings() }
}

/** 每个视频上次播放到哪里(毫秒)。 */
class PlaybackStore(private val ctx: Context, private val ns: () -> String = { "" }) {
    private fun k(id: String, prefix: String) = longPreferencesKey("p_$prefix$id")
    private fun kt(id: String, prefix: String) = longPreferencesKey("pt_$prefix$id") // 这条记录的更新时间,和服务器上的比较谁更新

    suspend fun get(id: String, prefix: String = ns()): Long = runCatching { ctx.playbackDataStore.data.first()[k(id, prefix)] ?: 0L }.getOrDefault(0L)

    /** (进度毫秒, 更新时间毫秒);没有记录是 (0, 0)。 */
    suspend fun getWithTime(id: String, prefix: String = ns()): Pair<Long, Long> = runCatching {
        val d = ctx.playbackDataStore.data.first()
        (d[k(id, prefix)] ?: 0L) to (d[kt(id, prefix)] ?: 0L)
    }.getOrDefault(0L to 0L)

    suspend fun set(id: String, ms: Long, prefix: String = ns(), at: Long = System.currentTimeMillis()) {
        // 进度为 0(看完 / 重新开始)也要记下来并带上时间,换设备时才知道"这是最新的状态"
        runCatching { ctx.playbackDataStore.edit { it[k(id, prefix)] = ms; it[kt(id, prefix)] = at } }
    }

    suspend fun count(): Int = runCatching { ctx.playbackDataStore.data.first().asMap().keys.count { it.name.startsWith("p_") } }.getOrDefault(0)

    suspend fun clearAll() {
        runCatching { ctx.playbackDataStore.edit { it.clear() } }
    }
}
