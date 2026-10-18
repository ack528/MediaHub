package com.localtg.ui

import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.localtg.ui.tg.Pill
import com.localtg.ui.tg.TgIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.request.ImageRequest
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import com.localtg.ui.tg.LocalSettings
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/** 全屏查看器:左右滑动切换;图片可缩放;视频自动开始播放(当前页),带宽只在翻到该页时才使用。 */
@Composable
fun ViewerScreen(c: AppContainer, startIndex: Int, onBack: () -> Unit, onCurrent: (Item) -> Unit = {}) {
    val feed = c.viewerFeed
    val items = feed.items
    if (items.isEmpty()) { onBack(); return }
    val cfg = LocalSettings.current
    val ctx = LocalContext.current
    val act = remember(ctx) { ctx.findActivity() }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val ui = remember { ViewerUi() }
    val scope = rememberCoroutineScope()
    val inPip by c.inPip.collectAsState()
    val cur = items[pager.currentPage]
    // 翻到接近已加载内容的末尾时,继续取更多(聊天历史 / 搜索结果),不会只能翻到第一页
    LaunchedEffect(pager.currentPage, items.size) { if (pager.currentPage >= items.size - 5) feed.loadMore() }
    val hapV = LocalHaptics.current
    LaunchedEffect(pager) { // 翻到另一项时轻震一下(第一次是打开时的初始页,不震)
        var first = true
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { if (first) first = false else hapV.perform(Hap.Tick) }
    }
    // 记住了转码画质:在看当前这一项时,让服务端提前把相邻两个视频的开头转好(切过去几乎秒开)。预热失败 / 服务端不支持都没有影响。
    // 直接播放的邻近视频:提前把开头几 MB 缓存到手机上(设置 → 视频缓存;只在不计流量的网络下),切过去几乎秒开
    val prefetchCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(pager.settledPage, cfg.videoCacheMb) {
        if (cfg.videoCacheMb <= 0 || c.isLocal) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        val page = pager.settledPage
        for (d in intArrayOf(1, -1)) {
            val n = items.getOrNull(page + d) ?: continue
            if (!n.isVideo || n.brokenLabel() != null || rememberedTranscode(c, n) != null) continue
            VideoCache.prefetch(prefetchCtx, c, c.api.fileUrl(n))
        }
    }
    LaunchedEffect(pager.settledPage, cfg.playerQuality, cfg.rememberPlayback, cfg.playerCodec) {
        if (!cfg.rememberPlayback || cfg.playerQuality <= 0 || c.isLocal || !c.api.caps.hlsPrepare) return@LaunchedEffect
        kotlinx.coroutines.delay(800) // 快速连续翻页时别每一页都预热
        val page = pager.settledPage
        for (d in intArrayOf(1, -1)) {
            val n = items.getOrNull(page + d) ?: continue
            val br = rememberedTranscode(c, n) ?: continue
            if (!n.isVideo) continue
            c.api.hlsPrepare(n, br, c.hlsSid(n.id), com.localtg.data.DeviceCodecs.resolveCodec(cfg.playerCodec, c.api.caps.codecs))
        }
    }
    LaunchedEffect(cur.id) { AppLog.i("viewer", "查看 ${cur.name} (${cur.type}, ${pager.currentPage + 1}/${items.size})"); onCurrent(cur) }
    // 每滑到一项就记下来:退出查看器后,群组页定位到最后停留的这一项(不是打开时的那一项)
    LaunchedEffect(cur.id) { feed.originDialogId?.let { c.viewerReturn.value = it to cur.id } }

    // 控制条隐藏 → 沉浸模式(隐藏状态栏和导航栏);离开时恢复
    DisposableEffect(ui.chrome, inPip) {
        val w = act?.window
        if (w != null) {
            val ctl = WindowCompat.getInsetsController(w, w.decorView)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (!ui.chrome || inPip) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            act?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // 横屏视频自动转横屏:翻到别的页时恢复
    LaunchedEffect(cur.id) { if (ui.autoRotated) { ui.autoRotated = false; ui.orientation = 0 } }
    // 屏幕方向(0 自动 / 1 横屏 / 2 竖屏)
    DisposableEffect(ui.orientation) {
        act?.requestedOrientation = when (ui.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    // VLC 方式:视频页水平滑动 = 快进快退,此时翻页靠上一个 / 下一个按钮
    val videoSwipeSeek = cur.isVideo && cfg.gestures && cfg.swipeSeek

    // 打开的转场动画期间只加载当前这一页,动画结束后再预加载左右页:三张大图同时解码 / 上传 GPU 会让转场掉帧(看起来就是闪 / 卡一下)
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); settled = true }
    // 翻页方向:向右滑 = 上一个(设置里可改)。列表序号和屏幕上的先后可能相反(聊天流最新在底部),所以按"上一个是不是序号更大的一项"决定
    val prevDelta = if (feed.prevIsHigherIndex) 1 else -1
    val reverse = feed.prevIsHigherIndex == cfg.rightSwipePrev
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = if (settled) cfg.viewerPreload else 0, key = { items[it].id }, reverseLayout = reverse,
            userScrollEnabled = !ui.locked && !videoSwipeSeek,
        ) { page ->
            val item = items[page]
            val isCurrent = pager.settledPage == page
            if (item.isVideo) {
                VideoPage(
                    item, c, isCurrent, ui, onBack = onBack,
                    onPrev = if (page + prevDelta in items.indices) { { scope.launch { pager.animateScrollToPage(page + prevDelta) } } } else null,
                    onNext = if (page - prevDelta in items.indices) { { scope.launch { pager.animateScrollToPage(page - prevDelta) } } } else null,
                    onEnded = {
                        if (ui.sleepAt == -1L) { ui.sleepAt = 0L; AppLog.i("player", "睡眠定时:播完停止") }
                        else if (c.settings.value.autoNext && page - prevDelta in items.indices) scope.launch { pager.animateScrollToPage(page - prevDelta) }
                    },
                )
            } else {
                PhotoPage(item, c, ui, shared = pager.currentPage == page)
            }
        }
        // 图片的顶栏 / 底栏:和视频页同一套风格(渐变底、图标按钮、小字号),不再是一整块半透明黑条
        if (!cur.isVideo) {
            var panel by remember { mutableStateOf("") } // 右上角菜单(和视频页同一套):"" 关闭 / main / info
            LaunchedEffect(cur.id) { panel = "" }
            AnimatedVisibility(
                rememberChromeState(ui, ui.chrome && !inPip), Modifier.align(Alignment.TopStart),
                enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerIcon(TgIcons.Back, "返回") { onBack() }
                    Text(
                        cur.name, Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    ViewerIcon(TgIcons.More, "更多") { panel = if (panel.isEmpty()) "main" else "" }
                }
            }
            AnimatedVisibility(
                rememberChromeState(ui, ui.chrome && !inPip), Modifier.align(Alignment.BottomStart),
                enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatDateTime(cur.takenAt), color = Color.White, fontSize = 14.sp)
                        Text(
                            listOfNotNull(cur.w?.let { "${cur.w}×${cur.h}" }, if (cur.isVideo) sizeLabel(cur, ui.transcodeBps, 0L, short = true) else formatSize(cur.size), cur.ext.uppercase().takeIf { it.isNotEmpty() }).joinToString(" · "),
                            color = Color(0xB3FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Pill("${pager.currentPage + 1} / ${items.size}", sizeSp = 12)
                }
            }
            val lastPage = remember { arrayOf("main") }
            if (panel.isNotEmpty()) lastPage[0] = panel
            val page = panel.ifEmpty { lastPage[0] }
            PlayerMenuHost(
                open = panel.isNotEmpty() && !inPip,
                title = if (page == "info") "图片信息" else null,
                onBack = { panel = "main" },
                onClose = { panel = "" },
            ) {
                if (page == "info") {
                    photoInfoRows(cur).forEach { (k, v) -> MenuInfo(k, v) }
                } else {
                    if (!c.isLocal && cur.size > 0L && !cur.flags.corrupt) {
                        MenuRow("保存到手机", arrow = false) { panel = ""; scope.launch { com.localtg.data.saveToPhoneWithToast(ctx, c.http, c.api, cur) } }
                    }
                    MenuRow("图片信息") { panel = "info" }
                }
            }
        }
    }
}

