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
    // ---- 浏览
    val defaultColumns: Int = 3,               // 网格默认列数(各文件夹自己记住的优先)
    val newestAtBottom: Boolean = true,        // 聊天流默认最新在底部
    val rememberPosition: Boolean = true,      // 记住每个文件夹的浏览位置
    val pauseThumbsWhenFast: Boolean = true,   // 快速滑动时暂停加载缩略图
    val showDatePills: Boolean = true,         // 聊天流里显示日期胶囊
    // ---- 图片(重启生效)
    val imageDiskCacheMb: Int = 512,
    val imageMemPercent: Int = 20,
    val hardwareBitmaps: Boolean = true,
    val crossfade: Boolean = true,
    val heicMode: String = "server",           // HEIC / HEIF / AVIF 的显示方式:server = 服务端转换(推荐) / native = 手机直接解码
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
    val maxHeight: Int = 0,                    // 0 = 不限;否则视频最大高度(用于以后的转码 / HLS)
    val maxBitrateMbps: Int = 0,               // 0 = 不限
    val bufferMode: String = "standard",       // small / standard / large
    val audioLanguage: String = "",            // "" = 跟随系统
    val showSubtitles: Boolean = false,
    val subtitleLanguage: String = "",
    // ---- 画质增强(实验):用自己的 OpenGL 渲染器显示视频,可实时超分 / 补帧 / SDR→HDR
    val enhUpscale: String = "off",            // off / fsr(通用)/ anime4k_s(动漫,较快)/ anime4k_m(动漫,画质更好)
    val enhFrc: String = "off",                // off / blend(帧混合)/ mc(运动补偿)
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
    }.getOrNull() ?: AppSettings()

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
class PlaybackStore(private val ctx: Context) {
    private fun k(id: String) = longPreferencesKey("p_$id")

    suspend fun get(id: String): Long = runCatching { ctx.playbackDataStore.data.first()[k(id)] ?: 0L }.getOrDefault(0L)

    suspend fun set(id: String, ms: Long) {
        runCatching { ctx.playbackDataStore.edit { if (ms > 0) it[k(id)] = ms else it.remove(k(id)) } }
    }

    suspend fun count(): Int = runCatching { ctx.playbackDataStore.data.first().asMap().size }.getOrDefault(0)

    suspend fun clearAll() {
        runCatching { ctx.playbackDataStore.edit { it.clear() } }
    }
}
