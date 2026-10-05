package com.localtg.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import com.localtg.AppContainer
import com.localtg.AppLog
import androidx.compose.foundation.lazy.items
import com.localtg.data.AppSettings
import com.localtg.ui.tg.BarIcon
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgBar
import com.localtg.ui.tg.TgIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页分类:id → (标题, 简介)。 */
private val SECTIONS = listOf(
    Triple("appearance", "外观", "主题、聊天背景"),
    Triple("browse", "浏览", "网格列数、浏览位置、缩略图加载"),
    Triple("image", "图片", "缓存大小、位图、过渡动画"),
    Triple("video", "视频播放", "自动播放、循环、倍速、进度记忆"),
    Triple("codec", "编解码器", "硬解 / 软解、缓冲、分辨率、音轨字幕"),
    Triple("network", "网络", "连接与读取超时"),
    Triple("storage", "存储与缓存", "清理缓存、记录,恢复默认设置"),
    Triple("account", "服务器与账号", "当前服务器、退出登录"),
    Triple("log", "日志与诊断", "日志级别、查看、复制、分享给开发者"),
    Triple("about", "关于", "版本信息"),
)

@Composable
fun SettingsScreen(c: AppContainer, section: String?, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val tg = LocalTg.current
    val s by c.settings.state.collectAsState()
    val title = when (section) {
        null -> "设置"
        "codecs-info" -> "本机解码器"
        "log-view" -> "应用日志"
        else -> SECTIONS.firstOrNull { it.first == section }?.second ?: "设置"
    }
    Column(Modifier.fillMaxSize()) {
        TgBar {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(TgIcons.Back, "返回", onBack)
                Text(title, color = tg.barText, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            }
        }
        val update: (AppSettings.() -> AppSettings) -> Unit = { c.settings.update(it) }
        when (section) {
            "codecs-info" -> CodecInfoPage()
            "log-view" -> LogViewPage()
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                when (section) {
                    null -> SECTIONS.forEach { (id, name, desc) ->
                        NavRow(name, desc) { onOpen(id) }
                    }
                    "appearance" -> AppearancePage(s, update)
                    "browse" -> BrowsePage(s, update)
                    "image" -> ImagePage(s, update)
                    "video" -> VideoPage(s, update)
                    "codec" -> CodecPage(s, update, onOpenCodecs = { onOpen("codecs-info") })
                    "network" -> NetworkPage(s, update)
                    "storage" -> StoragePage(c)
                    "account" -> AccountPage(c)
                    "log" -> LogPage(s, update, onOpenView = { onOpen("log-view") })
                    "about" -> AboutPage(c)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 各分类页面

@Composable
private fun AppearancePage(s: AppSettings, u: (AppSettings.() -> AppSettings) -> Unit) {
    Header("主题")
    ChoiceRow("颜色主题", s.theme, listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")) { u { copy(theme = it) } }
    Header("动画")
    ChoiceRow(
        "界面动画", s.motion,
        listOf("slide" to "滑动 + 视差(默认)", "axis" to "Material 共享轴", "fade" to "仅淡入淡出", "off" to "关闭动画"),
        desc = "页面前进 / 返回的过渡;打开图片时缩略图会直接放大成全屏(共享元素),支持系统的预测性返回手势",
    ) { u { copy(motion = it) } }
    Header("聊天流")
    ChoiceRow("聊天背景", s.chatBackground, listOf("gradient" to "渐变(Telegram 默认)", "plain" to "纯色")) { u { copy(chatBackground = it) } }
    SwitchRow("显示日期胶囊", "在聊天流里按天分隔,显示“今天 / 昨天 / 日期”", s.showDatePills) { u { copy(showDatePills = it) } }
}

@Composable
private fun BrowsePage(s: AppSettings, u: (AppSettings.() -> AppSettings) -> Unit) {
    Header("打开文件夹")
    SwitchRow("记住浏览位置", "再次打开同一个文件夹时回到上次退出的位置,并记住排序、网格 / 聊天、过滤", s.rememberPosition) { u { copy(rememberPosition = it) } }
    SwitchRow("最新在底部", "聊天流默认旧 → 新排列,最新一条在最下面(和 Telegram 一样)", s.newestAtBottom) { u { copy(newestAtBottom = it) } }
    Header("网格")
    ChoiceRow("默认列数", s.defaultColumns, (2..6).map { it to "$it 列" }, desc = "已经设置过的文件夹仍用它自己的列数") { u { copy(defaultColumns = it) } }
    Header("加载")
    SwitchRow("快速滑动时暂停加载缩略图", "滑得很快时先不请求图片,停下后再加载,省流量也更流畅", s.pauseThumbsWhenFast) { u { copy(pauseThumbsWhenFast = it) } }
}

@Composable
private fun ImagePage(s: AppSettings, u: (AppSettings.() -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    RestartNote()
    Header("缓存")
    ChoiceRow(
        "磁盘缓存上限", s.imageDiskCacheMb,
        listOf(128, 256, 512, 1024, 2048, 4096).map { it to if (it >= 1024) "${it / 1024} GB" else "$it MB" },
        desc = "已看过的缩略图和图片保存在手机里,再次浏览不用重新下载",
    ) { u { copy(imageDiskCacheMb = it) } }
    ChoiceRow(
        "内存缓存比例", s.imageMemPercent, listOf(10, 15, 20, 25, 30, 40).map { it to "$it%" },
        desc = "占应用可用内存的比例;大相册快速回滚时越大越流畅",
    ) { u { copy(imageMemPercent = it) } }
    Header("显示")
    SwitchRow("硬件位图", "更省内存、绘制更快;个别机型出现花屏时关闭", s.hardwareBitmaps) { u { copy(hardwareBitmaps = it) } }
    SwitchRow("淡入动画", "图片加载完成时渐显", s.crossfade) { u { copy(crossfade = it) } }
    RestartRow(ctx)
}

@Composable
private fun VideoPage(s: AppSettings, u: (AppSettings.() -> AppSettings) -> Unit) {
    Header("播放")
    SwitchRow("自动播放", "翻到视频页时自动开始播放", s.autoplay) { u { copy(autoplay = it) } }
    SwitchRow("循环播放", "播放结束后从头再播", s.loop) { u { copy(loop = it) } }
    SwitchRow("默认静音", null, s.muted) { u { copy(muted = it) } }
    ChoiceRow("默认倍速", s.speed, listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { it to "${it}x" }) { u { copy(speed = it) } }
    SwitchRow("记住播放进度", "下次打开同一个视频从上次的位置继续;看到结尾附近则从头开始", s.resume) { u { copy(resume = it) } }
    Header("手势与小窗")
    SwitchRow("手势控制", "双击左 / 右侧快退 / 快进,双击中间暂停;长按倍速;左半屏上下滑调亮度,右半屏调音量", s.gestures) { u { copy(gestures = it) } }
    SwitchRow("水平滑动快进快退", "视频里左右滑动调整进度(和 VLC 一样);关闭后左右滑动切换上 / 下一项", s.swipeSeek) { u { copy(swipeSeek = it) } }
    ChoiceRow("长按倍速", s.longPressSpeed, listOf(1.5f, 2f, 3f, 4f).map { it to "${it}x" }, desc = "按住画面时临时加速,松手恢复") { u { copy(longPressSpeed = it) } }
    SwitchRow("播放完自动下一个", "视频播放结束后自动翻到下一项", s.autoNext) { u { copy(autoNext = it) } }
    SwitchRow("离开应用时自动小窗", "正在播放时按 Home 键,自动进入画中画小窗", s.autoPip) { u { copy(autoPip = it) } }
    Header("快进快退")
    ChoiceRow("快退", s.seekBackSec, listOf(5, 10, 15, 30).map { it to "$it 秒" }) { u { copy(seekBackSec = it) } }
    ChoiceRow("快进", s.seekForwardSec, listOf(5, 10, 15, 30).map { it to "$it 秒" }) { u { copy(seekForwardSec = it) } }
    Header("画面")
    ChoiceRow("画面缩放", s.resizeMode, listOf("fit" to "适应屏幕(留黑边)", "zoom" to "裁剪填满", "fill" to "拉伸填满")) { u { copy(resizeMode = it) } }
    ChoiceRow("控制条自动隐藏", s.controllerTimeoutSec, listOf(2, 3, 4, 5, 8, 10).map { it to "$it 秒" }) { u { copy(controllerTimeoutSec = it) } }
    SwitchRow("播放时保持屏幕常亮", null, s.keepScreenOn) { u { copy(keepScreenOn = it) } }
    SwitchRow("显示技术信息", "在画面角落显示解码器、格式、码率、丢帧数,排查卡顿时有用", s.showStats) { u { copy(showStats = it) } }
}

@Composable
private fun CodecPage(s: AppSettings, u: (AppSettings.() -> AppSettings) -> Unit, onOpenCodecs: () -> Unit) {
    NavRow("本机解码器", "查看这台手机支持哪些格式、是不是硬件解码", onOpenCodecs)
    Header("解码器选择")
    ChoiceRow(
        "解码方式", s.decoderMode, DECODER_MODES,
        desc = "“仅硬件 / 仅软件”不会回退到另一种,遇到不支持的格式会直接报错",
    ) { u { copy(decoderMode = it) } }
    SwitchRow("解码失败自动改软解", "硬件解码器报错时,自动换成软件解码从当前位置重试", s.autoSoftwareFallback) { u { copy(autoSoftwareFallback = it) } }
    SwitchRow("允许备用解码器", "首选解码器初始化失败时依次尝试同格式的其他解码器", s.decoderFallback) { u { copy(decoderFallback = it) } }
    ChoiceRow(
        "MediaCodec 异步队列", s.asyncQueueing, listOf("auto" to "自动", "on" to "强制开启", "off" to "强制关闭"),
        desc = "部分机型开启后丢帧更少;个别机型会花屏,可强制关闭",
    ) { u { copy(asyncQueueing = it) } }
    SwitchRow("隧道播放", "把解码和显示交给硬件直通,可能更省电并提升 4K / HDR 流畅度;部分机型不稳定", s.tunneling) { u { copy(tunneling = it) } }
    Header("缓冲")
    ChoiceRow(
        "缓冲大小", s.bufferMode,
        listOf("small" to "小(约 15 秒,省流量)", "standard" to "标准(约 50 秒)", "large" to "大(约 120 秒,Wi-Fi 不稳时用)"),
    ) { u { copy(bufferMode = it) } }
    Header("画质限制(用于转码 / 自适应码率)")
    ChoiceRow(
        "最大分辨率", s.maxHeight, listOf(0 to "不限", 2160 to "2160p (4K)", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p"),
        desc = "对直接播放原文件不起作用;转码功能上线后用来限制输出画质",
    ) { u { copy(maxHeight = it) } }
    ChoiceRow(
        "最大码率", s.maxBitrateMbps, listOf(0 to "不限", 40 to "40 Mbps", 20 to "20 Mbps", 10 to "10 Mbps", 5 to "5 Mbps", 2 to "2 Mbps"),
        desc = "同上",
    ) { u { copy(maxBitrateMbps = it) } }
    Header("音轨与字幕")
    val langs = listOf("" to "跟随系统", "zh" to "中文", "en" to "English", "ja" to "日本語", "ko" to "한국어")
    ChoiceRow("首选音轨语言", s.audioLanguage, langs, desc = "文件里有多条音轨时优先选这种语言") { u { copy(audioLanguage = it) } }
    SwitchRow("显示内嵌字幕", "播放有内嵌字幕轨的 MKV / MP4 时显示字幕", s.showSubtitles) { u { copy(showSubtitles = it) } }
    ChoiceRow("首选字幕语言", s.subtitleLanguage, langs) { u { copy(subtitleLanguage = it) } }
}

@Composable
private fun NetworkPage(s: AppSettings, u: (AppSettings.() -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    RestartNote()
    Header("超时")
    ChoiceRow("连接超时", s.connectTimeoutSec, listOf(3, 5, 10, 20).map { it to "$it 秒" }, desc = "连不上服务器时多久放弃") { u { copy(connectTimeoutSec = it) } }
    ChoiceRow("读取超时", s.readTimeoutSec, listOf(15, 30, 60, 120).map { it to "$it 秒" }, desc = "硬盘休眠唤醒较慢时可调大") { u { copy(readTimeoutSec = it) } }
    RestartRow(ctx)
}

@Composable
private fun StoragePage(c: AppContainer) {
    val ctx = LocalContext.current
    var cacheBytes by remember { mutableStateOf(-1L) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) {
        cacheBytes = withContext(Dispatchers.IO) { SingletonImageLoader.get(ctx).diskCache?.size ?: 0L }
    }
    Header("缓存")
    ActionRow(
        "清除图片缓存", if (cacheBytes >= 0) "当前占用 ${formatSize(cacheBytes)}" else "计算中…",
        confirm = "删除手机里已缓存的缩略图和图片?之后需要重新从服务器下载。",
    ) {
        withContext(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(ctx)
            loader.diskCache?.clear(); loader.memoryCache?.clear()
        }
        msg = "图片缓存已清除"
    }
    Header("记录")
    ActionRow("清除浏览位置记录", "所有文件夹下次打开都从最新一端开始", confirm = "清除所有文件夹的浏览位置?") {
        c.viewState.clearAll(); msg = "浏览位置已清除"
    }
    ActionRow("清除播放进度记录", "所有视频下次都从头播放", confirm = "清除所有视频的播放进度?") {
        c.playback.clearAll(); msg = "播放进度已清除"
    }
    Header("设置")
    ActionRow("恢复默认设置", "不影响登录状态和浏览记录", confirm = "把所有设置恢复为默认值?", danger = true) {
        c.settings.reset(); msg = "已恢复默认设置"
    }
    msg?.let { Text(it, color = LocalTg.current.accent, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
}

@Composable
private fun AccountPage(c: AppContainer) {
    val session by c.session.session.collectAsState()
    val scope = rememberCoroutineScope()
    Header("当前服务器")
    InfoRow("地址", session?.baseUrl ?: "未登录")
    Header("账号")
    ActionRow("退出登录", "清除这台手机上的登录令牌,服务器地址会保留", confirm = "退出登录?", danger = true) {
        scope.launch { c.api.logout(); c.session.clearToken() }
    }
}

@Composable
private fun LogPage(s: AppSettings, u: (AppSettings.() -> AppSettings) -> Unit, onOpenView: () -> Unit) {
    val ctx = LocalContext.current
    var size by remember { mutableStateOf(AppLog.sizeBytes()) }
    var msg by remember { mutableStateOf<String?>(null) }
    Header("记录")
    ChoiceRow(
        "日志级别", s.logLevel,
        listOf("off" to "关闭", "warn" to "仅错误与警告", "info" to "信息(默认)", "debug" to "调试(详细,含每个网络请求)"),
        desc = "遇到问题时先切到“调试”,把问题重现一次,再分享日志",
    ) { u { copy(logLevel = it) } }
    NavRow("查看日志", "已占用 ${formatSize(size)}", onOpenView)
    Header("发给开发者")
    ActionRow("分享日志文件", "打包成 zip:设备信息、应用日志、崩溃报告、系统日志,用系统分享发出") {
        runCatching { AppLog.share(ctx) }.onFailure { msg = "分享失败:${it.message}" }
    }
    ActionRow("复制最近日志到剪贴板", "设备信息 + 最近一次崩溃 + 最近的日志,可直接粘贴到聊天里") {
        val n = AppLog.copyRecent(ctx)
        msg = "已复制 $n 字"
    }
    Header("崩溃报告")
    val crashes = remember(size) { AppLog.crashFiles() }
    InfoRow("崩溃记录", if (crashes.isEmpty()) "无" else "${crashes.size} 份,最近一次:${crashes.last().name}")
    Header("清理")
    ActionRow("清除日志", "删除应用日志和崩溃报告", confirm = "清除所有日志和崩溃报告?", danger = true) {
        AppLog.clear(); size = AppLog.sizeBytes(); msg = "日志已清除"
    }
    msg?.let { Text(it, color = LocalTg.current.accent, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
}

@Composable
private fun LogViewPage() {
    val tg = LocalTg.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var onlyWarn by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { lines = withContext(Dispatchers.IO) { AppLog.tail(300_000).lines() } }
    val all = lines
    val shown = remember(all, onlyWarn) { if (all == null) emptyList() else if (onlyWarn) all.filter { it.contains(" W/") || it.contains(" E/") || it.startsWith(" ") || it.startsWith("	at ") } else all }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onlyWarn = !onlyWarn }) { Text(if (onlyWarn) "仅警告和错误 ✓" else "全部") }
            TextButton(onClick = { tick++ }) { Text("刷新") }
            Text("${shown.size} 行", color = tg.message, fontSize = 12.sp)
        }
        if (all == null) Text("读取中…", color = tg.message, modifier = Modifier.padding(16.dp))
        else if (shown.isEmpty()) Text("还没有日志", color = tg.message, modifier = Modifier.padding(16.dp))
        else androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = listState) {
            items(shown.size) { i ->
                val l = shown[i]
                val c = when {
                    l.contains(" E/") -> Color(0xFFE53935)
                    l.contains(" W/") -> Color(0xFFF59E0B)
                    else -> tg.name
                }
                Text(l, color = c, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun AboutPage(c: AppContainer) {
    val ctx = LocalContext.current
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    var server by remember { mutableStateOf("读取中…") }
    LaunchedEffect(Unit) {
        val base = c.session.session.value?.baseUrl
        server = if (base == null) "未连接" else runCatching { c.api.serverInfo(base).let { "${it.name} ${it.version}" } }.getOrElse { "无法连接" }
    }
    Header("本地浏览")
    InfoRow("应用版本", ver)
    InfoRow("服务端", server)
    InfoRow("系统", "Android ${android.os.Build.VERSION.RELEASE}(API ${android.os.Build.VERSION.SDK_INT})")
    InfoRow("设备", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
}

@Composable
private fun CodecInfoPage() {
    val tg = LocalTg.current
    var groups by remember { mutableStateOf<List<CodecGroup>?>(null) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.Default) { probeDeviceCodecs() } }
    val list = groups
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        if (list == null) {
            Text("读取中…", color = tg.message, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Text(
            "绿色 = 硬件解码,灰色 = 软件解码。没有解码器的格式手机无法直接播放(之后可由服务器转码)。",
            color = tg.message, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
        )
        list.forEach { g ->
            Header(g.label + if (g.decoders.isEmpty()) "    不支持" else "")
            g.decoders.forEach { d ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(if (d.hardware) Color(0xFF4EC95E) else Color(0xFF9AA5B1))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) { Text(if (d.hardware) "硬件" else "软件", color = Color.White, fontSize = 11.sp) }
                        Text(d.name, color = tg.name, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (d.detail.isNotEmpty()) Text(d.detail, color = tg.message, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 通用行组件

@Composable
private fun Header(text: String) {
    Text(
        text, color = LocalTg.current.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun NavRow(title: String, desc: String, onClick: () -> Unit) {
    val tg = LocalTg.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = tg.name, fontSize = 16.sp)
            Text(desc, color = tg.message, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(TgIcons.ChevronRight, null, tint = tg.message, modifier = Modifier.size(22.dp))
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = tg.divider)
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tg = LocalTg.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = tg.name, fontSize = 16.sp)
            if (desc != null) Text(desc, color = tg.message, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, value: T, options: List<Pair<T, String>>, desc: String? = null, onSelect: (T) -> Unit) {
    val tg = LocalTg.current
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: value.toString()
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, color = tg.name, fontSize = 16.sp)
        Text(label, color = tg.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        if (desc != null) Text(desc, color = tg.message, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (v, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(v); open = false }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v == value, onClick = { onSelect(v); open = false })
                            Text(name, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ActionRow(title: String, desc: String, confirm: String? = null, danger: Boolean = false, action: suspend () -> Unit) {
    val tg = LocalTg.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { if (confirm != null) ask = true else scope.launch { action() } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = if (danger) Color(0xFFE53935) else tg.name, fontSize = 16.sp)
        Text(desc, color = tg.message, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(title) }, text = { Text(confirm.orEmpty()) },
            confirmButton = { TextButton(onClick = { ask = false; scope.launch { action() } }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val tg = LocalTg.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, color = tg.message, fontSize = 13.sp)
        Text(value, color = tg.name, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun RestartNote() {
    Text(
        "本页的设置在重启应用后生效。",
        color = LocalTg.current.message, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
}

@Composable
private fun RestartRow(ctx: android.content.Context) {
    ActionRow("立即重启应用", "让上面的设置马上生效") {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) {
            ctx.startActivity(i)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