/**
 * 顶栏 / 底栏的显示状态。打开查看器时先藏着,等打开动画交接完([LocalViewerShown])再淡入滑入;
 * 之后翻页新出现的页面直接显示(不再每一页都滑一次)。
 */
@Composable
internal fun rememberChromeState(ui: ViewerUi, visible: Boolean): androidx.compose.animation.core.MutableTransitionState<Boolean> {
    val st = remember { androidx.compose.animation.core.MutableTransitionState(ui.chromeIntroDone) }
    st.targetState = visible && LocalViewerShown.current
    if (st.isIdle && st.currentState) ui.chromeIntroDone = true
    return st
}

/** 顶栏上的圆形图标按钮(和视频页的控制按钮同尺寸)。 */
@Composable
private fun ViewerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).pressScale(src, 0.14f).clip(CircleShape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

private fun photoInfoRows(item: Item): List<Pair<String, String>> = listOfNotNull(
    "名称" to item.name,
    "格式" to listOf(item.ext.uppercase(), item.mime).filter { it.isNotEmpty() }.joinToString(" · "),
    item.w?.let { "尺寸" to "${item.w} × ${item.h}" },
    "大小" to "${formatSize(item.size)}(${"%,d".format(item.size)} 字节)",
    formatDateTime(item.takenAt).takeIf { it.isNotEmpty() }?.let { "拍摄" to it },
    formatDateTime(item.modifiedAt).takeIf { it.isNotEmpty() }?.let { "修改" to it },
    formatDateTime(item.createdAt).takeIf { it.isNotEmpty() }?.let { "创建" to it },
).filter { it.second.isNotEmpty() }

@Composable
private fun PhotoPage(item: Item, c: AppContainer, ui: ViewerUi, shared: Boolean) {
    val ready = LocalViewerReady.current
    if (!item.canShowNatively()) {
        LaunchedEffect(item.id) { ready(item.id) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂不支持预览 ${item.ext.uppercase()} 格式\n(服务端转换将在后续版本提供)", color = Color.White)
        }
        return
    }
    val cfg = LocalSettings.current
    val zs = me.saket.telephoto.zoomable.rememberZoomableImageState(
        me.saket.telephoto.zoomable.rememberZoomableState(zoomSpec = me.saket.telephoto.zoomable.ZoomSpec(maxZoomFactor = cfg.maxZoom)),
    )
    // 占位的缩略图或原图画出来了:告诉打开动画可以交接了
    // 最底下先垫上内存里的缩略图(第一帧就有,和打开动画同一张);大图显示出来以后就拿掉
    val based = if (!zs.isImageDisplayed) ViewerBaseThumb(item) else false
    // 垫图、占位的缩略图或原图画出来了:告诉打开动画可以交接了
    val shown = based || zs.isImageDisplayed || zs.isPlaceholderDisplayed
    LaunchedEffect(shown) { if (shown) ready(item.id) }
    ZoomableAsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(c.api.imageUrl(item, cfg.viewerImageWidth))
            .placeholderMemoryCacheKey(thumbKey(item)) // 先显示点开前的缩略图,不是一片黑
            .build(),
        contentDescription = item.name,
        state = zs,
        modifier = Modifier.fillMaxSize(),
        onClick = { ui.chrome = !ui.chrome },
    )
}
