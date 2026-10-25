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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
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
    var feed by remember { mutableStateOf(c.viewerFeed) }
    val items = feed.items
    if (items.isEmpty()) { onBack(); return }
    val cfg = LocalSettings.current
    val slideBarColor = remember(cfg.slideshowBarColor) { Color(com.localtg.data.slideBarArgb(cfg.slideshowBarColor)) }
    val ctx = LocalContext.current
    val act = remember(ctx) { ctx.findActivity() }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val ui = remember { ViewerUi() }
    val scope = rememberCoroutineScope()
    val inPip by c.inPip.collectAsState()
    val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    var slideReady by remember { mutableStateOf<String?>(null) }
    var advancing by remember { mutableStateOf(false) }
    var waitingForNext by remember { mutableStateOf(false) }
    var videoEnded by remember { mutableStateOf<String?>(null) }
    var startJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val photoCountdown = remember { androidx.compose.animation.core.Animatable(1f) }
    var countdownItem by remember { mutableStateOf<String?>(null) }
    var countdownRevision by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val slides = ui.slides
    val namespace = c.session.ns()
    fun notify(text: String) { android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show() }
    suspend fun advanceSlide(manual: Boolean = false) {
        if (!slides.active || advancing || slides.preparing) return
        advancing = true
        val expectedFeed = feed
        try {
            val next = pager.settledPage + 1
            // Empty or duplicate pages are not proof of exhaustion. Bound one attempt, then allow retry.
            var attempts = 0
            while (next >= items.size && !feed.exhausted && attempts++ < 3) feed.loadMore()
            if (!slides.active || feed !== expectedFeed || slides.preparing) return
            if (!manual && (ui.menuOpen || ui.touching || pager.isScrollInProgress)) return
            if (next < items.size) {
                videoEnded = null; waitingForNext = false
                slides.ready()
                pager.animateScrollToPage(next)
            } else if (feed.exhausted) {
                slides.end(); ui.chrome = true; notify("本轮已播放完毕")
            } else {
                waitingForNext = true; slides.pause("加载下一项失败，可点击继续重试"); ui.chrome = true; notify("加载下一项失败，可在幻灯片菜单重试")
            }
        } finally { advancing = false }
    }
    androidx.compose.runtime.SideEffect {
        ui.slideSource = feed.slideSource
        ui.startSlides = { options ->
            if (!slides.preparing) {
                val anchor = cur
                val source = feed.slideSource ?: com.localtg.data.SlideSource(
                    com.localtg.data.SlideScope(items.map { it.type }.distinct().joinToString(",")),
                    sequence = com.localtg.data.SlideSequence(items.toList()))
                slides.preparing = true
                startJob = scope.launch {
                    try {
                        val queue = com.localtg.data.createSlideshow(source, com.localtg.data.ApiSlideLibrary(c.api), anchor, options, kotlin.random.Random.nextLong(1, 2147483647))
                        if (c.session.ns() != namespace) return@launch
                        ui.slideResume = if (anchor.isVideo) ui.capturePosition()?.takeIf { it.first == anchor.id } else null
                        pager.requestScrollToPage(0)
                        feed = queue; c.viewerFeed = queue; videoEnded = null; waitingForNext = false
                        slides.start(options)
                        c.settings.update { copy(slideshowSeconds = options.imageSeconds, slideshowOrder = options.order) }
                        ui.dismissMenus++
                        ui.chrome = false
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        val text = if (e is com.localtg.data.ApiException && e.http == 404) "随机播放需要升级服务端到 3.4.1" else com.localtg.data.friendlyError(e)
                        notify(text); AppLog.w("slideshow", "创建播放队列失败", e)
                    } finally { slides.preparing = false }
                }
            }
        }
        ui.stopSlides = { startJob?.cancel(); slides.stop(); videoEnded = null; ui.chrome = true }
        ui.nextSlide = { scope.launch { advanceSlide(true) } }
        ui.toggleSlides = {
            when {
                slides.finished -> scope.launch { pager.scrollToPage(0); videoEnded = null; ui.restartVideo++; slides.start(slides.options!!); ui.chrome = false }
                waitingForNext -> { slides.resume(); scope.launch { advanceSlide(true) } }
                slides.failed -> { ui.chrome = true; notify("当前文件未能加载，可切换下一项") }
                slides.playing -> slides.pause()
                else -> { slides.resume() }
            }
        }
    }
    LaunchedEffect(cur.id) { startJob?.cancel(); slides.preparing = false; ui.photoZoomed = false; ui.menuOpen = false; if (!cur.isVideo) ui.locked = false }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { slides.pause(); startJob?.cancel() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); startJob?.cancel() }
    }
    LaunchedEffect(ui.sleepAt, slides.active) {
        val at = ui.sleepAt
        if (slides.active && at > 0) {
            kotlinx.coroutines.delay((at - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            slides.pause(); ui.sleepAt = 0; notify("睡眠定时到点，已暂停")
        }
    }
    val slideView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(slides.playing) {
        val old = slideView.keepScreenOn
        if (slides.active) slideView.keepScreenOn = slides.playing
        onDispose { slideView.keepScreenOn = old }
    }
    // The animated fraction is both the timer and the display; pausing keeps the remaining fraction.
    LaunchedEffect(cur.id, slideReady, slides.playing, slides.countdownRevision, pager.isScrollInProgress, ui.menuOpen, ui.touching, ui.photoZoomed, slides.preparing) {
        if (countdownItem != cur.id || countdownRevision != slides.countdownRevision) {
            photoCountdown.snapTo(1f)
            countdownItem = cur.id; countdownRevision = slides.countdownRevision
        }
        if (slides.canAdvance(ui.menuOpen, ui.touching, pager.isScrollInProgress, ui.photoZoomed) && !cur.isVideo && slideReady == cur.id) {
            val duration = (com.localtg.data.slideSeconds(slides.options!!.imageSeconds) * 1000f * photoCountdown.value).toInt().coerceAtLeast(1)
            photoCountdown.animateTo(0f, androidx.compose.animation.core.tween(duration, easing = androidx.compose.animation.core.LinearEasing))
            if (ui.sleepAt == -1L) { ui.sleepAt = 0L; slides.pause() } else scope.launch { advanceSlide() }
        }
    }
    LaunchedEffect(cur.id, videoEnded, slides.playing, ui.menuOpen, ui.touching, pager.isScrollInProgress, slides.preparing) {
        if (slides.canAdvance(ui.menuOpen, ui.touching, pager.isScrollInProgress) && videoEnded == cur.id) scope.launch { advanceSlide() }
    }
    LaunchedEffect(cur.id) { if (videoEnded != cur.id) videoEnded = null }
    // Prefetch never runs concurrently with starting/replacing a slideshow queue.
    LaunchedEffect(feed, pager.currentPage, items.size, slides.preparing) {
        if (!slides.preparing && pager.currentPage >= items.size - 5) feed.loadMore()
    }
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
    // The queue owns return policy: group shuffle pins its start; other queues follow the current item.
    LaunchedEffect(feed, cur.id) { feed.returnPosition(cur)?.let { c.viewerReturn.value = it } }

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
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) ui.touching = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.any { it.pressed }
        }
    }) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = if (settled) cfg.viewerPreload else 0, key = { items[it].id }, reverseLayout = reverse,
            userScrollEnabled = !ui.locked && !videoSwipeSeek,
        ) { page ->
            val item = items[page]
            val isCurrent = pager.settledPage == page && LocalViewerPlayerOk.current // 打开动画期间不创建播放器
            if (item.isVideo) {
                VideoPage(
                    item, c, isCurrent, ui, onBack = onBack,
                    onPrev = if (page + (if (slides.active) -1 else prevDelta) in items.indices) { { scope.launch { pager.animateScrollToPage(page + (if (slides.active) -1 else prevDelta)) } } } else null,
                    onNext = if (slides.active && !slides.finished) { { scope.launch { advanceSlide(true) } } } else if (page - prevDelta in items.indices) { { scope.launch { pager.animateScrollToPage(page - prevDelta) } } } else null,
                    onEnded = {
                        if (ui.slideshowMode) {
                            if (ui.sleepAt == -1L) { ui.sleepAt = 0L; slides.pause() }
                            videoEnded = item.id
                        }
                        else if (ui.sleepAt == -1L) { ui.sleepAt = 0L; AppLog.i("player", "睡眠定时:播完停止") }
                        else if (c.settings.value.autoNext && page - prevDelta in items.indices) scope.launch { pager.animateScrollToPage(page - prevDelta) }
                    },
                    onSlideError = { slides.fail("视频无法播放，可重试或跳过"); ui.chrome = true },
                )
            } else {
                PhotoPage(item, c, ui, shared = pager.settledPage == page, onSlideReady = { ok ->
                    if (pager.settledPage == page) {
                        slideReady = item.id
                        if (!ok) { slides.fail("图片加载失败，可跳过"); ui.chrome = true } else slides.ready()
                    }
                })
            }
        }
        // 图片的顶栏 / 底栏:和视频页同一套风格(渐变底、图标按钮、小字号),不再是一整块半透明黑条
        if (!cur.isVideo) {
            var panel by remember { mutableStateOf("") } // 右上角菜单(和视频页同一套):"" 关闭 / main / info
            LaunchedEffect(cur.id, ui.dismissMenus) { panel = "" }
            androidx.compose.runtime.SideEffect { ui.menuOpen = panel.isNotEmpty() }
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
                    if (!slides.active) Column(Modifier.weight(1f)) {
                        Text(formatDateTime(cur.takenAt), color = Color.White, fontSize = 14.sp)
                        Text(
                            listOfNotNull(cur.w?.let { "${cur.w}×${cur.h}" }, if (cur.isVideo) sizeLabel(cur, ui.transcodeBps, 0L, short = true) else formatSize(cur.size), cur.ext.uppercase().takeIf { it.isNotEmpty() }).joinToString(" · "),
                            color = Color(0xB3FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (slides.active) ViewerIcon(if (slides.playing) TgIcons.Pause else TgIcons.Play, if (slides.playing) "暂停幻灯片" else "继续幻灯片") { ui.toggleSlides() }
                    if (!slides.active) Pill("${pager.currentPage + 1} / ${items.size}", sizeSp = 12)
                }
            }
            val lastPage = remember { arrayOf("main") }
            if (panel.isNotEmpty()) lastPage[0] = panel
            val page = panel.ifEmpty { lastPage[0] }
            PlayerMenuHost(
                open = panel.isNotEmpty() && !inPip,
                title = when (page) { "info" -> "图片信息"; "slides" -> "幻灯片播放"; else -> null },
                onBack = { panel = "main" },
                onClose = { panel = "" },
            ) {
                if (page == "slides") {
                    SlideshowMenu(c, ui, feed.slideSource)
                } else if (page == "info") {
                    photoInfoRows(cur).forEach { (k, v) -> MenuInfo(k, v) }
                } else {
                    if (!c.isLocal && cur.size > 0L && !cur.flags.corrupt) {
                        MenuRow("保存到手机", arrow = false) { panel = ""; scope.launch { com.localtg.data.saveToPhoneWithToast(ctx, c.http, c.api, cur) } }
                    }
                    MenuRow("幻灯片播放", slideshowSummary(ui)) { panel = "slides" }
                    MenuRow("图片信息") { panel = "info" }
                }
            }
        }

        if (slides.active && !ui.chrome && !ui.menuOpen && !inPip && !slides.finished && !slides.failed) {
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.TopStart).fillMaxWidth().height(com.localtg.data.slideBarThickness(cfg.slideshowBarThickness).dp)) {
                val remaining = if (cur.isVideo) ui.videoRemaining else photoCountdown.value
                drawRect(slideBarColor, size = androidx.compose.ui.geometry.Size(size.width * remaining.coerceIn(0f, 1f), size.height))
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
private fun PhotoPage(item: Item, c: AppContainer, ui: ViewerUi, shared: Boolean, onSlideReady: (Boolean) -> Unit = {}) {
    val ready = LocalViewerReady.current
    val currentSlideReady by androidx.compose.runtime.rememberUpdatedState(onSlideReady)
    if (!item.canShowNatively()) {
        LaunchedEffect(item.id, shared) { ready(item.id); if (shared) currentSlideReady(false) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂不支持预览 ${item.ext.uppercase()} 格式\n(服务端转换将在后续版本提供)", color = Color.White)
        }
        return
    }
    val cfg = LocalSettings.current
    val zs = me.saket.telephoto.zoomable.rememberZoomableImageState(
        me.saket.telephoto.zoomable.rememberZoomableState(zoomSpec = me.saket.telephoto.zoomable.ZoomSpec(maxZoomFactor = cfg.maxZoom)),
    )
    // 放大了就告诉外层:这时上下拖是平移图片,不是拖拽关闭
    val zoomedNow = (zs.zoomableState.zoomFraction ?: 0f) > 0.01f
    val zoomReport = LocalViewerZoomReport.current
    androidx.compose.runtime.SideEffect { zoomReport(item.id, zoomedNow); if (shared) ui.photoZoomed = zoomedNow }
    // 占位的缩略图或原图画出来了:告诉打开动画可以交接了
    // 最底下先垫上内存里的缩略图(第一帧就有,和打开动画同一张);大图显示出来以后就拿掉
    val based = if (!zs.isImageDisplayed) ViewerBaseThumb(item) else false
    // 垫图、占位的缩略图或原图画出来了:告诉打开动画可以交接了
    val shown = based || zs.isImageDisplayed || zs.isPlaceholderDisplayed
    LaunchedEffect(shown) { if (shown) ready(item.id) }
    LaunchedEffect(zs.isImageDisplayed, shared) { if (shared && zs.isImageDisplayed) currentSlideReady(true) }
    ZoomableAsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(c.api.imageUrl(item, cfg.viewerImageWidth))
            .placeholderMemoryCacheKey(thumbKey(item)) // 先显示点开前的缩略图,不是一片黑
            .listener(onError = { _, _ -> currentSlideReady(false) })
            .build(),
        contentDescription = item.name,
        state = zs,
        modifier = Modifier.fillMaxSize(),
        onClick = { ui.chrome = !ui.chrome },
    )
}
